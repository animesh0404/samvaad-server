# Samvaad Web Admin

Angular 22 + Tailwind CSS v4 administration UI for the Samvaad server.
Thin HTTP client over the existing backend contracts; the server remains authoritative.

## Prerequisites

- Node 24 (`nvm use` reads `.nvmrc`)
- npm (comes with Node; dependencies are locked in `package-lock.json`)
- Backend running on `http://localhost:8080` (see `../server`, e.g. `cd ../server && ./gradlew bootTestRun`)

## Development

```bash
nvm use
npm install
npm start         # ng serve on :4200, /api proxied to :8080
```

## Quality gates

```bash
npm run build     # production build → dist/web-admin/browser/
npm test          # unit tests (Vitest, single run)
```

## Notes

- Login uses `clientPlatform: "WEB"` and sends no `installationId`.
- Web Admin session tokens (`accessToken`, `refreshToken`, `sessionId`) are
  persisted in `sessionStorage` (ADR 0014), so a page reload restores the
  session instead of requiring re-login. Closing the tab ends the session.
  No tokens use `localStorage`, cookies, or IndexedDB.
- Application startup restores the persisted session before the first route
  decision: a usable session bootstraps the current user, an expired access
  token rotates through the existing refresh call, and a rejected session is
  cleared back to `/login`.
- Expired access tokens recover transparently: the first 401 triggers one
  shared refresh and retries the failed request once. There is no proactive
  refresh timer by design; expiry is handled reactively.
- The server caps each user at five active sessions: against a long-running
  backend, stale sessions can surface session-limit errors, so prefer a
  fresh backend database or log out to free a slot.
- Only ADMIN users can use this UI; authorization is enforced server-side.
