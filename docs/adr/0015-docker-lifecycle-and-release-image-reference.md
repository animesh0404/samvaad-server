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

At the time this ADR was created, the implementation built and ran a local-only image
(`samvaad-server:latest`, `pull_policy: never` in the root `compose.yaml`),
so deployment required a local build. This ADR records the transition from that
model to the now-implemented published, versioned release-image workflow and
root deployment Compose reference.

## Decision

Samvaad maintains distinct Docker workflows (see also ADR 0016 for the
installer and deployment credentials):

1. **Backend development.** `server/compose.yaml` remains the development
   PostgreSQL-only Compose stack, used with `./gradlew bootRun` /
   `bootTestRun`. It is not removed.

2. **Full Docker development.** `compose.dev.yaml` overrides the root
   `compose.yaml` topology to build and run the application from local
   source as the local-only image `samvaad-server:dev` (isolated
   `samvaad-dev` project and `samvaad-dev-data` volume). This is the
   iterative Compose development loop and is never published.

3. **Portable local image artifact.** `scripts/build.sh` remains a
   local-only image build producing `samvaad-server:latest` plus the
   `server/build/samvaad-server.tar.gz` export for offline/portable use.
   It stays independent of any registry publication and is not consumed
   by any Compose workflow.

4. **Release / distribution workflow.** Published Samvaad Docker images are
   separate release artifacts. The dedicated `scripts/release-image.sh`
   workflow — separate from the normal developer `scripts/build.sh` workflow
   — builds and publishes versioned images such as
   `animesh0404/samvaad-server:0.0.1` to Docker Hub.

5. **Root deployment Compose.** The root `compose.yaml` is the
   end-user/deployment Compose definition (Samvaad + PostgreSQL). Its
   application service consumes the published, versioned Docker image from
   Docker Hub (for example, `animesh0404/samvaad-server:0.0.1`) rather than
   the developer-local `samvaad-server:latest`. The deployment model is
   `docker compose up -d`: Compose obtains missing images, creates the network,
   starts PostgreSQL, waits for its health check, and starts Samvaad.
   (`docker pull` only downloads an image; it does not start PostgreSQL or any
   container.)

6. **Versioning principle.** Release/deployment image references must use
   explicit version tags corresponding to the Samvaad release (`v0.0.1` →
   `animesh0404/samvaad-server:0.0.1`), providing reproducible deployment
   references. `latest` must not be used as the release/deployment image
   reference.

7. **Compose files are kept separate by environment.** `/compose.yaml`
   (deployment: Samvaad + PostgreSQL), `/server/compose.yaml`
   (backend development: PostgreSQL only), and `/compose.dev.yaml`
   (full Docker development override) represent different environments
   and are not merged merely to simplify the Docker workflow.

## Consequences

- Local development keeps working exactly as today with no registry
  dependency; publishing outages or credential issues never block
  development builds.
- End-user deployment becomes build-free: `docker compose up -d` pulls the
  pinned release image plus `postgres:18` and converges the stack.
- Version tags make deployments reproducible and rollbacks expressible as a
  tag change.
- Three local image references are kept visibly distinct in scripts and
  docs (`samvaad-server:dev` for the iterative Compose loop,
  `samvaad-server:latest` for the portable artifact) from the published
  references (`animesh0404/samvaad-server:<version>`), to avoid pulling
  when a build was meant or building when a pull was meant.

## Explicitly deferred

- Upgrade policy for moving a deployment from one version tag to the next.

## Source material

- `docs/adr/0012-web-admin-packaging-and-deployment-model.md`
- `compose.yaml`
- `compose.dev.yaml`
- `server/compose.yaml`
- `scripts/build.sh`
- `scripts/start.sh`
