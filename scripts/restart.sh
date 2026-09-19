#!/usr/bin/env bash
#
# Restart the existing Samvaad Docker Compose stack. Builds nothing and
# leaves the JWT secret and all other configuration untouched.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "Restarting Samvaad..."
docker compose -f "${ROOT}/compose.yaml" down
docker compose -f "${ROOT}/compose.yaml" up -d
echo "Samvaad restarted."
