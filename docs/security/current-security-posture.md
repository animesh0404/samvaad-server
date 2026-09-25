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


## E2EE enrollment and device security boundary

The V1 E2EE enrollment flow distinguishes first-device bootstrap from recovery-required enrollment.

- First-device bootstrap is allowed only for an account that has never completed a trusted E2EE device enrollment.
- If an existing account has one or more ACTIVE E2EE devices, a new device must receive explicit approval from an already trusted device before becoming fully trusted.
- If an existing account has previously completed E2EE enrollment but has zero ACTIVE E2EE devices, the account is in recovery-required enrollment. Account credentials alone do not complete authentication; an unused account-level recovery code is required.
- Recovery-code consumption is atomic with successful recovery enrollment.
- Device revocation terminates all authenticated sessions belonging to that device, so stale sessions cannot bypass cryptographic device revocation.

The enrollment state machine defined by ADR 0018 is implemented in the server-side E2EE foundation. The implemented boundary covers device enrollment state, trusted-device approval authorization, recovery-required enrollment, session-to-device binding, device revocation/session termination, one-time prekey handling, recipient device discovery, and account-level recovery-code handling.

This is not yet full E2EE message confidentiality. Signal/Sesame session establishment, client-side cryptographic state, encrypted message envelopes/mailboxes, ciphertext message persistence, history synchronization, and encrypted history backup/restoration remain unimplemented. The current direct-message content path therefore remains server-readable plaintext until the subsequent messaging-encryption slice replaces it.

## Deferred / future

- Reconnect/missed-event synchronization and offline queues.
- Persistent read state/read receipts.
- Typing/presence, delivery receipts, and push notifications.
- Message mutations/replies.
- Blocking, unfriend, mute, archive.
- Rate limiting and stable machine-readable error codes.
- Full audit/event-history policy beyond operational logging.
- Horizontal scaling/external brokers and a crash-safe outbox.
- Full E2EE message implementation and rollout; the device/prekey foundation is implemented and architecture remains governed by ADR 0018.
