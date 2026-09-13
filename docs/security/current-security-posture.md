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
- Self-messaging is rejected.
- Direct conversation uniqueness and message sequence/request-ID uniqueness are backed by database constraints.
- Client request UUID replay is idempotent only for the original sender/conversation; foreign reuse is rejected with `409`.

## Deferred / future

- Friend-gated profile visibility.
- Realtime transport authentication and delivery.
- Conversation/message read and listing authorization beyond the send-message slice.
- Read state, message mutations, replies, reconnect/offline synchronization.
- Blocking, unfriend, mute, archive, and related relationship controls.
- Rate limiting.
- Stable machine-readable error codes.
- Full audit policy.
