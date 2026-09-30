# Testing Guide

This document describes the testing approach and verification commands used in the Samvaad backend server.

## Testing Approach

### Unit tests

E2EE transport unit tests verify envelope decoding/bounds, device-approval authorization, sync cursor guards, realtime notifier routing/failure isolation, and STOMP connection-registry lifecycle. Conversation unit tests verify listing, pagination, and participant checks.

### Web layer tests

HTTP controller tests verify routing, status codes, validation, authentication context, serialization, and service delegation.

### Integration & concurrency tests

Spring Boot/Testcontainers integration tests verify PostgreSQL persistence, Liquibase migrations, authentication/session behavior, relationship behavior, E2EE ciphertext transport (submission, mailbox, history, cursors, idempotency, concurrency), and realtime behavior.

Realtime/device-channel coverage currently includes:

- `StompDeviceAuthTest` — CONNECT authentication (valid, missing/invalid JWT, revoked/expired/mismatched/unbound/foreign/inactive device sessions) and SUBSCRIBE authorization (own device channel, cross-device denial without oracle, post-revocation denial)
- `StompAuthInterceptorLoggingTest` — trace propagation and secret-free CONNECT/SUBSCRIBE logging
- `StompConnectionRegistryTest` — connection tracking, multi-connection termination, idempotent termination, disconnect cleanup
- `DeviceWebSocketIntegrationTest` — real-port CONNECT/SUBSCRIBE, cross-device and conversation-topic denial, revoked/unbound CONNECT rejection, session-revocation connection termination
- `E2eeRealtimeDeliveryIntegrationTest` — per-device ciphertext delivery, offline mailbox fallback, multi-conversation single channel, reconnect/ack recovery, duplicate suppression, rollback silence, HTTP submission delivery, revocation termination
- `E2eeRealtimeNotifierTest` — per-device routing and broker-failure isolation

The full suite also verifies that existing HTTP behavior remains passing.

## What the Tests Currently Establish

The automated suite provides evidence for the implemented user/profile, authentication/session, relationship, conversation listing, E2EE ciphertext transport, device-level realtime, and revocation slices.

Device-level realtime specifically establishes that CONNECT derives a server-side device identity, subscriptions are authorized by exact match against the connection's own device channel, session/device revocation terminates live connections, realtime fan-out runs post-commit per recipient device, idempotent replays produce no second event, failed/rolled-back submissions produce no event, and the durable mailbox remains the delivery fallback.

The suite does **not** establish the full planned messaging system. Reconnect/backfill UX beyond mailbox/history/cursor catch-up, late-join device history, sender self-sync, persistent read state, typing/presence, delivery receipts, push notifications, message mutations/replies, relationship controls, horizontal scaling/external brokers, browser/Android cryptographic adapters, and encrypted history backup/restoration remain outside the implemented slices. The current E2EE suite establishes real JVM Signal/PQXDH interoperability, persistent private-key custody, session restart/replay behavior, encrypted envelope submission, mailbox/history transport, synchronization, device enrollment/recovery, revocation, and recovery-code consumption. The server rebind slice is covered by E2eeRebindIntegrationTest.

The E2EE foundation has dedicated integration, concurrency, and security regression coverage for device enrollment, approval, revocation, prekey handling, recovery enrollment, recovery-code consumption, device limits, and validation/error paths.

## Manual Verification

Automated integration coverage is complemented by the archived manual smoke test of the retired plaintext realtime path (2026-09-13). Current realtime verification is fully automated; see the realtime/device-channel test classes listed above.

The archived sanitized evidence is recorded in [Realtime Smoke Test (archived)](../verification/realtime-smoke-test.md).

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
./gradlew test --tests com.samvaad.samvaad_server.e2ee.realtime.E2eeRealtimeDeliveryIntegrationTest
```

`git diff --check` should also be clean before an implementation commit.

## Related Documentation

- [Local Development Setup](setup.md)
- [Current Implementation State](../architecture/current-state.md)
- [Current Security Posture](../security/current-security-posture.md)
- [Realtime Smoke Test (archived)](../verification/realtime-smoke-test.md)
- [Documentation Map](../README.md)
