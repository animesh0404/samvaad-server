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

The first administrator is bootstrapped during first-time application setup. A bootstrap credential may exist for initial deployment, but it must not be committed to source control or stored as plaintext after provisioning.

An administrator creates a user with:

```text
username  required
password  required
email     optional
```

The server generates the permanent `userId` and creates the empty `UserProfile` as part of the same user-creation lifecycle.

---

## 3.2 Password storage

**LOCKED**

Passwords use BCrypt with generated salt.

Plaintext passwords:

- are accepted only at the authentication boundary
- are never persisted
- are never stored in domain entities
- are never placed in events
- must not be logged

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

Samvaad distinguishes at least:

```text
ADMIN
STANDARD_USER
```

The initial bootstrap account is an administrator.

---

## 4.2 Administrator capabilities

**LOCKED**

An authenticated administrator may:

- create users
- list users
- retrieve user records where the API permits it
- delete users

The administrator may **not** edit an existing user's username, email, password, or `UserProfile`.

Deleting a user is the V1 administrative mechanism for account removal/revocation.

---

## 4.3 Standard-user capabilities

**LOCKED**

A standard user may operate on their own account/profile only where self-service is permitted:

- retrieve their own user/account data
- retrieve their own profile
- update their own profile
- change their own email
- change their own password

A standard user cannot modify another user's account or profile.

Authorization derives identity from the authenticated server-side session context, not from a caller-supplied identity claim.

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

---

# 6. User/Account API Direction

**LOCKED**

The V1 account lifecycle is:

```text
admin creates user
      ↓
User + empty UserProfile
      ↓
user logs in
      ↓
user manages own email/password/profile
```

There is no generic "edit user" permission for administrators.

A separate profile-creation endpoint is unnecessary because profile creation is coupled to user creation. Profile mutation is an update/patch operation.

---

# 7. User Discovery & Friend Requests

## 7.1 Exact username discovery

**LOCKED**

Authenticated users can discover other users by exact username.

Email is not the V1 messaging discovery key.

Discovery responses expose only an appropriate public/discovery DTO and must not expose credentials, sessions, or private account data.

---

## 7.2 Friend request prerequisite

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

A recipient may accept or reject a request. A sender may cancel a pending request.

---

## 7.3 Relationship features explicitly deferred

**DEFERRED**

The initial friend-request slice does not implement:

- blocking
- unfriend
- mute
- archive
- other advanced relationship controls

The relationship model must remain extensible enough to add them later without changing user identity semantics.

---

# 8. Direct Messaging Gate

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
chat
```

---

# 9. Existing Messaging Decisions

The existing direct-conversation, message, history, ordering, read-state, and persistence decisions remain in force unless explicitly superseded by an ADR.

Features such as reactions, attachments, groups, rich text, and E2EE remain outside the V1 boundary as recorded in the relevant ADRs.

For the latest authoritative relationship and authorization decisions, see:

- [ADR 0007: Admin-provisioned users and V1 authorization boundary](adr/0007-user-provisioning-and-authorization.md)
- [ADR 0008: Friend-request-gated direct messaging](adr/0008-friend-request-gated-direct-messaging.md)
