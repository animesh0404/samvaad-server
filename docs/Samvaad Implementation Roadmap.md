# Samvaad Implementation Roadmap

## Phase 0 — Design reconciliation
COMPLETE.

## Phase 1 — Authentication & Authorization Boundary
COMPLETE.

## Phase 2 — Exact Username Discovery
COMPLETE.

## Phase 3 — Friend Request Vertical Slice
COMPLETE.

## Phase 4 — Direct Messaging Vertical Slice
COMPLETE.

Implemented direct conversation persistence, friendship authorization, atomic first-message creation, server sequencing/timestamps, request-ID idempotency, and authenticated HTTP send.

## Phase 5 — Conversation/Message Reads & Listing
COMPLETE.

Implemented:
- `GET /api/conversations/direct?limit=20&offset=0`
- `GET /api/conversations/direct/{conversationId}/messages?afterSequence=0&limit=20`
- participant-only reads
- recent-activity ordering for conversation lists
- offset/limit conversation pagination
- exclusive per-conversation sequence cursor for messages
- 404-before-403 read behavior
- nullable other-participant username after admin deletion

## Realtime V1 — STOMP/WebSocket Message Delivery
COMPLETE.

Implemented:
- Spring WebSocket/STOMP support.
- WebSocket endpoint `/ws`.
- `/app` application destination prefix.
- `/app/chat.send` message command.
- `/topic/conversations/{conversationId}` conversation broadcast destination.
- STOMP `CONNECT` authentication using the existing access JWT and persisted session validation.
- `Authorization: Bearer <JWT>` as the CONNECT credential header.
- authenticated `userId`/`sessionId` principal association.
- participant-only conversation subscription authorization.
- participant message send through the existing `MessageService` business logic.
- server-authoritative sender identity, sequence, timestamp, and request ID.
- persistence before broadcast.
- Spring simple broker for the first slice.
- integration coverage for connect authentication, revoked sessions, participant/non-participant subscriptions, send/broadcast, persistence, idempotency, and failed-send behavior.

Implementation boundary: the HTTP and STOMP transports enter the same message business logic. The STOMP layer does not maintain separate persistence, sequencing, friendship, or idempotency rules.

## Friends List API

COMPLETE.

Implemented authenticated `GET /api/friends` using the existing accepted-FriendRequest-as-friendship model. The endpoint derives the caller from the authenticated principal, returns the other party from each accepted request, excludes non-accepted relationship states and self, exposes only the safe `userId`/`username` representation, and orders results by username ascending. No separate Friendship entity/table was introduced.

See [Friends API Contract](api/friends-api.md).

## Administrative User Lifecycle

COMPLETE.

V1 user deletion remains an ADMIN-only hard delete. The implementation locks the target user row and explicitly removes sessions, profile, friend requests, sent messages, conversations involving the user, and messages in those conversations before deleting the user row. Existing database RESTRICT foreign keys remain as backstops; no `ON DELETE CASCADE` migration was introduced.

Successful deletion remains `204 No Content`. A residual concurrent-integrity conflict is mapped to `409 Conflict` with the standard message envelope. Successful deletion logging is registered after transaction commit so a rolled-back transaction does not emit the normal INFO success claim.

## Pre-client architecture cleanup

COMPLETE.

The installation-ID/session contract was audited and refactored so authentication remains session-based and installation identity is optional client/device metadata. `POST /api/auth/login` accepts clients that omit `installationId`; blank/whitespace values normalize to null, while nonblank values remain supported. Liquibase migration `011-make-installation-id-nullable.yaml` makes the persisted session field nullable. Focused integration coverage verifies login without installation identity across web/TUI/desktop platforms, mobile metadata preservation when supplied, refresh, logout/revocation, session limits, HTTP validation, and STOMP `CONNECT` without installation identity. The identity-critical login transaction no longer performs installation-ID normalization because session creation and session-limit enforcement do not depend on that metadata.

## Operational logging

COMPLETE.

Operational logging is implemented with correlation/trace context, selective service-level `@OperationalLog` instrumentation, explicit domain/security events, secret avoidance, and configuration-driven size-based rolling file retention. The default active file size is 10MB with 50 retained rolled files; archives are compressed. Operational logging remains distinct from a future full audit/event-history system.

## Web Admin Panel

COMPLETE — initial implementation slice.

Implemented:
- Angular 22 + TypeScript.
- Tailwind CSS v4.
- npm dependency management with Node 24 convention via `.nvmrc`.
- Dashboard and admin shell.
- User listing, creation, detail, and deletion flows.
- Existing server authentication/session contracts; login sends `clientPlatform: "WEB"` without an `installationId`.
- `sessionStorage` Web Admin session persistence (`accessToken`, `refreshToken`, `sessionId`) with startup restoration before the first route decision.
- Reactive access-token refresh on `401` with a single shared refresh operation and one retry.
- Admin-only login boundary: valid non-admin credentials are rejected at `/login`, auth state is cleared, and no Web Admin session is retained.
- Unit tests (Vitest).

The first implementation slice does not include a WebSocket/STOMP client or production Angular/Gradle packaging. Those remain separate concerns.

## Application Packaging & Deployment

Angular/Gradle packaging: COMPLETE.

Implemented:
- `bootJar`-scoped Angular production build (plain Gradle `Exec`/`Copy`, no extra plugins; `npm ci` never runs, backend tests stay Node-free).
- Packaged bundle under `BOOT-INF/classes/static` in the executable JAR.
- SPA fallback for client-side routes without claiming `/api/**`, `/ws`, or static files.
- Security permits for the SPA shell and static assets; protected API routes unchanged.
- Standalone `java -jar ...` serving the web admin, REST API, and WebSocket endpoint against external PostgreSQL.

Locked deployment direction (unchanged):
- Angular production assets are packaged into the Spring Boot executable JAR.
- `java -jar ...` serves the web admin, REST API, and WebSocket endpoint from the same embedded-Tomcat application.
- The JAR remains capable of connecting to an externally provisioned PostgreSQL instance through externalized configuration.
- Docker provides an additional containerized deployment path, including a Compose-managed Samvaad application plus PostgreSQL.
- Public HTTPS terminates at a TLS-capable deployment edge such as a reverse proxy, tunnel, or managed edge.

Implementation work still to decide includes Docker image/Compose production details, release publication, one-command VPS installation/update, external configuration file generation, and the concrete TLS/reverse-proxy setup.

## Later / deferred

- reconnect/missed-event synchronization
- offline queues
- persistent read state/read receipts
- typing/presence
- delivery receipts
- push notifications
- message edits/deletes/replies
- blocking, unfriend, mute, archive
- horizontal scaling and external brokers
- general event bus
- end-to-end encryption
