# Local Development Setup

This guide details how to configure and run the Samvaad backend server locally.

---

## Prerequisites & Environment

The versions and tools below are derived directly from the repository configuration:

- **Java**: 25 (specified via `java.toolchain.languageVersion = JavaLanguageVersion.of(25)` in `build.gradle`)
- **Build Tool**: Gradle 9.7.0 (using the included `./gradlew` wrapper)
- **Framework**: Spring Boot 4.1.1
- **Database**: PostgreSQL 18 (configured via Docker Compose in `compose.yaml`)
- **Container Runtime**: Docker (required for Docker Compose or Testcontainers)

---

## Database Configuration

### Using Docker Compose

The repository includes a `compose.yaml` file defining the local PostgreSQL development instance:

- **Image**: `postgres:18`
- **Container Name**: `samvaad-postgres`
- **Database Name**: `samvaad`
- **Username**: `samvaad`
- **Password**: `samvaad`
- **Exposed Port**: `5432:5432`
- **Volume**: `samvaad-postgres-data` (persisted locally)

Start the database in the background:
```bash
docker compose up -d
```

Stop the database:
```bash
docker compose down
```

### Application Datasource Settings

The application connects to the local PostgreSQL instance via configuration in `src/main/resources/application.yaml`:

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

Liquibase runs on startup (`src/main/resources/db/changelog/db.changelog-master.yaml`) to automatically execute changelogs and bring the database schema up to date before Hibernate validates entity mappings.

---

## Running the Application

There are two primary ways to run the service locally:

### 1. Standard Execution (with Docker Compose)

When running against the Compose-managed PostgreSQL database:

1. Ensure the PostgreSQL container is active:
   ```bash
   docker compose up -d
   ```
2. Start the Spring Boot application:
   ```bash
   ./gradlew bootRun
   ```

The application starts by default on port `8080`.

### 2. Testcontainers Development Run (Automated Database)

Spring Boot includes development-time Testcontainers support. You can launch the application along with a containerized PostgreSQL database in a single command, without starting Docker Compose:

```bash
./gradlew bootTestRun
```

This runs `com.samvaad.samvaad_server.TestSamvaadServerApplication`, which boots `SamvaadServerApplication` along with the `@ServiceConnection` defined in `TestcontainersConfiguration`. Requires Docker to be running.

---

## Build & Test Commands

Use the Gradle wrapper to build and verify the project:

- **Compile and assemble artifacts (skipping tests)**:
  ```bash
  ./gradlew build -x test
  ```
  *(or `./gradlew assemble`)*

- **Run all automated tests**:
  ```bash
  ./gradlew test
  ```

- **Run full verification (tests and quality checks)**:
  ```bash
  ./gradlew check
  ```

- **Clean build directory**:
  ```bash
  ./gradlew clean
  ```

---

## Related Documentation

- [Testing Guide](testing.md) — Testing strategy, layers, and commands.
- [Current User and Profile API](../api/current-user-profile-api.md) — Available REST endpoints and request formats.
- [Documentation Map](../README.md) — Overview of all design records, ADRs, and guides.
