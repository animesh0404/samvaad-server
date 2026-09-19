#!/usr/bin/env bash
#
# Build the Samvaad Docker image. Never starts containers.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="samvaad-server:latest"

echo "Building Samvaad Docker image (${IMAGE})..."
if docker build -t "${IMAGE}" "${ROOT}"; then
  echo "Build succeeded: ${IMAGE}"
else
  echo "ERROR: Docker build failed." >&2
  exit 1
fi
