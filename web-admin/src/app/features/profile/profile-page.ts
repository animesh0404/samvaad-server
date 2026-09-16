import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { toApiError } from '../../core/api/api-error';
import { UserDto, UserProfileDto, UserProfileUpdatePayload } from '../../core/api/models';
import { UsersApiService } from '../../core/api/users-api.service';
import { AuthService } from '../../core/auth/auth.service';

/**
 * Admin self-management: view account + edit own profile/email/password.
 * All operations are strictly self-scoped — target userId is derived only
 * from the authenticated session (AuthService), never from route params.
 *
 * Profile PATCH preserves ADR 0006 semantics via dirty tracking:
 *  - pristine (untouched) → omitted → unchanged
 *  - dirty + empty      → explicit null → clear
 *  - dirty + non-empty  → trimmed value → replace
 */
@Component({
  selector: 'app-profile-page',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './profile-page.html',
})
export class ProfilePage {
  private readonly auth = inject(AuthService);
  private readonly usersApi = inject(UsersApiService);
  private readonly fb = inject(FormBuilder);

  protected readonly loading = signal(true);
  protected readonly user = signal<UserDto | null>(null);
  protected readonly profile = signal<UserProfileDto | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly profileNotice = signal<string | null>(null);
  protected readonly profileError = signal<string | null>(null);
  protected readonly profileSaving = signal(false);

  protected readonly emailNotice = signal<string | null>(null);
  protected readonly emailError = signal<string | null>(null);
  protected readonly emailSaving = signal(false);

  protected readonly passwordNotice = signal<string | null>(null);
  protected readonly passwordError = signal<string | null>(null);
  protected readonly passwordSaving = signal(false);

  protected readonly authRef = this.auth;

  protected readonly profileForm = this.fb.nonNullable.group({
    displayName: ['', [Validators.maxLength(255)]],
    firstName: ['', [Validators.maxLength(100)]],
    middleName: ['', [Validators.maxLength(100)]],
    lastName: ['', [Validators.maxLength(100)]],
    statusMessage: ['', [Validators.maxLength(255)]],
    avatarUrl: ['', [Validators.maxLength(2048)]],
    bio: ['', [Validators.maxLength(2000)]],
  });

  protected readonly emailForm = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
  });

  protected readonly passwordForm = this.fb.nonNullable.group({
    currentPassword: ['', [Validators.required]],
    newPassword: ['', [Validators.required]],
  });

  constructor() {
    this.loadSelf();
  }

  private loadSelf(): void {
    this.loading.set(true);
    this.error.set(null);

    const current = this.auth.currentUser();
    if (current) {
      this.fetchForUser(current.userId);
      return;
    }
    this.auth.bootstrap().subscribe({
      next: (user) => this.fetchForUser(user.userId),
      error: (err: unknown) => {
        this.error.set(toApiError(err).message);
        this.loading.set(false);
      },
    });
  }

  private fetchForUser(userId: string): void {
    this.usersApi.getUser(userId).subscribe({
      next: (user) => {
        this.user.set(user);
        this.emailForm.controls.email.setValue(user.email ?? '');
        this.emailForm.markAsPristine();
        this.loading.set(false);
        this.usersApi.getUserProfile(userId).subscribe({
          next: (profile) => {
            this.profile.set(profile);
            this.patchProfileForm(profile);
          },
          error: (err: unknown) => {
            if (err instanceof HttpErrorResponse && err.status === 404) {
              // No profile record yet — keep form empty but usable.
              this.profile.set(null);
              this.profileForm.markAsPristine();
            } else {
              this.profileError.set(toApiError(err).message);
            }
          },
        });
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err).message);
        this.loading.set(false);
      },
    });
  }

  private patchProfileForm(profile: UserProfileDto): void {
    this.profileForm.patchValue({
      displayName: profile.displayName ?? '',
      firstName: profile.firstName ?? '',
      middleName: profile.middleName ?? '',
      lastName: profile.lastName ?? '',
      statusMessage: profile.statusMessage ?? '',
      avatarUrl: profile.avatarUrl ?? '',
      bio: profile.bio ?? '',
    });
    this.profileForm.markAsPristine();
  }

  protected submitProfile(): void {
    if (this.profileForm.invalid || this.profileSaving()) {
      this.profileForm.markAllAsTouched();
      return;
    }
    const userId = this.requireSelfId();
    if (!userId) {
      return;
    }
    const payload = this.buildProfilePayload();
    if (Object.keys(payload).length === 0) {
      this.profileNotice.set('No changes to save.');
      this.profileError.set(null);
      return;
    }
    this.profileSaving.set(true);
    this.profileError.set(null);
    this.profileNotice.set(null);
    this.usersApi.updateProfile(userId, payload).subscribe({
      next: (updated) => {
        this.profileSaving.set(false);
        this.profile.set(updated);
        this.patchProfileForm(updated);
        this.profileNotice.set('Profile updated.');
      },
      error: (err: unknown) => {
        this.profileSaving.set(false);
        this.profileError.set(toApiError(err).message);
      },
    });
  }

  protected submitEmail(): void {
    if (this.emailForm.invalid || this.emailSaving()) {
      this.emailForm.markAllAsTouched();
      return;
    }
    const userId = this.requireSelfId();
    if (!userId) {
      return;
    }
    const email = this.emailForm.getRawValue().email.trim();
    this.emailSaving.set(true);
    this.emailError.set(null);
    this.emailNotice.set(null);
    this.usersApi.changeEmail(userId, { email }).subscribe({
      next: (updatedUser) => {
        this.emailSaving.set(false);
        this.user.set(updatedUser);
        this.emailForm.controls.email.setValue(updatedUser.email ?? '');
        this.emailForm.markAsPristine();
        // Keep header badge in sync.
        this.auth.setCurrentUser(updatedUser);
        this.emailNotice.set('Email updated.');
      },
      error: (err: unknown) => {
        this.emailSaving.set(false);
        this.emailError.set(toApiError(err).message);
      },
    });
  }

  protected submitPassword(): void {
    if (this.passwordForm.invalid || this.passwordSaving()) {
      this.passwordForm.markAllAsTouched();
      return;
    }
    const userId = this.requireSelfId();
    if (!userId) {
      return;
    }
    const { currentPassword, newPassword } = this.passwordForm.getRawValue();
    this.passwordSaving.set(true);
    this.passwordError.set(null);
    this.passwordNotice.set(null);
    this.usersApi.changePassword(userId, { currentPassword, newPassword }).subscribe({
      next: () => {
        this.passwordSaving.set(false);
        this.passwordForm.reset({ currentPassword: '', newPassword: '' });
        this.passwordNotice.set('Password updated.');
      },
      error: (err: unknown) => {
        this.passwordSaving.set(false);
        this.passwordError.set(toApiError(err).message);
      },
    });
  }

  private buildProfilePayload(): UserProfileUpdatePayload {
    const payload: UserProfileUpdatePayload = {};
    const controls = this.profileForm.controls;
    (Object.keys(controls) as Array<keyof typeof controls>).forEach((key) => {
      const control = controls[key];
      if (control.pristine) {
        return;
      }
      const raw = control.value as string;
      const trimmed = raw.trim();
      // Dirty + empty → explicit null (clear). Dirty + non-empty → trimmed value.
      (payload as Record<string, string | null>)[key] = trimmed === '' ? null : trimmed;
    });
    return payload;
  }

  private requireSelfId(): string | null {
    const id = this.auth.currentUser()?.userId ?? this.user()?.userId ?? null;
    if (!id) {
      this.error.set('Not authenticated.');
    }
    return id;
  }
}
