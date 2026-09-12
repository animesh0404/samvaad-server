# Current implementation state

This document is a concise implementation snapshot. It does not replace the
three original design records in `docs/`, which preserve planned behavior and
historical reasoning.

## Planned design

The original design defines a server-authoritative messaging system with
session-derived identity, BCrypt credentials, JWT/refresh-token sessions,
direct-conversation integrity, durable messaging, and per-user conversation
state. See [the ADR index](../adr/README.md) for concise decision summaries.

## Implemented now

- PostgreSQL persistence, Liquibase migrations, JPA, and JPA auditing are in use.
- `User` persistence exists with `userId`, username, email, and password hash.
- The current `UserService.createUser` flow creates a `UserProfile` for each newly created user, and that profile shares the user's identifier.
- Profile fields include names, display name, bio, avatar URL, and status message.
- User provisioning (`POST /api/users`, ADMIN-only), user lookup, profile GET, and profile PATCH endpoints exist.
- API DTOs are mapped separately from JPA entities.
- The current auditor reports `"system"` as the actor.
- Authentication login is implemented at `POST /api/auth/login` with BCrypt password verification, persisted sessions, session-bound JWT access tokens, and refresh tokens.
- Refresh is implemented at `POST /api/auth/refresh` with persisted refresh-token hashes, expiry/revocation checks, and refresh-token rotation.
- Access tokens currently have a one-day lifetime; refresh expiry is 30 days and slides forward on successful refresh.
- A maximum of five active sessions per user is enforced transactionally by serializing on the user row while active session capacity is checked and a session is created.
- When session capacity is exhausted, the authentication service publishes a `LoginBlockedDueToSessionLimitEvent`. The event is currently an internal application event; realtime delivery to authenticated sessions is not yet implemented.
- The initial `ADMIN` is seeded by Liquibase during database initialization. The application has no runtime admin bootstrap configuration or startup mutation path.
- V1 has exactly two roles, `ADMIN` and `USER`. Provisioned users are forced to `USER`; callers cannot choose a role.
- Admin-only provisioning requires a password, forces the `USER` role, hashes the password with BCrypt, and never returns credential material.
- `password_hash` is `NOT NULL`; the schema does not permit passwordless users.
- Requests are authenticated from the access JWT: signature and expiry are validated, the `sid` is resolved to a persisted session, the session must exist, must not be revoked, and must not be past `refresh_token_expires_at`, and the JWT `sub` must match the session's user. Missing/revoked/expired/mismatched sessions are rejected with `401`.
- Profile authorization is enforced from the authenticated session identity: users may read and write their own profile, an `ADMIN` may read any profile but may not write another user's profile, and cross-user access is rejected with `403`.
- `POST /api/auth/logout` revokes the current session only; other sessions remain active.
- `GET /api/users` provides ADMIN-only user listing without credential/session secrets.
- `DELETE /api/users/{userId}` provides ADMIN-only hard deletion. An administrator cannot delete themselves; deletion removes sessions before profile/account removal.
- `PATCH /api/users/{userId}/email` provides self-service email change. Email is case-insensitively unique, duplicate email returns `409`, and existing sessions remain valid.
- `PATCH /api/users/{userId}/password` provides self-service password change. The current password is required and verified; the new password is BCrypt-hashed and existing sessions remain valid.
- Profile PATCH distinguishes omitted fields from explicitly present `null` values: omitted fields remain unchanged, non-null values replace existing values, and explicit `null` clears the corresponding field.

Maintained source diagrams:

- [Current User/Profile persistence model](diagrams/current-user-profile.puml)
- [Current authentication/session model](diagrams/current-authentication-session.puml)

## Next planned work

The current account/authentication boundary is implemented and is ready to move to
user discovery. The next product slice is exact username discovery for
authenticated users, followed by the friend-request vertical slice.

The planned realtime protocol skeleton (CONNECT → LOGIN → LOGIN_SUCCESS) is still
pending; the current authentication endpoints are HTTP endpoints rather than the
planned realtime protocol.

The broader messaging vertical slices are still pending: conversations, messages,
per-user conversation state, and transport protocol behavior.

## Intentionally deferred

- first-login enforcement for changing the seeded administrator's known default password
- email verification/OTP lifecycle
- exact JWT signing/claims/key management
- protocol framing
- reconnect behavior
- pagination cursor mechanics
- rate limiting
- full audit policy
- V1 application-level encryption; see ADR 0005

## Known implementation gaps

- Realtime transport authentication and realtime delivery of blocked-login security notifications are not implemented.
- Relationship-based profile visibility (friend-gated reads) is not yet implemented; non-admin cross-user profile reads are denied.
- Messaging, conversations, blocking, read state, archive/mute, and transport protocol are absent.
- Display-name fallback is designed but not implemented.
- Current error responses do not yet expose the planned stable machine-readable error-code contract.
