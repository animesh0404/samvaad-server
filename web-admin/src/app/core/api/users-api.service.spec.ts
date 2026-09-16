import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideSpecRouter } from '../../testing/spec-router';
import { describe, expect, it } from 'vitest';
import { CreateUserRequest, UserDto, UserProfileDto } from './models';
import { UsersApiService } from './users-api.service';
import { authInterceptor } from '../auth/auth.interceptor';

const USER: UserDto = { userId: 'u-1', username: 'alice', email: 'a@x.com', role: 'USER' };
const PROFILE: UserProfileDto = {
  userId: 'u-1',
  displayName: 'Alice',
  bio: null,
  avatarUrl: null,
  firstName: null,
  middleName: null,
  lastName: null,
  statusMessage: null,
};

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      provideSpecRouter(),
    ],
  });
  return {
    api: TestBed.inject(UsersApiService),
    backend: TestBed.inject(HttpTestingController),
  };
}

describe('UsersApiService', () => {
  it('lists users', () => {
    const { api, backend } = setup();
    api.listUsers().subscribe((users) => expect(users).toEqual([USER]));
    backend.expectOne('/api/users').flush([USER]);
    backend.verify();
  });

  it('creates users with the server DTO shape', () => {
    const { api, backend } = setup();
    const body: CreateUserRequest = { username: 'bob', password: 'pw', email: null };
    api.createUser(body).subscribe((user) => expect(user.username).toBe('bob'));
    const req = backend.expectOne('/api/users');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({ ...USER, username: 'bob' });
    backend.verify();
  });

  it('reads, deletes, and reads profiles', () => {
    const { api, backend } = setup();
    api.getUser('u-1').subscribe((u) => expect(u).toEqual(USER));
    backend.expectOne('/api/users/u-1').flush(USER);

    api.getUserProfile('u-1').subscribe((p) => expect(p).toEqual(PROFILE));
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);

    api.deleteUser('u-1').subscribe();
    const del = backend.expectOne('/api/users/u-1');
    expect(del.request.method).toBe('DELETE');
    del.flush('');
    backend.verify();
  });

  it('patches profile with ADR 0006 field-presence semantics', () => {
    const { api, backend } = setup();
    api.updateProfile('u-1', { displayName: 'Bob', bio: null }).subscribe((p) => expect(p.displayName).toBe('Bob'));
    const req = backend.expectOne('/api/users/u-1/profile');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ displayName: 'Bob', bio: null });
    req.flush({ ...PROFILE, displayName: 'Bob', bio: null });
    backend.verify();
  });

  it('patches email and password through self-only endpoints', () => {
    const { api, backend } = setup();
    api.changeEmail('u-1', { email: 'new@x.com' }).subscribe((u) => expect(u.email).toBe('new@x.com'));
    const emailReq = backend.expectOne('/api/users/u-1/email');
    expect(emailReq.request.method).toBe('PATCH');
    expect(emailReq.request.body).toEqual({ email: 'new@x.com' });
    emailReq.flush({ ...USER, email: 'new@x.com' });

    api.changePassword('u-1', { currentPassword: 'old', newPassword: 'new' }).subscribe((u) => expect(u.userId).toBe('u-1'));
    const pwReq = backend.expectOne('/api/users/u-1/password');
    expect(pwReq.request.method).toBe('PATCH');
    expect(pwReq.request.body).toEqual({ currentPassword: 'old', newPassword: 'new' });
    pwReq.flush(USER);
    backend.verify();
  });
});
