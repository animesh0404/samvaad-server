# Current Architecture State

## Implemented

- PostgreSQL persistence with Liquibase-managed schema migrations.
- JPA entities and auditing.
- User provisioning and administration.
- JWT authentication with persisted, session-bound refresh tokens.
- Access-token/session validation and per-session logout/revocation.
- Maximum five active sessions per user.
- Liquibase-seeded initial admin account.
- ADMIN and USER roles with server-side authorization.
- Self-service email and password changes.
- Self-only profile reads/writes, including PATCH omitted/null field-presence semantics.
- Exact case-insensitive username discovery via `GET /api/users/lookup?username={username}` with a restricted discovery DTO.
- Friend-request lifecycle: send, incoming/outgoing pending lists, accept, reject, and sender cancellation.
- Friend-request states: `PENDING`, `ACCEPTED`, `REJECTED`, `CANCELLED`.
- An accepted friend-request row represents the friendship; an internal `areFriends(a, b)` query is used by direct messaging authorization.
- Direct conversation persistence with normalized participant pairs and database-enforced pair uniqueness.
- Plain-text direct message persistence with server-generated timestamps, monotonic per-conversation sequence numbers, and unique client request UUIDs for idempotency.
- Atomic conversation creation and first-message persistence; conversation creation races are resolved through database uniqueness and winner retrieval.
- Authenticated friend-gated message sending via `POST /api/conversations/direct/messages`; friendship authorization is performed before conversation lookup/creation.
- Idempotent replay of an already-owned request UUID returns the original message; reuse of a request UUID by another message owner returns `409 Conflict`.
- Authenticated direct conversation listing via `GET /api/conversations/direct` with offset/limit pagination, recent-activity ordering, and deterministic conversation-ID tiebreaking.
- Participant-only message reads via `GET /api/conversations/direct/{conversationId}/messages` using an exclusive per-conversation sequence cursor and ascending sequence ordering.
- Conversation read authorization is participant-only; friendship is not re-checked for reads because participation is the current access boundary.
- Unknown conversation reads return `404`; known non-participant reads return `403`; invalid pagination returns `400`.
- Conversation DTOs resolve the other participant's current username and allow it to be `null` when that user has been admin-deleted.

## Architecture diagrams

The current implementation is also captured visually in the PlantUML diagrams under `docs/architecture/diagrams/`:

- `current-authentication-session.puml` — JWT/session validation, persisted sessions, revocation, and endpoint authorization boundary.
- `current-user-profile.puml` — user/profile persistence model.
- `current-domain-model.puml` — current User, Profile, Session, FriendRequest, Conversation, and Message domain relationships.
- `current-data-model.puml` — current PostgreSQL relationship/message tables and key uniqueness invariants.
- `current-messaging-write-flow.puml` — implemented HTTP direct-message write path and transaction/idempotency flow.
- `current-messaging-read-flow.puml` — implemented HTTP conversation-list and participant-only message-read flows, including pagination semantics.

These diagrams describe implemented behavior only; future STOMP/WebSocket transport is intentionally not represented as current architecture.

## Next work

Realtime messaging transport and delivery using STOMP/WebSocket.

## Explicitly deferred

- First-login enforcement for changing the seeded admin default password.
- Email verification / OTP lifecycle.
- Exact JWT signing/key-management policy.
- Realtime transport authentication and delivery protocol.
- Reconnect/offline synchronization protocol.
- General pagination cursors.
- Rate limiting.
- Full audit-policy definition.
- V1 application-level encryption.
- Friend-gated profile visibility remains deferred; the Phase 3 friend-request slice did not activate it.
- Read state, message editing/deletion, replies, blocking, unfriend, mute, archive, and other later messaging/social features.

## Known gaps

- Realtime transport auth and blocked-login delivery behavior.
- Friend-gated profile visibility.
- Display-name fallback policy.
- Stable machine-readable error codes.
- A dedicated conversation recency field if future requirements make JPA `updatedAt` insufficient.
