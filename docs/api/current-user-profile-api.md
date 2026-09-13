# Current User / Profile API

## Username discovery

Implemented endpoint:

`GET /api/users/lookup?username={username}`

Authentication is required. Matching is exact and case-insensitive. Missing or blank input returns `400`; an unknown username returns `404`. The response is restricted to discovery-safe identity fields (`userId` and `username`) and does not expose email, password/hash, role, sessions, tokens, or internal security metadata.

Self-lookup is allowed. Friendship is not required for lookup.

## Friend-request relationship

Friend-request lifecycle is implemented separately under `/api/friend-requests`. An accepted request represents the friendship used by direct messaging authorization. Friend-gated profile visibility is not activated by Phase 3.

## Direct messaging

Implemented endpoint:

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

Conversation/message reads and listing, realtime delivery, read state, mutations, replies, and offline/reconnect behavior are outside this slice.

## Profile PATCH

`PATCH /api/users/{userId}/profile` is implemented as a self-only update. Omitted fields remain unchanged, present non-null fields replace the stored value, and explicitly present `null` fields clear the stored value.
