/*
  8 PM race load test (k6): USERS funded users with a vehicle all click "reserve" at the same moment.

  !!! DISPOSABLE TARGETS ONLY. It creates USERS accounts and takes ALL 80 spaces for tomorrow, so it must never run
  against the shared demo. It refuses to start unless DISPOSABLE_TARGET=yes, the window is open, and all 80 spaces
  are still free.

  Run (repo root, PowerShell; k6 runs in Docker, nothing to install). Start the app with the window open first,
  e.g. RESERVATION_OPEN_TIME=00:00, against a fresh database:
    docker run --rm -v "${PWD}\infra\loadtest:/scripts" -e BASE_URL=http://host.docker.internal:8080 `
      -e DISPOSABLE_TARGET=yes grafana/k6:1.3.0 run /scripts/reserve-8pm.js
  Options: USERS (1000), PREP_SECONDS (60), LOGIN_SPREAD_SECONDS (10), MAX_ATTEMPTS (5).
  Afterwards run infra/loadtest/verify.sql against that database: every violation count must be 0.

  Each virtual user:
    1. prepares, at a random moment in the first LOGIN_SPREAD_SECONDS (real users log in before 8 PM, not in the
       same millisecond; with 0, 1,000 simultaneous new TCP connections overflow a single local Tomcat's accept
       queue, which Cloud Run's front end never does): registers, deposits $10 (with an Idempotency-Key),
       registers a vehicle;
    2. waits for the shared race moment (PREP_SECONDS after the start) plus 0-500 ms of human reaction time;
    3. reserves a random space; if it is taken, tries another (up to MAX_ATTEMPTS). On a network error (e.g. EOF on
       a keep-alive connection the server just closed) or a 503, the client cannot know whether the server processed
       the request, so it retries the SAME request (after Retry-After for a 503). That is safe only because
       reservations are idempotent: a processed request is answered with the original reservation.
  Pass = all thresholds below: exactly min(USERS, 80) spaces won, every user ready in time, no unexpected
  outcomes, < 1% failed reserve requests (409 is an expected answer, not a failure), p95 reserve latency < 2 s.
*/
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://host.docker.internal:8080').replace(/\/$/, '');
const USERS = Number(__ENV.USERS || 1000);
const PREP_SECONDS = Number(__ENV.PREP_SECONDS || 60);
const LOGIN_SPREAD_SECONDS = Number(__ENV.LOGIN_SPREAD_SECONDS || 10);
const MAX_ATTEMPTS = Number(__ENV.MAX_ATTEMPTS || 5);
const SPACES = 80;
const PASSWORD = 'LoadTest-2026';

const spacesWon = new Counter('spaces_won');
const spaceTakenRetries = new Counter('space_taken_retries');
const sameRequestRetries = new Counter('same_request_retries');
const lateStarters = new Counter('late_starters');
const unexpectedOutcomes = new Counter('unexpected_outcomes');

// 409 (space taken etc.) is a normal answer in a race; anything else non-2xx counts as a failed request.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export const options = {
  setupTimeout: '1m',
  scenarios: {
    race: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: 1,
      maxDuration: `${PREP_SECONDS + 120}s`,
    },
  },
  thresholds: {
    spaces_won: [`count==${Math.min(USERS, SPACES)}`],
    late_starters: ['count==0'],
    unexpected_outcomes: ['count==0'],
    'http_req_failed{name:reserve}': ['rate<0.01'],
    'http_req_duration{name:reserve}': ['p(95)<2000'],
    checks: ['rate==1'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (__ENV.DISPOSABLE_TARGET !== 'yes') {
    fail('Refusing to run: this test creates accounts and takes ALL 80 spaces for tomorrow. '
        + 'Set DISPOSABLE_TARGET=yes, and only for a disposable deployment/database.');
  }
  const run = Date.now().toString(36).slice(-5); // makes usernames and plates unique per run
  const observer = register(`load-${run}-observer`);
  if (!observer) {
    fail(`Cannot register on ${BASE_URL}`);
  }
  const board = dashboard(observer);
  if (!board.open) {
    fail(`The reservation window is closed on ${BASE_URL}: start the app with RESERVATION_OPEN_TIME=00:00.`);
  }
  const alreadyReserved = board.spaces.filter((s) => s.reserved).length;
  if (alreadyReserved > 0) {
    fail(`${alreadyReserved} spaces are already reserved for ${board.date}: use a fresh database.`);
  }
  return { run, observerToken: observer };
}

export default function (data) {
  const vu = exec.vu.idInTest;
  sleep(Math.random() * LOGIN_SPREAD_SECONDS);
  const me = prepare(data.run, vu);
  if (!me) {
    unexpectedOutcomes.add(1, { step: 'prepare' });
    return;
  }

  // Everyone is logged in and waiting; the race starts at the same moment for all.
  const waitMs = exec.scenario.startTime + PREP_SECONDS * 1000 - Date.now();
  if (waitMs > 0) {
    sleep(waitMs / 1000);
  } else {
    lateStarters.add(1);
  }
  sleep(Math.random() * 0.5);

  let space = randomSpace();
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    const res = http.post(`${BASE_URL}/api/reservations`,
        JSON.stringify({ spaceId: space, vehicleId: me.vehicleId }), params(me.token, 'reserve'));
    if (res.status === 201) {
      spacesWon.add(1);
      return;
    }
    if (res.status === 0 || res.status === 503) {
      // Outcome unknown to the client: retry the same request (idempotent).
      sameRequestRetries.add(1, { reason: res.status === 0 ? 'network error' : '503' });
      sleep(res.status === 503 ? Number(res.headers['Retry-After'] || 1) : 0.2);
      continue;
    }
    const code = res.status === 409 ? res.json('code') : `HTTP ${res.status}`;
    if (code === 'SPACE_ALREADY_RESERVED') {
      spaceTakenRetries.add(1);
      space = randomSpace();
      continue;
    }
    unexpectedOutcomes.add(1, { outcome: String(code) });
    return;
  }
}

export function teardown(data) {
  const board = dashboard(data.observerToken);
  const reserved = board.spaces.filter((s) => s.reserved).length;
  console.log(`After the race: ${reserved}/${SPACES} spaces reserved for ${board.date}`);
  check(reserved, { 'dashboard shows every winnable space reserved': (n) => n === Math.min(USERS, SPACES) });
}

function prepare(run, vu) {
  const token = register(`load-${run}-${vu}`);
  if (!token) {
    return null;
  }
  const deposit = http.post(`${BASE_URL}/api/wallet/deposits`, JSON.stringify({ amount: 10 }),
      params(token, 'deposit', { 'Idempotency-Key': uuidv4() }));
  if (!check(deposit, { 'deposit -> 200': (r) => r.status === 200 })) {
    return null;
  }
  const plate = `LT${run.toUpperCase()}${String(vu).padStart(4, '0')}`;
  const vehicle = http.post(`${BASE_URL}/api/vehicles`, JSON.stringify({ plate }), params(token, 'vehicle'));
  if (!check(vehicle, { 'vehicle -> 201': (r) => r.status === 201 })) {
    return null;
  }
  return { token, vehicleId: vehicle.json('id') };
}

function register(username) {
  const res = http.post(`${BASE_URL}/api/auth/register`, JSON.stringify({ username, password: PASSWORD }),
      params(null, 'register'));
  return check(res, { 'register -> 201': (r) => r.status === 201 }) ? res.json('token') : null;
}

function dashboard(token) {
  const res = http.get(`${BASE_URL}/api/spaces`, params(token, 'dashboard'));
  if (res.status !== 200) {
    fail(`GET /api/spaces -> ${res.status}`);
  }
  return res.json();
}

function params(token, name, extraHeaders = {}) {
  const headers = { 'Content-Type': 'application/json', ...extraHeaders };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return { headers, tags: { name } };
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
