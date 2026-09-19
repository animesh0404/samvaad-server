# ADR 0012: Web admin packaging and deployment model

## Status

Accepted; Angular/Gradle packaging implemented, deployment paths pending.

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

Docker will additionally provide a reproducible distribution/deployment path. The repository's Docker Compose deployment is intended to be able to bring up the Samvaad application container together with PostgreSQL for installations that want the database lifecycle managed by Compose.

The two supported operational modes are therefore:

- **Standalone JAR:** an operator supplies/configures PostgreSQL and runs the Samvaad executable JAR.
- **Docker Compose:** an operator uses the containerized Samvaad application together with the Compose-managed PostgreSQL instance.

Application configuration, including database connection details and other deployment-sensitive values, must be externalized from the JAR. Secrets must not be baked into the artifact.

## Consequences

- One executable JAR can deliver the admin UI, REST API, and WebSocket transport.
- A separate frontend web server is not required for the initial deployment model.
- Existing PostgreSQL installations remain supported.
- Docker remains valuable for reproducible packaging and convenient database provisioning rather than becoming a hard application dependency.
- The exact installer/one-line VPS bootstrap mechanism can be added later without changing the application artifact model.

## Explicitly deferred

- Final Angular-to-Gradle build integration mechanics.
- Final Dockerfile/image base and Compose production profile.
- Release publication mechanism (for example GitHub Releases or a container registry).
- One-command VPS installer/update workflow.
- Final external configuration file generation/lookup mechanism.
