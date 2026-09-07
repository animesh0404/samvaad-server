# ADR 0001: Server-authoritative identity and state

## Status

Accepted.

## Decision

Samvaad is server-first. The server is authoritative for identity,
authorization, sessions, conversations, messages, ordering, timestamps, read
state, blocking, archive/mute state, and synchronization. Authenticated
identity is derived from the server-side session context, not a client-supplied
user identifier.

## Consequences

Clients consume server contracts and may validate for usability, but client
state or timestamps do not replace server validation or authority. Features
that introduce identity or state must preserve this boundary.

## Source material

- `docs/Samvaad Product & Design Decisions.md`, sections 1 and 2.1
- `docs/Samvaad Technical Design.md`, sections 1 and 19
