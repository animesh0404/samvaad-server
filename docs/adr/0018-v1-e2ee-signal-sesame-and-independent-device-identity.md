# ADR 0018: V1 E2EE, Signal/Sesame, and independent device identity

## Status

Accepted.

This ADR supersedes the E2EE deferral in ADR 0005. All other V1 scope exclusions in ADR 0005 remain unchanged unless explicitly superseded by a later decision.

## Context

Samvaad V1 now requires end-to-end encrypted one-to-one messaging while preserving the existing server-authoritative authentication, authorization, sequencing, idempotency, and participant-access invariants.

The target client set is Web/browser, Android, and TUI. Each client installation must be able to become an independently identifiable cryptographic device. Authentication sessions and cryptographic device identities are separate concepts.

## Decision

### 1. V1 one-to-one messaging uses the Signal protocol family with Sesame-style multi-device session management

Samvaad will use the Signal protocol family and Sesame-style multi-device session management for V1 direct one-to-one messaging rather than designing a custom messaging cryptosystem.

The intended responsibilities are:

- asynchronous session establishment;
- per-device cryptographic identities;
- per-device session state;
- ratcheted message encryption and forward secrecy;
- asynchronous/offline encrypted-message delivery;
- multi-device fan-out and device/session lifecycle management.

The exact implementation library remains a separate engineering decision and must be validated for Java, browser, Android, and TUI targets before adoption.

### 2. Samvaad uses independent cryptographic devices

There is no primary cryptographic device.

A user's Web client, Android client, TUI client, or another supported client installation may each become an independent E2EE device with its own device identity and private key material.

The existing authenticated server session remains the authentication/revocation boundary. E2EE device identity is separate and must not be implemented by reinterpreting `sessionId` or `installationId`.

Conceptually:

```
User
  ├── authenticated Session
  ├── authenticated Session
  └── authenticated Session

User
  ├── E2EE Device
  ├── E2EE Device
  └── E2EE Device
       └── private cryptographic material remains client-side
```

### 3. First-device enrollment is client-independent

The first client through which an administrator-provisioned user completes setup may be Web, Android, TUI, or another supported client. We will not restrict first enrollment to Web merely to simplify implementation.

The first device:

1. authenticates with the provisioned account credentials;
2. completes required first-login account setup, including replacing the initial password where applicable;
3. generates its cryptographic device identity locally;
4. registers only public device/key material with the server;
5. retains private key material locally;
6. establishes account-level recovery before the account is considered fully enrolled.

The server must never receive the device's private keys.

### 4. Recovery is account-level one-time recovery codes

All-device-loss recovery uses an account-level set of one-time recovery codes.

Recovery codes are not tied to a browser, phone, TUI installation, session, or individual device.

The first trusted device must guide the user to generate and securely store the initial recovery-code set before the account is considered fully enrolled for recovery.

Initial policy:

- 25 one-time recovery codes per set;
- each code is independently consumable;
- consumed codes cannot be reused;
- codes are intended for secure offline storage;
- the server must not possess plaintext recovery codes or a capability to derive the user's recovery material from them;
- possession of an unused code is a high-trust recovery capability;
- loss or theft of the codes carries the corresponding availability/confidentiality trade-off and must be communicated clearly.

The recovery-code mechanism is account-level recovery material, not device identity material.

### 5. Recovery-code rollover is mandatory

Recovery codes have a rollover mechanism.

With the initial 25-code policy, when the 24th code is consumed and only one remains, the application must prominently warn the user and require/recommend generation and secure storage of a fresh set before relying on the final code.

The user must not silently reach a state where one remaining recovery code is their only path to recoverable history.

The exact cryptographic wrapping/re-encryption procedure for replacing recovery material will be defined in the recovery implementation design.

### 6. New-device enrollment supports existing-device approval and recovery

After first-device and recovery setup, a new client may request enrollment.

The new client authenticates normally and creates a new local cryptographic device identity. It does not become trusted merely because username/password authentication succeeded.

At least two approval paths are required:

- **Existing-device approval:** an already trusted E2EE device authorizes the new device.
- **Recovery-code enrollment:** the user proves possession of an unused account-level recovery code when no trusted existing device is available.

The exact approval ceremony and QR transport/UI remain implementation details of the device-enrollment slice.

### 7. Device identity and server session remain separate

A cryptographic device may have one or more authenticated server sessions during its lifetime.

Revoking a server session is therefore not automatically equivalent to deleting an E2EE device. Conversely, revoking an E2EE device must have explicit consequences for its authenticated sessions and future encrypted-message delivery.

Future device management must enforce both server-side transport/session revocation and cryptographic exclusion from future message sessions.

### 8. Future groups use a separate protocol boundary

Samvaad V1 does not implement group chat.

The V1 E2EE architecture must nevertheless leave a clean protocol boundary so future group conversations can adopt a group E2EE protocol such as MLS without replacing the V1 one-to-one Signal/Sesame architecture.

MLS is therefore a future group-messaging direction, not a V1 dependency and not part of the current implementation.

Signal/Sesame and future MLS group cryptography must remain separate protocol adapters behind Samvaad-owned application/crypto boundaries.

### 9. Crypto implementation remains replaceable at the Samvaad boundary

Samvaad domain, persistence, transport contracts, and client APIs must not depend directly on a specific crypto library's internal classes.

A Samvaad-owned cryptographic boundary must be capable of supporting the selected Signal/Sesame implementation for V1 and a future group protocol such as MLS.

This is an architectural seam, not a requirement to implement multiple providers now.

## Consequences

The E2EE implementation slice must introduce or define:

- device records and device lifecycle;
- public device/key directory;
- Signal/Sesame session state;
- asynchronous encrypted-message delivery/mailbox semantics;
- per-device encrypted envelopes;
- device enrollment and approval;
- device revocation and future-message exclusion;
- recovery-code storage/verification semantics;
- encrypted history/recovery-key hierarchy;
- browser, Android, and TUI private-key storage;
- verification and key-change UX;
- migration away from server-readable message content.

These are implementation consequences and are not solved by this ADR.

## Explicitly deferred

- group chat and MLS implementation;
- exact Signal-family library selection;
- exact QR enrollment ceremony;
- exact recovery-code wrapping/rotation format;
- backup storage provider and external-backup adapters;
- metadata minimization beyond existing server-authoritative requirements;
- receipts, typing/presence, attachments, reactions, edits, and other non-E2EE messaging features unless separately brought into scope.

## Relationship to existing ADRs

- **ADR 0001:** remains authoritative for server-derived authenticated identity and state. E2EE changes message confidentiality, not server authentication authority.
- **ADR 0003:** remains authoritative for server sessions, JWTs, refresh tokens, and session revocation. Device identity is an additional layer.
- **ADR 0004:** existing conversation/message integrity invariants remain unless explicitly changed by the E2EE implementation design.
- **ADR 0005:** its E2EE deferral is superseded by this ADR; its other V1 exclusions remain.
- **ADR 0008:** friendship remains the authorization gate for V1 direct messaging. Friendship does not itself constitute cryptographic verification.
- **ADR 0009:** STOMP remains a transport concern. E2EE introduces encrypted envelopes and asynchronous delivery semantics above/beside that transport.
- **ADR 0010:** remains authoritative that `installationId` is optional session metadata. E2EE device identity is a new independent concept.

## Implementation entry condition

No production E2EE code should be written until the selected Signal-family implementation has passed focused feasibility validation for Java 25/server runtime, browser/Angular, Android, TUI, Docker/Alpine, persistent crypto-state handling, and license/operational constraints.

The next implementation slice is **V1 E2EE Device & Signal/Sesame Foundation**, beginning with feasibility validation and protocol-boundary design rather than message UI work.
