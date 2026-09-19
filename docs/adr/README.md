# Architecture Decision Records

ADRs are concise records of durable architectural decisions and their
consequences. They complement the original Samvaad design records by making
current implementation constraints easy to identify and review.

The detailed product/design and technical-design documents remain the broader
reference material:

- [Samvaad Product & Design Decisions](../Samvaad%20Product%20%26%20Design%20Decisions.md)
- [Samvaad Technical Design](../Samvaad%20Technical%20Design.md)
- [Samvaad Implementation Roadmap](../Samvaad%20Implementation%20Roadmap.md)

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
| [0007](0007-user-provisioning-and-authorization.md) | Admin-provisioned users and V1 authorization boundary |
| [0008](0008-friend-request-gated-direct-messaging.md) | Friend-request-gated direct messaging |
| [0009](0009-realtime-stomp-websocket-transport.md) | Realtime STOMP/WebSocket transport |
| [0010](0010-client-session-and-installation-identity.md) | Client session and installation identity |
| [0011](0011-web-admin-panel-technology-and-ui-styling.md) | Web admin panel technology and UI styling |
| [0012](0012-web-admin-packaging-and-deployment-model.md) | Web admin packaging and deployment model |
| [0013](0013-tls-termination-at-deployment-edge.md) | TLS termination at the deployment edge |
| [0014](0014-web-admin-browser-session-storage.md) | Web admin browser-session storage |
