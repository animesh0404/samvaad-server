# Current Security Posture

## Implemented

- JWT access authentication with persisted session validation.
- Refresh-token rotation with session-bound persistence and expiry.
- Per-session logout and revocation.
- ADMIN/USER role authorization.
- Server-authoritative caller identity via `CurrentUser`.
- Self-only profile writes.
- Admin-only user provisioning, listing, and deletion.
- Exact username discovery requires authentication and returns a restricted DTO.
- Friend-request endpoints require authentication.
- Friend-request mutations enforce sender/recipient ownership server-side: only recipients accept/reject and only senders cancel.
- Self friend-requests are rejected.
- Duplicate, reverse-direction pending, already-friends, and terminal-transition conflicts are rejected with `409` where applicable.
- Direct messaging endpoints require authentication.
- Direct message sender identity is derived from `CurrentUser`; sender identity cannot be supplied by the client.
- Direct message creation requires an accepted friendship via the existing `areFriends(a, b)` relationship check.
- Friendship authorization is performed before conversation lookup/creation, preventing unauthorized message attempts from creating conversation state.
- Self-messaging is rejected.
- Direct conversation uniqueness and message sequence/request-ID uniqueness are backed by database constraints.
- Client request UUID replay is idempotent only for the original sender/conversation; foreign reuse is rejected with `409`.
- Conversation listing requires authentication and scopes results to conversations in which the caller is a participant.
- Message reads require authentication and participant authorization; a known non-participant receives `403` and an unknown conversation receives `404`.
- Message reads use server-owned sequence numbers for ordering/cursor progression; clients cannot choose message sequence values.
- Read endpoints validate pagination bounds and reject invalid values with `400` rather than silently clamping them.

## Deferred / future

- Realtime transport authentication and delivery.
- Friend-gated profile visibility.
- Read state, message mutations, replies, reconnect/offline synchronization.
- Blocking, unfriend, mute, archive, and related relationship controls.
- Rate limiting.
- Stable machine-readable error codes.
- Full audit policy.
