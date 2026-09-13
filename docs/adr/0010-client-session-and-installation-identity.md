# ADR 0010: Client session and installation identity

## Status

Accepted.

## Decision

Authentication identity is based on the authenticated user and persisted server-side session. Installation identity is not an authentication primitive.

A `Session` represents a distinct authenticated client session. The JWT `sub` identifies the user and `sid` identifies the persisted session; the server remains authoritative for both.

Client/device/installation information may be retained as session metadata when it is useful to the client or operational model, but an installation identifier is optional at the architecture level and must not be required merely to authenticate a client.

Different clients have different lifecycle semantics:

- Admin web UI is a web application and does not inherently represent an installed client.
- Normal web chat clients do not inherently have a stable installation identity.
- TUI clients do not inherently require an installation identity.
- Portable desktop executables do not inherently require an installation identity.
- Android and iOS applications naturally have an installation/device lifecycle, so an installation identifier may be useful there, especially for future push-notification and device-management features.

Therefore Samvaad models the relationship conceptually as:

```text
User
  ├── Session
  ├── Session
  └── Session

Optional:
Session → client/device/installation metadata
```

rather than making installation a mandatory parent identity for sessions.

## Current implementation boundary

The current implementation still requires an `installationId` in the login/session-creation contract and persists it as session metadata. This is a transitional implementation detail, not the architectural identity boundary established by this ADR.

Before client development, the installation-ID/session contract must be audited and refactored so clients that do not naturally have an installation concept are not forced to invent one. The implementation and API documentation must then be reconciled together.

## Consequences

- Session remains the authentication and revocation boundary.
- A user can have multiple independent authenticated sessions without an installation hierarchy being required.
- Client-specific metadata can evolve without changing the authentication model.
- Mobile clients can adopt installation identity when their lifecycle and future push/device-management requirements justify it.
- Web, TUI, and portable desktop clients can authenticate without manufacturing an artificial installation identity after the transitional contract is removed.
- Existing implementation and API contracts must not be documented as already refactored; the migration is a separate implementation task.

## Relationship to ADR 0003

ADR 0003 remains the authoritative decision for authentication, persisted sessions, refresh-token rotation, revocation, and session-capacity enforcement. This ADR clarifies the separate meaning and lifecycle of client/installation identity and does not replace the session-based authentication boundary.
