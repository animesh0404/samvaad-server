import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideSpecRouter } from '../../testing/spec-router';
import { describe, expect, it } from 'vitest';
import { authInterceptor } from './auth.interceptor';
import { TokenStoreService } from './token-store.service';

function b64url(value: object): string {
  return btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_');
}
const jwt = (sub: string) => `h.${b64url({ sub })}.s`;

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      provideSpecRouter(),
    ],
  });
  return {
    http: TestBed.inject(HttpClient),
    backend: TestBed.inject(HttpTestingController),
    tokens: TestBed.inject(TokenStoreService),
  };
}

/**
 * The refresh single-flight resolves through promises, so the retry it
 * triggers lands on a later microtask than a purely synchronous RxJS chain.
 * Yield to the macrotask queue before asserting on retried requests.
 */
function flushMicrotasks(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('authInterceptor', () => {
  it('attaches the Bearer token to API calls', () => {
    const { http, backend, tokens } = setup();
    tokens.set({ accessToken: 'tok-1', refreshToken: 'r', sessionId: 's' });

    http.get('/api/users').subscribe();
    const req = backend.expectOne('/api/users');
    expect(req.request.headers.get('Authorization')).toBe('Bearer tok-1');
    req.flush([]);
    backend.verify();
  });

  it('leaves login/refresh calls untouched', () => {
    const { http, backend, tokens } = setup();
    tokens.set({ accessToken: 'tok-1', refreshToken: 'r', sessionId: 's' });

    http.post('/api/auth/login', {}).subscribe();
    const login = backend.expectOne('/api/auth/login');
    expect(login.request.headers.has('Authorization')).toBe(false);
    login.flush({});

    http.post('/api/auth/refresh', {}).subscribe();
    const refresh = backend.expectOne('/api/auth/refresh');
    expect(refresh.request.headers.has('Authorization')).toBe(false);
    refresh.flush({});
    backend.verify();
  });

  it('refreshes once on 401 and retries with the new token', async () => {
    const { http, backend, tokens } = setup();
    tokens.set({ accessToken: 'old', refreshToken: 'r', sessionId: 's' });

    let result: unknown;
    http.get('/api/users').subscribe((v) => (result = v));

    backend.expectOne('/api/users').flush({ message: 'x' }, { status: 401, statusText: 'U' });
    const refresh = backend.expectOne('/api/auth/refresh');
    expect(refresh.request.headers.has('Authorization')).toBe(false);
    refresh.flush({ accessToken: 'new', refreshToken: 'r2', expiresIn: 1, sessionId: 's' });

    await flushMicrotasks();
    const retry = backend.expectOne('/api/users');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer new');
    retry.flush([{ userId: 'u' }]);
    expect(result).toEqual([{ userId: 'u' }]);
    backend.verify();
  });

  it('clears state when refresh fails', async () => {
    const { http, backend, tokens } = setup();
    tokens.set({ accessToken: jwt('u-1'), refreshToken: 'r', sessionId: 's' });

    let failed = false;
    http.get('/api/users').subscribe({ error: () => (failed = true) });

    backend.expectOne('/api/users').flush({ message: 'x' }, { status: 401, statusText: 'U' });
    backend
      .expectOne('/api/auth/refresh')
      .flush({ message: 'bad' }, { status: 401, statusText: 'U' });

    await flushMicrotasks();
    expect(failed).toBe(true);
    expect(tokens.hasTokens()).toBe(false);
    backend.verify();
  });

  it('does not retry a request twice', async () => {
    const { http, backend, tokens } = setup();
    tokens.set({ accessToken: jwt('u-1'), refreshToken: 'r', sessionId: 's' });

    let failures = 0;
    http.get('/api/users', { context: undefined }).subscribe({ error: () => failures++ });
    backend.expectOne('/api/users').flush({ message: 'x' }, { status: 401, statusText: 'U' });
    backend
      .expectOne('/api/auth/refresh')
      .flush({ accessToken: 'n', refreshToken: 'r2', expiresIn: 1, sessionId: 's' });
    await flushMicrotasks();
    // Second 401 on the already-retried request surfaces instead of looping.
    backend.expectOne('/api/users').flush({ message: 'x' }, { status: 401, statusText: 'U' });

    expect(failures).toBe(1);
    // Any second refresh attempt would leave an outstanding request and
    // fail backend.verify() below.
    backend.verify();
  });
});
