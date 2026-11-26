#!/usr/bin/env bash
# Disposable local load-test environment for m3.js and load-stress-test.js (Git Bash / Linux); see README.md. Separate
# from docker compose: its own Docker network "parking-lt" with PostgreSQL, Redis, N application instances (default
# 3 x 2 CPUs / 1 GiB, like small Cloud Run instances) and k6. Never points at the compose database or any shared
# environment.
#
# Stack configuration (environment variables, applied by up and reset; every instance gets the same values):
#   INSTANCES=3     application instances          APP_CPUS=2      CPU quota per instance
#   APP_MEMORY=1g   memory limit per instance      PERMITS=4       global reservation semaphore permits (one semaphore)
#   DB_POOL_SIZE=10 HikariCP maximum-pool-size per instance; INSTANCES x DB_POOL_SIZE must leave PG_HEADROOM=20 of
#                   PostgreSQL's max_connections for other clients (samplers, psql, audit), or reset refuses to start
# run, load, metrics and semaphore use the instances actually running.
#
#   infra/loadtest/lt.sh up                   build nothing; start the stack with backend/build/libs/*-SNAPSHOT.jar
#   infra/loadtest/lt.sh reset                runbook reset: stop apps, empty + seed the DB, FLUSHDB, start apps
#   infra/loadtest/lt.sh run NAME [VAR=...]   run m3.js (SCENARIO=..., MIX=..., RATE=..., DURATION=...)
#   infra/loadtest/lt.sh load NAME [VAR=...]  run load-stress-test.js (RATE=..., DURATION=..., PRE_VUS=..., MAX_VUS=...)
#                                             run and load: PostgreSQL concurrency sampling, docker stats, Redis command
#                                             count, then the database audit; results in infra/loadtest/results/NAME.*
#   infra/loadtest/lt.sh bench NAME [VAR=...] reset, warm-up (m3.js reads), pause, load NAME, diagnostics, report
#   infra/loadtest/lt.sh report NAME          iterations, throughput, latency, outcomes, resources of a load run
#   infra/loadtest/lt.sh samples              PostgreSQL concurrency per run
#   infra/loadtest/lt.sh metrics              application metrics summed over the instances
#   infra/loadtest/lt.sh semaphore            the reservation semaphore(s) in Redis: exactly one, all permits free
#   infra/loadtest/lt.sh config               the running instances' configuration (docker inspect)
#   infra/loadtest/lt.sh verify               money/booking audit (verify.sql) + negative balances
#   infra/loadtest/lt.sh down                 remove everything
set -euo pipefail
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WIN() { (cd "$1" && pwd -W 2>/dev/null || pwd); }
NET=parking-lt
INSTANCES=${INSTANCES:-3}
APP_CPUS=${APP_CPUS:-2}
APP_MEMORY=${APP_MEMORY:-1g}
PERMITS=${PERMITS:-4}
DB_POOL_SIZE=${DB_POOL_SIZE:-10}
PG_HEADROOM=${PG_HEADROOM:-20}
REDIS_PASSWORD=loadtest
JWT_SECRET=$(printf 'local-load-test-only-jwt-signing-key-0123456789' | base64 -w0)
FUNDED=${FUNDED:-5000}
BROKE=${BROKE:-300}
K6=lt-k6
K6_IMAGE=grafana/k6:1.3.0
K6_CPUS=${K6_CPUS:-4}
# APP_ENV="KEY=VALUE KEY=VALUE": setting overrides for the instances (applied on the next reset).
APP_OVERRIDES=()
for kv in ${APP_ENV:-}; do APP_OVERRIDES+=(-e "$kv"); done
psql() { docker exec -i lt-postgres psql -U parking -d parking -v ON_ERROR_STOP=1 -q "$@"; }
redis_cli() { docker exec lt-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning "$@"; }

# Instance i (1..N) is container lt-app-i, published on host port 18080 + i.
port_of() { echo $(( 18080 + $1 )); }
running_apps() { # indices of the running instances, ascending
  docker ps --format '{{.Names}}' --filter 'name=^lt-app-[0-9]+$' | sed 's/^lt-app-//' | sort -n
}
app_setting() { # INDEX VAR -> the instance's effective environment value (the last -e wins)
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "lt-app-$1" | sed -n "s/^$2=//p" | tail -1
}
# One line describing the running instances, from docker inspect (what really runs, not what was asked for).
stack_config() {
  local i sigs n
  n=$(running_apps | wc -l)
  sigs=$(for i in $(running_apps); do
    docker inspect --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}' "lt-app-$i" \
      | awk -v p="$(app_setting "$i" PARKING_CONCURRENCY_RESERVATIONPERMITS_PERMITS)" \
            -v q="$(app_setting "$i" DB_POOL_SIZE)" \
            '{ printf "app_cpus=%g app_memory=%dMiB permits=%s db_pool_size=%s\n", $1 / 1e9, $2 / 1048576, p, q }'
  done | sort -u)
  if [ "$(grep -c . <<<"$sigs")" -gt 1 ]; then
    echo "instances=$n MIXED CONFIGURATION: $(tr '\n' ';' <<<"$sigs")"
  else
    echo "instances=$n $sigs"
  fi
}

start_apps() {
  local jar_dir max_conn i
  jar_dir="$(WIN "$ROOT/backend/build/libs")"
  max_conn=$(psql -Atc "SHOW max_connections")
  if [ $(( INSTANCES * DB_POOL_SIZE + PG_HEADROOM )) -gt "$max_conn" ]; then
    echo "refused: $INSTANCES instances x DB_POOL_SIZE=$DB_POOL_SIZE = $(( INSTANCES * DB_POOL_SIZE )) connections" \
         "+ $PG_HEADROOM headroom exceeds PostgreSQL max_connections=$max_conn" >&2
    return 1
  fi
  [ "$DB_POOL_SIZE" -ge "$PERMITS" ] || echo "warning: DB_POOL_SIZE=$DB_POOL_SIZE < PERMITS=$PERMITS" >&2
  for i in $(seq 1 "$INSTANCES"); do
    docker run -d --name "lt-app-$i" --network "$NET" --cpus "$APP_CPUS" --memory "$APP_MEMORY" \
      -p "$(port_of "$i"):8080" \
      -v "$jar_dir:/app:ro" \
      -e SPRING_PROFILES_ACTIVE=local \
      -e SPRING_DATASOURCE_URL=jdbc:postgresql://lt-postgres:5432/parking \
      -e REDIS_ADDRESS=redis://lt-redis:6379 -e REDIS_PASSWORD="$REDIS_PASSWORD" \
      -e RESERVATION_OPEN_TIME=00:00 -e JWT_SECRET="$JWT_SECRET" \
      -e MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics \
      -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
      -e DB_POOL_SIZE="$DB_POOL_SIZE" -e PARKING_CONCURRENCY_RESERVATIONPERMITS_PERMITS="$PERMITS" \
      "${APP_OVERRIDES[@]}" \
      eclipse-temurin:21-jre java -jar /app/parking-reservation-0.0.1-SNAPSHOT.jar >/dev/null
    [ "$i" = 1 ] && wait_healthy "$(port_of 1)" # the first instance runs the Flyway migrations alone
  done
  for i in $(seq 1 "$INSTANCES"); do wait_healthy "$(port_of "$i")"; done
}

wait_healthy() {
  for _ in $(seq 1 90); do
    curl -fs "localhost:$1/actuator/health" | grep -q '"status":"UP"' && return 0
    sleep 1
  done
  echo "instance on port $1 not healthy" >&2; return 1
}

stop_apps() { # every instance, whatever the previous INSTANCES was
  local ids
  ids=$(docker ps -aq --filter 'name=^lt-app-[0-9]+$')
  [ -z "$ids" ] || docker rm -f $ids >/dev/null 2>&1 || true
}

to_seconds() { # k6 duration (180s, 3m, 2m30s) -> seconds
  if [[ -n "$1" && $1 =~ ^(([0-9]+)m)?(([0-9]+)s)?$ ]]; then
    echo $(( ${BASH_REMATCH[2]:-0} * 60 + ${BASH_REMATCH[4]:-0} ))
  else
    echo "unsupported DURATION: $1 (use 180s, 3m or 2m30s)" >&2; return 1
  fi
}

redis_commands() {
  redis_cli INFO stats | tr -d '\r' | sed -n 's/^total_commands_processed://p'
}

# semaphore_state: the reservation semaphores in Redis. Healthy = exactly one, named after the permits the instances
# run with (DbPermits.semaphoreName), and every permit free (no request in flight, no leaked permit).
semaphore_state() {
  local first permits names n expected available
  first=$(running_apps | head -1)
  permits=$( [ -n "$first" ] && app_setting "$first" PARKING_CONCURRENCY_RESERVATIONPERMITS_PERMITS || true)
  permits=${permits:-4}
  expected="parking:permits:reservation:$permits"
  names=$(redis_cli --scan --pattern '*parking:permits:*' | grep -oE 'parking:permits:reservation:[0-9]+' | sort -u || true)
  n=$(grep -c . <<<"$names" || true)
  available=$(redis_cli GET "$expected" | tr -d '\r')
  if [ "$n" = 1 ] && [ "$names" = "$expected" ] && [ "$available" = "$permits" ]; then
    echo "reservation semaphore: OK (1 semaphore $expected, $available/$permits permits free)"
  else
    echo "reservation semaphore: NOT OK ($n semaphore(s): $(tr '\n' ' ' <<<"$names")expected $expected with" \
         "$permits free, found ${available:-none})"
    return 1
  fi
}

# run_k6 SCRIPT NAME [VAR=...]: one k6 run with its diagnostics. A failed threshold (k6 exit code 99) does not stop the
# script: resource samples, database counters and the audit are collected either way.
run_k6() {
  local script="$1" name="$2"; shift 2
  local out="$HERE/results/$name" seconds=${SAMPLE_SECONDS:-45} kv rc db_before redis_before base_urls # stats_pid, vm_pid: global (EXIT trap)
  mkdir -p "$HERE/results"
  base_urls=$(running_apps | sed 's|.*|http://lt-app-&:8080|' | paste -sd, -)
  [ -n "$base_urls" ] || { echo "no instance running: lt.sh up / reset first" >&2; return 1; }
  local envs=(-e BASE_URLS="$base_urls" -e JWT_SECRET="$JWT_SECRET" -e FUNDED="$FUNDED")
  for kv in "$@"; do
    envs+=(-e "$kv")
    case "$kv" in DURATION=*) seconds=$(( $(to_seconds "${kv#DURATION=}") + 50 )) ;; esac # + VU start-up + drain
  done
  {
    echo "script=$script"
    echo "started=$(date -u +%FT%TZ)"
    echo "git=$(git -C "$ROOT" rev-parse --short HEAD)$(git -C "$ROOT" diff --quiet || echo ' + uncommitted changes')"
    echo "k6=$K6_IMAGE ($K6_CPUS CPUs); postgres=2 CPUs, 2 GiB, max_connections=$(psql -Atc 'SHOW max_connections');" \
         "redis=1 CPU, 512 MiB; host CPUs=$(nproc); docker VM CPUs=$(docker info --format '{{.NCPU}}')"
    echo "stack=$(stack_config)"
    echo "base_urls=$base_urls"
    echo "before the run, $(semaphore_state || true)"
    echo "app_env=${APP_ENV:-}"
    for kv in "$@"; do echo "var $kv"; done
  } > "$out.env.txt"

  # PostgreSQL concurrency every 50 ms: write transactions (hold a transaction id), read transactions in flight, and
  # open connections (other samplers excluded). The table is created first, on its own: the sampler's DO block is one
  # transaction, so a CREATE inside it would stay uncommitted (and collide with a second sampler) until the block ends.
  psql -c "CREATE TABLE IF NOT EXISTS lt_samples (run text, at timestamptz, writing int, reading int, connected int);"
  docker exec -d lt-postgres psql -U parking -d parking -c "
    DO \$\$ BEGIN FOR i IN 1..$(( seconds * 20 )) LOOP
      PERFORM pg_stat_clear_snapshot();
      INSERT INTO lt_samples SELECT '$name', clock_timestamp(),
        count(*) FILTER (WHERE backend_xid IS NOT NULL),
        count(*) FILTER (WHERE backend_xid IS NULL AND xact_start IS NOT NULL),
        count(*)
      FROM pg_stat_activity
      WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend'
        AND query NOT LIKE '%lt_samples%';
      PERFORM pg_sleep(0.05);
    END LOOP; END \$\$;"
  # CPU (100% = one core) and memory of every lt-* container, k6 included: one line per container about every 2 s.
  ( while :; do
      ts=$(date +%s)
      docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' 2>/dev/null | grep '^lt-' \
        | sed "s/^/$ts,/" || true
    done ) > "$out.stats.csv" &
  stats_pid=$!
  # Whole Docker VM CPU (all containers + Docker itself) every second: /proc/stat is not namespaced, so any container
  # reads the VM's counters. "epoch cpu user nice system idle iowait irq softirq steal ...", one bounded exec.
  docker exec lt-redis sh -c "for i in \$(seq 1 $seconds); do echo \"\$(date +%s) \$(head -1 /proc/stat)\"; sleep 1; done" \
    > "$out.vmcpu.txt" 2>/dev/null &
  vm_pid=$!
  trap 'kill "$stats_pid" "$vm_pid" 2>/dev/null || true' EXIT

  db_before=$(psql -Atc "SELECT 'db before: commits=' || xact_commit || ' rollbacks=' || xact_rollback FROM pg_stat_database WHERE datname='parking'")
  redis_before=$(redis_commands)
  docker rm -f "$K6" >/dev/null 2>&1 || true
  set +e
  docker run --rm --name "$K6" --network "$NET" --cpus "$K6_CPUS" -v "$(WIN "$HERE"):/scripts" "${envs[@]}" \
    "$K6_IMAGE" run --quiet --summary-export="/scripts/results/$name.json" "/scripts/$script" 2>&1 | tee "$out.txt"
  rc=${PIPESTATUS[0]}
  set -e
  kill "$stats_pid" "$vm_pid" 2>/dev/null || true
  {
    echo "k6 exit code: $rc$([ "$rc" = 99 ] && echo ' (thresholds crossed)')"
    echo "after the run, $(semaphore_state || true)"
    echo "$db_before"
    psql -Atc "SELECT 'db after:  commits=' || xact_commit || ' rollbacks=' || xact_rollback FROM pg_stat_database WHERE datname='parking'"
    echo "redis commands during the run: $(( $(redis_commands) - redis_before ))"
    "$0" verify
  } 2>&1 | tee -a "$out.txt" || true
}

# report NAME: a load-stress-test.js run, from its summary export, k6 output, env file, docker stats and diagnostics.
report() {
  local name="$1" out="$HERE/results/$1"
  [ -f "$out.json" ] || { echo "no results/$name.json" >&2; return 1; }
  # k6 writes the summary export's keys in random order, and its indentation varies with that order: parse by nesting
  # depth (an object opens at the end of a line and closes at the start of one), never by indentation.
  get() { # METRIC [FIELD] -> value from the k6 summary export, 0 if absent
    awk -v key="\"$1\": {" -v field="\"${2:-count}\":" '
      { line = $0; sub(/^[ \t]+/, "", line) }
      !inside && line == key { inside = 1; depth = 1; next }
      !inside { next }
      line ~ /^}/ { if (--depth == 0) exit; next }
      depth == 1 && index(line, field) == 1 { v = line; sub(/^[^:]*: */, "", v); sub(/,$/, "", v); print v; found = 1; exit }
      line ~ /\{$/ { depth++ }
      END { if (!found) print 0 }' "$out.json"
  }
  var() { sed -n "s/^var $1=//p" "$out.env.txt" | tail -1; }
  local rate duration started dropped completed measure drain start
  rate=$(var RATE); rate=${rate:-1500}
  duration=$(var DURATION); duration=$(to_seconds "${duration:-180s}")
  started=$(get started_iterations); dropped=$(get dropped_iterations)
  completed=$(get 'iterations{scenario:load}')
  measure=$(get 'completed_requests{window:measure}'); drain=$(get 'completed_requests{window:drain}')

  echo "== $name: $(sed -n 's/^script=//p' "$out.env.txt"), RATE=$rate/s, DURATION=${duration}s"
  grep -h '^var ' "$out.env.txt" | tr '\n' ' '; echo
  grep -h '^stack=' "$out.env.txt" | sed 's/^stack=/stack: /' || true
  echo "iterations: scheduled=$(( started + dropped )) (RATE x DURATION = $(( rate * duration )))" \
       "started=$started completed=$completed dropped=$dropped interrupted=$(( started - completed ))"
  awk -v m="$measure" -v d="$drain" -v s="$duration" -v st="$started" -v h="$(get http_reqs rate)" 'BEGIN {
    printf "throughput: %.1f requests/s completed within the %d s window (%d requests); %.1f iterations/s started\n", m / s, s, m, st / s
    printf "drain: %d requests completed after the window; k6 http_reqs over the whole run incl. drain: %.1f/s\n", d, h }'
  echo "latency (ms)                          p95      p99      max      med"
  local endpoint tag
  for endpoint in reserve spaces deposit; do
    for tag in "name:$endpoint" "name:$endpoint,phase:open" "name:$endpoint,phase:steady"; do
      if [ "$(get "http_req_duration{$tag}" max)" != 0 ]; then
        printf "  %-32s %8.0f %8.0f %8.0f %8.0f\n" "$tag" "$(get "http_req_duration{$tag}" 'p(95)')" \
          "$(get "http_req_duration{$tag}" 'p(99)')" "$(get "http_req_duration{$tag}" max)" \
          "$(get "http_req_duration{$tag}" med)"
      else
        printf "  %-32s no requests\n" "$tag"
      fi
    done
  done
  echo "business rejections: $(get business) (SPACE_ALREADY_RESERVED=$(get 'business{code:SPACE_ALREADY_RESERVED}')," \
       "VEHICLE_ALREADY_RESERVED=$(get 'business{code:VEHICLE_ALREADY_RESERVED}')," \
       "INSUFFICIENT_BALANCE=$(get 'business{code:INSUFFICIENT_BALANCE}'))"
  echo "technical failures: capacity=$(get capacity) (503=$(get 'capacity{status:503}') 429=$(get 'capacity{status:429}');" \
       "reserve=$(get 'capacity{name:reserve}') deposit=$(get 'capacity{name:deposit}')), unexpected=$(get failure);" \
       "http_req_failed{scenario:load}=$(get 'http_req_failed{scenario:load}' value)"
  echo "successes: $(get success) (reserve=$(get 'success{name:reserve}') spaces=$(get 'success{name:spaces}')" \
       "deposit=$(get 'success{name:deposit}'))"
  echo "thresholds crossed:"
  awk '{ line = $0; sub(/^[ \t]+/, "", line) }
       line ~ /": \{$/ && line != "\"thresholds\": {" { m = line; sub(/^"/, "", m); sub(/": \{$/, "", m) }
       line ~ /": true,?$/ { t = line; sub(/^"/, "", t); sub(/": true,?$/, "", t); print "  " m "  " t; n++ }
       END { if (!n) print "  none" }' "$out.json"
  start=$(grep -o 'window-start=[0-9]*' "$out.txt" | head -1 | cut -d= -f2 || true)
  echo "resources (docker stats; CPU 100% = one core; window = the ${duration} s of arrivals):"
  awk -F, -v from="$(( ${start:-0} / 1000 ))" -v to="$(( ${start:-0} / 1000 + duration ))" '
    function mib(v, x) { x = v + 0; if (v ~ /GiB/) return x * 1024; if (v ~ /KiB/) return x / 1024; if (v ~ /MiB/) return x; return x / 1048576 }
    { cpu = $3; sub(/%/, "", cpu); cpu += 0; split($4, mem, " / "); m = mib(mem[1]); limit[$2] = mem[2]
      if (m > memMax[$2]) memMax[$2] = m
      if (cpu > runMax[$2]) runMax[$2] = cpu
      if ($1 >= from && $1 <= to) { n[$2]++; sum[$2] += cpu; if (cpu > winMax[$2]) winMax[$2] = cpu } }
    END { for (c in limit) {
            avg = n[c] ? sum[c] / n[c] : 0; total += avg; if (c ~ /^lt-app-/) { apps += avg; napps++ }
            printf "  %-12s CPU in window avg %6.1f%% max %6.1f%% (whole run max %6.1f%%); memory max %7.1f MiB of %s\n",
              c, avg, winMax[c], runMax[c], memMax[c], limit[c] }
          printf "  ~total      CPU in window avg %6.1f%% (all lt-* containers); %d apps avg %6.1f%% (%.1f%% per app)\n",
            total, napps, apps, (napps ? apps / napps : 0) }' "$out.stats.csv" | sort
  if [ -s "$out.vmcpu.txt" ]; then
    # Docker VM CPU between the first and the last /proc/stat sample inside the window.
    awk -v from="$(( ${start:-0} / 1000 ))" -v to="$(( ${start:-0} / 1000 + duration ))" \
        -v cpus="$(sed -n 's/.*docker VM CPUs=\([0-9]*\).*/\1/p' "$out.env.txt")" '
      $2 == "cpu" && $1 >= from && $1 <= to {
        busy = $3 + $4 + $5 + $8 + $9 + $10; all = busy + $6 + $7
        if (!seen) { b0 = busy; a0 = all; t0 = $1; seen = 1 } b1 = busy; a1 = all; t1 = $1 }
      END { if (seen && a1 > a0) printf "  docker VM  CPU in window %5.1f%% busy = %.1f of %s CPUs (%d s sampled)\n",
              100 * (b1 - b0) / (a1 - a0), cpus * (b1 - b0) / (a1 - a0), cpus, t1 - t0
            else print "  docker VM  CPU: no samples in the window" }' "$out.vmcpu.txt"
  fi
  if [ -f "$out.diag.txt" ]; then
    grep -E "^$name: |hikaricp|db_permit|space_lock|lease" "$out.diag.txt" | sed 's/^/  /' || true
  fi
  grep -hoE 'after the run: [^"]*' "$out.txt" || true
  grep -h '^before the run, reservation semaphore' "$out.env.txt" || true
  grep -hE '^(k6 exit code|after the run, reservation semaphore|db before|db after|redis commands|negative balances|reservations per date|database audit)' \
    "$out.txt" || true
}

case "${1:-}" in
  up)
    docker network create "$NET" >/dev/null 2>&1 || true
    docker run -d --name lt-postgres --network "$NET" --cpus 2 --memory 2g \
      -e POSTGRES_DB=parking -e POSTGRES_USER=parking -e POSTGRES_PASSWORD=parking \
      postgres:17-alpine -c max_connections=100 >/dev/null
    docker run -d --name lt-redis --network "$NET" --cpus 1 --memory 512m \
      redis:7.2-alpine redis-server --requirepass "$REDIS_PASSWORD" --maxmemory-policy noeviction \
      --save "" --appendonly no >/dev/null
    until docker exec lt-postgres pg_isready -U parking -d parking >/dev/null 2>&1; do sleep 1; done
    start_apps
    "$0" reset
    ;;
  reset)
    stop_apps
    psql -c "DROP TABLE IF EXISTS lt_samples;" \
         -c "TRUNCATE wallet_transactions, reservations, vehicles, wallets, users RESTART IDENTITY CASCADE;" \
         -c "SELECT pg_stat_reset();"
    psql -v funded="$FUNDED" -v broke="$BROKE" -v balance=10000 < "$HERE/seed.sql"
    # Flushed while no instance runs: no semaphore of an earlier PERMITS (or anything else) survives into the test.
    redis_cli FLUSHDB >/dev/null
    start_apps
    echo "reset: $FUNDED funded + $BROKE broke users, Redis flushed, instances restarted: $(stack_config)"
    ;;
  run)
    shift; run_k6 m3.js "$@"
    ;;
  load)
    shift; run_k6 load-stress-test.js "$@"
    ;;
  bench)
    # Comparable runs: fresh data, the same warm-up, then the measured load. Diagnostics and the report are produced
    # even when the warm-up or the load run fails its thresholds.
    name="$2"; shift 2
    rate=1500
    for kv in "$@"; do case "$kv" in RATE=*) rate=${kv#RATE=} ;; esac; done
    "$0" reset
    "$0" run "$name-warmup" SCENARIO=mix MIX=warmup RATE="${WARMUP_RATE:-$(( (rate + 1) / 2 ))}" \
      DURATION="${WARMUP_DURATION:-60s}" > /dev/null || echo "warm-up failed: see results/$name-warmup.txt"
    sleep "${WARMUP_PAUSE:-5}"
    # The instances must share exactly one semaphore, of the configured size, with every permit free.
    "$0" semaphore || { echo "bench aborted before the measured run: reservation semaphore not clean" >&2; exit 1; }
    "$0" load "$name" "$@" || true
    { "$0" samples; "$0" metrics; "$0" semaphore; } > "$HERE/results/$name.diag.txt" 2>&1 || true
    report "$name" | tee "$HERE/results/$name.report.txt"
    ;;
  report)
    report "$2"
    ;;
  samples)
    # A sampler's rows become visible only when its DO block ends: wait for running samplers first.
    while [ "$(psql -Atc "SELECT count(*) FROM pg_stat_activity WHERE query LIKE '%INSERT INTO lt_samples%'
                           AND pid <> pg_backend_pid()")" != 0 ]; do sleep 1; done
    psql -Atc "SELECT run || ': max write tx in flight=' || max(writing) || ', max read tx in flight=' || max(reading)
               || ', max tx in flight=' || max(writing + reading) || ', max connections open=' || max(connected)
               || ', samples=' || count(*) FROM lt_samples GROUP BY run ORDER BY run"
    ;;
  metrics)
    get() { # name [tag...] -> COUNT TOTAL_TIME MAX (or VALUE) summed / maxed over the instances
      local q="" c=0 t=0 m=0 v=0 out
      for tag in "${@:2}"; do q+="${q:+&}tag=$tag"; done
      for i in $(running_apps); do
        out=$(curl -fs "localhost:$(port_of "$i")/actuator/metrics/$1${q:+?$q}" || true)
        [ -z "$out" ] && continue
        c=$(awk -v a="$c" -v b="$(grep -o '"COUNT","value":[0-9.E+-]*' <<<"$out" | cut -d: -f2)" 'BEGIN{print a+(b==""?0:b)}')
        t=$(awk -v a="$t" -v b="$(grep -o '"TOTAL_TIME","value":[0-9.E+-]*' <<<"$out" | cut -d: -f2)" 'BEGIN{print a+(b==""?0:b)}')
        m=$(awk -v a="$m" -v b="$(grep -o '"MAX","value":[0-9.E+-]*' <<<"$out" | cut -d: -f2)" 'BEGIN{print (b!="" && b>a)?b:a}')
        v=$(awk -v a="$v" -v b="$(grep -o '"VALUE","value":[0-9.E+-]*' <<<"$out" | cut -d: -f2)" 'BEGIN{print a+(b==""?0:b)}')
      done
      awk -v n="$*" -v c="$c" -v t="$t" -v m="$m" -v v="$v" 'BEGIN{
        if (t > 0) printf "%-62s count=%d avg=%.1fms max=%.1fms\n", n, c, (c>0? t*1000/c : 0), m*1000;
        else printf "%-62s count=%d%s\n", n, (c>0?c:v), "" }'
    }
    for path in cache database_read transaction; do get parking.reservation.resolution "path:$path"; done
    for outcome in acquired timeout; do get parking.space_lock.wait "outcome:$outcome"; done
    for outcome in acquired timeout; do get parking.db_permit.wait "outcome:$outcome"; done
    get parking.lease.expired; get parking.space_lock.ambiguous_retry
    get hikaricp.connections.max; get hikaricp.connections.timeout
    get hikaricp.connections.acquire; get hikaricp.connections.usage
    ;;
  semaphore)
    semaphore_state
    ;;
  config)
    stack_config
    ;;
  verify)
    result=$(psql -At < "$HERE/verify.sql")
    sed 's/^/  /; s/|/: /' <<<"$result"
    violations=$(awk -F'|' '{ s += $2 } END { print s + 0 }' <<<"$result")
    negative=$(psql -Atc "SELECT count(*) FROM wallets WHERE balance_cents < 0")
    echo "negative balances: $negative"
    psql -Atc "SELECT 'reservations per date: ' || coalesce(string_agg(reservation_date || '=' || n, ', '), 'none') FROM
               (SELECT reservation_date, count(*) n FROM reservations GROUP BY 1 ORDER BY 1) r"
    if [ "$violations" = 0 ] && [ "$negative" = 0 ]; then
      echo "database audit: CONSISTENT (0 violations, 0 negative balances)"
    else
      echo "database audit: INCONSISTENT ($violations violations, $negative negative balances)"
    fi
    ;;
  down)
    stop_apps
    docker rm -f lt-postgres lt-redis "$K6" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    ;;
  *) sed -n '2,27p' "$0"; exit 1 ;;
esac
