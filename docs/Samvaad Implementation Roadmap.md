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
- authorization is evaluated before any conversation lookup/creation, so unauthorized sends cannot create conversation state
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

## Phase 5 — Conversation/Message Reads & Listing
COMPLETE.

Implemented:
- `GET /api/conversations/direct?limit=20&offset=0` for authenticated users to list their direct conversations
- conversation-list ordering by recent `updatedAt` descending with `conversationId` ascending as deterministic tiebreaker
- offset/limit validation with `limit` restricted to 1–100 and non-negative offset
- `GET /api/conversations/direct/{conversationId}/messages?afterSequence=0&limit=20` for participant-only message reads
- exclusive message sequence cursor (`afterSequence`) with ascending sequence ordering
- message-read validation with `limit` restricted to 1–100 and non-negative cursor
- `404` for an unknown conversation and `403` for a known conversation whose caller is not a participant
- nullable `otherParticipantUsername` when the other user has been admin-deleted
- unit, controller, and Testcontainers integration coverage for authentication, scoping, ordering, pagination, empty results, and invalid parameters

Design notes:
- Conversation lists use offset/limit because the current model has no dedicated stable single-column conversation cursor.
- Message reads use the existing monotonic per-conversation sequence as a message-specific cursor; no general pagination framework is introduced.
- Conversation-list recency currently uses JPA-audited `updatedAt`, which is refreshed by the existing message write path through the conversation sequence mutation. A dedicated `last_message_at` field can be revisited if read/list requirements grow.
- Conversation-list pagination uses the existing Spring Data derived-query approach and handles non-aligned offsets by over-fetching within the selected page and dropping the required head rows.

## Next implementation area

Realtime messaging transport and delivery (STOMP/WebSocket).

## Later / deferred

Read state, message editing/deletion, replies, reconnect/offline synchronization, blocking, unfriend, mute, archive, and other later-stage messaging/social features remain outside the completed slices until explicitly scoped.
