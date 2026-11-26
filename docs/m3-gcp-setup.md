# M3 on GCP: infrastructure setup (manual steps)

**Same application behavior, different infrastructure capacity.** M3 runs on the existing environment: the Cloud SQL
instance and the Cloud Run service are upgraded in place, and Memorystore for Redis is the only new component.
You run every command yourself, in the order below. Nothing here deploys M3: that is the next phase.

| | Today | After this guide (and the M3 deploy) |
|---|---|---|
| Cloud SQL `parking-db` | `db-g1-small` (shared core, 1.7 GB), `max_connections` 50 | `db-custom-2-7680` (2 dedicated vCPUs, 7.5 GB), `max_connections` 300. Same PostgreSQL 17, private IP, database, users, passwords and 10 GB disk |
| Memorystore | none | `parking-redis`: Redis 7.2, Basic, 1 GB, private, AUTH, `noeviction`, no persistence, no TLS |
| Secret Manager | `db-password`, `jwt-signing-key` | + `redis-password` (Redis AUTH string), readable by `parking-run-sa` |
| Cloud Run `parking-reservation` | 1 vCPU, 1 GiB, concurrency 80, max 2 (+ hidden service cap 3) | 2 vCPU, 2 GiB, concurrency 200, min 1 / max 10 (set by `infra/deploy.ps1` in the M3 deploy; 10 x 2 vCPU is the regional quota) |

Reused as they are: project, region, `parking-vpc`, `parking-run-subnet`, Direct VPC egress, Private Services Access,
`parking-run-sa`, Cloud KMS, Artifact Registry, the bastion. No new VPC, no Serverless VPC Access connector.

## Before you start

- Windows PowerShell, from the repo root, logged in as a project owner: `gcloud auth list`, `gcloud config get-value project`
  (should be `parking-project-demo-2026`).
- Every step exists in two forms: one line of [`infra/setup-m3.ps1`](../infra/setup-m3.ps1) (checks first, skips what is done,
  never prints secrets) and the plain `gcloud` command it runs. Use either. `-DryRun` prints a step's commands without
  running them, e.g. `.\infra\setup-m3.ps1 -Step redis -DryRun`.
- **Downtime:** only step 2 (the Cloud SQL upgrade restarts the database, usually a few minutes). Tell teammates first.
- **Application data:** nothing here reads, changes or deletes application data. Step 1 only adds a backup.
- **Cost:** see [Costs](#costs). With everything upgraded, the project costs about $225/month instead of about $57.

## Step 0: look before changing (read-only)

```powershell
.\infra\setup-m3.ps1 -Step status
```

Shows the current tier, flags, backups, Redis, the secret and the Cloud Run scaling. Expected today: `db-g1-small`,
`database flags: (none)`, `max_connections 50`, `DOES NOT FIT`, Redis API not enabled, Cloud Run `service-level max 3`.

**Verify the real connection limit** in Cloud SQL Studio: Console > SQL > `parking-db` > Cloud SQL Studio, database
`parking`, user `parking_app`, built-in authentication (password: Secret Manager > `db-password` > latest version >
View secret value; do not copy it anywhere else). Run:

```sql
SHOW max_connections;                 -- expected 50 (db-g1-small default)
SHOW superuser_reserved_connections;  -- expected 3 (not usable by the application)
SELECT datname, usename, count(*) FROM pg_stat_activity GROUP BY 1, 2 ORDER BY 3 DESC;  -- a handful today
```

If Studio cannot reach the private-IP instance, stop there: do not enable a public IP. The configured value is also in
`gcloud sql instances describe parking-db --format="value(settings.tier,settings.databaseFlags)"`.

## Step 1: back up `parking-db` and turn on deletion protection

```powershell
.\infra\setup-m3.ps1 -Step backup
# same as:
gcloud sql backups create --instance=parking-db --description=pre-m3
gcloud sql instances patch parking-db --deletion-protection
```

- **What:** an on-demand backup (the command waits until it has finished), then deletion protection.
- **Why:** automated backups are off on this instance. Step 2 restarts it and the M3 deploy migrates its schema (Flyway V3, V4).
  Deletion protection stops an accidental `instances delete`. Neither causes a restart.
- **Verify:** `gcloud sql backups list --instance=parking-db` shows `pre-m3` with status `SUCCESSFUL`. Console > SQL >
  `parking-db` > Backups. Step 2 refuses to run without a successful `pre-m3` backup from the last 24 hours.
- **Cost:** about $0.01/month (the database uses 103 MiB; backups cost $0.112/GiB per month).
- **Rollback:** `gcloud sql backups delete <ID> --instance=parking-db` and `gcloud sql instances patch parking-db --no-deletion-protection`.

## Step 2: upgrade `parking-db` (restarts the database)

```powershell
.\infra\setup-m3.ps1 -Step sql-upgrade      # asks you to type RESTART
# same as (today the instance has no other flags):
gcloud sql instances patch parking-db --tier=db-custom-2-7680 --database-flags=max_connections=300
```

- **What:** 2 dedicated vCPUs and 7.5 GB instead of a shared core and 1.7 GB, and `max_connections` 300 instead of 50.
  Version, private IP, network, SSL mode, database, users, passwords and data are unchanged.
- **Careful:** `--database-flags` replaces the instance's whole flag list. The script re-sends every existing flag and
  prints the old values for rollback. If you type the command yourself, include any flag that `status` lists.
- **Why these sizes** (measured, not guessed):
  - CPU: in the best local run (1,223 req/s), PostgreSQL used 0.4 to 0.8 of a core. 2 dedicated vCPUs leave about 2.5x
    headroom; a shared core does not (no SLA, burst only).
  - Memory: the data is only 103 MiB. Memory is sized for up to about 250 connections (a few MB each) plus PostgreSQL's
    buffers; 7.5 GB is the standard 2-vCPU shape.
  - Storage: **kept at 10 GB.** 103 MiB is used. SSD speed grows with disk size (about 300 IOPS at 10 GB): watch
    Console > SQL > `parking-db` > System insights > Disk read/write operations during the load test. If it is the limit,
    `gcloud sql instances patch parking-db --storage-size=20` grows it without a restart, but a disk can never shrink again.
- **Connection budget** (why 300; `infra/deploy.ps1` refuses any deploy that does not fit):

  | Situation | Application connections | With admin (+10) | Of 300 |
  |---|---|---|---|
  | Normal operation: 1 instance x pool 10 (each instance opens its pool at start) | 10 | 20 | 7% |
  | Load test: 8 warm instances, autoscaling up to the maximum of 10 | 80 to 100 | 110 | 37% |
  | Deploy or revision replacement: old and new revision side by side, at most 2 x 10 | 200 | 210 | 70% |

  The admin share covers the superuser reserved slots (3), Cloud SQL's own `cloudsqladmin` sessions (2 seen) and
  Cloud SQL Studio / psql (~5). The maximum of 10 instances comes from this project's Cloud Run quotas in
  asia-southeast1 (20 vCPUs, 10 instances with Direct VPC egress); `deploy.ps1` checks both before its tests run.
- **Verify:** `.\infra\setup-m3.ps1 -Step status` shows `db-custom-2-7680`, `max_connections=300`, `fits`. In Cloud SQL
  Studio, `SHOW max_connections;` returns `300`. The demo still answers: open `<service URL>/actuator/health` (shows `UP`
  once the database is back; the connection pool reconnects by itself).
- **Downtime:** one restart, usually a few minutes. During it, the demo's database calls fail (503 / errors).
- **Cost:** $0.049/h becomes $0.189/h: about +$3.36/day, +$102/month.
- **Rollback:** another restart. If M3 is already deployed with max 10, lower Cloud Run first so the budget fits 50
  connections again: `gcloud run services update parking-reservation --region=asia-southeast1 --max=2` (2 x 10 x 2 + 10 = 50).
  Then `gcloud sql instances patch parking-db --tier=db-g1-small --clear-database-flags` (or `--database-flags=<old list>`
  if `status` showed other flags). Restoring the step 1 backup (`gcloud sql backups restore <ID> --restore-instance=parking-db`)
  **overwrites all current data**: only on purpose, after agreement.

## Step 3: enable the Memorystore API

```powershell
.\infra\setup-m3.ps1 -Step redis-api
# same as:
gcloud services enable redis.googleapis.com
```

- **Why:** the API is off in this project; Memorystore cannot be created without it.
- **Verify:** `gcloud services list --enabled --filter=config.name=redis.googleapis.com`, or Console > APIs & Services > Enabled APIs.
- **Cost:** none. **Rollback:** `gcloud services disable redis.googleapis.com` (only after step 4 is rolled back).

## Step 4: create Memorystore `parking-redis` (5 to 10 minutes)

```powershell
.\infra\setup-m3.ps1 -Step redis
# same as:
gcloud redis instances create parking-redis --region=asia-southeast1 --zone=asia-southeast1-c `
  --tier=basic --size=1 --redis-version=redis_7_2 `
  --network=projects/parking-project-demo-2026/global/networks/parking-vpc `
  --connect-mode=private-service-access --reserved-ip-range=google-managed-services-parking `
  --enable-auth --transit-encryption-mode=disabled --persistence-mode=disabled `
  --redis-config=maxmemory-policy=noeviction --labels=app=parking,component=m3
```

| Setting | Why |
|---|---|
| `redis_7_2` | The version the code is tested against (docker compose, Testcontainers, load tests) |
| `basic`, 1 GB | No replica. Everything in Redis can be rebuilt (the semaphore is re-created, the reservation cache reloads from PostgreSQL), so high availability is not needed for correctness. Local load tests used about 16 MiB |
| `parking-vpc`, `private-service-access`, `google-managed-services-parking` | Uses the Private Services Access range and peering Cloud SQL already uses: no new peering, range or firewall rule. Memorystore has no public IP |
| `--enable-auth` | A password (AUTH string) is required, so other machines in the VPC (e.g. the bastion) cannot use it without the secret |
| `maxmemory-policy=noeviction` | Space locks and permits expire on their own (TTL). Memorystore's default, `volatile-lru`, would silently delete such keys when memory runs low. With `noeviction` a full Redis refuses writes, and the app answers 503 instead of losing a lock. Same as local |
| `--persistence-mode=disabled` | Nothing to keep across restarts; same as local (`--save "" --appendonly no`) |
| `--transit-encryption-mode=disabled` | See limitations below. Can only be chosen at creation |
| `asia-southeast1-c` | Same zone as `parking-db` and the bastion |

**How Cloud Run will reach Redis** (the same path it uses for Cloud SQL today):

```text
Cloud Run parking-reservation
  -> Direct VPC egress (parking-vpc / parking-run-subnet 10.10.0.0/24, private-ranges-only: 10.x goes into the VPC)
  -> VPC peering servicenetworking-googleapis-com (Private Services Access, 10.164.0.0/20)
  -> parking-redis 10.164.x.y:6379   (Cloud SQL parking-db is 10.164.0.3 on the same path)
Redisson authenticates every connection with REDIS_PASSWORD (Secret Manager redis-password, steps 5-6).
```

**Security and TLS limitations:**

- No TLS: Redis traffic, including the AUTH string, is not encrypted by the client. It stays on Google's private network
  (VPC and peering) and is never exposed publicly. TLS would need a new instance (the mode is fixed at creation) and a
  backend change (`rediss://`, port 6378, trusting Memorystore's CA in `RedisConfig`). Recreating is cheap: Redis holds
  only rebuildable state.
- AUTH is a single shared password, no users or ACLs. IAM does not control Redis access: anyone who can reach the VPC
  (for example through the bastion) and read the secret can use Redis. Bastion access is therefore privileged.
- Basic tier: maintenance or a failure restarts Redis empty. The app recovers by itself (reservations answer 503 until it
  is back, then the semaphore and cache are rebuilt), but first-come-first-served order is not kept during that moment.
- One Redis per database: key names have no environment prefix, so never share `parking-redis` with another database.
- **Verify:** `.\infra\setup-m3.ps1 -Step status` lists every setting as `ok`. Console > Memorystore > Redis instances >
  `parking-redis` (Configuration: version, tier, AUTH, connection mode; Connections: IP and port).
- **Downtime:** none. The running application does not use Redis until M3 is deployed.
- **Cost:** $0.065/h: about $1.56/day, $47/month. It cannot be paused; billing stops only when it is deleted.
- **Rollback:** `gcloud redis instances delete parking-redis --region=asia-southeast1` (first roll back M3, which answers
  every reservation with 503 without Redis).

## Step 5: store the AUTH string in Secret Manager

```powershell
.\infra\setup-m3.ps1 -Step redis-secret
```

- **What:** copies the instance's AUTH string into the secret `redis-password` (creates it, or adds a version if the
  string changed). The value goes through a temporary file that is deleted immediately and is never printed.
- **Why:** Cloud Run injects it as `REDIS_PASSWORD` (`--set-secrets`), like `DB_PASSWORD`. The string is generated by
  Google; you cannot choose it.
- The same by hand, without printing it (a PowerShell pipe would add a newline to the secret, hence the file):

  ```powershell
  $auth = gcloud redis instances get-auth-string parking-redis --region=asia-southeast1 --format="value(authString)"
  $tmp = [IO.Path]::GetTempFileName(); [IO.File]::WriteAllText($tmp, $auth)
  gcloud secrets create redis-password --replication-policy=automatic --data-file=$tmp
  Remove-Item $tmp; Remove-Variable auth
  ```
- **Verify:** `.\infra\setup-m3.ps1 -Step status` shows `matches the instance's current AUTH string: True`.
  `gcloud secrets versions list redis-password`. Console > Security > Secret Manager > `redis-password`.
- **Cost:** a few cents per month. **Rollback:** `gcloud secrets delete redis-password` (only while no revision uses it).
- If the AUTH string ever changes (instance recreated, AUTH rotated), run this step again and redeploy: instances read
  `latest` when they start.

## Step 6: let the Cloud Run service account read the secret

```powershell
.\infra\setup-m3.ps1 -Step iam
# same as:
gcloud secrets add-iam-policy-binding redis-password `
  --member=serviceAccount:parking-run-sa@parking-project-demo-2026.iam.gserviceaccount.com `
  --role=roles/secretmanager.secretAccessor
```

- **Why:** Cloud Run reads secrets as `parking-run-sa`, which can read only `db-password` and `jwt-signing-key` so far.
  No Redis role is needed: Memorystore access is controlled by network + AUTH, not IAM.
- **Verify:** `gcloud secrets get-iam-policy redis-password`; Console > Secret Manager > `redis-password` > Permissions.
- **Cost:** none. **Rollback:** `gcloud secrets remove-iam-policy-binding redis-password --member=... --role=...` (same arguments).

## Step 7: check the network path from the bastion

The bastion is in `parking-run-subnet`, the subnet Cloud Run's Direct VPC egress uses, so it sees Redis exactly as Cloud
Run will. It has no internet access (no packages to install); bash's built-in `/dev/tcp` is enough:

```powershell
$redisIp = gcloud redis instances describe parking-redis --region=asia-southeast1 --format="value(host)"
gcloud compute ssh parking-bastion --zone=asia-southeast1-c --tunnel-through-iap `
  --command="timeout 3 bash -c '</dev/tcp/$redisIp/6379' && echo REDIS_REACHABLE || echo REDIS_UNREACHABLE"
```

Expected: `REDIS_REACHABLE`. The first `gcloud compute ssh` creates an SSH key and registers it with your OS Login
profile. Alternative: Console > Compute Engine > VM instances > `parking-bastion` > SSH, then run
`timeout 3 bash -c '</dev/tcp/<redis IP>/6379' && echo REDIS_REACHABLE`.
(The AUTH string itself is proven by the M3 deploy: reservations work only if Redis accepts it.)

## Step 8: final check

```powershell
.\infra\setup-m3.ps1 -Step status
```

Ready for the M3 deploy when it shows: `db-custom-2-7680`, `max_connections 300` and `fits`; a `SUCCESSFUL` `pre-m3`
backup; every Redis setting `ok`; the secret matching the AUTH string and readable by `parking-run-sa`.
Cloud Run still shows the old revision (`max 3`, `REDIS_ADDRESS set: False`): the deploy changes that.

## Next phase (do not run yet): deploy M3

```powershell
.\infra\deploy.ps1 -ProjectId parking-project-demo-2026 -Profiles gcp,demo -DryRun   # checks + the exact command
.\infra\deploy.ps1 -ProjectId parking-project-demo-2026 -Profiles gcp,demo           # tests, build, deploy
```

- `-Profiles gcp,demo` keeps today's demo behavior (reservation window open all day). The defaults are the settings
  above: 2 vCPU, 2 GiB, concurrency 200, min 1 / max 10, pool 10, permits 10, Redis `parking-redis`, CPU allocated
  per request, startup CPU boost, 60 s timeout. Every other application setting keeps its `application.yaml` value, as in
  the best local load test (virtual threads; 3 s connection timeout; lock 10 s / 20 s; permit 3 s / 15 s; Redisson pools,
  retries and timeouts).
- The deploy sets the maximum (10) at **both** service and revision level, so the two agree (Cloud Run always applies the
  lower one); that replaces today's hidden service-level cap of 3. The minimum is set at service level only, so
  `--min 8` / `--min 1` later needs no new revision.
- On start, Flyway applies V3 and V4 to the existing data (additive, backward compatible; covered by the step 1 backup).
- **Garbage collector:** JVM options stay the Dockerfile's, so Java chooses. With the same base image and limits, a local
  run selects G1 (the 1 vCPU / 1 GiB service today and the 2 CPU / 1 GiB local load test used SerialGC):

  ```powershell
  docker run --rm --cpus 2 --memory 2g eclipse-temurin:21-jre java -XX:MaxRAMPercentage=75 -XX:+PrintFlagsFinal -version |
    Select-String 'Use(Serial|Parallel|G1|Z)GC '        # expected: UseG1GC = true
  ```

  To confirm on Cloud Run after the deploy without changing the service or exposing any endpoint, run the deployed image
  once as a job with the same CPU and memory, read its output, and delete it (costs less than $0.01):

  ```powershell
  $image = gcloud run services describe parking-reservation --region=asia-southeast1 --format="value(spec.template.spec.containers[0].image)"
  gcloud run jobs create gccheck --region=asia-southeast1 --image=$image --cpu=2 --memory=2Gi --max-retries=0 --task-timeout=120 `
    --service-account=parking-run-sa@parking-project-demo-2026.iam.gserviceaccount.com `
    --command=java --args=-XX:+PrintFlagsFinal,-version --execute-now --wait
  gcloud logging read "resource.type=cloud_run_job AND resource.labels.job_name=gccheck AND (textPayload:UseG1GC OR textPayload:UseSerialGC)" `
    --freshness=1h --limit=5 --format="value(textPayload)"
  gcloud run jobs delete gccheck --region=asia-southeast1 --quiet
  ```
- **Load-test window:** raise the warm instances shortly before a test and lower them afterwards. No new revision, no build:
  `gcloud run services update parking-reservation --region=asia-southeast1 --min=8`, then `--min=1`.
- **Load tests against Cloud Run:** the runbook in [infra/loadtest/README.md](../infra/loadtest/README.md), section 4
  (`lt-gcp.ps1`). It needs no further GCP change: test users are registered through the public API and use the tokens the
  service issues (the JWT signing key is never exported), and its SQL and Redis commands go through the existing bastion.

## Costs

List prices for asia-southeast1 (Cloud Billing Catalog, November 2026), USD:

| Resource | Today | After | Change |
|---|---|---|---|
| Cloud SQL `parking-db` | `db-g1-small` $0.049/h (~$36/month) | 2 x $0.0578 vCPU + 7.5 x $0.0098 GB = $0.189/h (~$138/month) | +$102/month |
| Cloud SQL disk 10 GB SSD, backup | ~$2.40/month | unchanged, + ~$0.01 backup | - |
| Memorystore Basic 1 GB | - | $0.065/h (~$47/month) | +$47/month |
| Secret `redis-password` | - | a few cents/month | - |
| Cloud Run, min 1 instance idle (request-based billing) | 1 vCPU / 1 GiB: ~$0.025/h (~$18/month) | 2 vCPU / 2 GiB: ~$0.050/h (~$37/month) | +$18/month (from the M3 deploy) |
| Cloud Run during a load test | - | ~$0.27/h per busy instance: 8 busy ~$2.14/h, 10 busy ~$2.67/h | per test hour |
| **Total standing** (resources in this table; not the bastion VM, logs or Artifact Registry) | **~$57/month** | **~$225/month** | |

The project budget alert from `infra/setup.sh` is $150/month (if it was configured). To save money between test
sessions, the Cloud SQL upgrade can be reversed (step 2 rollback, one restart each way); Memorystore can only be deleted.

## Rollback summary (reverse order)

| Undo | Command | Downtime |
|---|---|---|
| M3 deploy (next phase) | `gcloud run services update-traffic parking-reservation --region=asia-southeast1 --to-revisions=<old revision>=100` | none |
| Step 6 | `gcloud secrets remove-iam-policy-binding redis-password --member=serviceAccount:parking-run-sa@parking-project-demo-2026.iam.gserviceaccount.com --role=roles/secretmanager.secretAccessor` | none |
| Step 5 | `gcloud secrets delete redis-password` | none |
| Step 4 | `gcloud redis instances delete parking-redis --region=asia-southeast1` | M3 reservations answer 503 |
| Step 3 | `gcloud services disable redis.googleapis.com` | none |
| Step 2 | (lower Cloud Run max to 2 if M3 is deployed) `gcloud sql instances patch parking-db --tier=db-g1-small --clear-database-flags` | restart, a few minutes |
| Step 1 | `gcloud sql instances patch parking-db --no-deletion-protection`; `gcloud sql backups delete <ID> --instance=parking-db` | none |
