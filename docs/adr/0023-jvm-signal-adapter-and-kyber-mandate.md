# ADR 0023: JVM Signal adapter, mandatory last-resort Kyber, and private-key custody

## Status

Accepted. Implementation: `LibSignalAdapter` (libsignal-client 0.86.5, the
newest line published to Maven Central and the line ADR-0018 validated on
Java 25) behind the unchanged `SignalAdapter` seam, with two-device
interoperability and service/persistence integration coverage. Later 0.10x
lines are not published to Maven Central and were not adopted.

Private-key custody is implemented as decided below (`PrivateKeyVault`
plus the password-based file backend); the restart limitation previously
recorded here no longer applies to the JVM backend.

Extends ADR 0018 (Signal/Sesame direction), ADR 0019 (last-resort Kyber
public-material foundation), ADR 0021 (fingerprint construction), and
ADR 0022 (persistent store boundary). Supersedes nothing.

## Context

The seam assumed X3DH-only bundles were constructible and that the
Samvaad-side envelope-type heuristic could classify every ciphertext.
Empirical spikes against the real library disproved both:

1. `PreKeyBundle` construction rejects null Kyber material: every session
   runs PQXDH against the bundle's last-resort Kyber triple. The triple
   ADR-0019 already carries in every bundle is therefore mandatory, not
   optional.
2. The library repeats the prekey message (same OTPK reference) until the
   peer's first reply advances the session, so a reused session can still
   yield PREKEY_INIT bytes that no Samvaad-side heuristic can classify.

## Decision

1. **Adapter generates the device's last-resort Kyber pair** via new seam
   method `generateKyberPrekey` (id-preserving, identity-signed public
   triple for directory upload, sealed private handle). One-time Kyber
   pools remain deferred. Inbound uses the device's remembered last-resort
   pair; unknown/stale Kyber ids fail closed as claim failures.
2. **`SignedPrekeyPair` carries its signature.** Bundle assembly (directory
   upload, claims) needs the signature alongside the public key; it was
   previously only synthesized by fakes. The file store persists it as an
   additive optional field (no version bump, old snapshots load with null).
3. **The producer reports the wire type.** `EncryptResult` gains the
   authoritative envelope type (null defers to the Samvaad heuristic for
   fake/testing adapters). Samvaad still supplies the envelope; it no
   longer guesses repeats.
4. **Signature schemes (frozen for V1 interop).** Signed prekey and Kyber
   signatures are both the identity key's signature over the serialized
   subject public key, verified explicitly by the adapter before
   establishment in addition to the library's own checks. Future Android/
   Web adapters must reproduce these byte-exactly.
5. **Fingerprint split.** The single-key `fingerprint()` is stable
   dev-display (SHA-256 hex), never the verification string. Human
   verification uses `IdentityFingerprints.displayFingerprint`, the exact
   ADR-0021 pair construction (golden vectors covered).
6. **Password-based JVM private-key custody.** The adapter seals every
   generated private object (identity pair, signed/OTPK records, Kyber
   record) into a `PrivateKeyVault` keyed by handle UUID and recovers
   misses from it on demand, including the lone last-resort Kyber entry
   (single-key invariant enforced at regeneration). The file backend
   encrypts entries with AES-256-GCM under a PBKDF2-HMAC-SHA256 master key
   (600,000 iterations) derived from a caller-supplied password that never
   reaches disk, logs, or checked-in configuration; handle UUID + kind are
   the per-entry associated data. The vault file is versioned, owner-bound,
   and written temp+fsync+atomic-rename; unknown versions, wrong
   passwords, tampering, and foreign owners fail closed. No Samvaad
   contract changed: the service, stores, and wire still see only opaque
   handles, and the normal snapshot still carries references only.

## Consequences

- First message, reply, multi-message ratcheting, exactly-once OTPK,
  signed-prekey fallback, simultaneous initiation, blob reload, and
  service-level commit/restart/replay all verified with real crypto.
- Rapid sends before the first reply yield prekey repeats referencing the
  consumed OTPK, which fail closed deterministically per the locked §12
  rule (no silent ratchet fallback). Duplicate-PREKEY_INIT recovery stays
  a future slice; no message in the covered flows is misclassified.
- Private keys live behind handle UUIDs with the adapter registry as the
  hot cache and the encrypted vault as the durable tier. After a full JVM
  restart, replays, ratchet decrypts, sends on established sessions, fresh
  establishment, and new inbound prekey-init all work once the vault is
  unlocked; wrong credentials fail closed at open. Memory hygiene beyond
  the vault's own buffers is best-effort (documented).

## Explicitly deferred

- Platform adapters/backends (Android, Web, Tauri).
- One-time Kyber pools and rotation.
- Duplicate-PREKEY_INIT recovery semantics.
- OS-keyring/TPM-backed custody beyond the password vault.
- Groups/MLS, backup/restore (unchanged).
