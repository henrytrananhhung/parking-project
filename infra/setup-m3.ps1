<#
  M3 infrastructure for the EXISTING GCP environment (Windows PowerShell 5.1 or later, run from the repo root).

  Same application behavior, more capacity: this upgrades Cloud SQL parking-db and adds the only new component M3
  needs, Memorystore for Redis. Everything else is reused (project, VPC, subnet, Direct VPC egress, Private Services
  Access, parking-run-sa, Cloud KMS, Secret Manager, Artifact Registry, bastion). infra/setup.sh is NOT re-run.

  Run one step at a time, in this order. Each step first checks the current state and skips what is already done,
  so every step is safe to re-run. What each step does, how to verify it, cost and rollback: docs/m3-gcp-setup.md

    .\infra\setup-m3.ps1 -Step status         read-only overview (run it before and after every step)
    .\infra\setup-m3.ps1 -Step backup         1. on-demand backup of parking-db, then deletion protection
    .\infra\setup-m3.ps1 -Step sql-upgrade    2. parking-db -> db-custom-2-7680 + max_connections=300 (RESTARTS the database)
    .\infra\setup-m3.ps1 -Step redis-api      3. enable the Memorystore for Redis API
    .\infra\setup-m3.ps1 -Step redis          4. create Memorystore parking-redis (takes 5-10 minutes)
    .\infra\setup-m3.ps1 -Step redis-secret   5. store its AUTH string in Secret Manager as redis-password
    .\infra\setup-m3.ps1 -Step iam            6. let parking-run-sa read redis-password

  -DryRun: run the read-only checks and print the commands that would change something, without running them.
  Secret values are never printed.
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('status', 'backup', 'sql-upgrade', 'redis-api', 'redis', 'redis-secret', 'iam')]
    [string]$Step,
    [string]$ProjectId = 'parking-project-demo-2026',
    [string]$Region = 'asia-southeast1',
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'

# Existing resources (infra/setup.sh, Phase 1).
$Zone = 'asia-southeast1-c'                  # zone of parking-db and the bastion
$Vpc = 'parking-vpc'
$PsaRange = 'google-managed-services-parking'   # Private Services Access range, already used by Cloud SQL
$SqlInstance = 'parking-db'
$RunService = 'parking-reservation'
$RunSa = "parking-run-sa@$ProjectId.iam.gserviceaccount.com"
# New / changed.
$Redis = 'parking-redis'
$RedisSecret = 'redis-password'
$BackupDescription = 'pre-m3'
$BackupMaxAgeHours = 24                      # the upgrade needs a successful backup at most this old
$TargetTier = 'db-custom-2-7680'             # 2 dedicated vCPUs, 7.5 GB
$TargetMaxConnections = 300
# The connection budget the upgrade is sized for (infra/deploy.ps1 defaults):
# max instances x pool, twice while an old and a new revision overlap during a deploy, plus admin connections
# (superuser reserved slots 3, Cloud SQL's own cloudsqladmin sessions 2, Cloud SQL Studio / psql ~5).
$PlannedMaxInstances = 10      # Cloud Run quotas here: 20 vCPUs, 10 instances with Direct VPC egress
$PlannedPoolSize = 10
$AdminConnections = 10

# ---------------------------------------------------------------------------------------------------------------
# gcloud helpers. gcloud writes progress to stderr, and Windows PowerShell 5.1 turns redirected stderr into error
# records, so native calls run with 'Continue' and are judged by their exit code (as in deploy.ps1).
# ---------------------------------------------------------------------------------------------------------------

function Get-GcloudJson {
    # Read-only call: the parsed JSON, or nothing if it fails (not found, API disabled, ...). --quiet declines prompts.
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

function Invoke-Change([string[]]$GcloudArgs) {
    # A call that changes GCP: printed first, skipped with -DryRun. Never receives secret values (see Set-SecretValue).
    $all = $GcloudArgs + @('--project', $ProjectId, '--quiet')
    Write-Host "  > gcloud $($all -join ' ')" -ForegroundColor Cyan
    if ($DryRun) { Write-Host '    dry run: not executed' -ForegroundColor Yellow; return }
    $ErrorActionPreference = 'Continue'
    & gcloud @all
    if ($LASTEXITCODE -ne 0) { throw "gcloud $($GcloudArgs[0..2] -join ' ') failed (exit code $LASTEXITCODE)." }
}

function Stop-Step([string]$Message) {
    # A missing prerequisite: an error normally, a warning in a dry run (so the rest can still be previewed).
    if ($DryRun) { Write-Warning $Message } else { throw $Message }
}

function Read-SecretValue([string]$Name) {
    # The latest version, kept in memory only (used to compare, never printed).
    $ErrorActionPreference = 'Continue'
    $value = & gcloud secrets versions access latest --secret $Name --project $ProjectId --quiet 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($value -join "`n")
}

function Set-SecretValue([string]$Name, [string]$Value) {
    # Creates the secret, or adds a version if the value changed. The value goes through a temporary file (a
    # PowerShell 5.1 pipe would append a newline) that is deleted right away.
    if (-not $Value) { throw "Refusing to store an empty value in secret $Name." }
    $exists = [bool](Get-GcloudJson secrets describe $Name)
    if ($exists -and ((Read-SecretValue $Name) -ceq $Value)) {
        Write-Host "  secret $Name already holds the current value"
        return
    }
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        if (-not $DryRun) { [System.IO.File]::WriteAllText($tmp, $Value) }
        if ($exists) {
            Invoke-Change @('secrets', 'versions', 'add', $Name, "--data-file=$tmp")
        } else {
            Invoke-Change @('secrets', 'create', $Name, '--replication-policy=automatic', "--data-file=$tmp")
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

# ---------------------------------------------------------------------------------------------------------------
# State
# ---------------------------------------------------------------------------------------------------------------

function Get-SqlInstance {
    $sql = Get-GcloudJson sql instances describe $SqlInstance
    if (-not $sql) { throw "Cloud SQL instance $SqlInstance not found (project ${ProjectId})." }
    return $sql
}

function Get-DatabaseFlags($Sql) {
    # Existing database flags, in order. --database-flags REPLACES the whole list, so the upgrade re-sends them.
    $flags = [ordered]@{}
    foreach ($f in @($Sql.settings.databaseFlags)) { if ($f) { $flags[$f.name] = $f.value } }
    return $flags
}

function Format-Flags($Flags) {
    if ($Flags.Count -eq 0) { return '(none)' }
    return (($Flags.GetEnumerator() | ForEach-Object {
        if ($null -ne $_.Value -and "$($_.Value)" -ne '') { "$($_.Key)=$($_.Value)" } else { $_.Key }
    }) -join ',')
}

function Get-MaxConnections($Sql) {
    # The max_connections flag, else Cloud SQL's memory-based default for the shared-core tiers.
    $flags = Get-DatabaseFlags $Sql
    if ($flags.Contains('max_connections')) { return [int]$flags['max_connections'] }
    switch ($Sql.settings.tier) {
        'db-f1-micro' { return 25 }
        'db-g1-small' { return 50 }
        default { return $null }
    }
}

function ConvertTo-UtcTime($Value) {
    # Windows PowerShell 5.1 keeps JSON timestamps as strings; PowerShell 7 already converts them to DateTime.
    if ($Value -is [datetime]) { return $Value.ToUniversalTime() }
    return [DateTimeOffset]::Parse("$Value", [System.Globalization.CultureInfo]::InvariantCulture).UtcDateTime
}

function Get-RecentBackup {
    # The newest SUCCESSFUL pre-m3 backup that finished within the last $BackupMaxAgeHours hours.
    $cutoff = (Get-Date).ToUniversalTime().AddHours(-$BackupMaxAgeHours)
    @(Get-GcloudJson sql backups list --instance $SqlInstance) |
        Where-Object { $_ -and $_.description -eq $BackupDescription -and $_.status -eq 'SUCCESSFUL' -and $_.endTime } |
        Where-Object { (ConvertTo-UtcTime $_.endTime) -gt $cutoff } |
        Sort-Object { ConvertTo-UtcTime $_.endTime } -Descending |
        Select-Object -First 1
}

function Test-RedisApi {
    return (@(Get-GcloudJson services list --enabled --filter=config.name=redis.googleapis.com).Count -gt 0)
}

function Get-RedisMismatches($R) {
    # Prints every expected setting; returns the names of those that differ.
    $checks = [ordered]@{
        'state READY'                        = ($R.state -eq 'READY')
        'version REDIS_7_2'                  = ($R.redisVersion -eq 'REDIS_7_2')
        'tier BASIC'                         = ($R.tier -eq 'BASIC')
        'memory 1 GB'                        = ([int]$R.memorySizeGb -eq 1)
        "network $Vpc"                       = ("$($R.authorizedNetwork)" -like "*/networks/$Vpc")
        'connect mode PRIVATE_SERVICE_ACCESS' = ($R.connectMode -eq 'PRIVATE_SERVICE_ACCESS')
        'AUTH enabled'                       = ($R.authEnabled -eq $true)
        'transit encryption DISABLED'        = ("$($R.transitEncryptionMode)" -in @('', 'DISABLED', 'TRANSIT_ENCRYPTION_MODE_UNSPECIFIED'))
        'maxmemory-policy noeviction'        = ($R.redisConfigs.'maxmemory-policy' -eq 'noeviction')
        'persistence DISABLED'               = (-not $R.persistenceConfig -or "$($R.persistenceConfig.persistenceMode)" -in @('', 'DISABLED'))
    }
    $bad = @()
    foreach ($c in $checks.GetEnumerator()) {
        if ($c.Value) { Write-Host "    ok        $($c.Key)" } else { Write-Host "    MISMATCH  $($c.Key)" -ForegroundColor Red; $bad += $c.Key }
    }
    return $bad
}

# ---------------------------------------------------------------------------------------------------------------
# Steps
# ---------------------------------------------------------------------------------------------------------------

function Show-Status {
    Write-Host "== Cloud SQL $SqlInstance"
    $sql = Get-SqlInstance
    $s = $sql.settings
    $ip = (@($sql.ipAddresses) | Where-Object { $_.type -eq 'PRIVATE' } | Select-Object -First 1).ipAddress
    Write-Host "    state $($sql.state), tier $($s.tier), $($sql.databaseVersion), zone $($sql.gceZone), private IP $ip"
    Write-Host "    disk $($s.dataDiskSizeGb) GB $($s.dataDiskType), deletion protection $([bool]$s.deletionProtectionEnabled)"
    Write-Host "    database flags: $(Format-Flags (Get-DatabaseFlags $sql))"
    $max = Get-MaxConnections $sql
    $needed = 2 * $PlannedMaxInstances * $PlannedPoolSize + $AdminConnections
    Write-Host "    max_connections $max; M3 budget $PlannedMaxInstances x $PlannedPoolSize x 2 + $AdminConnections = $needed -> $(if ($max -and $needed -le $max) { 'fits' } else { 'DOES NOT FIT (run -Step sql-upgrade)' })"
    $b = Get-RecentBackup
    if ($b) { Write-Host "    '$BackupDescription' backup: id $($b.id), SUCCESSFUL, finished $($b.endTime)" }
    else { Write-Host "    '$BackupDescription' backup: none in the last $BackupMaxAgeHours h" }

    Write-Host "== Memorystore $Redis"
    if (-not (Test-RedisApi)) {
        Write-Host '    redis.googleapis.com is not enabled (step 3)'
    } else {
        $r = Get-GcloudJson redis instances describe $Redis --region $Region
        if (-not $r) { Write-Host '    not created yet (step 4)' }
        else {
            Write-Host "    host $($r.host):$($r.port), reserved range $($r.reservedIpRange), zone $($r.currentLocationId)"
            $null = Get-RedisMismatches $r
        }
    }

    Write-Host "== Secret $RedisSecret"
    if (-not (Get-GcloudJson secrets describe $RedisSecret)) {
        Write-Host '    not created yet (step 5)'
    } else {
        if ((Test-RedisApi) -and ($auth = (Get-GcloudJson redis instances get-auth-string $Redis --region $Region).authString)) {
            Write-Host "    matches the instance's current AUTH string: $((Read-SecretValue $RedisSecret) -ceq $auth)"
        }
        $policy = Get-GcloudJson secrets get-iam-policy $RedisSecret
        $granted = @($policy.bindings) | Where-Object { $_ -and $_.role -eq 'roles/secretmanager.secretAccessor' -and @($_.members) -contains "serviceAccount:$RunSa" }
        Write-Host "    parking-run-sa can read it: $([bool]$granted)$(if (-not $granted) { ' (step 6)' })"
    }

    Write-Host "== Cloud Run $RunService"
    $svc = Get-GcloudJson run services describe $RunService --region $Region
    if (-not $svc) { Write-Host '    not found' } else {
        $ann = $svc.metadata.annotations
        $tAnn = $svc.spec.template.metadata.annotations
        $c = $svc.spec.template.spec.containers[0]
        $envNames = @($c.env | ForEach-Object { $_.name })
        Write-Host "    revision $($svc.status.latestReadyRevisionName): cpu $($c.resources.limits.cpu), memory $($c.resources.limits.memory), concurrency $($svc.spec.template.spec.containerConcurrency)"
        $scale = @($ann.'run.googleapis.com/minScale', $ann.'run.googleapis.com/maxScale',
            $tAnn.'autoscaling.knative.dev/minScale', $tAnn.'autoscaling.knative.dev/maxScale') |
            ForEach-Object { if ($_) { $_ } else { '-' } }
        Write-Host "    instances: service-level min $($scale[0]) max $($scale[1]); revision-level min $($scale[2]) max $($scale[3]) (Cloud Run applies the lower max; '-' = not set)"
        Write-Host "    REDIS_ADDRESS set: $($envNames -contains 'REDIS_ADDRESS') (M3 not deployed yet if False)"
    }
}

function Invoke-BackupStep {
    Write-Host "== 1. Backup $SqlInstance and turn on deletion protection"
    $b = Get-RecentBackup
    if ($b) {
        Write-Host "  a successful '$BackupDescription' backup already exists: id $($b.id), finished $($b.endTime)"
    } else {
        # Waits until the backup has finished (no --async).
        Invoke-Change @('sql', 'backups', 'create', "--instance=$SqlInstance", "--description=$BackupDescription")
        if (-not $DryRun) {
            $b = Get-RecentBackup
            if (-not $b) { throw "The backup did not finish successfully: check 'gcloud sql backups list --instance=$SqlInstance'. Do not upgrade." }
            Write-Host "  backup id $($b.id): SUCCESSFUL, finished $($b.endTime)"
        }
    }
    $sql = Get-SqlInstance
    if ($sql.settings.deletionProtectionEnabled) {
        Write-Host '  deletion protection is already on'
    } else {
        # No restart: deletion protection is instance metadata.
        Invoke-Change @('sql', 'instances', 'patch', $SqlInstance, '--deletion-protection')
    }
}

function Invoke-SqlUpgradeStep {
    Write-Host "== 2. Upgrade $SqlInstance to $TargetTier with max_connections=$TargetMaxConnections"
    $needed = 2 * $PlannedMaxInstances * $PlannedPoolSize + $AdminConnections
    Write-Host "  connection budget: $PlannedMaxInstances instances x $PlannedPoolSize = $($PlannedMaxInstances * $PlannedPoolSize); x2 during a deploy overlap + $AdminConnections admin = $needed <= $TargetMaxConnections"
    if ($needed -gt $TargetMaxConnections) { throw "The planned budget ($needed) exceeds max_connections=$TargetMaxConnections." }

    $sql = Get-SqlInstance
    $flags = Get-DatabaseFlags $sql
    $before = Format-Flags $flags
    Write-Host "  current: tier $($sql.settings.tier), flags $before, max_connections $(Get-MaxConnections $sql), disk $($sql.settings.dataDiskSizeGb) GB (unchanged)"
    if ($sql.settings.tier -eq $TargetTier -and "$($flags['max_connections'])" -eq "$TargetMaxConnections") {
        Write-Host '  already upgraded'
        return
    }
    $b = Get-RecentBackup
    if ($b) { Write-Host "  backup check: id $($b.id) SUCCESSFUL, finished $($b.endTime)" }
    else { Stop-Step "No successful '$BackupDescription' backup of $SqlInstance in the last $BackupMaxAgeHours h: run -Step backup first." }

    foreach ($f in $flags.GetEnumerator()) {
        if ("$($f.Value)" -match ',') { throw "Flag $($f.Key) has a comma in its value: set the flags by hand (docs/m3-gcp-setup.md)." }
    }
    $flags['max_connections'] = "$TargetMaxConnections"   # every other existing flag is kept as is
    Write-Host "  rollback values (keep them): tier $($sql.settings.tier), flags $before"
    Write-Host "  WARNING: this restarts $SqlInstance. The demo cannot reach its database for a few minutes." -ForegroundColor Yellow
    if (-not $DryRun) {
        $answer = Read-Host "  Type RESTART to upgrade $SqlInstance now"
        if ($answer -cne 'RESTART') { Write-Host '  cancelled'; return }
    }
    Invoke-Change @('sql', 'instances', 'patch', $SqlInstance, "--tier=$TargetTier", "--database-flags=$(Format-Flags $flags)")
    if (-not $DryRun) {
        $sql = Get-SqlInstance
        Write-Host "  now: state $($sql.state), tier $($sql.settings.tier), flags $(Format-Flags (Get-DatabaseFlags $sql))"
        Write-Host '  next: confirm in Cloud SQL Studio that SHOW max_connections returns 300 (docs/m3-gcp-setup.md, step 2)'
    }
}

function Invoke-RedisApiStep {
    Write-Host '== 3. Enable the Memorystore for Redis API'
    if (Test-RedisApi) { Write-Host '  redis.googleapis.com is already enabled'; return }
    Invoke-Change @('services', 'enable', 'redis.googleapis.com')
}

function Invoke-RedisStep {
    Write-Host "== 4. Memorystore $Redis"
    if (-not (Test-RedisApi)) { Stop-Step 'redis.googleapis.com is not enabled: run -Step redis-api first.'; $r = $null }
    else { $r = Get-GcloudJson redis instances describe $Redis --region $Region }
    if ($r) {
        Write-Host "  already exists: $($r.host):$($r.port)"
        $bad = @(Get-RedisMismatches $r)
        if ($bad.Count -eq 1 -and $bad[0] -eq 'maxmemory-policy noeviction') {
            Invoke-Change @('redis', 'instances', 'update', $Redis, "--region=$Region", '--update-redis-config=maxmemory-policy=noeviction')
        } elseif ($bad.Count -gt 0) {
            throw "$Redis differs from the expected configuration ($($bad -join ', ')). Most of these settings are fixed at creation: delete and recreate it (docs/m3-gcp-setup.md)."
        }
        return
    }
    Invoke-Change @('redis', 'instances', 'create', $Redis,
        "--region=$Region", "--zone=$Zone",
        '--tier=basic', '--size=1', '--redis-version=redis_7_2',
        "--network=projects/$ProjectId/global/networks/$Vpc",
        '--connect-mode=private-service-access', "--reserved-ip-range=$PsaRange",
        '--enable-auth', '--transit-encryption-mode=disabled', '--persistence-mode=disabled',
        '--redis-config=maxmemory-policy=noeviction',
        '--labels=app=parking,component=m3')
    if (-not $DryRun) {
        $r = Get-GcloudJson redis instances describe $Redis --region $Region
        Write-Host "  created: $($r.host):$($r.port)"
        $bad = @(Get-RedisMismatches $r)
        if ($bad.Count -gt 0) { throw "$Redis was created but differs from the expected configuration: $($bad -join ', ')." }
    }
}

function Invoke-RedisSecretStep {
    Write-Host "== 5. Store the AUTH string of $Redis in Secret Manager ($RedisSecret)"
    $r = $null
    if (Test-RedisApi) { $r = Get-GcloudJson redis instances describe $Redis --region $Region }
    if (-not $r) { Stop-Step "$Redis does not exist: run -Step redis first."; return }
    if (-not $r.authEnabled) { throw "$Redis has AUTH disabled." }
    $auth = (Get-GcloudJson redis instances get-auth-string $Redis --region $Region).authString
    if (-not $auth) { throw "Could not read the AUTH string of $Redis." }
    Set-SecretValue $RedisSecret $auth
}

function Invoke-IamStep {
    Write-Host "== 6. Let parking-run-sa read $RedisSecret"
    if (-not (Get-GcloudJson secrets describe $RedisSecret)) { Stop-Step "Secret $RedisSecret does not exist: run -Step redis-secret first." }
    $member = "serviceAccount:$RunSa"
    $policy = Get-GcloudJson secrets get-iam-policy $RedisSecret
    $granted = @($policy.bindings) | Where-Object { $_ -and $_.role -eq 'roles/secretmanager.secretAccessor' -and @($_.members) -contains $member }
    if ($granted) { Write-Host '  already granted'; return }
    Invoke-Change @('secrets', 'add-iam-policy-binding', $RedisSecret, "--member=$member", '--role=roles/secretmanager.secretAccessor')
}

# ---------------------------------------------------------------------------------------------------------------

if ($DryRun) { Write-Host '*** DRY RUN: read-only checks only; commands that change GCP are printed, not run ***' -ForegroundColor Yellow }
switch ($Step) {
    'status' { Show-Status }
    'backup' { Invoke-BackupStep }
    'sql-upgrade' { Invoke-SqlUpgradeStep }
    'redis-api' { Invoke-RedisApiStep }
    'redis' { Invoke-RedisStep }
    'redis-secret' { Invoke-RedisSecretStep }
    'iam' { Invoke-IamStep }
}
