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
- `User` persistence exists with `userId`, username, and email.
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

The maintained source diagram is
[current-user-profile.puml](diagrams/current-user-profile.puml).

## Next planned slice

The authentication protocol skeleton and user/authentication vertical slice are
not complete. They must add BCrypt credential handling, login, session
persistence, JWT access tokens, rotating refresh tokens, logout, and session
revocation in accordance with ADR 0003 and the original records.

## Intentionally deferred

Exact JWT signing/claims/key management, protocol framing, reconnect behavior,
pagination cursor mechanics, rate limiting, and full audit policy remain
deferred. V1 application-level encryption is also deferred; see ADR 0005.

## Known implementation gaps

- Authentication, authorization, sessions, JWTs, and refresh tokens are absent.
- Messaging, conversations, blocking, read state, archive/mute, and transport
  protocol are absent.
- Display-name fallback is designed but not implemented.
- Profile PATCH cannot yet distinguish omitted fields from explicit `null`.
- Current error responses do not yet expose the planned stable machine-readable
  error-code contract.
