import {
  Observable,
  catchError,
  firstValueFrom,
  from,
  map,
  of,
  switchMap,
  tap,
  throwError,
} from 'rxjs';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthApiService } from '../api/auth-api.service';
import { UsersApiService } from '../api/users-api.service';
import { UserDto } from '../api/models';
import { TokenStoreService } from './token-store.service';

/**
 * Rejection for valid credentials without the ADMIN role. Carries the
 * login-level message shown on /login. Authentication itself succeeded;
 * the panel simply refuses to retain the tokens or establish the session.
 */
export class NonAdminLoginError extends Error {
  constructor() {
    super('This account is not authorized to access the administration panel.');
    this.name = 'NonAdminLoginError';
  }
}

/**
 * Session orchestration over the existing server auth contract.
 *
 * - Login posts clientPlatform WEB and no installationId (ADR 0010).
 * - The JWT `sub` claim is decoded client-side ONLY to learn which user
 *   record to fetch for the routing hint; it is never trusted for
 *   authorization. The server remains authoritative (ADMIN is enforced by
 *   `hasRole("ADMIN")` plus in-controller checks on every request).
 * - This panel is ADMIN-only: valid non-ADMIN credentials are rejected at
 *   login with all tokens cleared, so a non-admin session can never be
 *   established here. This is login UX only, not a security boundary.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly authApi = inject(AuthApiService);
  private readonly usersApi = inject(UsersApiService);
  private readonly tokens = inject(TokenStoreService);
  private readonly router = inject(Router);

  private readonly currentUserSignal = signal<UserDto | null>(null);
  readonly currentUser = this.currentUserSignal.asReadonly();
  readonly isAuthenticated = this.tokens.hasTokens;
  readonly isAdmin = computed(() => this.currentUserSignal()?.role === 'ADMIN');

  /**
   * Single-flight refresh shared by concurrent 401 recoveries. Promise-based
   * so concurrent callers trivially share one in-flight rotation; reset when
   * it settles so later 401s start a fresh rotation.
   */
  private refreshInFlight: Promise<string> | null = null;

  login(identifier: string, password: string): Observable<UserDto> {
    return this.authApi.login({ identifier, password, clientPlatform: 'WEB' }).pipe(
      tap((res) =>
        this.tokens.set({
          accessToken: res.accessToken,
          refreshToken: res.refreshToken,
          sessionId: res.sessionId,
        }),
      ),
      switchMap(() => this.bootstrap()),
      tap((user) => {
        if (user.role !== 'ADMIN') {
          this.clearState();
          throw new NonAdminLoginError();
        }
      }),
      catchError((err) => {
        this.clearState();
        return throwError(() => err);
      }),
    );
  }

  /**
   * Resolves the current user through the existing GET /api/users/{id}.
   * Any authenticated user may read their own record; the login flow
   * rejects non-ADMIN users immediately after this resolves.
   */
  bootstrap(): Observable<UserDto> {
    const userId = this.userIdFromAccessToken();
    if (!userId) {
      return throwError(() => new Error('The access token does not identify a user.'));
    }
    return this.usersApi.getUser(userId).pipe(tap((user) => this.currentUserSignal.set(user)));
  }

  refreshAccessToken(): Observable<string> {
    const inFlight = this.refreshInFlight;
    if (inFlight) {
      return from(inFlight);
    }
    const refreshToken = this.tokens.session()?.refreshToken;
    if (!refreshToken) {
      return throwError(() => new Error('No refresh token available.'));
    }
    const pending = firstValueFrom(
      this.authApi.refresh(refreshToken).pipe(
        tap((res) =>
          this.tokens.set({
            accessToken: res.accessToken,
            refreshToken: res.refreshToken,
            sessionId: res.sessionId,
          }),
        ),
        map((res) => res.accessToken),
        catchError((err: unknown) => {
          this.clearState();
          return throwError(() => err);
        }),
      ),
    ).finally(() => {
      if (this.refreshInFlight === pending) {
        this.refreshInFlight = null;
      }
    });
    this.refreshInFlight = pending;
    return from(pending);
  }

  logout(): Observable<unknown> {
    const accessToken = this.tokens.session()?.accessToken;
    const call: Observable<unknown> = accessToken ? this.authApi.logout(accessToken) : of(null);
    return call.pipe(
      catchError(() => of(null)),
      tap(() => {
        this.clearState();
        void this.router.navigate(['/login']);
      }),
    );
  }

  /** Clears memory state and routes to login (used when refresh fails). */
  clearAndGoLogin(): void {
    this.clearState();
    void this.router.navigate(['/login']);
  }

  userIdFromAccessToken(): string | null {
    const token = this.tokens.session()?.accessToken;
    if (!token) {
      return null;
    }
    try {
      const payload = token.split('.')[1];
      if (!payload) {
        return null;
      }
      // Server JWTs are base64url-encoded without padding; atob requires
      // padded base64.
      const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
      const json = atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4));
      const sub = (JSON.parse(json) as { sub?: unknown }).sub;
      return typeof sub === 'string' && sub.length > 0 ? sub : null;
    } catch {
      return null;
    }
  }

  private clearState(): void {
    this.refreshInFlight = null;
    this.tokens.clear();
    this.currentUserSignal.set(null);
  }
}
