# ADR 0003: Authentication and session model

## Status

Accepted; not yet implemented.

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
revealing the session-capacity state to the unauthenticated client. Existing
authenticated sessions receive a real-time security notification that a login
attempt was blocked because the maximum active-session capacity was reached.
The blocked login client does not receive that notification.

Session management supports revoking the current session, revoking another
specific session, and revoking all other sessions while keeping the current
session active. There is deliberately no separate "logout all devices"
operation in V1.

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

## Consequences

Authentication work must introduce durable session and refresh-token state and
must bind authenticated connections to session-derived identity. The realtime
transport must be able to deliver security events to authenticated sessions,
including the blocked-login notification. JWT signing, claims, key storage, and
key rotation remain deferred implementation decisions.

The session-capacity check must use a transaction and a database-level lock on
the user's row (or an equivalent serialization mechanism) around counting active
sessions and creating a new session. This is preferred over an application-only
synchronized block because multiple application instances may handle logins.

## Source material

- `docs/Samvaad Product & Design Decisions.md`, sections 3–4
- `docs/Samvaad Technical Design.md`, sections 12–14
