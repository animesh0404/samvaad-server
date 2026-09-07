# ADR 0003: Authentication and session model

## Status

Accepted; not yet implemented.

## Decision

Passwords use BCrypt with generated salt and plaintext passwords are neither
persisted nor logged. Samvaad uses one-day JWT access tokens and rotating,
session-scoped refresh tokens with a 30-day sliding expiry. Refresh is reactive;
an already consumed refresh token is rejected. Logout and revocation affect the
current session, while other sessions remain active.

## Consequences

Authentication work must introduce durable session and refresh-token state and
must bind authenticated connections to session-derived identity. JWT signing,
claims, key storage, and key rotation remain deferred implementation decisions.

## Source material

- `docs/Samvaad Product & Design Decisions.md`, sections 3–4
- `docs/Samvaad Technical Design.md`, sections 12–14
