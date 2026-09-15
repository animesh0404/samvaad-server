import { Routes } from '@angular/router';
import { AdminShell } from './core/layout/admin-shell';
import { adminGuard, authGuard } from './core/auth/auth.guard';
import { DashboardPage } from './features/dashboard/dashboard-page';
import { LoginPage } from './features/login/login-page';
import { NotFoundPage } from './features/not-found/not-found-page';
import { UserCreatePage } from './features/users/user-create-page';
import { UserDetailPage } from './features/users/user-detail-page';
import { UsersPage } from './features/users/users-page';

export const routes: Routes = [
  { path: 'login', component: LoginPage },
  {
    path: '',
    component: AdminShell,
    canActivate: [authGuard, adminGuard],
    children: [
      { path: '', component: DashboardPage },
      { path: 'users', component: UsersPage },
      { path: 'users/new', component: UserCreatePage },
      { path: 'users/:id', component: UserDetailPage },
    ],
  },
  { path: '**', component: NotFoundPage },
];
