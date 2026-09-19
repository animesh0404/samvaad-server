#!/usr/bin/env bash
#
# Build the Samvaad Docker image. Never starts containers.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="samvaad-server:latest"

echo "Building Samvaad Docker image (${IMAGE})..."
# BuildKit/buildx. --load is required: start.sh and Compose expect the
# image in the local image store, and buildx does not load it there by
# default. Never pushed to a registry.
if docker buildx build --load -t "${IMAGE}" "${ROOT}"; then
  echo "Build succeeded: ${IMAGE}"
else
  echo "ERROR: Docker build failed." >&2
  exit 1
fi

# Export a portable copy of the image. The image stays loaded in the local
# image store, so start.sh keeps working exactly as before.
EXPORT_DIR="${ROOT}/server/build"
EXPORT_FILE="${EXPORT_DIR}/samvaad-server.tar.gz"
mkdir -p "${EXPORT_DIR}"
echo "Exporting image to ${EXPORT_FILE}..."
if docker save "${IMAGE}" | gzip > "${EXPORT_FILE}"; then
  echo "Export succeeded: ${EXPORT_FILE}"
else
  echo "ERROR: Docker image export failed." >&2
  exit 1
fi
