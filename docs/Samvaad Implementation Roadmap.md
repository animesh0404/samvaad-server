# Samvaad — Implementation Roadmap

> **Status:** High-level design is recorded; V1 user/authentication and friend-request boundaries are now locked.  
> **Next phase:** Complete the user/auth authorization boundary, then implement the friend-request vertical slice, then begin direct messaging.  
> **Working philosophy:** Keep the server authoritative, keep V1 small, and decide low-level implementation details when the relevant slice creates a concrete need.

---

# 1. Current Position

The original high-level design phase is **COMPLETE**.

The repository already contains a substantial HTTP authentication/session foundation and user/profile implementation. The next work is not to invent another broad design phase; it is to reconcile the implementation with the newly locked V1 user/auth rules, establish friend requests, and only then build direct messaging.

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

Locked V1 decisions now include:

- admin-only user provisioning; no public self-registration
- initial administrator bootstrapped during first-time application setup
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

TDD is a development practice for the implementation work and is intentionally not recorded as a project ADR or product decision.

---

# 4. Phase 1 — Authentication & Authorization Boundary

**STATUS: PARTIALLY COMPLETE**

Existing foundation includes:

- User persistence
- UserProfile persistence
- BCrypt password verification
- `POST /api/auth/login`
- `GET /api/users` for admin user listing
- JWT access tokens
- persisted sessions
- session-bound JWT access tokens
- `POST /api/auth/refresh`
- hashed refresh-token persistence
- session-scoped refresh rotation
- 30-day sliding refresh expiry
- one-day access-token lifetime
- five-active-session capacity enforcement

Remaining alignment work:

- bootstrap/provision the initial ADMIN account safely
- change `POST /api/users` to require password and remain admin-only
- ensure user creation atomically creates the empty profile
- remove/deprecate any separate create-profile lifecycle endpoint
- implement authorization enforcement for all protected user/profile operations
- **[DONE]** implement `DELETE /api/users/{userId}` for admin deletion
- do not add a generic admin edit-user endpoint
- **[DONE]** implement self-service email change
- implement self-service password change
- enforce immutable username
- enforce self-only profile updates
- implement `POST /api/auth/logout`
- complete session revocation HTTP operations required by the accepted auth model

### Acceptance

The minimum secure user lifecycle is:

```text
application first setup
        ↓
bootstrap ADMIN
        ↓
ADMIN login
        ↓
authenticated ADMIN
        ↓
create USER(username, password, optional email)
        ↓
User + empty UserProfile
        ↓
USER login
        ↓
user manages own email/password/profile
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

Complete Phase 1 first. Do not begin direct messaging until the following chain is implemented and tested:

```text
bootstrap ADMIN
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
