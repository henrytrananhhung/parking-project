# Load and concurrency tests (k6)

Two targets, kept apart:

- **Local** (sections 1 to 3): a **disposable local stack** started by [lt.sh](lt.sh). It has its own Docker network
  `parking-lt` with PostgreSQL 17, Redis 7.2, N application instances (default 3 × 2 CPUs / 1 GiB, see
  [Stack configuration](#stack-configuration)) and k6 `grafana/k6:1.3.0` (4 CPUs). It never touches the docker-compose
  database, Cloud Run or any shared environment. These scripts reserve spaces, move money and sign their own JWTs, so
  never point them anywhere else. Results: `results/NAME.*`.
- **GCP** ([section 4](#4-gcp-cloud-run-lt-gcpps1)): the deployed Cloud Run service, k6 on your machine, driven by
  [lt-gcp.ps1](lt-gcp.ps1) from PowerShell. No Docker, no `lt.sh`. Same workload and pass criteria, its own users,
  tokens issued by the service, demo data left alone. Results: `results/gcp/NAME.*`.

| File | Purpose |
|---|---|
| [lib/stress-workload.js](lib/stress-workload.js) | Shared by both stress tests: the workload, outcome classes, metrics and thresholds |
| [load-stress-test.js](load-stress-test.js) | Local sustained load: 70% reserve / 20% parking availability / 10% deposit, constant arrival rate, default 1,500 TPS for 180 s |
| [m3.js](m3.js) | Local concurrency and correctness scenarios (`one-space`, `all-spaces`, `fifo`) and the older `mix` read/reservation workloads, including the `warmup` mix |
| [reserve-8pm.js](reserve-8pm.js) | End-to-end 8 PM race through the public API (register → deposit → vehicle → reserve), against a single local app |
| [seed.sql](seed.sql) | Local: 5,000 funded users ($100, one vehicle each, vehicle id = user id) + 300 users with $0 |
| [verify.sql](verify.sql) | Money and booking audit, local and GCP: every violation count must be 0 |
| [lt.sh](lt.sh) | Local stack lifecycle, test runner, diagnostics and report |
| [load-stress-test-gcp.js](load-stress-test-gcp.js) | GCP: the same sustained load against Cloud Run (`MODE=warmup`: reads only) |
| [seed-gcp.js](seed-gcp.js) | GCP: registers the load-test users through the public API (vehicle, $100 deposit) and keeps their tokens |
| [gcp-check.sql](gcp-check.sql) | GCP, read-only: load-test rows, tomorrow's reservations, and a fingerprint of all other (demo) data |
| [gcp-reset.sql](gcp-reset.sql) | GCP, before every benchmark: removes the load-test users' reservations and refunds them (users stay) |
| [gcp-cleanup.sql](gcp-cleanup.sql) | GCP, end of session: deletes every load-test user (`gcplt-…`) and their rows, nothing else |
| [lt-gcp.ps1](lt-gcp.ps1) | GCP runbook commands: preflight, seed, reset, run (warm-up + load + audit + monitoring + report), cleanup |

## Prerequisites

Docker and Git Bash (or Linux), plus a current application jar:

```bash
cd backend && ./gradlew bootJar && cd ..
infra/loadtest/lt.sh up        # start the stack and seed it (about 1.5 min)
infra/loadtest/lt.sh down      # remove everything when done
```

`lt.sh reset` restores the seed: it truncates the data, flushes Redis and restarts the instances cold. Do this before
any test that needs free spaces. The stack opens the reservation window all day (`RESERVATION_OPEN_TIME=00:00`).
`APP_ENV="KEY=VALUE ..."` overrides application settings on the next reset.

### Stack configuration

Set as environment variables; `up` and `reset` (so also `bench`) apply them, and every instance gets the same values.
`run`, `load`, `metrics` and `semaphore` always use the instances actually running; `lt.sh config` prints them
(from `docker inspect`), and every run records them in `NAME.env.txt` and on the report's `stack:` line.

| Variable | Default | Meaning |
|---|---|---|
| `INSTANCES` | 3 | application instances `lt-app-1..N`, host ports 18081.. |
| `APP_CPUS` / `APP_MEMORY` | 2 / 1g | CPU quota and memory limit per instance. Below ~1.75 GiB the JVM does not count as server-class and uses SerialGC |
| `PERMITS` | 4 | global reservation semaphore permits (`parking.concurrency.reservation-permits.permits`); still one semaphore for all instances |
| `DB_POOL_SIZE` | 10 | HikariCP `maximum-pool-size` per instance |
| `PG_HEADROOM` | 20 | `reset` refuses to start when `INSTANCES × DB_POOL_SIZE + PG_HEADROOM` exceeds PostgreSQL's `max_connections` (100) |

```bash
INSTANCES=5 PERMITS=10 WARMUP_DURATION=10s infra/loadtest/lt.sh bench t-i5-p10 RATE=1500 DURATION=10s
```

The semaphore's name includes its size (`parking:permits:reservation:<PERMITS>`). `reset` flushes Redis while no
instance runs, and `bench` aborts before the measured run unless `lt.sh semaphore` finds exactly one semaphore, of the
configured size, with every permit free. It checks again after the run (no leaked permit).

## 1. Sustained load: `load-stress-test.js`

Run it with `bench`, which gives comparable runs:

1. `reset`;
2. a reads-only warm-up (`m3.js` `MIX=warmup` at half the rate for `WARMUP_DURATION`, default 60 s);
3. a pause (`WARMUP_PAUSE`, default 5 s);
4. the measured run;
5. PostgreSQL / Hikari diagnostics and the report.

The diagnostics, the database audit and the report are produced even when k6 thresholds fail.

```bash
# full test (1,500 TPS, 180 s)
infra/loadtest/lt.sh bench lst-1500-180

# preliminary runs: shorter run, short warm-up
WARMUP_DURATION=10s infra/loadtest/lt.sh bench lst-500  RATE=500  DURATION=10s
WARMUP_DURATION=10s infra/loadtest/lt.sh bench lst-1500 RATE=1500 DURATION=10s

# the test alone, on the current data (fails in setup if any space is already reserved)
infra/loadtest/lt.sh load NAME RATE=1500 DURATION=180s
infra/loadtest/lt.sh report NAME
```

| Variable | Default | Meaning |
|---|---|---|
| `RATE` | 1500 | iterations started per second (each iteration is exactly one request) |
| `DURATION` | 180s | arrival window (`180s`, `3m`, `2m30s`) |
| `PRE_VUS` / `MAX_VUS` | 2000 / 2000 | VUs pre-allocated / maximum. All are started before the run, so no VU starts up during measurement |
| `OPEN_SECONDS` | 10 | requests started earlier are tagged `phase:open` (the race for the 80 spaces); later ones `phase:steady` |
| `FUNDED` | 5000 | seeded users the test picks from at random |

**Pass criteria:**

- completed iterations ≥ 99% of `RATE × DURATION`;
- dropped iterations ≤ 0.1%;
- `http_req_failed` < 1%. That counts 503/429 and anything else unexpected; a 409 with a named business code does not;
- no unexpected answer (`failure == 0`);
- per endpoint (reserve, spaces, deposit): p95 < 1 s and p99 < 2 s.

**Outcome classes:**

| Class | What it covers |
|---|---|
| `success` | 2xx |
| `business` | 409 `SPACE_ALREADY_RESERVED`, `VEHICLE_ALREADY_RESERVED`, `INSUFFICIENT_BALANCE`: the expected answers once the spaces are taken |
| `capacity` | 503 / 429: load shedding, a technical failure |
| `failure` | anything else, including timeouts and network errors |

**Report** (`results/NAME.report.txt`):

- **iterations:** scheduled = started + dropped; completed; interrupted.
- **throughput:** requests completed *within* the window ÷ `DURATION`.
- **drain:** requests completed *after* the window. k6's own `http_reqs` rate covers the whole run including the drain, so it is shown for reference only.
- **latency:** p95/p99/max/median per endpoint and per phase.
- **outcomes:** business rejections vs technical failures, and the thresholds crossed.
- **resources:** docker stats CPU (100% = one core) and memory for the apps, PostgreSQL, Redis and k6, averaged over the arrival window, with their total, and the whole Docker VM's CPU from `/proc/stat` (`NAME.vmcpu.txt`). Also peak PostgreSQL transactions and connections, space-lock and reservation-permit waits, Hikari connection waits and timeouts, the semaphore before/after the run, Redis commands, and the database audit.

A run of `OPEN_SECONDS` or less is all opening phase: it has no steady-state figures. k6 schedules one extra iteration
at the very end of the window (for example 15,001 for 1,500 × 10 s), which lands in `phase:steady`.

### Compared with the teammate's `tests/load-stress-test.js`

Same workload and executor. These measurement issues are fixed:

- Users are seeded, and tokens signed once at start-up. There is no API registration in setup, so nothing outside the scenario is counted.
- VUs are pre-allocated and dropped iterations fail the run.
- The achieved rate is checked, and requests completed within the window are reported apart from those completed in the drain.
- 409, 429 and 408 are no longer all treated as fine: only named business codes are expected, and 503/429 are failures.
- `http_req_failed` is consistent with that classification. In their script, k6 counts every 409 as failed.
- No retries (theirs retry deposits without an Idempotency-Key) and no think time.
- Latency targets are per endpoint, not one global figure.
- The setup refuses a closed window or a lot that isn't empty.

## 2. Concurrency and correctness: `m3.js`

Each scenario needs fresh data:

```bash
infra/loadtest/lt.sh reset && infra/loadtest/lt.sh run reg-one-space  SCENARIO=one-space   # exactly 1 winner
infra/loadtest/lt.sh reset && infra/loadtest/lt.sh run reg-all-spaces SCENARIO=all-spaces  # exactly 80 winners
infra/loadtest/lt.sh reset && infra/loadtest/lt.sh run reg-fifo       SCENARIO=fifo        # first funded user wins each round
infra/loadtest/lt.sh run NAME SCENARIO=mix MIX=read|reservation|warmup RATE=500 DURATION=10s
```

Check `spaces_won` / `fifo_first_funded_won` in `results/NAME.txt`, and the audit line at its end.

## 3. End-to-end 8 PM race: `reserve-8pm.js`

Through the public API against one app with an empty database. See the file header. From the repo root, in PowerShell:

```powershell
docker run --rm -v "${PWD}\infra\loadtest:/scripts" -e BASE_URL=http://host.docker.internal:8080 `
  -e DISPOSABLE_TARGET=yes grafana/k6:1.3.0 run /scripts/reserve-8pm.js
```

Then run `verify.sql` against that database: every count must be 0.

## Diagnostics and results

Every `run` and `load` writes:

| File | Contents |
|---|---|
| `results/NAME.txt` | k6 summary, k6 exit code, PostgreSQL commits/rollbacks, Redis command count, audit |
| `results/NAME.json` | k6 summary export |
| `results/NAME.env.txt` | git revision, image and quotas, variables |
| `results/NAME.stats.csv` | docker stats about every 2 s |
| `results/NAME.vmcpu.txt` | the Docker VM's `/proc/stat` CPU counters every second |

`bench` also writes `NAME.diag.txt` (`samples` + `metrics`) and `NAME.report.txt`.

```bash
infra/loadtest/lt.sh verify    # audit; prints "database audit: CONSISTENT" or "INCONSISTENT"
infra/loadtest/lt.sh samples   # peak PostgreSQL write/read transactions and connections per run (50 ms sampling)
infra/loadtest/lt.sh metrics   # reservation paths, lock/permit waits, Hikari, summed over the instances
infra/loadtest/lt.sh semaphore # exactly one reservation semaphore, of the configured size, all permits free
infra/loadtest/lt.sh config    # instances, CPUs, memory, permits and pool size actually running
```

k6 runs on the same host as the stack. Check the `lt-k6` line of the report: if its CPU approaches its 4-CPU quota, the
load generator, not the application, is limiting the run.

## 4. GCP: Cloud Run (`lt-gcp.ps1`)

k6 runs **on your machine** against the deployed service, driven from **PowerShell**. Same workload, outcome classes
and pass criteria as section 1 (both use [lib/stress-workload.js](lib/stress-workload.js)), and, as locally, every
measured run starts with all 80 spaces free, so local and GCP results compare directly. Nothing here uses Docker or
`lt.sh`, and nothing needs a new GCP resource, setting or permission.

| | Local (`lt.sh`) | GCP (`lt-gcp.ps1`) |
|---|---|---|
| Users | `seed.sql`, inserted directly | [seed-gcp.js](seed-gcp.js): registered through the **public API** (BCrypt + Cloud KMS, one vehicle, $100 deposit), like any user, once per session |
| Authentication | JWTs signed by k6 with the stack's test secret | tokens **issued by the service** at registration, kept in `infra/loadtest/.gcp/` (git-ignored, deleted by `cleanup`). The JWT signing key never leaves Secret Manager |
| Before each run | `reset`: truncate everything, flush Redis, restart the apps | `reset`: remove (and refund) only the load-test reservations, clear their Redis cache, replace the instances. Demo data stays |
| SQL and Redis | `psql` / `redis-cli` in the containers | `psql` on your machine and one Redis command, through an IAP SSH tunnel to the existing **bastion**, with the existing `db-password` / `redis-password` secrets (memory only) |
| Resources | `docker stats`, `pg_stat_activity` samples, actuator metrics | **Cloud Monitoring** (Cloud Run, Cloud SQL, Memorystore), read-only REST queries |
| Results | `results/NAME.*` | `results/gcp/NAME.*` |

### Safety rules (enforced by the script)

- **Demo users are never touched.** Load-test users are `gcplt-<yyyyMMdd-HHmmss>-<nnnnn>`; [gcp-reset.sql](gcp-reset.sql)
  and [gcp-cleanup.sql](gcp-cleanup.sql) select rows only through that strict pattern, each in one atomic statement.
  `seed` saves a fingerprint of all other data (users, vehicles, reservations, ledger, balances); `reset`, `run` and
  `cleanup` compare against it (`demo data check: UNCHANGED`).
- **No race with reservations in flight.** `reset` and `cleanup` refuse while k6 runs on this machine and wait until
  60 s (the Cloud Run request timeout) after the last k6 run ended, so no load-test request is still being processed.
  For anybody else reserving at that moment: only load-test rows are deleted, and in Redis only the reservation cache of
  the affected dates is removed (not `FLUSHDB`), so their space locks and permits stay intact.
- **Every benchmark starts with 80/80 free.** `run` refuses otherwise, and so does the k6 setup. A reset cannot free
  spaces reserved by demo or teammate users: benchmark when tomorrow's lot is empty (it starts empty when the target
  date moves on at midnight, Asia/Ho_Chi_Minh) and ask teammates not to reserve during the session.

**Why a reset needs more than SQL (and why this is the minimum without backend changes).** The Redis reservation cache
only ever adds entries, and marks a sold-out date as final; every instance also keeps a sold-out date in memory, with no
API to clear it. After a benchmark fills the lot, deleting rows in PostgreSQL alone would leave the lot looking full.
So a reset deletes the date's cache hash (rebuilt from PostgreSQL on the next read) and starts a new revision with the
same image and settings (`LOADTEST_RESET=<timestamp>`): traffic moves when the new instances are ready, no downtime.
This is safe at `--min 8`: only an env var changes, so CPU, memory and the maximum of 10 instances stay within this
project's Cloud Run quotas (20 vCPUs, 10 instances with Direct VPC egress), and old plus new instances are at most
2 × 10, the overlap `deploy.ps1` already fits into Cloud SQL (2 × 10 × 10 + 10 = 210 of 300 connections). When a
reset finds no load-test reservation, it skips both steps. `cleanup` also sets the minimum back to 1 (first, so its
revision replacement starts 1 instance, not 8).

### Prerequisites (once)

1. The M3 infrastructure and deploy are done ([docs/m3-gcp-setup.md](../../docs/m3-gcp-setup.md)).
2. **k6**: `winget install k6 --source winget`, then open a new terminal (`k6 version`).
3. **psql**, client tools only (no PostgreSQL server is installed; `lt-gcp.ps1` finds any version in
   `C:\Program Files\PostgreSQL\<version>\bin`, e.g. 17 or 19):

   ```powershell
   winget install --id PostgreSQL.PostgreSQL.17 --exact --override "--mode unattended --unattendedmodeui none --disable-components server,pgAdmin,stackbuilder"
   ```
4. **Bastion SSH once, interactively** (creates/registers your SSH key and accepts the host key; the script's tunnel
   then runs hidden): `gcloud compute ssh parking-bastion --zone=asia-southeast1-c --tunnel-through-iap --command="echo ok"`

### Runbook

```powershell
# 0. Read-only check: tools, Cloud Run (M3 deployed?), Cloud SQL and Redis through the bastion, users, lot.
.\infra\loadtest\lt-gcp.ps1 preflight

# 1. Warm instances for the whole session (service-level setting: no new revision, no build).
gcloud run services update parking-reservation --region=asia-southeast1 --min=8

# 2. Test users, once per session: 5,000 through the API (a few minutes); their tokens are valid for 12 hours.
.\infra\loadtest\lt-gcp.ps1 seed

# 3. Every benchmark = reset + run. Start small, then the full test.
.\infra\loadtest\lt-gcp.ps1 reset
.\infra\loadtest\lt-gcp.ps1 run gcp-500-10s -Rate 500 -Duration 10s -WarmupDuration 10s

.\infra\loadtest\lt-gcp.ps1 reset
.\infra\loadtest\lt-gcp.ps1 run gcp-1500-180s                  # 1,500 TPS for 180 s, after a 60 s warm-up

# 4. End of the session: minimum instances back to 1, every load-test row removed.
.\infra\loadtest\lt-gcp.ps1 cleanup
# (no cleanup this session? scale back by hand: gcloud run services update parking-reservation --region=asia-southeast1 --min=1)
```

| Command | What it does |
|---|---|
| `preflight` | Read-only. Prints the Cloud Run revision and settings (M3 deployed, pool, permits, min/max), Cloud SQL tier and flags, Redis, then through the bastion `gcp-check.sql` and a Redis `PING`; the users and token lifetime; whether the lot is ready (80/80 free, window open) |
| `seed [-Users 5000] [-SeedBatch 50]` | Refuses while load-test users exist. Saves the demo fingerprint, registers the users through the API (BCrypt + KMS per user: more instances seed faster), writes the tokens to `.gcp/users.json` |
| `reset` | Waits for quiet (above). [gcp-reset.sql](gcp-reset.sql): deletes the load-test reservations and their charges and refunds them, so balances still match the ledger; users, vehicles, deposits and tokens stay. Then the Redis cache of those dates and a new revision (skipped if nothing was removed). Ends with `gcp-check.sql`, the demo data check and the lot check |
| `run NAME [-Rate 1500] [-Duration 180s] [-PreVus 2000] [-MaxVus 2000] [-OpenSeconds 10] [-WarmupDuration 60s] [-WarmupRate n]` | Refuses unless 80/80 free. Warm-up (reads only, half the rate by default; `0s` skips it), the measured run, the audit (`verify.sql` + `gcp-check.sql`), a 4-minute wait for Cloud Monitoring (Ctrl+C is safe: `monitor NAME` and `report NAME` later), the resources and the report. A failed threshold does not stop the audit or the report |
| `cleanup [-Force]` | Waits for quiet, asks you to type `CLEANUP`, sets the minimum instances back to 1. [gcp-cleanup.sql](gcp-cleanup.sql) deletes every load-test user and their rows; then the same cache/revision step as `reset`; deletes the tokens; demo data check |
| `verify [NAME]`, `monitor NAME`, `report NAME` | Read-only: the audit (whole database, demo data included; every violation must be 0), the Cloud Monitoring summary, the report |

### If something goes wrong

- **The tunnel does not open:** run step 4 of the prerequisites again (interactive SSH); `preflight` shows the error.
- **`reset` or `cleanup` stopped after the SQL step:** run it again. The SQL finds nothing more to delete, and the
  Redis/revision step is finished from `.gcp/pending-cache-reset.txt`.
- **`run` refuses, spaces still reserved after `reset`:** demo or teammate reservations for tomorrow (shown by
  `gcp-check.sql`, "all reservations for tomorrow"). The load test never removes them; benchmark after midnight.
- **Tokens expire (12 h):** `cleanup`, then `seed` again.
- **`dropped_iterations` or k6 near 100% CPU:** your machine or connection is the limit, not the service. k6's latency
  includes the network to asia-southeast1; the service's own latency is in the monitoring section of the report.

### Results (`results/gcp/`)

| File | Contents |
|---|---|
| `NAME.env.txt` | target=gcp, git revision, k6 version, Cloud Run revision and settings, Cloud SQL tier and flags, Redis, users batch, variables, run window |
| `NAME.json` / `NAME.txt` | k6 summary export / k6 output (warm-up: `NAME-warmup.*`) |
| `NAME.audit.txt` | `verify.sql` + `gcp-check.sql` right after the run, with the demo data check |
| `NAME.monitoring.txt` | Cloud Monitoring, 1-minute points over the window: Cloud Run requests/s per status class, latency p95/p99, instances, CPU and memory p99, concurrency; Cloud SQL CPU, memory, connections, transactions/s, disk writes/s; Memorystore CPU, commands/s, clients, memory, rejected connections. Plus Console links |
| `NAME.report.txt` | the sections of the local report (iterations, throughput, drain, latency per endpoint and phase, outcomes, thresholds crossed), then the resources and the audit |
| `seed-<tag>.txt` | seeding output (no summary export: it would contain the tokens) |

### Results so far (2026-11-27)

Setup: Cloud Run 2 vCPU / 2 GiB, concurrency 200, min 8 / max 10, pool 10, permits 10, profiles `gcp,demo`; Cloud SQL
`db-custom-2-7680` (`max_connections` 300); Memorystore Basic 1 GB. 5,000 users seeded through the API in 104 s.
k6 v2.4.0 on a Windows laptop, **over a phone hotspot**.

| Run | Outcome | p95 reserve / spaces / deposit | Service side (Cloud Monitoring) | Audit |
|---|---|---|---|---|
| `gcp-500-10s` (500/s, 10 s) | **all thresholds passed**: 493.7 req/s, 0 dropped, 0 failures | 480 / 725 / 503 ms | too short for 1-minute points | consistent, 80 reservations, demo data unchanged |
| `gcp-1500-180s` (1,500/s, 180 s) | **thresholds crossed**: 1,456 req/s completed, 7,630 dropped (2.8%), 19 failures, all network errors on the client side; 0 server errors (5xx) and 0 capacity rejections (503/429) | 1,091 / 2,259 / 1,127 ms | Cloud Run CPU p99 32%, concurrency p99 44 of 200, 8 instances (never scaled to 9-10), latency p95 248 ms on average (994 ms in the worst minute); Cloud SQL CPU 18%; Redis CPU ~0 | consistent, 80 reservations, demo data unchanged |

**Reading `gcp-1500-180s`:** most of the latency was outside the service. Cloud Run's own p95 averaged 248 ms while k6
measured 1.42 s overall; by Little's law, about 170 requests were in flight at Cloud Run against about 590 at k6. The
slowest endpoint was `/api/spaces`, the largest response but cheap for the service (served from the cache), and the 19
failures were connect timeouts and dropped connections to Google's front end. The service had spare CPU and never added
instances. So this run is not yet a valid 1,500 TPS verdict: repeat it from a wired connection, and check that
`dropped_iterations` stays at most 5, that no network errors appear, and that k6's p95 comes close to Cloud Run's. The
worst minute (Cloud Run p95 994 ms) is the part to explain if latency stays high: it needs per-endpoint server metrics.
For comparison, the best local run (`t-i5-p10-h10`: 5 × 2 CPU / 1 GiB, pool 10, permits 10) completed 1,223 req/s with
reserve p95 6.8 s.

`reserve` successes can exceed 80 (120 in `gcp-1500-180s`): a winner that asks again for the space it already holds gets
its original reservation back (idempotent replay, [decision 14](../../docs/decisions.md)), not a new one.

### Cost of a session

At asia-southeast1 list prices, 8 to 10 busy 2-vCPU Cloud Run instances cost about $2.10 to $2.70 per hour (idle warm
instances much less); a 3-minute run at 1,500 TPS is about 270,000 requests. Seeding 5,000 users makes 5,000 Cloud KMS
encryptions (cents). Cloud SQL and Memorystore cost the same whether or not a test runs
([docs/m3-gcp-setup.md](../../docs/m3-gcp-setup.md#costs)). `cleanup` scales back to `--min=1`; run it right after the session.
