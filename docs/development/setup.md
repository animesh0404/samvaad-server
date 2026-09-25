# Local Development Setup

This guide details how to configure and run the Samvaad backend server locally.

The backend lives under `server/`. Run backend commands from `server/`
(`cd server && ./gradlew ...`). The local `.env` stays at the repository
root; the tracked example is `server/.env.example`.

---

## Prerequisites & Environment

The versions and tools below are derived directly from the repository configuration:

- **Java**: 25 (specified via `java.toolchain.languageVersion = JavaLanguageVersion.of(25)` in `server/build.gradle`)
- **Build Tool**: Gradle 9.7.0 (using the included wrapper; run `./gradlew` from `server/`)
- **Framework**: Spring Boot 4.1.1
- **Database**: PostgreSQL 18 (configured via Docker Compose in `server/compose.yaml`)
- **Container Runtime**: Docker (required for Docker Compose or Testcontainers)
- **Local environment loading**: `direnv` (used to load the local `.env` file)

### Local environment variables

The application requires `SAMVAAD_JWT_SECRET` for the JWT signing configuration. The real local secret must not be committed.

Create the local environment file at the repository root from the committed
example (run from the repository root):

```bash
cp server/.env.example .env
```

The existing local `.env` stays at the repository root. Do not create a
second `.env` under `server/`.

Generate a development secret:

```bash
openssl rand -base64 32
```

Put the generated value in `.env`:

```dotenv
SAMVAAD_JWT_SECRET=<your-local-secret>
```

Samvaad uses `direnv` to load `.env` automatically when entering the repository. The repository-root `.envrc` contains the `dotenv` directive, so the variables remain available when running backend commands under `server/`.

If `direnv` is not already integrated with Bash, add its hook and reload the shell:

```bash
echo 'eval "$(direnv hook bash)"' >> ~/.bashrc
source ~/.bashrc
```

Allow the repository environment:

```bash
direnv allow
```

Verify that the variable is loaded without printing the secret:

```bash
test -n "$SAMVAAD_JWT_SECRET" && echo "JWT secret loaded" || echo "JWT secret missing"
```

`.env` and `.envrc` live at the repository root, are local-only, and are excluded by `.gitignore`. `server/.env.example` is safe to commit and must contain only a placeholder value.

---

## Database Configuration

### Using Docker Compose

The backend includes a `server/compose.yaml` file defining the local PostgreSQL development instance:

- **Image**: `postgres:18`
- **Container Name**: `samvaad-postgres`
- **Database Name**: `samvaad`
- **Username**: `samvaad`
- **Password**: `samvaad`
- **Exposed Port**: `5432:5432`
- **Volume**: `samvaad-postgres-data` (persisted locally)

Start the database in the background (from `server/`):
```bash
cd server
docker compose up -d
```

Stop the database (from `server/`):
```bash
cd server
docker compose down
```

### Application Datasource Settings

The application connects to the local PostgreSQL instance via configuration in `server/src/main/resources/application.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/samvaad
    username: samvaad
    password: samvaad

  jpa:
    hibernate:
      ddl-auto: validate
```

Liquibase runs on startup (`server/src/main/resources/db/changelog/db.changelog-master.yaml`) to automatically execute changelogs and bring the database schema up to date before Hibernate validates entity mappings.

---

## Running the Application

There are two primary ways to run the service locally:

### 1. Standard Execution (with Docker Compose)

When running against the Compose-managed PostgreSQL database:

1. Ensure the PostgreSQL container is active:
   ```bash
   cd server
   docker compose up -d
   ```
2. Ensure `SAMVAAD_JWT_SECRET` is loaded as described above.
3. Start the Spring Boot application:
   ```bash
   cd server
   ./gradlew bootRun
   ```

The application starts by default on port `8080` serving **HTTPS** (application-managed TLS, ADR 0017): first local runs create `~/.samvaad/application.yaml` and `~/.samvaad/tls/keystore.p12` automatically. Use `https://localhost:8080` (accept the self-signed warning) or the `ng serve` proxy, which forwards to the HTTPS backend.

### 2. Testcontainers Development Run (Automated Database)

Spring Boot includes development-time Testcontainers support. You can launch the application along with a containerized PostgreSQL database in a single command, without starting Docker Compose:

```bash
cd server
./gradlew bootTestRun
```

This runs `com.samvaad.samvaad_server.TestSamvaadServerApplication`, which boots `SamvaadServerApplication` along with the `@ServiceConnection` defined in `TestcontainersConfiguration`. Requires Docker to be running.

---

## Build & Test Commands

Use the Gradle wrapper to build and verify the project:

- **Compile and assemble artifacts (skipping tests)**:
  ```bash
  cd server
  ./gradlew build -x test
  ```
  *(or `./gradlew assemble`)*

- **Run all automated tests**:
  ```bash
  cd server
  ./gradlew test
  ```

- **Run full verification (tests and quality checks)**:
  ```bash
  cd server
  ./gradlew check
  ```

- **Clean build directory**:
  ```bash
  cd server
  ./gradlew clean
  ```

---

## Application Packaging

The deliverable is one executable Spring Boot JAR serving the Web Admin UI,
the REST API, and the WebSocket/STOMP endpoint from embedded Tomcat
(ADR 0012). PostgreSQL stays external and is never embedded.

One-time prerequisite (same toolchain as web-admin development):

```bash
cd web-admin
nvm use
npm install
```

Build the artifact (from `server/`):

```bash
cd server
./gradlew clean bootJar
```

`bootJar` builds the Angular production bundle (`npm run build`, skipped
when Angular inputs are unchanged; `npm ci` is never run) and stages it
under `BOOT-INF/classes/static` in
`server/build/libs/samvaad-server-*.jar`. Backend `./gradlew test` never
touches Node.

Run it against an externally provisioned PostgreSQL instance:

```bash
java -jar server/build/libs/samvaad-server-*.jar
```

`SAMVAAD_JWT_SECRET` must be provided as described above; remaining
datasource settings come from `application.yaml` for standalone JAR deployment. Since application-managed TLS (ADR 0017), standalone runs also use `~/.samvaad/application.yaml` and `~/.samvaad/tls/keystore.p12` (created automatically on first start) and serve HTTPS on port `8080`. The JAR serves `/`
(login shell), SPA routes (`/login`, `/profile`, `/users…`, including on
browser refresh), static assets, `/api/**`, and `/ws` on port `8080`.

The `ng serve` + `bootRun` development workflow is unchanged and does not
serve the packaged UI; use it for day-to-day frontend/backend work and the
JAR for packaging verification.

---

## Docker Deployment

The repository also provides an implemented Docker deployment path for installations that want the application and PostgreSQL lifecycle managed together. Deployment consumes the published versioned image referenced by the root `compose.yaml` (for example `animesh0404/samvaad-server:0.0.1`); deploying a release does NOT require building the application image locally first.

End users normally install with the one-command installer (no repository
checkout needed; see the project README):

```bash
curl -fsSL https://raw.githubusercontent.com/animesh0404/samvaad-server/main/scripts/install.sh | bash
```

The installer writes `~/.samvaad/compose.yaml` plus `~/.samvaad/.env`
(`SAMVAAD_DB_PASSWORD`, `SAMVAAD_JWT_SECRET`,
`SAMVAAD_TLS_KEYSTORE_PASSWORD`) and starts the stack. Installations created
by older installers under `~/Samvaad` are migrated automatically (files move
over; nothing is overwritten).

The deployment database password is supplied through `.env`, never
hardcoded: the root `compose.yaml` requires `SAMVAAD_DB_PASSWORD` (fail-fast
`${VAR:?…}` references) and passes the same value to PostgreSQL
(`POSTGRES_PASSWORD`) and to the application
(`SPRING_DATASOURCE_PASSWORD`). `scripts/install.sh` and
`scripts/start.sh` generate a secure value automatically when none is
configured and reuse the existing one otherwise.

Migration note: deployments created before the password was externalized
used the fixed password `samvaad` baked into their existing PostgreSQL
volume. After updating, set `SAMVAAD_DB_PASSWORD` in `.env` to the password
your existing database was initialized with (default installations: the
previous fixed value) rather than generating a new one, otherwise the
application cannot authenticate to the retained volume.

### Application-managed TLS

Samvaad serves **HTTPS only** on port 8080 (ADR 0017); there is no insecure
HTTP listener. On first boot the application generates a self-signed
PKCS#12 identity (RSA 2048, SHA256withRSA, ~10-year validity, alias
`samvaad`, default SANs `DNS:localhost` + `IP:127.0.0.1`) and persists it;
later boots reuse it. Expired, malformed, wrong-password, or alias-missing
stores fail startup — nothing is ever silently regenerated. Regeneration is
explicit: delete the keystore and restart.

- **Configuration split.** Secrets live in `.env` (`SAMVAAD_DB_PASSWORD`,
  `SAMVAAD_JWT_SECRET`, `SAMVAAD_TLS_KEYSTORE_PASSWORD`).
  Non-secret deployment settings live in the external operator-owned
  `application.yaml`, created with defaults once and never overwritten:
  `~/.samvaad/application.yaml` standalone (repo deployments use the
  `application.yaml` next to `compose.yaml`), `/config/application.yaml`
  in Docker (bind-mounted). Additional certificate SANs are configured
  there under `samvaad.tls.sans` and apply only when the keystore is
  (re)generated.
- **TLS storage.** Standalone: `~/.samvaad/tls/keystore.p12`. Docker:
  `/tls/keystore.p12` on the dedicated `samvaad-tls` volume
  (`samvaad-dev-tls` for the dev stack) — never in the image, classpath,
  JAR, logs, or PostgreSQL volumes.
- **Trust.** Browsers warn on the self-signed certificate; traffic is still
  encrypted. Compare the SHA-256 fingerprint from the application logs
  (`[samvaad] Generated new TLS identity …` / `Reusing TLS identity …`)
  before accepting it. Trusted public deployments terminate TLS at a
  reverse proxy instead (ADR 0013); no ACME, rotation, HSTS, or hot reload
  in V1.
- **Operator fallback.** The application never shells out to `keytool`, but
  operators may use it for diagnostics (e.g. `keytool -list
  -keystore tls/keystore.p12 -storetype PKCS12`).

Build the portable local image artifact from the repository root (local/offline workflow only; never pushed, and not consumed by any Compose workflow):

```bash
./scripts/build.sh
```

This uses Docker Buildx/BuildKit, loads `samvaad-server:latest` into the local Docker image store, and writes:

```text
server/build/samvaad-server.tar.gz
```

The generated `server/build/` directory is ignored by Git.

### Local Docker development loop

To run the application container built from local source (instead of the
published release image), use the tracked development override, which
reuses the entire `compose.yaml` topology and only swaps image identity,
build source, container/project names, and the PostgreSQL volume:

```bash
docker compose -f compose.yaml -f compose.dev.yaml up -d --build
```

or stepwise:

```bash
docker compose -f compose.yaml -f compose.dev.yaml build app
docker compose -f compose.yaml -f compose.dev.yaml up -d
```

This builds `samvaad-server:dev` from the repository `Dockerfile` and runs
it as `samvaad-dev-app` with PostgreSQL as `samvaad-dev-db` under the
`samvaad-dev` project. The image name has no registry prefix, so it can
never be pushed to Docker Hub; publishing stays the job of
`scripts/release-image.sh`. The development database uses the separate
`samvaad-dev-data` volume — it never shares the release `samvaad-data`
volume. Tear down with:

```bash
docker compose -f compose.yaml -f compose.dev.yaml down
```

(`down` keeps the dev volume; add `-v` to drop it as well. The dev stack
publishes the same host port `8080`, so run either the release or the dev
stack at a time.)

`scripts/build.sh` above remains the portable tarball-artifact workflow
(`samvaad-server:latest` + `server/build/samvaad-server.tar.gz` for offline
transfer); it is not the iterative development loop and nothing in Compose
consumes its output.

Release publication is a separate workflow from the local build above
(ADR 0015). To build and publish a versioned release image to Docker Hub:

```bash
./scripts/release-image.sh 0.0.1
```

This builds the production image from the repository `Dockerfile`, tags
exactly `animesh0404/samvaad-server:<version>`, and pushes only that tag. It
never tags or pushes `latest`. The version argument is required and explicit;
authentication relies on your existing `docker login` session.

In short, the deployment lifecycle scripts are:

- `scripts/build.sh` → portable local artifact (`samvaad-server:latest` + tarball, never pushed, not consumed by Compose).
- `compose.dev.yaml` → iterative local development loop (`samvaad-server:dev`, built from source via Compose).
- `scripts/release-image.sh <version>` → explicit versioned Docker Hub release publication.
- `scripts/start.sh` → deploy/start the published image referenced by root `compose.yaml` (pulls it when missing; no local build required).
- `scripts/restart.sh` → restart the deployment stack.
- `scripts/stop.sh` → stop the deployment stack.

Start the deployment (no local build required):

```bash
./scripts/start.sh
```

On the first start, if the repository-root `.env` does not contain `SAMVAAD_JWT_SECRET`, the script prompts for a secret or generates one automatically when Enter is pressed. The generated/entered secret is stored in `.env` with mode `600`. Existing secrets are reused and are never rotated by restart.

Restart or stop the deployment without rebuilding:

```bash
./scripts/restart.sh
./scripts/stop.sh
```

The Docker Compose deployment uses the `samvaad` project name and a separate PostgreSQL volume/network from the `server/compose.yaml` development database and from the `compose.dev.yaml` local-build stack. PostgreSQL is not published to the host by the deployment Compose file.

The Docker runtime image contains the Spring Boot executable JAR and a Java 25 JRE only; Node, npm, Gradle, source files, and frontend `node_modules` are build-stage content and are not included in the runtime image. The E2EE/libsignal runtime requires a glibc-based JRE image rather than Alpine/musl.

---

## Related Documentation

- [Testing Guide](testing.md) — Testing strategy, layers, and commands.
- [Current User and Profile API](../api/current-user-profile-api.md) — Available REST endpoints and request formats.
- [Documentation Map](../README.md) — Overview of all design records, ADRs, and guides.
