# Samvaad Server

Samvaad is a server-first messaging system built around server-authoritative state, simple invariants, and explicit contracts.

This repository contains the backend server service. The client interface (such as a terminal-based TUI or other client) is developed as a separate consumer of stable server APIs and does not dictate server design.

---

## Current Status

The project is in active backend development. The account/authentication boundary and authenticated user discovery are implemented and tested.

Currently implemented:

- **User and Profile Slice**: User creation (`POST /api/users`), authenticated user lookup (`GET /api/users/{userId}`), profile retrieval (`GET /api/users/{userId}/profile`), and self-service profile updates (`PATCH /api/users/{userId}/profile`). Profile PATCH distinguishes omitted fields, non-null values, and explicit `null` values.
- **User Administration**: ADMIN-only user listing (`GET /api/users`) and hard deletion (`DELETE /api/users/{userId}`).
- **Authentication & Sessions**: Login with username or email and password, persisted sessions, session-bound JWT access tokens, rotating refresh tokens, five-active-session enforcement, and logout/session revocation.
- **Account Self-Service**: Users can change their own email and password through dedicated endpoints. Username is immutable after creation.
- **User Discovery**: Authenticated exact username lookup (`GET /api/users/lookup?username=...`) with case-insensitive matching and a restricted discovery DTO containing only `userId` and `username`.
- **Persistence & Migrations**: PostgreSQL database integration managed via Liquibase changelogs.
- **JPA Auditing**: Basic entity change auditing (currently recording `"system"` as the actor).

### Not Yet Implemented

Planned features not present in the current codebase include:

- Friend-request lifecycle and friendship persistence.
- Direct conversations and message persistence.
- Realtime transport and delivery (STOMP/WebSocket).
- Read state, message mutation, replies, reconnect/offline synchronization, and related messaging infrastructure.
- User blocking, archiving, or mute preferences.

See [docs/architecture/current-state.md](docs/architecture/current-state.md) and [docs/security/current-security-posture.md](docs/security/current-security-posture.md) for detailed implementation snapshots.

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

For testing conventions and suite organization, see `docs/development/testing.md`.

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

The next planned slice is **Phase 3 — Friend Request Vertical Slice**:

- Persist friend requests and friendship state.
- Allow an authenticated user to send a request to a discovered user.
- Support the pending → accepted/rejected lifecycle required by the V1 contract.
- Enforce sender/recipient ownership and duplicate-request rules server-side.
- Establish friendship as the authorization gate for later direct messaging.

The Phase 1 account/authentication boundary and Phase 2 exact username discovery are complete. Do not begin direct messaging until the friend-request slice is implemented and tested.

See [docs/Samvaad Implementation Roadmap.md](docs/Samvaad%20Implementation%20Roadmap.md) for the full implementation sequence and [docs/adr/0008-user-relationship-and-friend-request-model.md](docs/adr/0008-user-relationship-and-friend-request-model.md) for relationship rules.
