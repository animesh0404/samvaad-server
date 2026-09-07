# Samvaad — Product & Design Decisions

> **Status:** High-level backend design complete; implementation phase begins next.  
> **Role:** Product behavior, design rationale, locked decisions, and deferred decisions.  
> **Canonical rule:** Do not reopen a decision marked **LOCKED** unless new implementation evidence forces a change.

---

## 1. Project Direction

Samvaad is being designed as a server-first messaging system.

The current priority is the **backend/server**. The TUI/client is a separate project/repository concern and should consume stable server contracts rather than drive premature client-specific design.

We deliberately stopped short of writing a complete end-to-end specification before writing code. The goal is to lock the important domain invariants and security/product behavior, then let implementation resolve low-level details when concrete constraints appear.

### Design principle

Prefer:

- server-authoritative state
- simple invariants
- explicit contracts
- database-enforced correctness where appropriate
- minimal V1 complexity
- implementation-driven decisions for low-level mechanics

Avoid:

- unnecessary behavior analytics
- speculative abstractions
- distributed locking where a database invariant is enough
- duplicating authoritative state
- designing future features before V1 needs them

---

# 2. User Identity

## 2.1 Internal identity

**LOCKED**

`userId` is the permanent internal identity of a user.

It is the identifier referenced by:

- messages
- conversations
- sessions
- profiles
- block relationships
- per-user conversation state

The client must never be trusted to declare its authenticated identity. The server derives the authenticated `userId` from the session.

---

## 2.2 Username

**LOCKED**

Username is:

- immutable
- unique
- case-sensitive
- used for exact user discovery
- not the internal identity

Examples:

```text
Rahul
rahul
RAHUL
```

are three distinct usernames.

V1 validation:

```text
[a-zA-Z0-9_]{3,32}
```

Therefore:

- minimum 3 characters
- maximum 32 characters
- ASCII letters
- digits
- underscore
- no spaces
- no punctuation
- no Unicode characters

The exact username is the discovery key.

---

## 2.3 User vs UserProfile

**LOCKED**

Authentication/account identity and profile data are separate backend concepts.

### User

Conceptually owns:

```text
userId
username
authentication/account lifecycle data
```

### UserProfile

Conceptually owns:

```text
userId
firstName
middleName
lastName
displayName
future profile fields
```

This prevents the authentication identity from becoming a giant profile object.

---

## 2.4 Display name

**LOCKED**

An explicit `displayName` takes precedence.

If it is not set:

```text
effectiveDisplayName = firstName + " " + lastName
```

Middle name is stored but is not part of the fallback display name.

If the last name is unavailable, the fallback can reduce to first name.

Messages reference `senderUserId`; they do not snapshot a sender's display name. The current profile is resolved when displayed.

---

# 3. Registration & Passwords

## 3.1 Self-registration

**LOCKED**

Self-registration is allowed.

---

## 3.2 Password storage

**LOCKED**

Passwords use bcrypt with a generated salt.

The plaintext password:

- is accepted only at the authentication boundary
- is never persisted
- is never placed in domain entities
- is never placed in events
- must not be logged

The bcrypt work factor is configurable.

The exact configuration/default can be selected during implementation.

---

# 4. Sessions & Authentication

The authentication model was intentionally changed during design.

The earlier opaque-token concept is **replaced** by JWT access tokens plus rotating refresh tokens.

## 4.1 Token model

**LOCKED**

```text
Access token  → JWT
Refresh token → long-lived rotating credential
```

### Access token

**LOCKED**

```text
lifetime = 1 day
```

### Refresh token

**LOCKED**

```text
lifetime = 30 days
```

The 30-day lifetime is a **sliding inactivity window**.

Every successful refresh:

1. invalidates the old refresh token
2. issues a new refresh token
3. issues a new access token
4. resets the refresh-token expiry to 30 days from that successful rotation

Example:

```text
Aug 01 → login
Aug 10 → refresh → refresh expiry moves forward 30 days
Aug 25 → refresh → expiry moves forward again
```

If the user stops using the client long enough for the refresh token to expire, re-authentication is required.

---

## 4.2 Refresh behavior

**LOCKED**

Refresh is **reactive**, not proactive.

The client does not run a refresh timer merely because an access token is approaching expiry.

Instead:

```text
authenticated operation
        ↓
access token expired
        ↓
refresh
        ↓
new access token + new refresh token
        ↓
retry/re-establish as appropriate
```

The refresh happens in the background from the user's perspective.

---

## 4.3 Refresh-token rotation

**LOCKED**

Every successful refresh rotates the refresh token.

```text
Refresh A
   ↓ successful refresh
Refresh B
```

`Refresh A` is no longer valid.

### Refresh-token reuse

**LOCKED for V1**

If an old refresh token is presented again:

```text
Refresh A
   ↓
already consumed
   ↓
reject
```

We do **not** automatically revoke the entire session in V1.

This is deliberately simpler than a full token-family compromise response.

---

## 4.4 Session scope

**LOCKED**

Refresh-token rotation is session-scoped.

Each login/device/session has its own refresh-token chain:

```text
User
├── Laptop Session
│   └── Refresh chain A
└── Phone Session
    └── Refresh chain B
```

Two devices therefore cannot accidentally race on the same refresh token.

Multiple sessions remain active simultaneously.

---

## 4.5 Session lifecycle

**LOCKED**

Normal logout:

```text
current session → revoked
other sessions → remain active
```

Session revocation:

```text
session revoked
     ↓
authenticated connection invalidated
     ↓
connection closed
```

Session expiry follows the same principle.

A revoked/expired session cannot continue using an already-established authenticated connection.

---

## 4.6 Absolute session lifetime

**DEFERRED**

No arbitrary absolute session lifetime is imposed in V1.

A 1-year maximum was considered and rejected as a default because it creates user inconvenience without being architecturally necessary.

Current security boundaries are:

- 1-day access-token lifetime
- 30-day sliding refresh expiry
- refresh-token rotation
- session-level revocation
- logout
- refresh-token reuse rejection

A future absolute lifetime can be introduced as a security policy if real requirements justify it.

---

# 5. Direct Conversations

## 5.1 One conversation per pair

**LOCKED**

A direct conversation between two users exists at most once.

The database must enforce this invariant.

A normalized participant representation can be used:

```text
(min(userA, userB), max(userA, userB))
```

with a uniqueness constraint.

---

## 5.2 Self-conversations

**LOCKED**

A user may create a direct conversation with themselves.

Self-blocking is not allowed.

No special "notes" domain is required; self-chat follows normal message semantics.

---

## 5.3 Atomic first message

**LOCKED**

Creating a new conversation and creating its first message occur in one database transaction.

Conceptually:

```text
BEGIN

find/create conversation
validate sender
create first message

COMMIT
```

Failure rolls back the entire operation.

We must never intentionally leave:

```text
conversation exists
first message missing
```

as the result of a failed first send.

---

## 5.4 Conversation creation race

**LOCKED**

If both users race to create the same direct conversation:

```text
Request A ─┐
           ├── database unique constraint
Request B ─┘
```

one creation wins.

The losing transaction handles the uniqueness conflict, retrieves the existing conversation, and persists its own message.

The final state is one conversation containing both messages.

Application-level check-then-insert is not sufficient.

---

## 5.5 Block check before creation

**LOCKED**

Block authorization is checked before conversation creation or message persistence.

A blocked send cannot create an empty conversation.

---

## 5.6 Unknown username

**LOCKED**

An exact username that does not exist produces:

```text
USER_NOT_FOUND
```

No conversation or message is created.

---

# 6. Message Semantics

## 6.1 Plain text

**LOCKED**

V1 messages are plain text.

Formatting semantics:

- meaningful newlines are preserved
- paragraph breaks are preserved
- HTML is not interpreted
- Markdown is not interpreted
- arbitrary rich text is not supported

Example:

```text
Hello Rahul,

How are you?

Let's meet tomorrow.
```

remains a multi-paragraph message.

Client display decisions belong to the client project.

---

## 6.2 Empty messages

**LOCKED**

Normal user-generated messages must contain at least one non-whitespace character.

Rejected:

```text
""
"   "
"\n\n"
```

Accepted:

```text
"Hello"
"  Hello  "
"Hello\n\nHow are you?"
```

The server performs the authoritative validation.

---

## 6.3 Message size

**LOCKED**

Maximum text-message payload:

```text
64 KB
```

The limit is conceptually applied to the encoded payload bytes rather than an arbitrary character count.

The client may validate for UX, but the server must enforce the limit.

Failure uses a stable machine-readable error code.

---

## 6.4 Message identity

Each message has a server-generated immutable `messageId`.

A send request also carries a client-generated UUID `requestId`.

The `requestId` exists for idempotency and is not the message identity.

---

## 6.5 Idempotent send

**LOCKED**

Retrying the same `requestId` must not create a second message.

Conceptually:

```text
requestId X
    ↓
accepted
    ↓
message M
```

A retry with `requestId X` resolves to the same logical result instead of creating message `M2`.

The mapping must survive reconnects.

---

## 6.6 Ordering

**LOCKED**

The server is authoritative for message ordering.

Messages receive server-controlled sequence numbers within a conversation.

Client timestamps are informational only.

A client timestamp:

- does not establish ordering
- does not establish server chronology
- is not a reason to reject a message

Server timestamps are authoritative.

---

## 6.7 Editing

**LOCKED**

A sender owns their message mutation rights.

Editing uses last-write-wins based on server acceptance order.

Editing updates:

```text
edited = true
editedAt = server time
```

The message keeps its identity and sequence.

A successful edit produces a message-update event.

---

## 6.8 Deletion

**LOCKED**

Deletion is terminal.

A deleted message remains as a tombstone rather than disappearing from the durable history.

Once deleted:

```text
edit → rejected
delete → rejected
```

A delete event is emitted for synchronization.

---

## 6.9 Replies

**LOCKED**

V1 supports replying to a particular message using:

```text
repliedToMessageId
```

This is a first-class message relationship.

The relationship is metadata, not part of the textual content.

If the parent message is later deleted, the reply relationship remains meaningful while the deleted parent is represented by its tombstone.

---

## 6.10 Reactions

**LOCKED as V1 exclusion**

Reactions are explicitly out of V1.

Do not add reaction state to the V1 message model unless implementation requirements change.

---

# 7. History & Synchronization

## 7.1 Permanent history

**LOCKED**

Message history is permanent.

We already expect paginated history, so the server does not need to load the entire conversation into memory.

---

## 7.2 Pagination

**LOCKED direction**

Load the latest page first.

Older messages are retrieved as the user moves backward through history.

Exact pagination cursor mechanics are an implementation-time decision.

---

## 7.3 Read position

**LOCKED**

Read position is account-level and monotonic.

Conceptually:

```text
lastReadSequenceNumber
```

can only move forward.

It can never be intentionally moved backwards.

Read state is separate from message content and mutation state.

---

## 7.4 Read definition

**LOCKED**

A message becomes read only after it has actually been displayed to the user.

The client reports read progress to the server; the server validates the monotonic transition.

---

## 7.5 Offline synchronization

**LOCKED at the high level**

The server should not force the client to download all history on startup.

The intended model is:

```text
authenticate
   ↓
conversation list / unread state
   ↓
open conversation
   ↓
load latest message page
   ↓
load older pages on demand
```

Exact reconnect/missed-event recovery will be designed during implementation.

---

# 8. Blocking

## 8.1 Block semantics

**LOCKED**

Blocking affects the existing direct conversation from **both perspectives**.

Once blocked:

```text
conversation → read-only
```

for both blocker and blockee.

This prevents the blocker from accidentally sending a message after blocking.

---

## 8.2 Immediate send rejection

**LOCKED**

No message is queued while blocked.

The server rejects the send immediately.

Error:

```text
CONVERSATION_BLOCKED
```

User-facing text:

```text
You cannot send messages because this conversation is blocked.
```

---

## 8.3 No self-blocking

**LOCKED**

A user cannot block themselves.

Self-blocking is rejected as unnecessary complexity.

---

# 9. Archive & Mute

## 9.1 Per-user state

**LOCKED**

Archive and mute belong to the individual user's conversation state, not the global conversation.

---

## 9.2 Auto-unarchive

**LOCKED**

Auto-unarchive is configurable per user.

Default:

```text
auto-unarchive = false
```

---

## 9.3 Mute

**LOCKED**

Mute is required for V1.

It suppresses notification/highlight behavior without preventing synchronization or message delivery.

Exact client notification UX is deferred to the TUI project.

---

# 10. Conversation List

**LOCKED high-level behavior**

Conversations are ordered by most recent activity, based on server-authoritative message activity rather than when the user opened the conversation.

A conversation preview represents the latest message's current content.

Examples:

- edited message → current edited content
- deleted message → `"This message was deleted."`
- reply → actual message content, not reply metadata

Preview length is capped at approximately 100 characters for the list payload.

Exact truncation behavior is implementation/client detail.

---

# 11. Attachments

**LOCKED V1 exclusion**

Text chat has no attachments in V1.

Attachments may later be modeled as a separate entity:

```text
Message
   │
   └── MessageAttachment
```

Future attachment design may include:

- per-file limits
- per-message attachment count
- file-type policies
- streaming
- external/object storage for large files

Do not implement these in the V1 text-message path.

---

# 12. Presence & Sessions

Presence is session-derived at a high level.

A user is online if at least one authenticated session is online.

Richer presence behavior is deferred.

Session state remains distinct from account-level state:

```text
User
├── Session A
├── Session B
└── Session C
```

while:

```text
conversation read state
block state
archive state
mute state
message history
```

are account/domain state rather than per-socket state.

---

# 13. Protocol Direction

The high-level protocol direction is locked enough to begin implementation.

Expected operations/events include:

```text
LOGIN
SEND_MESSAGE
MARK_READ
EDIT_MESSAGE
DELETE_MESSAGE
```

and future refresh/session operations.

Expected server-side events include:

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

The exact envelope and payload fields will be specified while implementing the first vertical slice.

---

# 14. Decision Status

### LOCKED

- Server-authoritative ordering/state
- Immutable case-sensitive username
- Username grammar
- User/UserProfile separation
- Self-registration
- bcrypt password hashing
- Direct conversation uniqueness
- Self-chat
- Atomic conversation + first message
- Race resolution through DB uniqueness
- Plain text
- 64 KB message limit
- Non-empty message rule
- UUID send idempotency
- Server timestamps/sequence
- Last-write-wins editing
- Terminal deletion
- Reply-to-message
- Permanent history
- Pagination direction
- Monotonic account-level read position
- Blocking semantics
- Blocked-send error
- Per-user archive/mute
- No auto-unarchive by default
- No V1 reactions
- No V1 attachments
- JWT access token: 1 day
- Refresh token: 30-day sliding lifetime
- Refresh-token rotation
- Reactive refresh
- Refresh-token reuse rejected in V1
- Session-scoped refresh chains
- Multiple concurrent sessions
- Per-session logout/revocation
- No V1 absolute session lifetime

### DEFERRED

- Exact JWT claims/signing/key management
- Exact database implementation
- Exact socket framing
- Sequence allocation implementation
- Reconnect/missed-event recovery
- Rate limiting
- Observability/auditing
- Client/TUI UX
- Attachments
- Reactions
- Groups
- Advanced presence
- Moderation
- Rich text
