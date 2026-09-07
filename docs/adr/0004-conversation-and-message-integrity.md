# ADR 0004: Conversation and message integrity

## Status

Accepted; not yet implemented.

## Decision

Direct conversations are unique per normalized participant pair and the
database enforces that invariant. Self-chat is valid. Conversation creation and
the first message are atomic; uniqueness conflicts resolve by retrieving the
winner and persisting the losing request's message.

Messages have durable request-ID idempotency, server-authoritative sequence and
time, permanent history, terminal tombstones, and sender-owned last-write-wins
edits. Read, archive, and mute state are per user; read position is monotonic.
Blocking is directional but makes the conversation read-only for both users.

## Consequences

Messaging persistence and transactions must use database constraints for race
correctness. Do not replace these invariants with client coordination or
distributed locking. The exact schema, indexes, allocation mechanism, and wire
format remain deferred.

## Source material

- `docs/Samvaad Product & Design Decisions.md`, sections 5–10
- `docs/Samvaad Technical Design.md`, sections 3–11 and 19
