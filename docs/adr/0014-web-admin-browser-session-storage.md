# ADR 0014: Web admin browser-session storage

## Status

Accepted.

## Context

The Web Admin first slice stored its session tokens in process memory only,
so every full browser reload discarded the session and forced the
administrator to log in again. The server already persists sessions with
rotating refresh tokens, session revocation, and expiry, but the client
threw away both keys to that session on reload.

ADR 0011 deferred authentication UI details beyond consuming the existing
server contract; this ADR records the browser-storage half of that gap.
The server contract (ADR 0003) is unchanged: the backend stays
authoritative for session validity, refresh-token rotation, revocation,
expiry, and active-session limits.

## Decision

The Web Admin persists its complete session token state in browser
`sessionStorage`:

```text
Web Admin accessToken   → sessionStorage
Web Admin refreshToken  → sessionStorage
Web Admin sessionId     → sessionStorage
Server session          → authoritative
```

Storage keys are namespaced as `samvaad.web-admin.accessToken`,
`samvaad.web-admin.refreshToken`, and `samvaad.web-admin.sessionId`, and
all `sessionStorage` access stays centralized in `TokenStoreService`.

Application startup restores that state through `provideAppInitializer`
before the router's initial navigation, so guards never decide on
uninitialized auth state:

```text
No persisted session       → unauthenticated → /login
Usable persisted session   → restore + bootstrap current user → /admin
Expired access token       → existing refresh call rotates the pair, then bootstrap → /admin
Rejected session           → clear memory + persisted state → /login
```

`localStorage`, authentication cookies, IndexedDB, additional
state-management libraries, and proactive refresh timers are all
explicitly not used. `sessionStorage` is tab-scoped: closing the tab ends
the Web Admin session and shares nothing across tabs.

## Consequences

- A normal page refresh keeps the administrator signed in while the
  server session remains valid.
- Logout and refresh failure clear both memory and persisted state, so a
  reload after logout cannot resurrect the session.
- Route guards keep their synchronous form; startup ordering is owned by
  the application initializer, not by per-guard async logic.
- Tokens in `sessionStorage` remain readable by page JavaScript, so the
  existing XSS posture is unchanged in kind: the boundary is same-origin
  code plus server-side revocation, not token invisibility.

## Explicitly deferred

- Cross-tab session sharing (`localStorage`) and "remember me" semantics.
- `HttpOnly`/`SameSite` cookie transport (requires server contract work).
- Proactive refresh timers; expiry stays reactive (`401` → refresh → retry).
- Final visual design system and component inventory (still under ADR 0011).

## Source material

- `docs/adr/0011-web-admin-panel-technology-and-ui-styling.md`
- `docs/adr/0003-authentication-and-session-model.md`
- `web-admin/src/app/core/auth/token-store.service.ts`
- `web-admin/src/app/app.config.ts`
