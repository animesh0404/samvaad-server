import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideSpecRouter } from '../../testing/spec-router';
import { describe, expect, it } from 'vitest';
import { adminGuard, authGuard } from './auth.guard';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { TokenStoreService } from './token-store.service';
import { UserDto } from '../api/models';

import type { CanActivateFn } from '@angular/router';
import { firstValueFrom } from 'rxjs';

const ADMIN: UserDto = { userId: 'u-1', username: 'admin', email: null, role: 'ADMIN' };
const USER: UserDto = { userId: 'u-2', username: 'bob', email: null, role: 'USER' };

function jwt(sub: string): string {
  const payload = btoa(JSON.stringify({ sub })).replace(/\+/g, '-').replace(/\//g, '_');
  return `h.${payload}.s`;
}

function loginResponse(sub: string) {
  return { accessToken: jwt(sub), refreshToken: 'r', expiresIn: 86400, sessionId: 's' };
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
    backend: TestBed.inject(HttpTestingController),
    router: TestBed.inject(Router),
    run: (guard: CanActivateFn) =>
      TestBed.runInInjectionContext(() => guard({} as never, {} as never)),
  };
}

function loginAs(fixture: ReturnType<typeof setup>, user: UserDto) {
  const pending = fixture.auth.login(user.username, 'pw').toPromise();
  fixture.backend.expectOne('/api/auth/login').flush(loginResponse(user.userId));
  fixture.backend.expectOne(`/api/users/${user.userId}`).flush(user);
  return pending;
}

describe('authGuard', () => {
  it('redirects anonymous users and allows sessions', () => {
    const f = setup();
    expect(f.router.serializeUrl(f.run(authGuard) as never)).toBe('/login');
    f.tokens.set({ accessToken: 'a', refreshToken: 'r', sessionId: 's' });
    expect(f.run(authGuard)).toBe(true);
    f.backend.verify();
  });

  it('allows a session restored from persisted storage', () => {
    // Seed storage before the store is created, mirroring application
    // startup after a browser reload.
    sessionStorage.clear();
    sessionStorage.setItem('samvaad.web-admin.accessToken', 'a');
    sessionStorage.setItem('samvaad.web-admin.refreshToken', 'r');
    sessionStorage.setItem('samvaad.web-admin.sessionId', 's');
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideSpecRouter(),
      ],
    });
    const tokens = TestBed.inject(TokenStoreService);
    expect(tokens.hasTokens()).toBe(true);
    const run = TestBed.runInInjectionContext(() => authGuard({} as never, {} as never));
    expect(run).toBe(true);
    TestBed.inject(HttpTestingController).verify();
    sessionStorage.clear();
  });
});

describe('adminGuard', () => {
  it('allows known ADMIN users', async () => {
    const f = setup();
    await loginAs(f, ADMIN);
    expect(f.run(adminGuard)).toBe(true);
    f.backend.verify();
  });

  it('sends non-ADMIN bootstrap results back to login', async () => {
    const f = setup();
    // Login itself rejects non-ADMIN sessions, so this branch is defensive:
    // a bootstrapped non-ADMIN record must still not enter the shell.
    f.tokens.set({ accessToken: jwt('u-2'), refreshToken: 'r', sessionId: 's' });
    const pending = firstValueFrom(f.run(adminGuard) as import('rxjs').Observable<unknown>);
    f.backend.expectOne('/api/users/u-2').flush(USER);
    const tree = await pending;
    expect(f.router.serializeUrl(tree as never)).toBe('/login');
    f.backend.verify();
  });

  it('bootstraps from the token when the user is unknown', async () => {
    const f = setup();
    f.tokens.set({ accessToken: jwt('u-9'), refreshToken: 'r', sessionId: 's' });

    const pending = firstValueFrom(f.run(adminGuard) as import('rxjs').Observable<unknown>);
    f.backend.expectOne('/api/users/u-9').flush(ADMIN);
    await expect(pending).resolves.toBe(true);
    f.backend.verify();
  });

  it('sends anonymous traffic to login', () => {
    const f = setup();
    expect(f.router.serializeUrl(f.run(adminGuard) as never)).toBe('/login');
    f.backend.verify();
  });

  it('expired access with rejected refresh ends at login with cleared state', async () => {
    const f = setup();
    f.tokens.set({ accessToken: jwt('u-9'), refreshToken: 'revoked', sessionId: 's' });

    const pending = firstValueFrom(f.run(adminGuard) as import('rxjs').Observable<unknown>);
    // Bootstrap lookup fails on the expired access token; the interceptor
    // then spends the revoked refresh token and also fails.
    f.backend.expectOne('/api/users/u-9').flush({ message: 'x' }, { status: 401, statusText: 'U' });
    f.backend
      .expectOne('/api/auth/refresh')
      .flush({ message: 'bad' }, { status: 401, statusText: 'U' });
    const tree = await pending;

    expect(f.router.serializeUrl(tree as never)).toBe('/login');
    expect(f.tokens.hasTokens()).toBe(false);
    expect(sessionStorage.getItem('samvaad.web-admin.accessToken')).toBeNull();
    expect(sessionStorage.getItem('samvaad.web-admin.refreshToken')).toBeNull();
    expect(sessionStorage.getItem('samvaad.web-admin.sessionId')).toBeNull();
    f.backend.verify();
  });
});
