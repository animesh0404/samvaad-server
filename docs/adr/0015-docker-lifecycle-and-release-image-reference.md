# ADR 0015: Docker development vs release workflows and deployment image reference

## Status

Accepted. Extends ADR 0012; does not supersede it. Implemented:
`scripts/release-image.sh` is the release/publish mechanism and the root
`compose.yaml` consumes the published versioned image. Extended by
ADR 0016 (one-command installer, env-supplied database credentials).

## Context

ADR 0012 locked the packaging/deployment model: one Spring Boot executable
JAR (Angular assets included) as the primary artifact, runnable standalone
against externally provisioned PostgreSQL, with Docker Compose as the
reproducible distribution/deployment path (`compose.yaml` running the
application plus Compose-managed PostgreSQL; lifecycle via
`scripts/build.sh`, `start.sh`, `restart.sh`, `stop.sh`).

The current implementation builds and runs a local-only image
(`samvaad-server:latest`, `pull_policy: never` in the root `compose.yaml`),
so deployment today requires a local build. The deferred ADR 0012 items
— release publication mechanism and one-command VPS installer/update
workflow — need a locked direction before they are implemented: which image
reference the deployment Compose file should consume, how versioning works,
and how the developer-local workflow relates to published releases.

## Decision

Samvaad maintains two distinct Docker workflows:

1. **Development / local workflow.** `scripts/build.sh` remains a
   developer-focused local image build producing the local-only image
   `samvaad-server:latest` for development, testing, and local deployment.
   It stays independent of any registry publication. `server/compose.yaml`
   remains the development PostgreSQL-only Compose stack and is not removed.

2. **Release / distribution workflow.** Published Samvaad Docker images are
   separate release artifacts. A future dedicated release/publish mechanism
   — separate from the normal developer `scripts/build.sh` workflow — will
   build and publish versioned images such as
   `animesh0404/samvaad-server:0.0.1` to Docker Hub.

3. **Root deployment Compose.** The root `compose.yaml` is the
   end-user/deployment Compose definition (Samvaad + PostgreSQL). Its
   application service will eventually consume a published, versioned Docker
   image from Docker Hub (for example,
   `animesh0404/samvaad-server:0.0.1`) rather than the developer-local
   `samvaad-server:latest`. The deployment model is `docker compose up -d`:
   Compose obtains missing images, creates the network, starts PostgreSQL,
   waits for its health check, and starts Samvaad. (`docker pull` only
   downloads an image; it does not start PostgreSQL or any container.)

4. **Versioning principle.** Release/deployment image references must use
   explicit version tags corresponding to the Samvaad release (`v0.0.1` →
   `animesh0404/samvaad-server:0.0.1`), providing reproducible deployment
   references. `latest` must not be used as the release/deployment image
   reference.

5. **Two Compose files are kept.** `/compose.yaml` (deployment: Samvaad +
   PostgreSQL) and `/server/compose.yaml` (development: PostgreSQL only)
   represent different environments and are not merged merely to simplify
   the Docker workflow.

## Consequences

- Local development keeps working exactly as today with no registry
  dependency; publishing outages or credential issues never block
  development builds.
- End-user deployment becomes build-free: `docker compose up -d` pulls the
  pinned release image plus `postgres:18` and converges the stack.
- Version tags make deployments reproducible and rollbacks expressible as a
  tag change.
- Two image references (`samvaad-server:latest` local vs
  `animesh0404/samvaad-server:<version>` published) must be kept visibly
  distinct in scripts and docs to avoid pulling when a build was meant or
  building when a pull was meant.

## Explicitly deferred

- Upgrade policy for moving a deployment from one version tag to the next.

## Source material

- `docs/adr/0012-web-admin-packaging-and-deployment-model.md`
- `compose.yaml`
- `server/compose.yaml`
- `scripts/build.sh`
- `scripts/start.sh`
