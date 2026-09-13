# Current User / Profile API

## Username discovery

`GET /api/users/lookup?username={username}` is authenticated, exact, case-insensitive, and returns only discovery-safe identity fields.

## Friend-request relationship

Friend-request lifecycle is implemented under `/api/friend-requests`. An accepted request represents the friendship used by direct messaging authorization. Friend-gated profile visibility remains deferred.

## Direct messaging

### HTTP send

`POST /api/conversations/direct/messages`

```json
{
  "username": "recipient",
  "content": "hello",
  "requestId": "client-generated-uuid"
}
```

The caller identity comes from server authentication. Accepted friendship is required; self-messaging is rejected. The service owns conversation creation, message sequence/timestamp, and request-ID idempotency.

### HTTP conversation list

`GET /api/conversations/direct?limit=20&offset=0`

Participant-only. `limit` is 1–100; `offset` is non-negative. Results use recent `updatedAt` descending and `conversationId` ascending as deterministic tiebreaker.

### HTTP message read

`GET /api/conversations/direct/{conversationId}/messages?afterSequence=0&limit=20`

Participant-only. `afterSequence` is a non-negative exclusive server sequence cursor; `limit` is 1–100. Results are ascending by server sequence. Unknown conversation is `404`; known non-participant is `403`.

## Realtime messaging — STOMP/WebSocket V1

### WebSocket endpoint

`/ws`

The HTTP handshake is allowed through the servlet security chain so STOMP can perform token authentication. Application authentication occurs on the STOMP `CONNECT` frame.

### CONNECT authentication

Send the existing access token as:

```text
Authorization: Bearer <access-jwt>
```

The server validates the JWT and persisted session using the same identity/session rules as HTTP authentication. No separate WebSocket login exists.

### Conversation subscription

Subscribe to:

```text
/topic/conversations/{conversationId}
```

The authenticated caller must be a participant. Unknown and non-participant destinations are rejected identically so subscription attempts do not reveal conversation existence.

### Send message

Send to:

```text
/app/chat.send
```

Payload:

```json
{
  "conversationId": "...",
  "content": "hello",
  "requestId": "client-generated-uuid"
}
```

The client does not provide sender identity, sequence number, or server timestamp. The STOMP handler delegates to the existing `MessageService`, so friendship authorization, sequencing, timestamps, persistence, and request-ID idempotency remain shared with HTTP messaging.

After successful persistence, the server broadcasts the persisted `MessageDto` to:

```text
/topic/conversations/{conversationId}
```

Failed sends are not broadcast. Replays use the existing idempotency behavior.

### Broker boundary

Realtime V1 uses Spring's in-memory simple broker. Reconnect/missed-event synchronization, read state, message mutation/replies, presence/receipts/notifications, external brokers, horizontal scaling, and offline behavior remain deferred.

## Profile PATCH

`PATCH /api/users/{userId}/profile` is self-only. Omitted fields remain unchanged, present non-null fields replace values, and explicitly present `null` fields clear values.
