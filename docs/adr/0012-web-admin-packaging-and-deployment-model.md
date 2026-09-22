# ADR 0012: Web admin packaging and deployment model

## Status

Accepted; Angular/Gradle packaging and Docker deployment implemented.

> Extended by [ADR 0015](0015-docker-lifecycle-and-release-image-reference.md),
> which records the implemented development-vs-release image workflow and
> versioned deployment image reference, and by [ADR 0016](0016-installer-and-deployment-credentials.md),
> which records the implemented installer and deployment-credential workflow.

## Context

Samvaad is being developed as a monorepo with the Spring Boot backend under `server/`. The intended deployment should support both a simple standalone JAR installation and a Docker-based deployment, without making Docker a prerequisite for running the application.

The web admin panel should be deployable with the server rather than requiring a separately hosted frontend for the first production-oriented slice.

## Decision

The production web-admin delivery model will package the Angular production build into the Samvaad Spring Boot application artifact.

The intended artifact flow is:

1. Build the Angular web admin into static production assets.
2. Include those assets in the Spring Boot application's static resources during the application build.
3. Produce the normal executable Spring Boot JAR (`bootJar`).
4. Run that JAR with `java -jar ...` to serve the web admin and the existing server APIs/WebSocket endpoint from the same application process and embedded Tomcat.

The exact Gradle/Angular build integration is an implementation detail and is not fixed by this ADR.

The JAR is the primary deployable application artifact and must remain capable of connecting to an externally provisioned PostgreSQL database. Docker is not a runtime requirement for the application itself.

Docker additionally provides the implemented reproducible distribution/deployment path. The repository's `compose.yaml` brings up the Samvaad application container together with PostgreSQL, consuming the published versioned image from Docker Hub; the lifecycle is controlled by `scripts/start.sh`, `scripts/restart.sh`, and `scripts/stop.sh`. Local image builds are separate concerns: `scripts/build.sh` produces the portable `samvaad-server:latest` artifact (plus tarball export), and `compose.dev.yaml` provides the iterative local development loop (`samvaad-server:dev`).

The two supported operational modes are therefore:

- **Standalone JAR:** an operator supplies/configures PostgreSQL and runs the Samvaad executable JAR.
- **Docker Compose:** an operator uses the containerized Samvaad application together with the Compose-managed PostgreSQL instance. The runtime image uses the Alpine-based Temurin 25 JRE; the build uses Docker Buildx/BuildKit; `scripts/build.sh` also exports `server/build/samvaad-server.tar.gz` as a portable image artifact.

Application configuration, including database connection details and other deployment-sensitive values, must be externalized from the JAR. Secrets must not be baked into the artifact. Direct-access TLS configuration and persisted TLS identity are also external to the JAR and are defined by ADR 0017.

## Consequences

- One executable JAR can deliver the admin UI, REST API, and WebSocket transport.
- A separate frontend web server is not required for the initial deployment model.
- Existing PostgreSQL installations remain supported.
- Docker remains valuable for reproducible packaging and convenient database provisioning rather than becoming a hard application dependency.
- The installer/one-line VPS bootstrap mechanism is implemented separately from the application artifact model.

## Relationship to later deployment ADRs

ADR 0013 defines the public deployment-edge TLS boundary. ADR 0017 adds the implemented application-managed TLS mode for direct host/LAN deployments without replacing the JAR-first packaging decision recorded here.

## Explicitly deferred

- Concrete TLS/reverse-proxy/tunnel provider and certificate automation.
- Deployment upgrade policy between published version tags.
- First-time setup wizard and enforced bootstrap-password change.
