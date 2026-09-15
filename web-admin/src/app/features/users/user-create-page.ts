import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { toApiError } from '../../core/api/api-error';
import { UsersApiService } from '../../core/api/users-api.service';

/**
 * ADMIN user provisioning over POST /api/users.
 * Mirrors the server CreateUserRequestDto constraints (username 3–32 chars,
 * `[a-zA-Z0-9_]+`; password required; email optional). Created users get the
 * server default role; the backend accepts no role field.
 */
@Component({
  selector: 'app-user-create-page',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './user-create-page.html',
})
export class UserCreatePage {
  private readonly usersApi = inject(UsersApiService);
  private readonly router = inject(Router);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    username: [
      '',
      [
        Validators.required,
        Validators.minLength(3),
        Validators.maxLength(32),
        Validators.pattern(/^[a-zA-Z0-9_]+$/),
      ],
    ],
    password: ['', [Validators.required]],
    email: ['', [Validators.email]],
  });
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    const { username, password, email } = this.form.getRawValue();
    this.usersApi
      .createUser({
        username: username.trim(),
        password,
        email: email.trim() === '' ? null : email.trim(),
      })
      .subscribe({
        next: (user) => {
          this.submitting.set(false);
          void this.router.navigate(['/users', user.userId]);
        },
        error: (err: unknown) => {
          this.submitting.set(false);
          this.error.set(toApiError(err).message);
        },
      });
  }
}
