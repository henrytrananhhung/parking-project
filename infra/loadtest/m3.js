/*
  M3 local performance verification (k6). DISPOSABLE environment only (see lt.sh): it reserves spaces and moves money
  for the load-test users created by seed.sql. Their JWTs are signed here with the environment's JWT secret, so no
  BCrypt/KMS work runs during measured requests (logins are not part of these scenarios).

  SCENARIO
    one-space   1,000 users at the same instant, all on space 1              -> exactly 1 winner
    all-spaces  1,000 users at the same instant, random spaces, a taken space is retried on another one (max 5 tries)
                                                                              -> exactly 80 winners
    fifo        ROUNDS rounds, each on its own space: a user without money first, then 9 funded users STAGGER_MS apart,
                spread over the instances                                     -> the first funded user wins each round
    mix         constant arrival rate RATE/s for DURATION. MIX=read: 60% dashboard, 20% reservations, 8% wallet,
                7% my reservations, 5% deposits. MIX=reservation: 20% dashboard, 70% reservations, 5% wallet, 5% deposits.
                MIX=warmup: reads only (run before measured scenarios: instances restart cold after each reset)
  BASE_URLS     the instances, comma-separated: requests are spread over them as a load balancer would.
  JWT_SECRET    the instances' parking.jwt.secret (base64).

  Outcome classes (separate counters, never mixed):
    success    2xx
    business   expected business answers (e.g. 409 SPACE_ALREADY_RESERVED)
    capacity   503 (space-lock / permit / pool timeout, or Redis down) and 429
    failure    anything else: unexpected codes, other 4xx/5xx, timeouts and network errors (status 0)
*/
import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URLS = (__ENV.BASE_URLS || 'http://localhost:8080').split(',');
const SCENARIO = __ENV.SCENARIO || 'mix';
const MIX = __ENV.MIX || 'read';
const FUNDED = Number(__ENV.FUNDED || 5000);
const RATE = Number(__ENV.RATE || 1500);
const DURATION = __ENV.DURATION || '180s';
const ROUNDS = Number(__ENV.ROUNDS || 30);
const STAGGER_MS = Number(__ENV.STAGGER_MS || 5);
const RACERS = Number(__ENV.RACERS || 1000);
const SPACES = 80;
const KEY = encoding.b64decode(__ENV.JWT_SECRET || '', 'std');

const success = new Counter('success');
const business = new Counter('business');
const capacity = new Counter('capacity');
const failure = new Counter('failure');
const spacesWon = new Counter('spaces_won');
const fifoFirstFundedWon = new Counter('fifo_first_funded_won');
const fifoOtherWon = new Counter('fifo_other_won');

const RESERVE_REJECTIONS = ['SPACE_ALREADY_RESERVED', 'VEHICLE_ALREADY_RESERVED'];

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

const SCENARIOS = {
  'one-space': { executor: 'per-vu-iterations', vus: RACERS, iterations: 1, maxDuration: '3m', exec: 'race' },
  'all-spaces': { executor: 'per-vu-iterations', vus: RACERS, iterations: 1, maxDuration: '3m', exec: 'race' },
  fifo: { executor: 'per-vu-iterations', vus: ROUNDS * 10, iterations: 1, maxDuration: '3m', exec: 'fifo' },
  mix: {
    executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: 600, maxVUs: 4000, exec: 'mix',
  },
};

const any = ['count>=0'];
export const options = {
  scenarios: { [SCENARIO]: SCENARIOS[SCENARIO] },
  setupTimeout: '1m',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // Latency targets per endpoint (also makes k6 report each endpoint separately).
    'http_req_duration{name:spaces}': ['p(95)<300'],
    'http_req_duration{name:wallet}': ['p(95)<300'],
    'http_req_duration{name:me}': ['p(95)<300'],
    'http_req_duration{name:deposit}': ['p(95)<1000'],
    'http_req_duration{name:reserve}': ['p(95)<1000'],
    failure: ['count==0'],
    // Reported only (no target): the breakdown of each class.
    success: any, business: any, capacity: any,
    'success{name:reserve}': any, 'success{name:deposit}': any, 'success{name:spaces}': any,
    'business{code:SPACE_ALREADY_RESERVED}': any, 'business{code:VEHICLE_ALREADY_RESERVED}': any,
    'business{code:INSUFFICIENT_BALANCE}': any,
    'capacity{status:503}': any, 'capacity{status:429}': any,
    'capacity{name:reserve}': any, 'capacity{name:deposit}': any, 'capacity{name:spaces}': any,
    spaces_won: any, fifo_first_funded_won: any, fifo_other_won: any,
  },
};

export function setup() {
  // Racing users wait for one common instant (after every VU is initialized).
  return { startAt: Date.now() + 15000 };
}

export function race(data) {
  const vu = exec.vu.idInTest; // users 1..1000
  const baseUrl = BASE_URLS[vu % BASE_URLS.length];
  waitUntil(data.startAt + Math.random() * 50);
  let space = SCENARIO === 'one-space' ? 1 : randomSpace();
  for (let attempt = 1; attempt <= 5; attempt++) {
    const outcome = classify(reserve(baseUrl, vu, space), 'reserve', RESERVE_REJECTIONS);
    if (outcome === 'OK') {
      spacesWon.add(1);
      return;
    }
    if (outcome === '503' || outcome === '429') {
      sleep(1); // Retry-After: the same request again (reservations are idempotent)
      continue;
    }
    if (outcome === 'SPACE_ALREADY_RESERVED' && SCENARIO === 'all-spaces') {
      space = randomSpace();
      continue;
    }
    return;
  }
}

export function fifo(data) {
  const vu = exec.vu.idInTest - 1;
  const round = Math.floor(vu / 10);
  const position = vu % 10; // 0 = the user without money, 1..9 = funded users in arrival order
  const user = position === 0 ? FUNDED + round + 1 : 2000 + round * 9 + position;
  const baseUrl = BASE_URLS[position % BASE_URLS.length];
  waitUntil(data.startAt + round * 500 + position * STAGGER_MS);
  const expected = position === 0 ? ['INSUFFICIENT_BALANCE', 'SPACE_ALREADY_RESERVED'] : ['SPACE_ALREADY_RESERVED'];
  if (classify(reserve(baseUrl, user, round + 1), 'reserve', expected) === 'OK') {
    if (position === 1) {
      fifoFirstFundedWon.add(1);
    } else {
      fifoOtherWon.add(1, { position: String(position) });
      console.warn(`fifo: round ${round} won by position ${position}`);
    }
  }
}

export function mix() {
  const user = 1 + Math.floor(Math.random() * FUNDED);
  const baseUrl = BASE_URLS[exec.scenario.iterationInTest % BASE_URLS.length];
  const r = Math.random();
  if (MIX === 'warmup') { // reads only: warms the JVMs and caches without changing any reservation or balance
    if (r < 0.50) return classify(get(baseUrl, '/api/spaces', user, 'spaces'), 'spaces', []);
    if (r < 0.75) return classify(get(baseUrl, '/api/wallet', user, 'wallet'), 'wallet', []);
    return classify(get(baseUrl, '/api/reservations/me', user, 'me'), 'me', []);
  }
  if (MIX === 'reservation') {
    if (r < 0.70) return classify(reserve(baseUrl, user, randomSpace()), 'reserve', RESERVE_REJECTIONS);
    if (r < 0.90) return classify(get(baseUrl, '/api/spaces', user, 'spaces'), 'spaces', []);
    if (r < 0.95) return classify(get(baseUrl, '/api/wallet', user, 'wallet'), 'wallet', []);
    return classify(deposit(baseUrl, user), 'deposit', []);
  }
  if (r < 0.60) return classify(get(baseUrl, '/api/spaces', user, 'spaces'), 'spaces', []);
  if (r < 0.80) return classify(reserve(baseUrl, user, randomSpace()), 'reserve', RESERVE_REJECTIONS);
  if (r < 0.88) return classify(get(baseUrl, '/api/wallet', user, 'wallet'), 'wallet', []);
  if (r < 0.95) return classify(get(baseUrl, '/api/reservations/me', user, 'me'), 'me', []);
  return classify(deposit(baseUrl, user), 'deposit', []);
}

function reserve(baseUrl, user, space) {
  return http.post(`${baseUrl}/api/reservations`, JSON.stringify({ spaceId: space, vehicleId: user }),
      params(user, 'reserve'));
}

function deposit(baseUrl, user) {
  return http.post(`${baseUrl}/api/wallet/deposits`, JSON.stringify({ amount: 1 }),
      params(user, 'deposit', { 'Idempotency-Key': uuidv4() }));
}

function get(baseUrl, path, user, name) {
  return http.get(`${baseUrl}${path}`, params(user, name));
}

/** @returns 'OK', the business code, '503' / '429', or a failure description. */
function classify(res, name, expectedCodes) {
  if (res.status >= 200 && res.status < 300) {
    success.add(1, { name });
    return 'OK';
  }
  if (res.status === 503 || res.status === 429) {
    capacity.add(1, { name, status: String(res.status) });
    return String(res.status);
  }
  if (res.status === 409 || res.status === 422) {
    const code = codeOf(res);
    if (expectedCodes.includes(code)) {
      business.add(1, { name, code });
    } else {
      failure.add(1, { name, code });
    }
    return code;
  }
  failure.add(1, { name, code: res.status === 0 ? 'network/timeout' : `HTTP ${res.status}` });
  return `HTTP ${res.status}`;
}

function codeOf(res) {
  try {
    return res.json('code');
  } catch (e) {
    return 'unreadable';
  }
}

function params(user, name, extraHeaders = {}) {
  return {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token(user)}`, ...extraHeaders },
    tags: { name },
    timeout: '30s',
  };
}

/** HS256 JWT as the application issues it (subject = user id). */
function token(user) {
  const now = Math.floor(Date.now() / 1000);
  const header = encoding.b64encode(JSON.stringify({ alg: 'HS256' }), 'rawurl');
  const payload = encoding.b64encode(JSON.stringify({
    iss: 'parking-reservation', sub: String(user), username: `lt${user}`, iat: now, exp: now + 3600,
  }), 'rawurl');
  const signature = crypto.hmac('sha256', KEY, `${header}.${payload}`, 'base64rawurl');
  return `${header}.${payload}.${signature}`;
}

function waitUntil(epochMillis) {
  const wait = epochMillis - Date.now();
  if (wait > 0) {
    sleep(wait / 1000);
  }
}

function randomSpace() {
  return 1 + Math.floor(Math.random() * SPACES);
}

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r % 4) + 8).toString(16);
  });
}
