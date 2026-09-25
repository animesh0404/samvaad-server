# ADR 0005: V1 scope and data-protection boundary

## Status

Accepted.

## Decision

V1 originally excluded E2EE, but that specific exclusion has been **superseded by ADR 0018**. ADR 0018 is now authoritative for V1 end-to-end encrypted one-to-one messaging.

The following original V1 exclusions remain in force unless explicitly superseded by a later ADR:

- attachments
- reactions
- groups
- rich text
- moderation
- other features not separately admitted into V1 scope

Application-level encryption of non-message profile/private application data remains a separate future security concern unless explicitly brought into scope.

Passwords remain an exception: they use BCrypt with generated salt, and plaintext passwords must never be persisted or logged.

For messaging, the server is no longer permitted to persist message content in a server-readable plaintext representation once the ADR 0018 E2EE implementation slice is adopted. The E2EE design is client-side encryption with server-retained ciphertext and server-visible metadata limited to what is required for routing, ordering, delivery, synchronization, and necessary abuse protection. The current implementation is in a transitional foundation phase: the device/enrollment/prekey/recovery boundary is implemented, while the existing plaintext message path remains temporarily in place until the subsequent messaging-encryption slice migrates message persistence to ciphertext.

## Consequences

- ADR 0018 governs the V1 messaging confidentiality boundary and supersedes this ADR's earlier E2EE deferral.
- Do not reintroduce a separate plaintext message-storage path or a second encryption design outside the ADR 0018 protocol boundary.
- Profile/account fields that are still outside the E2EE message boundary may remain server-readable unless a later decision protects them.
- Existing domain behavior, API contracts, authorization, and messaging invariants should remain stable while the message persistence representation is migrated to ciphertext/encrypted-envelope storage. The plaintext message path is transitional implementation state and is not the target V1 security boundary.

## Source material

- [ADR 0018: V1 E2EE, Signal/Sesame, and independent device identity](0018-v1-e2ee-signal-sesame-and-independent-device-identity.md)
- `docs/Samvaad Product & Design Decisions.md`, sections 11 and 14
- `docs/Samvaad Implementation Roadmap.md`