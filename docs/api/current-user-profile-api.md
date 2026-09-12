# Current user, profile, and authentication API

This document describes the V1 API contract for user/account/profile operations
and the authentication endpoints. It distinguishes the intended contract from
implementation status.

Authentication and authorization are governed by ADR 0003 and ADR 0007.
Relationship gating for messaging is governed by ADR 0008.

## Authentication

### `POST /api/auth/login`

Authenticates a provisioned user using username or email and password. On success the
server creates a persisted session and returns an access token, refresh token,
access-token expiry, and session identifier.

### `POST /api/auth/refresh`

Accepts a refresh token and returns a new access token and rotated refresh token
for the associated session.

Refresh tokens are stored as hashes, are session-scoped, and are rotated on
successful refresh.

### `POST /api/auth/logout`

Revokes the currently authenticated session. Other active sessions for the same
user remain active. A revoked session subsequently fails authenticated requests
and refresh attempts.

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
there is no separate V1 `create user profile` endpoint. Provisioned users are
always created with role `USER`.

### `GET /api/users`

**Admin only.** Lists users available to the administrator according to the V1
user-management contract.

The response uses an account-safe DTO and does not expose password hashes,
refresh-token state, session secrets, or other credential material.

### `GET /api/users/{userId}`

Protected user lookup. A standard user may retrieve only their own account
information. Administrative access is governed by the admin authorization
rules.

### `DELETE /api/users/{userId}`

**Admin only.** Hard-deletes an existing user account. An administrator may
delete another administrator, but may not delete themselves. Deletion removes
the user's sessions first, then profile, then account, so previously issued
access tokens are no longer backed by a valid persisted session.

Temporary account pause/suspension is deferred.

### Account mutation restrictions

There is intentionally **no generic admin edit-user operation** in V1.

After creation:

- username is immutable
- administrator cannot change another user's username
- administrator cannot change another user's email
- administrator cannot change another user's password
- administrator cannot change another user's profile
- a user may change their own email and password through the dedicated self-service operations

### `PATCH /api/users/{userId}/email`

**Self-service only.** The authenticated user may change their own email.
Another user, including an administrator, may not change it through this V1
operation. An administrator may change their own email.

Request:

```json
{
  "email": "new@example.com"
}
```

The email must be non-blank, valid according to the standard email validator,
and at most 320 characters. Email uniqueness is case-insensitive and is enforced
by the database for non-null values. A duplicate email returns `409 Conflict`.
The same email, including a case-only variation, is a successful no-op and returns
`200` with the current `UserDto`. The submitted representation is preserved when
an actual change is made.

Email changes do not revoke existing sessions or JWTs. The changed email can be
used as the login identifier immediately.

Email verification/OTP is not part of this V1 operation; its lifecycle remains
a separate security decision.

### `PATCH /api/users/{userId}/password`

**Self-service only.** The authenticated user may change their own password.
Another user, including an administrator, may not change it through this V1
operation. An administrator may change their own password.

Request:

```json
{
  "currentPassword": "required",
  "newPassword": "required"
}
```

Both fields are required and non-blank. The current password is verified using
the persisted BCrypt hash. A wrong current password returns `401 Unauthorized`.
The new password is BCrypt-hashed before persistence and plaintext is never
persisted or returned. No additional password-strength policy is enforced by
this endpoint. The same current/new password is allowed.

Password changes do not revoke existing sessions or JWTs. Existing sessions and
refresh tokens remain valid; subsequent new logins must use the new password.

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

The repository currently implements the Phase 1 account/authentication boundary:
login, refresh, logout and session revocation, JWT/session validation,
admin-only provisioning, profile authorization, admin user listing and deletion,
and self-service email and password changes. The initial ADMIN is seeded by
Liquibase rather than application startup. Remaining work in this area is limited
to explicitly deferred/known gaps such as first-login default-password enforcement,
friend-gated profile visibility, and the profile PATCH field-presence gap.
User discovery, friendship, direct messaging, and realtime transport remain
pending.
