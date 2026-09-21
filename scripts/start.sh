#!/usr/bin/env bash
#
# Start the Samvaad deployment stack with Docker Compose.
# Deploys the published versioned image referenced by the root compose.yaml;
# Compose pulls it from Docker Hub when it is not available locally, so no
# local image build is required. Never builds or publishes. Reuses the
# existing JWT secret; only creates one when none is configured.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT}/.env"

# Append a KEY=VALUE line to .env, ensuring the file stays newline-terminated
# and readable only by the owner (it holds secrets).
append_env_var() {
  if [ -f "${ENV_FILE}" ] && [ -n "$(tail -c 1 "${ENV_FILE}" 2>/dev/null || true)" ]; then
    printf '\n' >> "${ENV_FILE}"
  fi
  printf '%s=%s\n' "${1}" "${2}" >> "${ENV_FILE}"
  chmod 600 "${ENV_FILE}"
}

# Deployment database password (ADR 0016): the Compose stack requires
# SAMVAAD_DB_PASSWORD so PostgreSQL and the application share the same
# secret. Reuse the configured value; generate one silently when none is
# configured (opaque credential, no user choice required). Never printed.
existing_db=""
if [ -f "${ENV_FILE}" ]; then
  existing_db="$(grep -E '^SAMVAAD_DB_PASSWORD=..+' "${ENV_FILE}" || true)"
fi

if [ -n "${existing_db}" ]; then
  echo "Reusing existing database password from .env."
else
  if ! command -v openssl >/dev/null 2>&1; then
    echo "ERROR: openssl is required to generate the database password." >&2
    exit 1
  fi
  append_env_var "SAMVAAD_DB_PASSWORD" "$(openssl rand -hex 24)"
  echo "Database password generated and stored in .env."
fi

existing=""
if [ -f "${ENV_FILE}" ]; then
  existing="$(grep -E '^SAMVAAD_JWT_SECRET=..+' "${ENV_FILE}" || true)"
fi

if [ -n "${existing}" ]; then
  echo "Reusing existing JWT secret from .env."
else
  printf '%s' "Enter your JWT secret, or press Enter to generate one automatically: "
  entered=""
  IFS= read -rs entered || true
  echo
  secret="${entered}"
  if [ -z "${secret}" ]; then
    if ! command -v openssl >/dev/null 2>&1; then
      echo "ERROR: openssl is required to generate a JWT secret." >&2
      exit 1
    fi
    secret="$(openssl rand -base64 32)"
  fi
  append_env_var "SAMVAAD_JWT_SECRET" "${secret}"
  echo "JWT secret stored in .env."
fi

echo "Starting Samvaad..."
docker compose -f "${ROOT}/compose.yaml" up -d
echo "Samvaad started."
