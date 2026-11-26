# AI Usage Log

| Date | Tool | What AI did | Human review / decision |
|---|---|---|---|
| 2026-11-25 | Claude (web, Claude Code) | Phase 1 plan, testing strategy, two-day execution plan | Plan reviewed and adjusted (timeline, smoke-test cleanup, events, exposure rule) |
| 2026-11-25 | Claude Code | M1: Gradle/Angular scaffold, Dockerfile, `infra/setup.sh`, `infra/deploy.ps1` | GCP setup and first deploy run by the developer |
| 2026-11-26 | Claude Code | M2: auth + KMS, vehicles, wallet, reservations, demo seeding, Angular UI, 45 unit + 9 integration tests, smoke test | Corrections requested: test execution check, no secret logging, timezone, cleanup scope, Flyway immutability, atomic registration, concurrent seeding |
| 2026-11-26 | Claude Code | Deployed M2 (revision 00003), fixed deploy/smoke scripts for Windows PowerShell 5.1, live smoke 22/22 | Developer verifies the UI manually and shares the URL |
| 2026-11-26 | Claude Code | Phase 1 finalization: secret / generated-file review, removed 2 unused test dependencies, tightened `.gcloudignore` / `.dockerignore`, docs, commit | Reviewed by the developer before Phase 2 |
