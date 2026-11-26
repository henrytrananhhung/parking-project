# Decisions

| # | Decision | Why |
|---|---|---|
| 1 | One Cloud Run service, Angular bundled into Spring Boot | One container, one URL, no CORS, one deploy command |
| 2 | PostgreSQL constraints + conditional UPDATE in one transaction decide correctness | Correct under any number of Cloud Run instances; Redis later only reduces load |
| 3 | Cloud SQL Java Connector, private IP, `ENCRYPTED_ONLY` | TLS without managing certificates; no public IP |
| 4 | Secrets via `--set-secrets` env vars | No Secret Manager client code |
| 5 | BCrypt hash encrypted with Cloud KMS (AAD = username) | Interpretation of "passwords encrypted with AES/RSA" (pending, see below) |
| 6 | HS256 JWT via Spring Security resource server, 12h TTL | Stateless; logins stay off the 20:00 reservation hot path |
| 7 | Demo mode = `demo` profile (open time 00:00), no API override | Same rules and code path; cannot be bypassed by clients |
| 8 | Demo seeding in one transaction behind `pg_advisory_xact_lock` | Safe when several instances start at once |
| 9 | Business timezone `Asia/Ho_Chi_Minh`; window 20:00 → midnight for tomorrow | Pending, see below; both configurable |
| 10 | First-come-first-served decided by database-level competition | Pending, see below |
| 11 | One space per vehicle per day; a user may own several vehicles; no cancellation | Phase 1 assumption (pending, see below) |
| 12 | Unit tests (Mockito) separate from Testcontainers integration tests | `./gradlew test` needs no Docker or GCP |
| 13 | `DB_POOL_SIZE × max instances < Cloud SQL max_connections` | Avoid connection exhaustion when scaling (db-g1-small ≈ 50) |
| 14 | Reservation idempotency by natural key: `(vehicle, date)` + space, via `INSERT … ON CONFLICT DO NOTHING` | A retry of a successful request returns the original reservation instead of a 409; no idempotency-key table. Conflicts raise no SQL errors, so Hibernate error logging stays on |
| 15 | Flyway migrations are immutable once deployed | Schema changes go into new `V3+` migrations |
| 16 | Temporary DB failures → `503 TEMPORARILY_UNAVAILABLE` + `Retry-After: 1` | Pool exhaustion at 8 PM is load, not a bug; tells clients to retry, which is safe for idempotent reservations and deposits |
| 17 | Deposits require an `Idempotency-Key` header (UUID), `UNIQUE(user_id, idempotency_key)` in the ledger (V3) | Deposits have no natural key; a retried deposit is applied once. Required, not optional: a client that forgets it would silently double-credit |
| 18 | Reservation window checked before the DB transaction (`TransactionTemplate`, as in `AuthService`) | "Closed" answers at 19:59 never take a pool connection |
| 19 | Deploy gate runs unit **and** integration tests | Only the integration tests prove the concurrency, payment and idempotency rules |
| 20 | Integration tests run the real 20:00 config with a movable `MutableClock` and BCrypt cost 4 | Tests the shipped window rule and dates deterministically; the 150-user race stays fast |

## Pending mentor confirmation

These are implemented with the defaults below. Each one is configurable or isolated, so a different answer means a small change.

| # | Question | Current behaviour | If the answer differs |
|---|---|---|---|
| A | Is "passwords encrypted with AES/RSA" satisfied by **BCrypt + Cloud KMS (AES-256-GCM)** encryption of the hash? | `stored = KMS.encrypt(BCrypt(pw), aad=username)` | Change only `PasswordCipher` / `AuthService` |
| B | Reservation window and timezone | Opens 20:00 **Asia/Ho_Chi_Minh** for tomorrow, closes at midnight | `RESERVATION_OPEN_TIME`, `RESERVATION_TIMEZONE`; closing rule in `ReservationWindow` |
| C | Does FCFS require **strict request-arrival ordering**, or is database-level competition acceptable? | First transaction to insert wins (unique constraint) | Phase 2 design topic (queue / ordering) |
| D | Can one user reserve several spaces with different vehicles on the same day? | Yes (limit is per vehicle) | Add a `(user_id, reservation_date)` unique constraint in a new migration |
| E | Cancellation / refunds? | Not supported | New feature |
