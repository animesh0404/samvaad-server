# Samvaad — Implementation Roadmap

> **Status:** High-level design complete.  
> **Next phase:** Implement the first backend vertical slice.  
> **Working philosophy:** Do not turn every implementation detail into a design meeting.

---

# 1. Current Position

The high-level backend design phase is **COMPLETE**.

We have enough locked invariants to begin writing code.

We intentionally stopped the exhaustive-specification phase because continuing to decide every low-level detail before implementation would create a false sense of certainty.

Implementation should now reveal the remaining technical questions.

---

# 2. Working Rules

## 2.1 When deciding what comes next

When continuing the design journey:

1. Start with a concrete example.
2. Ask only a real unresolved question.
3. Do not reopen locked decisions.
4. If the answer is an obvious implementation choice, make the choice and document it.
5. If a decision affects architecture or a durable invariant, stop and discuss it.
6. Keep the backend as the priority.
7. Defer client/TUI UX unless it creates a server contract.

---

# 3. Phase 0 — High-Level Design

**STATUS: COMPLETE**

Locked areas include:

- user identity
- username rules
- user/profile separation
- password hashing
- sessions
- JWT access tokens
- refresh-token rotation
- direct conversations
- self-chat
- conversation uniqueness
- atomic first message
- message lifecycle
- idempotency
- server ordering
- permanent history
- pagination direction
- read state
- blocking
- archive/mute
- V1 exclusions

---

# 4. Phase 1 — Protocol Skeleton

**STATUS: NEXT**

Goal: establish the smallest coherent server/client protocol before building the complete domain.

Start with:

```text
CONNECT
  ↓
LOGIN
  ↓
LOGIN_SUCCESS
  ↓
authenticated session
```

Then establish the minimal command/event envelope.

We do not need to freeze every future event.

### Acceptance

A minimal test client can:

1. connect
2. authenticate
3. receive authentication success
4. obtain the session/token state required for authenticated operation

---

# 5. Phase 2 — User & Authentication

Goal: build the actual authentication boundary.

Implement:

- User persistence
- UserProfile persistence
- registration
- bcrypt password hashing
- login
- JWT access token
- refresh token
- session persistence
- logout
- session revocation

Current token policy:

```text
Access token  → 1 day
Refresh token → 30-day sliding expiry
```

Refresh behavior:

```text
access token expires
      ↓
reactive refresh
      ↓
rotate refresh token
      ↓
new access token
```

Refresh-token reuse:

```text
old token reused
      ↓
reject
```

No absolute session lifetime in V1.

### Acceptance

A client can:

```text
register
  ↓
login
  ↓
receive tokens
  ↓
authenticate
  ↓
logout
  ↓
session becomes invalid
```

---

# 6. Phase 3 — Minimal Send Message

This is the first genuinely useful vertical slice.

Goal:

> authenticated user sends one plain-text message to another user.

Implement:

- exact username discovery
- block authorization check
- direct conversation lookup/creation
- database uniqueness
- atomic conversation + first message
- message validation
- server timestamp
- server sequence
- request UUID idempotency
- MESSAGE_ACCEPTED
- NEW_MESSAGE

### Example

```text
Alice
  ↓
SEND_MESSAGE(requestId=X, recipient=Bob, "Hello")
  ↓
authenticate
  ↓
check Bob exists
  ↓
check block
  ↓
find/create A↔B conversation
  ↓
validate content
  ↓
persist message
  ↓
commit
  ↓
MESSAGE_ACCEPTED
```

### Acceptance

Retrying the same `requestId` never creates a second message.

---

# 7. Phase 4 — Receiving & Delivery

Goal: make the second user receive messages reliably.

Implement:

- NEW_MESSAGE
- delivery semantics
- multi-session delivery
- delivery state separate from message content

Example:

```text
Alice
  ↓
Server
  ↓
Bob laptop
Bob phone
```

Both sessions may receive the same logical message.

The message itself remains one database record.

### Acceptance

Multiple active sessions do not create duplicate message records.

---

# 8. Phase 5 — History & Pagination

Goal: durable message retrieval.

Implement:

- permanent message history
- latest-page-first retrieval
- older-page pagination
- conversation list
- latest message preview

High-level flow:

```text
authenticate
   ↓
conversation list
   ↓
open conversation
   ↓
latest page
   ↓
older pages
```

Do not load the entire history by default.

### Acceptance

A conversation with a large history can be opened without loading all messages.

---

# 9. Phase 6 — Read State

Goal: account-level monotonic read state.

Implement:

```text
lastReadSequenceNumber
```

and:

```text
MARK_READ
```

Rules:

- only moves forward
- shared across sessions
- only represents messages actually displayed

### Acceptance

If laptop marks through sequence 100, phone cannot move the account back to 90.

---

# 10. Phase 7 — Message Mutation

Goal: editing and deletion.

Implement:

```text
EDIT_MESSAGE
MESSAGE_UPDATED

DELETE_MESSAGE
MESSAGE_DELETED
```

Rules:

- sender owns edit/delete
- last-write-wins for edits
- deletion is terminal
- deleted message remains as tombstone

### Acceptance

Concurrent edit/delete tests resolve deterministically.

---

# 11. Phase 8 — Replies

Goal: first-class reply relationships.

Implement:

```text
repliedToMessageId
```

Validate:

```text
parent conversation == child conversation
```

A deleted parent remains represented by its tombstone.

### Acceptance

A reply remains durable even when its parent is deleted.

---

# 12. Phase 9 — Archive, Mute & Blocking

Implement per-user conversation state.

### Archive

```text
archived
```

### Auto-unarchive

```text
default = false
configurable
```

### Mute

```text
muted
```

### Blocking

```text
blockerUserId
blockedUserId
```

Blocked conversation:

```text
read-only for both sides
```

Send error:

```text
CONVERSATION_BLOCKED
```

### Acceptance

A blocked sender cannot queue or persist a message.

---

# 13. Phase 10 — Offline & Reconnect

This phase deliberately waits until the preceding slices exist.

Implement:

- startup synchronization
- reconnect
- state convergence
- retry-safe mutation behavior
- missed-event recovery

Do not invent the exact recovery protocol until the actual socket/message model exists.

The existing idempotency invariant gives us a foundation for safe retries.

---

# 14. Phase 11 — Hardening

Once the core path works, add:

### Authorization tests

- sender ownership
- blocked send
- unknown recipient
- session validity

### Concurrency tests

- conversation creation race
- duplicate request race
- edit race
- delete race
- multi-session state

### Boundary tests

- empty message
- whitespace-only message
- exactly 64 KB
- 64 KB + 1 byte
- username boundaries
- invalid usernames

### Authentication tests

- expired access token
- refresh rotation
- refresh-token reuse
- revoked session
- logout
- multiple sessions

### Persistence tests

- transaction rollback
- uniqueness constraints
- tombstones
- read monotonicity

---

# 15. What We Deliberately Do Not Build Yet

V1 excludes:

- reactions
- attachments/media
- groups
- admin/moderation
- rich text/Markdown
- push notification infrastructure
- advanced presence
- typing indicators
- end-to-end encryption
- voice/video
- message revision history
- client/TUI-specific UX features

These can be designed after the core messaging server works.

---

# 16. Implementation-Time Decisions

The following are intentionally not blockers:

- exact JWT signing algorithm
- JWT key management
- exact JWT claims
- exact database technology/index layout
- migration tooling
- socket/WebSocket implementation
- framing
- sequence allocation implementation
- exact refresh endpoint schema
- retry semantics
- reconnect protocol
- missed-event recovery
- rate limiting
- metrics/tracing/logging
- audit strategy
- exact pagination cursor
- deployment topology

The rule is:

> If a detail does not change a locked domain invariant, prefer deciding it while implementing the relevant slice.

---

# 17. Definition of Done for Each Slice

A slice is done when:

- the relevant product behavior is locked
- the technical contract is clear enough to implement
- persistence changes exist
- migrations exist where needed
- server-side validation exists
- authorization exists
- happy path works
- failure paths are tested
- concurrency implications are considered
- multiple sessions are considered where relevant
- the slice is demonstrable end-to-end

Do not require the entire future system to be designed before declaring a slice complete.

---

# 18. Immediate Next Step

The next concrete engineering task is:

## Build the protocol skeleton for authentication.

Start with an example:

```text
Client
  │
  │ CONNECT
  ▼
Server
  │
  │ LOGIN(username, password)
  ▼
Authentication
  │
  │ validate credentials
  │ create session
  │ issue JWT + refresh token
  ▼
LOGIN_SUCCESS
```

Then implement that minimal path in Java.

After it works, we can decide the next implementation detail from the evidence rather than from speculation.
