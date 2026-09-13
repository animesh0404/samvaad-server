# Samvaad Server

Samvaad is a server-first messaging system built around server-authoritative state, simple invariants, and explicit contracts.

This repository contains the backend server service. The client interface is developed as a separate consumer of stable server APIs and does not dictate server design.

---

## Current Status

The project is in active backend development. **Phase 1 — Authentication & Authorization**, **Phase 2 — Exact Username Discovery**, **Phase 3 — Friend Request Vertical Slice**, **Phase 4 — Direct Messaging Vertical Slice**, and **Phase 5 — Conversation/Message Reads & Listing** are complete and tested.

Currently implemented:

- **User and Profile Slice**: User creation (`POST /api/users`), authenticated user lookup, profile retrieval, and self-service profile updates. Profile PATCH distinguishes omitted fields, non-null values, and explicit `null` values.
- **User Administration**: ADMIN-only user listing and hard deletion.
- **Authentication & Sessions**: Login with username or email and password, persisted sessions, session-bound JWT access tokens, rotating refresh tokens, five-active-session enforcement, and logout/session revocation.
- **Account Self-Service**: Users can change their own email and password. Username is immutable after creation.
- **User Discovery**: Authenticated exact username lookup (`GET /api/users/lookup?username=...`) with case-insensitive matching and a restricted discovery DTO containing only `userId` and `username`.
- **Friend Requests**: Authenticated send, incoming/outgoing pending lists, recipient accept/reject, sender cancellation, duplicate/reverse-direction protection, and re-request after rejected/cancelled requests.
- **Friendship**: An accepted friend-request row represents the friendship, with an internal `areFriends(a, b)` relationship check used by direct messaging authorization.
- **Direct Messaging**: Authenticated friends can send plain-text messages through `POST /api/conversations/direct/messages`. Friendship authorization is checked before conversation lookup/creation; direct conversations are unique per unordered user pair, first-message creation is atomic, messages receive server sequence/timestamp values, and client request UUIDs provide idempotent replay handling.
- **Conversation Reads**: Authenticated participants can list their direct conversations through `GET /api/conversations/direct`, ordered by recent activity with offset/limit pagination.
- **Message Reads**: Authenticated conversation participants can fetch messages through `GET /api/conversations/direct/{conversationId}/messages`, ordered by server sequence with an exclusive `afterSequence` cursor and limit.
- **Persistence & Migrations**: PostgreSQL database integration managed via Liquibase changelogs.
- **JPA Auditing**: Basic entity change auditing.

### Not Yet Implemented

- Realtime transport and delivery (STOMP/WebSocket).
- Read state, message mutation, replies, reconnect/offline synchronization, and related messaging infrastructure.
- User blocking, unfriend, archiving, or mute preferences.

Friend-gated profile visibility remains deferred; it was intentionally not activated as part of Phase 3.

See the architecture, API, security, roadmap, and ADR documents under `docs/` for detailed implementation snapshots and locked design decisions.

---

## Technology Stack

- **Java**: 25
- **Framework**: Spring Boot 4.1.1
- **Build Tool**: Gradle 9.7.0
- **Database**: PostgreSQL 18
- **Database Migrations**: Liquibase
- **Testing**: JUnit 5, Mockito, Spring MockMvc, Testcontainers PostgreSQL

---

## Local Development

Start PostgreSQL with Docker Compose and run the application:

```bash
docker compose up -d
./gradlew bootRun
```

Or use the Testcontainers development mode:

```bash
./gradlew bootTestRun
```

See `docs/development/setup.md` for complete environment details.

---

## Build & Test Commands

```bash
./gradlew build -x test
./gradlew test
./gradlew check
```

See `docs/development/testing.md` for testing conventions and suite organization.

---

## Documentation

The authoritative documentation lives under [`docs/`](docs/):

- [Documentation Map](docs/README.md)
- [Product & Design Decisions](docs/Samvaad%20Product%20%26%20Design%20Decisions.md)
- [Technical Design](docs/Samvaad%20Technical%20Design.md)
- [Implementation Roadmap](docs/Samvaad%20Implementation%20Roadmap.md)
- [Architecture Current State](docs/architecture/current-state.md)
- [User & Profile API Contract](docs/api/current-user-profile-api.md)
- [Security Posture](docs/security/current-security-posture.md)
- [Architecture Decision Records](docs/adr/)
- [Developer Guides](docs/development/)
- [Agent Guidelines](AGENTS.md)

---

## Next Implementation Area

**Realtime messaging transport**: STOMP/WebSocket delivery is the next major messaging area; read state, mutation, reply, offline/reconnect, and relationship-control work remain later/deferred until explicitly scoped.
