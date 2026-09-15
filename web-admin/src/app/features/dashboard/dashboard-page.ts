import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { toApiError } from '../../core/api/api-error';
import { UsersApiService } from '../../core/api/users-api.service';

/** Admin home: user count plus entry points into user administration. */
@Component({
  selector: 'app-dashboard-page',
  imports: [RouterLink],
  templateUrl: './dashboard-page.html',
})
export class DashboardPage {
  private readonly usersApi = inject(UsersApiService);

  protected readonly loading = signal(true);
  protected readonly userCount = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.usersApi.listUsers().subscribe({
      next: (users) => {
        this.userCount.set(users.length);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err).message);
        this.loading.set(false);
      },
    });
  }
}
