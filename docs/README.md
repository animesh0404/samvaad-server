# Samvaad Documentation

The documentation set tracks the implementation state of the Samvaad Server and the locked product/architecture decisions.

Current implementation phases:
- Phase 0 — Design reconciliation: complete.
- Phase 1 — Authentication & authorization boundary: complete.
- Phase 2 — Exact username discovery: complete.
- Phase 3 — Friend request vertical slice: complete.
- Phase 4 — Direct messaging vertical slice: complete.
- Phase 5 — Conversation/message reads and listing: complete.
- Realtime V1 — STOMP/WebSocket message delivery: complete and manually verified end-to-end.

Current server capabilities also include the completed **Friends List API** (`GET /api/friends`), which exposes the authenticated user's accepted friends using the existing accepted-FriendRequest-as-friendship model. See [Friends API Contract](api/friends-api.md).

Phase 4 provides the direct-message write path. Phase 5 adds authenticated HTTP conversation/message reads without realtime assumptions.

Realtime V1 now provides:
- WebSocket endpoint `/ws` with STOMP.
- `/app` application prefix and `/topic` simple broker.
- `/app/chat.send` with `{conversationId, content, requestId}`.
- `/topic/conversations/{conversationId}` conversation delivery.
- STOMP `CONNECT` authentication using the existing access JWT plus persisted session validation through `Authorization: Bearer <JWT>`.
- Participant-only conversation subscriptions.
- Sender identity derived from the authenticated STOMP principal.
- Reuse of the existing message persistence, friendship authorization, sequencing, timestamps, and idempotency logic.
- Broadcast only after the message service successfully persists/commits the message.

A manual smoke test has verified authenticated Alice and Bob clients connecting, subscribing to the same conversation, and Alice's realtime message being delivered to Bob without polling. See [Realtime V1 Smoke Test](verification/realtime-smoke-test.md) for the recorded evidence.

The first realtime slice uses Spring's in-memory simple broker and is intentionally single-instance V1 behavior. Reconnect/missed-event synchronization, persistent read state, typing/presence, delivery receipts, push notifications, message mutation/replies, relationship controls, external brokers/horizontal scaling, and end-to-end encryption remain deferred.

Friend-gated profile visibility also remains deferred; it was intentionally not activated as part of Phase 3.

See the implementation roadmap, architecture/security documents, API contract, and ADRs for the current state and locked decisions.
