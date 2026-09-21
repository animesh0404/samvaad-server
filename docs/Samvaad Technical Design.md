# Samvaad — Technical Design

> **Status:** Phases 1–5 and Realtime V1 are implemented. The realtime transport is now part of the current server architecture.
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

Messages have server-owned identity, conversation, sender, sequence number, content, server timestamp, and client request UUID. The server controls sequence and timestamp values. The HTTP read API uses sequence as its exclusive cursor.

# 3. Database Invariants

- usernames are unique
- non-null emails are unique case-insensitively
- direct participant pairs are unique
- message `(conversation, sequence)` is unique
- request IDs are unique
- conversation creation and first-message persistence are atomic
- message request UUIDs are replay-safe

# 4. Authorization Model

Authentication establishes the caller identity. Authorization establishes what the caller may do.

Direct-message creation between distinct users requires accepted friendship. Conversation/message HTTP reads require conversation participation. Realtime conversation subscriptions and sends require authenticated participation in the target conversation.

# 5. Direct Messaging

HTTP send:

```text
authenticate
  ↓
identify recipient
  ↓
verify friendship
  ↓
find/create conversation
  ↓
persist message
```

The service owns sequencing, timestamps, request-ID idempotency, and persistence. Unauthorized sends cannot create conversation state.

# 6. HTTP Read APIs

`GET /api/conversations/direct?limit=20&offset=0` lists participant conversations ordered by `updatedAt DESC`, then `conversationId ASC`.

`GET /api/conversations/direct/{conversationId}/messages?afterSequence=0&limit=20` reads participant messages in ascending server sequence. Unknown conversation is `404`; known non-participant is `403`.

# 7. Realtime STOMP/WebSocket

## Transport

- WebSocket endpoint: `/ws`
- STOMP application prefix: `/app`
- simple broker prefix: `/topic`
- send command: `/app/chat.send`
- conversation destination: `/topic/conversations/{conversationId}`

## CONNECT authentication

The WebSocket HTTP handshake is permitted through the servlet security chain, but the STOMP connection is not considered authenticated until `CONNECT` is processed. `StompAuthInterceptor` reads `Authorization: Bearer <JWT>`, validates the access token and persisted session using the existing JWT/session rules, and sets an `AuthenticatedUser` principal containing `userId`, role, and `sessionId`.

There is no separate WebSocket login mechanism.

## Subscription authorization

A `SUBSCRIBE` to `/topic/conversations/{conversationId}` is allowed only when the authenticated principal is a participant. Unknown and non-participant conversation subscriptions are rejected identically so subscription attempts do not reveal conversation existence.

## Send command

The client supplies only:

```json
{
  "conversationId": "...",
  "content": "...",
  "requestId": "..."
}
```

The client does not supply authoritative sender identity, sequence, or server timestamp. `ChatController` delegates to `MessageService.sendMessageToConversation`, which reuses the existing friendship authorization, persistence, sequencing, timestamp, and idempotency logic.

## Broadcast boundary

The persisted `MessageDto` is broadcast to `/topic/conversations/{conversationId}` only after the message service successfully returns from its transactional operation. V1 has no outbox, so this is not crash-recoverable across a process failure; that reliability/scaling problem is deferred.

## Broker

Realtime V1 uses Spring's in-memory simple broker. Redis, Kafka, RabbitMQ, broker relay, horizontal scaling, and a general event bus are deferred.

# 8. Realtime Flow

```text
STOMP CONNECT
   ↓
JWT + persisted-session validation
   ↓
AuthenticatedUser principal
   ↓
SUBSCRIBE /topic/conversations/{id}
   ↓
participant authorization
   ↓
SEND /app/chat.send
   ↓
MessageService
   ↓
friendship + idempotency + sequencing + persistence
   ↓
transaction succeeds
   ↓
MessageDto broadcast to conversation topic
```

# 9. Concurrency and Failure Boundary

The database remains authoritative for conversation uniqueness, message sequencing, and request-ID uniqueness. A failed STOMP send persists and broadcasts nothing. An idempotent replay returns the existing persisted message through the shared service path.

The current session-validation logic is intentionally duplicated between the HTTP JWT filter and STOMP interceptor to avoid changing established HTTP authentication behavior during the realtime slice; a later auth refactor may extract the common validation logic.

# 10. Deferred Realtime Work

Reconnect/missed-event synchronization, offline queues, read state/read receipts, typing/presence, delivery receipts, push notifications, message edits/deletes/replies, blocking/unfriend/mute/archive, horizontal scaling/external brokers, end-to-end encryption, and a general event bus are outside Realtime V1.

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
9. HTTP and STOMP message sends use the same message business logic.
10. Realtime broadcast occurs only after successful persistence.
11. Conversation subscriptions are participant-only.
12. STOMP `CONNECT` uses the existing access JWT plus persisted session validation.
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

# 15. Public HTTPS Boundary

For public/VPS deployments, TLS is terminated at a deployment edge in front of the Samvaad application. The edge may be a reverse proxy, tunnel, or managed ingress service. The application may therefore continue to listen on an internal HTTP port while public traffic is served over HTTPS.

The deployment edge must support both HTTP API traffic and the WebSocket connection at `/ws`. The application must remain proxy-compatible; its architecture must not require public certificate material to be embedded in the application JAR.

The concrete reverse proxy/tunnel provider, certificate automation, domain/DNS configuration, forwarded-header settings, and deployment upgrade policy remain deployment decisions.

See ADR 0013.
