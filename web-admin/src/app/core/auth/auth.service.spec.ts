import { HttpErrorResponse } from '@angular/common/http';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideSpecRouter } from '../../testing/spec-router';
import { describe, expect, it } from 'vitest';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { NonAdminLoginError } from './auth.service';
import { TokenStoreService } from './token-store.service';
import { UserDto } from '../api/models';
import { UsersApiService } from '../api/users-api.service';

const ADMIN: UserDto = { userId: 'u-1', username: 'admin', email: 'a@x.com', role: 'ADMIN' };
const USER: UserDto = { userId: 'u-2', username: 'bob', email: null, role: 'USER' };

function fakeJwt(sub: string): string {
  const payload = btoa(JSON.stringify({ sub })).replace(/\+/g, '-').replace(/\//g, '_');
  return `h.${payload}.s`;
}

function unpaddedJwt(sub: string): string {
  // Real server JWTs omit base64 padding; atob must still decode them.
  const payload = btoa(JSON.stringify({ sub }))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `h.${payload}.s`;
}

function setup() {
  sessionStorage.clear();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      provideSpecRouter(),
    ],
  });
  return {
    auth: TestBed.inject(AuthService),
    tokens: TestBed.inject(TokenStoreService),
    users: TestBed.inject(UsersApiService),
    backend: TestBed.inject(HttpTestingController),
  };
}

/**
 * The refresh single-flight resolves through promises, so chained requests
 * after a refresh flush land on a later microtask. Yield before asserting.
 */
function flushMicrotasks(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('AuthService', () => {
  it('login stores tokens and bootstraps the user', async () => {
    const { auth, tokens, backend: http } = setup();

    const done = auth.login('admin', 'admin123').toPromise();
    http.expectOne('/api/auth/login').flush({
      accessToken: fakeJwt('u-1'),
      refreshToken: 'r-1',
      expiresIn: 86400,
      sessionId: 's-1',
    });
    http.expectOne('/api/users/u-1').flush(ADMIN);
    const user = await done;

    expect(user).toEqual(ADMIN);
    expect(tokens.hasTokens()).toBe(true);
    expect(auth.isAdmin()).toBe(true);
    http.verify();
  });

  it('login clears state on bad credentials', async () => {
    const { auth, tokens, backend: http } = setup();

    const done = auth.login('admin', 'wrong').toPromise();
    http
      .expectOne('/api/auth/login')
      .flush({ message: 'Invalid credentials' }, { status: 401, statusText: 'Unauthorized' });
    await expect(done).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(tokens.hasTokens()).toBe(false);
    http.verify();
  });

  it('refresh is single-flight across concurrent callers', async () => {
    const { auth, tokens, backend: http } = setup();
    tokens.set({ accessToken: fakeJwt('u-1'), refreshToken: 'old', sessionId: 's' });

    const first = auth.refreshAccessToken().toPromise();
    const second = auth.refreshAccessToken().toPromise();
    const refreshCalls = http.match('/api/auth/refresh');
    expect(refreshCalls).toHaveLength(1);
    refreshCalls[0]?.flush({
      accessToken: fakeJwt('u-1'),
      refreshToken: 'new',
      expiresIn: 1,
      sessionId: 's',
    });

    await expect(first).resolves.toContain('.');
    await expect(second).resolves.toContain('.');
    expect(tokens.session()?.refreshToken).toBe('new');
    http.verify();
  });

  it('starts a fresh rotation after the previous one settles', async () => {
    const { auth, tokens, backend: http } = setup();
    tokens.set({ accessToken: fakeJwt('u-1'), refreshToken: 'old', sessionId: 's' });

    const first = auth.refreshAccessToken().toPromise();
    http.expectOne('/api/auth/refresh').flush({
      accessToken: fakeJwt('u-1'),
      refreshToken: 'mid',
      expiresIn: 1,
      sessionId: 's',
    });
    await first;

    const second = auth.refreshAccessToken().toPromise();
    http.expectOne('/api/auth/refresh').flush({
      accessToken: fakeJwt('u-1'),
      refreshToken: 'new',
      expiresIn: 1,
      sessionId: 's',
    });
    await second;
    expect(tokens.session()?.refreshToken).toBe('new');
    http.verify();
  });

  it('logout posts then clears state even when the call fails', async () => {
    const { auth, tokens, backend: http } = setup();
    tokens.set({ accessToken: 'tok', refreshToken: 'r', sessionId: 's' });

    const done = auth.logout().toPromise();
    http.expectOne('/api/auth/logout').flush('gone', { status: 500, statusText: 'Error' });
    await done;
    expect(tokens.hasTokens()).toBe(false);
    http.verify();
  });

  it('decodes the JWT sub for the bootstrap lookup', () => {
    const { auth, tokens } = setup();
    expect(auth.userIdFromAccessToken()).toBeNull();
    tokens.set({ accessToken: fakeJwt('user-42'), refreshToken: 'r', sessionId: 's' });
    expect(auth.userIdFromAccessToken()).toBe('user-42');
    tokens.set({ accessToken: 'not-a-jwt', refreshToken: 'r', sessionId: 's' });
    expect(auth.userIdFromAccessToken()).toBeNull();
  });

  it('rejects valid non-ADMIN credentials without retaining any session', async () => {
    const { auth, tokens, backend: http } = setup();

    const done = auth.login('bob', 'secret').toPromise();
    http.expectOne('/api/auth/login').flush({
      accessToken: fakeJwt('u-2'),
      refreshToken: 'r-2',
      expiresIn: 86400,
      sessionId: 's-2',
    });
    http.expectOne('/api/users/u-2').flush(USER);
    await expect(done).rejects.toBeInstanceOf(NonAdminLoginError);
    await expect(done).rejects.toThrow(/not authorized to access the administration panel/);

    expect(tokens.hasTokens()).toBe(false);
    expect(tokens.session()).toBeNull();
    expect(auth.currentUser()).toBeNull();
    expect(auth.isAdmin()).toBe(false);
    http.verify();
  });

  it('decodes real-world unpadded JWT payloads', () => {
    const { auth, tokens } = setup();
    // 'u-1' JSON payload length forces padding removal to exercise the fix.
    tokens.set({ accessToken: unpaddedJwt('u-1'), refreshToken: 'r', sessionId: 's' });
    expect(tokens.session()?.accessToken.split('.')[1]).not.toMatch(/=$/);
    expect(auth.userIdFromAccessToken()).toBe('u-1');
  });

  it('successful login persists the complete session', async () => {
    const { auth, tokens, backend: http } = setup();

    const done = auth.login('admin', 'admin123').toPromise();
    http.expectOne('/api/auth/login').flush({
      accessToken: fakeJwt('u-1'),
      refreshToken: 'r-1',
      expiresIn: 86400,
      sessionId: 's-1',
    });
    http.expectOne('/api/users/u-1').flush(ADMIN);
    await done;

    expect(sessionStorage.getItem('samvaad.web-admin.accessToken')).toContain('.');
    expect(sessionStorage.getItem('samvaad.web-admin.refreshToken')).toBe('r-1');
    expect(sessionStorage.getItem('samvaad.web-admin.sessionId')).toBe('s-1');
    expect(tokens.hasTokens()).toBe(true);
    http.verify();
  });

  it('startup with a persisted session bootstraps the current user', async () => {
    const { auth, tokens, backend: http } = setup();
    tokens.set({ accessToken: fakeJwt('u-1'), refreshToken: 'r-1', sessionId: 's-1' });

    const done = auth.initialize();
    http.expectOne('/api/users/u-1').flush(ADMIN);
    await done;

    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.currentUser()).toEqual(ADMIN);
    http.verify();
  });

  it('startup with no persisted session remains unauthenticated', async () => {
    const { auth, backend: http } = setup();

    await auth.initialize();

    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.currentUser()).toBeNull();
    http.verify();
  });

  it('startup refreshes an expired access token and continues the session', async () => {
    const { auth, tokens, backend: http } = setup();
    // 'expired' is not a decodable JWT, so bootstrap cannot run and the
    // existing refresh mechanism must take over.
    tokens.set({ accessToken: 'expired', refreshToken: 'valid-refresh', sessionId: 's-1' });

    const done = auth.initialize();
    http.expectOne('/api/auth/refresh').flush({
      accessToken: fakeJwt('u-1'),
      refreshToken: 'rotated',
      expiresIn: 86400,
      sessionId: 's-1',
    });
    await flushMicrotasks();
    http.expectOne('/api/users/u-1').flush(ADMIN);
    await done;

    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.currentUser()).toEqual(ADMIN);
    expect(sessionStorage.getItem('samvaad.web-admin.refreshToken')).toBe('rotated');
    http.verify();
  });

  it('startup with a rejected refresh token clears persisted state', async () => {
    const { auth, tokens, backend: http } = setup();
    tokens.set({ accessToken: 'expired', refreshToken: 'revoked', sessionId: 's-1' });

    const done = auth.initialize();
    http
      .expectOne('/api/auth/refresh')
      .flush({ message: 'Invalid refresh token' }, { status: 401, statusText: 'Unauthorized' });
    await done;

    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.currentUser()).toBeNull();
    expect(tokens.hasTokens()).toBe(false);
    expect(sessionStorage.getItem('samvaad.web-admin.accessToken')).toBeNull();
    expect(sessionStorage.getItem('samvaad.web-admin.refreshToken')).toBeNull();
    expect(sessionStorage.getItem('samvaad.web-admin.sessionId')).toBeNull();
    http.verify();
  });

  it('refresh rotation updates persisted state', async () => {
    const { auth, tokens, backend: http } = setup();
    tokens.set({ accessToken: fakeJwt('u-1'), refreshToken: 'old', sessionId: 's' });

    const rotated = auth.refreshAccessToken().toPromise();
    http.expectOne('/api/auth/refresh').flush({
      accessToken: fakeJwt('u-1'),
      refreshToken: 'new',
      expiresIn: 1,
      sessionId: 's',
    });
    await rotated;

    expect(sessionStorage.getItem('samvaad.web-admin.refreshToken')).toBe('new');
    expect(sessionStorage.getItem('samvaad.web-admin.accessToken')).toContain('.');
    http.verify();
  });

  it('logout clears persisted state', async () => {
    const { auth, tokens, backend: http } = setup();
    tokens.set({ accessToken: 'tok', refreshToken: 'r', sessionId: 's' });

    const done = auth.logout().toPromise();
    http.expectOne('/api/auth/logout').flush('ok');
    await done;

    expect(tokens.hasTokens()).toBe(false);
    expect(sessionStorage.getItem('samvaad.web-admin.accessToken')).toBeNull();
    expect(sessionStorage.getItem('samvaad.web-admin.refreshToken')).toBeNull();
    expect(sessionStorage.getItem('samvaad.web-admin.sessionId')).toBeNull();
    http.verify();
  });
});
