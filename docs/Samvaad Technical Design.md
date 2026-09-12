# Samvaad — Technical Design

> **Status:** High-level server architecture is recorded. V1 user/authentication and relationship boundaries are aligned with ADRs 0007 and 0008.
> **Focus:** Backend/domain/protocol/persistence/concurrency.
> **Rule:** Architectural invariants are fixed by ADRs; low-level mechanics are decided when the implementation creates a concrete need.

---

# 1. Architecture Direction

Samvaad is server-first.

The server owns authoritative state for:

- user identity
- authentication
- authorization
- sessions
- user relationships/friendships
- conversations
- messages
- message ordering
- canonical timestamps
- read state
- synchronization

The client/TUI consumes server contracts and does not become the source of truth for domain state.

---

# 2. Domain Model

## 2.1 User

Conceptual fields:

```text
userId
username
email
password credential / authentication data
account lifecycle data
role
```

Rules:

- userId is immutable
- username is immutable
- username is unique
- username is case-sensitive
- username matches `[a-zA-Z0-9_]{3,32}`
- role is exactly one of ADMIN or USER in V1

The authenticated user is derived from the server-side session.

V1 users are admin-provisioned; there is no public self-registration.

---

## 2.2 UserProfile

Separate domain concept:

```text
userId
firstName
middleName
lastName
displayName
bio
avatarUrl
statusMessage
```

A UserProfile is automatically created when its User is created. There is no separate V1 create-profile lifecycle endpoint.

Profile changes do not change user identity.

### Profile authorization

Profile reads and writes are intentionally different:

```text
READ
  own profile                         -> allowed
  ADMIN reading any profile           -> allowed
  USER reading non-friend              -> denied
  accepted friend reading profile     -> allowed

WRITE
  own profile                         -> allowed
  accepted friend editing friend      -> denied
  ADMIN editing another profile       -> denied
  ADMIN editing own profile            -> allowed
```

Friendship grants mutual profile-read visibility, but never profile-edit permission. The accepted-friend check becomes an authorization input when the friendship model is implemented.

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

Multiple sessions per user are allowed, with the existing V1 five-active-session limit.

For every authenticated HTTP request, the server validates the JWT and then validates the referenced session using `sid`. The session must exist, not be revoked, and not be past `refresh_token_expires_at`; JWT `sub` must match the session user. Missing, revoked, or expired sessions return `401`.

Per-request session lookup is the V1 correctness choice; no long-lived session-validation cache is required.

---

## 2.4 UserFriendship / FriendRequest

V1 introduces a relationship layer between users before direct messaging is authorized.

Conceptually:

```text
FriendRequest
requestId
senderUserId
recipientUserId
status
createdAt
respondedAt
```

Possible initial statuses:

```text
PENDING
ACCEPTED
REJECTED
CANCELLED
```

An accepted request establishes friendship between the pair and grants mutual profile-read visibility.

The model must support future relationship controls without coupling them to User identity. Blocking, unfriend, mute, and archive are not part of the initial friend-request implementation.

---

## 2.5 Conversation

A direct conversation contains two distinct users or one user for self-chat.

For the friendship-gated direct-message path, a conversation between two distinct users is authorized only when the pair has an accepted friendship.

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

## 2.6 UserConversationState

Per-user state can conceptually contain:

```text
userId
conversationId
archived
muted
lastReadSequenceNumber
```

Archive/mute remain future concerns for the current relationship-focused V1 work, but the model is kept separate so they can be introduced later.

---

## 2.7 Message

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

---

# 3. Database Invariants

The database should enforce invariants wherever correctness depends on concurrency.

## 3.1 Username uniqueness

```text
UNIQUE(username)
```

Do not silently apply case-folding because usernames are case-sensitive.

## 3.2 Email uniqueness

Non-null email addresses are unique case-insensitively. PostgreSQL enforces this with a unique index over `LOWER(email)` while allowing multiple `NULL` values.

## 3.3 Conversation uniqueness

```text
UNIQUE(normalizedParticipantA, normalizedParticipantB)
```

## 3.4 Friendship/request consistency

For a pair of users, concurrent relationship requests must not create contradictory duplicate active relationships. The exact schema/index strategy is implementation-time work, but the application must define deterministic behavior for repeated requests and opposite-direction races.

## 3.5 First-message atomicity

Conversation creation and first message creation occur in one transaction once messaging is authorized by accepted friendship.

Conceptually:

```text
BEGIN

find/create conversation
check friendship authorization
validate message
persist message

COMMIT
```

## 3.6 Request idempotency

A successful send request must be replay-safe.

The server needs a durable association between:

```text
requestId → accepted result/message
```

## 3.7 Read monotonicity

The server must never accept a read position that moves backwards.

## 3.8 Delete terminality

A deleted message cannot later be edited or deleted again. A tombstone remains durable.

---

# 4. Authorization Model

Authorization has two separate dimensions:

```text
Authentication → who is the caller?
Authorization  → what may that caller do?
```

The server derives the authenticated `userId` from the session context.

### ADMIN

V1 admin authority is deliberately narrow:

```text
create user
list users
retrieve user records as permitted
read any user profile
delete user
change own email
change own password
```

The administrator may not change another user's username, email, password, or personal profile.

### USER

A standard user may:

```text
retrieve own account/profile
change own email
change own password
update own profile
```

A standard user cannot modify another user's account or profile. Once an accepted friendship exists, a standard user may also read the friend's profile.

Profile-read visibility and profile-write ownership are separate authorization rules.

---

# 5. User Provisioning

V1 provisioning flow:

```text
Liquibase seeds initial ADMIN
        ↓
ADMIN authenticates
        ↓
ADMIN creates user
        ↓
username + password + optional email
        ↓
User + empty UserProfile
```

Initial administrator behavior:

```text
Liquibase database initialization → create fixed ADMIN + empty UserProfile
Application startup                 → no administrator mutation
```

The initial administrator password is stored only as a BCrypt hash in the Liquibase seed. First-login enforcement requiring the administrator to change the known default password remains deferred; ordinary self-service password change is implemented.

Password handling:

```text
plaintext password
      ↓
BCrypt
      ↓
persist password hash only
```

Plaintext passwords must never be persisted or logged.

New provisioned users receive `USER`; the provisioning API cannot set a role. Username is immutable after creation.

---

# 6. User Discovery & Friendship

## 6.1 User discovery

Authenticated users may search by exact username.

The discovery response must use a restricted DTO and must not expose credentials, session secrets, or unrelated private account data.

Email is not the V1 messaging discovery key.

## 6.2 Friend request

Initial lifecycle:

```text
NO_RELATIONSHIP
      ↓
PENDING
      ↓
ACCEPTED
      ↓
FRIENDSHIP
```

A request may instead become rejected or cancelled while pending.

The recipient may accept or reject. The sender may cancel while pending.

The friendship relationship is also the authorization basis for mutual profile reads and direct messaging between the two users.

The exact endpoint vocabulary and persistence schema are finalized with the first friendship vertical slice.

---

# 7. Direct Messaging Authorization

Direct messaging is gated by friendship.

Before conversation creation or message persistence for a distinct-user direct conversation:

```text
authenticate caller
      ↓
identify recipient
      ↓
verify accepted friendship
      ↓
authorize messaging
```

No accepted friendship means no new direct conversation/message for that pair.

This authorization rule is separate from the authentication/session layer so future relationship controls can be introduced without redesigning credentials.

---

# 8. Conversation Creation Race

Two users can race to create the same direct conversation. The database uniqueness constraint decides the winner; the losing operation handles the uniqueness conflict, retrieves the existing conversation, and continues with its own message where authorized.

No distributed lock is required merely to prevent this race.

---

# 9. Message Lifecycle

The previously defined message lifecycle remains in force: server-controlled sequence numbers and timestamps, request UUID idempotency, sender-owned edits/deletes, terminal tombstones, replies through `repliedToMessageId`, permanent history, and authoritative server validation.

---

# 10. Authentication Architecture

## 10.1 Login

```text
LOGIN(username-or-email, password)
       ↓
verify BCrypt
       ↓
create Session
       ↓
issue JWT access token
       ↓
issue refresh token
```

## 10.2 Refresh

```text
refresh token
      ↓
validate session-bound hash
      ↓
rotate refresh token
      ↓
issue new access token
```

Current policy:

```text
access token  = 1 day
refresh token = 30-day sliding expiry
```

## 10.3 Logout

```text
current session → revoked
```

Other sessions remain active.

---

# 11. Protocol Direction

The realtime/message protocol will be finalized after the user/auth and friendship slices exist.

High-level flow:

```text
authenticate
    ↓
discover user
    ↓
friend request
    ↓
accept
    ↓
friendship
    ↓
profile visibility + conversation/message protocol
```

Do not freeze every future command/event before the relevant vertical slice is implemented.

---

# 12. Implementation-Time Decisions

The following remain implementation-time choices unless a later ADR changes that:

- exact database schema/index layout
- migration mechanics
- exact JWT claims/signing/key-management details
- socket/WebSocket library and framing
- exact friend-request endpoint payloads
- sequence allocation implementation
- exact pagination cursor
- retry/reconnect/missed-event recovery
- rate limiting
- observability strategy
- deployment topology

---

# 13. Technical Invariants

1. Server is authoritative.
2. Authenticated identity comes from session context.
3. Username is immutable, unique, and case-sensitive.
4. User creation is admin-only in V1.
5. A user creation operation creates its empty UserProfile.
6. Passwords are never persisted or logged in plaintext.
7. An administrator cannot mutate another user's username, email, password, or profile in V1.
8. A standard user can mutate only their own permitted account/profile fields.
9. An accepted friendship grants mutual profile-read visibility but never profile-edit permission.
10. Exact username discovery is the V1 messaging discovery mechanism.
11. Direct messaging between distinct users requires accepted friendship.
12. A direct participant pair has at most one conversation.
13. Conversation + first message creation is atomic.
14. A request UUID cannot create two messages.
15. Server sequence numbers determine message order.
16. Client time is never authoritative.
17. Read position never moves backwards.
18. Delete is terminal.
19. Message history is permanent.
20. Client validation never replaces server validation.
21. Session revocation invalidates its active authenticated connection.
22. Refresh-token rotation is session-scoped and old refresh tokens are rejected after successful rotation.
