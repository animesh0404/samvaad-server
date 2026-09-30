# Current User / Profile API

## Username discovery

`GET /api/users/lookup?username={username}` is authenticated, exact, case-insensitive, and returns only discovery-safe identity fields.

## Friend-request relationship

Friend-request lifecycle is implemented under `/api/friend-requests`. An accepted request represents the friendship used by direct messaging authorization. Friend-gated profile visibility remains deferred.

## Direct messaging

Message transport is E2EE ciphertext only; see `e2ee-api.md` for the authoritative contract. The legacy plaintext `POST /api/conversations/direct/messages` send endpoint and the plaintext `GET /api/conversations/direct/{conversationId}/messages` history endpoint have been removed.

### HTTP conversation list

`GET /api/conversations/direct?limit=20&offset=0`

Participant-only. `limit` is 1–100; `offset` is non-negative. Results use recent `updatedAt` descending and `conversationId` ascending as deterministic tiebreaker.

## Realtime messaging — device-level STOMP channel

### WebSocket endpoint

`/ws`

The HTTP handshake is allowed through the servlet security chain so STOMP can perform token authentication. Application authentication occurs on the STOMP `CONNECT` frame.

### CONNECT authentication

Send the existing access token as:

```text
Authorization: Bearer <access-jwt>
```

The server validates the JWT and persisted session using the same identity/session rules as HTTP authentication, and additionally requires the session to be bound to an `ACTIVE` E2EE device owned by the caller. The resulting principal identifies exactly one device. No separate WebSocket login exists, and the client never supplies its own device identity.

### Device subscription

Subscribe to:

```text
/topic/devices/{deviceId}
```

where `{deviceId}` must equal the authenticated connection's own device. Destinations for any other device — or any other shape, including conversation topics — are rejected identically so subscription attempts do not reveal whether another device exists. The subscription is revalidated against session/device liveness; session or device revocation terminates the connection server-side.

### No client SEND handler

There is no application STOMP send endpoint. E2EE messages are submitted exclusively through HTTPS (`POST /api/e2ee/messages`); after the database transaction commits, the server fans out one persisted per-device ciphertext envelope to each recipient device's topic. The durable per-device mailbox remains the fallback and is never acknowledged by realtime delivery.

### Broker boundary

Realtime uses Spring's in-memory simple broker (single server). Presence/receipts/notifications, message mutation/replies, external brokers, and horizontal scaling remain deferred. Missed-event recovery is provided by the durable mailbox, permanent ciphertext history, and sync cursors — see `e2ee-api.md`.

## Profile PATCH

`PATCH /api/users/{userId}/profile` is self-only. Omitted fields remain unchanged, present non-null fields replace values, and explicitly present `null` fields clear values.
