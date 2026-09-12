# Current user, profile, and authentication API

This document describes the V1 API contract for user/account/profile operations
and the authentication endpoints. It distinguishes the intended contract from
implementation status.

Authentication and authorization are governed by ADR 0003 and ADR 0007.
Relationship gating for messaging is governed by ADR 0008.

## Authentication

### `POST /api/auth/login`

Authenticates a provisioned user using username and password. On success the
server creates a persisted session and returns an access token, refresh token,
access-token expiry, and session identifier.

### `POST /api/auth/refresh`

Accepts a refresh token and returns a new access token and rotated refresh token
for the associated session.

Refresh tokens are stored as hashes, are session-scoped, and are rotated on
successful refresh.

### `POST /api/auth/logout`

Revokes the currently authenticated session. Other active sessions for the same
user remain active.

## User administration

User-management endpoints are protected by authentication and role/ownership
rules defined in ADR 0007.

### `POST /api/users`

**Admin only.** Creates a user account and its corresponding empty profile.

Request:

```json
{
  "username": "required",
  "password": "required",
  "email": "optional"
}
```

The server generates `userId`.

The password is hashed before persistence and is never stored or logged in
plaintext. The profile is created as part of the user-creation operation, so
there is no separate V1 `create user profile` endpoint.

### `GET /api/users`

**Admin only.** Lists users available to the administrator according to the V1
user-management contract.

The response must use an account-safe DTO and must not expose password hashes,
refresh-token state, session secrets, or other credential material.

### `GET /api/users/{userId}`

Protected user lookup. A standard user may retrieve only their own account
information. Administrative access is governed by the admin authorization
rules.

### `DELETE /api/users/{userId}`

**Admin only.** Deletes/removes an existing user account. User deletion is the
V1 mechanism for administrative account removal/revocation. Temporary account
pause/suspension is deferred.

### Account mutation restrictions

There is intentionally **no generic admin edit-user operation** in V1.

After creation:

- username is immutable
- administrator cannot change username
- administrator cannot change email
- administrator cannot change password
- administrator cannot change the user's profile

The absence of a generic edit endpoint is deliberate and preserves the boundary
between administrative provisioning and user-owned personal data.

### `PATCH /api/users/{userId}/email`

**Self-service only.** The authenticated user may change their own email.
Another user, including an administrator, may not change it through this V1
operation.

The exact email verification policy remains a separate security decision.

### `PATCH /api/users/{userId}/password`

**Self-service only.** The authenticated user may change their own password.
The server must verify the appropriate current credential/change authorization
and persist only the resulting password hash.

## User profile

### `GET /api/users/{userId}/profile`

Returns the target user's profile, subject to V1 profile read authorization:
the authenticated user may read their own profile, and an authenticated `ADMIN`
may read any user's profile. A standard user may not read another user's profile
in this slice; relationship-based (friend-gated) profile reads are deferred.
Unauthorized cross-user reads are rejected with `403`.

The profile contains fields such as:

```text
displayName
bio
avatarUrl
firstName
middleName
lastName
statusMessage
```

### `PATCH /api/users/{userId}/profile`

**Self-service only.** The authenticated user may update only their own profile.
An administrator may update their own profile but cannot use this endpoint to
edit another user's personal profile. Cross-user writes are rejected with `403`.

Accepted fields:

`displayName`, `bio`, `avatarUrl`, `firstName`, `middleName`, `lastName`, and
`statusMessage`.

The intended field semantics are:

| Request field state | Required result |
| --- | --- |
| Omitted | Leave existing value unchanged |
| Present with non-null value | Replace existing value |
| Present with `null` | Clear existing value |

### Known implementation gap

The current implementation does not yet reliably distinguish omitted fields from
explicit JSON `null`; see ADR 0006.

## User discovery

### `GET /api/users/search?username={username}`

**Authenticated users.** Performs exact username discovery for the messaging
relationship flow.

The response must use a restricted discovery DTO. It must not expose password
information, session information, or private account data.

Email is not used as the V1 messaging discovery key.

## Friend requests

Friend-request endpoints are the prerequisite for direct messaging and are
specified by ADR 0008. They will be introduced as a separate API contract and
implemented before direct-chat transport.

The intended lifecycle is:

```text
search user
   ↓
send friend request
   ↓
pending
   ↓
accept or reject
   ↓
accepted friendship
   ↓
direct messaging becomes authorized
```

Unfriend, block, mute, archive, and similar relationship controls are deferred
from the initial friend-request slice.

## Relationship to current implementation

The repository currently has the login/refresh foundation, admin bootstrap,
ADMIN-only password-aware user provisioning with BCrypt hashes, JWT/session
validation, and user/profile authorization. Remaining work to align with this
V1 contract includes logout/session revocation, user listing/deletion,
user-owned account mutations, and friendship.
