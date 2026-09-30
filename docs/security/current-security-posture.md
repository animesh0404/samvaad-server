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
- STOMP `CONNECT` authentication uses the existing access JWT plus persisted session validation, and additionally requires the session to be bound to an `ACTIVE` E2EE device owned by the caller. The STOMP credential is `Authorization: Bearer <JWT>`.
- The WebSocket handshake endpoint is servlet-security-permitted only so the STOMP layer can perform token authentication; the handshake itself does not grant application access.
- Authenticated STOMP connections receive a `StompDevicePrincipal` containing server-authoritative user/session/device identity (`AuthenticatedUser` remains HTTP-only). The device identity is derived server-side and never supplied by the client.
- Subscriptions are authorized by exact match against the connection's own `/topic/devices/{deviceId}`. Any other destination is rejected identically to avoid revealing whether another device exists. Session/device liveness is revalidated on `SUBSCRIBE`.
- There is no client STOMP send handler. E2EE messages are submitted exclusively via HTTPS; the server fans out one persisted per-device ciphertext envelope to each recipient device topic only after the database transaction commits. Replayed submissions produce no second event, and broker failure never fails persistence.
- E2EE submission bounds are enforced: at most 65,536 decoded ciphertext bytes per envelope and at most 10 envelopes per submit. Sync cursors are valid from `0` through the conversation `lastSequenceNumber`.
- Session revocation terminates the associated live WebSocket connections server-side; device revocation terminates every live connection bound to that device's sessions.
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

The E2EE message-confidentiality path is implemented for the JVM/TUI client boundary: real Signal/PQXDH session establishment, local private-key custody, encrypted per-device envelopes, ciphertext mailbox/history transport, and synchronization are covered by the current implementation and tests. The server remains blind to message content. The legacy plaintext direct-message transport has been removed; messaging is E2EE-ciphertext only. **The server's current durable ciphertext-history implementation is a transition state: ADR 0025 targets device-owned durable history and bounded server delivery buffering.** Browser/Android adapters, Primary/Companion role enforcement, Primary-to-Companion history synchronization, bounded retention/eviction, and encrypted history backup/restoration remain future work.

## Deferred / future

- Reconnect/backfill UX beyond mailbox/history/cursor catch-up, and offline queues beyond the per-device E2EE mailbox.
- Persistent read state/read receipts.
- Typing/presence, delivery receipts, and push notifications.
- Message mutations/replies.
- Blocking, unfriend, mute, archive.
- Rate limiting and stable machine-readable error codes.
- Full audit/event-history policy beyond operational logging.
- Horizontal scaling/external brokers and a crash-safe outbox.
- Full E2EE rollout to browser/Android clients and encrypted history backup/restoration; server-side ciphertext transport, device/prekey foundation, and realtime fan-out are implemented and architecture remains governed by ADR 0018.
