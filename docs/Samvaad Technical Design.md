# Samvaad — Technical Design

> **Status:** High-level server architecture is sufficiently defined to begin implementation.  
> **Focus:** Backend/domain/protocol/persistence/concurrency.  
> **Rule:** Implementation details that are not architectural invariants should be decided when the code creates a concrete need.

---

# 1. Architecture Direction

Samvaad is server-first.

The server owns the authoritative state for:

- user identity
- authentication
- authorization
- sessions
- conversations
- messages
- message ordering
- canonical timestamps
- read state
- blocking
- archive/mute state
- synchronization

The client/TUI consumes server contracts.

The client does not become the source of truth for domain state.

## 1.1 Development strategy

Use vertical slices.

The intended sequence is:

```text
protocol contract
      ↓
domain behavior
      ↓
persistence
      ↓
transport integration
      ↓
tests
      ↓
demonstrable end-to-end slice
```

Do not design every future feature before implementing the first slice.

---

# 2. Domain Model

## 2.1 User

Conceptual fields:

```text
userId
username
password credential / authentication data
account lifecycle data
```

Rules:

- userId is immutable
- username is immutable
- username is unique
- username is case-sensitive
- username matches `[a-zA-Z0-9_]{3,32}`

The authenticated user is derived from the server-side session.

---

## 2.2 UserProfile

Separate domain concept:

```text
userId
firstName
middleName
lastName
displayName
```

Display name:

```text
explicit displayName
    OR
firstName + lastName
```

Profile changes do not change user identity.

Messages do not snapshot profile names.

---

## 2.3 Session

Session is a first-class server-side concept even though authentication uses JWT.

Conceptual state:

```text
sessionId
userId
createdAt
lastSeenAt
expiresAt
revokedAt
refresh-token state
```

A session represents one authenticated login/device context.

Multiple sessions per user are allowed.

```text
User
├── Session A
├── Session B
└── Session C
```

Session state is distinct from account/domain state.

---

## 2.4 UserConversationState

Per-user state should conceptually contain:

```text
userId
conversationId
archived
muted
lastReadSequenceNumber
```

These values must not be stored as global conversation state because archive, mute, and read position are user-specific.

---

## 2.5 Conversation

A direct conversation contains two distinct users or one user for self-chat.

Conceptual identity:

```text
conversationId
participantA
participantB
```

For uniqueness, normalize the participant pair:

```text
(min(userA, userB), max(userA, userB))
```

and enforce uniqueness in the database.

---

## 2.6 Message

Conceptual model:

```text
messageId
conversationId
senderUserId
sequenceNumber

content

clientTimestamp
serverTimestamp

requestId

repliedToMessageId

edited
editedAt

deleted
deletedAt
```

The exact physical schema may differ.

Important distinction:

- `messageId` = server identity
- `requestId` = idempotency identity for a client command
- `sequenceNumber` = ordering within conversation

---

# 3. Database Invariants

The database should enforce invariants wherever correctness depends on concurrency.

## 3.1 Username uniqueness

```text
UNIQUE(username)
```

Because usernames are case-sensitive, do not silently apply case-folding.

---

## 3.2 Conversation uniqueness

```text
UNIQUE(normalizedParticipantA, normalizedParticipantB)
```

This prevents:

```text
Conversation 101: A ↔ B
Conversation 102: A ↔ B
```

even when two requests race.

---

## 3.3 First-message atomicity

Conversation creation and first message creation occur in one transaction.

Conceptually:

```text
BEGIN

find/create conversation
check authorization
validate message
persist message

COMMIT
```

Rollback removes the entire newly-created conversation/message operation.

---

## 3.4 Request idempotency

A successful send request must be replay-safe.

The server needs a durable association between:

```text
requestId → accepted result/message
```

The exact table/index shape is an implementation decision.

The invariant is not.

---

## 3.5 Read monotonicity

The server must never accept:

```text
newReadSequence < currentReadSequence
```

The transition is:

```text
lastReadSequence = max(current, requested)
```

or an equivalent transactional guard.

---

## 3.6 Delete terminality

A deleted message cannot later be edited or deleted again.

A tombstone remains durable.

---

# 4. Conversation Creation Race

Two users can race:

```text
A → create conversation
B → create conversation
```

Both may initially observe no conversation.

The database uniqueness constraint decides the winner.

The losing operation handles the conflict, retrieves the existing conversation, and persists its own message.

We do not introduce distributed locks merely to prevent this race.

The database is the synchronization authority for this invariant.

---

# 5. Message Lifecycle

## 5.1 Send

Conceptually:

```text
client
  ↓
SEND_MESSAGE(requestId, conversation target, content)
  ↓
authenticate
  ↓
authorize
  ↓
validate
  ↓
idempotency lookup
  ↓
find/create conversation
  ↓
allocate server sequence
  ↓
persist message
  ↓
commit
  ↓
MESSAGE_ACCEPTED
  ↓
NEW_MESSAGE / delivery events
```

Exact event ordering will be finalized during implementation.

---

## 5.2 Duplicate send

```text
SEND(requestId=X)
    ↓
message M created

SEND(requestId=X)
    ↓
existing accepted result found
    ↓
no new message
```

---

## 5.3 Edit

```text
EDIT_MESSAGE
    ↓
authenticate
    ↓
verify sender owns message
    ↓
verify message not deleted
    ↓
persist edit
    ↓
MESSAGE_UPDATED
```

Concurrent accepted edits use server acceptance order; last accepted write wins.

---

## 5.4 Delete

```text
DELETE_MESSAGE
    ↓
authenticate
    ↓
verify sender owns message
    ↓
verify not already deleted
    ↓
write tombstone
    ↓
MESSAGE_DELETED
```

After this:

```text
EDIT → reject
DELETE → reject
```

---

# 6. Reply Model

A reply carries:

```text
repliedToMessageId
```

The server should validate that the referenced message belongs to the same conversation.

A reply is not copied into the content field.

Deleting the parent message does not destroy the reply. The parent remains represented by its tombstone.

---

# 7. Message Ordering & Time

The server controls:

```text
serverTimestamp
sequenceNumber
```

Client time is informational:

```text
clientTimestamp
```

The server must never use clientTimestamp as authoritative ordering.

A slow or incorrectly clocked client cannot rewrite conversation chronology.

---

# 8. Message Validation

V1:

```text
content is non-null
content contains at least one non-whitespace character
encoded payload <= 64 KB
content is plain text
```

Meaningful newlines/paragraph breaks are preserved.

The server is authoritative even if the client validates the same rules.

---

# 9. Read & Delivery State

Delivery and read are separate from message persistence.

## Read

Account-level:

```text
User
  └── Conversation
       └── lastReadSequenceNumber
```

Monotonic:

```text
100 → 101 → 102
```

never:

```text
102 → 99
```

A message is read only after actual display.

## Multiple sessions

Because read state is account-level:

```text
Laptop → marks through 120
Phone  → now knows account read position is 120
```

Exact propagation/event mechanics are implementation-time work.

---

# 10. Blocking

Block relationship is directional:

```text
blockerUserId
blockedUserId
```

But its messaging effect is bilateral:

```text
block exists
    ↓
conversation becomes read-only for both
    ↓
new send rejected
```

Error:

```text
CONVERSATION_BLOCKED
```

with:

```text
You cannot send messages because this conversation is blocked.
```

The check must occur before conversation creation/message persistence.

No blocked message is queued.

Self-blocking is rejected.

---

# 11. Archive & Mute

These are per-user conversation state.

```text
UserConversationState
├── archived
├── muted
└── lastReadSequenceNumber
```

Auto-unarchive:

```text
default = false
configurable per user
```

Mute affects notification/highlight behavior, not message synchronization.

---

# 12. Authentication Architecture

## 12.1 Registration

```text
REGISTER
    ↓
validate username/password/profile
    ↓
bcrypt password
    ↓
persist User + UserProfile
```

Plaintext password never reaches durable storage.

---

## 12.2 Login

```text
LOGIN(username, password)
       ↓
verify bcrypt
       ↓
create Session
       ↓
issue JWT access token
       ↓
issue refresh token
```

---

## 12.3 Access token

Current locked policy:

```text
JWT access token
lifetime = 1 day
```

The exact JWT claims and signing algorithm remain open until implementation.

The token should identify the user and session sufficiently for the server to bind requests/connections to the correct session.

---

## 12.4 Refresh token

Current locked policy:

```text
refresh lifetime = 30 days
```

The expiry is sliding.

Every successful refresh:

```text
old refresh token
      ↓
invalidate
      ↓
new refresh token
      +
new access token
```

The 30-day expiry is calculated from the successful rotation.

---

## 12.5 Reactive refresh

There is no scheduled refresh loop.

The client refreshes when an actual access-token expiry/authentication failure requires it.

For a persistent connection, the exact expired-token/reconnect sequence is an implementation-time protocol decision.

---

## 12.6 Refresh-token reuse

If an already-consumed refresh token is presented:

```text
reject
```

V1 does not automatically revoke the entire session.

This is deliberately a simpler policy.

---

## 12.7 Session-scoped rotation

Every session has its own refresh chain.

```text
Laptop Session
    Refresh A → B → C

Phone Session
    Refresh X → Y → Z
```

This avoids cross-device refresh races.

---

# 13. Session Revocation

Normal logout:

```text
current session → revoked
```

Other sessions remain valid.

If a session is revoked:

```text
session revoked
      ↓
associated authenticated connection invalidated
      ↓
connection closed
```

Expiry follows the same principle.

---

# 14. Socket Authentication

After successful authentication, the server associates:

```text
connection
   ↓
sessionId
   ↓
userId
```

Subsequent commands do not need to carry a trusted userId.

The server derives authority from the authenticated connection/session context.

If a session becomes invalid, the server rejects further commands and terminates the connection.

---

# 15. Protocol Direction

High-level command/event vocabulary:

### Commands

```text
REGISTER
LOGIN
REFRESH
SEND_MESSAGE
MARK_READ
EDIT_MESSAGE
DELETE_MESSAGE
```

### Events

```text
LOGIN_SUCCESS
MESSAGE_ACCEPTED
NEW_MESSAGE
MESSAGE_DELIVERED
CONVERSATION_READ
MESSAGE_UPDATED
MESSAGE_DELETED
ERROR
```

This is intentionally a direction rather than a fully frozen wire specification.

Exact envelope fields, correlation IDs, payloads, and serialization should be finalized during the first implementation slice.

---

# 16. Errors

Errors should have stable machine-readable codes.

Examples already decided:

```text
USER_NOT_FOUND
CONVERSATION_BLOCKED
```

The client may render friendly text, but server error codes remain stable protocol values.

The exact complete error taxonomy is implementation-time work.

---

# 17. Synchronization Direction

Startup:

```text
authenticate
    ↓
conversation list / unread state
    ↓
open conversation
    ↓
latest message page
    ↓
older pages on demand
```

History is permanent.

The server does not need to load all message history into memory.

Reconnect and missed-event recovery are intentionally deferred to implementation.

---

# 18. Explicitly Implementation-Time

Do not block the project on these now:

- JWT signing algorithm
- JWT key storage/rotation
- exact JWT claims
- exact database schema
- migration tooling
- socket/WebSocket library
- framing
- sequence allocation implementation
- exact refresh endpoint wire format
- retry mechanics
- reconnect/missed-event recovery
- rate limiting
- logging/metrics/tracing
- audit policy
- exact pagination cursor
- Unicode/wire encoding details
- deployment topology

These are important, but they become easier to decide once the first vertical slice exists.

---

# 19. Technical Invariants

The following are the core backend invariants:

1. Server is authoritative.
2. Authenticated identity comes from session context.
3. Username is immutable, unique, and case-sensitive.
4. A direct participant pair has at most one conversation.
5. Self-chat is valid.
6. Conversation + first message creation is atomic.
7. A request UUID cannot create two messages.
8. Server sequence numbers determine message order.
9. Client time is never authoritative.
10. Read position never moves backwards.
11. Delete is terminal.
12. Blocked conversations cannot accept new messages.
13. Message history is permanent.
14. Client validation never replaces server validation.
15. Session revocation invalidates its active connection.
16. Refresh-token rotation is session-scoped.
17. Old refresh tokens are rejected after successful rotation.
