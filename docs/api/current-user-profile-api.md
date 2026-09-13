# Current User / Profile API

## Username discovery

Implemented endpoint:

`GET /api/users/lookup?username={username}`

Authentication is required. Matching is exact and case-insensitive. Missing or blank input returns `400`; an unknown username returns `404`. The response is restricted to discovery-safe identity fields (`userId` and `username`) and does not expose email, password/hash, role, sessions, tokens, or internal security metadata.

Self-lookup is allowed. Friendship is not required for lookup.

## Friend-request relationship

Friend-request lifecycle is implemented separately under `/api/friend-requests`. An accepted request represents the friendship used by direct messaging authorization. Friend-gated profile visibility is not activated by Phase 3.

## Direct messaging

### Send message

`POST /api/conversations/direct/messages`

Request body:

```json
{
  "username": "recipient",
  "content": "hello",
  "requestId": "client-generated-uuid"
}
```

Authentication is required. The caller identity comes from the authenticated server context; sender identity is not accepted from the request body. The recipient is resolved by exact, case-insensitive username lookup. The two users must be accepted friends, and self-messaging is rejected.

Friendship authorization is evaluated before conversation lookup or creation. An unauthorized send therefore cannot create conversation state as a side effect of the rejected request. After authorization, the endpoint finds or creates the single direct conversation for the unordered participant pair and persists the first/subsequent message atomically. Message content is plain text. The server supplies the message timestamp and monotonic conversation sequence number. `requestId` is a client-provided UUID used for idempotency: the original owner replay receives the existing message with `200`, while foreign reuse returns `409`.

A newly persisted message returns `201`. Validation failures return `400`; unauthenticated requests return `401`; non-friends or self-messages return `403`; an unknown recipient returns `404`; conflicting request UUID reuse returns `409`.

### List direct conversations

`GET /api/conversations/direct?limit=20&offset=0`

Authentication is required. The response is a JSON array of conversation DTOs with `conversationId`, `otherParticipantUserId`, `otherParticipantUsername`, `lastSequenceNumber`, and `updatedAt`.

`limit` defaults to `20` and must be between `1` and `100`. `offset` defaults to `0` and must be non-negative. Invalid pagination returns `400`; non-numeric request parameters are handled as Spring `400` responses. Conversations are ordered by recent `updatedAt` descending, with `conversationId` ascending as a deterministic tiebreaker.

The caller sees only conversations where they are one of the two participants. The other participant's current username is resolved for the response; it is `null` if that user has been admin-deleted.

### Read conversation messages

`GET /api/conversations/direct/{conversationId}/messages?afterSequence=0&limit=20`

Authentication is required and the caller must be a participant in the conversation. `afterSequence` defaults to `0`, must be non-negative, and is an exclusive sequence cursor. `limit` defaults to `20` and must be between `1` and `100`. Results are ordered by ascending server-assigned `sequenceNumber`.

An unknown conversation returns `404`. A known conversation requested by a non-participant returns `403`. Invalid pagination returns `400`; non-numeric request parameters are handled as Spring `400` responses.

Message reads do not re-check friendship. Current authorization is based on conversation participation because unfriend/block lifecycle does not yet exist.

Conversation/message reads are HTTP-only in this slice. Realtime delivery, read state, message mutations, replies, and offline/reconnect behavior remain outside the implemented scope.

## Profile PATCH

`PATCH /api/users/{userId}/profile` is implemented as a self-only update. Omitted fields remain unchanged, present non-null fields replace the stored value, and explicitly present `null` fields clear the stored value.
