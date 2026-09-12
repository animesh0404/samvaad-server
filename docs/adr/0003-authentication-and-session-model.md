# ADR 0003: Authentication and session model

## Status

Accepted; partially implemented.

The core HTTP authentication/session foundation described below is implemented:
BCrypt password verification, persisted client sessions, one-day JWT access
tokens, session-scoped rotating refresh tokens, five-session capacity
serialization, and blocked-login event publication. The remaining lifecycle and
transport behaviors are explicitly tracked as implementation gaps rather than
being treated as implemented merely because the design is accepted.

## Decision

Passwords use BCrypt with generated salt and plaintext passwords are neither
persisted nor logged. Samvaad uses one-day JWT access tokens and rotating,
session-scoped refresh tokens with a 30-day sliding expiry. Refresh is reactive;
an already consumed refresh token is rejected. Logout and revocation affect the
current session, while other sessions remain active.

Each successful login is associated with a distinct client session. A user may
have multiple active sessions across devices or clients; there is no primary
device concept in V1. Samvaad permits at most five active client sessions per
user.

The five-session limit is enforced transactionally. Login session allocation
serializes on the user whose session capacity is being checked, so concurrent
login attempts cannot both observe an available slot and exceed the five-session
limit. Expired or revoked sessions do not count toward the active-session limit.

When the five-session limit is already reached, a new login attempt fails without
revealing the session-capacity state to the unauthenticated client. The current
implementation publishes a `LoginBlockedDueToSessionLimitEvent` containing the
attempt and client metadata. Realtime delivery of that security event to other
authenticated sessions is not yet implemented because the realtime transport is
not yet implemented.

Session management supports revoking the current session, revoking another
specific session, and revoking all other sessions while keeping the current
session active. There is deliberately no separate "logout all devices"
operation in V1. These session-management operations are design requirements;
the corresponding HTTP lifecycle endpoints and authorization enforcement are
not yet implemented.

Refresh tokens are bound to their session and rotated on successful refresh;
an already consumed or revoked refresh token is rejected. The refresh expiry is
sliding, so a successful refresh establishes a new 30-day expiry window. Only

the current refresh-token hash is persisted for a session; refresh-token history
or token-family persistence is not required in V1.

Session records retain client/session metadata useful for session management
and security analysis, including an installation identifier, client type/name,
last-seen IP address, and user-agent where available. IP address and user-agent
are signals rather than hard session identity, and session validity is not
revoked solely because an IP address changes.

Authentication failures must not expose account-enumeration information. Unknown
usernames/emails and session-state failures use a generic authentication failure.
For an otherwise valid account, an incorrect password may return a specific
incorrect-password error to support normal credential-typing UX, without
revealing additional account information. Admin-authenticated user-management
endpoints may expose their own appropriate validation errors.

## Current implementation evidence

- `POST /api/auth/login` verifies the stored password hash, serializes on the
  user row while checking session capacity, creates a persisted session, and
  returns an access token, refresh token, expiry, and session identifier.
- `POST /api/auth/refresh` validates the presented refresh-token hash against the
  persisted session, rejects revoked/expired sessions, rotates the stored hash,
  extends the refresh expiry, and issues a new session-bound access token.
- Session records persist the refresh-token hash and client/session metadata.
- Active-session capacity is set to five and concurrent login allocation is
  covered by an integration test.
- The blocked-login security event is published when capacity is reached and is
  covered by authentication tests.

## Remaining implementation gaps

- Password-based user registration is not yet implemented as the authentication
  flow; the existing user-creation endpoint is still a separate user/profile
  operation.
- Authorization enforcement for protected operations is not yet complete.
- Logout and session-revocation operations are not yet implemented.
- Realtime delivery of blocked-login security notifications is not yet
  implemented.
- JWT signing algorithm, claims beyond the current implementation, production
  key storage, and key rotation remain implementation/deployment decisions.

## Consequences

Authentication work introduces durable session and refresh-token state and must
bind authenticated connections to session-derived identity. The realtime
transport must eventually be able to deliver security events to authenticated
sessions, including the blocked-login notification.

The session-capacity check must use a transaction and a database-level lock on
the user's row (or an equivalent serialization mechanism) around counting active
sessions and creating a new session. This is preferred over an application-only
synchronized block because multiple application instances may handle logins.

## Source material

- `docs/Samvaad Product & Design Decisions.md`, sections 3–4
- `docs/Samvaad Technical Design.md`, sections 12–14
