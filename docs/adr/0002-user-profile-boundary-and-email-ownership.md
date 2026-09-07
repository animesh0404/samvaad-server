# ADR 0002: User/Profile boundary and email ownership

## Status

Accepted.

## Decision

`User` owns the permanent `userId`, immutable username, email, and future
authentication/account data. `UserProfile` owns first, middle, and last names,
display name, bio, avatar URL, status message, and future profile fields.

`userId` remains the internal identity. Username is the exact messaging
discovery identifier; email is neither the internal identity nor the messaging
discovery identifier. For V1, username and email may both be login identifiers.

## Consequences

Email lifecycle details—including mutability and verification—remain
unresolved. API DTOs stay separate from persistence entities. Profile changes
must not change account identity.

## Source material

- `docs/Samvaad Product & Design Decisions.md`, sections 2.1–2.4
- `docs/Samvaad Technical Design.md`, sections 2.1–2.2
- Reconciliation decision: email ownership/login role
