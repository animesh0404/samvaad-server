import { Injectable, computed, signal } from '@angular/core';

export interface SessionTokens {
  accessToken: string;
  refreshToken: string;
  sessionId: string;
}

const ACCESS_TOKEN_KEY = 'samvaad.web-admin.accessToken';
const REFRESH_TOKEN_KEY = 'samvaad.web-admin.refreshToken';
const SESSION_ID_KEY = 'samvaad.web-admin.sessionId';

function readStored(key: string): string | null {
  try {
    if (typeof sessionStorage === 'undefined') {
      return null;
    }
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStored(key: string, value: string): void {
  try {
    if (typeof sessionStorage === 'undefined') {
      return;
    }
    sessionStorage.setItem(key, value);
  } catch {
    // Storage unavailable (e.g. private mode): memory state still applies
    // for this page lifetime; persistence is best-effort.
  }
}

function removeStored(): void {
  try {
    if (typeof sessionStorage === 'undefined') {
      return;
    }
    sessionStorage.removeItem(ACCESS_TOKEN_KEY);
    sessionStorage.removeItem(REFRESH_TOKEN_KEY);
    sessionStorage.removeItem(SESSION_ID_KEY);
  } catch {
    // ignore: nothing durable to clean up
  }
}

function isPresent(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

/**
 * Web Admin session token storage (ADR 0014).
 *
 * The complete session token state (access token, refresh token, session
 * id) lives in `sessionStorage` so a full browser reload restores the
 * authenticated session. `sessionStorage` is tab-scoped: closing the tab
 * ends the Web Admin session, and no token is shared across tabs.
 *
 * The server remains authoritative for session validity, refresh-token
 * rotation, revocation, and expiry. Anything the server rejects clears
 * both memory and persisted state through `clear()`.
 */
@Injectable({ providedIn: 'root' })
export class TokenStoreService {
  private readonly tokens = signal<SessionTokens | null>(restore());

  readonly session = this.tokens.asReadonly();
  readonly hasTokens = computed(() => this.tokens() !== null);

  set(tokens: SessionTokens): void {
    this.tokens.set(tokens);
    writeStored(ACCESS_TOKEN_KEY, tokens.accessToken);
    writeStored(REFRESH_TOKEN_KEY, tokens.refreshToken);
    writeStored(SESSION_ID_KEY, tokens.sessionId);
  }

  clear(): void {
    this.tokens.set(null);
    removeStored();
  }
}

/**
 * Restores the persisted session, if complete. Partial or missing state is
 * never manufactured into a session: any leftover entries are removed so
 * the application starts cleanly unauthenticated.
 */
function restore(): SessionTokens | null {
  const accessToken = readStored(ACCESS_TOKEN_KEY);
  const refreshToken = readStored(REFRESH_TOKEN_KEY);
  const sessionId = readStored(SESSION_ID_KEY);
  if (isPresent(accessToken) && isPresent(refreshToken) && isPresent(sessionId)) {
    return { accessToken, refreshToken, sessionId };
  }
  if (accessToken !== null || refreshToken !== null || sessionId !== null) {
    removeStored();
  }
  return null;
}
