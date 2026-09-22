# Samvaad Server

Samvaad is a server-first messaging system built around server-authoritative state, simple invariants, and explicit contracts.

This repository is a monorepo. The backend server service lives under `server/`, the web administration client lives under `web-admin/`, and shared documentation lives under `docs/` at the repository root.

Repository layout:

```text
samvaad-server/
├── docs/         # shared project documentation
├── server/       # self-contained Spring Boot backend (Gradle build, src/, compose.yaml)
├── web-admin/    # Angular + TypeScript + Tailwind administration client
├── README.md
├── AGENTS.md
├── .gitignore
└── .gitattributes
```

Backend commands run from `server/`. Web Admin commands run from `web-admin/`.

## Design Principles

Samvaad is designed according to the principles defined in the [Software Constitution](https://github.com/animesh0404/software-constitution).

The Constitution is a separate, project-independent engineering document that guides design decisions where there is meaningful freedom over how Samvaad is built and how it interacts with people. It emphasizes human dignity, autonomy, agency, meaningful consent, transparency, proportionality, data stewardship, failure containment, human oversight, and engineering restraint.

The Constitution is not a software licence or a substitute for Samvaad's security, legal, or operational requirements. Samvaad's project documentation and ADRs record the concrete technical decisions through which those principles are applied.

---

## Installation

### Docker installation (recommended)

Requirements: **Docker** (including `docker compose`) must already be installed. The installer does not install Docker.

Linux/macOS:

```bash
curl -fsSL https://raw.githubusercontent.com/animesh0404/samvaad-server/main/scripts/install.sh | bash
```

Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/animesh0404/samvaad-server/main/scripts/install.ps1 | iex
```

What the installer does:

```text
Docker required
    ↓
Installer creates the deployment directory (~/Samvaad, or C:\Samvaad on Windows)
    ↓
Downloads compose.yaml from this repository
    ↓
Creates/reuses .env (generates the database password, asks about the JWT secret)
    ↓
docker compose up -d (published versioned image; nothing is built locally)
    ↓
Verifies startup, then Samvaad is available at http://localhost:8080
```

Details:

- **Installation directory**: `~/Samvaad` on Linux/macOS, `C:\Samvaad` on Windows. It permanently contains `compose.yaml` (kept for future `up -d` / `down` / `restart` / `pull` operations — do not delete it) and `.env`.
- **Configuration**: secrets live only in `<install-dir>/.env` (`SAMVAAD_DB_PASSWORD`, `SAMVAAD_JWT_SECRET`). The installer never prints them. Do not commit or share `.env`. Re-running the installer preserves existing secrets.
- **Managing the deployment** (from the installation directory):
  - Stop: `docker compose down` (keeps data in the named volume)
  - Start: `docker compose up -d`
  - Restart: `docker compose restart`
  - Logs: `docker compose logs --tail=50` (or `docker logs samvaad-app`)
- **Network**: the container publishes `8080:8080`, so Samvaad is reachable at `http://localhost:8080` and, host firewall permitting, from other machines on the LAN via `http://<host-ip>:8080`. The Spring Boot app itself binds all interfaces (no `server.address` restriction); Docker owns the port mapping.
- **Bootstrap administrator (temporary credentials)**: username `admin`, password `admin123`. This is a Liquibase-seeded account (ADR 0007). Change the password after first login using the account self-service password change. The current release does not enforce this automatically; a first-time setup wizard is planned for a future release.

### Standalone JAR installation

The same application can run without Docker as an executable Spring Boot JAR (one process serves the Web Admin, REST API, and WebSocket endpoint):

- **Java**: JDK 25.
- **Database**: an externally provisioned PostgreSQL 18 instance with a `samvaad` database. Default connection expectations (from `server/src/main/resources/application.yaml`) are host `localhost`, port `5432`, database/user `samvaad`.
- **Required environment**: `SAMVAAD_JWT_SECRET` must be set (signing key for JWT access tokens).
- **Build & run** (from `server/`):
  ```bash
  ./gradlew clean bootJar
  java -jar build/libs/samvaad-server-*.jar
  ```
  Liquibase migrates the schema (including the bootstrap `admin` account) on startup before Hibernate validates mappings.

See `docs/development/setup.md` for the full packaging and deployment reference.

---

## Current Status

The project is in active development. **Phases 1–5, Realtime V1, Friends List API, the Web Admin panel, application packaging, Docker deployment, and the current administrative user-deletion slice are implemented and tested. Realtime V1 has also been manually verified end-to-end.**

Currently implemented:

- **User and Profile Slice**: User creation (`POST /api/users`), authenticated user lookup, profile retrieval, and self-service profile updates.
- **User Administration**: ADMIN-only user listing, provisioning, and hard deletion. Hard deletion explicitly removes the user's sessions, profile, friend requests, sent messages, conversations involving the user, and messages in those conversations before deleting the user row. The database RESTRICT foreign keys remain as backstops. Successful deletion returns `204 No Content`; residual concurrent deletion conflicts return `409 Conflict`.
- **Authentication & Sessions**: Login with username or email and password, persisted sessions, session-bound JWT access tokens, rotating refresh tokens, five-active-session enforcement, and logout/session revocation. `installationId` is optional client/device metadata; clients without an installation concept do not need to manufacture one.
- **Account Self-Service**: Users can change their own email and password. Username is immutable after creation.
- **User Discovery**: Authenticated exact username lookup (`GET /api/users/lookup?username=...`).
- **Friend Requests**: Authenticated send, incoming/outgoing pending lists, recipient accept/reject, sender cancellation, duplicate/reverse-direction protection, and re-request after rejected/cancelled requests.
- **Friendship**: An accepted friend-request row represents the friendship; `areFriends(a,b)` is used by direct messaging authorization.
- **Friends List API**: Authenticated `GET /api/friends` returns the caller's accepted friends as safe `userId`/`username` representations, ordered by username ascending. The caller is derived from the authenticated principal; no separate Friendship table/entity is used.
- **Direct Messaging**: Authenticated friends can send plain-text messages through HTTP. Direct conversations are unique per unordered user pair; messages receive server sequence/timestamp values and client request UUIDs provide idempotent replay handling.
- **Conversation Reads**: Participants can list direct conversations through `GET /api/conversations/direct` with offset/limit pagination and recent-activity ordering.
- **Message Reads**: Participants can fetch messages through `GET /api/conversations/direct/{conversationId}/messages` using the exclusive `afterSequence` cursor.
- **Realtime Messaging V1**: WebSocket + STOMP transport at `/ws`, authenticated STOMP `CONNECT` using the existing access JWT/session model, participant-only conversation subscriptions, `/app/chat.send`, `/topic/conversations/{conversationId}`, reuse of the existing message persistence/idempotency/authorization logic, and broadcast only after successful persistence. The first slice uses Spring's in-memory simple broker. A manual Alice-to-Bob smoke test has verified live delivery without polling.
- **Web Admin**: Angular 22 + TypeScript + Tailwind CSS administration UI with dashboard, user listing, user creation, user detail, and user deletion flows. Web Admin login uses `clientPlatform: "WEB"`; only `ADMIN` accounts are allowed to establish a Web Admin session. Valid non-admin credentials are rejected at the login page and do not retain tokens/session state.
- **Persistence & Migrations**: PostgreSQL database integration managed via Liquibase changelogs.
- **JPA Auditing**: Basic entity change auditing.

### Not Yet Implemented / Deferred

- Reconnect/missed-event synchronization and offline queues.
- Persistent read state/read receipts.
- Typing/presence, delivery receipts, and push notifications.
- Message editing/deletion/replies.
- Blocking, unfriend, archiving, and mute preferences.
- Horizontal scaling/external brokers, a general event bus, and end-to-end encryption.

Friend-gated profile visibility remains deferred; it was intentionally not activated as part of Phase 3.

See `docs/` for implementation snapshots, locked decisions, and verification records.

---

## Technology Stack

### Backend

- **Java**: 25
- **Framework**: Spring Boot 4.1.1
- **Build Tool**: Gradle 9.7.0
- **Database**: PostgreSQL 18
- **Database Migrations**: Liquibase
- **Realtime**: Spring WebSocket/STOMP with simple broker
- **Testing**: JUnit 5, Mockito, Spring MockMvc, Testcontainers PostgreSQL

### Web Admin

- **Framework**: Angular 22
- **Language**: TypeScript
- **Styling**: Tailwind CSS v4
- **Package manager**: npm
- **Node convention**: Node 24 via `.nvmrc`
- **Testing**: Vitest

The Web Admin is a thin client of the stable Samvaad server contracts. Bootstrap is not used, and Angular Material is not a required component system for the initial panel. Web Admin session tokens are persisted in `sessionStorage` so a page reload restores the session; the server remains the authoritative authentication/session system (ADR 0014).

The Angular production build is now packaged into the Spring Boot executable JAR, so one JAR serves the web admin, REST API, and WebSocket endpoint through embedded Tomcat. The packaging mechanics are implemented through the existing Gradle `bootJar` tasks.

---

## Deployment Direction

Two deployment modes are supported:

1. **Standalone JAR** — run the executable JAR directly and connect it to an externally provisioned PostgreSQL instance using externalized deployment configuration.
2. **Docker Compose** — run the containerized Samvaad application together with the Compose-managed PostgreSQL instance using the repository lifecycle scripts.

Docker is therefore a distribution/deployment option, not a hard runtime requirement for the application JAR.

For public/VPS deployments, HTTPS is intended to terminate at a TLS-capable reverse proxy, tunnel, or managed edge in front of the application. The Samvaad JAR remains behind that edge and must support REST and WebSocket traffic through the proxy. The Unix and Windows installers provide the current one-command deployment path; the concrete TLS provider, certificate automation, domain/DNS configuration, and deployment upgrade policy remain open deployment work.

See ADRs 0011–0013, 0015, and 0016 for the locked packaging, deployment, release-image, and installer decisions and their remaining deferred details.

## Local Development

Backend:

```bash
cd server
docker compose up -d
./gradlew bootRun
```

Or:

```bash
cd server
./gradlew bootTestRun
```

Web Admin, in a separate terminal:

```bash
cd web-admin
nvm use
npm install
npm start
```

The Web Admin development server runs on `http://localhost:4200` and proxies `/api` to the backend on `http://localhost:8080`.

Full Docker development (application container built from local source instead of the published release image):

```bash
docker compose -f compose.yaml -f compose.dev.yaml up -d --build
```

This builds the local-only `samvaad-server:dev` image and runs it with an isolated `samvaad-dev` project/volume. It is never pushed; release publication stays in `scripts/release-image.sh`.

See `docs/development/setup.md` and `web-admin/README.md` for complete environment details.

## Build & Test Commands

Backend:

```bash
cd server
./gradlew build -x test
./gradlew test
./gradlew check
```

Web Admin:

```bash
cd web-admin
npm test
npm run build
```

## Documentation

The authoritative documentation lives under [`docs/`](docs/):

- [Documentation Map](docs/README.md)
- [Product & Design Decisions](docs/Samvaad%20Product%20%26%20Design%20Decisions.md)
- [Technical Design](docs/Samvaad%20Technical%20Design.md)
- [Implementation Roadmap](docs/Samvaad%20Implementation%20Roadmap.md)
- [Architecture Current State](docs/architecture/current-state.md)
- [SOLID Architecture Review](docs/architecture/solid-architecture-review.md)
- [User & Profile API Contract](docs/api/current-user-profile-api.md)
- [Friends API Contract](docs/api/friends-api.md)
- [Security Posture](docs/security/current-security-posture.md)
- [Architecture Decision Records](docs/adr/)
- [Developer Guides](docs/development/)
- [Verification Records](docs/verification/)
- [Agent Guidelines](AGENTS.md)

---

## Next Implementation Area

**Public deployment edge and operational hardening**: application packaging, Docker deployment, versioned release publication, external deployment credentials, and the Unix/Windows installer workflow are implemented. Remaining deployment work is the concrete TLS/reverse-proxy setup, certificate/domain configuration, deployment upgrade policy, and the future first-time setup wizard.
