# ADR 0024: Reusable JVM E2EE client library (`e2ee-client`)

## Status

Accepted. Extraction completed: the library now lives in the standalone
`samvaad-e2ee-lib` repository (sibling checkout, no longer an `e2ee-client/`
directory in this repository). Coordinates are unchanged
(`com.samvaad:e2ee-client:0.1.0`).

Amended: the temporary composite-build consumption
(`includeBuild('../../samvaad-e2ee-lib')` with an explicit
`dependencySubstitution` rule in `server/settings.gradle`) is retired.
The server module now consumes the published GitHub Packages artifact
`com.samvaad:e2ee-client:0.1.0` (library tag `v0.1.0`) from
`https://maven.pkg.github.com/animesh0404/samvaad-e2ee-lib`. No sibling
checkout is required.

Amended (2026-09-28, see Amendment below; interim GitHub Packages
decision preserved as history): GitHub Packages consumption is retired.
The server module now consumes the Maven Central artifact
`io.github.animesh0404:e2ee-client:0.1.0` through `mavenCentral()`,
with no credentials required.

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
   the published `com.samvaad:e2ee-client:0.1.0` artifact resolved from
   GitHub Packages
   (`https://maven.pkg.github.com/animesh0404/samvaad-e2ee-lib`, library
   tag `v0.1.0`). The temporary composite-build consumption is retired;
   no sibling checkout is required for normal builds. The dependency
   coordinate is unchanged.
4. No protocol, persistence-format, wire-format, fingerprint, trust-state,
   or key-custody behavior changes in this slice: extraction only.

## Consequences

- The server keeps byte-identical E2EE behavior while compiling against
  the extracted library; its full suite (including E2EE transport tests)
  must stay green.
- Future TUI crypto-runtime work consumes `e2ee-client` through its own
  migration to the published artifact, without touching STOMP/realtime
  or plaintext flows.
- `e2ee-client` versioning (`0.1.0`) becomes the shared contract coordinate;
  the server resolves the exact published coordinate
  `com.samvaad:e2ee-client:0.1.0` from GitHub Packages.

## Amendment: GitHub Packages consumption (this slice)

- Repository: `https://maven.pkg.github.com/animesh0404/samvaad-e2ee-lib`
  (see `samvaad-e2ee-lib` ADR 0002 for why GitHub Packages is used
  instead of Maven Central). Exact consumed coordinate:
  `implementation 'com.samvaad:e2ee-client:0.1.0'` (library tag `v0.1.0`).
- No Java source, API, version, or architectural change: this is a
  repository-resolution change only (composite `includeBuild` +
  substitution removed from `server/settings.gradle`; Maven repository
  declared in `server/build.gradle`).
- Credentials (local development and CI): GitHub Packages Maven requires
  authentication even for reads; anonymous resolution is not assumed to
  work. `server/build.gradle` reads `GITHUB_ACTOR` / `GITHUB_TOKEN` from
  the environment only — no credentials are hardcoded or committed.
  Provide any GitHub username as `GITHUB_ACTOR` and a personal access
  token with `read:packages` scope as `GITHUB_TOKEN` (in GitHub Actions
  the repository-provided `GITHUB_TOKEN` works as-is). See
  `docs/development/setup.md`.
- License / source availability: the consumed library is published as
  AGPL-3.0-only (see its `LICENSE`, per-file
  `SPDX-License-Identifier: AGPL-3.0-only`, and published POM), and it
  links `org.signal:libsignal-client:0.86.5` (AGPL-3.0-only; no Signal
  source vendored). The corresponding source for the exact consumed
  artifact is `https://github.com/animesh0404/samvaad-e2ee-lib` at tag
  `v0.1.0`. Downstream distribution must preserve the already-identified
  AGPL source-availability implications; nothing in this amendment
  re-licenses the server or defers the open AGPL licensing/product
  decision recorded in ADR 0019.

## Amendment (2026-09-28): E2EE client from Maven Central (final)

The interim GitHub Packages consumption recorded above is retired. The
server module now consumes the publicly published Maven Central
artifact:

```groovy
implementation 'io.github.animesh0404:e2ee-client:0.1.0'
```

- HISTORICAL: composite build (`includeBuild` + substitution) →
  interim GitHub Packages (`com.samvaad:e2ee-client:0.1.0` from
  `https://maven.pkg.github.com/animesh0404/samvaad-e2ee-lib`,
  authenticated with `GITHUB_ACTOR` / `GITHUB_TOKEN`).
- FINAL: Maven Central → `io.github.animesh0404:e2ee-client:0.1.0`
  (library tag `v0.1.0`; previously `com.samvaad:e2ee-client:0.1.0`).
- Repository: Maven Central, resolved through the existing
  `mavenCentral()` configuration in `server/build.gradle`. No GitHub
  Packages repository is configured anymore.
- Credentials: Maven Central resolution requires no GitHub credentials.
  `GITHUB_ACTOR` / `GITHUB_TOKEN` are no longer required for
  `e2ee-client` resolution, in local development, Docker builds, or CI.
- The temporary composite-build consumption (`includeBuild` +
  substitution in `server/settings.gradle`) remains retired, as decided
  above.
- The final dependency is a normal external Maven dependency; its
  runtime dependency (`org.signal:libsignal-client:0.86.5`) resolves
  from Maven Central with it.
- No Java source, API, version, cryptographic-architecture, or
  behavior change: this is a repository-resolution and coordinate
  change only, verified by `dependencyInsight` / `dependencies` on
  `runtimeClasspath`. The AGPL-3.0-only / source-availability position
  stated above is unchanged; nothing in this amendment re-licenses the
  server or introduces a new licensing decision.
