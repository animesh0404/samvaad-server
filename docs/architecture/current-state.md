# Current Architecture State

## Implemented

- PostgreSQL persistence with Liquibase-managed schema migrations.
- JPA entities and auditing.
- User provisioning, administration, JWT authentication, persisted sessions, refresh-token rotation, and per-session logout/revocation.
- ADMIN/USER roles and server-side authorization.
- Self-service email/password changes and profile PATCH semantics.
- Exact case-insensitive username discovery.
- Friend-request lifecycle and accepted-request-as-friendship model.
- Authenticated friends list read through `GET /api/friends`, derived from accepted friend-request relationships.
- Direct conversation persistence with normalized participant pairs and database uniqueness.
- Plain-text direct messages with server timestamps, monotonic per-conversation sequences, and request-ID idempotency.
- Atomic conversation/first-message creation and database-backed concurrency invariants.
- HTTP direct message send, conversation listing, and participant-only message reads.
- WebSocket/STOMP realtime transport:
  - `/ws` WebSocket endpoint
  - `/app` application prefix
  - `/topic` simple broker
  - `/app/chat.send` send command
  - `/topic/conversations/{conversationId}` conversation delivery
  - `Authorization: Bearer <JWT>` on STOMP `CONNECT`
  - existing JWT + persisted-session validation
  - authenticated `AuthenticatedUser` principal containing user/session identity
  - participant-only conversation subscriptions
  - shared message persistence/idempotency/authorization/sequencing logic
  - persistence before broadcast
- Installation identity is optional session metadata: login accepts a missing `installationId`, blank/whitespace values normalize to null, and nonblank values remain supported.
- Operational logging policy covering meaningful business/application and security/authentication events, correlation/trace context, secret avoidance, and bounded rolling file retention.
- Web Admin first implementation slice using Angular + TypeScript + Tailwind CSS, with admin dashboard and user-administration flows.

## User administration and hard deletion

V1 user deletion is an ADMIN-only hard-delete operation. It is implemented as explicit transactional domain cleanup rather than JPA cascade mappings or database `ON DELETE CASCADE` rules.

The deletion flow:

```text
lock user row
   ↓
sessions
   ↓
user profile
   ↓
friend requests (sender or recipient)
   ↓
sent messages
   ↓
messages in conversations involving the user
   ↓
conversations involving the user
   ↓
users row
   ↓
transaction flush/commit
   ↓
after-commit success log
```

The target user row is locked pessimistically before cleanup. Existing RESTRICT foreign keys remain database backstops. A residual integrity conflict caused by concurrent activity is translated to `409 Conflict`; ordinary successful deletion remains `204 No Content`.

Deleting the user also deletes conversations involving that user and the messages in those conversations. This is the V1 hard-delete semantic; the other participant's view of those conversations is removed as part of the deletion. Account suspension/pausing remains deferred.

## Friends list architecture

`GET /api/friends` is a read projection of the existing friendship model. The service queries `FriendRequest` rows in `ACCEPTED` state where the authenticated user is either sender or recipient, resolves the opposite user as the friend, maps to the existing safe `UserLookupDto` representation, filters self, and sorts by username ascending.

The API does not introduce a separate Friendship entity/table or an alternate relationship state. Authentication derives the caller from the server principal, so the endpoint cannot be used to retrieve another user's friend list by supplying a user ID.

## Client/session identity: current state

The current authentication implementation is session-based: JWT `sub` identifies the user and `sid` resolves the persisted server-side session. Installation identity is optional client/device metadata rather than a prerequisite for authentication. The `sessions.installation_id` database column is nullable following Liquibase migration `011-make-installation-id-nullable.yaml`.

Admin web UI, normal web clients, TUI clients, and portable desktop executables do not inherently require an installation identifier. Android and iOS clients may naturally use installation identity for device-specific lifecycle and future push-notification capabilities.

See ADR 0010 for the durable client/session and installation-identity decision.

## Web admin architecture: current implementation

The Web Admin is a thin client of the existing Samvaad server contracts. The selected UI stack is Angular 22 + TypeScript with Tailwind CSS v4 for styling/layout. Bootstrap is not used and Angular Material is not a required component system for the initial admin panel. No global state-management framework is mandated.

The implemented admin surface includes a dashboard, user listing, user creation, user detail, and user deletion. Login uses the existing `POST /api/auth/login` contract with `clientPlatform: "WEB"` and no `installationId`. Web Admin session tokens (`accessToken`, `refreshToken`, `sessionId`) are persisted in `sessionStorage` under the `samvaad.web-admin.*` keys, so a browser reload restores the session. Application startup restores that state via `provideAppInitializer` before the first route decision: a usable session bootstraps the current user, an expired access token rotates through the existing `POST /api/auth/refresh` call, and a rejected session is cleared back to `/login`. The server remains authoritative for session validity, rotation, revocation, and expiry.

The Web Admin enforces an admin-only login boundary: after successful credential authentication, the authenticated user's role is resolved through the existing user lookup. `ADMIN` users establish the Web Admin session; valid non-admin credentials are rejected on the login page, auth state is cleared, and no admin-panel session is retained. Server-side authorization remains authoritative for protected operations.

The current Web Admin slice has no WebSocket/STOMP client. It is an HTTP client of the existing server APIs.

See ADR 0011 for the durable web-admin technology decision.

## Application packaging and deployment: current implementation

The application delivery artifact is a Spring Boot executable JAR containing the Angular production static assets. Running the JAR with `java -jar ...` serves the web admin, REST API, and WebSocket endpoint from the same embedded-Tomcat application.

The Gradle packaging (`server/build.gradle`, no extra plugins) builds the Angular production bundle and stages it under `BOOT-INF/classes/static`, wired into `bootJar` only so backend tests stay Node-free. A small `WebAdminController` forwards SPA routes (`/`, `/login`, `/profile`, `/users…`) to `index.html` without claiming `/api/**`, `/ws`, or static files, and `SecurityConfig` permits the SPA shell plus static assets while authenticated API routes keep their existing protection.

The JAR remains independently deployable against an externally provisioned PostgreSQL database through externalized configuration. Docker is an implemented additional deployment/distribution path: `Dockerfile` builds the Angular bundle and Spring Boot executable JAR in multi-stage builds and the runtime uses `eclipse-temurin:25-jre-alpine`. The Docker workflows and image identities are: root `compose.yaml` running the published versioned image (`animesh0404/samvaad-server:<version>`) with a Compose-managed PostgreSQL instance for deployment; `compose.dev.yaml` overriding it to build and run the local-only `samvaad-server:dev` image (isolated `samvaad-dev` project/volume) for the iterative development loop; `scripts/build.sh` producing the portable `samvaad-server:latest` artifact plus tarball export (consumed by no Compose workflow); and `scripts/release-image.sh` publishing versioned release images. `scripts/build.sh` uses Docker Buildx/BuildKit with `--load`; `start.sh`, `restart.sh`, and `stop.sh` provide the deployment lifecycle without rebuilding or rotating an existing JWT secret.

See ADR 0012 for the durable packaging/deployment decision.

## Public HTTPS architecture: locked direction

Samvaad currently supports two HTTPS modes. For direct single-host/LAN deployments, the application terminates HTTPS itself on port `8080` using the persisted application-managed TLS identity defined by ADR 0017. For public/VPS deployments, HTTPS terminates at a TLS-capable deployment edge such as a reverse proxy, tunnel, or managed edge as defined by ADR 0013. The application remains proxy-compatible for REST and WebSocket traffic. The public reverse-proxy/tunnel provider, certificate authority, production domain, and forwarded-header configuration remain implementation decisions.

See ADR 0013 for the durable TLS termination decision.

## Architecture diagrams

- `current-authentication-session.puml` — JWT/session validation and authorization boundary.
- `current-user-profile.puml` — user/profile persistence model.
- `current-domain-model.puml` — current domain relationships.
- `current-data-model.puml` — PostgreSQL relationship/message tables and uniqueness invariants.
- `current-messaging-write-flow.puml` — HTTP direct-message write path.
- `current-messaging-read-flow.puml` — HTTP conversation/message reads.
- `current-realtime-message-flow.puml` — STOMP connect, subscription, send, persistence, and broadcast flow.


## E2EE enrollment architecture: locked, implementation pending

ADR 0018 defines the V1 E2EE enrollment state machine. Device enrollment is part of authentication/trust establishment rather than an independent post-login action.

- An account that has never completed E2EE enrollment uses **first-device bootstrap**. The first supported client authenticates with the provisioned account credentials, creates its cryptographic identity locally, completes recovery-code setup, and becomes the first ACTIVE E2EE device.
- An existing account with one or more ACTIVE E2EE devices requires **existing-device approval** before a newly presented device becomes trusted and receives a fully trusted authenticated session.
- An existing account that previously completed E2EE enrollment but has **zero ACTIVE E2EE devices** enters **recovery-required enrollment**. It is not treated as a new account. Account credential validation alone is insufficient; an unused account-level recovery code is required to complete authentication/enrollment.
- Recovery-code consumption is atomic with successful recovery enrollment.
- Recovery creates a new cryptographic device identity. It does not recreate a revoked/lost device identity. Encrypted chat-history restoration remains a separate recovery mechanism.

This is architectural state only; the enrollment implementation is not yet present in the current server.

## Realtime V1 boundary

The WebSocket handshake is servlet-security-permitted, while actual authentication occurs on STOMP `CONNECT`. Subscription authorization is participant-only. Unknown and non-participant subscription destinations are rejected identically to avoid existence leakage. STOMP sends enter the existing message service and broadcast only after successful persistence.

## Explicitly deferred

- reconnect/missed-event synchronization
- offline queues
- persistent read state/read receipts
- typing/presence, delivery receipts, push notifications
- message editing/deletion/replies
- blocking, unfriend, mute, archive
- horizontal scaling/external brokers/general event bus
- group E2EE / MLS implementation
- rate limiting and stable machine-readable error codes
- dedicated conversation recency field if `updatedAt` later proves insufficient
- friend-gated profile visibility
- full audit/event-history policy beyond operational logging
- final web-admin visual design system and component inventory
- concrete TLS/reverse-proxy/tunnel provider and certificate automation
- deployment upgrade policy

## Known V1 limitation

Broadcast-after-commit is not crash-safe across process failure because Realtime V1 has no outbox. The simple broker is in-memory and intended for the first single-instance slice only.
