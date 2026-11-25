# Parking Reservation System

80 parking spaces, $10/day, reservations open at 20:00 for the next day.
Spring Boot (Java 21) + Angular in **one container** on **Google Cloud Run**, PostgreSQL on **Cloud SQL** (private IP, SSL enforced).

## Prerequisites (local, Windows)

- JDK 21 with `JAVA_HOME` set (e.g. `C:\Users\<you>\.jdks\graalvm-jdk-21.0.7`)
- Docker Desktop (local PostgreSQL + Testcontainers)
- Node 24 (frontend dev only; the Docker build has its own Node)
- Google Cloud CLI (`gcloud`) for deployment

## Run locally

```powershell
docker compose up -d                 # PostgreSQL 17 on localhost:5432
cd backend; .\gradlew.bat bootRun    # http://localhost:8080 (profile: local)
cd frontend; npm start               # http://localhost:4200, proxies /api to :8080
```

## Tests

```powershell
cd backend
.\gradlew.bat test              # unit tests: JUnit 5 + Mockito, no Docker/GCP needed
.\gradlew.bat integrationTest   # Testcontainers PostgreSQL, needs Docker
```

## Deploy

1. One-time GCP setup in Cloud Shell: `PROJECT_ID=<id> REGION=asia-southeast1 bash infra/setup.sh`
2. Deploy from the repo root (runs unit tests first):
   `.\infra\deploy.ps1 -ProjectId <id>`

## Profiles

| Profile | Used for |
|---|---|
| `local` (default) | docker-compose PostgreSQL |
| `test` | integration tests (Testcontainers) |
| `gcp` | Cloud Run → Cloud SQL via Java Connector (private IP), secrets from Secret Manager |
| `demo` | added on top of `gcp` for the teammate demo |
