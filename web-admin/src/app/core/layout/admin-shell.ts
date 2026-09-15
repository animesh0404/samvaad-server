import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../auth/auth.service';

/** Authenticated admin shell: header, nav, and logout. */
@Component({
  selector: 'app-admin-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './admin-shell.html',
})
export class AdminShell {
  protected readonly auth = inject(AuthService);

  protected logout(): void {
    this.auth.logout().subscribe();
  }
}
