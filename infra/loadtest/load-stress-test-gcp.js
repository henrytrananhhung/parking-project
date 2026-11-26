/**
 * Parking reservation — sustained load test (k6) against the DEPLOYED Cloud Run service, run from your machine.
 * 1,500 TPS for 180 s by default. Run it through lt-gcp.ps1 (README.md, "GCP: Cloud Run").
 *
 * Same workload (70% reserve / 20% parking availability / 10% deposit), outcome classes and thresholds as the local
 * load-stress-test.js: both come from lib/stress-workload.js. What differs:
 *   - one BASE_URL, the Cloud Run https URL: Cloud Run itself spreads the requests over the instances;
 *   - the users come from USERS_FILE, written by seed-gcp.js (lt-gcp.ps1 seed). They were registered through the public
 *     API, and their tokens were issued by the service at registration: the JWT signing key never leaves Secret
 *     Manager. Vehicle ids come from the same file (they are not the user ids on a shared database);
 *   - setup: as locally, the opening race must start with all 80 spaces free (lt-gcp.ps1 reset removes the previous
 *     benchmark's reservations); the window must be open and the tokens must outlive the run.
 *
 * MODE=warmup: reads only (50% dashboard, 25% wallet, 25% my reservations), as m3.js MIX=warmup does before local
 * runs. It changes no reservation or balance and has no pass criteria.
 *
 * Variables: BASE_URL, USERS_FILE (./.gcp/users.json), MODE (load | warmup), RATE, DURATION, PRE_VUS, MAX_VUS,
 * OPEN_SECONDS (as in the local test).
 */

import http from 'k6/http';
import encoding from 'k6/encoding';
import { fail } from 'k6';
import { SharedArray } from 'k6/data';
import { SPACES, settings, loadOptions, iteration, dashboard, logWindowStart } from './lib/stress-workload.js';

// ══════════════════════════════════════════════════════════════════
// Configuration
// ══════════════════════════════════════════════════════════════════

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/+$/, '');
if (!/^https:\/\/[^/]+$/.test(BASE_URL)) {
  throw new Error(`BASE_URL must be the Cloud Run service URL, e.g. https://parking-reservation-....run.app (got "${BASE_URL}")`);
}
const USERS_FILE = __ENV.USERS_FILE || './.gcp/users.json';
const MODE = __ENV.MODE || 'load';
const S = settings();
const HINT = 'is BASE_URL right and are the seeded tokens still valid (lt-gcp.ps1 preflight)?';

// ══════════════════════════════════════════════════════════════════
// Test data: the seeded users ({ t: token, v: vehicle id }), shared read-only by all VUs
// ══════════════════════════════════════════════════════════════════

const SEED = new SharedArray('seed', () => {
  const file = JSON.parse(open(USERS_FILE));
  return [{ baseUrl: file.baseUrl, tag: file.tag, count: file.users.length }];
})[0];
const USERS = new SharedArray('users', () => JSON.parse(open(USERS_FILE)).users);

// ══════════════════════════════════════════════════════════════════
// Options
// ══════════════════════════════════════════════════════════════════

function warmupOptions(s) {
  return {
    scenarios: {
      warmup: {
        executor: 'constant-arrival-rate',
        rate: s.rate,
        timeUnit: '1s',
        duration: s.duration,
        preAllocatedVUs: s.preVus,
        maxVUs: s.maxVus,
        gracefulStop: '35s',
        exec: 'warmup',
      },
    },
    setupTimeout: '1m',
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: { // reported only: a warm-up does not pass or fail
      'http_req_duration{name:spaces}': ['max>=0'],
      'http_req_duration{name:wallet}': ['max>=0'],
      'http_req_duration{name:me}': ['max>=0'],
    },
  };
}

export const options = MODE === 'warmup' ? warmupOptions(S) : loadOptions(S);

// ══════════════════════════════════════════════════════════════════
// Setup: refuse a target that cannot give meaningful results
// ══════════════════════════════════════════════════════════════════

export function setup() {
  if (USERS.length === 0) {
    fail(`${USERS_FILE} holds no users: run lt-gcp.ps1 seed first.`);
  }
  if (SEED.baseUrl && SEED.baseUrl.replace(/\/+$/, '') !== BASE_URL) {
    fail(`The users in ${USERS_FILE} were seeded on ${SEED.baseUrl}, not on ${BASE_URL}.`);
  }
  const expiresAt = tokenExpiry(USERS[0].t);
  const runEnds = Date.now() + (S.durationSeconds + 120) * 1000;
  if (expiresAt < runEnds) {
    fail(`The seeded tokens expire at ${new Date(expiresAt).toISOString()}, before this run ends: `
        + 'run lt-gcp.ps1 cleanup and seed again.');
  }
  const board = dashboard(BASE_URL, USERS[0].t, HINT);
  if (!board.open) {
    fail(`The reservation window is closed (opens ${board.opensAt} ${board.timezone}): `
        + 'deploy with -Profiles gcp,demo, or run after the opening time.');
  }
  const reserved = board.spaces.filter((s) => s.reserved).length;
  const free = board.spaces.length - reserved;
  console.log(`seed batch ${SEED.tag}: ${USERS.length} users; ${board.date}: ${free}/${board.spaces.length} spaces free`);
  if (MODE === 'load' && (board.spaces.length !== SPACES || reserved > 0)) {
    fail(`Expected ${SPACES} free spaces for ${board.date}, found ${board.spaces.length} with ${reserved} reserved: `
        + 'run lt-gcp.ps1 reset first.');
  }
  // The scenario starts right after setup: lt-gcp.ps1 report uses this instant as the start of the measured window.
  logWindowStart();
  return { date: board.date, free, reservedBefore: reserved };
}

// ══════════════════════════════════════════════════════════════════
// Scenarios
// ══════════════════════════════════════════════════════════════════

/** Measured load: a random seeded user, one request (lib/stress-workload.js). */
export function load() {
  const user = USERS[Math.floor(Math.random() * USERS.length)];
  iteration(S, BASE_URL, { token: user.t, vehicleId: user.v });
}

/** Warm-up: reads only. */
export function warmup() {
  const user = USERS[Math.floor(Math.random() * USERS.length)];
  const r = Math.random();
  let path = '/api/reservations/me';
  let name = 'me';
  if (r < 0.5) {
    path = '/api/spaces';
    name = 'spaces';
  } else if (r < 0.75) {
    path = '/api/wallet';
    name = 'wallet';
  }
  http.get(`${BASE_URL}${path}`, { headers: { Authorization: `Bearer ${user.t}` }, tags: { name }, timeout: '30s' });
}

// ══════════════════════════════════════════════════════════════════
// Teardown
// ══════════════════════════════════════════════════════════════════

export function teardown(data) {
  if (MODE !== 'load') {
    return;
  }
  const board = dashboard(BASE_URL, USERS[0].t, HINT);
  const reserved = board.spaces.filter((s) => s.reserved).length;
  console.log(`after the run: ${reserved}/${SPACES} spaces reserved for ${board.date}`);
}

// ══════════════════════════════════════════════════════════════════
// Helpers
// ══════════════════════════════════════════════════════════════════

/** Expiry (epoch millis) of a JWT issued by the service; only read, never verified or re-signed. */
function tokenExpiry(token) {
  try {
    return JSON.parse(encoding.b64decode(token.split('.')[1], 'rawurl', 's')).exp * 1000;
  } catch (e) {
    fail('The seeded tokens are unreadable: run lt-gcp.ps1 cleanup and seed again.');
  }
  return 0;
}
