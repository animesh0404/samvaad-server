# ADR 0004: Conversation and message integrity

## Status

Accepted; Phase 4 persistence subset and Phase 5 HTTP read subset implemented.

## Decision

Direct conversations are unique per normalized participant pair and the database enforces that invariant. Self-chat is valid in the broader messaging model; however, the Phase 4 direct-message API intentionally rejects self-chat with `403` as part of the initial product slice. This is an implementation boundary to revisit if self-chat is explicitly brought into scope.

Conversation creation and the first message are atomic; uniqueness conflicts resolve by retrieving the winner and persisting the losing request's message.

For the direct-message write path, friendship authorization is evaluated before conversation lookup or creation. An unauthorized send therefore cannot create conversation state as a side effect of the rejected request.

Messages have durable request-ID idempotency, server-authoritative sequence and time, permanent history, terminal tombstones, and sender-owned last-write-wins edits. Read, archive, and mute state are per user; read position is monotonic. Blocking is directional but makes the conversation read-only for both users.

The Phase 5 read subset implements HTTP conversation listing and message reads. Conversation lists are scoped to the authenticated participant and use offset/limit pagination, ordered by `updatedAt` descending with `conversationId` as a deterministic ascending tiebreaker. Message reads are scoped to participants and use the durable per-conversation sequence number as an exclusive cursor (`afterSequence`), returning messages in ascending sequence order. No general cursor framework is introduced by this slice.

## Consequences

Messaging persistence and transactions must use database constraints for race correctness. Do not replace these invariants with client coordination or distributed locking. Authorization must also be performed before creating or mutating conversation state.

Conversation/message reads remain participant-authorized HTTP reads in this slice. Friendship is not re-checked on reads because unfriend/block lifecycle is not implemented. Unknown conversations return `404`; known non-participant conversations return `403`.

Pagination limits are validated rather than clamped. Conversation lists use offset/limit because the current model has no dedicated stable conversation cursor. Message reads use the existing monotonic sequence because it is already an architecture-native ordering primitive.

The exact later realtime wire formats, read-state protocol, and remaining message lifecycle features remain deferred.

## Source material

- `docs/Samvaad Product & Design Decisions.md`, sections 5–10
- `docs/Samvaad Technical Design.md`, sections 3–11 and 19
