/**
 * Shared part of the sustained load test: the workload (70% reserve / 20% parking availability / 10% deposit at a
 * constant arrival rate), the outcome classes, the metrics and the acceptance thresholds.
 *
 * Used by:
 *   load-stress-test.js      the disposable local stack (lt.sh): JWTs signed with the stack's test secret
 *   load-stress-test-gcp.js  the deployed Cloud Run service (lt-gcp.ps1): tokens issued by the service itself
 *
 * Only the target, the test users and the setup checks differ between the two; everything that decides what is
 * measured and what passes lives here, so local and GCP results stay comparable.
 */

import http from 'k6/http';
import exec from 'k6/execution';
import { fail } from 'k6';
import { Counter } from 'k6/metrics';

export const SPACES = 80;
const BUSINESS_CODES = ['SPACE_ALREADY_RESERVED', 'VEHICLE_ALREADY_RESERVED', 'INSUFFICIENT_BALANCE'];

// ══════════════════════════════════════════════════════════════════
// Metrics
// ══════════════════════════════════════════════════════════════════

// Outcome classes (separate counters, never mixed), tagged with the endpoint (name) and the code or status.
const success = new Counter('success'); //   2xx
const business = new Counter('business'); // 409 with a named business code: an expected answer
const capacity = new Counter('capacity'); // 503 (lock / permit / pool timeout, Redis down) and 429: load shedding
const failure = new Counter('failure'); //   anything else: other codes, 5xx, timeouts, network errors (status 0)

const startedIterations = new Counter('started_iterations'); // scheduled = started + dropped_iterations
const completedRequests = new Counter('completed_requests'); // window:measure (within DURATION) or window:drain

// 409 is an expected answer; 503, 429, other codes and network errors count in http_req_failed.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

// ══════════════════════════════════════════════════════════════════
// Settings and options
// ══════════════════════════════════════════════════════════════════

/**
 * Test settings from the environment (same variables and defaults for every target): RATE (iterations/s),
 * DURATION (180s, 3m, 2m30s), PRE_VUS / MAX_VUS, OPEN_SECONDS (requests started earlier are tagged phase:open).
 */
export function settings() {
  const rate = Number(__ENV.RATE || 1500);
  const duration = __ENV.DURATION || '180s';
  const durationSeconds = seconds(duration);
  const preVus = Number(__ENV.PRE_VUS || 2000);
  return {
    rate,
    duration,
    durationSeconds,
    scheduled: rate * durationSeconds,
    preVus,
    maxVus: Math.max(Number(__ENV.MAX_VUS || 2000), preVus),
    openSeconds: Number(__ENV.OPEN_SECONDS || 10),
  };
}

const reported = ['count>=0']; // no target: makes k6 print the (sub-)metric
const reportedLatency = ['max>=0'];

/** The measured scenario "load" (exec: load) and its pass criteria. */
export function loadOptions(s) {
  return {
    scenarios: {
      load: {
        executor: 'constant-arrival-rate',
        rate: s.rate,
        timeUnit: '1s',
        duration: s.duration,
        preAllocatedVUs: s.preVus,
        maxVUs: s.maxVus,
        gracefulStop: '35s', // longer than the 30 s request timeout: no started iteration is cut off
        exec: 'load',
      },
    },
    setupTimeout: '1m',
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: {
      // The arrival rate was really sustained (dropped = no free VU when an iteration was due).
      'iterations{scenario:load}': [`count>=${Math.ceil(s.scheduled * 0.99)}`],
      dropped_iterations: [`count<=${Math.floor(s.scheduled * 0.001)}`],
      // Technical failures: capacity + failure < 1% of the requests; no unexpected answer at all.
      'http_req_failed{scenario:load}': ['rate<0.01'],
      failure: ['count==0'],
      // Latency per endpoint.
      'http_req_duration{name:reserve}': ['p(95)<1000', 'p(99)<2000'],
      'http_req_duration{name:spaces}': ['p(95)<1000', 'p(99)<2000'],
      'http_req_duration{name:deposit}': ['p(95)<1000', 'p(99)<2000'],
      // Reported only.
      'http_req_duration{name:reserve,phase:open}': reportedLatency,
      'http_req_duration{name:reserve,phase:steady}': reportedLatency,
      'http_req_duration{name:spaces,phase:open}': reportedLatency,
      'http_req_duration{name:spaces,phase:steady}': reportedLatency,
      'http_req_duration{name:deposit,phase:open}': reportedLatency,
      'http_req_duration{name:deposit,phase:steady}': reportedLatency,
      started_iterations: reported,
      'completed_requests{window:measure}': reported,
      'completed_requests{window:drain}': reported,
      success: reported,
      'success{name:reserve}': reported,
      'success{name:spaces}': reported,
      'success{name:deposit}': reported,
      business: reported,
      'business{code:SPACE_ALREADY_RESERVED}': reported,
      'business{code:VEHICLE_ALREADY_RESERVED}': reported,
      'business{code:INSUFFICIENT_BALANCE}': reported,
      capacity: reported,
      'capacity{status:503}': reported,
      'capacity{status:429}': reported,
      'capacity{name:reserve}': reported,
      'capacity{name:deposit}': reported,
    },
  };
}

// ══════════════════════════════════════════════════════════════════
// Load scenario — 70% reserve / 20% parking availability / 10% deposit
// ══════════════════════════════════════════════════════════════════

/** One iteration = exactly one request, for `user` ({ token, vehicleId }) against `baseUrl`. */
export function iteration(s, baseUrl, user) {
  startedIterations.add(1);
  const r = Math.random();
  if (r < 0.7) {
    const space = 1 + Math.floor(Math.random() * SPACES);
    classify(s, http.post(`${baseUrl}/api/reservations`, JSON.stringify({ spaceId: space, vehicleId: user.vehicleId }),
        params(s, user.token, 'reserve')), 'reserve');
  } else if (r < 0.9) {
    classify(s, http.get(`${baseUrl}/api/spaces`, params(s, user.token, 'spaces')), 'spaces');
  } else {
    const amount = 10 + Math.floor(Math.random() * 50); // $10-59, as in the teammate's test
    classify(s, http.post(`${baseUrl}/api/wallet/deposits`, JSON.stringify({ amount }),
        params(s, user.token, 'deposit', { 'Idempotency-Key': uuidv4() })), 'deposit');
  }
}

/** Marks the start of the measured window (the scenario starts right after setup); the reports read it. */
export function logWindowStart() {
  console.log(`window-start=${Date.now()}`);
}

/** GET /api/spaces (setup and teardown only, tagged name:setup): { date, open, spaces: [{ reserved, ... }] }. */
export function dashboard(baseUrl, token, hint) {
  const res = http.get(`${baseUrl}/api/spaces`, {
    headers: { Authorization: `Bearer ${token}` }, tags: { name: 'setup' }, timeout: '30s',
  });
  if (res.status !== 200) {
    fail(`GET /api/spaces -> ${res.status}: ${hint}`);
  }
  return res.json();
}

// ══════════════════════════════════════════════════════════════════
// Helpers
// ══════════════════════════════════════════════════════════════════

function classify(s, res, name) {
  const elapsed = Date.now() - exec.scenario.startTime;
  completedRequests.add(1, { window: elapsed <= s.durationSeconds * 1000 ? 'measure' : 'drain' });
  if (res.status >= 200 && res.status < 300) {
    success.add(1, { name });
  } else if (res.status === 503 || res.status === 429) {
    capacity.add(1, { name, status: String(res.status) });
  } else if (res.status === 409 && BUSINESS_CODES.includes(codeOf(res))) {
    business.add(1, { name, code: codeOf(res) });
  } else {
    const code = res.status === 0 ? 'network/timeout' : `HTTP ${res.status} ${codeOf(res)}`;
    failure.add(1, { name, code });
  }
}

function codeOf(res) {
  try {
    return res.json('code');
  } catch (e) {
    return 'unreadable';
  }
}

function params(s, token, name, extraHeaders = {}) {
  const phase = Date.now() - exec.scenario.startTime < s.openSeconds * 1000 ? 'open' : 'steady';
  return {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...extraHeaders },
    tags: { name, phase },
    timeout: '30s',
  };
}

/** k6 duration ("180s", "3m", "2m30s") in seconds. */
export function seconds(duration) {
  const parts = /^(?:(\d+)m)?(?:(\d+)s)?$/.exec(duration);
  if (!parts || (parts[1] === undefined && parts[2] === undefined)) {
    throw new Error(`DURATION must look like 180s, 3m or 2m30s: ${duration}`);
  }
  return Number(parts[1] || 0) * 60 + Number(parts[2] || 0);
}

export function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r % 4) + 8).toString(16);
  });
}
