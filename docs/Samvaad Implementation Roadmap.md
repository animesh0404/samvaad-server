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

COMPLETE.

The installation-ID/session contract was audited and refactored so authentication remains session-based and installation identity is optional client/device metadata. `POST /api/auth/login` accepts clients that omit `installationId`; blank/whitespace values normalize to null, while nonblank values remain supported. Liquibase migration `011-make-installation-id-nullable.yaml` makes the persisted session field nullable. Focused integration coverage verifies login without installation identity across web/TUI/desktop platforms, mobile metadata preservation when supplied, refresh, logout/revocation, session limits, HTTP validation, and STOMP `CONNECT` without installation identity. The identity-critical login transaction no longer performs installation-ID normalization because session creation and session-limit enforcement do not depend on that metadata.

## Operational logging

COMPLETE.

Operational logging is implemented with correlation/trace context, selective service-level `@OperationalLog` instrumentation, explicit domain/security events, secret avoidance, and configuration-driven size-based rolling file retention. The default active file size is 10MB with 50 retained rolled files; archives are compressed. Operational logging remains distinct from a future full audit/event-history system.

## Next implementation area

TUI client development: build the first client as a thin consumer of the stable server authentication, session, HTTP messaging, and STOMP/WebSocket contracts. The TUI must not introduce a separate authentication/session model or manufacture an installation identity merely to authenticate.

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
