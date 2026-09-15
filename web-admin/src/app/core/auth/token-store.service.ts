import { Injectable, computed, signal } from '@angular/core';

export interface SessionTokens {
  accessToken: string;
  refreshToken: string;
  sessionId: string;
}

/**
 * In-memory-only token storage. Tokens never touch localStorage,
 * sessionStorage, or cookies: a page reload requires re-login.
 * This is a deliberate XSS-exfiltration tradeoff, documented in the README.
 */
@Injectable({ providedIn: 'root' })
export class TokenStoreService {
  private readonly tokens = signal<SessionTokens | null>(null);

  readonly session = this.tokens.asReadonly();
  readonly hasTokens = computed(() => this.tokens() !== null);

  set(tokens: SessionTokens): void {
    this.tokens.set(tokens);
  }

  clear(): void {
    this.tokens.set(null);
  }
}
