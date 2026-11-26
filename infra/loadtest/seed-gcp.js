/**
 * Seeds the load-test users of load-stress-test-gcp.js through the PUBLIC API of the deployed service. Run it through
 * lt-gcp.ps1 seed (README.md, "GCP: Cloud Run").
 *
 * Per user, exactly what seed.sql gives a local user: register (BCrypt + Cloud KMS, like any user) -> one vehicle ->
 * one $100 deposit (with an Idempotency-Key). The registration answer carries the user's JWT, issued and signed by
 * the service: no signing key ever leaves Secret Manager. The password is random, kept in memory only and never
 * written: the tokens (valid for the service's JWT_TTL, 12 h) are all the test needs.
 *
 * Writes USERS_FILE: { tag, baseUrl, created, expiresAt, users: [{ t: token, v: vehicle id }] }. It holds bearer
 * tokens: it lives in infra/loadtest/.gcp/ (git-ignored) and lt-gcp.ps1 cleanup deletes it.
 * Never run this with k6 --summary-export: the export would contain setup()'s result, i.e. the tokens.
 *
 * Test data is recognisable: usernames gcplt-<SEED_TAG>-<nnnnn> (SEED_TAG = yyyyMMdd-HHmmss) and plates
 * G<nnnnn><HHmmss>. gcp-cleanup.sql deletes exactly those usernames and their rows, never demo or teammate data.
 *
 * Variables: BASE_URL, USERS (5000), SEED_BATCH (50 users in parallel), SEED_TAG, USERS_FILE.
 */

import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { fail, sleep } from 'k6';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/+$/, '');
if (!/^https:\/\/[^/]+$/.test(BASE_URL)) {
  throw new Error(`BASE_URL must be the Cloud Run service URL (got "${BASE_URL}")`);
}
const USERS = Number(__ENV.USERS || 5000);
const BATCH = Number(__ENV.SEED_BATCH || 50);
const TAG = __ENV.SEED_TAG || '';
if (!/^\d{8}-\d{6}$/.test(TAG)) {
  throw new Error(`SEED_TAG must look like 20261127-153000 (got "${TAG}")`);
}
if (USERS < 1 || USERS > 99999) {
  throw new Error(`USERS must be between 1 and 99999 (got ${USERS})`);
}
const USERS_FILE = __ENV.USERS_FILE || './.gcp/users.json';
const DEPOSIT_DOLLARS = 100; // $100 each, as seed.sql gives the local funded users

export const options = {
  vus: 1,
  iterations: 1,
  setupTimeout: '30m',
  batch: BATCH,
  batchPerHost: BATCH,
};

export function setup() {
  const password = randomPassword();
  const users = [];
  const started = Date.now();
  for (let first = 1; first <= USERS; first += BATCH) {
    const indexes = [];
    for (let i = first; i < first + BATCH && i <= USERS; i++) {
      indexes.push(i);
    }
    const tokens = withRetry(indexes, (i) => ['POST', `${BASE_URL}/api/auth/register`,
      JSON.stringify({ username: username(i), password }), json()], 201, 'register',
    // A lost answer to a registration that succeeded: the retry gets USERNAME_TAKEN, so log in instead.
    (i) => login(username(i), password)).map((res) => res.json('token'));

    const vehicleIds = withRetry(indexes, (i, k) => ['POST', `${BASE_URL}/api/vehicles`,
      JSON.stringify({ plate: plate(i) }), json(tokens[k])], 201, 'vehicle',
    // PLATE_TAKEN on a retry: the first attempt succeeded; find the vehicle among the user's own.
    (i, k) => ownVehicle(tokens[k], plate(i))).map((res) => res.json('id'));

    const depositKeys = indexes.map(() => uuidv4()); // reused by a retry: the deposit is applied once
    withRetry(indexes, (i, k) => ['POST', `${BASE_URL}/api/wallet/deposits`,
      JSON.stringify({ amount: DEPOSIT_DOLLARS }), json(tokens[k], { 'Idempotency-Key': depositKeys[k] })], 200, 'deposit');

    indexes.forEach((i, k) => users.push({ t: tokens[k], v: vehicleIds[k] }));
    if (users.length % 1000 < BATCH || users.length === USERS) {
      console.log(`seeded ${users.length}/${USERS} users (${Math.round((Date.now() - started) / 1000)} s)`);
    }
  }
  return {
    tag: TAG,
    baseUrl: BASE_URL,
    created: new Date().toISOString(),
    expiresAt: new Date(tokenExpiry(users[0].t)).toISOString(),
    users,
  };
}

export default function () {
  // Everything happens in setup(), which runs once; handleSummary() writes its result.
}

export function handleSummary(data) {
  const seeded = data.setup_data;
  if (!seeded || !seeded.users || seeded.users.length !== USERS) {
    return { stdout: 'seeding FAILED: no users file written (see the errors above).\n' };
  }
  return {
    stdout: `seeded ${seeded.users.length} users, batch ${seeded.tag}; tokens valid until ${seeded.expiresAt}\n`,
    [USERS_FILE]: JSON.stringify(seeded),
  };
}

// ══════════════════════════════════════════════════════════════════
// Helpers
// ══════════════════════════════════════════════════════════════════

/**
 * Sends one request per index in parallel (http.batch); retries those that failed for capacity or network reasons
 * (up to 3 attempts). `recover(index, position)` turns a 409 on a retry into the response of the original request.
 * Fails the seeding on anything else: a half-seeded batch is removed by lt-gcp.ps1 cleanup.
 */
function withRetry(indexes, request, expected, what, recover) {
  const results = new Array(indexes.length);
  let pending = indexes.map((_, k) => k);
  for (let attempt = 1; attempt <= 3 && pending.length > 0; attempt++) {
    const responses = http.batch(pending.map((k) => {
      const [method, url, body, params] = request(indexes[k], k);
      return { method, url, body, params };
    }));
    const retry = [];
    pending.forEach((k, j) => {
      const res = responses[j];
      if (res.status === expected) {
        results[k] = res;
      } else if (attempt > 1 && res.status === 409 && recover) {
        results[k] = recover(indexes[k], k);
      } else if (res.status === 0 || res.status === 429 || res.status >= 500) {
        retry.push(k);
      } else {
        fail(`${what} for ${username(indexes[k])} -> HTTP ${res.status} ${res.body}`);
      }
    });
    pending = retry;
    if (pending.length > 0) {
      sleep(attempt);
    }
  }
  if (pending.length > 0) {
    fail(`${what}: ${pending.length} requests still failing after 3 attempts (is the service scaled up?)`);
  }
  return results;
}

function login(name, password) {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ username: name, password }), json());
  if (res.status !== 200) {
    fail(`login for ${name} -> HTTP ${res.status}`);
  }
  return res;
}

function ownVehicle(token, wanted) {
  const res = http.get(`${BASE_URL}/api/vehicles`, json(token));
  const vehicle = res.status === 200 ? res.json().find((v) => v.plate === wanted) : undefined;
  if (!vehicle) {
    fail(`plate ${wanted} is taken by another account: run lt-gcp.ps1 cleanup first`);
  }
  return { json: (field) => vehicle[field] };
}

function json(token, extraHeaders = {}) {
  const headers = { 'Content-Type': 'application/json', ...extraHeaders };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return { headers, timeout: '60s', tags: { name: 'seed' } };
}

function username(i) {
  return `gcplt-${TAG}-${String(i).padStart(5, '0')}`;
}

/** 12 characters, within the plate rule ^[A-Z0-9]{4,12}$: G + user index + the batch's HHmmss. */
function plate(i) {
  return `G${String(i).padStart(5, '0')}${TAG.slice(9)}`;
}

/** 32 characters from 24 cryptographically random bytes; shared by the batch, never written anywhere. */
function randomPassword() {
  return encoding.b64encode(crypto.randomBytes(24), 'rawurl');
}

function tokenExpiry(token) {
  return JSON.parse(encoding.b64decode(token.split('.')[1], 'rawurl', 's')).exp * 1000;
}

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r % 4) + 8).toString(16);
  });
}
