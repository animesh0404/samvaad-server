# Samvaad — Implementation Roadmap

> **Status:** High-level design is recorded; the V1 user/authentication and friend-request boundaries are locked.
> **Next phase:** Complete the user/auth boundary, then implement the friend-request vertical slice, then begin direct messaging.
> **Working philosophy:** Keep the server authoritative, keep V1 small, and decide low-level implementation details when the relevant slice creates a concrete need.

---

# 1. Current Position

The original high-level design phase is **COMPLETE**.

The repository now contains the Phase 1 HTTP authentication/session and user/account boundary: login, refresh, logout/revocation, admin provisioning, profile authorization, admin listing/deletion, self-service email/password changes, and the accepted profile PATCH field-presence semantics. The next product slice is authenticated user discovery followed by friend requests.

---

# 2. Working Rules

1. Do not reopen a locked ADR without concrete implementation evidence.
2. Keep account identity separate from personal profile data.
3. Derive authorization identity from the authenticated server-side session.
4. Prefer one clear endpoint per semantic operation; do not create separate aliases for the same update operation.
5. Keep relationship authorization separate from authentication.
6. Defer client/TUI-specific UX unless it creates a server contract.

---

# 3. Phase 0 — Design Reconciliation

**STATUS: COMPLETE**

Locked V1 decisions include:

- admin-only user provisioning; no public self-registration
- initial administrator seeded by Liquibase; no application-startup admin bootstrap
- username required at creation and immutable afterwards
- password required at creation and BCrypt-hashed
- email optional at creation and user-owned after creation
- `User` and `UserProfile` remain separate
- `UserProfile` is automatically created with `User`
- no separate V1 create-profile lifecycle endpoint
- ADMIN and USER roles
- administrator may create, list, retrieve as permitted, and delete users
- administrator may not change another user's username, email, password, or profile
- users may change their own email, password, and profile
- login, refresh, and logout are the authentication lifecycle endpoints
- exact username discovery for relationship setup
- accepted friendship is required before direct messaging
- blocking, unfriend, mute, and archive are deferred from the initial relationship slice

TDD is a development practice for implementation work and is intentionally not recorded as a project ADR or product decision.

---

# 4. Phase 1 — Authentication & Authorization Boundary

**STATUS: COMPLETE**

Implemented and tested:

- User and UserProfile persistence
- admin-only user provisioning
- required password at creation with BCrypt hashing
- automatic empty profile creation with the user
- fixed initial ADMIN seeded through Liquibase
- no runtime admin bootstrap configuration or startup mutation
- `POST /api/auth/login`
- username-or-email login
- JWT access tokens
- persisted sessions
- session-bound JWT access tokens
- `POST /api/auth/refresh`
- hashed refresh-token persistence
- session-scoped refresh rotation
- 30-day sliding refresh expiry
- one-day access-token lifetime
- five-active-session capacity enforcement with transactional serialization
- `POST /api/auth/logout`
- current-session revocation while other sessions remain active
- JWT/session validation on authenticated requests
- ADMIN/USER authorization
- self-only profile writes and ADMIN cross-user profile reads
- `GET /api/users` admin listing
- `DELETE /api/users/{userId}` admin deletion
- self-service `PATCH /api/users/{userId}/email`
- self-service `PATCH /api/users/{userId}/password`
- immutable username
- no generic admin edit-user endpoint
- profile PATCH field-presence semantics: omitted fields remain unchanged, non-null values replace existing values, and explicit `null` clears the field

### Acceptance

The secure account lifecycle is:

```text
Liquibase seed ADMIN
        ↓
ADMIN login
        ↓
ADMIN creates USER(username, password, optional email)
        ↓
User + empty UserProfile
        ↓
USER login
        ↓
user manages own email/password/profile
        ↓
admin can list/delete users
```

---

# 5. Phase 2 — User Discovery

**STATUS: PENDING**

Goal: authenticated users can find another user by exact username before sending a friend request.

Implement:

- exact username lookup
- restricted discovery DTO
- authorization requiring an authenticated session
- no password/session/private credential fields in discovery responses

### Acceptance

A valid authenticated user can resolve a known username, while an unknown username produces a stable not-found result without creating any relationship or conversation state.

---

# 6. Phase 3 — Friend Request Vertical Slice

**STATUS: PENDING**

This is the first relationship feature and the gate for direct messaging.

Goal:

> One authenticated user sends a friend request to another discovered user, the recipient accepts it, and the two users become friends.

Minimum lifecycle:

```text
no relationship
      ↓
request sent
      ↓
pending
      ↓
accepted
      ↓
friendship
```

Implement:

- friend request persistence
- sender/recipient ownership
- pending state
- send request
- list/view incoming requests as needed by the contract
- accept request
- reject request
- cancel pending request
- uniqueness/duplicate-request rules
- server-side authorization
- future-compatible relationship representation

Do not implement blocking, unfriend, mute, or archive in this slice.

### Acceptance

Two authenticated users can discover one another, establish friendship through an accepted request, and the server can authorize subsequent direct messaging based on the friendship state.

---

# 7. Phase 4 — Direct Conversation & Minimal Send Message

**STATUS: PENDING**

Only start this phase after the friend-request slice works.

Goal:

> An authenticated user sends a plain-text message to an accepted friend.

Implement:

- accepted-friendship authorization gate
- direct conversation lookup/creation
- database uniqueness for a user pair
- atomic conversation + first message creation
- message validation
- server timestamp
- server sequence
- request UUID idempotency
- message acceptance event
- delivery event skeleton

### Acceptance

A user who is not an accepted friend cannot create a conversation or persist a message to another user.

---

# 8. Phase 5 — Receiving & Delivery

**STATUS: PENDING**

Implement:

- realtime message delivery
- multi-session delivery
- delivery state separate from message content
- authenticated connection/session binding

---

# 9. Phase 6 — History & Pagination

**STATUS: PENDING**

Implement:

- permanent message history
- latest-page-first retrieval
- older-page pagination
- conversation list
- latest-message preview

---

# 10. Phase 7 — Read State

**STATUS: PENDING**

Implement:

- account-level `lastReadSequenceNumber`
- `MARK_READ`
- monotonic read progression
- cross-session read-state convergence

---

# 11. Phase 8 — Message Mutation

**STATUS: PENDING**

Implement:

- edit message
- delete message
- message-update events
- terminal tombstones

---

# 12. Phase 9 — Replies

**STATUS: PENDING**

Implement:

- `repliedToMessageId`
- same-conversation validation
- durable reply relationship when parent is deleted

---

# 13. Phase 10 — Deferred Relationship Controls

**STATUS: FUTURE**

Potential later additions include:

- unfriend
- blocking
- mute
- archive
- account pause/suspension

These must be added without changing the permanent `userId` identity model or breaking the accepted friendship abstraction.

---

# 14. Phase 11 — Offline & Reconnect

**STATUS: PENDING**

Implement after the core realtime/message model exists:

- startup synchronization
- reconnect
- missed-event recovery
- state convergence
- retry-safe mutation behavior

---

# 15. Phase 12 — Hardening

**STATUS: PENDING**

Cover:

- authentication and authorization failures
- user ownership checks
- admin-only operations
- friendship authorization
- duplicate friend-request races
- conversation creation races
- message idempotency races
- session revocation
- refresh-token rotation/reuse
- persistence constraints and rollback
- username boundary validation
- message boundary validation

---

# 16. V1 Explicit Exclusions

The existing V1 exclusions remain unless a later ADR supersedes them:

- E2EE
- attachments/media
- groups
- rich text/Markdown
- reactions
- advanced moderation
- advanced presence
- voice/video
- client/TUI-specific UX infrastructure

The initial relationship slice also excludes blocking, unfriend, mute, and archive.

---

# 17. Immediate Next Step

Phase 1 account/auth implementation is complete. Move to Phase 2: exact username discovery. Do not begin direct messaging until this chain is implemented and tested:

```text
Liquibase-seeded ADMIN
      ↓
ADMIN login
      ↓
ADMIN creates user(username + password + optional email)
      ↓
User + empty UserProfile
      ↓
user login
      ↓
user updates own profile/email/password
      ↓
admin can list/delete users ✓
      ↓
exact username discovery
      ↓
friend request
      ↓
accept
      ↓
friendship
      ↓
chat
```
