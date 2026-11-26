<#
  End-to-end smoke test of the real business flow against a running deployment (local or Cloud Run).

  Usage (repo root):
    .\infra\smoke.ps1 -BaseUrl https://parking-reservation-866340516434.asia-southeast1.run.app
    .\infra\smoke.ps1 -BaseUrl http://localhost:8080

  - Creates ONE test account per run, named smoke-yyyyMMdd-HHmmss-xxxx, appended to infra/smoke-accounts.log
    (git-ignored) so it can be removed explicitly with infra/smoke-cleanup.sql.
  - Uses at most ONE parking space per run. Each business error is checked independently:
      deposit $15 -> V1 reserves free space S1 (balance $5)
      V1 -> S1 again (client retry) : same reservation, not charged twice
      V2 -> S1 : SPACE_ALREADY_RESERVED
      V1 -> S2 : VEHICLE_ALREADY_RESERVED
      V2 -> S2 : INSUFFICIENT_BALANCE
    and after every error: balance unchanged and S2 still free.
  - Never prints the password or tokens.
#>
param([Parameter(Mandatory = $true)][string]$BaseUrl)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:failures = 0

# HttpClient (not Invoke-WebRequest): identical on Windows PowerShell 5.1 and PowerShell 7,
# returns 4xx bodies (application/problem+json) without throwing.
Add-Type -AssemblyName System.Net.Http
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$script:http = [System.Net.Http.HttpClient]::new()
$script:http.Timeout = [TimeSpan]::FromSeconds(30)

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = '', [hashtable]$Headers = @{})
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$BaseUrl$Path")
    if ($Token) {
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    }
    foreach ($name in $Headers.Keys) {
        $request.Headers.Add($name, [string]$Headers[$name])
    }
    if ($null -ne $Body) {
        $request.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Compress),
            [System.Text.Encoding]::UTF8, 'application/json')
    }
    $response = $script:http.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $json = $null
    if ($text) { try { $json = $text | ConvertFrom-Json } catch { } }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $json }
}

function Test-Check {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) { Write-Host "  PASS  $Name" -ForegroundColor Green }
    else { Write-Host "  FAIL  $Name  $Detail" -ForegroundColor Red; $script:failures++ }
}

function Get-RandomText([string]$Alphabet, [int]$Length) {
    -join (1..$Length | ForEach-Object { $Alphabet[(Get-Random -Maximum $Alphabet.Length)] })
}

function Get-Balance([string]$Token) { (Invoke-Api GET '/api/wallet' -Token $Token).Body.balanceCents }

function Get-Space([string]$Token, [int]$Id) {
    (Invoke-Api GET '/api/spaces' -Token $Token).Body.spaces | Where-Object { $_.id -eq $Id }
}

Write-Host "Smoke test against $BaseUrl"

# --- Health ---
# Retries cover a revision that is still rolling out or an instance cold start.
for ($attempt = 1; $attempt -le 6; $attempt++) {
    try { $health = Invoke-Api GET '/actuator/health' } catch { $health = [pscustomobject]@{ Status = 0; Body = $null } }
    if ($health.Status -eq 200 -and $health.Body.status -eq 'UP') { break }
    Start-Sleep -Seconds 5
}
Test-Check 'health UP (app + db)' ($health.Status -eq 200 -and $health.Body.status -eq 'UP' -and $health.Body.components.db.status -eq 'UP') `
    "status=$($health.Status) app=$($health.Body.status) db=$($health.Body.components.db.status)"

# --- Account (explicitly identified test account) ---
$username = 'smoke-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + (Get-RandomText 'abcdefghijklmnopqrstuvwxyz0123456789' 4)
$password = Get-RandomText 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789' 20
Add-Content -Path (Join-Path $PSScriptRoot 'smoke-accounts.log') -Value $username -Encoding ascii
Write-Host "  test account: $username (recorded in infra/smoke-accounts.log)"

$reg = Invoke-Api POST '/api/auth/register' @{ username = $username; password = $password }
Test-Check 'register -> 201' ($reg.Status -eq 201 -and $reg.Body.token) "status=$($reg.Status)"
$dup = Invoke-Api POST '/api/auth/register' @{ username = $username; password = $password }
Test-Check 'duplicate username -> USERNAME_TAKEN' ($dup.Status -eq 409 -and $dup.Body.code -eq 'USERNAME_TAKEN') "status=$($dup.Status)"
$bad = Invoke-Api POST '/api/auth/login' @{ username = $username; password = 'wrong-password' }
Test-Check 'wrong password -> 401 INVALID_CREDENTIALS' ($bad.Status -eq 401 -and $bad.Body.code -eq 'INVALID_CREDENTIALS') "status=$($bad.Status)"
$login = Invoke-Api POST '/api/auth/login' @{ username = $username; password = $password }
Test-Check 'login -> token' ($login.Status -eq 200 -and $login.Body.token) "status=$($login.Status)"
$token = $login.Body.token
if (-not $token) { Write-Host 'Cannot continue without a token.' -ForegroundColor Red; exit 1 }

$anon = Invoke-Api GET '/api/wallet'
Test-Check 'API without token -> 401' ($anon.Status -eq 401) "status=$($anon.Status)"

# --- Vehicles ---
$plate1 = 'SMK' + (Get-RandomText '0123456789' 6)
$plate2 = 'SMK' + (Get-RandomText '0123456789' 6)
$v1 = Invoke-Api POST '/api/vehicles' @{ plate = $plate1 } $token
$v2 = Invoke-Api POST '/api/vehicles' @{ plate = $plate2 } $token
Test-Check 'register 2 vehicles' ($v1.Status -eq 201 -and $v2.Status -eq 201) "status=$($v1.Status),$($v2.Status)"
$vdup = Invoke-Api POST '/api/vehicles' @{ plate = $plate1.Insert(3, '-') } $token
Test-Check 'duplicate plate (normalized) -> PLATE_TAKEN' ($vdup.Status -eq 409 -and $vdup.Body.code -eq 'PLATE_TAKEN') "status=$($vdup.Status)"

# --- Wallet (every deposit carries an Idempotency-Key; a retry reuses it) ---
$noKey = Invoke-Api POST '/api/wallet/deposits' @{ amount = 15 } $token
Test-Check 'deposit without Idempotency-Key -> IDEMPOTENCY_KEY_INVALID' ($noKey.Status -eq 400 -and $noKey.Body.code -eq 'IDEMPOTENCY_KEY_INVALID') "status=$($noKey.Status)"
$badDeposit = Invoke-Api POST '/api/wallet/deposits' @{ amount = 0 } $token @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
Test-Check 'deposit 0 -> INVALID_AMOUNT' ($badDeposit.Status -eq 400 -and $badDeposit.Body.code -eq 'INVALID_AMOUNT') "status=$($badDeposit.Status)"
$depositKey = @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$dep = Invoke-Api POST '/api/wallet/deposits' @{ amount = 15 } $token $depositKey
Test-Check 'deposit $15 -> balance 1500 cents' ($dep.Status -eq 200 -and $dep.Body.balanceCents -eq 1500) "balance=$($dep.Body.balanceCents)"
$depRetry = Invoke-Api POST '/api/wallet/deposits' @{ amount = 15 } $token $depositKey
Test-Check 'deposit retried with the same key -> applied once (1500)' ($depRetry.Status -eq 200 -and $depRetry.Body.balanceCents -eq 1500) "balance=$($depRetry.Body.balanceCents)"

# --- Dashboard ---
$dash = Invoke-Api GET '/api/spaces' -Token $token
Test-Check 'dashboard lists 80 spaces' ($dash.Status -eq 200 -and @($dash.Body.spaces).Count -eq 80)
Write-Host "  target date $($dash.Body.date), window open=$($dash.Body.open) (opens $($dash.Body.opensAt) $($dash.Body.timezone))"
$free = @($dash.Body.spaces | Where-Object { -not $_.reserved } | Sort-Object id -Descending)

if (-not $dash.Body.open) {
    # Outside the window (normal mode): verify the rule without using a space.
    $closed = Invoke-Api POST '/api/reservations' @{ spaceId = $free[0].id; vehicleId = $v1.Body.id } $token
    Test-Check 'window closed -> RESERVATION_CLOSED' ($closed.Status -eq 409 -and $closed.Body.code -eq 'RESERVATION_CLOSED') "status=$($closed.Status)"
    Test-Check 'balance unchanged' ((Get-Balance $token) -eq 1500)
} elseif ($free.Count -lt 2) {
    Test-Check 'at least 2 free spaces available for reservation checks' $false "free=$($free.Count)"
} else {
    $s1 = $free[0].id; $s2 = $free[1].id
    Write-Host "  using free spaces S1=$s1, S2=$s2"

    $ok = Invoke-Api POST '/api/reservations' @{ spaceId = $s1; vehicleId = $v1.Body.id } $token
    Test-Check "V1 reserves S1 -> 201" ($ok.Status -eq 201 -and $ok.Body.spaceId -eq $s1) "status=$($ok.Status) code=$($ok.Body.code)"
    Test-Check 'exactly $10 deducted (1500 -> 500)' ((Get-Balance $token) -eq 500)
    $s1View = Get-Space $token $s1
    Test-Check 'S1 shows reserved, mine, last 3 plate chars' ($s1View.reserved -and $s1View.mine -and $s1View.plateSuffix -eq $plate1.Substring($plate1.Length - 3))

    $retry = Invoke-Api POST '/api/reservations' @{ spaceId = $s1; vehicleId = $v1.Body.id } $token
    Test-Check 'V1 -> S1 again (retry): same reservation' ($retry.Status -eq 201 -and $retry.Body.id -eq $ok.Body.id) "status=$($retry.Status) code=$($retry.Body.code)"
    Test-Check '  not charged twice' ((Get-Balance $token) -eq 500)

    $e1 = Invoke-Api POST '/api/reservations' @{ spaceId = $s1; vehicleId = $v2.Body.id } $token
    Test-Check 'V2 -> S1: SPACE_ALREADY_RESERVED' ($e1.Status -eq 409 -and $e1.Body.code -eq 'SPACE_ALREADY_RESERVED') "code=$($e1.Body.code)"
    Test-Check '  balance unchanged' ((Get-Balance $token) -eq 500)

    $e2 = Invoke-Api POST '/api/reservations' @{ spaceId = $s2; vehicleId = $v1.Body.id } $token
    Test-Check 'V1 -> S2: VEHICLE_ALREADY_RESERVED' ($e2.Status -eq 409 -and $e2.Body.code -eq 'VEHICLE_ALREADY_RESERVED') "code=$($e2.Body.code)"
    Test-Check '  balance unchanged, S2 still free' ((Get-Balance $token) -eq 500 -and -not (Get-Space $token $s2).reserved)

    $e3 = Invoke-Api POST '/api/reservations' @{ spaceId = $s2; vehicleId = $v2.Body.id } $token
    Test-Check 'V2 -> S2: INSUFFICIENT_BALANCE' ($e3.Status -eq 409 -and $e3.Body.code -eq 'INSUFFICIENT_BALANCE') "code=$($e3.Body.code)"
    Test-Check '  balance unchanged, S2 still free (rolled back)' ((Get-Balance $token) -eq 500 -and -not (Get-Space $token $s2).reserved)

    $mine = @((Invoke-Api GET '/api/reservations/me' -Token $token).Body)
    Test-Check 'my reservations: exactly one, on S1' ($mine.Count -eq 1 -and $mine[0].spaceId -eq $s1)
}

Write-Host ''
if ($script:failures -eq 0) { Write-Host 'SMOKE TEST PASSED' -ForegroundColor Green; exit 0 }
Write-Host "SMOKE TEST FAILED: $script:failures check(s)" -ForegroundColor Red
exit 1
