# Samvaad Server

Samvaad is a server-first messaging system built around server-authoritative state, simple invariants, and explicit contracts.

This repository contains the backend server service. The client interface (such as a terminal-based TUI or other client) is developed as a separate consumer of stable server APIs and does not dictate server design.

---

## Current Status

The project is in active backend development. Currently implemented:

- **User and Profile Slice**: Core entities, database schemas, and REST endpoints for user creation (`POST /api/users`), user lookup (`GET /api/users/{userId}`), profile retrieval (`GET /api/users/{userId}/profile`), and profile updates (`PATCH /api/users/{userId}/profile`).
- **Persistence & Migrations**: PostgreSQL database integration managed via Liquibase changelogs.
- **JPA Auditing**: Basic entity change auditing (currently recording `"system"` as the actor).

### Not Yet Implemented

Planned features not present in the current codebase include:
- Authentication, session lifecycle, password handling, JWT access tokens, and rotating refresh tokens.
- Messaging, direct conversations, message persistence, delivery acknowledgments, read states, and realtime transport (STOMP/WebSocket).
- User blocking, archiving, or mute preferences.

See [docs/architecture/current-state.md](docs/architecture/current-state.md) and [docs/security/current-security-posture.md](docs/security/current-security-posture.md) for detailed snapshots.

---

## Technology Stack

- **Java**: 25 (configured via Gradle toolchain in `build.gradle`)
- **Framework**: Spring Boot 4.1.1
  - Spring Web MVC (`spring-boot-starter-webmvc`)
  - Spring Data JPA (`spring-boot-starter-data-jpa`)
  - Spring Validation (`spring-boot-starter-validation`)
  - Spring Boot Liquibase (`spring-boot-starter-liquibase`)
- **Build Tool**: Gradle 9.7.0 (via `./gradlew` wrapper)
- **Database**: PostgreSQL 18 (defined in `compose.yaml`)
- **Database Migrations**: Liquibase (`src/main/resources/db/changelog/`)
- **Testing & Development Containers**:
  - JUnit 5 / Jupiter Platform
  - Mockito & Spring MockMvc
  - Testcontainers PostgreSQL (`org.testcontainers:testcontainers-postgresql`)

---

## Local Development

You can run the server locally using either Docker Compose or Spring Boot's Testcontainers development mode:

### Option A: With Docker Compose (Dedicated Database)

1. Start the PostgreSQL container:
   ```bash
   docker compose up -d
   ```
2. Start the application:
   ```bash
   ./gradlew bootRun
   ```
The application connects to `jdbc:postgresql://localhost:5432/samvaad` with credentials `samvaad`/`samvaad` as defined in `src/main/resources/application.yaml`.

### Option B: With Testcontainers (Zero Manual Setup)

Run the application using the test runtime classpath, which starts a containerized PostgreSQL instance automatically:
```bash
./gradlew bootTestRun
```
This runs `TestSamvaadServerApplication` using `TestcontainersConfiguration`. Requires a running Docker daemon.

For complete environment details, see [docs/development/setup.md](docs/development/setup.md).

---

## Build & Test Commands

Verified commands runnable via the Gradle wrapper:

- **Build without running tests**:
  ```bash
  ./gradlew build -x test
  ```
  *(or `./gradlew assemble`)*

- **Run all unit and integration tests**:
  ```bash
  ./gradlew test
  ```

- **Run all checks and verification tasks**:
  ```bash
  ./gradlew check
  ```

For testing conventions and suite organization, see [docs/development/testing.md](docs/development/testing.md).

---

## Documentation Directory

The authoritative documentation lives under [`docs/`](docs/):

- **[Documentation Map](docs/README.md)**: Full navigation guide to all project records and decision categories.
- **Canonical Design Records**:
  - [Product & Design Decisions](docs/Samvaad%20Product%20%26%20Design%20Decisions.md)
  - [Technical Design](docs/Samvaad%20Technical%20Design.md)
  - [Implementation Roadmap](docs/Samvaad%20Implementation%20Roadmap.md)
- **Architecture Decision Records (ADRs)**: [`docs/adr/`](docs/adr/) (ADRs 0001–0006)
- **Architecture, API & Security Snapshots**:
  - [Architecture Current State](docs/architecture/current-state.md)
  - [User & Profile API Contract](docs/api/current-user-profile-api.md)
  - [Security Posture](docs/security/current-security-posture.md)
- **Developer Guides**:
  - [Environment Setup](docs/development/setup.md)
  - [Testing Guide](docs/development/testing.md)
- **Agent Guidelines & Guardrails**: [`AGENTS.md`](AGENTS.md)

---

## Next Implementation Area

The next planned slice is **Authentication and Session Management**:
- BCrypt credential hashing and storage (passwords must never be logged or stored in plaintext).
- Registration and login flows.
- Persistent session management with JWT access tokens and rotating refresh tokens.
- Logout and session revocation.

See [ADR 0003](docs/adr/0003-authentication-and-session-model.md) and [docs/Samvaad Implementation Roadmap.md](docs/Samvaad%20Implementation%20Roadmap.md) for specifications.
