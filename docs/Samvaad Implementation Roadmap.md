# Samvaad Implementation Roadmap

## Phase 0 — Design reconciliation
COMPLETE.

## Phase 1 — Authentication & Authorization Boundary
COMPLETE.

## Phase 2 — Exact Username Discovery
COMPLETE.

## Phase 3 — Friend Request Vertical Slice
COMPLETE.

## Phase 4 — Direct Messaging Vertical Slice
COMPLETE.

Implemented direct conversation persistence, friendship authorization, atomic first-message creation, server sequencing/timestamps, request-ID idempotency, and authenticated HTTP send.

## Phase 5 — Conversation/Message Reads & Listing
COMPLETE.

Implemented:
- `GET /api/conversations/direct?limit=20&offset=0`
- `GET /api/conversations/direct/{conversationId}/messages?afterSequence=0&limit=20`
- participant-only reads
- recent-activity ordering for conversation lists
- offset/limit conversation pagination
- exclusive per-conversation sequence cursor for messages
- 404-before-403 read behavior
- nullable other-participant username after admin deletion

## Realtime V1 — STOMP/WebSocket Message Delivery
COMPLETE.

Implemented:
- Spring WebSocket/STOMP support.
- WebSocket endpoint `/ws`.
- `/app` application destination prefix.
- `/app/chat.send` message command.
- `/topic/conversations/{conversationId}` conversation broadcast destination.
- STOMP `CONNECT` authentication using the existing access JWT and persisted session validation.
- `Authorization: Bearer <JWT>` as the CONNECT credential header.
- authenticated `userId`/`sessionId` principal association.
- participant-only conversation subscription authorization.
- participant message send through the existing `MessageService` business logic.
- server-authoritative sender identity, sequence, timestamp, and request ID.
- persistence before broadcast.
- Spring simple broker for the first slice.
- integration coverage for connect authentication, revoked sessions, participant/non-participant subscriptions, send/broadcast, persistence, idempotency, and failed-send behavior.

Implementation boundary: the HTTP and STOMP transports enter the same message business logic. The STOMP layer does not maintain separate persistence, sequencing, friendship, or idempotency rules.

## Pre-client architecture cleanup

NEXT.

Audit and refactor the current installation-ID/session contract so authentication remains session-based and installation identity becomes optional client/device metadata where appropriate. The goal is to avoid forcing web, TUI, or portable desktop clients to invent an installation identity while retaining a natural installation concept for mobile clients where useful. Reconcile the login/session implementation and API documentation after the refactor.

## Later / deferred

- reconnect/missed-event synchronization
- offline queues
- persistent read state/read receipts
- typing/presence
- delivery receipts
- push notifications
- message edits/deletes/replies
- blocking, unfriend, mute, archive
- horizontal scaling and external brokers
- general event bus
- end-to-end encryption
