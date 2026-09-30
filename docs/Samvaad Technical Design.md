# Samvaad — Technical Design

> **Status:** Phases 1–5, Realtime V1, the E2EE device/recovery foundation, ciphertext transport, and the JVM Signal client implementation are implemented. Realtime transport and E2EE ciphertext transport are part of the current server architecture.
> **Focus:** Backend/domain/protocol/persistence/concurrency.
> **Rule:** Architectural invariants are fixed by ADRs; low-level mechanics are decided when implementation creates a concrete need.

---

# 1. Architecture Direction

Samvaad is server-first. The server owns authoritative state for identity, authentication, authorization, sessions, relationships, conversations, messages, ordering, canonical timestamps, read state, and synchronization. Clients consume server contracts and do not become the source of truth.

HTTP and STOMP are transports into the same messaging business logic. They must not maintain separate persistence, sequencing, friendship-authorization, or idempotency implementations.

# 2. Domain Model

## User

`userId`, `username`, `email`, password/authentication data, account lifecycle data, and role. User ID and username are immutable; username is unique and case-sensitive. V1 users are admin-provisioned.

## UserProfile

Profile data is separate from identity. Profile writes are self-only; profile visibility rules remain separately defined and friend-gated visibility is still deferred.

## Session

Session is first-class server state referenced by JWT `sid`. Every authenticated HTTP request validates the JWT and persisted session. The same JWT/session validation model is applied when a STOMP connection is authenticated.

### Client/session identity

The authentication primitive is the authenticated user plus persisted session. Client platform, client name/version, and similar information are session metadata rather than alternate identity authorities.

Installation identity is optional at the architectural level. It is a client/device lifecycle concern, not a prerequisite for authentication. Admin web UI, normal web clients, TUI clients, and portable desktop executables do not inherently require a stable installation identity. Android and iOS applications naturally have an installation/device lifecycle and may use an installation identifier when needed for future device-specific capabilities such as push notifications.

The current implementation accepts a missing `installationId`; blank/whitespace values normalize to null, while nonblank values remain supported. This preserves installation metadata as optional while keeping compatibility for clients that provide it.

The intended relationship is:

```text
User
  ├── Session
  ├── Session
  └── Session

Optional:
Session → client/device/installation metadata
```

The session remains the authentication and revocation boundary. See ADR 0010 for the durable installation-identity decision.

## FriendRequest / Friendship

An accepted friend-request row represents friendship. `areFriends(a,b)` is the relationship authorization input for distinct-user direct messaging.

## Conversation

Direct conversations normalize the participant pair and enforce uniqueness in the database. The current model contains `conversationId`, `participantA`, `participantB`, `lastSequenceNumber`, `createdAt`, and `updatedAt`.

## Message

Messaging is E2EE-ciphertext only. One logical message has server-owned identity, conversation, sender, sender device, sequence number, server timestamp, and client request UUID, plus one opaque per-recipient-device ciphertext envelope. The server controls sequence and timestamp values and never inspects ciphertext. Mailbox/history reads use sequence as the exclusive cursor; sync cursors are valid from `0` through the conversation `lastSequenceNumber`.

# 3. Database Invariants

- usernames are unique
- non-null emails are unique case-insensitively
- direct participant pairs are unique
- E2EE message `(conversation, sequence)` is unique
- request IDs are unique
- conversation creation and first-message persistence are atomic
- message request UUIDs are replay-safe (replays produce no second realtime event)
- one envelope and one mailbox entry exist per recipient device per message

# 4. Authorization Model

Authentication establishes the caller identity. Authorization establishes what the caller may do.

Direct-message creation between distinct users requires accepted friendship. Conversation listing and E2EE mailbox/history/cursor reads require conversation participation and a bound `ACTIVE` device. Realtime subscription requires the connection's own device identity; session or device revocation terminates live connections.

# 5. Direct Messaging (E2EE)

HTTPS submit (`POST /api/e2ee/messages`):

```text
authenticate
  ↓
resolve bound ACTIVE sender device
  ↓
decode + bound opaque envelopes (max 64 KiB each, max 10 per submit)
  ↓
verify sender, distinct + ACTIVE recipients, single recipient user
  ↓
verify friendship, reject self-message
  ↓
find/create conversation
  ↓
persist message + per-device envelopes + per-device mailbox entries
```

The service owns sequencing, timestamps, request-ID idempotency, and persistence. Unauthorized submits cannot create conversation state. Identical retries replay without a second realtime event; divergent reuse is a `409` conflict.

# 6. HTTP Read APIs

`GET /api/conversations/direct?limit=20&offset=0` lists participant conversations ordered by `updatedAt DESC`, then `conversationId ASC`.

`GET /api/e2ee/mailbox?limit=50` fetches the calling device's undelivered ciphertext items; `POST /api/e2ee/mailbox/ack` acknowledges by scoped delete. `GET /api/e2ee/conversations/{conversationId}/messages?afterSequence=0&limit=20` reads durable per-device envelopes in ascending server sequence. Unknown conversation is `404`; known non-participant is `403`.

# 7. Realtime STOMP/WebSocket

## Transport

- WebSocket endpoint: `/ws`
- simple broker prefix: `/topic` (single server)
- device destination: `/topic/devices/{deviceId}`
- no client send command; submission is HTTPS-only

## CONNECT authentication

The WebSocket HTTP handshake is permitted through the servlet security chain, but the STOMP connection is not considered authenticated until `CONNECT` is processed. `StompAuthInterceptor` reads `Authorization: Bearer <JWT>`, validates the access token and persisted session using the existing JWT/session rules, additionally requires the session to be bound to an `ACTIVE` E2EE device owned by the caller, and sets a `StompDevicePrincipal` containing `userId`, role, `sessionId`, and `deviceId` (`AuthenticatedUser` remains HTTP-only).

There is no separate WebSocket login mechanism, and the client never supplies its own device identity.

## Subscription authorization

A `SUBSCRIBE` is allowed only for the connection's own `/topic/devices/{deviceId}`, with session/device liveness revalidated at subscribe time. Any other destination is rejected identically so subscription attempts do not reveal whether another device exists. Session or device revocation terminates live connections server-side.

## Broadcast boundary

One persisted per-device ciphertext envelope is fanned out to each recipient device topic only after the persistence transaction commits. V1 has no outbox: a crash between commit and broadcast loses only the live hint, and the durable mailbox preserves every message. Broker failure never fails the HTTPS submission.

## Broker

Realtime V1 uses Spring's in-memory simple broker. Redis, Kafka, RabbitMQ, broker relay, horizontal scaling, and a general event bus are deferred.

# 8. Realtime Flow

```text
STOMP CONNECT
   ↓
JWT + persisted-session + ACTIVE-device validation
   ↓
StompDevicePrincipal
   ↓
SUBSCRIBE /topic/devices/{ownDeviceId}
   ↓
exact-match + liveness authorization
   ↓
(separately) POST /api/e2ee/messages
   ↓
validation + atomic batch persistence
   ↓
transaction commits
   ↓
per-device ciphertext fan-out to recipient device topics
   ↓
mailbox remains the fallback (never acknowledged by delivery)
```

# 9. Concurrency and Failure Boundary

The database remains authoritative for conversation uniqueness, message sequencing, and request-ID uniqueness. Failed or rolled-back submissions produce no realtime event. An idempotent replay returns the existing persisted message without a second realtime event.

The current session-validation logic is intentionally duplicated between the HTTP JWT filter and STOMP interceptor to avoid changing established HTTP authentication behavior during the realtime slice; a later auth refactor may extract the common validation logic.

# 10. Deferred Realtime Work

Reconnect/backfill UX beyond mailbox/history/cursor catch-up, offline queues beyond the per-device mailbox, read state/read receipts, typing/presence, delivery receipts, push notifications, message edits/deletes/replies, blocking/unfriend/mute/archive, horizontal scaling/external brokers, and a general event bus are outside Realtime V1. V1 E2EE is separately admitted and governed by ADR 0018.

# 11. Operational Logging

Samvaad operational logging is centered on meaningful business/application operations and useful security/authentication events, especially around service-layer business boundaries. Routine low-level repository CRUD/getters should not be logged mechanically.

Relevant log lines carry the fixed application identifier `[samvaad-server]` and a consistent correlation/trace identifier so a business operation can be followed across application components. Passwords, access tokens, refresh tokens, and equivalent secrets must never be logged; sensitive user or message data should be logged only when operationally justified.

Correlation IDs are established at the transport boundary and carried through MDC. Selective service-layer operations use `@OperationalLog` with `OperationalLoggingAspect` to add a generic DEBUG envelope containing operation name, success/failure outcome, and duration, with exception type on failure. The aspect does not create correlation IDs, log arguments/returns, expose sensitive data, or alter exceptions. Domain-specific INFO/WARN operational events remain explicit in the owning services.

Operational file logs use configuration-driven size-based rolling, compressed archives, and bounded retention. The default active file size is 10MB and the default retention target is 50 rolled files, with both values configurable. Full immutable audit/event history remains a separate future concern rather than being implied by operational logging.

# 12. Technical Invariants

1. Server is authoritative.
2. Authenticated identity comes from server authentication context.
3. Direct messaging between distinct users requires accepted friendship.
4. A direct participant pair has at most one conversation.
5. Conversation + first message creation is atomic.
6. A request UUID cannot create two messages.
7. Server sequence numbers determine message order.
8. Client time is never authoritative.
9. E2EE submission is HTTPS-only; there is no client STOMP send handler.
10. Realtime fan-out occurs only after the persistence transaction commits, one envelope per recipient device.
11. Device subscriptions are exact-match against the connection's own device identity.
12. STOMP `CONNECT` uses the existing access JWT plus persisted session validation plus bound-`ACTIVE`-device validation.
13. The simple broker is an in-memory V1 choice, not the horizontal-scaling architecture.
14. Authentication is session-based; installation identity is optional client/device metadata.

# 13. Web Admin Client Architecture

The web admin panel is a thin client of the Samvaad server. Its locked technology direction is Angular + TypeScript with Tailwind CSS for styling and layout. Bootstrap is not used and Angular Material is not a required component system for the initial admin panel.

The admin panel must consume the existing authentication, user-administration, and other server contracts rather than introducing a parallel backend or identity/session model. No global state-management framework is mandated until concrete application complexity requires one.

See ADR 0011.

# 14. Application Packaging and Deployment

The production application artifact is a Spring Boot executable JAR containing the Angular production static assets. The implemented Gradle build produces the Angular bundle and stages it under `BOOT-INF/classes/static` as part of `bootJar`, while keeping backend tests independent of the Node toolchain.

Running the resulting JAR with `java -jar ...` serves the web admin, REST API, and WebSocket endpoint from the same embedded Tomcat instance.

The JAR remains independently deployable against an externally provisioned PostgreSQL database. Deployment-sensitive configuration, including datasource details and secrets, is externalized rather than baked into the artifact.

Docker is an additional distribution/deployment path, not a hard runtime dependency. The implemented root Compose deployment runs the containerized Samvaad application together with PostgreSQL and consumes the published versioned application image. `scripts/release-image.sh` publishes release images; `scripts/start.sh`, `restart.sh`, and `stop.sh` manage the deployment lifecycle; `scripts/install.sh` and `scripts/install.ps1` provide one-command bootstrap on Unix-like systems and Windows.

Deployment credentials are supplied through `.env` rather than baked into the image or Compose file. The installers generate or preserve the database password and JWT secret without printing them.

See ADRs 0012, 0015, and 0016.

# 15. HTTPS and TLS Deployment Boundary

Samvaad supports two complementary TLS deployment modes.

### Direct-access deployments

For single-host and LAN deployments without a TLS-terminating edge, the Spring Boot application terminates HTTPS itself on port `8080`. The application generates and persists a self-signed RSA-2048 PKCS#12 identity, with configurable certificate SANs, before embedded Tomcat starts. WebSocket/STOMP therefore operates over WSS on the same listener without a separate transport configuration. See ADR 0017.

### Public/VPS deployments

For public deployments, TLS is terminated at a TLS-capable deployment edge in front of the Samvaad application. The edge may be a reverse proxy, tunnel, or managed ingress service. The public edge must support both REST traffic and the WebSocket connection at `/ws` and must preserve the application’s proxy compatibility.

The public edge remains the preferred production boundary described by ADR 0013. ADR 0017 does not replace that decision; it adds an application-managed TLS mode for deployments where no TLS edge is present. An edge may later forward to an HTTPS-enabled application listener as well.

Certificate issuance/renewal for the public edge, production domain/DNS configuration, exact forwarded-header settings, and deployment upgrade policy remain deployment decisions.

See ADR 0013.
