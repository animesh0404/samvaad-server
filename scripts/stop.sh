#!/usr/bin/env bash
#
# Stop the Samvaad Docker Compose stack. Builds nothing, touches no config.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "Stopping Samvaad..."
docker compose -f "${ROOT}/compose.yaml" down
echo "Samvaad stopped."
