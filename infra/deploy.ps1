<#
  Build + deploy to Cloud Run from Windows (PowerShell).
  Unit AND integration tests must pass first (integration tests need Docker running: they prove the
  concurrency, payment and idempotency rules against real PostgreSQL). The container is built by Cloud Build
  from the root Dockerfile.

  Usage (from the repo root):
    .\infra\deploy.ps1 -ProjectId my-project
    .\infra\deploy.ps1 -ProjectId my-project -Profiles gcp,demo -MinInstances 1   # teammate demo (window open all day)
    .\infra\deploy.ps1 -ProjectId my-project -ExtraEnv 'RESERVATION_OPEN_TIME=20:00'  # normal rules
#>
param(
    [Parameter(Mandatory = $true)][string]$ProjectId,
    [string]$Region = 'asia-southeast1',
    [string[]]$Profiles = @('gcp'),  # -Profiles gcp,demo (PowerShell passes it as a list)
    [string]$ExtraEnv = '',          # Additional env vars, ';'-separated: 'A=1;B=2'
    [int]$MinInstances = 0,
    [int]$MaxInstances = 2
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$Profiles = ($Profiles -join ',')

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

# '^;^' switches gcloud's list delimiter to ';' so SPRING_PROFILES_ACTIVE can contain commas.
$envVars = "^;^SPRING_PROFILES_ACTIVE=$Profiles;DB_NAME=parking;DB_USER=parking_app;INSTANCE_CONNECTION_NAME=${ProjectId}:${Region}:parking-db"
$envVars += ";KMS_KEY_NAME=projects/$ProjectId/locations/$Region/keyRings/parking-keyring/cryptoKeys/password-key"
if ($ExtraEnv) { $envVars += ";$ExtraEnv" }

Write-Host "==> Deploying parking-reservation to $ProjectId / $Region (profiles: $Profiles)"
# gcloud writes progress to stderr; Windows PowerShell 5.1 turns redirected stderr into error records.
# Judge native commands by their exit code instead.
$ErrorActionPreference = 'Continue'
Push-Location $repoRoot
try {
    gcloud run deploy parking-reservation `
        --source . `
        --project $ProjectId `
        --region $Region `
        --service-account "parking-run-sa@$ProjectId.iam.gserviceaccount.com" `
        --network parking-vpc `
        --subnet parking-run-subnet `
        --vpc-egress private-ranges-only `
        --set-secrets 'DB_PASSWORD=db-password:latest,JWT_SECRET=jwt-signing-key:latest' `
        --set-env-vars $envVars `
        --cpu 1 --memory 1Gi --concurrency 80 `
        --min-instances $MinInstances --max-instances $MaxInstances `
        --cpu-boost --timeout 60 `
        --allow-unauthenticated `
        --quiet
    if ($LASTEXITCODE -ne 0) { throw 'gcloud run deploy failed.' }
} finally { Pop-Location }

$url = gcloud run services describe parking-reservation --project $ProjectId --region $Region --format 'value(status.url)'
Write-Host "==> Deployed: $url"
Write-Host "    Health:   $url/actuator/health"
