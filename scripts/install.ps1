<#
.SYNOPSIS
  Samvaad one-command installer (Windows PowerShell).

.DESCRIPTION
  Installs the published Samvaad release into $HOME\.samvaad without
  requiring a repository checkout. The application serves HTTPS only
  (self-signed certificate, ADR 0017).

    irm https://raw.githubusercontent.com/animesh0404/samvaad-server/main/scripts/install.ps1 | iex

  Docker required            (never installed by this script)
      |
  Creates $HOME\.samvaad
      |
  Downloads compose.yaml from the Samvaad GitHub repository
      |
  Creates/reuses .env (generates secrets, asks about the
  JWT secret, preserves existing secrets on re-runs)
      |
  Ensures application.yaml (populated by the application on first boot)
      |
  docker compose up -d (published versioned image, never built locally)
      |
  Verifies Samvaad responds at https://localhost:8080

  Installations created by older installers under C:\Samvaad are migrated
  deliberately (compose.yaml and .env move over; nothing is overwritten).

  Idempotent: re-running refreshes compose.yaml, keeps existing secrets,
  and converges the deployment. Secrets are never printed.

  Responsibility boundary (ADR 0015/0016): bootstrap layer only. Release
  publication stays in scripts/release-image.sh, the portable local image
  artifact stays in scripts/build.sh, iterative Docker development uses
  compose.dev.yaml, day-to-day lifecycle stays in
  scripts/start.sh, scripts/restart.sh, and scripts/stop.sh.
#>

$ErrorActionPreference = 'Stop'

$InstallDir   = Join-Path $HOME '.samvaad'
$LegacyDir    = 'C:\Samvaad'
$ComposeUrl   = 'https://raw.githubusercontent.com/animesh0404/samvaad-server/main/compose.yaml'
$AppUrl       = 'https://localhost:8080/'
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

# --- Legacy location migration --------------------------------------------------
# Older installers used C:\Samvaad. Move deployment files over exactly once:
# only files missing in the new location move, existing state is never
# overwritten, and the old directory is left otherwise untouched.
if ((Test-Path -LiteralPath $LegacyDir) -and ($LegacyDir -ne $InstallDir)) {
  foreach ($Name in @('compose.yaml', '.env')) {
    $From = Join-Path $LegacyDir $Name
    $To = Join-Path $InstallDir $Name
    if ((Test-Path -LiteralPath $From) -and -not (Test-Path -LiteralPath $To)) {
      Move-Item -LiteralPath $From -Destination $To
      Write-Host "Migrated $Name from $LegacyDir (old location is no longer used)."
    }
  }
  Write-Host "Note: $LegacyDir remains on disk (including any files that were already present there)."
  Write-Host 'Remove it manually after verifying the migration.'
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

function New-HexSecret {
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
  Add-EnvValue 'SAMVAAD_DB_PASSWORD' (New-HexSecret)
  Write-Host 'Generated database password and stored it in .env.'
}

# --- TLS keystore password ----------------------------------------------------------
# Same reuse-or-generate contract as the database password. Never printed.
$EnvLines = Read-EnvLines
if (Test-EnvKey $EnvLines 'SAMVAAD_TLS_KEYSTORE_PASSWORD') {
  Write-Host 'Reusing existing TLS keystore password from .env.'
} else {
  Add-EnvValue 'SAMVAAD_TLS_KEYSTORE_PASSWORD' (New-HexSecret)
  Write-Host 'Generated TLS keystore password and stored it in .env.'
}

# --- External operator configuration --------------------------------------------------
# The Compose stack bind-mounts .\application.yaml into the container. Ensure
# the file exists (empty is fine: the application populates defaults on
# first boot and never modifies existing content). Never write secrets here.
$AppConfigFile = Join-Path $InstallDir 'application.yaml'
if (-not (Test-Path -LiteralPath $AppConfigFile)) {
  [System.IO.File]::WriteAllText($AppConfigFile, '', $Utf8NoBom)
  Write-Host 'Created empty application.yaml (populated by the application on first boot).'
} else {
  Write-Host 'Found existing application.yaml: preserving operator configuration.'
}
# TLS state directory (used directly only by standalone runs; Docker keeps
# TLS state in the dedicated samvaad-tls volume).
New-Item -ItemType Directory -Path (Join-Path $InstallDir 'tls') -Force | Out-Null

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
  # Non-interactive use (redirected stdin, remote execution): Read-Host would
  # fail, so fall back to automatic generation like the Unix installer.
  $Choice = '1'
  try {
    if (-not [Console]::IsInputRedirected) {
      $Choice = Read-Host 'Select [1/2] (default 1)'
    } else {
      Write-Host 'No interactive input available: generating the JWT secret automatically.'
    }
  } catch {
    Write-Host 'No interactive input available: generating the JWT secret automatically.'
    $Choice = '1'
  }
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
# Self-signed HTTPS: certificate verification is bypassed for this local
# readiness probe only (PowerShell 6+ via -SkipCertificateCheck, Windows
# PowerShell 5.1 via an HttpClient handler). It does not change browser
# trust.
function Test-AppReady {
  if ($PSVersionTable.PSVersion.Major -ge 6) {
    try {
      return (Invoke-WebRequest -Uri $AppUrl -UseBasicParsing -TimeoutSec 5 -SkipCertificateCheck).StatusCode -eq 200
    } catch {
      return $false
    }
  }
  $Handler = $null
  $Client = $null
  try {
    Add-Type -AssemblyName System.Net.Http
    $Handler = New-Object System.Net.Http.HttpClientHandler
    $Handler.ServerCertificateCustomValidationCallback = { $true }
    $Client = New-Object System.Net.Http.HttpClient($Handler)
    $Client.Timeout = [TimeSpan]::FromSeconds(5)
    return $Client.GetAsync($AppUrl).Result.IsSuccessStatusCode
  } catch {
    return $false
  } finally {
    if ($Client) { $Client.Dispose() }
    if ($Handler) { $Handler.Dispose() }
  }
}

Write-Host 'Waiting for Samvaad to become ready...'
$Elapsed = 0
$Ready = $false
while ($Elapsed -lt $StartupTimeoutSeconds) {
  if (Test-AppReady) { $Ready = $true; break }
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
# Best-effort fingerprint display: the application logs its public TLS
# certificate fingerprint at startup (never secrets). Absence here is not
# fatal; the fingerprint remains in the application logs.
$Fingerprint = docker compose -f compose.yaml logs --no-log-prefix app 2>$null `
  | Select-String -Pattern 'SHA256(:[0-9A-F]{2}){32}' -AllMatches `
  | Select-Object -ExpandProperty Matches -Last 1
if ($Fingerprint) { $Fingerprint = $Fingerprint.Value }
Write-Host @"

Samvaad installation completed successfully.

Installation directory:
  $InstallDir

Configuration:
  $EnvFile (contains your secrets; do not commit or share it)
  $(Join-Path $InstallDir 'application.yaml') (operator configuration, preserved across runs)

Deployment:
  Docker Compose (compose.yaml stays in the installation directory for
  future 'docker compose up -d / down / restart / pull' operations)

Web Admin (HTTPS, self-signed certificate):
  $AppUrl
$(if ($Fingerprint) { "`nCertificate fingerprint (public; verify on first browser connect):`n  $Fingerprint`n" })
Your browser will warn about the self-signed certificate. This is expected
for direct deployments: traffic is still encrypted. A trusted public
deployment terminates TLS at a reverse proxy instead (see documentation).

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
  updating the password inside the existing database as well. Changing
  SAMVAAD_TLS_KEYSTORE_PASSWORD after the keystore was generated makes
  the existing keystore unreadable: either restore the original password
  or delete the keystore for explicit regeneration (tls\keystore.p12 in
  the install dir, or the samvaad-tls volume).
"@
