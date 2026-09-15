import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { toApiError } from '../../core/api/api-error';
import { UserDto } from '../../core/api/models';
import { UsersApiService } from '../../core/api/users-api.service';
import { AuthService } from '../../core/auth/auth.service';

/** ADMIN user list with inline delete confirmation and self-delete guard. */
@Component({
  selector: 'app-users-page',
  imports: [RouterLink],
  templateUrl: './users-page.html',
})
export class UsersPage {
  private readonly usersApi = inject(UsersApiService);
  protected readonly auth = inject(AuthService);

  protected readonly loading = signal(true);
  protected readonly users = signal<UserDto[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly pendingDelete = signal<UserDto | null>(null);
  protected readonly deleting = signal(false);
  protected readonly notice = signal<string | null>(null);

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.usersApi.listUsers().subscribe({
      next: (users) => {
        this.users.set(users);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err).message);
        this.loading.set(false);
      },
    });
  }

  protected isSelf(user: UserDto): boolean {
    // The row button is disabled for the own account, and the server
    // rejects self-delete with 403 regardless.
    return user.userId === this.auth.currentUser()?.userId;
  }

  protected askDelete(user: UserDto): void {
    this.notice.set(null);
    this.pendingDelete.set(user);
  }

  protected cancelDelete(): void {
    if (!this.deleting()) {
      this.pendingDelete.set(null);
    }
  }

  protected confirmDelete(): void {
    const user = this.pendingDelete();
    if (!user || this.deleting()) {
      return;
    }
    if (this.isSelf(user)) {
      this.notice.set('You cannot delete your own administrator account.');
      this.pendingDelete.set(null);
      return;
    }
    this.deleting.set(true);
    this.usersApi.deleteUser(user.userId).subscribe({
      next: () => {
        this.deleting.set(false);
        this.pendingDelete.set(null);
        this.notice.set(`Deleted user “${user.username}”.`);
        this.load();
      },
      error: (err: unknown) => {
        this.deleting.set(false);
        this.pendingDelete.set(null);
        this.notice.set(toApiError(err).message);
      },
    });
  }
}
