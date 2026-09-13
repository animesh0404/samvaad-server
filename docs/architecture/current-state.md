# Current Architecture State

## Implemented

- PostgreSQL persistence with Liquibase-managed schema migrations.
- JPA entities and auditing.
- User provisioning, administration, JWT authentication, persisted sessions, refresh-token rotation, and per-session logout/revocation.
- ADMIN/USER roles and server-side authorization.
- Self-service email/password changes and profile PATCH semantics.
- Exact case-insensitive username discovery.
- Friend-request lifecycle and accepted-request-as-friendship model.
- Direct conversation persistence with normalized participant pairs and database uniqueness.
- Plain-text direct messages with server timestamps, monotonic per-conversation sequences, and request-ID idempotency.
- Atomic conversation/first-message creation and database-backed concurrency invariants.
- HTTP direct message send, conversation listing, and participant-only message reads.
- WebSocket/STOMP realtime transport:
  - `/ws` WebSocket endpoint
  - `/app` application prefix
  - `/topic` simple broker
  - `/app/chat.send` send command
  - `/topic/conversations/{conversationId}` conversation delivery
  - `Authorization: Bearer <JWT>` on STOMP `CONNECT`
  - existing JWT + persisted-session validation
  - authenticated `AuthenticatedUser` principal containing user/session identity
  - participant-only conversation subscriptions
  - shared message persistence/idempotency/authorization/sequencing logic
  - persistence before broadcast

## Architecture diagrams

- `current-authentication-session.puml` — JWT/session validation and authorization boundary.
- `current-user-profile.puml` — user/profile persistence model.
- `current-domain-model.puml` — current domain relationships.
- `current-data-model.puml` — PostgreSQL relationship/message tables and uniqueness invariants.
- `current-messaging-write-flow.puml` — HTTP direct-message write path.
- `current-messaging-read-flow.puml` — HTTP conversation/message reads.
- `current-realtime-message-flow.puml` — STOMP connect, subscription, send, persistence, and broadcast flow.

## Realtime V1 boundary

The WebSocket handshake is servlet-security-permitted, while actual authentication occurs on STOMP `CONNECT`. Subscription authorization is participant-only. Unknown and non-participant subscription destinations are rejected identically to avoid existence leakage. STOMP sends enter the existing message service and broadcast only after successful persistence.

## Explicitly deferred

- reconnect/missed-event synchronization
- offline queues
- persistent read state/read receipts
- typing/presence, delivery receipts, push notifications
- message editing/deletion/replies
- blocking, unfriend, mute, archive
- horizontal scaling/external brokers/general event bus
- end-to-end encryption
- rate limiting and stable machine-readable error codes
- dedicated conversation recency field if `updatedAt` later proves insufficient
- friend-gated profile visibility

## Known V1 limitation

Broadcast-after-commit is not crash-safe across process failure because Realtime V1 has no outbox. The simple broker is in-memory and intended for the first single-instance slice only.
