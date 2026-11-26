<#
  Load test of the DEPLOYED Cloud Run service, k6 running on this machine (Windows PowerShell 5.1 or later).
  Never uses Docker or the local lt.sh stack. Results: infra/loadtest/results/gcp/<NAME>.* (local runs: results/).
  The runbook (prerequisites, a full session, costs): infra/loadtest/README.md, section 4.

    .\infra\loadtest\lt-gcp.ps1 preflight              read-only: service, M3, scaling, Cloud SQL + Redis via the bastion, lot
    .\infra\loadtest\lt-gcp.ps1 seed [-Users 5000]     once per session: register the load-test users through the public API
    .\infra\loadtest\lt-gcp.ps1 reset                  before EVERY benchmark: remove the load-test reservations (refunded),
                                                       clear their Redis cache, replace the instances; checks 80/80 free
    .\infra\loadtest\lt-gcp.ps1 run NAME [-Rate 1500] [-Duration 180s] [-PreVus 2000] [-MaxVus 2000] [-WarmupDuration 60s]
                                                       refuses unless 80/80 free; warm-up, measured run, audit, monitoring, report
    .\infra\loadtest\lt-gcp.ps1 report NAME            iterations, throughput, latency, outcomes, resources, audit
    .\infra\loadtest\lt-gcp.ps1 monitor NAME           Cloud Run / Cloud SQL / Memorystore metrics of the run's window
    .\infra\loadtest\lt-gcp.ps1 verify [NAME]          money/booking audit, load-test rows, demo data unchanged (read-only)
    .\infra\loadtest\lt-gcp.ps1 cleanup                end of session: remove ALL load-test data (+ cache and instances),
                                                       minimum instances back to 1

  Authentication: the load-test users are registered through the public API; the tokens the service issues are kept
  in infra/loadtest/.gcp/ (git-ignored, deleted by cleanup). The JWT signing key never leaves Secret Manager.
  Database and Redis are private: SQL (psql) and the one Redis command go through an IAP SSH tunnel to the existing
  bastion, with the existing db-password and redis-password secrets read into memory only. Nothing is ever printed.
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('preflight', 'seed', 'reset', 'run', 'report', 'monitor', 'verify', 'cleanup')]
    [string]$Action,
    [Parameter(Position = 1)][string]$Name = '',
    [string]$BaseUrl = '',             # default: the URL of the Cloud Run service
    [int]$Rate = 1500,
    [string]$Duration = '180s',
    [int]$PreVus = 2000,
    [int]$MaxVus = 2000,
    [int]$OpenSeconds = 10,
    [string]$WarmupDuration = '60s',   # '0s' skips the warm-up
    [int]$WarmupRate = 0,              # default: half the rate (as lt.sh bench)
    [int]$Users = 5000,
    [int]$SeedBatch = 50,
    [switch]$Force,                    # cleanup without the confirmation prompt
    [string]$ProjectId = 'parking-project-demo-2026',
    [string]$Region = 'asia-southeast1'
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Here = $PSScriptRoot
$Root = Split-Path -Parent (Split-Path -Parent $Here)
$ResultsDir = Join-Path $Here 'results\gcp'
$PrivateDir = Join-Path $Here '.gcp'                        # git-ignored: bearer tokens of the load-test users
$UsersFile = Join-Path $PrivateDir 'users.json'
$FingerprintFile = Join-Path $PrivateDir 'fingerprint.json'  # the non-test data before seeding
$PendingCacheFile = Join-Path $PrivateDir 'pending-cache-reset.txt'
$LastK6File = Join-Path $PrivateDir 'last-k6-exit.txt'
$Service = 'parking-reservation'
$SqlInstance = 'parking-db'
$DbPasswordSecret = 'db-password'
$RedisInstance = 'parking-redis'
$RedisSecret = 'redis-password'
$Bastion = 'parking-bastion'
$BastionZone = 'asia-southeast1-c'
$SqlPort = 25432                # local ends of the tunnel (15432 is the docker compose PostgreSQL)
$RedisPort = 16379
$QuietSeconds = 60              # the Cloud Run request timeout: no request of a finished k6 run outlives it
$NormalMinInstances = 1         # restored by cleanup (a test session raises it to 8)
$MonitoringDelaySeconds = 240   # Cloud Monitoring needs a few minutes before the last minute of a run is readable
$script:Tunnel = $null
$script:DbPassword = $null

# ---------------------------------------------------------------------------------------------------------------
# Helpers: native commands, gcloud, files
# ---------------------------------------------------------------------------------------------------------------

function Get-NativeText {
    # Output of a native command (stderr ignored), as one string. 'Continue': Windows PowerShell 5.1 would turn
    # stderr lines (git's line-ending warnings, gcloud's notices) into terminating errors.
    $ErrorActionPreference = 'Continue'
    $rest = @($args | Select-Object -Skip 1)
    $out = & $args[0] @rest 2>$null
    return (@($out) -join "`n").Trim()
}

function Get-GitRevision {
    $ErrorActionPreference = 'Continue'
    $rev = Get-NativeText git -C $Root rev-parse --short HEAD
    & git -C $Root diff --quiet 2>$null
    if ($LASTEXITCODE -ne 0) { $rev += ' + uncommitted changes' }
    return $rev
}

function Get-GcloudJson {
    # Read-only gcloud call: the parsed JSON, or nothing if it fails (not found, API disabled). An expired gcloud login
    # is reported as such instead of looking like a missing resource.
    $ErrorActionPreference = 'Continue'
    $output = & gcloud @args --project $ProjectId --format=json --quiet 2>&1
    if ($LASTEXITCODE -ne 0) {
        if ((@($output | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] }) -join ' ') -match 'gcloud auth login|Reauthentication') {
            throw 'gcloud needs you to sign in again: gcloud auth login'
        }
        return $null
    }
    $json = @($output | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }) -join "`n"
    if (-not $json) { return $null }
    return ($json | ConvertFrom-Json)
}

function Read-SecretValue([string]$Secret) {
    # Latest version, in memory only (never printed or written).
    $ErrorActionPreference = 'Continue'
    $value = & gcloud secrets versions access latest --secret $Secret --project $ProjectId --quiet 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $value) { throw "Cannot read secret $Secret." }
    return ($value -join "`n")
}

function Get-ServiceInfo {
    $svc = Get-GcloudJson run services describe $Service --region $Region
    if (-not $svc) { throw "Cloud Run service $Service not found in $Region (project ${ProjectId})." }
    $c = $svc.spec.template.spec.containers[0]
    $envVars = @{}
    foreach ($e in @($c.env)) { if ($e -and $e.name) { $envVars[$e.name] = $e.value } }
    $ann = $svc.metadata.annotations
    $tAnn = $svc.spec.template.metadata.annotations
    return [pscustomobject]@{
        Url         = $svc.status.url
        Revision    = $svc.status.latestReadyRevisionName
        Cpu         = $c.resources.limits.cpu
        Memory      = $c.resources.limits.memory
        Concurrency = $svc.spec.template.spec.containerConcurrency
        Min         = $ann.'run.googleapis.com/minScale'
        Max         = $ann.'run.googleapis.com/maxScale'
        RevisionMin = $tAnn.'autoscaling.knative.dev/minScale'
        RevisionMax = $tAnn.'autoscaling.knative.dev/maxScale'
        CpuThrottle = $tAnn.'run.googleapis.com/cpu-throttling'
        Env         = $envVars
    }
}

function Resolve-BaseUrl {
    if ($BaseUrl) { return $BaseUrl.TrimEnd('/') }
    return (Get-ServiceInfo).Url
}

function Get-SqlSummary {
    $sql = Get-GcloudJson sql instances describe $SqlInstance
    if (-not $sql) { return 'not found' }
    $flags = (@($sql.settings.databaseFlags) | Where-Object { $_ } | ForEach-Object { "$($_.name)=$($_.value)" }) -join ','
    return "tier=$($sql.settings.tier) flags=$flags"
}

function Get-RedisSummary {
    $r = Get-GcloudJson redis instances describe $RedisInstance --region $Region
    if (-not $r) { return 'not found' }
    return "state=$($r.state) version=$($r.redisVersion) tier=$($r.tier) size=$($r.memorySizeGb)GB maxmemory-policy=$($r.redisConfigs.'maxmemory-policy')"
}

function Assert-K6 {
    if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
        throw 'k6 is not installed on this machine: winget install k6 --source winget (then open a new terminal).'
    }
}

function Find-Psql {
    $cmd = Get-Command psql -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $installed = Get-ChildItem 'C:\Program Files\PostgreSQL\*\bin\psql.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($installed) { return $installed.FullName }
    throw 'psql is not installed (client only, no server): winget install --id PostgreSQL.PostgreSQL.17 --exact --override "--mode unattended --unattendedmodeui none --disable-components server,pgAdmin,stackbuilder"'
}

function ConvertTo-Seconds([string]$Value) {
    $match = [regex]::Match("$Value", '^(?:(\d+)m)?(?:(\d+)s)?$')
    if (-not $Value -or -not $match.Success) { throw "Durations look like 180s, 3m or 2m30s (got '$Value')." }
    $minutes = 0; $secs = 0
    if ($match.Groups[1].Success) { $minutes = [int]$match.Groups[1].Value }
    if ($match.Groups[2].Success) { $secs = [int]$match.Groups[2].Value }
    return $minutes * 60 + $secs
}

function ConvertTo-K6Path([string]$Path) { return $Path -replace '\\', '/' }

function Read-UsersFile {
    # Metadata only (the tokens stay in the file).
    if (-not (Test-Path $UsersFile)) { return $null }
    $f = Get-Content -LiteralPath $UsersFile -Raw | ConvertFrom-Json
    return [pscustomobject]@{ Tag = $f.tag; BaseUrl = $f.baseUrl; ExpiresAt = $f.expiresAt; Count = @($f.users).Count; FirstToken = $f.users[0].t }
}

function Format-Rows($Rows) { return ($Rows | Format-Table -AutoSize | Out-String -Width 200).TrimEnd() }

# ---------------------------------------------------------------------------------------------------------------
# k6, the lot, and the "no request in flight" guard
# ---------------------------------------------------------------------------------------------------------------

function Invoke-K6([string]$Script, [string]$OutBase, [hashtable]$Vars, [switch]$NoSummaryExport) {
    # Runs k6 with live output, keeps the output (UTF-8) and the summary export; returns the exit code.
    # k6's summary export also contains what setup() returned: the seeding run must not write one (its setup data are
    # the tokens). The load test's setup data hold no secret ({ date, free, reservedBefore }).
    $k6Args = @('run', '--quiet')
    if (-not $NoSummaryExport) { $k6Args += "--summary-export=$(ConvertTo-K6Path "$OutBase.json")" }
    foreach ($kv in $Vars.GetEnumerator()) { $k6Args += @('-e', "$($kv.Key)=$($kv.Value)") }
    $k6Args += (ConvertTo-K6Path (Join-Path $Here $Script))
    $previous = [Console]::OutputEncoding
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    try {
        $ErrorActionPreference = 'Continue'
        & k6 @k6Args 2>&1 | ForEach-Object { $line = "$_"; Write-Host $line; $line } | Out-File -FilePath "$OutBase.txt" -Encoding utf8
        $code = $LASTEXITCODE
    } finally {
        [Console]::OutputEncoding = $previous
        New-Item -ItemType Directory -Force $PrivateDir | Out-Null
        (Get-Date).ToUniversalTime().ToString('o') | Set-Content -LiteralPath $LastK6File -Encoding ascii
    }
    Add-Content -LiteralPath "$OutBase.txt" -Value "k6 exit code: $code$(if ($code -eq 99) { ' (thresholds crossed)' })" -Encoding utf8
    return $code
}

function Wait-Quiet {
    # Reset and cleanup must not race with load-test reservations: no k6 running on this machine, and no request of the
    # last run still being processed by Cloud Run (60 s, its request timeout, after k6 ended).
    if (Get-Process -Name k6 -ErrorAction SilentlyContinue) { throw 'k6 is still running: let it finish (or stop it) first.' }
    if (Test-Path $LastK6File) {
        $last = [DateTimeOffset]::Parse((Get-Content -LiteralPath $LastK6File -Raw).Trim(), [Globalization.CultureInfo]::InvariantCulture).UtcDateTime
        $wait = [int][Math]::Ceiling(($last.AddSeconds($QuietSeconds) - (Get-Date).ToUniversalTime()).TotalSeconds)
        if ($wait -gt 0) {
            Write-Host "  waiting $wait s: requests of the last k6 run may still be finishing on Cloud Run"
            Start-Sleep -Seconds $wait
        }
    }
}

function Get-Lot {
    # The lot as the application reports it (GET /api/spaces with a load-test token).
    $seeded = Read-UsersFile
    if (-not $seeded) { return $null }
    $board = Invoke-RestMethod -Uri "$($seeded.BaseUrl)/api/spaces" -Headers @{ Authorization = "Bearer $($seeded.FirstToken)" } -TimeoutSec 30
    $reserved = @($board.spaces | Where-Object { $_.reserved }).Count
    return [pscustomobject]@{ Date = $board.date; Open = $board.open; OpensAt = $board.opensAt; Timezone = $board.timezone; Total = @($board.spaces).Count; Reserved = $reserved }
}

function Assert-EmptyLot {
    # Every opening race starts with all 80 spaces free, as the local test does.
    $lot = Get-Lot
    if (-not $lot) { throw 'No load-test users: run lt-gcp.ps1 seed first.' }
    $state = "$($lot.Date): $($lot.Total - $lot.Reserved)/$($lot.Total) spaces free, reservation window open: $($lot.Open)"
    if (-not $lot.Open) { throw "$state. The window opens at $($lot.OpensAt) $($lot.Timezone): deploy with -Profiles gcp,demo, or run after the opening time." }
    if ($lot.Total -ne 80 -or $lot.Reserved -gt 0) {
        throw ("$state. Run lt-gcp.ps1 reset. If spaces are still reserved after a reset, demo or teammate users hold them, " +
            "and the load test never removes their reservations: benchmark once the target date has moved on (midnight " +
            "$($lot.Timezone)) and tomorrow's lot is empty, and ask teammates not to reserve during the session.")
    }
    Write-Host "== Lot ready: $state" -ForegroundColor Green
}

# ---------------------------------------------------------------------------------------------------------------
# The bastion tunnel: psql to Cloud SQL, RESP to Memorystore
# ---------------------------------------------------------------------------------------------------------------

function Open-Tunnel([switch]$WithRedis) {
    # One IAP SSH tunnel through the existing bastion (it sits in the subnet Cloud Run uses), forwarding Cloud SQL and,
    # if asked, Memorystore to localhost. Nothing is installed on the bastion; no new GCP permission or setting.
    $sql = Get-GcloudJson sql instances describe $SqlInstance
    $sqlIp = (@($sql.ipAddresses) | Where-Object { $_.type -eq 'PRIVATE' } | Select-Object -First 1).ipAddress
    if (-not $sqlIp) { throw "No private IP found for $SqlInstance." }
    $forwards = @('-L', "${SqlPort}:${sqlIp}:5432")
    $target = "Cloud SQL $sqlIp`:5432 -> localhost:$SqlPort"
    if ($WithRedis) {
        $redis = Get-GcloudJson redis instances describe $RedisInstance --region $Region
        if (-not $redis) { throw "Memorystore $RedisInstance not found." }
        $forwards += @('-L', "${RedisPort}:$($redis.host):$($redis.port)")
        $target += ", Redis $($redis.host):$($redis.port) -> localhost:$RedisPort"
    }
    $gcloudCmd = (Get-Command gcloud.cmd -ErrorAction Stop).Source
    Write-Host "  IAP SSH tunnel through $Bastion`: $target"
    $script:Tunnel = Start-Process -FilePath $gcloudCmd -PassThru -WindowStyle Hidden -ArgumentList (@(
        'compute', 'ssh', $Bastion, "--zone=$BastionZone", '--tunnel-through-iap', "--project=$ProjectId", '--', '-N') + $forwards)
    $deadline = (Get-Date).AddSeconds(60)
    while ($true) {
        if ($script:Tunnel.HasExited) {
            throw "The SSH tunnel stopped. Run once interactively: gcloud compute ssh $Bastion --zone=$BastionZone --tunnel-through-iap --command=`"echo ok`""
        }
        try { (New-Object System.Net.Sockets.TcpClient('127.0.0.1', $SqlPort)).Dispose(); break } catch { Start-Sleep -Milliseconds 500 }
        if ((Get-Date) -gt $deadline) {
            Close-Tunnel
            throw "The SSH tunnel did not open within 60 s. Run once interactively: gcloud compute ssh $Bastion --zone=$BastionZone --tunnel-through-iap --command=`"echo ok`""
        }
    }
}

function Close-Tunnel {
    if ($script:Tunnel) {
        & taskkill /PID $script:Tunnel.Id /T /F *> $null
        $script:Tunnel = $null
    }
}

function Invoke-Psql([string]$File) {
    # One SQL file through the tunnel as parking_app (password from Secret Manager, passed to psql only); rows as objects.
    if (-not $script:DbPassword) { $script:DbPassword = Read-SecretValue $DbPasswordSecret }
    $psql = Find-Psql
    $connection = "host=127.0.0.1 port=$SqlPort dbname=parking user=parking_app sslmode=require connect_timeout=15"
    $env:PGPASSWORD = $script:DbPassword
    try {
        $ErrorActionPreference = 'Continue'
        # -A: unaligned output, '|' between fields (psql's default separator); header line, no row-count footer.
        $output = & $psql $connection -X -q -A -P footer=off -v ON_ERROR_STOP=1 -f (Join-Path $Here $File) 2>&1
        $code = $LASTEXITCODE
    } finally {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    }
    $lines = @($output | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" } | Where-Object { $_ })
    if ($code -ne 0) {
        $errors = @($output | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" }) -join "`n"
        throw "psql $File failed (exit code $code):`n$errors"
    }
    $rows = @()
    if ($lines.Count -gt 0) {
        $columns = $lines[0] -split '\|'
        foreach ($line in ($lines | Select-Object -Skip 1)) {
            $values = $line -split '\|'
            $o = [ordered]@{}
            for ($i = 0; $i -lt $columns.Count; $i++) { $o[$columns[$i]] = $values[$i] }
            $rows += [pscustomobject]$o
        }
    }
    return , $rows
}

function Send-RedisCommand($Stream, [string[]]$Parts) {
    # RESP request; every reply used here (+OK, +PONG, :n, -ERR) is a single line.
    $request = "*$($Parts.Count)`r`n"
    foreach ($p in $Parts) { $request += "`$$([System.Text.Encoding]::UTF8.GetByteCount($p))`r`n$p`r`n" }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($request)
    $Stream.Write($bytes, 0, $bytes.Length)
    $reply = New-Object System.Text.StringBuilder
    while ($true) {
        $b = $Stream.ReadByte()
        if ($b -lt 0) { throw 'Redis closed the connection.' }
        if ($b -eq 10) { break }
        if ($b -ne 13) { [void]$reply.Append([char]$b) }
    }
    return $reply.ToString()
}

function Invoke-Redis($Commands) {
    # Runs the given commands (each one a string array) through the open tunnel after AUTH (string from Secret Manager,
    # never printed).
    $client = New-Object System.Net.Sockets.TcpClient('127.0.0.1', $RedisPort)
    try {
        $client.ReceiveTimeout = 10000
        $stream = $client.GetStream()
        if ((Send-RedisCommand $stream @('AUTH', (Read-SecretValue $RedisSecret))) -ne '+OK') { throw 'Redis refused the AUTH string (is secret redis-password up to date?).' }
        foreach ($c in $Commands) {
            $reply = Send-RedisCommand $stream $c
            Write-Host "  redis: $($c -join ' ') -> $reply"
            if ($reply -like '-*') { throw "Redis error: $reply" }
        }
    } finally {
        $client.Dispose()
    }
}

function Update-Revision {
    # Running instances keep a sold-out date in memory (ReservationStateCache, no API to clear it): only new instances
    # forget it. A new revision with the same image and settings replaces them; traffic moves once the new instances
    # are ready (no downtime) and old instances finish their requests. Only an env var changes: CPU, memory and the
    # maximum of 10 instances stay as deployed, within the regional quotas (20 vCPUs, 10 instances with Direct VPC
    # egress). Safe at any minimum instances: old + new instances are at most 2 x 10, the overlap deploy.ps1 already
    # fits into Cloud SQL (2 x 10 x 10 + 10 = 210 <= 300).
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
    Write-Host "  new revision of $Service (same image and settings, LOADTEST_RESET=$stamp) to replace the instances"
    $ErrorActionPreference = 'Continue'
    & gcloud run services update $Service --region $Region --project $ProjectId --update-env-vars "LOADTEST_RESET=$stamp" --quiet
    if ($LASTEXITCODE -ne 0) { throw 'gcloud run services update failed: run the same lt-gcp.ps1 command again.' }
}

function Reset-ReservationCache([string]$DatesCsv) {
    # After load-test reservations were deleted in PostgreSQL: delete the Redis reservation cache of their dates (it only
    # ever adds entries; it is rebuilt from PostgreSQL on the next read) - not FLUSHDB, so the locks and the permit
    # semaphore of anybody reserving right now stay intact - then replace the instances. Requires an open tunnel to Redis.
    $dates = @(@("$DatesCsv" -split ',') + @(if (Test-Path $PendingCacheFile) { Get-Content -LiteralPath $PendingCacheFile }) |
        Where-Object { $_ } | Sort-Object -Unique)
    if ($dates.Count -eq 0) {
        Write-Host '  no load-test reservation was removed: Redis cache and instances are left as they are'
        return
    }
    $dates | Set-Content -LiteralPath $PendingCacheFile -Encoding ascii   # finished on a re-run if anything below fails
    $commands = @($dates | ForEach-Object { , @('DEL', "parking:reservations:$_") })
    Invoke-Redis $commands
    Update-Revision
    Remove-Item -LiteralPath $PendingCacheFile -Force
}

function Get-Fingerprint($CheckRows) {
    $f = [ordered]@{}
    foreach ($r in $CheckRows) { if ($r.item -like 'other *') { $f[$r.item] = $r.value } }
    return $f
}

function Compare-Fingerprint($CheckRows) {
    # The demo/teammate data must be exactly as before seeding.
    if (-not (Test-Path $FingerprintFile)) { return 'demo data check: no fingerprint saved (seed saves one)' }
    $before = Get-Content -LiteralPath $FingerprintFile -Raw | ConvertFrom-Json
    $now = Get-Fingerprint $CheckRows
    $diff = @()
    foreach ($k in $now.Keys) { if ("$($before.$k)" -ne "$($now[$k])") { $diff += "$k $($before.$k) -> $($now[$k])" } }
    if ($diff.Count -eq 0) { return 'demo data check: UNCHANGED since seeding (other users, vehicles, reservations, ledger, balances)' }
    return "demo data check: CHANGED since seeding: $($diff -join '; ') (expected only if somebody used the demo meanwhile)"
}

# ---------------------------------------------------------------------------------------------------------------
# Actions
# ---------------------------------------------------------------------------------------------------------------

function Invoke-Preflight {
    Write-Host "== gcloud: account $(Get-NativeText gcloud config get-value account), project $ProjectId"
    $k6 = Get-Command k6 -ErrorAction SilentlyContinue
    Write-Host "== k6: $(if ($k6) { Get-NativeText k6 version } else { 'NOT INSTALLED: winget install k6 --source winget' })"
    try { Write-Host "== psql: $(Find-Psql)" } catch { Write-Host "== psql: $($_.Exception.Message)" -ForegroundColor Yellow }

    $svc = Get-ServiceInfo
    Write-Host "== Cloud Run $Service"
    Write-Host "    $($svc.Url), revision $($svc.Revision), profiles $($svc.Env['SPRING_PROFILES_ACTIVE'])"
    Write-Host "    $($svc.Cpu) vCPU, $($svc.Memory), concurrency $($svc.Concurrency), instances min $($svc.Min) max $($svc.Max) (revision level: $($svc.RevisionMin)/$($svc.RevisionMax)), CPU throttling $($svc.CpuThrottle)"
    Write-Host "    M3 deployed (REDIS_ADDRESS set): $($svc.Env.ContainsKey('REDIS_ADDRESS')); DB_POOL_SIZE=$($svc.Env['DB_POOL_SIZE']), permits=$($svc.Env['PARKING_CONCURRENCY_RESERVATIONPERMITS_PERMITS'])"
    Write-Host "== Cloud SQL $SqlInstance`: $(Get-SqlSummary)"
    $redisSummary = Get-RedisSummary
    Write-Host "== Memorystore $RedisInstance`: $redisSummary"

    Write-Host "== Through the bastion (read-only)"
    $hasPsql = $true
    try { $null = Find-Psql } catch { $hasPsql = $false }
    if (-not $hasPsql) {
        Write-Host '    skipped: install psql first (prerequisites)' -ForegroundColor Yellow
    } else {
        try {
            Open-Tunnel -WithRedis:($redisSummary -ne 'not found')
            Write-Host (Format-Rows (Invoke-Psql 'gcp-check.sql'))
            if ($redisSummary -ne 'not found') { Invoke-Redis @(, @('PING')) }
        } catch {
            Write-Host "    $($_.Exception.Message)" -ForegroundColor Yellow
        } finally {
            Close-Tunnel
        }
    }

    Write-Host "== Load-test users ($UsersFile)"
    $seeded = Read-UsersFile
    if (-not $seeded) { Write-Host '    none: run lt-gcp.ps1 seed'; return }
    $left = ([DateTimeOffset]::Parse($seeded.ExpiresAt, [Globalization.CultureInfo]::InvariantCulture) - [DateTimeOffset]::UtcNow).TotalHours
    Write-Host ("    batch {0}: {1} users on {2}; tokens valid for {3:N1} more hours" -f $seeded.Tag, $seeded.Count, $seeded.BaseUrl, $left)
    try { Assert-EmptyLot } catch { Write-Host "== Lot NOT ready: $($_.Exception.Message)" -ForegroundColor Yellow }
}

function Invoke-Seed {
    Assert-K6
    $url = Resolve-BaseUrl
    if (Test-Path $UsersFile) { throw "$UsersFile exists: the session already has users (lt-gcp.ps1 cleanup ends a session)." }
    Open-Tunnel
    try { $check = Invoke-Psql 'gcp-check.sql' } finally { Close-Tunnel }
    $ltUsers = ($check | Where-Object { $_.item -eq 'load-test users' }).value
    if ([int]$ltUsers -gt 0) { throw "$ltUsers load-test users are still in the database: run lt-gcp.ps1 cleanup first." }
    New-Item -ItemType Directory -Force $PrivateDir, $ResultsDir | Out-Null
    (Get-Fingerprint $check) | ConvertTo-Json | Set-Content -LiteralPath $FingerprintFile -Encoding utf8
    $tag = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
    Write-Host "== Seeding $Users users (batch $tag, $SeedBatch in parallel) on $url"
    Write-Host '   registration runs BCrypt + Cloud KMS per user: more instances seed faster (--min 8)'
    $code = Invoke-K6 'seed-gcp.js' (Join-Path $ResultsDir "seed-$tag") @{
        BASE_URL = $url; USERS = $Users; SEED_BATCH = $SeedBatch; SEED_TAG = $tag; USERS_FILE = (ConvertTo-K6Path $UsersFile) } -NoSummaryExport
    # Not "$users": PowerShell names ignore case, so it would overwrite the -Users parameter it is compared with.
    $seeded = Read-UsersFile
    if ($code -ne 0 -or -not $seeded -or $seeded.Count -ne $Users) {
        throw "Seeding failed (k6 exit code $code). Partly created users are removed by: lt-gcp.ps1 cleanup"
    }
    Write-Host "== Seeded $($seeded.Count) users; tokens valid until $($seeded.ExpiresAt)."
    try { Assert-EmptyLot } catch { Write-Host "== Lot NOT ready: $($_.Exception.Message)" -ForegroundColor Yellow }
}

function Invoke-Reset {
    Write-Host '== Reset: remove the load-test reservations (and refund them), clear their Redis cache, replace the instances.'
    Write-Host '   Users, vehicles, deposits and tokens stay. Demo, teammate and smoke-test rows are never selected (gcp-reset.sql).'
    Wait-Quiet
    New-Item -ItemType Directory -Force $PrivateDir | Out-Null
    Open-Tunnel -WithRedis
    try {
        $result = Invoke-Psql 'gcp-reset.sql'
        Write-Host (Format-Rows $result)
        Reset-ReservationCache $result[0].reservation_dates
        $check = Invoke-Psql 'gcp-check.sql'
        Write-Host (Format-Rows $check)
        Write-Host (Compare-Fingerprint $check)
    } finally {
        Close-Tunnel
    }
    Assert-EmptyLot
}

function Invoke-Verify([string]$OutFile = '') {
    Write-Host '== Money and booking audit (verify.sql, read-only)'
    Open-Tunnel
    try {
        $audit = Invoke-Psql 'verify.sql'
        $check = Invoke-Psql 'gcp-check.sql'
    } finally {
        Close-Tunnel
    }
    $violations = 0
    foreach ($r in $audit) { $violations += [int]$r.violations }
    $lines = @(
        (Format-Rows $audit),
        "database audit: $(if ($violations -eq 0) { 'CONSISTENT (0 violations)' } else { "INCONSISTENT ($violations violations)" })",
        '',
        (Format-Rows $check),
        (Compare-Fingerprint $check))
    $lines | ForEach-Object { Write-Host $_ }
    if ($OutFile) { $lines | Out-File -FilePath $OutFile -Encoding utf8 }
}

function Invoke-Run {
    if (-not $Name) { throw 'run needs a NAME, e.g.: lt-gcp.ps1 run gcp-1500' }
    Assert-K6
    $url = Resolve-BaseUrl
    $seeded = Read-UsersFile
    if (-not $seeded) { throw 'No load-test users: run lt-gcp.ps1 seed first.' }
    New-Item -ItemType Directory -Force $ResultsDir | Out-Null
    $out = Join-Path $ResultsDir $Name
    if (Test-Path "$out.json") { throw "results/gcp/$Name.json exists: choose another NAME." }
    $warmSeconds = ConvertTo-Seconds $WarmupDuration
    $null = ConvertTo-Seconds $Duration
    $warmRate = $WarmupRate
    if ($warmRate -le 0) { $warmRate = [int][Math]::Ceiling($Rate / 2) }
    Assert-EmptyLot

    $svc = Get-ServiceInfo
    @(
        'target=gcp', 'script=load-stress-test-gcp.js', "name=$Name", "started=$((Get-Date).ToUniversalTime().ToString('o'))",
        "git=$(Get-GitRevision)", "k6=$(Get-NativeText k6 version)", "base_url=$url",
        "cloud_run=revision=$($svc.Revision) cpu=$($svc.Cpu) memory=$($svc.Memory) concurrency=$($svc.Concurrency) min=$($svc.Min) max=$($svc.Max) cpu_throttling=$($svc.CpuThrottle) pool=$($svc.Env['DB_POOL_SIZE']) permits=$($svc.Env['PARKING_CONCURRENCY_RESERVATIONPERMITS_PERMITS'])",
        "cloud_sql=$(Get-SqlSummary)", "redis=$(Get-RedisSummary)",
        "users=$($seeded.Count) batch=$($seeded.Tag) tokens_expire=$($seeded.ExpiresAt)",
        "var RATE=$Rate", "var DURATION=$Duration", "var PRE_VUS=$PreVus", "var MAX_VUS=$MaxVus", "var OPEN_SECONDS=$OpenSeconds",
        "warmup=$WarmupDuration at $warmRate/s"
    ) | Out-File -FilePath "$out.env.txt" -Encoding utf8

    $vars = @{ BASE_URL = $url; USERS_FILE = (ConvertTo-K6Path $UsersFile); PRE_VUS = $PreVus; MAX_VUS = $MaxVus; OPEN_SECONDS = $OpenSeconds }
    if ($warmSeconds -gt 0) {
        Write-Host "== Warm-up: reads only, $warmRate/s for $WarmupDuration"
        $warm = $vars.Clone(); $warm.MODE = 'warmup'; $warm.RATE = $warmRate; $warm.DURATION = $WarmupDuration
        $null = Invoke-K6 'load-stress-test-gcp.js' "$out-warmup" $warm
        Start-Sleep -Seconds 5
    }
    Write-Host "== Measured run: $Rate/s for $Duration"
    $load = $vars.Clone(); $load.MODE = 'load'; $load.RATE = $Rate; $load.DURATION = $Duration
    $started = (Get-Date).ToUniversalTime()
    $code = Invoke-K6 'load-stress-test-gcp.js' $out $load
    $ended = (Get-Date).ToUniversalTime()
    @("run_started=$($started.ToString('o'))", "run_ended=$($ended.ToString('o'))", "k6_exit_code=$code") |
        Out-File -FilePath "$out.env.txt" -Encoding utf8 -Append
    if (-not (Test-Path "$out.json")) { throw "k6 wrote no summary (exit code $code): see results/gcp/$Name.txt" }

    Invoke-Verify "$out.audit.txt"
    $wait = [int]($ended.AddSeconds($MonitoringDelaySeconds) - (Get-Date).ToUniversalTime()).TotalSeconds
    if ($wait -gt 0) {
        Write-Host "== Waiting $wait s for Cloud Monitoring to ingest the run (Ctrl+C is safe: run 'monitor $Name' and 'report $Name' later)"
        Start-Sleep -Seconds $wait
    }
    Invoke-Monitor
    Invoke-Report
    Write-Host "== Next benchmark: lt-gcp.ps1 reset, then lt-gcp.ps1 run <new NAME>. End of session: lt-gcp.ps1 cleanup"
}

function Get-EnvValue([string]$File, [string]$Key) {
    $line = Get-Content -LiteralPath $File | Where-Object { $_ -like "$Key=*" } | Select-Object -Last 1
    if ($line) { return $line.Substring($Key.Length + 1) }
    return $null
}

function Get-RunWindow([string]$Out) {
    if (-not (Test-Path "$Out.env.txt")) { throw "results/gcp/$Name.env.txt not found." }
    $start = Get-EnvValue "$Out.env.txt" 'run_started'
    $end = Get-EnvValue "$Out.env.txt" 'run_ended'
    $txt = if (Test-Path "$Out.txt") { Get-Content -LiteralPath "$Out.txt" -Raw } else { '' }
    if ($txt -match 'window-start=(\d+)') { $start = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$Matches[1]).UtcDateTime.ToString('o') }
    if (-not $start -or -not $end) { throw "results/gcp/$Name has no run window (run_started / run_ended)." }
    $culture = [Globalization.CultureInfo]::InvariantCulture
    return @([DateTimeOffset]::Parse($start, $culture).UtcDateTime, [DateTimeOffset]::Parse($end, $culture).UtcDateTime)
}

function Get-TimeSeries([string]$Filter, [string]$Aligner, [string]$Reducer, [string[]]$GroupBy, [datetime]$From, [datetime]$To, [string]$Token) {
    $fmt = 'yyyy-MM-ddTHH:mm:ssZ'
    $q = @("filter=$([uri]::EscapeDataString($Filter))", "interval.startTime=$($From.ToString($fmt))", "interval.endTime=$($To.ToString($fmt))",
        'aggregation.alignmentPeriod=60s', "aggregation.perSeriesAligner=$Aligner")
    if ($Reducer) { $q += "aggregation.crossSeriesReducer=$Reducer" }
    foreach ($g in $GroupBy) { $q += "aggregation.groupByFields=$([uri]::EscapeDataString($g))" }
    $r = Invoke-RestMethod -Headers @{ Authorization = "Bearer $Token" } -Uri ("https://monitoring.googleapis.com/v3/projects/$ProjectId/timeSeries?" + ($q -join '&'))
    return @($r.timeSeries)
}

function Invoke-Monitor {
    if (-not $Name) { throw 'monitor needs the NAME of a run.' }
    $out = Join-Path $ResultsDir $Name
    $window = Get-RunWindow $out
    $from = $window[0].AddSeconds(-60); $to = $window[1].AddSeconds(60)
    $token = Get-NativeText gcloud auth print-access-token
    $run = "resource.type=`"cloud_run_revision`" AND resource.labels.service_name=`"$Service`""
    $sql = "resource.type=`"cloudsql_database`" AND resource.labels.database_id=`"${ProjectId}:$SqlInstance`""
    $redis = "resource.type=`"redis_instance`" AND resource.labels.instance_id=`"projects/$ProjectId/locations/$Region/instances/$RedisInstance`""
    # label, resource filter, metric, aligner, cross-series reducer, group by, scale, unit, statistic (peak | total)
    $metrics = @(
        @('Cloud Run requests/s', $run, 'run.googleapis.com/request_count', 'ALIGN_RATE', 'REDUCE_SUM', @('metric.labels.response_code_class'), 1, '/s', 'peak'),
        @('Cloud Run latency p95', $run, 'run.googleapis.com/request_latencies', 'ALIGN_PERCENTILE_95', 'REDUCE_MAX', @(), 1, 'ms', 'peak'),
        @('Cloud Run latency p99', $run, 'run.googleapis.com/request_latencies', 'ALIGN_PERCENTILE_99', 'REDUCE_MAX', @(), 1, 'ms', 'peak'),
        @('Cloud Run instances', $run, 'run.googleapis.com/container/instance_count', 'ALIGN_MAX', 'REDUCE_SUM', @('metric.labels.state'), 1, '', 'peak'),
        @('Cloud Run CPU p99', $run, 'run.googleapis.com/container/cpu/utilizations', 'ALIGN_PERCENTILE_99', 'REDUCE_MAX', @(), 100, '%', 'peak'),
        @('Cloud Run memory p99', $run, 'run.googleapis.com/container/memory/utilizations', 'ALIGN_PERCENTILE_99', 'REDUCE_MAX', @(), 100, '%', 'peak'),
        @('Cloud Run concurrent req. p99', $run, 'run.googleapis.com/container/max_request_concurrencies', 'ALIGN_PERCENTILE_99', 'REDUCE_MAX', @(), 1, '/inst.', 'peak'),
        @('Cloud SQL CPU', $sql, 'cloudsql.googleapis.com/database/cpu/utilization', 'ALIGN_MAX', '', @(), 100, '%', 'peak'),
        @('Cloud SQL memory', $sql, 'cloudsql.googleapis.com/database/memory/utilization', 'ALIGN_MAX', '', @(), 100, '%', 'peak'),
        @('Cloud SQL connections', $sql, 'cloudsql.googleapis.com/database/postgresql/num_backends', 'ALIGN_MAX', 'REDUCE_SUM', @(), 1, '', 'peak'),
        @('Cloud SQL transactions/s', $sql, 'cloudsql.googleapis.com/database/postgresql/transaction_count', 'ALIGN_RATE', 'REDUCE_SUM', @(), 1, '/s', 'peak'),
        @('Cloud SQL disk write ops/s', $sql, 'cloudsql.googleapis.com/database/disk/write_ops_count', 'ALIGN_RATE', '', @(), 1, '/s', 'peak'),
        @('Redis CPU (cores)', $redis, 'redis.googleapis.com/stats/cpu_utilization', 'ALIGN_RATE', 'REDUCE_SUM', @(), 1, '', 'peak'),
        @('Redis main thread (cores)', $redis, 'redis.googleapis.com/stats/cpu_utilization_main_thread', 'ALIGN_RATE', 'REDUCE_SUM', @(), 1, '', 'peak'),
        @('Redis commands/s', $redis, 'redis.googleapis.com/commands/calls', 'ALIGN_RATE', 'REDUCE_SUM', @(), 1, '/s', 'peak'),
        @('Redis connected clients', $redis, 'redis.googleapis.com/clients/connected', 'ALIGN_MAX', 'REDUCE_SUM', @(), 1, '', 'peak'),
        @('Redis memory used', $redis, 'redis.googleapis.com/stats/memory/usage_ratio', 'ALIGN_MAX', 'REDUCE_MAX', @(), 100, '%', 'peak'),
        @('Redis rejected connections', $redis, 'redis.googleapis.com/stats/reject_connections_count', 'ALIGN_DELTA', 'REDUCE_SUM', @(), 1, '', 'total'))
    $lines = @("resources (Cloud Monitoring, 1-minute points, $($from.ToString('HH:mm'))-$($to.ToString('HH:mm')) UTC):",
        ('  {0,-34} {1,10} {2,10}  {3}' -f 'metric', 'peak', 'average', 'series'))
    foreach ($m in $metrics) {
        try {
            $series = Get-TimeSeries "$($m[1]) AND metric.type=`"$($m[2])`"" $m[3] $m[4] $m[5] $from $to $token
            if ($series.Count -eq 0) { $lines += ('  {0,-34} {1,10}' -f $m[0], 'no data'); continue }
            foreach ($s in $series) {
                $values = @($s.points | ForEach-Object { if ($null -ne $_.value.doubleValue) { [double]$_.value.doubleValue } else { [double]$_.value.int64Value } }) | ForEach-Object { $_ * $m[6] }
                $stats = $values | Measure-Object -Maximum -Average -Sum
                $group = (@($s.metric.labels.PSObject.Properties | ForEach-Object { $_.Value }) -join ',')
                $peak = if ($m[8] -eq 'total') { $stats.Sum } else { $stats.Maximum }
                $lines += ('  {0,-34} {1,10:N1} {2,10:N1}  {3}{4}' -f $m[0], $peak, $stats.Average, $m[7], $(if ($group) { " [$group]" } else { '' }))
            }
        } catch {
            $lines += ('  {0,-34} {1}' -f $m[0], "error: $($_.Exception.Message)")
        }
    }
    $lines += "  console: https://console.cloud.google.com/run/detail/$Region/$Service/metrics?project=$ProjectId"
    $lines += "           https://console.cloud.google.com/sql/instances/$SqlInstance/system-insights?project=$ProjectId"
    $lines += "           https://console.cloud.google.com/memorystore/redis/locations/$Region/instances/$RedisInstance/details/monitoring?project=$ProjectId"
    $lines | ForEach-Object { Write-Host $_ }
    $lines | Out-File -FilePath "$out.monitoring.txt" -Encoding utf8
}

function Invoke-Report {
    if (-not $Name) { throw 'report needs the NAME of a run.' }
    $out = Join-Path $ResultsDir $Name
    if (-not (Test-Path "$out.json")) { throw "results/gcp/$Name.json not found." }
    $j = Get-Content -LiteralPath "$out.json" -Raw | ConvertFrom-Json
    $m = $j.metrics
    function Get-Metric([string]$Metric, [string]$Field = 'count') {
        $v = $m.$Metric
        if ($null -eq $v -or $null -eq $v.$Field) { return 0 }
        return [double]$v.$Field
    }
    $rate = [int](Get-EnvValue "$out.env.txt" 'var RATE')
    $duration = ConvertTo-Seconds (Get-EnvValue "$out.env.txt" 'var DURATION')
    $started = Get-Metric 'started_iterations'; $dropped = Get-Metric 'dropped_iterations'
    $completed = Get-Metric 'iterations{scenario:load}'
    $measure = Get-Metric 'completed_requests{window:measure}'; $drain = Get-Metric 'completed_requests{window:drain}'
    $lines = @(
        "== $Name (GCP Cloud Run): load-stress-test-gcp.js, RATE=$rate/s, DURATION=${duration}s",
        "target: $(Get-EnvValue "$out.env.txt" 'base_url')",
        "cloud run: $(Get-EnvValue "$out.env.txt" 'cloud_run')",
        "cloud sql: $(Get-EnvValue "$out.env.txt" 'cloud_sql')",
        "redis: $(Get-EnvValue "$out.env.txt" 'redis')",
        "users: $(Get-EnvValue "$out.env.txt" 'users'); git $(Get-EnvValue "$out.env.txt" 'git'); $(Get-EnvValue "$out.env.txt" 'k6')",
        ("iterations: scheduled={0} (RATE x DURATION = {1}) started={2} completed={3} dropped={4} interrupted={5}" -f ($started + $dropped), ($rate * $duration), $started, $completed, $dropped, ($started - $completed)),
        ("throughput: {0:N1} requests/s completed within the {1} s window ({2} requests); {3:N1} iterations/s started" -f ($measure / $duration), $duration, $measure, ($started / $duration)),
        ("drain: {0} requests completed after the window; k6 http_reqs over the whole run incl. drain: {1:N1}/s" -f $drain, (Get-Metric 'http_reqs' 'rate')),
        'latency (ms)                          p95      p99      max      med')
    foreach ($endpoint in 'reserve', 'spaces', 'deposit') {
        foreach ($tag in "name:$endpoint", "name:$endpoint,phase:open", "name:$endpoint,phase:steady") {
            $key = "http_req_duration{$tag}"
            if ((Get-Metric $key 'max') -ne 0) {
                $lines += ('  {0,-32} {1,8:N0} {2,8:N0} {3,8:N0} {4,8:N0}' -f $tag, (Get-Metric $key 'p(95)'), (Get-Metric $key 'p(99)'), (Get-Metric $key 'max'), (Get-Metric $key 'med'))
            } else {
                $lines += ('  {0,-32} no requests' -f $tag)
            }
        }
    }
    $lines += ("business rejections: {0} (SPACE_ALREADY_RESERVED={1}, VEHICLE_ALREADY_RESERVED={2}, INSUFFICIENT_BALANCE={3})" -f (Get-Metric 'business'),
        (Get-Metric 'business{code:SPACE_ALREADY_RESERVED}'), (Get-Metric 'business{code:VEHICLE_ALREADY_RESERVED}'), (Get-Metric 'business{code:INSUFFICIENT_BALANCE}'))
    $lines += ("technical failures: capacity={0} (503={1} 429={2}; reserve={3} deposit={4}), unexpected={5}; http_req_failed{{scenario:load}}={6}" -f (Get-Metric 'capacity'),
        (Get-Metric 'capacity{status:503}'), (Get-Metric 'capacity{status:429}'), (Get-Metric 'capacity{name:reserve}'), (Get-Metric 'capacity{name:deposit}'),
        (Get-Metric 'failure'), (Get-Metric 'http_req_failed{scenario:load}' 'value'))
    $lines += ("successes: {0} (reserve={1} spaces={2} deposit={3})" -f (Get-Metric 'success'), (Get-Metric 'success{name:reserve}'), (Get-Metric 'success{name:spaces}'), (Get-Metric 'success{name:deposit}'))
    $lines += 'thresholds crossed:'
    $crossed = @()
    foreach ($p in $m.PSObject.Properties) {
        if ($p.Value.thresholds) {
            foreach ($t in $p.Value.thresholds.PSObject.Properties) { if ($t.Value -eq $true) { $crossed += "  $($p.Name)  $($t.Name)" } }
        }
    }
    if ($crossed.Count -eq 0) { $lines += '  none' } else { $lines += $crossed }
    $txt = if (Test-Path "$out.txt") { Get-Content -LiteralPath "$out.txt" } else { @() }
    $lines += @($txt | Where-Object { $_ -match 'seed batch .* spaces free|after the run: |^k6 exit code' } | ForEach-Object { ($_ -replace '^.*?(seed batch|after the run:)', '$1') -replace '"? source=console.*$', '' })
    if (Test-Path "$out.monitoring.txt") { $lines += Get-Content -LiteralPath "$out.monitoring.txt" }
    else { $lines += "resources: not collected yet: lt-gcp.ps1 monitor $Name" }
    if (Test-Path "$out.audit.txt") { $lines += Get-Content -LiteralPath "$out.audit.txt" | Where-Object { $_ -match 'database audit|demo data check' } }
    $lines | ForEach-Object { Write-Host $_ }
    $lines | Out-File -FilePath "$out.report.txt" -Encoding utf8
}

function Invoke-Cleanup {
    Write-Host '== Cleanup (end of session): minimum instances back to 1; removes ALL load-test users (gcplt-*) and their vehicles,'
    Write-Host '   wallets, ledger rows and reservations; clears the Redis cache of their dates and replaces the instances.'
    Write-Host '   Demo, teammate and smoke-test data are never selected (strict username pattern, gcp-cleanup.sql).'
    Wait-Quiet
    New-Item -ItemType Directory -Force $PrivateDir | Out-Null
    Open-Tunnel -WithRedis
    try {
        Write-Host (Format-Rows (Invoke-Psql 'gcp-check.sql'))
        if (-not $Force) {
            $answer = Read-Host '   Type CLEANUP to continue'
            if ($answer -cne 'CLEANUP') { Write-Host '   cancelled'; return }
        }
        # End of the session: back to the normal minimum first (service level: immediate, no new revision), so the
        # revision replacement below starts 1 instance instead of 8.
        Write-Host "  minimum instances back to $NormalMinInstances"
        $ErrorActionPreference = 'Continue'
        & gcloud run services update $Service --region $Region --project $ProjectId --min $NormalMinInstances --quiet
        if ($LASTEXITCODE -ne 0) { throw 'gcloud run services update --min failed: run lt-gcp.ps1 cleanup again.' }
        $ErrorActionPreference = 'Stop'
        $result = Invoke-Psql 'gcp-cleanup.sql'
        Write-Host (Format-Rows $result)
        Reset-ReservationCache $result[0].reservation_dates
        if (Test-Path $UsersFile) { Remove-Item -LiteralPath $UsersFile -Force }
        $after = Invoke-Psql 'gcp-check.sql'
        Write-Host (Format-Rows $after)
        Write-Host (Compare-Fingerprint $after)
    } finally {
        Close-Tunnel
    }
}

switch ($Action) {
    'preflight' { Invoke-Preflight }
    'seed' { Invoke-Seed }
    'reset' { Invoke-Reset }
    'run' { Invoke-Run }
    'report' { Invoke-Report }
    'monitor' { Invoke-Monitor }
    'verify' { $file = ''; if ($Name) { $file = Join-Path $ResultsDir "$Name.audit.txt" }; Invoke-Verify $file }
    'cleanup' { Invoke-Cleanup }
}
