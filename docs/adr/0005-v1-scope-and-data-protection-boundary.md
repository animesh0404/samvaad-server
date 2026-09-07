# ADR 0005: V1 scope and data-protection boundary

## Status

Accepted.

## Decision

V1 excludes E2EE, attachments, reactions, groups, rich text, moderation, and
other features listed in the original design record. Application-level
encryption of profile/private application data is deliberately deferred for V1.

Passwords remain an exception: they use BCrypt, and plaintext passwords must
never be persisted or logged. Deferring application-level encryption does not
mean it is unnecessary permanently.

## Consequences

Do not implement profile/message application-level encryption or introduce
placeholder encryption abstractions now. At the same time, avoid coupling
domain behavior, API contracts, or messaging semantics to a specific plaintext
persistence representation so that future protection can be introduced without
a whole-system rewrite.

Current profile/account fields are plaintext at rest; future application-level
encryption is a planned security phase, not a current capability.

## Source material

- `docs/Samvaad Product & Design Decisions.md`, sections 11 and 14
- `docs/Samvaad Implementation Roadmap.md`, section 15
- Reconciliation decision: V1 data encryption
