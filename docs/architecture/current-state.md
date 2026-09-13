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
- An accepted friend-request row represents the friendship; an internal `areFriends(a, b)` query is available for later authorization.

## Next work

Phase 4: direct messaging vertical slice, using established friendship as the authorization boundary.

## Explicitly deferred

- First-login enforcement for changing the seeded admin default password.
- Email verification / OTP lifecycle.
- Exact JWT signing/key-management policy.
- Realtime transport authentication and delivery protocol.
- Reconnect/offline synchronization protocol.
- Pagination cursors.
- Rate limiting.
- Full audit-policy definition.
- V1 application-level encryption.
- Friend-gated profile visibility remains deferred; the Phase 3 friend-request slice did not activate it.

## Known gaps

- Realtime transport auth and blocked-login delivery behavior.
- Friend-gated profile visibility.
- Messaging and message mutations.
- Display-name fallback policy.
- Stable machine-readable error codes.
