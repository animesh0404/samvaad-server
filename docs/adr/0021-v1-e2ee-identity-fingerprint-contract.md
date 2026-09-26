# ADR 0021: V1 E2EE identity fingerprint contract

## Status

Accepted. Executable spec: `FingerprintContractTest` (JDK primitives
only). No production code changed; no library selected.

## Context

Samvaad trust is per-device TOFU with explicit key-change pausing
(`PAUSED_KEY_CHANGED`), but the exact fingerprint representation was
never frozen: the client contract stores an adapter-defined display
string while the security decision needs byte-exact identity comparison
plus a human-verifiable display reproducible on JVM, Android, and
Web/Tauri without depending on any single crypto library's display API.
The 0.103.x spike established the raw facts used here: the device
identity public key is 33 bytes (`0x05` version byte + 32-byte X25519
key), exactly as served in the directory/claim records; signed,
one-time, and Kyber keys rotate and are therefore unsuitable as
identity material.

## Decision

1. **Fingerprinted material.** Exactly: each party's immutable Samvaad
   `userId` plus that device's 33-byte canonical identity public key.
   Nothing else — no signed/one-time/Kyber keys, no metadata.
2. **Scope.** A fingerprint identifies one E2EE device identity in the
   context of one peer pair. Trust entries stay per peer-device UUID.
   There is no user-level aggregate: a peer adding, removing, or
   revoking a device never changes other devices' fingerprints or
   trust entries; a new device UUID starts a fresh TOFU entry.
3. **Canonical encoding.** Identity key: the 33 bytes exactly as stored
   and served (`0x05 || X25519`). UserId: 16 bytes big-endian
   (most-significant bits first, then least-significant bits).
   Entry: `userId16 || key33` (49 bytes).
4. **Hash.** SHA-256 over `D || A || B`, where `D` is the domain below
   and `A,B` are the two 49-byte entries in ascending lexicographic
   (unsigned) byte order, so both viewers compute identical output.
5. **Digest use.** First 24 bytes (192 bits) as a big-endian unsigned
   integer. Since 2^192 < 10^60, zero-padding is unbiased.
6. **Display.** Decimal, left-padded with `0` to exactly 60 digits.
7. **Grouping.** Twelve groups of five digits joined by single spaces
   (e.g. `00136 34446 ...`). Grouping is presentation-only: storage
   and any machine handling use the ungrouped digits, and trust
   decisions never compare display strings.
8. **Version/domain separation.** ASCII `SAMVAAD-FP-V1` (13 bytes).
   The version lives in the domain string; rotation means a new
   domain (e.g. `SAMVAAD-FP-V2`) via a new ADR, never a side integer.
9. **Cross-platform stability.** The construction uses only SHA-256,
   ASCII, and fixed byte orders — reproducible byte-for-byte on JVM,
   Android, and Web/Tauri (WebCrypto-compatible) with no library calls.
10. **Key change (normative for `PAUSED_KEY_CHANGED`).** Byte-inequality
    of the 33-byte canonical identity key for the same peer device
    UUID. Revocation is terminal and distinct, never a "change"; a new
    device UUID is a new TOFU entry, never a change.
11. **Compare vs display.** Compared locally: the 33-byte keys (only
    they gate trust). Merely displayed: the 60-digit string (human
    out-of-band verification only). A future adapter must compare key
    bytes even where the current contract carries a display string.
12. **Server blindness preserved.** Fingerprints never cross the API;
    directory/claim carry raw keys, trust lives client-side. No server
    change.

## Test vectors

All vectors use `userId` UUIDs rendered canonically and keys
`0x05 || byte×32`. Reference pair V1: user
`11111111-1111-1111-1111-111111111111` key `0x01×32` with user
`22222222-2222-2222-2222-222222222222` key `0x02×32`:

```text
V1 = 00136 34446 03500 40354 18163 86955 22151 88162 40335 93379 98418 05716
```

Swapped viewing order yields identical output (symmetry). Pair V3
(`1111…`/`0x01×32` with `33333333-3333-3333-3333-333333333333`/`0x03×32`):

```text
V3 = 00530 85616 11504 63526 32365 68608 96307 52408 63097 80888 10308 98639
```

Fixture keys are byte patterns, not curve points: vectors pin the
construction, while curve validity remains the crypto layer's job.
Every platform adapter must reproduce these vectors byte-for-byte.

## Consequences

- Trust UX no longer depends on any library's fingerprint API; the
  `SignalAdapter` boundary keeps raw key bytes for comparison and
  derives this display form for UI.
- Signed/OTPK/Kyber rotation never triggers key-change pauses.
- Changing anything above (material, order, domain, hash, truncation,
  display) requires a new ADR and new vectors.

## Explicitly deferred

- QR/short-code verification ceremonies and UX copy.
- Post-quantum identity keys (a new domain + vectors when adopted).
- Whether the stored trust record also persists raw key bytes
  (required behavior is defined; storage shape is implementation).
