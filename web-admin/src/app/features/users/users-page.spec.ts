import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideSpecRouter } from '../../testing/spec-router';
import { describe, expect, it } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { authInterceptor } from '../../core/auth/auth.interceptor';
import { UserDto } from '../../core/api/models';
import { UsersPage } from './users-page';

const ME: UserDto = { userId: 'me', username: 'admin', email: null, role: 'ADMIN' };
const ROWS: UserDto[] = [ME, { userId: 'u-2', username: 'bob', email: 'b@x.com', role: 'USER' }];

function jwt(sub: string): string {
  const payload = btoa(JSON.stringify({ sub })).replace(/\+/g, '-').replace(/\//g, '_');
  return `h.${payload}.s`;
}

async function setup() {
  sessionStorage.clear();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      provideSpecRouter(),
    ],
  });
  const auth = TestBed.inject(AuthService);
  const backend = TestBed.inject(HttpTestingController);

  const loggedIn = auth.login('admin', 'pw').toPromise();
  backend.expectOne('/api/auth/login').flush({
    accessToken: jwt('me'),
    refreshToken: 'r',
    expiresIn: 1,
    sessionId: 's',
  });
  backend.expectOne('/api/users/me').flush(ME);
  await loggedIn;

  const fixture: ComponentFixture<UsersPage> = TestBed.createComponent(UsersPage);
  backend.expectOne('/api/users').flush(ROWS);
  fixture.detectChanges();
  return { fixture, page: fixture.componentInstance, backend };
}

describe('UsersPage', () => {
  it('renders one row per user', async () => {
    const { fixture, backend } = await setup();
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(fixture.nativeElement.textContent).toContain('bob');
    backend.verify();
  });

  it('disables delete for the own row', async () => {
    const { fixture, backend } = await setup();
    const buttons = [
      ...fixture.nativeElement.querySelectorAll('tbody button'),
    ] as HTMLButtonElement[];
    expect(buttons[0]?.disabled).toBe(true);
    expect(buttons[0]?.title).toContain('own account');
    expect(buttons[1]?.disabled).toBe(false);
    backend.verify();
  });

  it('confirms then deletes another user and reloads', async () => {
    const { fixture, page, backend } = await setup();
    page['askDelete'](ROWS[1]!);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alertdialog"]')).toBeTruthy();

    page['confirmDelete']();
    backend.expectOne('/api/users/u-2').flush('');
    backend.expectOne('/api/users').flush([ME]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Deleted user');
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(1);
    backend.verify();
  });
});
