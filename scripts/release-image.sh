#!/usr/bin/env bash
#
# Publish a versioned Samvaad release image to Docker Hub.
# Release/distribution workflow (ADR 0015). Never tags or pushes `latest`.
#
# Usage:
#   ./scripts/release-image.sh 0.0.1              # build + push that exact tag
#   ./scripts/release-image.sh --skip-push 0.0.1  # build + tag only, no push
#   ./scripts/release-image.sh --dry-run 0.0.1    # validate only, no build/push
#
# The release version is always an explicit argument; it is never derived,
# defaulted, or invented. Docker Hub authentication comes from your existing
# `docker login` session: this script stores no credentials. If you are not
# logged in, `docker push` fails with the registry's own error.
set -euo pipefail

REGISTRY="animesh0404/samvaad-server"
SKIP_PUSH=0
DRY_RUN=0
VERSION=""

usage() {
  echo "Usage: release-image.sh [--skip-push] [--dry-run] <version>"
  echo "Example: release-image.sh 0.0.1"
}

for arg in "$@"; do
  case "${arg}" in
    --skip-push)
      SKIP_PUSH=1
      ;;
    --dry-run)
      DRY_RUN=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "ERROR: unknown option '${arg}'." >&2
      usage >&2
      exit 1
      ;;
    *)
      if [ -z "${VERSION}" ]; then
        VERSION="${arg}"
      else
        echo "ERROR: unexpected extra argument '${arg}'." >&2
        usage >&2
        exit 1
      fi
      ;;
  esac
done

if [ -z "${VERSION}" ]; then
  echo "ERROR: a release version argument is required." >&2
  usage >&2
  exit 1
fi

# Docker tag rule: starts with a word character, then word characters, dots,
# or dashes, max 128 characters. Never `latest` for a release.
if ! [[ "${VERSION}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
  echo "ERROR: version '${VERSION}' is not suitable for a Docker tag." >&2
  exit 1
fi
if [ "${VERSION}" = "latest" ]; then
  echo "ERROR: 'latest' must not be used as a release version (ADR 0015)." >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker is not installed or not on PATH." >&2
  exit 1
fi

IMAGE="${REGISTRY}:${VERSION}"

if [ "${DRY_RUN}" -eq 1 ]; then
  echo "Release image reference valid: ${IMAGE} (dry run: nothing built or pushed)."
  exit 0
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "Building Samvaad release image (${IMAGE})..."
# BuildKit/buildx, mirroring scripts/build.sh. --load keeps the tagged image
# in the local image store so the tag can be inspected before pushing.
if docker buildx build --load -t "${IMAGE}" "${ROOT}"; then
  echo "Build succeeded: ${IMAGE}"
else
  echo "ERROR: Docker build failed; nothing was pushed." >&2
  exit 1
fi

if [ "${SKIP_PUSH}" -eq 1 ]; then
  echo "Skipping push (--skip-push): image available locally as ${IMAGE}."
  exit 0
fi

echo "Pushing release image (${IMAGE})..."
echo "Using your existing Docker Hub authentication (run 'docker login' first if needed)."
if docker push "${IMAGE}"; then
  echo "Push succeeded: ${IMAGE}"
else
  echo "ERROR: Docker push failed (check 'docker login' and network access)." >&2
  exit 1
fi
