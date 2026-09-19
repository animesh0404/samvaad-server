#!/usr/bin/env bash
#
# Start the already-built Samvaad image with Docker Compose.
# Never builds. Reuses the existing JWT secret; only creates one when none
# is configured.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="samvaad-server:latest"
ENV_FILE="${ROOT}/.env"

if ! docker image inspect "${IMAGE}" >/dev/null 2>&1; then
  echo "ERROR: Docker image '${IMAGE}' does not exist." >&2
  echo "Build it first with ./scripts/build.sh" >&2
  exit 1
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
  if [ -f "${ENV_FILE}" ] && [ -n "$(tail -c 1 "${ENV_FILE}" 2>/dev/null || true)" ]; then
    printf '\n' >> "${ENV_FILE}"
  fi
  printf 'SAMVAAD_JWT_SECRET=%s\n' "${secret}" >> "${ENV_FILE}"
  chmod 600 "${ENV_FILE}"
  echo "JWT secret stored in .env."
fi

echo "Starting Samvaad..."
docker compose -f "${ROOT}/compose.yaml" up -d
echo "Samvaad started."
