# ADR 0007: Admin-provisioned users and V1 authorization boundary

## Status

Accepted.

## Context

Samvaad V1 is not a public self-registration system. User accounts are
provisioned by an administrator. The account identity and personal profile are
separate concepts, and administrative authority must be deliberately limited
so that an administrator does not implicitly control a user's personal data.

V1 authorization is based on two roles: `ADMIN` and `USER`. Identity
must always be derived from authenticated server-side session context rather
than caller-supplied identity fields.

## Decision

### Initial administrator

The initial administrator is a **database seed**, not an application-startup
bootstrap operation. Liquibase creates the initial `ADMIN` row as part of the
schema initialization and stores only a BCrypt password hash. The corresponding
empty `UserProfile` is seeded in the same migration.

There are no `SAMVAAD_BOOTSTRAP_ADMIN_*` environment variables and no runtime
administrator-provisioning runner/service. Application startup must not mutate
the database merely to establish the initial administrator.

The seeded development/deployment bootstrap credential uses the fixed initial
administrator account defined by the migration. The initial password is a
known default. Self-service password change is implemented, but first-login
enforcement requiring the administrator to change that default remains outside
the current backend slice.

### User provisioning

V1 uses **admin-only user provisioning**. There is no public self-registration
flow.

An authenticated administrator may create a user with:

- `username` — required
- `password` — required at creation
- `email` — optional at creation

The server generates the permanent `userId`.

Newly provisioned non-admin users are assigned the `USER` role. The
caller cannot select or change the role through the provisioning API.

Creating a `User` also creates its corresponding empty `UserProfile` in the same
logical user-creation operation. The profile starts with nullable profile fields;
there is no separate V1 "create profile" lifecycle endpoint.

### Roles

V1 has exactly one role per user:

- `ADMIN`
- `USER`

The role belongs to the `User` identity. The role column is non-null and defaults
to `USER` for provisioned users. There is no role-change API in this
V1 slice.

### Username

Username is immutable after creation. Neither the administrator nor the user
may change it in V1. The permanent `userId` remains the internal identity and is
unaffected by username discovery.

### Password and email ownership

The administrator supplies the initial password when provisioning the account.
The password is immediately processed through BCrypt with a generated salt and
only the resulting hash is persisted. Plaintext passwords are never persisted,
logged, emitted in events, or returned by the API.

After creation, changing email is a user-owned operation; the admin may not edit
another user's email in V1. The user may change their own password after
authentication. The same self-service operations are available to an administrator
for their own account.

Email is case-insensitively unique for non-null values and the database enforces
that invariant. Duplicate email attempts return `409 Conflict`.

### Administrator permissions

For V1, an administrator may:

- create users
- list users
- retrieve user records as permitted by the API
- delete users
- read any user's `UserProfile`
- change their own email and password through self-service operations

An administrator may **not** edit another existing user's username, password,
email, or `UserProfile`. An administrator may edit their own profile through the
normal self-service profile operation.

Deleting a user is the V1 administrative mechanism for removing/revoking that
account. Account pausing, suspension, or temporary disablement is deferred.

### User permissions

An authenticated standard user may operate on their own account/profile where
the operation permits self-service:

- change their own password
- change their own email
- update their own `UserProfile`
- retrieve their own account/profile through the corresponding protected API

A standard user cannot read or modify another user's account or profile unless a
future relationship rule explicitly grants profile-read visibility.

### Profile visibility and friendship

Profile read access is relationship-aware:

- every authenticated user may read their own profile;
- an authenticated `ADMIN` may read any user's profile;
- an authenticated `USER` may not read a non-friend's profile;
- once two users have an accepted friendship, each friend may read the other
  user's profile;
- friendship never grants profile edit permission.

Profile write access remains ownership-based:

- a user may edit only their own profile;
- an administrator may edit their own profile;
- an administrator may not edit another user's profile;
- a friend may not edit another friend's profile.

The current authentication/authorization slice only establishes the identity and
role checks needed for these rules. Friend-based read authorization becomes
active when the friendship relationship model is implemented.

### Authorization principle

Authorization is evaluated from the authenticated server-side identity/session
context, not from a caller-supplied `userId` claim or request body field.

Ownership checks therefore use the authenticated `userId` for self-service write
operations. Role checks are applied to administrative operations, and future
friendship checks are applied to relationship-based profile reads.

### JWT and session validation

For every authenticated request, the server validates the access JWT signature
and expiration, then validates the referenced session using the JWT `sid`.
The session must exist, must not be revoked, and must not be past its
`refresh_token_expires_at`. The JWT `sub` must match the user bound to the
session.

The session identifier (`sid`) is authoritative for server-side session
validation. A validly signed JWT whose session is missing, revoked, or expired
is rejected with `401 Unauthorized`.

Session validation is performed per authenticated request rather than by a
long-lived cache, preserving immediate revocation semantics.

### Login identifier

The existing login behavior remains username-or-email based. Username remains the
exact V1 discovery key; email is not used as the messaging discovery key.

### HTTP error semantics

- unauthenticated access to a protected endpoint returns `401 Unauthorized`;
- an authenticated user lacking permission returns `403 Forbidden`;
- invalid, expired, revoked, or session-missing access tokens return `401`;
- validation failures remain `400`;
- duplicate username or email returns `409`.

The existing API error body shape is preserved for this slice. Cross-user denial
uses `403` rather than `404`.

## Consequences

The V1 account and profile APIs must distinguish self-service ownership from
administrative read authority, and relationship-based profile visibility.
A generic "edit user" capability must not grant an administrator permission to
mutate personal profile or credential data.

A user-creation request must carry the initial password, but the stored `User`
record contains only its BCrypt hash. The database schema makes
`password_hash` non-null; passwordless users are not a supported account state.

Initial administrator creation is a Liquibase responsibility and therefore
occurs before normal application services start. Runtime application startup
has no special administrator mutation path.

Authentication remains server-derived identity: protected endpoints cannot trust
path/body `userId` values as proof of ownership.

Per-request session lookup provides immediate revocation at the cost of a
read-only database lookup for authenticated requests. This is preferred for V1
correctness over introducing a session-validation cache.

The friend relationship is an authorization input for profile reads, but the
friendship model itself remains part of the later relationship vertical slice.

## Source material

- `docs/adr/0002-user-profile-boundary-and-email-ownership.md`
- `docs/adr/0003-authentication-and-session-model.md`
- `docs/adr/0008-friend-request-gated-direct-messaging.md`
- `docs/Samvaad Product & Design Decisions.md`
- `docs/Samvaad Technical Design.md`
