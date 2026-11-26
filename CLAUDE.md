# CLAUDE.md
## Tech stacks
- **Backend**: Java 21 (virtual threads preferred, not enabled yet), Spring Boot 4.2 — Web MVC, Data JPA, Security (OAuth2 resource server), Validation, Actuator
- **Database**: PostgreSQL 17, schema migrations with Flyway
- **Frontend**: Angular 22, TypeScript 6
- **Testing**: JUnit 5 + Mockito + AssertJ (unit), Testcontainers + PostgreSQL (integration)
- **Deployment**: GCP — Cloud Run, Cloud SQL (Cloud SQL Java Connector), Cloud KMS, Secret Manager, Artifact Registry; provisioned by the gcloud scripts in `infra/`
- **No Redis yet** — add it only if horizontal scaling needs distributed locking (see Architecture Considerations)

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Cloud-based parking space reservation system where users register license plates, deposit funds, and compete to reserve parking spaces at 8 PM daily for the following day.

### Key Requirements
- 80 parking spaces (numbered 1-80), each costing $10/day
- 1,000 concurrent users competing for spaces at 8 PM
- First-come, first-served reservation logic controlled by backend
- Each vehicle can only reserve one space per day
- Payment deducted immediately; insufficient balance fails reservation

## Architecture Considerations

This system requires handling high concurrency (1,000 simultaneous users):
- Use database-level locking or optimistic locking to prevent double-booking
- Consider Redis for distributed locking if horizontally scaled (luascript deleted check is preferred)
- Design for autoscaling to handle traffic spikes at 8 PM
- Implement idempotency for reservation requests to handle retries safely

## Core Features
1. **Status Display**: Show all 80 parking spaces with availability and last 3 digits of reserved license plates
2. **Reservation**: Time-gated competition at 8 PM with immediate payment processing
3. **Deposits**: Simple balance top-up via text input (no withdrawals)

## Build Commands

Gradle (Kotlin DSL) project in `backend/`. Run commands from `backend/`:

- `./gradlew build` — compile + unit tests
- `./gradlew test` — unit tests only (JUnit 5 + Mockito + AssertJ; no Docker, no GCP, no network)
- `./gradlew integrationTest` — integration tests in `src/integrationTest` (Testcontainers + real PostgreSQL; requires Docker). Not part of `test` or `check`.
- `./gradlew bootRun` — run the app locally (profile: `local`); start the database first with `docker compose up -d` from the repo root.
