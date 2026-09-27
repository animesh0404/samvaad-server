#!/usr/bin/env bash
#
# Build the portable local Samvaad image artifact. Never starts containers,
# is never consumed by any Compose workflow (iterative Docker development
# uses compose.dev.yaml), and is never pushed to a registry.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="samvaad-server:latest"

# GitHub Packages authentication (ADR 0024): the Gradle build inside the
# Dockerfile resolves `com.samvaad:e2ee-client:0.1.0` from GitHub Packages,
# which requires authentication even for reads. Credentials are forwarded
# as BuildKit secrets (never ARG/ENV, never baked into the image):
#   export GITHUB_ACTOR=<your-github-username>
#   export GITHUB_TOKEN=<token-with-read:packages-scope>
if [ -z "${GITHUB_ACTOR:-}" ] || [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "ERROR: GITHUB_ACTOR and GITHUB_TOKEN must be exported in the environment." >&2
  echo "The Dockerfile resolves com.samvaad:e2ee-client:0.1.0 from GitHub Packages," >&2
  echo "which requires authentication even for reads (GITHUB_TOKEN needs" >&2
  echo "'read:packages' scope). Tokens are passed as BuildKit secrets only and" >&2
  echo "are never stored in the image. See docs/development/setup.md." >&2
  exit 1
fi

echo "Building Samvaad Docker image (${IMAGE})..."
# BuildKit/buildx. --load is required: the image must land in the local
# image store so it can be exported below. Never pushed to a registry.
if docker buildx build --load -t "${IMAGE}" \
    --secret id=github_actor,env=GITHUB_ACTOR \
    --secret id=github_token,env=GITHUB_TOKEN \
    "${ROOT}"; then
  echo "Build succeeded: ${IMAGE}"
else
  echo "ERROR: Docker build failed." >&2
  exit 1
fi

# Export a portable copy of the image for offline transfer. The image stays
# loaded in the local image store as well.
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
