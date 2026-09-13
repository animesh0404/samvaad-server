# Realtime V1 Smoke Test

**Date:** 2026-09-13  
**Scope:** Manual end-to-end verification of the implemented Realtime V1 direct-message path.

## Scenario

Two provisioned users, Alice and Bob, were authenticated with the existing login/session flow. Their friendship had been accepted, and a direct conversation already existed.

Both clients then:

1. Opened a WebSocket connection to `/ws`.
2. Sent a STOMP `CONNECT` frame using the existing access JWT in the native `Authorization: Bearer <JWT>` header.
3. Successfully received STOMP `CONNECTED` frames with authenticated principals.
4. Subscribed to `/topic/conversations/{conversationId}`.
5. Alice sent a STOMP `SEND` frame to `/app/chat.send` containing only `conversationId`, `content`, and a client-generated `requestId`.

## Observed Result

The server accepted the realtime send, persisted the message, and broadcast the resulting server-authoritative message to the conversation topic.

The same message was observed by both Alice and Bob with these matching fields:

- `messageId`: `9009c8d4-e855-47f1-aa51-873343ea17f6`
- `requestId`: `7d8360d8-fac1-422a-914a-71ec4ea2c293`
- `senderUserId`: Alice's user ID
- `sequenceNumber`: `2`
- `serverTimestamp`: `2026-09-13T17:27:26.142161621`
- `content`: `🔥 Hello Bob — this is REALTIME!`
- `conversationId`: the existing Alice/Bob direct conversation

Bob received the message as a STOMP `MESSAGE` frame on `/topic/conversations/{conversationId}` without an HTTP message-read request or polling step.

## Verification Outcome

**PASS — Realtime V1 direct-message delivery was manually verified end-to-end.**

This confirms the local single-instance path from authenticated STOMP client, through the realtime message handler and existing message-service persistence/authorization logic, to broker delivery for subscribed conversation participants.

## Security/Privacy Note

Access tokens, refresh tokens, and other session secrets are intentionally not recorded here. The identifiers above are test-environment values used only to demonstrate the observed message flow.

## Boundary

This smoke test validates local realtime delivery only. It does not establish reconnect/missed-event synchronization, offline queues, persistent read state, delivery receipts, horizontal scaling, crash-safe event publication, or any other deferred Realtime V1 follow-on behavior.
