#!/usr/bin/env bash
#
# Samvaad one-command installer (Unix/Linux/macOS).
#
# Installs the published Samvaad release into ~/.samvaad without requiring a
# repository checkout:
#
#   Docker required           (never installed by this script)
#       |
#   Creates ~/.samvaad
#       |
#   Downloads compose.yaml from the Samvaad GitHub repository
#       |
#   Creates/reuses .env (generates secrets, asks about the
#   JWT secret, preserves existing secrets on re-runs)
#       |
#   Ensures application.yaml (populated by the application on first boot)
#       |
#   docker compose up -d (published versioned image, never built locally)
#       |
#   Verifies Samvaad responds at https://localhost:8080
#
# The application serves HTTPS only (self-signed certificate, ADR 0017).
#
# Installations created by older installers under ~/Samvaad are migrated
# deliberately (compose.yaml and .env move over; nothing is overwritten).
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/animesh0404/samvaad-server/main/scripts/install.sh | bash
#
# The script is idempotent: re-running it refreshes compose.yaml from the
# canonical source, keeps existing secrets in .env, and converges the
# deployment. Secrets are never printed.
#
# Responsibility boundary (ADR 0015/0016): this is a bootstrap layer only.
# Release publication stays in scripts/release-image.sh, the portable local
# image artifact stays in scripts/build.sh, iterative Docker development
# uses compose.dev.yaml, and day-to-day lifecycle stays in
# scripts/start.sh, scripts/restart.sh, and scripts/stop.sh.
set -euo pipefail

INSTALL_DIR="${HOME}/.samvaad"
LEGACY_DIR="${HOME}/Samvaad"
COMPOSE_URL="https://raw.githubusercontent.com/animesh0404/samvaad-server/main/compose.yaml"
APP_URL="https://localhost:8080/"
STARTUP_TIMEOUT_SECONDS=120

fail() {
  echo "ERROR: ${1}" >&2
  exit 1
}

# --- Prerequisite checks ----------------------------------------------------
# Docker itself is never installed here; the user must install it first.
command -v docker >/dev/null 2>&1 \
  || fail "Docker is not installed. Install Docker (https://docs.docker.com/get-docker/) and run this installer again."

docker info >/dev/null 2>&1 \
  || fail "Docker is installed but the daemon is not running. Start Docker (e.g. open Docker Desktop) and run this installer again."

docker compose version >/dev/null 2>&1 \
  || fail "Docker Compose is not available. Update Docker to a version that includes 'docker compose' and run this installer again."

command -v curl >/dev/null 2>&1 \
  || fail "curl is required to download the deployment configuration."

# --- Installation directory -------------------------------------------------
mkdir -p "${INSTALL_DIR}" \
  || fail "Could not create installation directory '${INSTALL_DIR}'."
cd "${INSTALL_DIR}" \
  || fail "Could not enter installation directory '${INSTALL_DIR}'."

# --- Legacy location migration --------------------------------------------------
# Older installers used ~/Samvaad. Move deployment files over exactly once:
# only files missing in the new location move, existing state is never
# overwritten, and the old directory is left otherwise untouched.
if [ -d "${LEGACY_DIR}" ] && [ "${LEGACY_DIR}" != "${INSTALL_DIR}" ]; then
  for legacy_file in compose.yaml .env; do
    if [ -f "${LEGACY_DIR}/${legacy_file}" ] && [ ! -f "${INSTALL_DIR}/${legacy_file}" ]; then
      mv "${LEGACY_DIR}/${legacy_file}" "${INSTALL_DIR}/${legacy_file}" \
        && echo "Migrated ${legacy_file} from ${LEGACY_DIR} (old location is no longer used)."
    fi
  done
  echo "Note: ${LEGACY_DIR} remains on disk (including any files that were already present here)."
  echo "Remove it manually after verifying the migration."
fi

# --- Deployment configuration -----------------------------------------------
# compose.yaml is a managed deployment file: always refresh it from the
# canonical source so the installer tracks the published release. User
# configuration lives in .env and is never overwritten (see below).
echo "Downloading deployment configuration..."
curl -fsSL "${COMPOSE_URL}" -o compose.yaml \
  || fail "Could not download compose.yaml from '${COMPOSE_URL}'. Check your network connection and try again."
echo "Saved compose.yaml to '${INSTALL_DIR}/compose.yaml'."

# --- .env handling ----------------------------------------------------------
ENV_FILE="${INSTALL_DIR}/.env"
if [ ! -f "${ENV_FILE}" ]; then
  ( umask 077 && : > "${ENV_FILE}" ) \
    || fail "Could not create '${ENV_FILE}'."
  chmod 600 "${ENV_FILE}" 2>/dev/null || true
  echo "Created '${ENV_FILE}'."
else
  echo "Found existing '${ENV_FILE}': preserving configured secrets."
fi

env_has_key() {
  grep -qE "^${1}=.+" "${ENV_FILE}" 2>/dev/null
}

env_append() {
  if [ -n "$(tail -c 1 "${ENV_FILE}" 2>/dev/null || true)" ]; then
    printf '\n' >> "${ENV_FILE}"
  fi
  printf '%s=%s\n' "${1}" "${2}" >> "${ENV_FILE}"
  chmod 600 "${ENV_FILE}" 2>/dev/null || true
}

# --- Secure random generation ------------------------------------------------
# OpenSSL preferred; /dev/urandom fallback; hard failure otherwise. Weak
# sources ($RANDOM, timestamps) are never used for secrets.
generate_hex() {
  local bytes="${1}"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "${bytes}"
  elif [ -r /dev/urandom ] && command -v od >/dev/null 2>&1; then
    head -c "${bytes}" /dev/urandom | od -An -tx1 | tr -d ' \n'
  else
    return 1
  fi
}

generate_base64() {
  local bytes="${1}"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 "${bytes}" | tr -d '\n'
    printf '\n'
  elif [ -r /dev/urandom ] && command -v base64 >/dev/null 2>&1; then
    head -c "${bytes}" /dev/urandom | base64 | tr -d '\n'
    printf '\n'
  else
    return 1
  fi
}

# --- Database password -------------------------------------------------------
if env_has_key "SAMVAAD_DB_PASSWORD"; then
  echo "Reusing existing database password from .env."
else
  db_password=""
  db_password="$(generate_hex 24)" \
    || fail "No secure random generator available (need openssl or /dev/urandom). Install openssl and run this installer again."
  env_append "SAMVAAD_DB_PASSWORD" "${db_password}"
  db_password=""
  echo "Generated database password and stored it in .env."
fi

# --- TLS keystore password -------------------------------------------------------
# Same reuse-or-generate contract as the database password. Never printed.
if env_has_key "SAMVAAD_TLS_KEYSTORE_PASSWORD"; then
  echo "Reusing existing TLS keystore password from .env."
else
  tls_password=""
  tls_password="$(generate_hex 24)" \
    || fail "No secure random generator available (need openssl or /dev/urandom). Install openssl and run this installer again."
  env_append "SAMVAAD_TLS_KEYSTORE_PASSWORD" "${tls_password}"
  tls_password=""
  echo "Generated TLS keystore password and stored it in .env."
fi

# --- External operator configuration ----------------------------------------------
# The Compose stack bind-mounts ./application.yaml into the container. Ensure
# the file exists (empty is fine: the application populates defaults on
# first boot and never modifies existing content). Never write secrets here.
if [ ! -f "application.yaml" ]; then
  : > "application.yaml"
  chmod 600 "application.yaml" 2>/dev/null || true
  echo "Created empty application.yaml (populated by the application on first boot)."
else
  echo "Found existing application.yaml: preserving operator configuration."
fi
# TLS state directory (used directly only by standalone runs; Docker keeps
# TLS state in the dedicated samvaad-tls volume).
mkdir -p tls \
  || fail "Could not create TLS state directory 'tls'."

# --- JWT secret --------------------------------------------------------------
# Prompts go to /dev/tty so they work when the installer itself is piped in
# via 'curl ... | bash' (stdin is the script, not the terminal). Without a
# terminal, fall back to automatic generation so scripted runs still work.
tty_read() {
  IFS= read -r "${1}" < /dev/tty
}

if env_has_key "SAMVAAD_JWT_SECRET"; then
  echo "Reusing existing JWT secret from .env."
else
  echo ""
  echo "JWT secret configuration"
  echo ""
  echo "  1) Generate a secure random secret automatically"
  echo "  2) Enter my own secret"
  echo ""
  choice=""
  if [ -r /dev/tty ] && [ -w /dev/tty ]; then
    printf 'Select [1/2] (default 1): ' > /dev/tty
    tty_read choice || choice=""
  else
    echo "No interactive terminal detected: generating the JWT secret automatically."
    choice="1"
  fi
  choice="$(printf '%s' "${choice}" | tr -d '[:space:]')"
  case "${choice}" in
    ""|"1")
      jwt_secret=""      jwt_secret="$(generate_base64 32)" \
        || fail "No secure random generator available (need openssl or /dev/urandom). Install openssl and run this installer again."
      env_append "SAMVAAD_JWT_SECRET" "${jwt_secret}"
      jwt_secret=""
      echo "Generated JWT secret and stored it in .env."
      ;;
    "2")
      jwt_secret=""
      if [ -r /dev/tty ] && [ -w /dev/tty ]; then
        printf 'Enter your JWT secret (input hidden): ' > /dev/tty
        IFS= read -rs jwt_secret < /dev/tty || jwt_secret=""
        printf '\n' > /dev/tty
      else
        fail "Cannot read a manual secret without an interactive terminal. Re-run in a terminal or allow automatic generation."
      fi
      [ -n "${jwt_secret}" ] \
        || fail "The JWT secret must not be empty. Run the installer again and provide a secret or choose automatic generation."
      env_append "SAMVAAD_JWT_SECRET" "${jwt_secret}"
      jwt_secret=""
      echo "Stored your JWT secret in .env."
      ;;
    *)
      fail "Invalid selection '${choice}'. Run the installer again and select 1 or 2."
      ;;
  esac
fi

# --- Start the deployment -----------------------------------------------------
# Consumes the published versioned image through Compose; nothing is built.
echo ""
echo "Starting Samvaad..."
docker compose -f compose.yaml up -d \
  || fail "'docker compose up -d' failed. Run 'cd \"${INSTALL_DIR}\" && docker compose ps' and 'docker compose logs --tail=50' to inspect."

# --- Verify startup -----------------------------------------------------------
# Self-signed HTTPS: -k accepts the application-generated certificate for
# this local readiness probe only. It does not change browser trust.
echo "Waiting for Samvaad to become ready..."
elapsed=0
ready=0
while [ "${elapsed}" -lt "${STARTUP_TIMEOUT_SECONDS}" ]; do
  if curl -kfSs -o /dev/null --max-time 5 "${APP_URL}" 2>/dev/null; then
    ready=1
    break
  fi
  sleep 3
  elapsed=$((elapsed + 3))
done

if [ "${ready}" -ne 1 ]; then
  echo "ERROR: Samvaad did not respond at ${APP_URL} within ${STARTUP_TIMEOUT_SECONDS}s." >&2
  echo "" >&2
  docker compose -f compose.yaml ps >&2 || true
  echo "" >&2
  echo "Inspect the failure with:" >&2
  echo "  cd \"${INSTALL_DIR}\" && docker compose logs --tail=50" >&2
  exit 1
fi

# --- Success -------------------------------------------------------------------
# Best-effort fingerprint display: the application logs its public TLS
# certificate fingerprint at startup (never secrets). Absence here is not
# fatal; the fingerprint remains in the application logs.
fingerprint=""
fingerprint="$(docker compose -f compose.yaml logs --no-log-prefix app 2>/dev/null \
  | grep -o 'SHA256\(:[0-9A-F]\{2\}\)\{32\}' | tail -n 1 || true)"

cat <<EOF

Samvaad installation completed successfully.

Installation directory:
  ${INSTALL_DIR}

Configuration:
  ${ENV_FILE} (contains your secrets; do not commit or share it)
  ${INSTALL_DIR}/application.yaml (operator configuration, preserved across runs)

Deployment:
  Docker Compose (compose.yaml stays in the installation directory for
  future 'docker compose up -d / down / restart / pull' operations)

Web Admin (HTTPS, self-signed certificate):
  ${APP_URL}
EOF

if [ -n "${fingerprint}" ]; then
cat <<EOF

Certificate fingerprint (public; verify on first browser connect):
  ${fingerprint}
EOF
fi

cat <<EOF

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
  Secrets live only in ${ENV_FILE}. To change them later, edit that
  file and restart ('docker compose restart'), noting that changing the
  database password after PostgreSQL was first initialized requires
  updating the password inside the existing database as well. Changing
  SAMVAAD_TLS_KEYSTORE_PASSWORD after the keystore was generated makes
  the existing keystore unreadable: either restore the original password
  or delete the keystore for explicit regeneration (tls/keystore.p12 in
  the install dir, or the samvaad-tls volume).
EOF
