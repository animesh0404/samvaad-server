# Samvaad — Product & Design Decisions

> **Status:** High-level backend design is recorded; V1 user/authentication and pre-chat relationship decisions are locked. Implementation must follow the accepted ADRs.
> **Role:** Product behavior, design rationale, locked decisions, and deferred decisions.
> **Canonical rule:** Do not reopen a decision marked **LOCKED** unless new implementation evidence forces a change.

---

## 1. Project Direction

Samvaad is a server-first messaging system.

The backend owns authoritative identity, authentication, authorization, relationship state, conversations, messages, and synchronization state. The client consumes stable server contracts.

Prefer simple invariants, explicit contracts, database-enforced correctness where appropriate, minimal V1 complexity, and implementation-driven choices for low-level mechanics.

---

# 2. User Identity

## 2.1 Internal identity

**LOCKED**

`userId` is the permanent internal identity of a user.

The authenticated user identity is always derived by the server from authenticated session context.

---

## 2.2 Username

**LOCKED**

Username is:

- immutable after creation
- unique
- case-sensitive
- used for exact user discovery
- not the internal identity

V1 validation:

```text
[a-zA-Z0-9_]{3,32}
```

Neither the administrator nor the user may change a username after account creation.

---

## 2.3 User vs UserProfile

**LOCKED**

Authentication/account identity and personal profile data are separate backend concepts.

### User

Conceptually owns:

```text
userId
username
email
authentication/account lifecycle data
role
```

### UserProfile

Conceptually owns profile fields such as:

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

A user's profile is automatically created when the user account is created. A separate V1 profile-creation operation is not required.

---

# 3. Registration, Provisioning & Passwords

## 3.1 V1 user provisioning

**LOCKED**

There is **no public self-registration** in V1.

User accounts are provisioned by an administrator.

The initial administrator is bootstrapped during application startup from environment-provided bootstrap credentials. Bootstrap is idempotent: when an administrator already exists, startup performs no mutation. When credentials are absent, startup warns and continues without creating an administrator. Bootstrap credentials must not be committed to source control or persisted in plaintext.

An administrator creates a user with:

```text
username  required
password  required
email     optional
```

The server generates the permanent `userId` and creates the empty `UserProfile` as part of the same user-creation lifecycle.

Newly provisioned users are `USER` unless they are the explicitly bootstrapped administrator; the provisioning API cannot choose or change the role.

---

## 3.2 Password storage

**LOCKED**

Passwords use BCrypt with generated salt.

Plaintext passwords:

- are accepted only at the authentication/provisioning boundary
- are never persisted
- are never stored in domain entities
- are never placed in events
- must not be logged
- are never returned by the API

Once the provisioning migration is complete, `password_hash` is expected to be non-null. Legacy passwordless rows must be handled explicitly by migration rather than silently preserved as a supported account state.

---

## 3.3 Username and credential ownership after creation

**LOCKED**

After creation:

- username cannot be changed by anyone
- administrator cannot change email
- administrator cannot change password
- administrator cannot edit the user's profile
- user may change their own email
- user may change their own password

Temporary account pausing/suspension is deferred from V1.

---

# 4. Roles & Authorization

## 4.1 Roles

**LOCKED for V1**

Samvaad distinguishes exactly one role per user:

```text
ADMIN
USER
```

The initial bootstrap account is an administrator. There is no V1 role-change API, and clients cannot assign roles during provisioning.

---

## 4.2 Administrator capabilities

**LOCKED**

An authenticated administrator may:

- create users
- list users
- retrieve user records where the API permits it
- delete users
- read any user's profile

The administrator may **not** edit an existing user's username, email, password, or `UserProfile`.

An administrator may edit their **own** profile through the normal self-service profile operation.

Deleting a user is the V1 administrative mechanism for account removal/revocation.

---

## 4.3 Standard-user capabilities

**LOCKED**

A standard user may:

- retrieve their own account/profile
- update their own profile
- change their own email
- change their own password

A standard user may not read or modify another user's account or profile unless an accepted friendship relationship grants profile-read visibility.

---

## 4.4 Profile visibility

**LOCKED**

Profile read and write permissions are deliberately different:

```text
READ
  own profile                         -> allowed
  ADMIN reading any profile           -> allowed
  USER reading non-friend   -> denied
  accepted friend reading profile    -> allowed

WRITE
  own profile                         -> allowed
  friend editing friend's profile    -> denied
  ADMIN editing another profile      -> denied
  ADMIN editing own profile           -> allowed
```

Friendship grants **read** access to each friend's profile; it never grants edit access.

The current authentication/authorization slice establishes identity and role enforcement. The accepted-friend relationship check becomes the additional authorization input when the friendship vertical slice is implemented.

---

# 5. Authentication & Sessions

The detailed authentication/session decision is recorded in [ADR 0003](adr/0003-authentication-and-session-model.md).

**LOCKED**

V1 authentication uses:

```text
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
```

Access tokens are JWTs with a one-day lifetime. Refresh tokens are session-scoped, rotating, hashed at rest, and use a 30-day sliding expiry.

Normal logout revokes only the current session.

Every authenticated request must validate both the access JWT and its referenced server-side session. The JWT `sid` identifies the session; the JWT `sub` must match the user bound to that session. Missing, revoked, or expired sessions cause `401 Unauthorized`.

The session is considered dead when its `refresh_token_expires_at` has passed. Per-request session lookup is preferred for V1 correctness and immediate revocation rather than adding a validation cache.

---

# 6. User/Account API Direction

**LOCKED**

The V1 account lifecycle is:

```text
bootstrap ADMIN
      ↓
admin creates user
      ↓
User + empty UserProfile
      ↓
user logs in
      ↓
user manages own permitted account/profile fields
```

There is no generic "edit user" permission for administrators.

A separate profile-creation endpoint is unnecessary because profile creation is coupled to user creation. Profile mutation is an update/patch operation.

HTTP authorization uses the server-derived identity rather than a caller-supplied `userId`.

For protected endpoints:

```text
unauthenticated                 -> 401
authenticated but not permitted -> 403
invalid/expired/revoked session  -> 401
validation failure               -> 400
duplicate username               -> 409
```

The existing API error body shape is preserved for this slice. Cross-user denial uses `403`, not `404`.

---

# 7. Login Identifier

**LOCKED**

The existing login behavior remains username-or-email based.

Username remains the exact messaging discovery key. Email is not the V1 messaging discovery key.

---

# 8. User Discovery & Friend Requests

## 8.1 Exact username discovery

**LOCKED**

Authenticated users can discover other users by exact username.

Email is not the V1 messaging discovery key.

Discovery responses expose only an appropriate public/discovery DTO and must not expose credentials, sessions, or private account data.

---

## 8.2 Friend request prerequisite

**LOCKED**

A user cannot directly message another user merely because both accounts are authenticated.

V1 requires:

```text
user discovery
    ↓
friend request
    ↓
pending
    ↓
recipient accepts
    ↓
friendship established
    ↓
direct messaging authorized
```

An accepted friendship also grants mutual **profile-read visibility**. It does not grant either friend permission to edit the other's profile.

A recipient may accept or reject a request. A sender may cancel a pending request.

---

## 8.3 Relationship features explicitly deferred

**DEFERRED**

The initial friend-request slice does not implement:

- blocking
- unfriend
- mute
- archive
- other advanced relationship controls

The relationship model must remain extensible enough to add them later without changing user identity semantics.

---

# 9. Direct Messaging Gate

**LOCKED**

Direct conversation creation and direct message persistence require an accepted friendship between the two users.

The server performs relationship authorization before creating a conversation or storing a message.

This makes the progression:

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
profile visibility + chat
```

---

# 10. Existing Messaging Decisions

The existing direct-conversation, message, history, ordering, read-state, and persistence decisions remain in force unless explicitly superseded by an ADR.

Features such as reactions, attachments, groups, rich text, and E2EE remain outside the V1 boundary as recorded in the relevant ADRs.

For the latest authoritative relationship and authorization decisions, see:

- [ADR 0007: Admin-provisioned users and V1 authorization boundary](adr/0007-user-provisioning-and-authorization.md)
- [ADR 0008: Friend-request-gated direct messaging](adr/0008-friend-request-gated-direct-messaging.md)
