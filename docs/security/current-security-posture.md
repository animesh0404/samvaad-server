# Current Security Posture

## Implemented

- JWT access authentication with persisted session validation.
- Refresh-token rotation with session-bound persistence and expiry.
- Per-session logout and revocation.
- ADMIN/USER role authorization and server-authoritative caller identity.
- Self-only profile writes and admin-only user administration.
- Exact username discovery requires authentication and returns a restricted DTO.
- Friend-request ownership and lifecycle authorization.
- Direct messaging requires accepted friendship; sender identity is server-derived.
- Direct conversation and message uniqueness are database-backed.
- HTTP conversation listing and message reads are participant-scoped.
- Known non-participant reads return `403`; unknown conversations return `404`.
- STOMP `CONNECT` authentication uses the existing access JWT plus persisted session validation. The STOMP credential is `Authorization: Bearer <JWT>`.
- The WebSocket handshake endpoint is servlet-security-permitted only so the STOMP layer can perform token authentication; the handshake itself does not grant application access.
- Authenticated STOMP connections receive an `AuthenticatedUser` principal containing server-authoritative user/session identity.
- Conversation subscriptions are participant-only. Unknown and non-participant conversation subscriptions are rejected identically to avoid revealing conversation existence through subscription behavior.
- STOMP message sends derive sender identity from the authenticated principal; the client cannot provide a sender ID.
- STOMP sends reuse the existing friendship authorization, sequence, timestamp, persistence, and request-ID idempotency logic.
- Realtime broadcast occurs only after successful persistence; failed sends persist and broadcast nothing.
- Operational logging is intended to capture meaningful business/application and security/authentication events with traceable correlation context, while avoiding routine low-level CRUD logging and sensitive authentication secrets.
- Operational log files use configurable size-based rolling, compressed archives, and bounded retention; the default retention target is 50 rolled files.

## Deferred / future

- Reconnect/missed-event synchronization and offline queues.
- Persistent read state/read receipts.
- Typing/presence, delivery receipts, and push notifications.
- Message mutations/replies.
- Blocking, unfriend, mute, archive.
- Rate limiting and stable machine-readable error codes.
- Full audit/event-history policy beyond operational logging.
- Horizontal scaling/external brokers and a crash-safe outbox.
- End-to-end encryption.
