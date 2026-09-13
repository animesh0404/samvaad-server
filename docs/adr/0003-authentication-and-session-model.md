# ADR 0003: Authentication and session model

## Status

Accepted.

## Decision

Samvaad V1 uses admin-provisioned accounts. There is no public self-registration.
The initial administrator is created by Liquibase as a database seed during
schema initialization. Application startup does not provision or mutate the
administrator, and there are no runtime bootstrap-admin environment variables.
The seeded account uses the fixed administrator identity and a known default
password; first-login enforcement for changing that default password remains a
separate deferred UI/backend decision.

Passwords use BCrypt with generated salt. Plaintext passwords are neither
persisted nor logged.

V1 authentication uses:

- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`

Login verifies the stored password hash, creates a persisted client session, and
issues a JWT access token plus a rotating refresh token.

Access tokens have a one-day lifetime. Refresh tokens are session-scoped and use
a 30-day sliding expiry. A successful refresh rotates the refresh token and
issues a new access token. The previously consumed refresh token is rejected.

Each successful login is associated with a distinct client session. A user may
have multiple active sessions across devices or clients; there is no primary
device concept in V1. Samvaad permits at most five active client sessions per
user.

The five-session limit is enforced transactionally. Login session allocation
serializes on the user whose session capacity is being checked, so concurrent
login attempts cannot both observe an available slot and exceed the five-session
limit.

When the five-session limit is already reached, a new login attempt fails without
revealing the session-capacity state to the unauthenticated client. The current
implementation publishes a `LoginBlockedDueToSessionLimitEvent` containing the
attempt and client metadata. Realtime delivery of that security event to other
authenticated sessions is deferred until the realtime transport exists.

Normal logout revokes the current session only; other sessions remain active.
Session revocation invalidates the associated authenticated connection.

Authentication failures must not expose account-enumeration information. Unknown
usernames/emails and session-state failures use a generic authentication failure.

## Authorization boundary

Authentication proves identity; authorization determines whether that identity
may perform an operation.

Role and ownership enforcement is governed by ADR 0007. V1 has exactly one role
per user: `ADMIN` or `USER`. There is no role-change API, and callers
cannot choose a role during normal user provisioning.

In particular:

- administrators may create users, list users, retrieve permitted user records,
  delete users, and read any user's profile
- administrators cannot mutate another user's username, email, password, or profile
- administrators may mutate their own profile, email, and password through normal self-service
- standard users may mutate only their own email, password, and profile
- standard users may read their own profile and, once an accepted friendship
  exists, the other user's profile
- friendship grants profile-read visibility only; it never grants profile-edit
  permission
- username is immutable after account creation

The server derives authenticated `userId` from session context rather than
trusting a caller-supplied identity.

For every authenticated request, the access JWT is validated for signature and
expiration, then its `sid` is resolved against the server-side session. The
session must exist, must not be revoked, and must not be past
`refresh_token_expires_at`. The JWT `sub` must match the session's user. A valid
JWT with a missing, revoked, or expired session is rejected with `401`.

Session validation is performed per authenticated request to preserve immediate
revocation semantics; V1 does not require a long-lived session-validation cache.

The existing login identifier behavior remains username-or-email based. Username
is still the exact messaging discovery key; email is not the V1 messaging
discovery key.

Protected-endpoint authorization uses the following HTTP semantics:

- unauthenticated access: `401 Unauthorized`
- authenticated but not permitted: `403 Forbidden`
- invalid, expired, revoked, or session-missing access token: `401`
- validation failure: `400`
- duplicate username or email: `409`

The existing API error body shape is preserved for this slice. Cross-user denial
uses `403` rather than `404`.

## Current implementation evidence

- `POST /api/auth/login` verifies the stored password hash, serializes on the
  user row while checking session capacity, creates a persisted session, and
  returns an access token, refresh token, expiry, and session identifier.
- `POST /api/auth/refresh` validates the presented refresh-token hash against the
  persisted session, rejects revoked/expired sessions, rotates the stored hash,
  extends the refresh expiry, and issues a new session-bound access token.
- `POST /api/auth/logout` revokes the current persisted session; subsequent
  authenticated requests and refresh attempts for that session are rejected.
- Session records persist the refresh-token hash and client/session metadata.
- Active-session capacity is set to five and concurrent login allocation is
  covered by an integration test.
- The blocked-login security event is published when capacity is reached and is
  covered by authentication tests.
- User email changes are self-service, case-insensitively unique, and do not
  revoke existing sessions.
- User password changes are self-service, require the current password, persist
  only a new BCrypt hash, and do not revoke existing sessions.

## Client/session identity clarification

The authentication boundary is the user plus persisted session. Installation
identity is a separate client/device concern and is governed by ADR 0010.

`installationId` is optional during login/session creation. Missing, null, blank,
or whitespace-only values result in no installation metadata being stored. A
nonblank value is retained as optional session metadata. The database column
`sessions.installation_id` is nullable following Liquibase migration
`011-make-installation-id-nullable.yaml`.

## Spring Boot default user

Spring Boot logs a generated security password and an
`inMemoryUserDetailsManager` bean at startup. This is
`UserDetailsServiceAutoConfiguration` fallback behavior: it activates because
the application defines no `AuthenticationManager`,
`AuthenticationProvider`, `UserDetailsService`,
`AuthenticationManagerResolver`, or `JwtDecoder` bean and sets no
`spring.security.user.*` properties.

That fallback is intentionally unused. Samvaad authentication uses `UserRepo`
plus `PasswordEncoder` for login, `JwtAuthenticationFilter` plus `SessionRepo`
for request authentication, persisted sessions for refresh and
logout/revocation, and `SecurityContextHolder` populated by the JWT filter for
authorization. No part of the flow calls `UserDetailsService` or
`AuthenticationManager`.

Decision: leave the fallback alone. Do not add a `UserDetailsService`,
`AuthenticationProvider`, or `AuthenticationManager` merely to suppress the
startup message, do not introduce a second username/password path, and do not
exclude Spring Security auto-configuration to remove the log. A
`UserDetailsService` adapter would also mismatch login semantics (the login
identifier is username-or-email, versus `loadUserByUsername`) and could bypass
session-capacity locking, login events, and revocation checks. The generated
password has no effect on Samvaad API authentication.

## Remaining implementation gaps

- realtime delivery of blocked-login security notifications
- final JWT signing algorithm, production key storage, and key rotation
- first-login enforcement for changing the seeded administrator's known default password

## Consequences

Authentication introduces durable session and refresh-token state and must bind
authenticated connections to session-derived identity. The realtime transport
must eventually deliver security events and reject commands after session
revocation.

The session-capacity check must use a transaction and database-level locking (or
equivalent serialization) around counting active sessions and creating a new
session so multiple application instances cannot exceed the limit.

## Source material

- `docs/adr/0007-user-provisioning-and-authorization.md`
- `docs/adr/0010-client-session-and-installation-identity.md`
- `docs/Samvaad Product & Design Decisions.md`
- `docs/Samvaad Technical Design.md`
