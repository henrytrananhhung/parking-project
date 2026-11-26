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
docker compose up -d                          # PostgreSQL 17 on localhost:5432

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
.\gradlew.bat test              # 62 unit tests (JUnit 5 + Mockito); no Docker, GCP or network needed
.\gradlew.bat integrationTest   # 53 integration tests (Testcontainers PostgreSQL); Docker required
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
2. **Deploy** from the repo root. Unit and integration tests run first (Docker must be running) and abort the deploy on failure:
   ```powershell
   .\infra\deploy.ps1 -ProjectId parking-project-demo-2026 -Profiles gcp,demo -MinInstances 1   # teammate demo
   .\infra\deploy.ps1 -ProjectId parking-project-demo-2026                                      # normal 20:00 rule
   ```
3. **Smoke test** the live service (creates one `smoke-…` account, uses one space):
   ```powershell
   .\infra\smoke.ps1 -BaseUrl https://parking-reservation-866340516434.asia-southeast1.run.app
   ```
4. **Roll back** if needed:
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

## Project structure

```text
backend/    Spring Boot (Gradle Kotlin DSL): src/main, src/test (unit), src/integrationTest (Testcontainers)
frontend/   Angular (plain CSS)
infra/      setup.sh (GCP foundation), deploy.ps1, smoke.ps1, smoke-cleanup.sql
docs/       architecture, decisions, Phase 1 status, AI log
Dockerfile  node → Gradle bootJar → Java 21 JRE
```
