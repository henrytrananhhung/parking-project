/**
 * Parking reservation — sustained load test (k6), 1,500 TPS for 180 s by default. LOCAL disposable stack (lt.sh).
 * The Cloud Run variant is load-stress-test-gcp.js; both share the workload, outcome classes and thresholds in
 * lib/stress-workload.js.
 *
 * Same workload as the teammate's tests/load-stress-test.js "load" scenario (70% reserve / 20% parking availability /
 * 10% deposit at a constant arrival rate), adapted to this application and with their measurement issues fixed:
 *   - users, vehicles and wallets come from seed.sql (lt.sh reset), not from API registration inside the test, and
 *     their JWTs are signed once at start-up: nothing outside the scenario is counted;
 *   - enough VUs are pre-allocated (no VU start-up during the run), and dropped iterations fail the test;
 *   - the achieved rate is checked (completed iterations >= 99% of RATE x DURATION) and requests completed within the
 *     window are counted apart from those completed in the drain after it (lt.sh report computes the throughput);
 *   - only named business answers (409 SPACE_ALREADY_RESERVED, VEHICLE_ALREADY_RESERVED, INSUFFICIENT_BALANCE) are
 *     expected; 503/429 are capacity failures and count in http_req_failed, anything else is a failure;
 *   - no retries and no think time: every iteration is exactly one request, every error is visible;
 *   - latency targets per endpoint (p95 < 1 s, p99 < 2 s, the teammate's bar), not one global figure.
 *
 * DISPOSABLE environment only: it reserves spaces and moves money. Run it through lt.sh (see README.md):
 *   infra/loadtest/lt.sh bench NAME [RATE=1500] [DURATION=180s]   reset + warm-up + this test + diagnostics + report
 *   infra/loadtest/lt.sh load NAME [VAR=...]                       this test only, on the current data
 *
 * Variables: BASE_URLS (comma-separated instances, round-robin), JWT_SECRET (base64, the instances' secret),
 * RATE (1500 iterations/s), DURATION (180s; also 3m, 2m30s), PRE_VUS / MAX_VUS (2000 / 2000), FUNDED (5000 seeded users
 * with $100 and vehicle id = user id), OPEN_SECONDS (10: requests started earlier are tagged phase:open, the 8 PM race
 * for the 80 spaces; later ones phase:steady; a 10 s run has no steady phase).
 */

import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { fail } from 'k6';
import { SharedArray } from 'k6/data';
import { SPACES, settings, loadOptions, iteration, dashboard, logWindowStart } from './lib/stress-workload.js';

// ══════════════════════════════════════════════════════════════════
// Configuration
// ══════════════════════════════════════════════════════════════════

const BASE_URLS = (__ENV.BASE_URLS || 'http://localhost:8080').split(',');
const KEY = encoding.b64decode(__ENV.JWT_SECRET || '', 'std');
const FUNDED = Number(__ENV.FUNDED || 5000);
const S = settings();
const HINT = 'is the stack up (lt.sh up) and JWT_SECRET right?';

// ══════════════════════════════════════════════════════════════════
// Test data: one pre-signed token per seeded user (ids 1..FUNDED), shared read-only by all VUs
// ══════════════════════════════════════════════════════════════════

const TOKENS = new SharedArray('tokens', () => {
  const issuedAt = Math.floor(Date.now() / 1000);
  const tokens = [];
  for (let user = 1; user <= FUNDED; user++) {
    tokens.push(token(user, issuedAt));
  }
  return tokens;
});

// ══════════════════════════════════════════════════════════════════
// Options: executor and acceptance criteria (lib/stress-workload.js)
// ══════════════════════════════════════════════════════════════════

export const options = loadOptions(S);

// ══════════════════════════════════════════════════════════════════
// Setup: refuse a target that cannot give comparable results
// ══════════════════════════════════════════════════════════════════

export function setup() {
  const board = dashboard(BASE_URLS[0], TOKENS[0], HINT);
  if (!board.open) {
    fail('The reservation window is closed: start the instances with RESERVATION_OPEN_TIME=00:00 (lt.sh does).');
  }
  const reserved = board.spaces.filter((s) => s.reserved).length;
  if (board.spaces.length !== SPACES || reserved > 0) {
    fail(`Expected ${SPACES} free spaces for ${board.date}, found ${board.spaces.length} with ${reserved} reserved: `
        + 'run lt.sh reset first (or lt.sh bench, which does).');
  }
  // The scenario starts right after setup: lt.sh report uses this instant to pick the resource samples in the window.
  logWindowStart();
  return {};
}

// ══════════════════════════════════════════════════════════════════
// Load scenario: a random seeded user (vehicle id = user id), instances in round-robin
// ══════════════════════════════════════════════════════════════════

export function load() {
  const user = 1 + Math.floor(Math.random() * FUNDED);
  const baseUrl = BASE_URLS[exec.scenario.iterationInTest % BASE_URLS.length];
  iteration(S, baseUrl, { token: TOKENS[user - 1], vehicleId: user });
}

// ══════════════════════════════════════════════════════════════════
// Teardown
// ══════════════════════════════════════════════════════════════════

export function teardown() {
  const board = dashboard(BASE_URLS[0], TOKENS[0], HINT);
  console.log(`after the run: ${board.spaces.filter((s) => s.reserved).length}/${SPACES} spaces reserved for ${board.date}`);
}

// ══════════════════════════════════════════════════════════════════
// Helpers
// ══════════════════════════════════════════════════════════════════

/** HS256 JWT as the application issues it (subject = user id), valid for 2 hours. */
function token(user, issuedAt) {
  const header = encoding.b64encode(JSON.stringify({ alg: 'HS256' }), 'rawurl');
  const payload = encoding.b64encode(JSON.stringify({
    iss: 'parking-reservation', sub: String(user), username: `lt${user}`, iat: issuedAt, exp: issuedAt + 7200,
  }), 'rawurl');
  const signature = crypto.hmac('sha256', KEY, `${header}.${payload}`, 'base64rawurl');
  return `${header}.${payload}.${signature}`;
}
