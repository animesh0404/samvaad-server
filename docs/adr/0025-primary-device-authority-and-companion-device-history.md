# ADR 0025: Primary device authority and companion-device history

## Status

Accepted as the new architectural direction. Implementation is deferred until the post-reconciliation code audit.

## Context

During implementation, a concrete unresolved question exposed a gap in the previous multi-device model: **when a new device is enrolled, where does its existing conversation history come from?**

The previous V1 design treated up to five devices as cryptographically equal peers and made the server a durable ciphertext-history repository. Reviewing that model against the Samvaad Software Constitution showed that the server should perform delivery work without becoming the user's long-term chat backup.

This is an implementation-driven architectural turning point: the device-enrollment foundation already existed, but the new-device-history question revealed the need to define **history authority** explicitly.

## Decision

### One Primary Device + up to four Companion Devices

Samvaad changes from five equivalent first-class cryptographic devices to one **Primary Device** and up to four **Companion Devices**. The five-device maximum remains the overall device-count boundary.

The existing-device approval mechanism remains valid. The Primary Device additionally becomes authoritative for Companion enrollment and device-originated history synchronization.

### Device-owned durable history

The Primary Device is the authoritative source for durable conversation history. The server is not the user's long-term chat backup.

If a user loses every device and has no independent encrypted backup, conversation history is intentionally unrecoverable. This is a privacy/data-minimization property, not an availability defect.

A newly enrolled Companion receives older history through an E2EE synchronization path from the Primary Device. The server may provide bounded delivery/replay state for normal delivery and reconnect behavior, but that state is not the authoritative historical archive.

### Bounded server retention

The server stores only the ciphertext and metadata required for operational delivery. Delivered/acknowledged entries may be removed according to the bounded-buffer policy; undelivered ciphertext remains available for the required delivery/recovery window.

The exact rolling-buffer count and eviction policy are not locked. A 1,000-message rolling buffer was discussed as an initial engineering candidate, but it is not a decision and is not a claim about WhatsApp.

### Primary and Companion client direction

The Primary Device is a mobile/Android device. The first production-oriented Primary Device implementation is native Android using Kotlin and Jetpack Compose.

The Android client is intended to become a full-featured, polished Samvaad messaging application. Web is the next planned Companion client. TUI development is paused while the Android Primary Device and server architecture are established; TUI remains useful as a companion/testing implementation.

### Bounded device trust

Primary-device liveness and individual-companion liveness are separate policy dimensions. Candidate inactivity windows are **7, 14, and 28 days**; the exact value remains tunable for the implementation audit.

Enforcement must be server-authoritative and atomic so reconnect/renewal and expiry/revocation cannot race into inconsistent trust state.

### Existing E2EE foundation remains

The Signal-family protocol direction, device public-key directory, per-device envelopes, device/session binding, trusted-device approval, revocation, prekeys, ciphertext transport, sequencing, idempotency, and device-level realtime remain reusable foundations.

The pivot changes device role, history authority, retention, and synchronization; it does not discard the existing cryptographic foundation.

## Consequences

- The server no longer needs to be a permanent repository of conversation history.
- New-device history has an explicit owner: the Primary Device.
- Loss of all devices without independent encrypted backup intentionally loses history.
- Primary availability matters for history older than the server's bounded delivery window.
- History synchronization becomes a client/protocol responsibility.
- Android becomes an architectural dependency for the Primary Device role.
- Existing server APIs and persistence assumptions must be reconciled before further client work.

## Non-goals

This ADR does not define the exact rolling-buffer count, final inactivity duration, Primary-to-Companion history-sync wire protocol, encrypted backup provider, complete Android UI/UX, or WhatsApp-specific implementation details.

## Supersession

This ADR supersedes the conflicting portions of ADR 0018 and ADR 0020 concerning five cryptographically equal devices, absence of a Primary Device, permanent server-side ciphertext history, and historical restoration from the server as the durable source. Compatible E2EE protocol, enrollment, cryptographic, sequencing, idempotency, and transport foundations remain applicable unless separately superseded.
