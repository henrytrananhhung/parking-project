# Parking Reservation System

80 parking spaces, $10 per day, reservations for tomorrow open daily at 20:00 (Asia/Ho_Chi_Minh).
Spring Boot (Java 21) + Angular in **one container** on **Google Cloud Run**, PostgreSQL on **Cloud SQL**
(private IP, SSL enforced), passwords protected with **Cloud KMS**.

**Live demo:** https://parking-reservation-866340516434.asia-southeast1.run.app

Docs: [architecture](docs/architecture.md) · [decisions & pending mentor questions](docs/decisions.md) ·
[Phase 1 status & verification](docs/phase1-status.md) · [AI usage log](docs/ai-log.md)

## Features

- Register / log in (stateless JWT). Passwords are BCrypt-hashed, then encrypted with Cloud KMS (local AES-GCM in dev/tests).
- Register vehicle license plates (normalized, globally unique).
- Wallet: view balance, deposit up to $1,000 per deposit (no payment gateway, no withdrawal).
- Dashboard of 80 spaces: available / reserved (last 3 plate characters) / mine.
- Reserve a space for tomorrow: $10 deducted atomically. PostgreSQL guarantees one vehicle per space per day, one space
  per vehicle per day, no negative balance, and full rollback when anything fails.

## Prerequisites

- JDK 21 (`java` on PATH or `JAVA_HOME` set)
- Docker Desktop (local PostgreSQL and integration tests)
- Node 24 (frontend development only; the Docker build has its own Node)
- Google Cloud CLI (`gcloud`), for deployment only

## Local development

```powershell
docker compose up -d                          # PostgreSQL 17 on :15432, Redis 7.2 on :6379 (password: parking)

cd backend
$env:RESERVATION_OPEN_TIME = '00:00'          # optional: reserve at any time of day
.\gradlew.bat bootRun                         # API on http://localhost:8080 (profile: local)

cd ..\frontend
npm install
npm start                                     # UI on http://localhost:4200, proxies /api to :8080
```

## Tests

```powershell
cd backend
.\gradlew.bat test              # 78 unit tests (JUnit 5 + Mockito); no Docker, GCP or network needed
.\gradlew.bat integrationTest   # 74 integration tests (Testcontainers PostgreSQL + Redis); Docker required
```

Unit tests mock repositories and the KMS-backed `PasswordCipher`; time comes from `Clock.fixed`.
Integration tests prove what mocks cannot: a 150-user race for the 80 spaces, concurrent reservations for the same space or
vehicle, concurrent balance deductions, retries and double submits (idempotency), rollback after the debit, connection-pool
exhaustion, the real 20:00 window (driven by a movable test clock), HTTP status codes, and concurrent demo seeding.
After every integration test, a check verifies that each balance equals its ledger total and every reservation was charged once.
Reports: `backend/build/test-results/`.
The frontend has no tests by design.

## Deployment (Cloud Run)

1. **One-time GCP setup** (Cloud Shell, idempotent): `PROJECT_ID=<id> REGION=asia-southeast1 bash infra/setup.sh`
2. **M3 infrastructure** (once, before the first M3 deploy; run by hand, step by step): [docs/m3-gcp-setup.md](docs/m3-gcp-setup.md)
   with `infra/setup-m3.ps1` (backup, Cloud SQL upgrade to 2 vCPU / `max_connections` 300, Memorystore `parking-redis`,
   secret `redis-password`).
3. **Deploy** from the repo root. Unit and integration tests run first (Docker must be running) and abort the deploy on failure.
   The script first checks that Redis exists and that the Cloud SQL connection budget fits; `-DryRun` stops after the checks
   and prints the command:
   ```powershell
   .\infra\deploy.ps1 -ProjectId parking-project-demo-2026 -Profiles gcp,demo   # teammate demo (window open all day)
   .\infra\deploy.ps1 -ProjectId parking-project-demo-2026                      # normal 20:00 rule
   ```
   Defaults: 2 vCPU, 2 GiB, concurrency 200, instances 1..10 (10 × 2 vCPU is this project's regional quota), pool 10,
   reservation permits 10; each is a
   parameter (`-Cpu`, `-Memory`, `-Concurrency`, `-MinInstances`, `-MaxInstances`, `-CpuAllocation`, `-DbPoolSize`,
   `-ReservationPermits`, `-RedisInstance` / `-RedisAddress`, `-RedisPasswordSecret`). For a load test, raise only the warm
   instances, without a new revision: `gcloud run services update parking-reservation --region asia-southeast1 --min 8`
   (and `--min 1` afterwards).
4. **Smoke test** the live service (creates one `smoke-…` account, uses one space):
   ```powershell
   .\infra\smoke.ps1 -BaseUrl https://parking-reservation-866340516434.asia-southeast1.run.app
   ```
5. **Roll back** if needed:
   `gcloud run services update-traffic parking-reservation --to-revisions=<revision>=100 --region asia-southeast1`

## Demo instructions (for teammates)

1. Open the live URL. Register an account, or log in as `demo1`, `demo2` or `demo3` (password `DemoPass2026`, $100 each, synthetic data).
2. Add a license plate (e.g. `51F-123.45`) and deposit some money.
3. Click a green space to reserve it for tomorrow ($10). Reserved spaces show the last 3 plate characters; yours are blue.
4. Try the rules: the same vehicle twice, a space that is already taken, or too little balance. Each is rejected and no money is taken.

The demo deployment keeps every business rule but opens the window all day (`demo` profile, opening time 00:00).
There is no API to bypass the opening time.

## Smoke-test cleanup

Each smoke run records its account in `infra/smoke-accounts.log` (git-ignored). To remove live smoke data, paste the usernames into
[infra/smoke-cleanup.sql](infra/smoke-cleanup.sql) and run it in Cloud SQL Studio. Only listed usernames that also match the strict
`smoke-yyyyMMdd-HHmmss-xxxx` pattern are deleted. Step-by-step: [docs/phase1-status.md](docs/phase1-status.md#cleaning-up-the-two-live-smoke-test-accounts).
M3 runs on GCP, so a smoke cleanup also needs the Redis step of [GCP cleanup](#gcp-cleanup-cloud-sql--memorystore) below;
otherwise the deleted reservation keeps showing as taken.

### Local cleanup (docker compose)

Any manual delete in PostgreSQL (smoke cleanup, `TRUNCATE`, a load-test reset) also needs the Redis cache and the app's
in-memory caches cleared. Reservation entries and vehicle owners are only ever *added* to those caches, so deleted rows
would keep being reported as reserved or owned.

```powershell
# 1. Stop every local app instance (bootRun / java -jar).
# 2. Delete the rows, e.g. the smoke accounts:
Get-Content infra\smoke-cleanup.sql | docker compose exec -T postgres psql -U parking -d parking
# 3. Clear all Redis coordination state. It is safe only while no instance runs; everything in it is reloaded from
#    PostgreSQL or re-created on demand.
docker compose exec redis redis-cli -a parking --no-auth-warning FLUSHDB
# 4. Start the instances again: their in-memory caches (sold-out snapshots, vehicle owners) start empty.
```

For a full reset, `docker compose down -v` removes the database volume. Redis keeps no data, so restarting it is enough.

### GCP cleanup (Cloud SQL + Memorystore)

The same rule applies to the live service: a manual delete in Cloud SQL (smoke accounts, clearing reservations) also
needs the Redis reservation cache cleared, and the instances replaced if a cleared date was sold out. Load-test data has
its own commands, which do all three steps: `lt-gcp.ps1 reset` / `cleanup`
([infra/loadtest/README.md](infra/loadtest/README.md#4-gcp-cloud-run-lt-gcpps1)).

1. **SQL** in Cloud SQL Studio (database `parking`, user `parking_app`): [infra/smoke-cleanup.sql](infra/smoke-cleanup.sql)
   for smoke accounts, or [infra/clear-reservations.sql](infra/clear-reservations.sql) to remove every reservation. The
   latter refunds each charge (users, vehicles, wallets and deposits stay); its last query must return `0 | 0`.
2. **Redis, after the SQL** (before it, a dashboard request would reload the rows still in the database). Memorystore has
   only a private IP: open a tunnel through the bastion and keep that window open. Use `gcloud.cmd`, not `gcloud`:
   PowerShell drops the `--` before the SSH options. The Redis IP is
   `gcloud redis instances describe parking-redis --region asia-southeast1 --format "value(host)"`.
   ```powershell
   gcloud.cmd compute ssh parking-bastion --zone=asia-southeast1-c --tunnel-through-iap --project=parking-project-demo-2026 -- -N -L 16379:10.164.4.187:6379
   ```
   Connect a Redis client (IntelliJ, Redis Insight) to `127.0.0.1:16379`, password = the AUTH string in secret
   `redis-password`, user `default` or none. Then:
   ```
   KEYS parking:reservations:*
   HEXISTS parking:reservations:<date> final     # 1 = that date was sold out
   DEL parking:reservations:<date>               # for each key listed
   ```
   Never `FLUSHDB` on GCP, and never delete `parking:lock:*` or `parking:permits:*`: they belong to reservations in progress.
3. **Restart** if any `HEXISTS` returned 1, or when unsure: each instance keeps a sold-out date in its own memory, out of
   Redis's reach. A new revision with the same image and settings; traffic moves when it is ready, no downtime:
   ```powershell
   gcloud run services update parking-reservation --region asia-southeast1 --project parking-project-demo-2026 --update-env-vars "LOADTEST_RESET=$(Get-Date -Format yyyyMMddHHmmss)"
   ```

Restoring a database dump always needs step 3: it resets the id sequences, so a new vehicle can get the id of a vehicle
the running instances still remember with its old owner, and its reservations would be refused (`VEHICLE_NOT_FOUND`).

## Project structure

```text
backend/    Spring Boot (Gradle Kotlin DSL): src/main, src/test (unit), src/integrationTest (Testcontainers)
frontend/   Angular (plain CSS)
infra/      setup.sh (GCP foundation), setup-m3.ps1 (M3 infrastructure), deploy.ps1, smoke.ps1, smoke-cleanup.sql,
            clear-reservations.sql
infra/loadtest/  k6 load tests: lt.sh (local Docker stack), lt-gcp.ps1 (deployed Cloud Run, from PowerShell)
docs/       architecture, decisions, Phase 1 status, AI log
Dockerfile  node → Gradle bootJar → Java 21 JRE
```
