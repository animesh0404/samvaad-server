import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService, NonAdminLoginError } from '../../core/auth/auth.service';
import { toApiError } from '../../core/api/api-error';

/** ADMIN sign-in over the existing POST /api/auth/login contract. */
@Component({
  selector: 'app-login-page',
  imports: [ReactiveFormsModule],
  templateUrl: './login-page.html',
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    identifier: ['', [Validators.required]],
    password: ['', [Validators.required]],
  });
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    if (this.auth.isAuthenticated() && this.auth.isAdmin()) {
      void this.router.navigate(['/']);
    }
  }

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    const { identifier, password } = this.form.getRawValue();
    this.auth.login(identifier.trim(), password).subscribe({
      next: () => {
        this.submitting.set(false);
        // The service only resolves for ADMIN users; anything else arrives
        // as an error below with tokens already cleared.
        void this.router.navigate(['/']);
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.error.set(this.loginMessage(err));
      },
    });
  }

  private loginMessage(err: unknown): string {
    if (err instanceof NonAdminLoginError) {
      return err.message;
    }
    if (err instanceof HttpErrorResponse && err.status === 0) {
      return toApiError(err).message;
    }
    const api = toApiError(err);
    // 401 carries either bad credentials or the five-session limit text;
    // both are server messages safe to display verbatim.
    return api.message;
  }
}
