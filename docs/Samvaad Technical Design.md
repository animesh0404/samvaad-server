# Samvaad — Technical Design

> **Status:** Phases 1–5 and Realtime V1 are implemented. The realtime transport is now part of the current server architecture.
> **Focus:** Backend/domain/protocol/persistence/concurrency.
> **Rule:** Architectural invariants are fixed by ADRs; low-level mechanics are decided when implementation creates a concrete need.

---

# 1. Architecture Direction

Samvaad is server-first. The server owns authoritative state for identity, authentication, authorization, sessions, relationships, conversations, messages, ordering, canonical timestamps, read state, and synchronization. Clients consume server contracts and do not become the source of truth.

HTTP and STOMP are transports into the same messaging business logic. They must not maintain separate persistence, sequencing, friendship-authorization, or idempotency implementations.

# 2. Domain Model

## User

`userId`, `username`, `email`, password/authentication data, account lifecycle data, and role. User ID and username are immutable; username is unique and case-sensitive. V1 users are admin-provisioned.

## UserProfile

Profile data is separate from identity. Profile writes are self-only; profile visibility rules remain separately defined and friend-gated visibility is still deferred.

## Session

Session is first-class server state referenced by JWT `sid`. Every authenticated HTTP request validates the JWT and persisted session. The same JWT/session validation model is applied when a STOMP connection is authenticated.

## FriendRequest / Friendship

An accepted friend-request row represents friendship. `areFriends(a,b)` is the relationship authorization input for distinct-user direct messaging.

## Conversation

Direct conversations normalize the participant pair and enforce uniqueness in the database. The current model contains `conversationId`, `participantA`, `participantB`, `lastSequenceNumber`, `createdAt`, and `updatedAt`.

## Message

Messages have server-owned identity, conversation, sender, sequence number, content, server timestamp, and client request UUID. The server controls sequence and timestamp values. The HTTP read API uses sequence as its exclusive cursor.

# 3. Database Invariants

- usernames are unique
- non-null emails are unique case-insensitively
- direct participant pairs are unique
- message `(conversation, sequence)` is unique
- request IDs are unique
- conversation creation and first-message persistence are atomic
- message request UUIDs are replay-safe

# 4. Authorization Model

Authentication establishes the caller identity. Authorization establishes what the caller may do.

Direct-message creation between distinct users requires accepted friendship. Conversation/message HTTP reads require conversation participation. Realtime conversation subscriptions and sends require authenticated participation in the target conversation.

# 5. Direct Messaging

HTTP send:

```text
authenticate
  ↓
identify recipient
  ↓
verify friendship
  ↓
find/create conversation
  ↓
persist message
```

The service owns sequencing, timestamps, request-ID idempotency, and persistence. Unauthorized sends cannot create conversation state.

# 6. HTTP Read APIs

`GET /api/conversations/direct?limit=20&offset=0` lists participant conversations ordered by `updatedAt DESC`, then `conversationId ASC`.

`GET /api/conversations/direct/{conversationId}/messages?afterSequence=0&limit=20` reads participant messages in ascending server sequence. Unknown conversation is `404`; known non-participant is `403`.

# 7. Realtime STOMP/WebSocket

## Transport

- WebSocket endpoint: `/ws`
- STOMP application prefix: `/app`
- simple broker prefix: `/topic`
- send command: `/app/chat.send`
- conversation destination: `/topic/conversations/{conversationId}`

## CONNECT authentication

The WebSocket HTTP handshake is permitted through the servlet security chain, but the STOMP connection is not considered authenticated until `CONNECT` is processed. `StompAuthInterceptor` reads `Authorization: Bearer <JWT>`, validates the access token and persisted session using the existing JWT/session rules, and sets an `AuthenticatedUser` principal containing `userId`, role, and `sessionId`.

There is no separate WebSocket login mechanism.

## Subscription authorization

A `SUBSCRIBE` to `/topic/conversations/{conversationId}` is allowed only when the authenticated principal is a participant. Unknown and non-participant conversation subscriptions are rejected identically so subscription attempts do not reveal conversation existence.

## Send command

The client supplies only:

```json
{
  "conversationId": "...",
  "content": "...",
  "requestId": "..."
}
```

The client does not supply authoritative sender identity, sequence, or server timestamp. `ChatController` delegates to `MessageService.sendMessageToConversation`, which reuses the existing friendship authorization, persistence, sequencing, timestamp, and idempotency logic.

## Broadcast boundary

The persisted `MessageDto` is broadcast to `/topic/conversations/{conversationId}` only after the message service successfully returns from its transactional operation. V1 has no outbox, so this is not crash-recoverable across a process failure; that reliability/scaling problem is deferred.

## Broker

Realtime V1 uses Spring's in-memory simple broker. Redis, Kafka, RabbitMQ, broker relay, horizontal scaling, and a general event bus are deferred.

# 8. Realtime Flow

```text
STOMP CONNECT
   ↓
JWT + persisted-session validation
   ↓
AuthenticatedUser principal
   ↓
SUBSCRIBE /topic/conversations/{id}
   ↓
participant authorization
   ↓
SEND /app/chat.send
   ↓
MessageService
   ↓
friendship + idempotency + sequencing + persistence
   ↓
transaction succeeds
   ↓
MessageDto broadcast to conversation topic
```

# 9. Concurrency and Failure Boundary

The database remains authoritative for conversation uniqueness, message sequencing, and request-ID uniqueness. A failed STOMP send persists and broadcasts nothing. An idempotent replay returns the existing persisted message through the shared service path.

The current session-validation logic is intentionally duplicated between the HTTP JWT filter and STOMP interceptor to avoid changing established HTTP authentication behavior during the realtime slice; a later auth refactor may extract the common validation logic.

# 10. Deferred Realtime Work

Reconnect/missed-event synchronization, offline queues, read state/read receipts, typing/presence, delivery receipts, push notifications, message edits/deletes/replies, blocking/unfriend/mute/archive, horizontal scaling/external brokers, end-to-end encryption, and a general event bus are outside Realtime V1.

# 11. Technical Invariants

1. Server is authoritative.
2. Authenticated identity comes from server authentication context.
3. Direct messaging between distinct users requires accepted friendship.
4. A direct participant pair has at most one conversation.
5. Conversation + first message creation is atomic.
6. A request UUID cannot create two messages.
7. Server sequence numbers determine message order.
8. Client time is never authoritative.
9. HTTP and STOMP message sends use the same message business logic.
10. Realtime broadcast occurs only after successful persistence.
11. Conversation subscriptions are participant-only.
12. STOMP `CONNECT` uses the existing access JWT plus persisted session validation.
13. The simple broker is an in-memory V1 choice, not the horizontal-scaling architecture.
