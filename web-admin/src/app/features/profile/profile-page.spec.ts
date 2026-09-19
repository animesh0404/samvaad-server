import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideSpecRouter } from '../../testing/spec-router';
import { describe, expect, it } from 'vitest';
import { authInterceptor } from '../../core/auth/auth.interceptor';
import { AuthService } from '../../core/auth/auth.service';
import { TokenStoreService } from '../../core/auth/token-store.service';
import { UserDto, UserProfileDto } from '../../core/api/models';
import { ProfilePage } from './profile-page';

const ADMIN: UserDto = { userId: 'u-1', username: 'admin', email: 'admin@x.com', role: 'ADMIN' };
const PROFILE: UserProfileDto = {
  userId: 'u-1',
  displayName: 'Admin Display',
  bio: 'hello',
  avatarUrl: null,
  firstName: 'Admin',
  middleName: null,
  lastName: 'User',
  statusMessage: 'hi',
};

function fakeJwt(sub: string): string {
  const payload = btoa(JSON.stringify({ sub })).replace(/\+/g, '-').replace(/\//g, '_');
  return `h.${payload}.s`;
}

function setupWithCurrentUser(user: UserDto | null) {
  sessionStorage.clear();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      provideSpecRouter(),
    ],
  });
  const auth = TestBed.inject(AuthService);
  const tokens = TestBed.inject(TokenStoreService);
  const backend = TestBed.inject(HttpTestingController);
  if (user) {
    // Provide a JWT so bootstrap is not needed; set current user directly.
    tokens.set({ accessToken: fakeJwt(user.userId), refreshToken: 'r', sessionId: 's' });
    auth.setCurrentUser(user);
  }
  const fixture: ComponentFixture<ProfilePage> = TestBed.createComponent(ProfilePage);
  return { fixture, page: fixture.componentInstance as unknown as ProfilePage & Record<string, unknown>, backend, auth, tokens };
}

describe('ProfilePage', () => {
  it('loads and shows account fields and profile', () => {
    const { fixture, backend } = setupWithCurrentUser(ADMIN);
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('admin');
    expect(text).toContain('admin@x.com');
    expect(text).toContain('ADMIN');
    // Profile form should be patched with existing values
    const page = fixture.componentInstance as unknown as { profileForm: { controls: Record<string, { value: string }> } };
    expect(page.profileForm.controls['displayName'].value).toBe('Admin Display');
    backend.verify();
  });

  it('bootstraps when no current user is present', () => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideSpecRouter(),
      ],
    });
    const auth = TestBed.inject(AuthService);
    const tokens = TestBed.inject(TokenStoreService);
    tokens.set({ accessToken: fakeJwt('u-1'), refreshToken: 'r', sessionId: 's' });
    const backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ProfilePage);
    // First call is bootstrap's GET /api/users/u-1
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    // Second call is the page's own getUser
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);
    fixture.detectChanges();
    expect(auth.currentUser()?.userId).toBe('u-1');
    backend.verify();
  });

  it('sends only dirty profile fields (omitted → unchanged, empty → null, value → replace)', () => {
    const { fixture, backend } = setupWithCurrentUser(ADMIN);
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);
    fixture.detectChanges();

    const page = fixture.componentInstance as unknown as {
      profileForm: import('@angular/forms').FormGroup;
      submitProfile: () => void;
    };
    // Mark only displayName as dirty with a new value, and bio as dirty+empty (clear)
    page.profileForm.controls['displayName'].setValue('New Name');
    page.profileForm.controls['displayName'].markAsDirty();
    page.profileForm.controls['bio'].setValue('   ');
    page.profileForm.controls['bio'].markAsDirty();
    // firstName remains pristine → should be omitted

    page.submitProfile();
    const req = backend.expectOne('/api/users/u-1/profile');
    expect(req.request.method).toBe('PATCH');
    // bio trimmed empty → null (clear), displayName trimmed → value
    expect(req.request.body).toEqual({ displayName: 'New Name', bio: null });
    // Ensure pristine field is not present
    expect((req.request.body as Record<string, unknown>)['firstName']).toBeUndefined();
    req.flush({ ...PROFILE, displayName: 'New Name', bio: null });
    backend.verify();
  });

  it('shows no-changes notice when nothing is dirty', () => {
    const { fixture, backend } = setupWithCurrentUser(ADMIN);
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);
    fixture.detectChanges();
    const page = fixture.componentInstance as unknown as {
      submitProfile: () => void;
      profileNotice: import('@angular/core').Signal<string | null>;
    };
    page.submitProfile();
    // No HTTP call expected
    backend.expectNone('/api/users/u-1/profile');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No changes to save');
    backend.verify();
  });

  it('validates profile maxLength before submission', () => {
    const { fixture, backend } = setupWithCurrentUser(ADMIN);
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);
    fixture.detectChanges();
    const page = fixture.componentInstance as unknown as {
      profileForm: import('@angular/forms').FormGroup;
      submitProfile: () => void;
    };
    page.profileForm.controls['displayName'].setValue('a'.repeat(300));
    page.profileForm.controls['displayName'].markAsDirty();
    page.profileForm.controls['displayName'].markAsTouched();
    page.submitProfile();
    backend.expectNone('/api/users/u-1/profile');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('At most 255 characters');
    backend.verify();
  });

  it('tolerates missing profile record', () => {
    const { fixture, backend } = setupWithCurrentUser(ADMIN);
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush({ message: 'nope' }, { status: 404, statusText: 'N' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Profile');
    backend.verify();
  });

  it('updates email and refreshes AuthService.currentUser', () => {
    const { fixture, backend, auth } = setupWithCurrentUser(ADMIN);
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);
    fixture.detectChanges();
    const page = fixture.componentInstance as unknown as {
      emailForm: import('@angular/forms').FormGroup;
      submitEmail: () => void;
    };
    page.emailForm.controls['email'].setValue('newadmin@x.com');
    page.submitEmail();
    const req = backend.expectOne('/api/users/u-1/email');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ email: 'newadmin@x.com' });
    const updated = { ...ADMIN, email: 'newadmin@x.com' };
    req.flush(updated);
    fixture.detectChanges();
    expect(auth.currentUser()?.email).toBe('newadmin@x.com');
    expect(fixture.nativeElement.textContent).toContain('Email updated');
    backend.verify();
  });

  it('validates email before submission', () => {
    const { fixture, backend } = setupWithCurrentUser(ADMIN);
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);
    fixture.detectChanges();
    const page = fixture.componentInstance as unknown as {
      emailForm: import('@angular/forms').FormGroup;
      submitEmail: () => void;
    };
    page.emailForm.controls['email'].setValue('not-an-email');
    page.emailForm.controls['email'].markAsTouched();
    page.submitEmail();
    backend.expectNone('/api/users/u-1/email');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Enter a valid email address');
    backend.verify();
  });

  it('changes password via self-only endpoint', () => {
    const { fixture, backend } = setupWithCurrentUser(ADMIN);
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);
    fixture.detectChanges();
    const page = fixture.componentInstance as unknown as {
      passwordForm: import('@angular/forms').FormGroup;
      submitPassword: () => void;
    };
    page.passwordForm.controls['currentPassword'].setValue('old');
    page.passwordForm.controls['newPassword'].setValue('new');
    page.submitPassword();
    const req = backend.expectOne('/api/users/u-1/password');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ currentPassword: 'old', newPassword: 'new' });
    req.flush(ADMIN);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Password updated');
    backend.verify();
  });

  it('shows error when profile patch fails', () => {
    const { fixture, backend } = setupWithCurrentUser(ADMIN);
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);
    fixture.detectChanges();
    const page = fixture.componentInstance as unknown as {
      profileForm: import('@angular/forms').FormGroup;
      submitProfile: () => void;
    };
    page.profileForm.controls['displayName'].setValue('x');
    page.profileForm.controls['displayName'].markAsDirty();
    page.submitProfile();
    backend.expectOne('/api/users/u-1/profile').flush({ message: 'too long' }, { status: 400, statusText: 'B' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('too long');
    backend.verify();
  });

  it('keeps username and role read-only in the DOM', () => {
    const { fixture, backend } = setupWithCurrentUser(ADMIN);
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);
    fixture.detectChanges();
    const html = fixture.nativeElement.innerHTML as string;
    // No input for username/role
    expect(html).not.toContain('formControlName="username"');
    expect(html).not.toContain('formControlName="role"');
    expect(fixture.nativeElement.textContent).toContain('Username and role cannot be changed');
    backend.verify();
  });

  it('always targets the authenticated userId', () => {
    const { fixture, backend } = setupWithCurrentUser(ADMIN);
    backend.expectOne('/api/users/u-1').flush(ADMIN);
    backend.expectOne('/api/users/u-1/profile').flush(PROFILE);
    fixture.detectChanges();
    const page = fixture.componentInstance as unknown as {
      profileForm: import('@angular/forms').FormGroup;
      submitProfile: () => void;
    };
    page.profileForm.controls['displayName'].setValue('x');
    page.profileForm.controls['displayName'].markAsDirty();
    page.submitProfile();
    // Would be /api/users/u-99/profile if it used a route param — ensure it uses u-1
    backend.expectOne('/api/users/u-1/profile').flush({ ...PROFILE, displayName: 'x' });
    backend.verify();
  });
});
