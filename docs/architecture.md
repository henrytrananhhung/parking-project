# Architecture (Phase 1, as deployed)

```text
Browser ──HTTPS──> Cloud Run service "parking-reservation" (one container, Java 21)
                     ├── Angular SPA (static files bundled into Spring Boot, hash routing)
                     └── Spring Boot REST API /api/**  (stateless HS256 JWT)
                           │  Cloud SQL Java Connector (TLS), Direct VPC egress (private-ranges-only)
                           ▼
                   Cloud SQL "parking-db": PostgreSQL 17, private IP only, SSL mode ENCRYPTED_ONLY

M3 (local only so far): Redis 7.2 via Redisson coordinates reservations across instances (docker compose locally).
On GCP it will be Memorystore parking-redis, reached over the same Direct VPC egress + Private Services Access path
as Cloud SQL; provisioning is prepared (infra/setup-m3.ps1, docs/m3-gcp-setup.md) but not run yet, so the deployed
revision does not include M3.

Supporting services
  Cloud KMS       parking-keyring/password-key   encrypts BCrypt password hashes
  Secret Manager  db-password, jwt-signing-key   injected as env vars (--set-secrets)
  Cloud Build + Artifact Registry                gcloud run deploy --source .
  Cloud Logging                                  application stdout
Runtime identity: parking-run-sa (cloudsql.client, secretAccessor on 2 secrets, KMS encrypter/decrypter on 1 key, log/metric writer)
```

## Deployment settings

| Setting | Value (Phase 1, live) | M3 target (prepared, [docs/m3-gcp-setup.md](m3-gcp-setup.md)) | Where |
|---|---|---|---|
| Cloud Run | 1 vCPU, 1 GiB, concurrency 80, min 1 (demo) / 0, max 2, CPU boost, 60 s timeout | 2 vCPU, 2 GiB, concurrency 200, min 1 (8 during a load test, service level) / max 10 (service and revision level; regional quota: 20 vCPUs, 10 instances with Direct VPC egress), CPU boost, 60 s timeout | `infra/deploy.ps1` |
| Cloud SQL | PostgreSQL 17 Enterprise, `db-g1-small`, zonal, 10 GB SSD | `db-custom-2-7680` (2 vCPU, 7.5 GB), `max_connections` 300, same disk | `infra/setup.sh`, `infra/setup-m3.ps1` |
| Connection pool | Hikari, `DB_POOL_SIZE` = 5 | 10 per instance: 10 normally, ≤ 100 at 10 instances, ≤ 200 while two revisions overlap; + 10 admin = 210 ≤ 300, checked by `deploy.ps1` | `application.yaml`, `infra/deploy.ps1` |
| Reservation permits | – | 10 (`PARKING_CONCURRENCY_RESERVATIONPERMITS_PERMITS`; yaml default 4), as in the best local load test | `infra/deploy.ps1` |
| Redis | – | Memorystore `parking-redis`: Redis 7.2, Basic 1 GB, Private Services Access, AUTH (secret `redis-password`), `noeviction`, no persistence, no TLS | `infra/setup-m3.ps1` |
| JVM | Dockerfile `JAVA_TOOL_OPTIONS` (75% heap, exit on OOM); SerialGC chosen at 1 vCPU / 1 GiB | unchanged options; G1 chosen at 2 vCPU / 2 GiB | `Dockerfile` |
| Image | node 24 (Angular build) → temurin 21 JDK (Gradle `bootJar`) → temurin 21 JRE, non-root | unchanged | `Dockerfile` |

## Backend (`com.parking`, single Gradle module, package by layer)

| Package | Responsibility |
|---|---|
| `controller` | `Auth`, `Vehicle`, `Wallet`, `Reservation` controllers: parse, validate, wrap. Never transactional, never see entities |
| `service` | `AuthService` (register/login), `VehicleService` (plate normalization `51f-123.45` → `51F12345`, ownership), `WalletService` (idempotent deposits $0.01–$1,000, one `@Transactional` method, no Redis), `ReservationService` (reserve coordination, dashboard, my reservations) → `ReservationBookingService` (the reservation transaction), `ReservationWindow`. Own every transaction, declared with `@Transactional` (`TransactionTemplate` only in `AuthService` and `DemoDataSeeder`, decision 31); return DTOs or primitives |
| `repository` | Spring Data JPA repositories (`repository/projection`: dashboard row record) |
| `entity` | `AbstractEntity` (IDENTITY `id` + `@Version`) and `UserEntity`, `VehicleEntity`, `WalletEntity`, `WalletTransactionEntity`, `ParkingSpaceEntity`, `ReservationEntity` |
| `dto/request`, `dto/response` | API records; the JSON contract (pinned by `ApiHttpIT`) |
| `mapper` | Entity / projection → response DTO (static methods) |
| `security` | `TokenService` (JWT), `PasswordCipher` → `KmsPasswordCipher` (`gcp`) / `LocalAesPasswordCipher` (other profiles), `CurrentUser` |
| `config` | `SecurityConfig`, `ClockConfig`, `RedisConfig` + `ParkingRedisProperties` (Redisson client), `DemoDataSeeder` (`demo` profile: `demo1`–`demo3` with $100 and one vehicle) |
| `concurrency` | Redis coordination: `SpaceLocks` (fair lock per date + space), `DbPermits` (the global reservation permit budget), `ReservationStateCache` (committed reservations per date), `ConcurrencyProperties` |
| `exception` | `GlobalExceptionHandler` (ProblemDetail + `code`), `ErrorCode`, `BusinessException`, `Constraints` |
| `event` | `ReservationConfirmedEvent` + after-commit `ReservationConfirmedListener` |

### API

| Endpoint | Purpose |
|---|---|
| `POST /api/auth/register`, `POST /api/auth/login` | Public; return `{token, username}` |
| `GET/POST /api/vehicles` | List / register plates |
| `GET /api/wallet`, `POST /api/wallet/deposits` | Balance (cents) / deposit `{amount}` in dollars; requires header `Idempotency-Key: <uuid>` (reuse it when retrying) |
| `GET /api/spaces` | Target date, window open, opening time, timezone, 80 spaces `{id, reserved, plateSuffix, mine}` |
| `POST /api/reservations`, `GET /api/reservations/me` | Reserve `{spaceId, vehicleId}` / own reservations |
| `GET /actuator/health` | Public; app + DB status (no details) |

### Data model (Flyway V1, V2 seeds spaces 1–80, V3 deposit idempotency key, V4 entity id + version)

`users` (unique username, `password_cipher`) · `vehicles` (unique plate) · `wallets` (`balance_cents ≥ 0`, unique `user_id`) ·
`wallet_transactions` (DEPOSIT / RESERVATION ledger, **`UNIQUE(user_id, idempotency_key)`**, new deposits must carry a key) · `parking_spaces` (1–80) ·
`reservations` with **`UNIQUE(space_id, reservation_date)`** and **`UNIQUE(vehicle_id, reservation_date)`**.
Since V4 every table has a generated `BIGINT id` (wallets got their own `id`; `parking_spaces.id` is an identity continuing at 81) and a
`version BIGINT NOT NULL DEFAULT 0` for `@Version`. V4 is backward compatible with the previous application version.

### Data access

Spring Data JPA only: derived queries and JPQL, no `JdbcClient` / `JdbcTemplate` / `java.sql` in application code (enforced by `ArchitectureTest`).
Associations are unidirectional and `LAZY`, with no collections; per-use-case SQL statement counts are pinned by `QueryCountIT`
(the dashboard is one query whatever the number of reservations).

Native SQL is kept, inside the repositories, only where JPQL cannot express it:

| Statement | Where | Why native |
|---|---|---|
| `INSERT INTO reservations … ON CONFLICT DO NOTHING RETURNING id` | `ReservationRepository.insertIfFree` | No `ON CONFLICT` in JPQL. A JPA persist would raise a unique violation, which aborts the PostgreSQL transaction and breaks the replay lookup that must run in it |
| `INSERT INTO wallet_transactions … ON CONFLICT (user_id, idempotency_key) DO NOTHING` | `WalletTransactionRepository.insertDepositIfNew` | Same, for deposit idempotency |
| `SELECT true FROM pg_advisory_xact_lock(:key)` | `UserRepository.acquireTransactionLock` | PostgreSQL function (demo seeding across instances) |

These writes and the wallet updates are `Propagation.MANDATORY`: they only run inside the service's transaction.
The read queries that the reservation coordination calls outside any service transaction (`findConfirmedOn`, `findOccupancy`,
`findAllIds`, `findDayStatus`) declare `@Transactional(readOnly = true)` themselves (joining a caller's transaction, if any).

Wallet balances change only through two single-statement JPQL bulk `UPDATE`s (credit; conditional debit), which also increment `version`;
there is no read-modify-save. Bulk updates bypass the persistence context: they flush pending changes first but do not clear it (that would
detach entities the caller still uses), so a wallet loaded earlier in the same transaction is stale. The balance is therefore always read with a
scalar query, `WalletEntity` has no mutators (Hibernate never writes it), and `@Version` would reject a stale write.

## Reservation coordination (M3, before the transaction)

`ReservationService.reserve()` coordinates the instances through Redis before the unchanged transaction below. No database
connection is held while waiting for Redis, and every wait is bounded.

1. **Window** (no I/O), as before.
2. **Committed facts:** `ReservationStateCache` holds the reservations PostgreSQL has committed, per date, in one Redis hash
   (`s:{space}` and `v:{vehicle}` entries, written together by one HSET after the commit). If they decide the request, it is
   answered exactly as the transaction would answer it, without the transaction:
   - the vehicle holds a reservation: someone else's vehicle → `VEHICLE_NOT_FOUND`; another space → `VEHICLE_ALREADY_RESERVED`;
     this space → replay of the original reservation (201);
   - the space is taken: a cached vehicle owner that is not the caller → `VEHICLE_NOT_FOUND`; a **sold-out** date (see below)
     → `SPACE_ALREADY_RESERVED`; otherwise one read (`VehicleRepository.findDayStatus`, no permit) decides with the
     transaction's own rules.

   A missing entry proves nothing (its write may have failed), so it never rejects anything. Pending reservations are never
   cached: only commits are.
3. **Space lock:** a Redisson fair lock per (date, space), `tryLock(10 s, lease 20 s)`: one request per space at a time, in
   the order Redis received them, across instances. Different spaces never wait for each other. Unknown space ids skip the
   lock (no keys for arbitrary ids) and get the transaction's answer.
4. **Recheck** the committed facts under the lock. If the space was taken meanwhile, the lock is released first, then the
   request is answered as in step 2. Waiters behind a winner never run a transaction.
5. **Reservation permit:** the only Redis semaphore, global (default 4, `tryAcquire(3 s, lease 15 s)`), then the
   transaction below.
6. After the commit: release the permit, cache the reservation (best effort: a failed cache write never fails the
   reservation), release the lock.

Timeouts answer **503 `TEMPORARILY_UNAVAILABLE` + `Retry-After: 1`**. If Redis is unreachable, reservations
**fail closed** (503) without touching PostgreSQL. The dashboard falls back to PostgreSQL (at most one query per second per
instance), and the other APIs (deposits included) do not use Redis. The application starts without Redis (lazy Redisson initialization), and
Redis is not part of the health groups.

**Sold-out dates:** when one PostgreSQL read shows every space of a date reserved, `UNIQUE(space, date)` guarantees that no
reservation can ever be added for it. The complete set is then kept in memory per instance (validated: every known space has
an entry whose vehicle entry points back to it; the count alone is never trusted), and "this vehicle has no reservation"
becomes a fact. After sell-out, reservation requests are answered with zero SQL, apart from one owner lookup per unseen
vehicle per instance.

**Leases are soft limits.** A lease can expire while its holder still works (GC pause, slow commit): the next request may
then proceed, and PostgreSQL serializes the two. The connection pool stays the hard limit. Expired leases are counted
(`parking.lease.expired`). A request whose instance dies while queued blocks its space until its queue entry expires:
the holder's remaining lease plus the lock wait (about 30 s at most).

**One budget** (`parking.concurrency.reservation-permits`, global across instances): reservation transactions only. The
semaphore's name contains its size, so a new size applies at once, and every instance must run with the same size.
Deposits, the database read of step 2, the dashboard fallback and every other API take no permit and do not need Redis:
HikariCP alone limits them.

**What the budget does not do:**
- **It bounds concurrent reservation transactions; it does not reserve connections.** Because it is global, one instance
  may hold every permit, so its pool must be at least the budget (a startup WARN checks this). The local starting value
  of 10 covers the 4 permits plus headroom for deposits and reads.
- **Database work outside the budget shares the pool:** login, deposits, vehicle and wallet reads, my reservations, the
  step 2 read, the dashboard fallback. That work can still fill a pool; a reservation then waits up to the 3 s connection
  timeout (503, see `PoolExhaustionIT`).

**Redis data loss:** the semaphore is re-created with all its permits while transactions admitted before the loss may
still run. For that moment, up to twice the budget can be active (still bounded by each pool). Their late releases are
ignored, so the budget never inflates. This is tested.

**Never inside a transaction:** `ReservationService.reserve()` is `@Transactional(propagation = NEVER)`. It waits for
Redis, so inside a caller's transaction it would hold a connection while waiting; such a call fails immediately instead.
The dashboard is not transactional at all: a cached dashboard needs no connection (tested with the pool exhausted).

**Lost Redis replies:** if a lock request times out on the client after Redis already ran it, the request is retried from
the same thread. It resumes its queue entry (or keeps a single hold), counted by `parking.space_lock.ambiguous_retry`.
This is tested by pausing Redis past the client timeout. A lost *unlock* reply still leaves the space locked until its
lease expires.

**Metrics:**
- `parking.reservation.resolution{path=cache|database_read|transaction}`
- `parking.space_lock.wait{outcome}`
- `parking.db_permit.wait{outcome}` (the reservation permit)
- `parking.lease.expired{kind}`
- `parking.space_lock.ambiguous_retry`
- Hikari `hikaricp.*`

## Reservation transaction

`ReservationController` (non-transactional entry point) → `ReservationService.reserve()`: step 1 (and the coordination above) runs
before any transaction, steps 2–5 in one transaction, `ReservationBookingService.book()` (`@Transactional`):

1. **Window:** server clock in the business timezone. Target date = tomorrow, never supplied by the client; before the opening time → `RESERVATION_CLOSED`. Checked before the transaction starts, so answering "closed" to everyone clicking at 19:59 never takes a pool connection.
2. **Ownership:** the vehicle must belong to the caller → otherwise `VEHICLE_NOT_FOUND`.
3. **Claim:** `INSERT … ON CONFLICT DO NOTHING RETURNING id`. The unique constraints pick the winner under concurrency. On a conflict, the vehicle's reservation for that date is looked up:
   - same vehicle, same space, same user → the request is a **retry**: return the original reservation (201, same body), charge nothing;
   - vehicle holds a different space → `VEHICLE_ALREADY_RESERVED`;
   - otherwise the space is taken → `SPACE_ALREADY_RESERVED`.

   `ON CONFLICT` waits for an in-flight conflicting transaction like a plain `INSERT`, so a conflict always means a *committed* reservation, and it doesn't abort the transaction, so the lookup can run in it.
4. **Pay:** `UPDATE wallets SET balance_cents = balance_cents - 1000, …, version = version + 1 WHERE user_id = ? AND balance_cents >= 1000` (JPQL bulk update). 0 rows → `INSUFFICIENT_BALANCE`.
5. **Record:** ledger row. `ReservationConfirmedEvent` is published in the transaction and handled only `AFTER_COMMIT`.

Any exception rolls back steps 3–5, so a failed reservation never costs money.

If the database is temporarily out of reach (connection pool exhausted at the 8 PM peak, connection lost, lock or query
timeout), the API answers **503 `TEMPORARILY_UNAVAILABLE` with `Retry-After: 1`** instead of a 500, and logs one WARN line
with the root cause. Retrying is safe because reservations and deposits are idempotent.

## Ordering guarantees (first-come, first-served)

Preventing double bookings and serving requests in arrival order are different properties. PostgreSQL guarantees the first
under all conditions; the Redis fair lock (M3) provides the second per space, within the limits below.

**Guaranteed** (PostgreSQL constraints and transactions, any number of instances):

- at most one committed reservation per (space, date) and per (vehicle, date);
- a claim that later fails (insufficient balance, any error) rolls back and frees the space for the next claimer;
- no charge without a reservation, and never two charges for one reservation (retries are idempotent).

**First-come, first-served per space (M3):** requests for the same space and date are served in the order **Redis received
their fair-lock requests**, across all instances. If a request fails (insufficient balance, rollback), the next one in line
gets the space. Different spaces are independent: there is no global order, and the reservation permits are not fair across
spaces.

**Outside that guarantee** (before the lock request reaches Redis):
- network paths and Cloud Run's load balancing;
- instance cold starts and queueing;
- the cache read before the lock;
- the 20:00 gate, which each instance evaluates with its own (NTP-synchronized) clock.

**Order is also not kept when:**
- a request's wait times out (503; its retry joins the back of the queue);
- Redis loses its data (the queue is gone);
- a lease expires while its holder is still working (the next request proceeds, and PostgreSQL serializes the two).

Correctness never depends on the order. **Who wins** is still decided by PostgreSQL's unique constraints.

## Deposit idempotency

Deposits have no natural key, so the client generates a UUID per deposit and sends it as `Idempotency-Key`, reusing it when
retrying (the Angular dashboard keeps it until the deposit succeeds or the amount changes). In one transaction:
`INSERT` the ledger row `ON CONFLICT (user_id, idempotency_key) DO NOTHING`, and credit the wallet only if the row is new.
A repeated key returns the current balance without crediting; the same key with a different amount → `422 IDEMPOTENCY_KEY_REUSED`;
a missing or malformed key → `400 IDEMPOTENCY_KEY_INVALID`.

## Security

- **Passwords:** `stored = KMS.encrypt(BCrypt(password), aad = "parking-user:" + username)`. BCrypt and KMS run outside the DB
  transaction, then the user and wallet are created atomically. Unknown users and wrong passwords cost the same and return the same error.
- **JWT:** HS256, subject = user id, 12h TTL, key from Secret Manager. Validated by Spring Security's resource server. No HTTP session, no CSRF.
- **Fail-fast:** in `gcp` the app refuses to start without `KMS_KEY_NAME` or `JWT_SECRET`. It never falls back to local keys.
- **No secrets in logs:** passwords, hashes, ciphertexts and tokens are never logged. Records override `toString`, and error messages carry codes only.

## Profiles and configuration

| Profile | Purpose |
|---|---|
| `local` (default) | docker-compose PostgreSQL, local AES key |
| `test` | Integration tests (Testcontainers), window always open |
| `gcp` | Cloud SQL connector, Cloud KMS, secrets from env |
| `demo` | On top of `gcp`: opening time 00:00, seeds demo accounts |

Environment variables: `RESERVATION_OPEN_TIME` (20:00), `RESERVATION_TIMEZONE` (Asia/Ho_Chi_Minh), `DB_POOL_SIZE` (10: local starting value, ≥ the reservation permits),
`DB_CONNECTION_TIMEOUT_MS` (3000), `JWT_TTL` (12h), `KMS_KEY_NAME`, `JWT_SECRET`, `DB_*`, `INSTANCE_CONNECTION_NAME`, `DEMO_PASSWORD`,
`REDIS_ADDRESS` (redis://localhost:6379), `REDIS_PASSWORD`. Waits, leases and the reservation permit budget: `parking.concurrency.*`
in `application.yaml`. Request handling runs on virtual threads (`spring.threads.virtual.enabled`).

## Testing

| Level | Tooling | Scope |
|---|---|---|
| Unit (`./gradlew test`, 78) | JUnit 5, Mockito, `Clock.fixed`, standalone MockMvc | Auth, cipher, JWT, vehicles, deposits (idempotency), window, reservation outcomes (incl. replay), answers from committed facts (each rule), mappers, 503 mapping (DB, capacity, Redis), architecture rules (entity inheritance/naming, no direct JDBC, no entity in the API). No Docker/GCP |
| Integration (`./gradlew integrationTest`, 74) | Testcontainers PostgreSQL 17 + Redis 7.2, movable test clock, real HTTP port, Hibernate statistics | 150-user race, concurrency (space, vehicle, balance), idempotent retries, rollback after the debit, pool exhaustion, real 20:00 window and per-day rule, HTTP status codes and JSON shape, demo seeding, native repository statements, wallet version increments, SQL statement counts (incl. 0 for cached answers), V4 migration on existing data. M3: FIFO across instances, FIFO handoff when the holder cannot pay, waiters rejected without a transaction, global permit budget, bounded waits and queue cleanup, expired leases, crashed waiter, a burst of 300 waiters on one space, answers identical with an empty / warm / sold-out cache, lost cache write repaired, Redis unreachable (reservations fail closed, deposits and dashboard keep working, app still starts). Money/booking audit after every test |
| Smoke (`infra/smoke.ps1`, 25 checks with the window open) | Live HTTP | Full business flow against Cloud Run, including deposit and reservation retries |

`infra/deploy.ps1` runs unit **and** integration tests before every deploy.

## Extension points

- **Implemented in M3, in `ReservationService` rather than at the points planned in Phase 1:**
  - fast rejection, in the service and not the controller, because services own the transactions;
  - cache updates, written after `tx.execute()` returns and not in the `AFTER_COMMIT` listener, which still holds the JDBC
    connection. The listener keeps logging.
- **Not implemented:** per-user rate limiting, and Redis Pub/Sub notification of waiters (the standard fair-lock handoff
  drained 300 waiters in about 1 s locally).
- **Scaling knobs:** `DB_POOL_SIZE`, the `parking.concurrency.*` permit budget and waits, Cloud Run concurrency and min / max
  instances, the Cloud SQL tier. Keep `DB_POOL_SIZE × max instances` (× 2 for scale-out overshoot) well below Cloud SQL
  `max_connections`.
