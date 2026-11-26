# Phase 1 Status

**Phase 1 is complete (2026-11-26).** This is the stable baseline for Phase 2 (performance).

| | |
|---|---|
| Live URL | https://parking-reservation-866340516434.asia-southeast1.run.app |
| GCP project / region | `parking-project-demo-2026` / `asia-southeast1` |
| Cloud Run revision | `parking-reservation-00003-d67`, profiles `gcp,demo`, min 1 / max 2 instances |
| Cloud SQL | `parking-db`, PostgreSQL 17, `db-g1-small`, private IP only, `ENCRYPTED_ONLY` |

## Acceptance criteria

- [x] Functional application deployed on Cloud Run, reachable over HTTPS
- [x] Register and log in (stateless JWT)
- [x] Register a vehicle license plate
- [x] Deposit money and view the balance
- [x] Dashboard shows all 80 spaces; reserved spaces show the last 3 plate characters
- [x] Select and reserve an available space; exactly $10 deducted
- [x] Failed reservations deduct nothing (space taken, vehicle already booked, insufficient balance, window closed)
- [x] Duplicate space / vehicle reservations prevented by database constraints, including under concurrency
- [x] Backend unit tests pass (JUnit 5 + Mockito, no Docker/GCP)
- [x] Critical PostgreSQL integration tests pass (Testcontainers)
- [x] Cloud SQL private IP with enforced SSL/TLS (Java Connector over Direct VPC egress)
- [x] Cloud KMS password protection (BCrypt hash encrypted with KMS); secrets in Secret Manager
- [x] Demo testable without waiting until 20:00 (`demo` profile, no API bypass)
- [x] Redeploy with one command (`infra/deploy.ps1`)
- [x] Architecture documentation and AI usage log
- [ ] Manual UI walkthrough in a browser (**user**)
- [ ] Live URL shared and teammate feedback requested (**user**)
- [ ] Live smoke-test accounts cleaned up (**user**, see below)
- [ ] Mentor confirmations (see [decisions.md](decisions.md#pending-mentor-confirmation))

## Verification record (2026-11-26)

| Check | Result |
|---|---|
| `./gradlew test` (unit, JUnit 5 + Mockito) | **45 tests, 0 failures** in 7 classes: AuthService 6, LocalAesPasswordCipher 5, TokenService 1, ReservationService 8, ReservationWindow 5, VehicleService 10, WalletService 10 |
| `./gradlew integrationTest` (Testcontainers PostgreSQL 17) | **9 tests, 0 failures**: ReservationConcurrencyIT 5, DemoDataSeederIT 3, ParkingReservationApplicationTests 1 |
| `infra/smoke.ps1` against the live URL | **22/22 checks passed** |
| Live startup logs | Cloud KMS cipher initialised; Flyway validated V1–V2 (unchanged); demo seeding idempotent |
| Live logs scanned for secrets | 0 JWTs, 0 BCrypt hashes, 0 passwords |

Integration scenarios: 20 users racing for one space (exactly 1 winner, only the winner pays); one vehicle racing for 10 spaces
(1 reservation, 1 charge); 10 concurrent reservations against a $30 balance (exactly 3 succeed, balance $0, never negative);
complete rollback of each failure; 3 instances seeding demo data concurrently (3 complete accounts, no duplicates).

Smoke checks: health; register; duplicate username; wrong password; login; 401 without token; 2 vehicles; duplicate
(normalized) plate; invalid deposit; $15 deposit; 80-space dashboard; reserve S1 with V1; exactly $10 deducted; S1 shows
reserved / mine / plate suffix; V2→S1 `SPACE_ALREADY_RESERVED`; balance unchanged; V1→S2 `VEHICLE_ALREADY_RESERVED`;
balance unchanged and S2 free; V2→S2 `INSUFFICIENT_BALANCE`; balance unchanged and S2 free; my reservations = S1 only.

## Cleaning up the two live smoke-test accounts

The two live smoke runs created these accounts. Each holds 1 reservation for **2026-11-27** (spaces **80** and **79**),
2 vehicles and 2 ledger rows:

```
smoke-20261126-063302-aj8q   (space 80)
smoke-20261126-064020-6pvu   (space 79)
```

1. **Open Cloud SQL Studio:** Cloud Console → SQL → `parking-db` → *Cloud SQL Studio*.
   Database `parking`, user `parking_app`, built-in authentication. The password is in Secret Manager →
   `db-password` → latest version → *View secret value*. Do not copy it anywhere else.
   If Studio cannot reach the private-IP instance, stop. **Do not enable a public IP.**
2. **Preview (read-only).** It must return exactly these 2 rows:
   ```sql
   SELECT u.username, r.space_id, r.reservation_date
   FROM users u LEFT JOIN reservations r ON r.user_id = u.id
   WHERE u.username IN ('smoke-20261126-063302-aj8q', 'smoke-20261126-064020-6pvu')
     AND u.username ~ '^smoke-[0-9]{8}-[0-9]{6}-[a-z0-9]{4}$';
   ```
3. **Delete:** open [infra/smoke-cleanup.sql](../infra/smoke-cleanup.sql) and replace the `VALUES` list with:
   ```sql
       VALUES
           ('smoke-20261126-063302-aj8q'),
           ('smoke-20261126-064020-6pvu')
   ```
   Run it once. It is a single atomic statement. Expected result:
   `users_removed = 2, reservations_removed = 2, vehicles_removed = 4, ledger_rows_removed = 4`.
   Any other result means it touched something unexpected; the statement only deletes listed `smoke-…` usernames.
   **Since M3 is live on GCP (2026-11-27):** the deleted reservations stay in the Redis reservation cache. Clear it right
   after this step: [README, GCP cleanup](../README.md#gcp-cleanup-cloud-sql--memorystore), steps 2 and 3.
4. **Verify:** re-run the preview (0 rows). On the dashboard, spaces 79 and 80 are green again, unless a teammate has reserved them since.
5. Delete those lines from your local `infra/smoke-accounts.log`. The other two entries there came from local runs
   (Docker database) and do not exist in Cloud SQL.

Demo accounts (`demo1`–`demo3`) and teammate accounts can never match the cleanup pattern.
