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

The application starts by default on port `8080`.

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
datasource settings come from `application.yaml` (externalized deployment
configuration remains future work per ADR 0012). The JAR serves `/`
(login shell), SPA routes (`/login`, `/profile`, `/users…`, including on
browser refresh), static assets, `/api/**`, and `/ws` on port `8080`.

The `ng serve` + `bootRun` development workflow is unchanged and does not
serve the packaged UI; use it for day-to-day frontend/backend work and the
JAR for packaging verification.

---

## Related Documentation

- [Testing Guide](testing.md) — Testing strategy, layers, and commands.
- [Current User and Profile API](../api/current-user-profile-api.md) — Available REST endpoints and request formats.
- [Documentation Map](../README.md) — Overview of all design records, ADRs, and guides.
