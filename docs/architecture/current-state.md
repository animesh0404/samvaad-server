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

- PostgreSQL persistence, Liquibase migrations, JPA, and JPA auditing are in
  use.
- `User` persistence exists with `userId`, username, email, and password hash.
- The current `UserService.createUser` flow creates a `UserProfile` for each
  newly created user, and that profile shares the user's identifier. The
  database does not require every `User` to have a profile: it permits a user
  without one, while requiring every `UserProfile` to reference an existing
  user.
- Profile fields include names, display name, bio, avatar URL, and status
  message.
- User creation and lookup, profile GET, and profile PATCH endpoints exist.
- API DTOs are mapped separately from JPA entities.
- The current auditor reports `"system"` as the actor.
- Authentication login is implemented at `POST /api/auth/login` with BCrypt
  password verification, persisted sessions, session-bound JWT access tokens,
  and refresh tokens.
- Refresh is implemented at `POST /api/auth/refresh` with persisted refresh-
  token hashes, expiry/revocation checks, and refresh-token rotation.
- Access tokens currently have a one-day lifetime; refresh expiry is 30 days and
  slides forward on successful refresh.
- A maximum of five active sessions per user is enforced transactionally by
  serializing on the user row while active session capacity is checked and a
  session is created.
- When session capacity is exhausted, the authentication service publishes a
  `LoginBlockedDueToSessionLimitEvent`. The event is currently an internal
  application event; realtime delivery to authenticated sessions is not yet
  implemented.

Maintained source diagrams:

- [Current User/Profile persistence model](diagrams/current-user-profile.puml)
- [Current authentication/session model](diagrams/current-authentication-session.puml)

## Next planned work

The authentication/session foundation is implemented, but the full
authentication boundary is not complete. Remaining authentication work includes
password-based registration, authorization enforcement, logout/session
revocation operations, and realtime session authentication/security-event
delivery.

The planned protocol skeleton (CONNECT → LOGIN → LOGIN_SUCCESS) is also still
pending; the current authentication endpoints are HTTP endpoints rather than the
planned realtime protocol.

The broader messaging vertical slices are still pending: conversations,
messages, per-user conversation state, and transport protocol behavior.

## Intentionally deferred

Exact JWT signing/claims/key management, protocol framing, reconnect behavior,
pagination cursor mechanics, rate limiting, and full audit policy remain
deferred. V1 application-level encryption is also deferred; see ADR 0005.

## Known implementation gaps

- Password-based registration is not yet part of the authentication flow.
- Authorization enforcement and logout/session revocation are not yet complete.
- Realtime transport authentication and realtime delivery of blocked-login
  security notifications are not implemented.
- Messaging, conversations, blocking, read state, archive/mute, and transport
  protocol are absent.
- Display-name fallback is designed but not implemented.
- Profile PATCH cannot yet distinguish omitted fields from explicit `null`.
- Current error responses do not yet expose the planned stable machine-readable
  error-code contract.
