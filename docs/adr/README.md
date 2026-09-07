# Architecture Decision Records

ADRs are concise records of durable architectural decisions and their
consequences. They are not a replacement for the original Samvaad design
records, which remain unchanged in this directory:

- [Samvaad Product & Design Decisions](../Samvaad%20Product%20%26%20Design%20Decisions.md)
- [Samvaad Technical Design](../Samvaad%20Technical%20Design.md)
- [Samvaad Implementation Roadmap](../Samvaad%20Implementation%20Roadmap.md)

Those documents contain the detailed reasoning, product rules, technical
model, and historical roadmap. ADRs summarize only decisions that are useful
to identify quickly while implementing or reviewing a change.

In the original decision record, **LOCKED** means accepted and **DEFERRED**
means unresolved. A conflict between implementation and a LOCKED decision must
be surfaced for review; it must not be silently changed. An ADR may supersede
an earlier ADR only by explicitly naming it.

## Index

| ADR | Decision |
| --- | --- |
| [0001](0001-server-authoritative-identity-and-state.md) | Server-authoritative identity and state |
| [0002](0002-user-profile-boundary-and-email-ownership.md) | User/Profile boundary and email ownership |
| [0003](0003-authentication-and-session-model.md) | Authentication and session model |
| [0004](0004-conversation-and-message-integrity.md) | Conversation and message integrity |
| [0005](0005-v1-scope-and-data-protection-boundary.md) | V1 scope and data-protection boundary |
| [0006](0006-profile-patch-field-presence.md) | Profile PATCH field-presence semantics |
