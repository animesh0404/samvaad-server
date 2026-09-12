# ADR 0007: Admin-provisioned users and V1 authorization boundary

## Status

Accepted.

## Context

Samvaad V1 is not a public self-registration system. User accounts are
provisioned by an administrator. The account identity and personal profile are
separate concepts, and administrative authority must be deliberately limited
so that an administrator does not implicitly control a user's personal data.

## Decision

### User provisioning

V1 uses **admin-only user provisioning**. There is no public self-registration
flow.

The initial administrator is provisioned during first-time application setup.
The exact bootstrap mechanism and secret-delivery mechanism are deployment
concerns; a plaintext default password must not be committed to source control
or stored in plaintext after bootstrap.

An authenticated administrator may create a user with:

- `username` — required
- `password` — required at creation
- `email` — optional at creation

The server generates the permanent `userId`.

Creating a `User` also creates its corresponding empty `UserProfile` in the same
logical user-creation operation. The profile starts with nullable profile fields;
there is no separate V1 "create profile" lifecycle endpoint.

### Username

Username is immutable after creation. Neither the administrator nor the user
may change it in V1. The permanent `userId` remains the internal identity and is
unaffected by username discovery.

### Password and email ownership

The administrator supplies the initial password when provisioning the account.
The password is immediately processed through the configured password hashing
mechanism and is never persisted or logged in plaintext.

Email, when provided by the administrator at creation time, belongs to the user
account. After creation, changing email is a user-owned operation; the admin may
not edit it in V1.

The user may change their own password after authentication.

### Administrator permissions

For V1, an administrator may:

- create users
- list users
- retrieve user records as permitted by the API
- delete users

An administrator may **not** edit an existing user's username, password, email,
or `UserProfile`.

Deleting a user is the V1 administrative mechanism for removing/revoking that
account. Account pausing, suspension, or temporary disablement is deferred.

### User permissions

An authenticated standard user may operate only on their own account/profile
where the operation permits self-service:

- change their own password
- change their own email
- update their own `UserProfile`
- retrieve their own account/profile through the corresponding protected API

A standard user cannot modify another user's account or profile.

### Authorization principle

Authorization is evaluated from the authenticated server-side identity/session
context, not from a caller-supplied `userId` claim or request body field.

Ownership checks therefore use the authenticated `userId` for self-service
operations. Role checks are applied to administrative operations.

## Consequences

The V1 API must distinguish account-management operations from profile-management
operations even though they refer to the same user. A single generic "edit user"
capability must not grant an administrator permission to mutate personal profile
or credential data.

The API contract should expose profile update as an update/patch operation, not a
profile-creation operation, because profile creation is coupled to user creation.

Future roles and account-lifecycle states can be added without changing the
permanent user identity model.

## Source material

- `docs/adr/0002-user-profile-boundary-and-email-ownership.md`
- `docs/adr/0003-authentication-and-session-model.md`
- `docs/Samvaad Product & Design Decisions.md`
- `docs/Samvaad Technical Design.md`
