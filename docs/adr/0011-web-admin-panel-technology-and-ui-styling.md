# ADR 0011: Web admin panel technology and UI styling

## Status

Accepted; implementation pending.

## Context

Samvaad is beginning its web-admin-panel work after the backend was reorganized as a monorepo. The admin panel is an operational client of the existing server APIs and must not introduce a competing server or authentication model.

The project needs a modern, maintainable UI without accumulating multiple component/styling systems or unnecessary state-management infrastructure.

## Decision

The Samvaad web admin panel will use:

- **Angular** for the application framework.
- **TypeScript** as the application language through the Angular toolchain.
- **Tailwind CSS** as the UI styling/layout system.
- **No Bootstrap**.
- **No Angular Material as a required UI component system** for the initial admin panel.

The initial implementation should rely on Angular components plus Tailwind rather than mixing Tailwind with a second opinionated component library. This keeps the visual language consistent and leaves the UI free to evolve without coupling the admin panel to a component-library-specific design system.

The admin panel consumes the stable Samvaad HTTP/WebSocket contracts. It does not define or alter backend/domain architecture merely to suit the client.

No global state-management framework is mandated by this decision. Additional state-management infrastructure may be introduced later only if concrete application complexity justifies it.

## Consequences

- The admin UI has one primary styling system and avoids component-library/style conflicts.
- Tailwind provides utility-driven layout and visual customization while Angular supplies application structure, routing, forms, and components.
- The team retains freedom to replace or remove Tailwind later without changing the server architecture.
- The initial admin panel remains a thin client of the existing backend contracts.

## Explicitly deferred

- A third-party Angular component library.
- Global state-management libraries such as NgRx.
- Final visual design system, branding, and component inventory.
- Authentication UI details beyond consuming the existing server authentication/session contract.
