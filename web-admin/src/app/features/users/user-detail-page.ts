import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toApiError } from '../../core/api/api-error';
import { UserDto, UserProfileDto } from '../../core/api/models';
import { UsersApiService } from '../../core/api/users-api.service';
import { AuthService } from '../../core/auth/auth.service';

/** ADMIN user detail: account record plus read-only profile. */
@Component({
  selector: 'app-user-detail-page',
  imports: [RouterLink],
  templateUrl: './user-detail-page.html',
})
export class UserDetailPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly usersApi = inject(UsersApiService);
  protected readonly auth = inject(AuthService);

  protected readonly loading = signal(true);
  protected readonly user = signal<UserDto | null>(null);
  protected readonly profile = signal<UserProfileDto | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly confirmingDelete = signal(false);
  protected readonly deleting = signal(false);

  protected readonly isSelf = computed(() => {
    const user = this.user();
    const me = this.auth.currentUser();
    return !!user && !!me && user.userId === me.userId;
  });

  constructor() {
    const userId = this.route.snapshot.paramMap.get('id');
    if (!userId) {
      this.error.set('Missing user id.');
      this.loading.set(false);
      return;
    }
    this.usersApi.getUser(userId).subscribe({
      next: (user) => {
        this.user.set(user);
        this.loading.set(false);
        this.usersApi.getUserProfile(userId).subscribe({
          next: (profile) => this.profile.set(profile),
          error: (err: unknown) => {
            // A missing profile is informational, not fatal to the page.
            if (!(err instanceof HttpErrorResponse && err.status === 404)) {
              this.notice.set(toApiError(err).message);
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

  protected askDelete(): void {
    this.notice.set(null);
    this.confirmingDelete.set(true);
  }

  protected cancelDelete(): void {
    if (!this.deleting()) {
      this.confirmingDelete.set(false);
    }
  }

  protected confirmDelete(): void {
    const user = this.user();
    if (!user || this.deleting()) {
      return;
    }
    if (this.isSelf()) {
      this.notice.set('You cannot delete your own administrator account.');
      this.confirmingDelete.set(false);
      return;
    }
    this.deleting.set(true);
    this.usersApi.deleteUser(user.userId).subscribe({
      next: () => {
        this.deleting.set(false);
        void this.router.navigate(['/users']);
      },
      error: (err: unknown) => {
        this.deleting.set(false);
        this.confirmingDelete.set(false);
        this.notice.set(toApiError(err).message);
      },
    });
  }
}
