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
});
