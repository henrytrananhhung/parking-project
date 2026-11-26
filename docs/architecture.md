# Architecture (Phase 1, as deployed)

```text
Browser ──HTTPS──> Cloud Run service "parking-reservation" (one container, Java 21)
                     ├── Angular SPA (static files bundled into Spring Boot, hash routing)
                     └── Spring Boot REST API /api/**  (stateless HS256 JWT)
                           │  Cloud SQL Java Connector (TLS), Direct VPC egress (private-ranges-only)
                           ▼
                   Cloud SQL "parking-db": PostgreSQL 17, private IP only, SSL mode ENCRYPTED_ONLY

Supporting services
  Cloud KMS       parking-keyring/password-key   encrypts BCrypt password hashes
  Secret Manager  db-password, jwt-signing-key   injected as env vars (--set-secrets)
  Cloud Build + Artifact Registry                gcloud run deploy --source .
  Cloud Logging                                  application stdout
Runtime identity: parking-run-sa (cloudsql.client, secretAccessor on 2 secrets, KMS encrypter/decrypter on 1 key, log/metric writer)
```

## Deployment settings

| Setting | Value (Phase 1) | Where |
|---|---|---|
| Cloud Run | 1 vCPU, 1 GiB, concurrency 80, min 1 (demo) / 0, max 2, CPU boost, 60 s timeout | `infra/deploy.ps1` |
| Cloud SQL | PostgreSQL 17 Enterprise, `db-g1-small`, zonal, 10 GB SSD | `infra/setup.sh` |
| Connection pool | Hikari, `DB_POOL_SIZE` = 5 | `application.yaml` |
| Image | node 24 (Angular build) → temurin 21 JDK (Gradle `bootJar`) → temurin 21 JRE, non-root | `Dockerfile` |

## Backend (`com.parking`, single Gradle module, package by layer)

| Package | Responsibility |
|---|---|
| `controller` | `Auth`, `Vehicle`, `Wallet`, `Reservation` controllers: parse, validate, wrap. Never transactional, never see entities |
| `service` | `AuthService` (register/login), `VehicleService` (plate normalization `51f-123.45` → `51F12345`, ownership), `WalletService` (idempotent deposits $0.01–$1,000), `ReservationService` (reserve, dashboard, my reservations), `ReservationWindow`. Own every transaction; return DTOs or primitives |
| `repository` | Spring Data JPA repositories (`repository/projection`: dashboard row record) |
| `entity` | `AbstractEntity` (IDENTITY `id` + `@Version`) and `UserEntity`, `VehicleEntity`, `WalletEntity`, `WalletTransactionEntity`, `ParkingSpaceEntity`, `ReservationEntity` |
| `dto/request`, `dto/response` | API records; the JSON contract (pinned by `ApiHttpIT`) |
| `mapper` | Entity / projection → response DTO (static methods) |
| `security` | `TokenService` (JWT), `PasswordCipher` → `KmsPasswordCipher` (`gcp`) / `LocalAesPasswordCipher` (other profiles), `CurrentUser` |
| `config` | `SecurityConfig`, `ClockConfig`, `DemoDataSeeder` (`demo` profile: `demo1`–`demo3` with $100 and one vehicle) |
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

Wallet balances change only through two single-statement JPQL bulk `UPDATE`s (credit; conditional debit), which also increment `version`;
there is no read-modify-save. Bulk updates bypass the persistence context: they flush pending changes first but do not clear it (that would
detach entities the caller still uses), so a wallet loaded earlier in the same transaction is stale. The balance is therefore always read with a
scalar query, `WalletEntity` has no mutators (Hibernate never writes it), and `@Version` would reject a stale write.

## Reservation transaction

`ReservationController` (non-transactional entry point) → `ReservationService.reserve()`: step 1 runs before any transaction,
steps 2–5 in one transaction (`TransactionTemplate`):

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

Preventing double bookings and serving requests in arrival order are different properties. The system guarantees the first, not the second.

**Guaranteed** (PostgreSQL constraints and transactions, any number of instances):

- at most one committed reservation per (space, date) and per (vehicle, date);
- a claim that later fails (insufficient balance, any error) rolls back and frees the space for the next claimer;
- no charge without a reservation, and never two charges for one reservation (retries are idempotent).

**Who wins:** the first transaction whose `INSERT` takes the unique-index entry in PostgreSQL and then commits, i.e. "first" as seen by the database.

**Not guaranteed:** that the winner is the user who clicked first, or whose request reached Cloud Run first. Requests can be reordered by:

- network paths and Cloud Run's load balancing across instances;
- thread scheduling in each instance and the wait for a pooled connection (not strictly FIFO);
- the vehicle-ownership `SELECT` that runs before the `INSERT`;
- PostgreSQL lock waits: when the holder of a contested key rolls back, the waiting transactions wake together and race again;
- the 20:00 gate, which each instance evaluates with its own (NTP-synchronized) clock.

Strict arrival ordering (pending question C in `decisions.md`) would need a different design, such as a single sequencer or queue; it is not
implemented.

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

Environment variables: `RESERVATION_OPEN_TIME` (20:00), `RESERVATION_TIMEZONE` (Asia/Ho_Chi_Minh), `DB_POOL_SIZE` (5),
`DB_CONNECTION_TIMEOUT_MS` (10000), `JWT_TTL` (12h), `KMS_KEY_NAME`, `JWT_SECRET`, `DB_*`, `INSTANCE_CONNECTION_NAME`, `DEMO_PASSWORD`.

## Testing

| Level | Tooling | Scope |
|---|---|---|
| Unit (`./gradlew test`, 62) | JUnit 5, Mockito, `Clock.fixed`, standalone MockMvc | Auth, cipher, JWT, vehicles, deposits (idempotency), window, reservation outcomes (incl. replay), mappers, 503 mapping, architecture rules (entity inheritance/naming, no direct JDBC, no entity in the API). No Docker/GCP |
| Integration (`./gradlew integrationTest`, 53) | Testcontainers PostgreSQL 17, movable test clock, real HTTP port, Hibernate statistics | 150-user race, concurrency (space, vehicle, balance), idempotent retries, rollback after the debit, pool exhaustion, real 20:00 window and per-day rule, HTTP status codes and JSON shape, demo seeding, native repository statements, wallet version increments, SQL statement counts (no N+1), V4 migration on existing data. Money/booking audit after every test |
| Smoke (`infra/smoke.ps1`, 25 checks with the window open) | Live HTTP | Full business flow against Cloud Run, including deposit and reservation retries |

`infra/deploy.ps1` runs unit **and** integration tests before every deploy.

## Extension points for Phase 2 (not implemented)

- **Request filtering, rate limiting, Redis fast-reject:** in `ReservationController`, before the DB transaction.
- **Cache updates (dashboard, taken spaces / vehicles):** `@TransactionalEventListener(AFTER_COMMIT)` on `ReservationConfirmedEvent`.
- **Dashboard cache:** single read path `ReservationService.dashboard()`.
- **Scaling knobs:** `DB_POOL_SIZE`, Cloud Run concurrency and min / max instances, Cloud SQL tier.
  Keep `DB_POOL_SIZE × max instances` below Cloud SQL `max_connections`.
