<#
  Build + deploy parking-reservation to Cloud Run from Windows (PowerShell).
  Unit AND integration tests must pass first (integration tests need Docker running: they prove the
  concurrency, payment and idempotency rules against real PostgreSQL). The container is built by Cloud Build
  from the root Dockerfile. JVM options stay the Dockerfile's: Java picks the garbage collector from the instance
  size (G1 at 2 vCPU / 2 GiB).

  M3 needs the infrastructure from infra/setup-m3.ps1 first (Memorystore parking-redis, secret redis-password,
  Cloud SQL with max_connections=300): see docs/m3-gcp-setup.md. Before running the tests, this script checks that
  Redis exists and that the Cloud SQL connection budget fits.

  Usage (from the repo root):
    .\infra\deploy.ps1 -ProjectId parking-project-demo-2026 -Profiles gcp,demo          # teammate demo (window open all day)
    .\infra\deploy.ps1 -ProjectId parking-project-demo-2026                             # normal 20:00 rule
    .\infra\deploy.ps1 -ProjectId parking-project-demo-2026 -Profiles gcp,demo -DryRun  # checks + command only

  Instance counts: the maximum is set at both levels (service and revision, same value); the minimum only at service
  level, so it changes without a new revision or build, e.g. for a load test:
    gcloud run services update parking-reservation --region asia-southeast1 --min 8   # shortly before the test
    gcloud run services update parking-reservation --region asia-southeast1 --min 1   # afterwards
  Regional quotas (asia-southeast1, this project): 20 vCPUs in total (max instances x CPU) and 10 instances with
  Direct VPC egress. They are checked before the tests; raise them in the Console (IAM & Admin > Quotas) first.
#>
param(
    [Parameter(Mandatory = $true)][string]$ProjectId,
    [string]$Region = 'asia-southeast1',
    [string[]]$Profiles = @('gcp'),  # -Profiles gcp,demo (PowerShell passes it as a list)
    [string]$ExtraEnv = '',          # Additional env vars, ';'-separated: 'A=1;B=2'
    # Cloud Run: per-instance size and instance counts (min: service level; max: service and revision level).
    [string]$Cpu = '2',
    [string]$Memory = '2Gi',
    [int]$Concurrency = 200,
    [int]$MinInstances = 1,
    [int]$MaxInstances = 10,         # the regional quotas allow 10 x 2 vCPU (see above)
    [ValidateSet('request', 'instance')][string]$CpuAllocation = 'request',  # request: CPU only while serving requests
    [int]$TimeoutSeconds = 60,
    # Application settings that differ from the application.yaml defaults (as in the best local load test).
    [int]$DbPoolSize = 10,           # HikariCP maximum-pool-size per instance (yaml default 10, set explicitly)
    [int]$ReservationPermits = 10,   # global reservation semaphore (yaml default 4)
    # Redis: the Memorystore instance (resolved to redis://host:port) or an explicit address; AUTH from Secret Manager.
    [string]$RedisInstance = 'parking-redis',
    [string]$RedisAddress = '',
    [string]$RedisPasswordSecret = 'redis-password',
    [switch]$DryRun                  # run the checks and print the deploy command; no tests, no deploy
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$Profiles = ($Profiles -join ',')
$service = 'parking-reservation'
$sqlInstance = 'parking-db'
# Database connections kept free for everything but the application: superuser reserved slots (3), Cloud SQL's own
# cloudsqladmin sessions (2 observed) and a few admin sessions (Cloud SQL Studio, psql).
$adminConnections = 10
# Cloud Run quotas of this project in this region (raise them first, then these values).
$quotaCpus = 20
$quotaDirectVpcInstances = 10

function Get-GcloudJson {
    # Read-only gcloud call: the parsed JSON, or nothing if it fails. gcloud writes progress to stderr, which Windows
    # PowerShell 5.1 turns into error records: run with 'Continue' and judge by the exit code.
    # An expired gcloud login is reported as such instead of looking like a missing resource.
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

function Stop-Deploy([string]$Message) {
    # A missing prerequisite: an error, or only a warning in a dry run (so the command can still be previewed).
    if ($DryRun) { Write-Warning $Message } else { throw $Message }
}

Write-Host '==> Checks'
if ($MinInstances -gt $MaxInstances) { throw "-MinInstances $MinInstances is larger than -MaxInstances $MaxInstances." }
if ($MaxInstances * [double]$Cpu -gt $quotaCpus -or $MaxInstances -gt $quotaDirectVpcInstances) {
    throw "-MaxInstances $MaxInstances x -Cpu $Cpu exceeds the Cloud Run quotas in ${Region}: $quotaCpus vCPUs in total, $quotaDirectVpcInstances instances with Direct VPC egress."
}
if ($DbPoolSize -lt $ReservationPermits) {
    throw "-DbPoolSize $DbPoolSize is smaller than -ReservationPermits ${ReservationPermits}: the permit budget is global, so one instance may hold every permit (decision 32)."
}

# Redis. M3 answers every reservation with 503 (fail closed) when Redis is unreachable, so never deploy without it.
if (-not $RedisAddress) {
    $redis = Get-GcloudJson redis instances describe $RedisInstance --region $Region
    if (-not $redis) {
        Stop-Deploy "Memorystore instance '$RedisInstance' not found in $Region (or its API is disabled): run infra\setup-m3.ps1 (docs\m3-gcp-setup.md), or pass -RedisAddress."
        $RedisAddress = "redis://<$RedisInstance-host>:6379"
    } elseif ($redis.state -ne 'READY') {
        Stop-Deploy "Memorystore instance '$RedisInstance' is $($redis.state), not READY."
    } else {
        $RedisAddress = "redis://$($redis.host):$($redis.port)"
    }
} elseif ($RedisAddress -notmatch '^redis://[^/\s:]+:\d+$') {
    throw "-RedisAddress must look like redis://10.0.0.3:6379 (got '$RedisAddress'); TLS (rediss://) is not configured."
}
if (-not (Get-GcloudJson secrets describe $RedisPasswordSecret)) {
    Stop-Deploy "Secret '$RedisPasswordSecret' not found: run infra\setup-m3.ps1 -Step redis-secret."
}

# Cloud SQL connection budget: every instance may open its whole pool, and during a deploy the old and the new
# revision run side by side.
$sql = Get-GcloudJson sql instances describe $sqlInstance
if (-not $sql) { throw "Cloud SQL instance $sqlInstance not found (project ${ProjectId})." }
$maxConnectionsFlag = @($sql.settings.databaseFlags) | Where-Object { $_ -and $_.name -eq 'max_connections' } | Select-Object -First 1
$maxConnections = $null
if ($maxConnectionsFlag) { $maxConnections = [int]$maxConnectionsFlag.value }
elseif ($sql.settings.tier -eq 'db-g1-small') { $maxConnections = 50 }   # Cloud SQL default for 1.7 GB
elseif ($sql.settings.tier -eq 'db-f1-micro') { $maxConnections = 25 }
$steady = $MaxInstances * $DbPoolSize
$needed = 2 * $steady + $adminConnections
if ($null -eq $maxConnections) {
    Stop-Deploy "$sqlInstance ($($sql.settings.tier)) has no max_connections flag, so the budget cannot be checked: infra\setup-m3.ps1 -Step sql-upgrade."
} else {
    Write-Host "    DB connections: $MaxInstances instances x $DbPoolSize = $steady; x2 during a deploy + $adminConnections admin = $needed of max_connections $maxConnections ($($sql.settings.tier))"
    if ($needed -gt $maxConnections) {
        Stop-Deploy "Connection budget exceeded ($needed > $maxConnections): upgrade Cloud SQL first (infra\setup-m3.ps1 -Step sql-upgrade), or lower -MaxInstances / -DbPoolSize."
    }
}

# '^;^' switches gcloud's list delimiter to ';' so SPRING_PROFILES_ACTIVE can contain commas.
$envVars = "^;^SPRING_PROFILES_ACTIVE=$Profiles;DB_NAME=parking;DB_USER=parking_app;INSTANCE_CONNECTION_NAME=${ProjectId}:${Region}:$sqlInstance"
$envVars += ";KMS_KEY_NAME=projects/$ProjectId/locations/$Region/keyRings/parking-keyring/cryptoKeys/password-key"
$envVars += ";DB_POOL_SIZE=$DbPoolSize;PARKING_CONCURRENCY_RESERVATIONPERMITS_PERMITS=$ReservationPermits;REDIS_ADDRESS=$RedisAddress"
if ($ExtraEnv) { $envVars += ";$ExtraEnv" }
$secrets = "DB_PASSWORD=db-password:latest,JWT_SECRET=jwt-signing-key:latest,REDIS_PASSWORD=${RedisPasswordSecret}:latest"
$cpuThrottling = '--cpu-throttling'
if ($CpuAllocation -eq 'instance') { $cpuThrottling = '--no-cpu-throttling' }

$deployArgs = @(
    'run', 'deploy', $service, '--source', '.', '--project', $ProjectId, '--region', $Region,
    '--service-account', "parking-run-sa@$ProjectId.iam.gserviceaccount.com",
    '--network', 'parking-vpc', '--subnet', 'parking-run-subnet', '--vpc-egress', 'private-ranges-only',
    '--set-secrets', $secrets,
    '--set-env-vars', $envVars,
    '--cpu', $Cpu, '--memory', $Memory, '--concurrency', "$Concurrency", $cpuThrottling, '--cpu-boost',
    '--timeout', "$TimeoutSeconds",
    # Maximum at both levels, same value (Cloud Run applies the lower one; this replaces the old hidden service cap).
    # Minimum at service level only (revision level cleared), so --min 8 / --min 1 later needs no new revision.
    '--min', "$MinInstances", '--max', "$MaxInstances", '--min-instances', 'default', '--max-instances', "$MaxInstances",
    '--allow-unauthenticated',
    '--quiet')

Write-Host "==> Plan: $service in $ProjectId / $Region (profiles: $Profiles)"
Write-Host "    Cloud Run: $Cpu vCPU, $Memory, concurrency $Concurrency, instances $MinInstances..$MaxInstances (max at service + revision level), CPU allocation $CpuAllocation, timeout ${TimeoutSeconds}s, startup CPU boost"
Write-Host "    App: DB_POOL_SIZE=$DbPoolSize, reservation permits=$ReservationPermits, REDIS_ADDRESS=$RedisAddress, JVM options from the Dockerfile"
Write-Host "    gcloud $($deployArgs -join ' ')"
if ($DryRun) {
    Write-Host '==> Dry run: no tests, no deploy.'
    return
}

Write-Host '==> Unit + integration tests (./gradlew test integrationTest)'
# 'Continue' while probing: PowerShell 5.1 turns a native command's redirected stderr into a terminating error.
$ErrorActionPreference = 'Continue'
$dockerOk = $false
if (Get-Command docker -ErrorAction SilentlyContinue) {
    docker info --format '{{.ServerVersion}}' *> $null
    $dockerOk = ($LASTEXITCODE -eq 0)
}
$ErrorActionPreference = 'Stop'
if (-not $dockerOk) { throw 'Docker is not running: integration tests need it - deployment aborted.' }
Push-Location (Join-Path $repoRoot 'backend')
try {
    .\gradlew.bat --console=plain test integrationTest
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed - deployment aborted.' }
} finally { Pop-Location }

Write-Host "==> Deploying $service to $ProjectId / $Region (profiles: $Profiles)"
# gcloud writes progress to stderr; Windows PowerShell 5.1 turns redirected stderr into error records.
# Judge native commands by their exit code instead.
$ErrorActionPreference = 'Continue'
Push-Location $repoRoot
try {
    & gcloud @deployArgs
    if ($LASTEXITCODE -ne 0) { throw 'gcloud run deploy failed.' }
} finally { Pop-Location }

$url = gcloud run services describe $service --project $ProjectId --region $Region --format 'value(status.url)'
Write-Host "==> Deployed: $url"
Write-Host "    Health:   $url/actuator/health"
Write-Host '    Scaling and Redis settings: .\infra\setup-m3.ps1 -Step status'
