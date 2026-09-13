# Samvaad Documentation

The documentation set tracks the implementation state of the Samvaad Server and the locked product/architecture decisions.

Current implementation phases:
- Phase 0 — Design reconciliation: complete.
- Phase 1 — Authentication & authorization boundary: complete.
- Phase 2 — Exact username discovery: complete.
- Phase 3 — Friend request vertical slice: complete.
- Phase 4 — Direct messaging vertical slice: complete.

Phase 4 currently provides the first direct-message write path: authenticated accepted friends can send plain-text messages through `POST /api/conversations/direct/messages`. Conversations are unique per unordered participant pair; creation and first-message persistence are atomic; messages have server timestamps and per-conversation sequence numbers; client request UUIDs provide idempotent replay handling.

The next implementation area is conversation/message reads and listing. Realtime delivery, read state, message mutations, replies, reconnect/offline synchronization, blocking, unfriend, mute, archive, and other later features remain deferred until explicitly scoped.

Friend-gated profile visibility also remains deferred; it was intentionally not activated as part of Phase 3.

See the implementation roadmap, architecture/security documents, API contract, and ADRs for the current state and locked decisions.
