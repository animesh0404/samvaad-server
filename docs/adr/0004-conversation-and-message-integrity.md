# ADR 0004: Conversation and message integrity

## Status

Accepted; Phase 4 persistence subset implemented.

## Decision

Direct conversations are unique per normalized participant pair and the
database enforces that invariant. Self-chat is valid in the broader messaging
model; however, the Phase 4 direct-message API intentionally rejects self-chat
with `403` as part of the initial product slice. This is an implementation
boundary to revisit if self-chat is explicitly brought into scope.

Conversation creation and the first message are atomic; uniqueness conflicts
resolve by retrieving the winner and persisting the losing request's message.

For the direct-message write path, friendship authorization is evaluated before
conversation lookup or creation. An unauthorized send therefore cannot create
conversation state as a side effect of the rejected request.

Messages have durable request-ID idempotency, server-authoritative sequence and
time, permanent history, terminal tombstones, and sender-owned last-write-wins
edits. Read, archive, and mute state are per user; read position is monotonic.
Blocking is directional but makes the conversation read-only for both users.

The Phase 4 persistence subset currently implements direct conversation
uniqueness, authorization-before-conversation-creation, atomic
conversation/first-message creation, durable request-ID idempotency, server
sequence/time, and database-backed race correctness. The later message
lifecycle behaviors remain deferred.

## Consequences

Messaging persistence and transactions must use database constraints for race
correctness. Do not replace these invariants with client coordination or
distributed locking. Authorization must also be performed before creating or
mutating conversation state. The implemented Phase 4 schema uses a normalized
pair unique constraint, a per-conversation sequence unique constraint, and a
unique message request ID. The exact later wire formats and remaining lifecycle
features remain deferred.

## Source material

- `docs/Samvaad Product & Design Decisions.md`, sections 5–10
- `docs/Samvaad Technical Design.md`, sections 3–11 and 19
