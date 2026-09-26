# ADR 0022: V1 E2EE client persistent store boundary

## Status

Accepted. Contract: `ClientCryptoStore` with the file-backed JVM reference
implementation (`client.persist`) and recovery coverage in
`PersistentStoreRecoveryTest`. No SignalAdapter exists yet.

Extends ADR 0018, which remains the locked decision for V1 E2EE
identity/enrollment, and ADR 0021, which remains the locked trust primitive
(canonical 33-byte identity key bytes; fingerprints are display only).
Supersedes nothing.

## Context

The hardened crypto slice requires two crash-atomic durability boundaries:

1. outbound: advanced session blob + COMMITTED ciphertext slot, together or
   neither (`SessionStore.commitOutboundCiphertext`);
2. inbound: READY session + OTPK consumption, together or neither.

The in-memory stores only simulated these, and the inbound pair spanned two
separate store calls (`saveSession` + `forgetOneTimePrivate`). The
server-side PostgreSQL/Liquibase schema cannot back this state: it belongs
to the server transport trust domain, which must never hold client private
handles or session state (ADR-0018), and client platforms (Android, Web,
TUI) have their own storage anyway.

## Decision

1. **Unified contract.** `ClientCryptoStore` extends `SessionStore`,
   `DeviceKeyStore`, and `TrustStore` and adds `commitInboundEstablishment`
   (READY session + OTPK consumption in one boundary). The service depends
   on this single durability owner; single-concern interfaces are unchanged
   for readers of individual concerns.
2. **Mandatory outbound boundary.** `saveSlot` implementations MUST reject
   `COMMITTED` slots; COMMITTED rows are written exclusively by
   `commitOutboundCiphertext`. There is no normal path that persists a
   committed slot without its advanced session.
3. **Inbound durability rule.** OTPK consumption is recorded only inside
   `commitInboundEstablishment`. Crash before it: neither half visible,
   retry re-resolves the same OTPK. Crash after it: replay fails closed at
   resolution time without consuming another OTPK; the converged session
   stays usable.
4. **Handle references, never key bytes.** Stores persist sealed private
   material exclusively as opaque `handleId` references. The platform
   keystore owns the material; this store owns references and lifecycle
   flags. No homemade key encryption was introduced.
5. **Reference backend.** The JVM reference implementation keeps one
   versioned JSON snapshot per single-process-owned directory, rewritten
   write-through via temp-file + fsync + atomic rename. The snapshot
   carries `STORE_FORMAT_VERSION`; unknown versions are refused, never
   migrated silently. The codec is JDK-only so the boundary never tracks a
   JSON library version.
6. **Platform mapping.** Android (Room/SQLite transaction + Keystore),
   Web/Tauri (IndexedDB + WebCrypto), and other JVM clients implement the
   same `ClientCryptoStore` boundaries; only the backend differs.

## Consequences

- Crash/recovery cannot expose session-advanced/ciphertext-missing or
  session-without-consumption / consumption-without-session half-states;
  both are covered by reopen-from-disk tests.
- Trust verdicts persist canonical key bytes across restarts (verified).
- The real Signal adapter needs no contract changes for durability: it
  parses OTPK IDs, resolves handles, and reports consumed IDs as before.

## Explicitly deferred

- Platform backends beyond the JVM reference store.
- OTPK issuance high-water durability (stays with `PrekeyManager`/platform).
- Snapshot migration paths (rejected explicitly until a versioned upgrade
  is designed).
- Real Signal/Sesame adapter, backup/restoration (unchanged).
