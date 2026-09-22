# ADR 0017: Application-managed TLS for direct access

## Status

Accepted; application-managed HTTPS implemented.

Extends ADR 0013 (which remains the locked decision for public edge TLS
termination) and ADR 0016 (whose `~/Samvaad` / `C:\Samvaad` installation
location is relocated by this ADR to `~/.samvaad` /
`%USERPROFILE%\.samvaad`; the remainder of ADR 0016 stands).

## Context

Samvaad serves direct-access deployments (single host, LAN) with no TLS
terminating reverse proxy in front of it. The JDK exposes no supported
public API for issuing X.509 certificates, and the runtime must not depend
on an external `keytool` binary being present on every target (Docker,
Linux standalone, Windows standalone).

## Decision

The Spring Boot application terminates HTTPS itself using embedded Tomcat:

- **HTTPS only on port 8080.** No parallel insecure HTTP connector, via
  standard `server.ssl.*` PKCS#12 configuration. WebSocket/STOMP inherits
  TLS as WSS with no further changes.
- **Self-signed certificates** for local/direct deployments: RSA 2048,
  SHA256withRSA, ~10-year validity, alias `samvaad`, default SANs
  `DNS:localhost` + `IP:127.0.0.1` plus explicitly configured additions.
  No automatic NIC discovery.
- **Generation inside the JVM** with Bouncy Castle (`bcprov-jdk18on`,
  `bcpkix-jdk18on`), before embedded Tomcat starts. `keytool` is
  documented as an operator diagnostic/fallback only, never used by the
  application.
- **Identity preservation.** The keystore is generated once and reused.
  Expired, malformed, wrong-password, or alias-missing stores fail startup
  with an explicit regeneration instruction (delete the keystore and
  restart); nothing is ever silently replaced. SAN changes never trigger
  regeneration.
- **Storage.** Standalone: `~/.samvaad/tls/keystore.p12`
  (`%USERPROFILE%\.samvaad` on Windows). Docker: `/tls/keystore.p12` on
  the dedicated `samvaad-tls` / `samvaad-dev-tls` volumes — never in the
  image, classpath, JAR, logs, or PostgreSQL volumes.
- **External operator configuration.** `~/.samvaad/application.yaml`
  (created with non-secret defaults once, never overwritten) loaded via
  Spring Boot's external configuration mechanism; secrets stay in `.env`
  (`SAMVAAD_DB_PASSWORD`, `SAMVAAD_JWT_SECRET`,
  `SAMVAAD_TLS_KEYSTORE_PASSWORD`). The canonical per-user directory is
  `~/.samvaad` (`%USERPROFILE%\.samvaad`), replacing the former
  `~/Samvaad` / `C:\Samvaad` installer locations; legacy files migrate
  without overwriting existing state.

## Consequences

- Direct deployments get encrypted transport with zero-touch first boot;
  the SHA-256 fingerprint is logged for out-of-band verification.
- Browsers warn on the self-signed certificate: expected, documented, and
  distinct from CA trust (see the installation documentation).
- Public deployments continue to terminate TLS at the edge per ADR 0013;
  both architectures remain supported and compose (edge in front of an
  HTTPS backend is possible later).

## Explicitly deferred

- ACME / Let's Encrypt / automatic renewal / rotation / hot reload.
- HSTS and additional hardening headers.
- First-time setup wizard and enforced bootstrap-password change.

## Source material

- `docs/adr/0013-tls-termination-at-deployment-edge.md`
- `docs/adr/0016-installer-and-deployment-credentials.md`
- `server/src/main/java/com/samvaad/samvaad_server/tls/`
- `compose.yaml`
- `compose.dev.yaml`
- `scripts/install.sh`
- `scripts/install.ps1`
