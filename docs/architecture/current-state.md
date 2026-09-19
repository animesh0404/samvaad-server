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

## Application packaging and deployment: locked direction

The intended application delivery artifact is a Spring Boot executable JAR containing the Angular production static assets. Running the JAR with `java -jar ...` is intended to serve the web admin, REST API, and WebSocket endpoint from the same embedded-Tomcat application.

The JAR remains independently deployable against an externally provisioned PostgreSQL database through externalized configuration. Docker is an additional deployment/distribution path and is intended to support a containerized Samvaad application together with PostgreSQL through Compose.

The Angular/Gradle packaging integration is not yet implemented.

See ADR 0012 for the durable packaging/deployment decision.

## Public HTTPS architecture: locked direction

Public HTTPS is intended to terminate at the deployment edge in front of the Samvaad application. A TLS-capable reverse proxy, tunnel, or managed edge forwards traffic to the application. The application must support operation behind such an edge, including REST and WebSocket traffic. The specific reverse proxy/tunnel provider, certificate authority, domain, and forwarded-header configuration remain implementation decisions.

See ADR 0013 for the durable TLS termination decision.

## Architecture diagrams

- `current-authentication-session.puml` — JWT/session validation and authorization boundary.
- `current-user-profile.puml` — user/profile persistence model.
- `current-domain-model.puml` — current domain relationships.
- `current-data-model.puml` — PostgreSQL relationship/message tables and uniqueness invariants.
- `current-messaging-write-flow.puml` — HTTP direct-message write path.
- `current-messaging-read-flow.puml` — HTTP conversation/message reads.
- `current-realtime-message-flow.puml` — STOMP connect, subscription, send, persistence, and broadcast flow.

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
- end-to-end encryption
- rate limiting and stable machine-readable error codes
- dedicated conversation recency field if `updatedAt` later proves insufficient
- friend-gated profile visibility
- full audit/event-history policy beyond operational logging
- final web-admin visual design system and component inventory
- exact Angular/Gradle build integration
- Docker production image/Compose profile
- release publication and one-command VPS installer/update workflow
- concrete external configuration file generation/lookup mechanism
- concrete TLS/reverse-proxy/tunnel provider and certificate automation

## Known V1 limitation

Broadcast-after-commit is not crash-safe across process failure because Realtime V1 has no outbox. The simple broker is in-memory and intended for the first single-instance slice only.
