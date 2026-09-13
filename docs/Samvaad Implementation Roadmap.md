# Samvaad Implementation Roadmap

## Phase 0 — Design reconciliation
COMPLETE.

## Phase 1 — Authentication & Authorization Boundary
COMPLETE.

Implemented login, refresh, JWT/session validation, session revocation/logout, user provisioning, profile GET/PATCH, admin listing/deletion, email change, password change, and related authorization/security behavior.

## Phase 2 — Exact Username Discovery
COMPLETE.

Implemented authenticated exact case-insensitive username lookup at `GET /api/users/lookup?username={username}` with restricted discovery representation.

## Phase 3 — Friend Request Vertical Slice
COMPLETE.

Implemented the authenticated lifecycle:

`no relationship → pending → accepted/friendship`

with terminal `REJECTED` and `CANCELLED` states.

Implemented:
- friend-request persistence and Liquibase migration
- sender/recipient ownership
- send request by discovered username
- incoming pending requests
- outgoing pending requests
- recipient accept/reject
- sender cancellation
- duplicate/reverse-direction pending protection
- already-friends protection
- re-request after rejected/cancelled requests
- internal `areFriends(a, b)` helper
- server-side authorization and integration/security coverage

Locked Phase 3 decisions:
- An `ACCEPTED` request row is the friendship record; no separate friendship table.
- `REJECTED`/`CANCELLED` requests remain history and a new request may be created later.
- Reverse-direction pending request returns `409 Conflict`; it is not auto-accepted.
- Both incoming and outgoing pending lists are exposed.
- Friend-gated profile reads remain deferred; Phase 3 only establishes the relationship model/helper.

## Phase 4 — Direct Messaging Vertical Slice
COMPLETE.

Implemented:
- direct conversation persistence for an unordered user pair
- database-enforced conversation uniqueness
- friendship authorization using `areFriends(a, b)`
- self-message rejection
- atomic conversation creation and first-message persistence
- plain-text message persistence
- server-generated message timestamp
- monotonic per-conversation server sequence number
- client-provided UUID request idempotency
- idempotent replay for the original request owner
- `409 Conflict` for foreign reuse of an existing request UUID
- authenticated `POST /api/conversations/direct/messages`
- unit, controller, integration, security, and database-invariant coverage

The Phase 4 slice intentionally does not add separate conversation CRUD or read/listing endpoints.

## Next implementation area

Conversation/message reads and listing are the next direct-messaging follow-up.

## Later / deferred

Realtime transport, reconnect/offline synchronization, read state, message editing/deletion, replies, blocking, unfriend, mute, archive, and other later-stage messaging/social features remain outside the completed slices until explicitly scoped.
