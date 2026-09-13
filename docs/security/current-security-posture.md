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

## Deferred / future

- Friend-gated profile visibility.
- Messaging authorization and realtime transport authentication.
- Rate limiting.
- Stable machine-readable error codes.
- Full audit policy.
