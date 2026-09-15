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

---

## Current Status

The project is in active development. **Phases 1–5, Realtime V1, Friends List API, the Web Admin panel, and the current administrative user-deletion slice are implemented and tested. Realtime V1 has also been manually verified end-to-end.**

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

- Web Admin production packaging into the Spring Boot JAR; the packaging direction is locked, but Angular/Gradle integration is not yet implemented.
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
- **Testing**: Vitest and Playwright

The Web Admin is a thin client of the stable Samvaad server contracts. Bootstrap is not used, and Angular Material is not a required component system for the initial panel. Access/refresh tokens are held in memory rather than browser storage.

The intended delivery model is to package the Angular production build into the Spring Boot executable JAR so one JAR can serve the web admin, REST API, and WebSocket endpoint through embedded Tomcat. The packaging mechanics are deferred.

---

## Deployment Direction

Two deployment modes are intended:

1. **Standalone JAR** — run the executable JAR directly and connect it to an externally provisioned PostgreSQL instance using externalized deployment configuration.
2. **Docker Compose** — run a containerized Samvaad application together with the Compose-managed PostgreSQL instance.

Docker is therefore a distribution/deployment option, not a hard runtime requirement for the application JAR.

For public/VPS deployments, HTTPS is intended to terminate at a TLS-capable reverse proxy, tunnel, or managed edge in front of the application. The Samvaad JAR remains behind that edge and must support REST and WebSocket traffic through the proxy. The concrete provider, certificate automation, and VPS installation/update workflow remain future implementation work.

See ADRs 0011–0013 for the locked architecture decisions and their explicit deferred details.

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
npm run e2e
```

## Documentation

The authoritative documentation lives under [`docs/`](docs/):

- [Documentation Map](docs/README.md)
- [Product & Design Decisions](docs/Samvaad%20Product%20%26%20Design%20Decisions.md)
- [Technical Design](docs/Samvaad%20Technical%20Design.md)
- [Implementation Roadmap](docs/Samvaad%20Implementation%20Roadmap.md)
- [Architecture Current State](docs/architecture/current-state.md)
- [User & Profile API Contract](docs/api/current-user-profile-api.md)
- [Friends API Contract](docs/api/friends-api.md)
- [Security Posture](docs/security/current-security-posture.md)
- [Architecture Decision Records](docs/adr/)
- [Developer Guides](docs/development/)
- [Verification Records](docs/verification/)
- [Agent Guidelines](AGENTS.md)

---

## Next Implementation Area

**Application packaging & deployment**: integrate the Angular production build into the Spring Boot executable JAR and validate the standalone-JAR and Docker deployment paths, while preserving the locked TLS-at-edge architecture.

The later release work will cover publication, VPS installation/update workflow, external configuration mechanics, and concrete TLS/reverse-proxy setup.
