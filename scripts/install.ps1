<#
.SYNOPSIS
  Samvaad one-command installer (Windows PowerShell).

.DESCRIPTION
  Installs the published Samvaad release into C:\Samvaad without requiring
  a repository checkout:

    irm https://raw.githubusercontent.com/animesh0404/samvaad-server/main/scripts/install.ps1 | iex

  Docker required            (never installed by this script)
      |
  Creates C:\Samvaad
      |
  Downloads compose.yaml from the Samvaad GitHub repository
      |
  Creates/reuses .env (generates the database password, asks about the
  JWT secret, preserves existing secrets on re-runs)
      |
  docker compose up -d (published versioned image, never built locally)
      |
  Verifies Samvaad responds at http://localhost:8080

  Idempotent: re-running refreshes compose.yaml, keeps existing secrets,
  and converges the deployment. Secrets are never printed.

  Responsibility boundary (ADR 0015/0016): bootstrap layer only. Release
  publication stays in scripts/release-image.sh, the portable local image
  artifact stays in scripts/build.sh, iterative Docker development uses
  compose.dev.yaml, day-to-day lifecycle stays in
  scripts/start.sh, scripts/restart.sh, and scripts/stop.sh.
#>

$ErrorActionPreference = 'Stop'

$InstallDir   = 'C:\Samvaad'
$ComposeUrl   = 'https://raw.githubusercontent.com/animesh0404/samvaad-server/main/compose.yaml'
$AppUrl       = 'http://localhost:8080/'
$StartupTimeoutSeconds = 120

function Fail([string]$Message) {
  Write-Error $Message
  exit 1
}

# --- Prerequisite checks ------------------------------------------------------
# Docker itself is never installed here; the user must install it first.
if (-not (Get-Command 'docker' -ErrorAction SilentlyContinue)) {
  Fail "Docker is not installed. Install Docker Desktop (https://docs.docker.com/get-docker/) and run this installer again."
}

docker info 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
  Fail "Docker is installed but the daemon is not running. Start Docker Desktop and run this installer again."
}

docker compose version 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
  Fail "Docker Compose is not available. Update Docker Desktop to a version that includes 'docker compose' and run this installer again."
}

# --- Installation directory ---------------------------------------------------
try {
  New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
  Set-Location -Path $InstallDir
} catch {
  Fail "Could not create or enter installation directory '$InstallDir': $($_.Exception.Message)"
}

# --- Deployment configuration -------------------------------------------------
# compose.yaml is a managed deployment file: always refresh it from the
# canonical source so the installer tracks the published release. User
# configuration lives in .env and is never overwritten (see below).
Write-Host 'Downloading deployment configuration...'
try {
  Invoke-WebRequest -Uri $ComposeUrl -OutFile (Join-Path $InstallDir 'compose.yaml') -UseBasicParsing
} catch {
  Fail "Could not download compose.yaml from '$ComposeUrl'. Check your network connection and try again."
}
Write-Host "Saved compose.yaml to '$InstallDir\compose.yaml'."

# --- .env handling --------------------------------------------------------------
# Parsed as raw lines so existing content (order, comments) is preserved;
# missing keys are appended. Written UTF-8 without BOM so Compose parses it.
$EnvFile = Join-Path $InstallDir '.env'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Read-EnvLines {
  if (Test-Path -LiteralPath $EnvFile) {
    return @([System.IO.File]::ReadAllLines($EnvFile, [System.Text.Encoding]::UTF8))
  }
  return @()
}

function Test-EnvKey([string[]]$Lines, [string]$Key) {
  foreach ($Line in $Lines) {
    if ($Line -match "^$Key=.+") { return $true }
  }
  return $false
}

function Write-EnvLines([string[]]$Lines) {
  [System.IO.File]::WriteAllLines($EnvFile, $Lines, $Utf8NoBom)
}

$EnvLines = Read-EnvLines
if (Test-Path -LiteralPath $EnvFile) {
  Write-Host "Found existing '$EnvFile': preserving configured secrets."
} else {
  Write-EnvLines @()
  Write-Host "Created '$EnvFile'."
  $EnvLines = @()
}

function Add-EnvValue([string]$Key, [string]$Value) {
  $Lines = @(Read-EnvLines)
  if ($Lines.Count -gt 0 -and $Lines[-1] -ne '') { $Lines += '' }
  $Lines += "$Key=$Value"
  Write-EnvLines $Lines
}

# --- Secure random generation -----------------------------------------------------
# RNGCryptoServiceProvider works on both Windows PowerShell 5.1 and
# PowerShell 7+. Weak sources (Get-Random, timestamps) are never used.
function New-RandomBytes([int]$Count) {
  $Bytes = New-Object byte[] $Count
  $Rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
  try {
    $Rng.GetBytes($Bytes)
  } finally {
    $Rng.Dispose()
  }
  return $Bytes
}

function New-DbPassword {
  $Hex = (New-RandomBytes 24 | ForEach-Object { $_.ToString('x2') }) -join ''
  return $Hex
}

function New-JwtSecret {
  return [System.Convert]::ToBase64String((New-RandomBytes 32))
}

# --- Database password ------------------------------------------------------------
if (Test-EnvKey $EnvLines 'SAMVAAD_DB_PASSWORD') {
  Write-Host 'Reusing existing database password from .env.'
} else {
  Add-EnvValue 'SAMVAAD_DB_PASSWORD' (New-DbPassword)
  Write-Host 'Generated database password and stored it in .env.'
}

# --- JWT secret -----------------------------------------------------------------------
$EnvLines = Read-EnvLines
if (Test-EnvKey $EnvLines 'SAMVAAD_JWT_SECRET') {
  Write-Host 'Reusing existing JWT secret from .env.'
} else {
  Write-Host ''
  Write-Host 'JWT secret configuration'
  Write-Host ''
  Write-Host '  1) Generate a secure random secret automatically'
  Write-Host '  2) Enter my own secret'
  Write-Host ''
  $Choice = Read-Host 'Select [1/2] (default 1)'
  if ([string]::IsNullOrWhiteSpace($Choice)) { $Choice = '1' }
  switch ($Choice.Trim()) {
    '1' {
      Add-EnvValue 'SAMVAAD_JWT_SECRET' (New-JwtSecret)
      Write-Host 'Generated JWT secret and stored it in .env.'
    }
    '2' {
      $Secure = Read-Host 'Enter your JWT secret (input hidden)' -AsSecureString
      $Ptr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
      try {
        $Plain = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($Ptr)
      } finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($Ptr)
      }
      if ([string]::IsNullOrEmpty($Plain)) {
        Fail 'The JWT secret must not be empty. Run the installer again and provide a secret or choose automatic generation.'
      }
      Add-EnvValue 'SAMVAAD_JWT_SECRET' $Plain
      $Plain = $null
      Write-Host 'Stored your JWT secret in .env.'
    }
    default {
      Fail "Invalid selection '$Choice'. Run the installer again and select 1 or 2."
    }
  }
}

# --- Start the deployment ---------------------------------------------------------------
# Consumes the published versioned image through Compose; nothing is built.
Write-Host ''
Write-Host 'Starting Samvaad...'
docker compose -f compose.yaml up -d
if ($LASTEXITCODE -ne 0) {
  Fail "'docker compose up -d' failed. Run 'cd $InstallDir; docker compose ps' and 'docker compose logs --tail=50' to inspect."
}

# --- Verify startup -----------------------------------------------------------------------
Write-Host 'Waiting for Samvaad to become ready...'
$Elapsed = 0
$Ready = $false
while ($Elapsed -lt $StartupTimeoutSeconds) {
  try {
    $Response = Invoke-WebRequest -Uri $AppUrl -UseBasicParsing -TimeoutSec 5
    if ($Response.StatusCode -eq 200) { $Ready = $true; break }
  } catch {
    # Not ready yet; keep waiting.
  }
  Start-Sleep -Seconds 3
  $Elapsed += 3
}

if (-not $Ready) {
  Write-Error "Samvaad did not respond at $AppUrl within $StartupTimeoutSeconds seconds."
  Write-Host ''
  docker compose -f compose.yaml ps
  Write-Host ''
  Write-Host 'Inspect the failure with:'
  Write-Host "  cd $InstallDir; docker compose logs --tail=50"
  exit 1
}

# --- Success ---------------------------------------------------------------------------------
Write-Host @"

Samvaad installation completed successfully.

Installation directory:
  $InstallDir

Configuration:
  $EnvFile (contains your secrets; do not commit or share it)

Deployment:
  Docker Compose (compose.yaml stays in the installation directory for
  future 'docker compose up -d / down / restart / pull' operations)

Web Admin:
  $AppUrl

Bootstrap administrator (temporary credentials):
  Username: admin
  Password: admin123

IMPORTANT:
  Change the bootstrap administrator password after first login
  (log in as admin, use the account self-service password change).
  The current release does not enforce this automatically; a
  first-time setup wizard is planned for a future release.

Secret management:
  Secrets live only in $EnvFile. To change them later, edit that
  file and restart ('docker compose restart'), noting that changing the
  database password after PostgreSQL was first initialized requires
  updating the password inside the existing database as well.
"@
