# ADR 0002: User/Profile boundary and email ownership

## Status

Accepted.

## Decision

`User` owns the permanent `userId`, immutable username, email, and
authentication/account data. `UserProfile` owns first, middle, and last names,
display name, bio, avatar URL, status message, and future profile fields.

`userId` is the internal identity. Username is the exact messaging discovery
identifier; email is neither the internal identity nor the V1 messaging
discovery identifier.

Username is immutable after account creation.

The V1 account lifecycle is admin-provisioned rather than publicly self-created.
An administrator supplies the initial required username and password and may
optionally supply email. The server generates `userId`.

Creating a `User` also creates its corresponding empty `UserProfile`. There is no
separate V1 create-profile lifecycle operation.

After creation, email is user-owned: an administrator may not modify it. An
authenticated user may change their own email through the self-service account
operation defined by ADR 0007.

Profile data is likewise user-owned. An authenticated user may update only their
own profile; administrators do not gain permission to modify another user's
personal profile merely because they administer the account.

## Consequences

API DTOs remain separate from persistence entities. Profile changes do not change
account identity. Administrative user management and self-service profile/account
management are separate authorization concerns.

Email verification lifecycle details remain unresolved and can be introduced by
a later security decision without changing the User/UserProfile boundary.

## Source material

- `docs/adr/0007-user-provisioning-and-authorization.md`
- `docs/Samvaad Product & Design Decisions.md`, sections 2–4
- `docs/Samvaad Technical Design.md`, sections 2.1–2.2
