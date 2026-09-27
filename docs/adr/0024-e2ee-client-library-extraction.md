# ADR 0024: Reusable JVM E2EE client library (`e2ee-client`)

## Status

Accepted. Extraction completed: the library now lives in the standalone
`samvaad-e2ee-lib` repository (sibling checkout, no longer an `e2ee-client/`
directory in this repository). Coordinates are unchanged
(`com.samvaad:e2ee-client:0.1.0`); the server module consumes them through
a composite build (`includeBuild('../../samvaad-e2ee-lib')` with an
explicit `dependencySubstitution` rule in `server/settings.gradle`,
because the library repository's root project is named
`samvaad-e2ee-lib` while its artifact remains `e2ee-client`).

Extends ADR 0018 (Signal/Sesame direction), ADR 0022 (persistent store
boundary), and ADR 0023 (JVM Signal adapter, Kyber mandate, key custody).
Supersedes nothing.

## Context

The JVM E2EE client implementation (`SamvaadCryptoService`,
`SignalAdapter`, `LibSignalAdapter`, `ClientCryptoStore`,
`FileBackedClientCryptoStore`, `PrivateKeyVault`, `FilePrivateKeyVault`,
plus supporting client types) lived inside the server module under
`com.samvaad.samvaad_server.e2ee.client`. The TUI must reuse exactly this
implementation over the architectural boundary
TUI → `SamvaadCryptoService` → `SignalAdapter` → `LibSignalAdapter` →
libsignal, without reimplementing Signal/PQXDH/Kyber and without depending
on the Spring Boot server application.

## Decision

1. The 17 production classes move verbatim (mechanical package rename to
   `com.samvaad.e2ee.client`, with `.signal` and `.persist` subpackages)
   into the standalone `e2ee-client` build: plain JVM `java-library` on
   Java 25, single external dependency
   `org.signal:libsignal-client:0.86.5` (`implementation`, never `api` —
   no public type exposes libsignal). No Spring, no Jackson, no JPA, no
   server DTOs/entities/repos. `SnapshotJson` stays package-private and
   JDK-only, as do the vault and fingerprint implementations.
2. Test doubles (`fake/FakeSignalAdapter`, `fake/InMemoryStores`) do NOT
   ship in the library; equivalent fixtures live under
   `samvaad-e2ee-lib/src/test`. Pure client proofs moved with the code;
   server transport/integration tests stay in the server module.
3. The server module drops its direct libsignal dependency and depends on
   `com.samvaad:e2ee-client:0.1.0`, resolved locally via the composite
   build. No published artifact is required for local development.
4. No protocol, persistence-format, wire-format, fingerprint, trust-state,
   or key-custody behavior changes in this slice: extraction only.

## Consequences

- The server keeps byte-identical E2EE behavior while compiling against
  the extracted library; its full suite (including E2EE transport tests)
  must stay green.
- Future TUI crypto-runtime work consumes `e2ee-client` through a composite
  build of its own, without touching STOMP/realtime or plaintext flows.
- `e2ee-client` versioning (`0.1.0`) becomes the shared contract coordinate;
  publishing (if ever needed) is deferred.
