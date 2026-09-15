import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideSpecRouter } from '../../testing/spec-router';
import { describe, expect, it } from 'vitest';
import { authInterceptor } from '../../core/auth/auth.interceptor';
import { UserCreatePage } from './user-create-page';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      provideSpecRouter(),
    ],
  });
  const backend = TestBed.inject(HttpTestingController);
  const fixture: ComponentFixture<UserCreatePage> = TestBed.createComponent(UserCreatePage);
  fixture.detectChanges();
  return { fixture, page: fixture.componentInstance, backend };
}

describe('UserCreatePage', () => {
  it('rejects usernames outside the server contract', () => {
    const { fixture, page } = setup();
    page['form'].setValue({ username: 'bad name!', password: 'pw', email: '' });
    page['submit']();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('letters, digits, and underscores');
  });

  it('posts the server DTO shape and omits blank email as null', () => {
    const { page, backend } = setup();
    page['form'].setValue({ username: 'carol', password: 'secret', email: '' });
    page['submit']();
    const req = backend.expectOne('/api/users');
    expect(req.request.body).toEqual({ username: 'carol', password: 'secret', email: null });
    req.flush({ userId: 'u-9', username: 'carol', email: null, role: 'USER' });
    backend.verify();
  });

  it('shows conflict messages from the server', () => {
    const { fixture, page, backend } = setup();
    page['form'].setValue({ username: 'bob', password: 'secret', email: '' });
    page['submit']();
    backend
      .expectOne('/api/users')
      .flush({ message: 'Username already exists' }, { status: 409, statusText: 'C' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
      'Username already exists',
    );
    backend.verify();
  });
});
