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

The selected Signal-family implementation must satisfy the Samvaad-owned crypto boundary and be validated for Java, browser, Android, and TUI targets before production adoption. The initial Java feasibility spike validated libsignal-client 0.86.5 on Java 25 with a glibc-based Temurin runtime; the browser, Android, and TUI paths still require target-specific validation.

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

### 6a. Enrollment state machine: bootstrap, trusted-device approval, and recovery

Device enrollment is part of the authentication/trust-establishment flow. Account credential validation alone does not make a newly presented cryptographic device trusted after the account has already completed an E2EE enrollment.

Samvaad distinguishes three enrollment states:

1. **First-device bootstrap:** the account has never completed an E2EE device enrollment. The first supported client authenticates with the provisioned account credentials, generates its device identity locally, completes first-device setup and recovery-code setup, and the device becomes the first ACTIVE E2EE device.
2. **Trusted-device enrollment:** the account has one or more ACTIVE E2EE devices. A new client may authenticate with the account credentials and generate a new local device identity, but authentication/enrollment is not completed until an already trusted device explicitly approves the new device.
3. **Recovery-required enrollment:** the account has previously completed an E2EE device enrollment but currently has zero ACTIVE E2EE devices. This state is **not** treated as first-device bootstrap. A new client may validate the account credentials, but it must also prove possession of an unused account-level recovery code before authentication and device enrollment are completed and the new device becomes ACTIVE.

The distinction between first-device bootstrap and recovery-required enrollment is based on whether the account has ever completed a trusted E2EE device enrollment, not merely on whether the current active-device count is zero.

The authentication flow may use a provisional enrollment context while approval or recovery is pending, but it must not issue a fully trusted authenticated session for the new device before the required trust step succeeds.

Recovery-code consumption and successful recovery enrollment must be atomic: a recovery code is consumed only as part of a successfully completed recovery enrollment, and a failed enrollment must not burn the code.

A recovery enrollment establishes a new independent cryptographic device identity. It does not restore or reuse the revoked/lost device's cryptographic identity. Historical message restoration remains a separate encrypted-history recovery operation as defined below.

### 7. Device identity and server session remain separate

A cryptographic device may have one or more authenticated server sessions during its lifetime.

Revoking a server session is therefore not automatically equivalent to deleting an E2EE device. Conversely, revoking an E2EE device must have explicit consequences for its authenticated sessions and future encrypted-message delivery.

**When an E2EE device is revoked, all authenticated server sessions belonging to that device are terminated server-side.** The device is then no longer eligible to send, receive, or synchronize E2EE traffic. An old session/JWT must not remain an alternative path around device revocation.

Future device management must enforce both server-side transport/session revocation and cryptographic exclusion from future message sessions.

### 8. Server-side device record and public key directory

Each enrolled E2EE device has a server-side device record.

The record contains the public cryptographic material and operational state required for device discovery and Signal/Sesame session establishment, including:

- device identity public key;
- signed prekey and its signature;
- available one-time prekeys;
- enrolled/active/revoked status;
- device metadata required for device management.

All corresponding private cryptographic material remains exclusively on the device. The server must never receive, reconstruct, or derive device private keys.

The device record is distinct from the authenticated server session record.

### 9. V1 device limit and equal-device model

There is no primary cryptographic device. All enrolled devices are cryptographically equal peers.

An account may have a maximum of **5 enrolled E2EE devices**. The five devices may be any supported client combination; the server does not assign different cryptographic authority based on client type.

When a new device is enrolled, it becomes eligible for future encrypted messages immediately. Sending clients discover the recipient's currently eligible device public/prekey material through the server-side device directory when required; no user-facing manual device-list synchronization is required.

A newly enrolled device obtains historical messages through the separate encrypted-history restoration mechanism. It does not receive old history merely because a new Signal/Sesame session was established.

### 10. Per-device encrypted message envelopes and server mailboxes

For one-to-one messaging, the sending client produces the cryptographic ciphertext/envelope needed by each currently eligible recipient device. The Samvaad server does not encrypt or decrypt message content.

The server stores and routes these encrypted envelopes to per-device delivery mailboxes.

If a recipient device is offline, its encrypted envelope remains available until that device receives it and acknowledges successful receipt.

A device maintains a pool of one-time prekeys for asynchronous session establishment. V1 starts each enrolled device with **100 one-time prekeys** and replenishes the pool when fewer than **20 remain**. One-time prekeys are consumed for session establishment, not per message; if the pool is temporarily empty, session establishment may fall back to the signed prekey according to the selected Signal/Sesame implementation.

Mailbox delivery state is separate from permanent conversation history. A delivery acknowledgement removes the device-specific mailbox copy only; it does not delete the message from conversation history.

### 11. Server-retained ciphertext history and per-device synchronization

Samvaad retains encrypted message ciphertext as permanent conversation history. Delivery to one device does not delete the historical ciphertext.

Each conversation has one server-authoritative, monotonically increasing sequence number. The server assigns the sequence number when it accepts the encrypted message. If concurrent messages race, server acceptance order determines their sequence order.

Each enrolled device maintains a per-conversation synchronization cursor representing the last successfully synchronized conversation sequence.

For example:

```
Conversation 42

101 → Alice → Bob
102 → Bob → Alice
103 → Alice → Bob
104 → Alice → Bob

Bob Phone   → synced through 104
Bob Laptop  → synced through 102
Bob TUI     → synced through 101
```

A device reconnecting with cursor 101 can request the encrypted envelopes after sequence 101 rather than downloading the complete conversation history again.

The server remains authoritative for conversation sequencing; clients do not choose the authoritative sequence number.

### 12. Device revocation and message history

Revoking a device permanently excludes that cryptographic identity from future E2EE participation.

Revocation immediately terminates the device's authenticated server sessions and prevents further message send, delivery, or synchronization operations for that device.

Historical conversation ciphertext is not deleted merely because one device is revoked. It remains available to other still-enrolled devices according to their synchronization state and access rights.

### 13. Encrypted chat-history backup and recovery

Encrypted chat-history backup is separate from account recovery codes and separate from device identity.

Samvaad V1 uses full encrypted backups. A client generates a separate high-entropy backup-root key for the account's encrypted history backup. The server must never possess the plaintext backup key or plaintext chat history.

The backup-root key is durable for the account rather than regenerated for every backup. Individual backup objects may use derived per-backup/per-purpose encryption keys and fresh nonces/IVs as appropriate.

A new device may restore historical chat state without authorization from a previously trusted device. An optional user-chosen passphrase may be used to protect/wrap the backup-root key for independent recovery.

The backup contains enough encrypted message and conversation metadata to reconstruct the user's prior conversation history as a continuous stream. Backup restoration is part of new-device enrollment rather than a separate user-facing merge operation.

For V1, backup storage is an encrypted file. Backup encryption uses **ChaCha20-Poly1305 AEAD**. Each encryption operation uses a unique nonce; the nonce is stored with the ciphertext and is not secret. The exact key-derivation, backup-file serialization, associated-data fields, and versioning format remain implementation details of the backup contract. Cloud backup providers such as Google Drive are future storage/synchronization adapters and are not required for the V1 cryptographic architecture.

### 14. V1 local backup policy

The following are client-side product decisions for the initial encrypted-backup implementation:

- automatic full backup is enabled by default;
- default frequency is every 1 day;
- supported frequencies are every 12 hours, every 1 day, and every 1 week;
- automatic backup can be disabled by the user;
- automatic backup runs on a fixed wall-clock schedule, with a default time of 2:00 AM local device time;
- the backup time is user-configurable;
- a backup is created only when message history exists and has changed since the previous successful backup;
- the schedule is fixed-periodic and is not reset by message activity;
- only one automatic backup is retained locally, replacing the previous automatic backup after successful creation;
- automatic backup is stored in the client's private application storage by default;
- users may explicitly export/copy an encrypted backup file;
- during new-device enrollment, the client may detect an accessible local encrypted backup, show its backup timestamp, and offer restore or ignore;
- the user may explicitly select another encrypted backup file for restoration;
- restoration is not automatically performed merely because a backup exists.

These client-side details are recorded here as the current product contract; platform-specific Android/Web/TUI implementation details remain outside the server implementation design.

### 15. Future groups use a separate protocol boundary

Samvaad V1 does not implement group chat.

The V1 E2EE architecture must nevertheless leave a clean protocol boundary so future group conversations can adopt a group E2EE protocol such as MLS without replacing the V1 one-to-one Signal/Sesame architecture.

MLS is therefore a future group-messaging direction, not a V1 dependency and not part of the current implementation.

Signal/Sesame and future MLS group cryptography must remain separate protocol adapters behind Samvaad-owned application/crypto boundaries.

### 16. Message privacy boundary

Only metadata required for routing, ordering, delivery, synchronization, and necessary abuse protection is server-visible. Message content and non-essential message metadata remain inside authenticated encrypted payloads.

The server stores the metadata envelope and ciphertext needed for its authoritative sequencing and delivery responsibilities but does not decrypt message content.

## Crypto implementation boundary

Samvaad domain, persistence, transport contracts, and client APIs must not depend directly on a specific crypto library's internal classes.

A Samvaad-owned cryptographic boundary must be capable of supporting the selected Signal/Sesame implementation for V1 and a future group protocol such as MLS. This is an architectural seam, not a requirement to implement multiple providers now.

The browser client may use a different compatible cryptographic implementation from the JVM/Android/TUI clients, provided all clients implement the same Samvaad E2EE protocol semantics and wire contracts.

## Identity-key change handling

If a previously trusted device presents a different cryptographic identity key, Samvaad V1 treats this as a security-relevant key-change event. Encrypted communication with that device is paused until the new identity is explicitly verified/accepted. This is distinct from device revocation; the key-change handling must provide a path for legitimate device re-establishment without silently trusting the new identity.

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
- receipts, typing/presence, attachments, reactions, edits, and other non-E2EE messaging features unless separately brought into scope.

## Relationship to existing ADRs

- **ADR 0001:** remains authoritative for server-derived authenticated identity and state. E2EE changes message confidentiality, not server authentication authority.
- **ADR 0003:** remains authoritative for server sessions, JWTs, refresh tokens, and session revocation. Device identity is an additional layer.
- **ADR 0004:** existing conversation/message integrity invariants remain unless explicitly changed by the E2EE implementation design.
- **ADR 0005:** its E2EE deferral is superseded by this ADR; its other V1 exclusions remain.
- **ADR 0008:** friendship remains the authorization gate for V1 direct messaging. Friendship does not itself constitute cryptographic verification.
- **ADR 0009:** STOMP remains a transport concern. E2EE introduces encrypted envelopes and asynchronous delivery semantics above/beside that transport.
- **ADR 0010:** remains authoritative that `installationId` is optional session metadata. E2EE device identity is a new independent concept.

## Implementation status

The server-side **V1 E2EE Device & Prekey Foundation** defined by this ADR is implemented.

Implemented foundation includes:

- independent E2EE device records and PENDING/ACTIVE/REVOKED lifecycle;
- first-device bootstrap, trusted-device enrollment, and recovery-required enrollment state handling;
- trusted-device approval authorization seam;
- session-to-device binding and prevention of session rebinding during enrollment;
- five-device non-REVOKED enrollment limit and pending-device expiry;
- public identity/signed-prekey storage and one-time prekey upload/atomic claim;
- friendship-gated recipient device discovery;
- account-level 25-code recovery sets with hashed storage and atomic single-use consumption;
- device revocation with server-session termination;
- Samvaad-owned validation/authorization seams and regression coverage for concurrency and security boundaries.

The implementation intentionally does **not** yet provide full E2EE message confidentiality. Signal/Sesame session establishment, client-side persistent cryptographic state, encrypted message envelopes/mailboxes, ciphertext message persistence, per-device synchronization, encrypted history backup/restoration, and target-specific Web/Android/TUI cryptographic clients remain future implementation work.

## Implementation entry condition

The initial Signal-family feasibility validation has established the Java 25 server-side libsignal path and its glibc runtime requirement. The implementation path still requires validation for browser/Angular, Android, TUI, persistent crypto-state handling, and license/operational constraints before production adoption. The exact backup file format and key-derivation serialization remain implementation details, while the V1 cipher choice is fixed above.

The next implementation slice is **V1 E2EE Device & Signal/Sesame Foundation**, beginning with protocol-boundary design and remaining target-specific feasibility validation rather than message UI work.
