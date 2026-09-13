# ADR 0009: Realtime STOMP/WebSocket transport

## Status

Accepted and implemented for Realtime V1.

## Context

Phases 1–5 established authentication/session validation, friendship authorization, direct-message persistence, idempotency, and HTTP conversation/message reads. Realtime delivery adds a transport without creating a second messaging business-logic path.

## Decision

Samvaad uses **WebSocket with STOMP** through Spring's WebSocket/STOMP support.

### Transport and destinations

- WebSocket endpoint: `/ws`
- Client-to-server application prefix: `/app`
- Direct-message command: `/app/chat.send`
- Conversation broadcast destination: `/topic/conversations/{conversationId}`

### Authentication

STOMP `CONNECT` carries the existing Samvaad access JWT in the native `Authorization` header as `Bearer <JWT>`. The server validates the JWT and persisted session using the same server-authoritative identity/session rules as HTTP authentication. No separate WebSocket login mechanism exists.

The authenticated STOMP connection is associated with the authenticated `userId` and `sessionId` through an `AuthenticatedUser` principal.

The WebSocket HTTP handshake is permitted through the servlet security chain because authentication is performed at STOMP `CONNECT`; handshake access alone does not authorize application messaging.

### Authorization

Conversation subscriptions and message commands are participant-authorized. A client may subscribe to a conversation destination only when authenticated and authorized as a participant. A `chat.send` command is authenticated and authorized for its target conversation by the existing messaging service.

Unknown and non-participant conversation subscriptions are rejected identically so subscription attempts do not reveal conversation existence.

Domain authorization remains in the messaging/domain service layer rather than being duplicated as business logic in the transport layer.

### Message command and event

The client sends only:

```json
{
  "conversationId": "...",
  "content": "...",
  "requestId": "..."
}
```

The client does not supply authoritative `senderUserId`, `sequenceNumber`, or `serverTimestamp`.

The realtime handler delegates to the existing message business operation. Persistence, friendship authorization, sequencing, timestamp generation, and request-ID idempotency remain shared with HTTP messaging.

A message is broadcast only after successful persistence. The broadcast contains the server-authoritative persisted `MessageDto`.

### Broker

Realtime V1 uses Spring's simple in-memory broker. Redis, Kafka, RabbitMQ, broker relay, and other external brokers are not part of this slice.

### Reliability boundary

Broadcast-after-commit is sufficient for the first single-instance slice but is not crash-safe across process failure because there is no outbox. Crash recovery, replay, and horizontal-delivery guarantees remain future work.

## Implemented Realtime V1 slice

The implementation proves:

1. WebSocket endpoint availability.
2. STOMP `CONNECT` authentication using the existing access JWT/session model.
3. Authenticated STOMP principal association.
4. Participant-only conversation subscription.
5. Authenticated participant message send through the existing messaging business logic.
6. Persistence before broadcast.
7. Delivery of the persisted message event to connected conversation subscribers.
8. Idempotent replay through the shared message service.
9. No persistence/broadcast for failed sends.

## Explicitly deferred

- reconnect/missed-event synchronization
- offline queues
- persistent read state/read receipts
- typing/presence
- delivery receipts
- push notifications
- message edits/deletes/replies
- blocking/unfriend/mute/archive
- horizontal scaling or external brokers
- end-to-end encryption
- a general event bus

## Consequences

Realtime delivery is a transport concern layered over the existing server-authoritative messaging model. The same authorization, sequence, timestamp, and idempotency invariants apply whether a message enters through HTTP or STOMP.

The first realtime implementation remains small while leaving broker, synchronization, reliability, and later messaging lifecycle work for separate slices.
