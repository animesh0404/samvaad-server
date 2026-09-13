# Samvaad Server

Samvaad is a server-first messaging system built around server-authoritative state, simple invariants, and explicit contracts.

This repository contains the backend server service. The client interface is developed as a separate consumer of stable server APIs and does not dictate server design.

---

## Current Status

The project is in active backend development. **Phases 1–5 and Realtime V1 are complete and tested. Realtime V1 has also been manually verified end-to-end.**

Currently implemented:

- **User and Profile Slice**: User creation (`POST /api/users`), authenticated user lookup, profile retrieval, and self-service profile updates.
- **User Administration**: ADMIN-only user listing and hard deletion.
- **Authentication & Sessions**: Login with username or email and password, persisted sessions, session-bound JWT access tokens, rotating refresh tokens, five-active-session enforcement, and logout/session revocation.
- **Account Self-Service**: Users can change their own email and password. Username is immutable after creation.
- **User Discovery**: Authenticated exact username lookup (`GET /api/users/lookup?username=...`).
- **Friend Requests**: Authenticated send, incoming/outgoing pending lists, recipient accept/reject, sender cancellation, duplicate/reverse-direction protection, and re-request after rejected/cancelled requests.
- **Friendship**: An accepted friend-request row represents the friendship; `areFriends(a,b)` is used by direct messaging authorization.
- **Direct Messaging**: Authenticated friends can send plain-text messages through HTTP. Direct conversations are unique per unordered user pair; messages receive server sequence/timestamp values and client request UUIDs provide idempotent replay handling.
- **Conversation Reads**: Participants can list direct conversations through `GET /api/conversations/direct` with offset/limit pagination and recent-activity ordering.
- **Message Reads**: Participants can fetch messages through `GET /api/conversations/direct/{conversationId}/messages` using the exclusive `afterSequence` cursor.
- **Realtime Messaging V1**: WebSocket + STOMP transport at `/ws`, authenticated STOMP `CONNECT` using the existing access JWT/session model, participant-only conversation subscriptions, `/app/chat.send`, `/topic/conversations/{conversationId}`, reuse of the existing message persistence/idempotency/authorization logic, and broadcast only after successful persistence. The first slice uses Spring's in-memory simple broker. A manual Alice-to-Bob smoke test has verified live delivery without polling.
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

- **Java**: 25
- **Framework**: Spring Boot 4.1.1
- **Build Tool**: Gradle 9.7.0
- **Database**: PostgreSQL 18
- **Database Migrations**: Liquibase
- **Realtime**: Spring WebSocket/STOMP with simple broker
- **Testing**: JUnit 5, Mockito, Spring MockMvc, Testcontainers PostgreSQL

---

## Local Development

```bash
docker compose up -d
./gradlew bootRun
```

Or:

```bash
./gradlew bootTestRun
```

See `docs/development/setup.md` for complete environment details.

## Build & Test Commands

```bash
./gradlew build -x test
./gradlew test
./gradlew check
```

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
- [Verification Records](docs/verification/)
- [Agent Guidelines](AGENTS.md)

---

## Next Implementation Area

**Realtime follow-on work**: reconnect/missed-event synchronization and persistent read state are the next natural messaging concerns. Message mutation, replies, relationship controls, and horizontal scaling remain separately deferred.
