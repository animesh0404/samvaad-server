# ADR 0016: One-command installer and env-supplied deployment database credentials

## Status

Accepted; Unix/Windows installers implemented.

## Context

ADR 0015 separated local builds from published releases and made the root
`compose.yaml` consume a versioned Docker Hub image, but installing still
required cloning the repository, hand-creating `.env`, and understanding
Compose. The deployment also used a fixed database password (`samvaad`)
hardcoded in `compose.yaml` for both PostgreSQL and the application.

## Decision

1. **One-command installers.** `scripts/install.sh` (Unix) and
   `scripts/install.ps1` (Windows) bootstrap a deployment without a
   repository checkout: they check Docker/Compose availability (never
   installing Docker), create the fixed installation directory
   (`~/Samvaad`, `C:\Samvaad` on Windows), download `compose.yaml` from
   the canonical GitHub repository, create/reuse `.env`, generate the
   database password, prompt for JWT configuration
   (generate-or-supplied), run `docker compose up -d` against the
   published versioned image, and verify startup. Both are idempotent:
   re-runs refresh `compose.yaml`, preserve existing secrets, and
   converge the stack. They are bootstrap layers only and do not replace
   `build.sh`, `release-image.sh`, `start.sh`, `restart.sh`, or `stop.sh`.

2. **Env-supplied database credentials.** The root `compose.yaml` takes
   the database password from `SAMVAAD_DB_PASSWORD` (fail-fast
   `${VAR:?…}` references, same value to `POSTGRES_PASSWORD` and
   `SPRING_DATASOURCE_PASSWORD`). Non-secret identifiers (`samvaad`
   database/user names, JDBC URL topology) stay in `compose.yaml`.
   `scripts/install.sh` and `scripts/start.sh` generate a secure value
   when none is configured and reuse the existing one otherwise.

3. **Bootstrap credentials unchanged.** The Liquibase-seeded `admin`
   account and its known default password are unchanged (ADR 0007); no
   password-change enforcement is added. Installers document the
   credentials as temporary and point at manual self-service change. A
   first-time setup wizard remains planned future work.

## Consequences

- New users install without Git, Compose knowledge, or manual secret
  handling; Docker remains the only prerequisite.
- No fixed password ships in `compose.yaml`; each installation gets a
  generated database password that is never printed or committed.
- Existing deployments must align `.env` with the password their volume
  was initialized with (see `docs/development/setup.md` migration note).
- The installer always refreshes `compose.yaml` from the canonical
  source; per-installation state lives in `.env`, which is never
  overwritten.

## Explicitly deferred

- Custom installation directories.
- First-time setup wizard enforcing a bootstrap password change.
- CI/CD-driven release publication (still `scripts/release-image.sh`).

## Source material

- `scripts/install.sh`
- `scripts/install.ps1`
- `compose.yaml`
- `scripts/start.sh`
- `docs/adr/0007-user-provisioning-and-authorization.md`
- `docs/adr/0015-docker-lifecycle-and-release-image-reference.md`
