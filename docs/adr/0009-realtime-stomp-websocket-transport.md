# ADR 0009: Realtime STOMP/WebSocket transport

## Status

Accepted; design locked for the first realtime implementation slice. No realtime transport is implemented yet.

## Context

Phases 1–5 established authentication/session validation, friendship authorization, direct-message persistence, idempotency, and HTTP conversation/message reads. The next implementation area is realtime delivery. The realtime design should add a transport without creating a second messaging business-logic path.

## Decision

Samvaad will use **WebSocket with STOMP** for realtime messaging, using Spring's WebSocket/STOMP support.

### Transport and destinations

- WebSocket endpoint: `/ws`
- Client-to-server application destination prefix: `/app`
- Direct-message command: `/app/chat.send`
- Conversation broadcast destination: `/topic/conversations/{conversationId}`

The first realtime slice is intentionally limited to conversation message delivery.

### Authentication

STOMP `CONNECT` carries the existing Samvaad access JWT. The server validates the JWT and its persisted session using the same server-authoritative identity model as HTTP authentication. No separate WebSocket login mechanism is introduced.

The authenticated STOMP connection is associated with the authenticated `userId` and `sessionId`.

### Authorization

Conversation subscriptions and message commands are participant-authorized. A client may subscribe to a conversation destination only when authenticated and authorized as a participant of that conversation. A `chat.send` command must likewise be authenticated and authorized for the target conversation.

Domain authorization remains in the messaging/domain service layer rather than being duplicated in the transport layer.

### Message command and event

The client sends only the data required to request a message operation, conceptually:

```json
{
  "conversationId": "...",
  "content": "...",
  "requestId": "..."
}
```

The client does not supply authoritative `senderUserId`, `sequenceNumber`, or `serverTimestamp`.

The realtime handler reuses the existing message business operation and persistence invariants. A message is broadcast only after successful persistence. The broadcast represents the server-authoritative persisted message, including its server-generated identity, sequence, timestamp, and request ID.

### Broker

The first implementation uses Spring's simple broker. No Redis, Kafka, RabbitMQ, or external broker is introduced by this slice. Broker replacement remains a later deployment/scaling decision.

### Reuse of existing messaging logic

HTTP and STOMP are transports into the same message business logic. The realtime implementation must not create a parallel persistence, sequencing, friendship-authorization, or idempotency implementation.

Conceptually:

```text
HTTP controller ─────┐
                     ├──> message service ──> persistence
STOMP handler ───────┘                 │
                                       └──> broadcast after commit
```

## First implementation slice

The first realtime vertical slice will prove:

1. WebSocket endpoint availability.
2. STOMP `CONNECT` authentication using the existing access JWT/session model.
3. Authenticated STOMP principal association.
4. Participant-only conversation subscription.
5. Authenticated participant message send through the existing messaging business logic.
6. Persistence before broadcast.
7. Delivery of the persisted message event to connected conversation subscribers.

Two connected clients representing the two existing conversation participants should be sufficient to demonstrate the end-to-end flow.

## Explicitly deferred

This ADR does not design or implement:

- reconnect/missed-event synchronization
- offline queues
- persistent read state/read receipts
- typing or presence
- delivery receipts
- push notifications
- message edits/deletes/replies
- blocking/unfriend/mute/archive
- horizontal scaling or external brokers
- end-to-end encryption
- a general event bus

These remain separate future slices unless explicitly brought into scope.

## Consequences

Realtime delivery becomes a transport concern layered over the existing server-authoritative messaging model. The same authorization, sequence, timestamp, and idempotency invariants continue to apply regardless of whether a message enters through HTTP or STOMP.

The first realtime implementation can therefore remain small while leaving room for later broker, synchronization, and messaging lifecycle work.
