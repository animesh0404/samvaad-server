# Testing Guide

This document describes the testing approach and verification commands used in the Samvaad backend server.

## Testing Approach

### Unit tests

Messaging service tests verify friend authorization, self-message rejection, authorization-before-conversation-creation, conversation race handling, request-ID replay/conflict behavior, sequencing, participant-only reads, pagination, and message cursor behavior. Realtime-related unit coverage also verifies conversation participant checks and shared message-service behavior.

### Web layer tests

HTTP controller tests verify routing, status codes, validation, authentication context, serialization, and messaging service delegation.

### Integration & concurrency tests

Spring Boot/Testcontainers integration tests verify PostgreSQL persistence, Liquibase migrations, authentication/session behavior, relationship behavior, direct-message invariants, conversation/message reads, and realtime behavior.

`RealtimeIntegrationTest` currently contains 9 tests covering:

- successful STOMP `CONNECT`
- missing/invalid access token rejection
- revoked-session rejection
- authenticated participant send and broadcast
- server-authoritative message fields with HTTP-read cross-check
- idempotent replay
- non-participant subscription rejection
- unknown-conversation subscription rejection
- failed send with no persisted/broadcast message

The full suite also verifies that existing HTTP behavior remains passing after the realtime transport was added.

## What the Tests Currently Establish

The automated suite provides evidence for the implemented user/profile, authentication/session, relationship, direct-messaging write, conversation/message read, Realtime V1, and E2EE device/prekey foundation slices.

Realtime V1 specifically establishes that authentication uses the existing JWT/session model, participant subscriptions are authorized, STOMP sends reuse the existing message business logic, persistence precedes broadcast, idempotent replay does not create a second message, and failed sends do not produce a persisted/broadcast message.

The suite does **not** establish the full planned messaging system. Reconnect/missed-event synchronization, offline queues, persistent read state, typing/presence, delivery receipts, push notifications, message mutations/replies, relationship controls, horizontal scaling/external brokers, Signal/Sesame message sessions, encrypted message envelopes/mailboxes, ciphertext history, history synchronization, client cryptographic persistence, or encrypted history backup/restoration remain outside the implemented slices.

The E2EE foundation has dedicated integration, concurrency, and security regression coverage for device enrollment, approval, revocation, prekey handling, recovery enrollment, recovery-code consumption, device limits, and validation/error paths.

## Manual Verification

Automated integration coverage is complemented by a local manual smoke test of the realtime path. On 2026-09-13, authenticated Alice and Bob clients connected to `/ws`, completed STOMP `CONNECT`, subscribed to the same conversation topic, and Alice sent a realtime message through `/app/chat.send`. Bob received the resulting server-authoritative message on `/topic/conversations/{conversationId}` without polling. The observed `messageId`, `requestId`, `senderUserId`, sequence number, server timestamp, content, and conversation ID matched across the clients.

The detailed sanitized evidence is recorded in [Realtime V1 Smoke Test](../verification/realtime-smoke-test.md).

## Running Tests

Backend test commands run from `server/`:

```bash
cd server
./gradlew test
./gradlew check
```

A specific test class can be run with:

```bash
cd server
./gradlew test --tests com.samvaad.samvaad_server.messaging.RealtimeIntegrationTest
```

`git diff --check` should also be clean before an implementation commit.

## Related Documentation

- [Local Development Setup](setup.md)
- [Current Implementation State](../architecture/current-state.md)
- [Current Security Posture](../security/current-security-posture.md)
- [Realtime V1 Smoke Test](../verification/realtime-smoke-test.md)
- [Documentation Map](../README.md)
