# Samvaad Documentation

The documentation set tracks the implementation state of the Samvaad project and the locked product/architecture decisions.

Current implementation state:
- Phase 0 — Design reconciliation: complete.
- Phase 1 — Authentication & authorization boundary: complete.
- Phase 2 — Exact username discovery: complete.
- Phase 3 — Friend request vertical slice: complete.
- Phase 4 — Direct messaging vertical slice: complete.
- Phase 5 — Conversation/message reads and listing: complete.
- Realtime V1 — STOMP/WebSocket message delivery: complete and manually verified end-to-end.
- Friends List API: complete.
- Administrative user hard deletion: complete.
- Web Admin panel: complete first implementation slice.

The repository is a monorepo. `server/` contains the self-contained Spring Boot backend, `web-admin/` contains the Angular administration client, and `docs/` is shared project documentation at the repository root.

## Current server capabilities

The server provides authentication and persisted sessions, user/profile operations, admin user administration, friend requests and friendship authorization, direct messaging, conversation/message reads, and Realtime V1 over STOMP/WebSocket. See the architecture and API documents for detailed contracts.

Administrative hard deletion is implemented as explicit transactional cleanup rather than database `ON DELETE CASCADE`. A deletion locks the user row, removes sessions, profile, friend requests, sent messages, conversations involving the user and their messages, then removes the user row. The existing RESTRICT foreign keys remain database backstops. Successful deletion is `204 No Content`; a residual concurrent deletion conflict is `409 Conflict`.

## Web Admin

The Web Admin first implementation slice is now implemented under `web-admin/`.

- Angular + TypeScript.
- Tailwind CSS v4.
- npm with Node 24 convention via `.nvmrc`.
- Dashboard, user listing, creation, detail, and deletion flows.
- Existing server authentication/session contracts; login sends `clientPlatform: "WEB"` without an `installationId`.
- Access/refresh tokens are persisted in `sessionStorage` (ADR 0014), so a page reload restores the session.
- Only `ADMIN` accounts can establish a Web Admin session. Valid non-admin credentials are rejected at the login page and do not retain Web Admin auth state.
- Server-side authorization remains authoritative.

The production delivery model is implemented: Angular production assets are packaged into the Spring Boot executable JAR through the existing Gradle build.

See [Web Admin README](../web-admin/README.md), [Architecture Current State](architecture/current-state.md), and ADRs 0011–0016 for the current client/deployment architecture.

## Realtime V1

Realtime V1 provides:
- WebSocket endpoint `/ws` with STOMP.
- `/app` application prefix and `/topic` simple broker.
- `/app/chat.send` with `{conversationId, content, requestId}`.
- `/topic/conversations/{conversationId}` conversation delivery.
- STOMP `CONNECT` authentication using the existing access JWT plus persisted session validation through `Authorization: Bearer <JWT>`.
- Participant-only conversation subscriptions.
- Sender identity derived from the authenticated STOMP principal.
- Reuse of the existing message persistence, friendship authorization, sequencing, timestamps, and idempotency logic.
- Broadcast only after the message service successfully persists/commits the message.

A manual smoke test has verified authenticated clients connecting, subscribing to the same conversation, and realtime message delivery without polling. See [Realtime V1 Smoke Test](verification/realtime-smoke-test.md).

The first realtime slice uses Spring's in-memory simple broker and is intentionally single-instance V1 behavior. Reconnect/missed-event synchronization, persistent read state, typing/presence, delivery receipts, push notifications, message mutation/replies, relationship controls, external brokers/horizontal scaling, and end-to-end encryption remain deferred.

Friend-gated profile visibility also remains deferred; it was intentionally not activated as part of Phase 3.

## Deployment direction

The locked deployment direction is:
- Angular production assets packaged into the Spring Boot executable JAR.
- One JAR serving the web admin, REST API, and WebSocket endpoint.
- Externalized configuration for externally provisioned PostgreSQL.
- Docker as an additional distribution/deployment path.
- Public HTTPS terminating at a TLS-capable deployment edge such as a reverse proxy, tunnel, or managed edge.

The deployment packaging, versioned image publication, environment-supplied credentials, and Unix/Windows installer workflow are implemented. The concrete TLS/reverse-proxy provider, certificate automation, domain/DNS configuration, and deployment upgrade policy remain deployment work.

Development and deployment use separate Docker workflows with distinct image identities: `server/compose.yaml` for PostgreSQL-only backend development, `compose.dev.yaml` (`samvaad-server:dev`, local-only) for the full Docker development loop, `scripts/build.sh` (`samvaad-server:latest`) for the portable local image artifact, and root `compose.yaml` (`animesh0404/samvaad-server:<version>`) for published-release deployment. See ADRs 0015–0016 and `docs/development/setup.md`.

See the implementation roadmap, architecture/security documents, API contracts, and ADRs for the detailed current state and locked decisions.

## Architecture Review

- [SOLID Architecture Review](architecture/solid-architecture-review.md) — September 2026 baseline assessment for future architectural reassessment.
