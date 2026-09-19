import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideSpecRouter } from '../../testing/spec-router';
import { describe, expect, it } from 'vitest';
import { authInterceptor } from '../../core/auth/auth.interceptor';
import { TokenStoreService } from '../../core/auth/token-store.service';
import { LoginPage } from './login-page';

function setup() {
  sessionStorage.clear();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      provideSpecRouter(),
    ],
  });
  TestBed.inject(HttpTestingController);
  const fixture: ComponentFixture<LoginPage> = TestBed.createComponent(LoginPage);
  fixture.detectChanges();
  return { fixture, page: fixture.componentInstance };
}

function jwt(sub: string): string {
  const payload = btoa(JSON.stringify({ sub })).replace(/\+/g, '-').replace(/\//g, '_');
  return `h.${payload}.s`;
}

describe('LoginPage', () => {
  it('blocks empty submits with validation messages', () => {
    const { fixture, page } = setup();
    page['submit']();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button[type="submit"]').disabled).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Enter your username or email.');
  });

  it('shows the server message on invalid credentials', () => {
    const { fixture, page } = setup();
    const backend = TestBed.inject(HttpTestingController);
    page['form'].setValue({ identifier: 'admin', password: 'wrong' });
    page['submit']();
    backend
      .expectOne('/api/auth/login')
      .flush({ message: 'Invalid credentials' }, { status: 401, statusText: 'U' });
    fixture.detectChanges();
    const alert = fixture.nativeElement.querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('Invalid credentials');
    backend.verify();
  });

  it('surfaces the session-limit message verbatim', () => {
    const { fixture, page } = setup();
    const backend = TestBed.inject(HttpTestingController);
    page['form'].setValue({ identifier: 'admin', password: 'admin123' });
    page['submit']();
    backend
      .expectOne('/api/auth/login')
      .flush({ message: 'Maximum active sessions reached' }, { status: 401, statusText: 'U' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
      'Maximum active sessions',
    );
    backend.verify();
  });

  it('posts clientPlatform WEB without installationId', () => {
    const { page } = setup();
    const backend = TestBed.inject(HttpTestingController);
    page['form'].setValue({ identifier: 'admin', password: 'admin123' });
    page['submit']();
    const req = backend.expectOne('/api/auth/login');
    expect(req.request.body).toEqual({
      identifier: 'admin',
      password: 'admin123',
      clientPlatform: 'WEB',
    });
    req.flush({ accessToken: jwt('u-1'), refreshToken: 'r', expiresIn: 1, sessionId: 's' });
    backend
      .expectOne('/api/users/u-1')
      .flush({ userId: 'u-1', username: 'admin', email: null, role: 'ADMIN' });
    backend.verify();
  });

  it('rejects valid non-admin credentials on login without keeping tokens', () => {
    const { fixture, page } = setup();
    const backend = TestBed.inject(HttpTestingController);
    const tokens = TestBed.inject(TokenStoreService);
    page['form'].setValue({ identifier: 'bob', password: 'secret' });
    page['submit']();
    backend.expectOne('/api/auth/login').flush({
      accessToken: jwt('u-2'),
      refreshToken: 'r',
      expiresIn: 1,
      sessionId: 's',
    });
    backend
      .expectOne('/api/users/u-2')
      .flush({ userId: 'u-2', username: 'bob', email: null, role: 'USER' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
      'not authorized to access the administration panel',
    );
    expect(tokens.hasTokens()).toBe(false);
    backend.verify();
  });
});
