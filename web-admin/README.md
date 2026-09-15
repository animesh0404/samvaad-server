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
npm run e2e       # Playwright (needs backend + frontend, see playwright.config.ts)
```

Run e2e against a fresh backend database (`cd ../server && ./gradlew bootTestRun`):
the server caps each user at five active sessions, so reusing a long-running
backend across runs can surface session-limit errors instead of test failures.

## Notes

- Login uses `clientPlatform: "WEB"` and sends no `installationId`.
- Access/refresh tokens live in memory only; a page reload requires re-login.
- Expired access tokens recover transparently: the first 401 triggers one
  shared refresh and retries the failed request once. There is no proactive
  refresh timer by design; expiry is handled reactively.
- Only ADMIN users can use this UI; authorization is enforced server-side.
