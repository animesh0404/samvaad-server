import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';
import { TokenStoreService } from './token-store.service';

/** Requires a stored session (restored before initial navigation); otherwise routes to /login. */
export const authGuard: CanActivateFn = () => {
  const tokens = inject(TokenStoreService);
  const router = inject(Router);
  return tokens.hasTokens() ? true : router.createUrlTree(['/login']);
};

/**
 * UX-level ADMIN gate. The decoded role is a routing hint only: the server
 * re-authorizes every request, so a client bypass gains nothing. Login
 * rejects non-ADMIN sessions before they are established, so the non-ADMIN
 * branches below are unreachable defense-in-depth; they fall back to /login.
 */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const tokens = inject(TokenStoreService);
  const router = inject(Router);

  const known = auth.currentUser();
  if (known) {
    return known.role === 'ADMIN' ? true : router.createUrlTree(['/login']);
  }
  if (!tokens.hasTokens()) {
    return router.createUrlTree(['/login']);
  }
  return auth.bootstrap().pipe(
    map((user) => (user.role === 'ADMIN' ? true : router.createUrlTree(['/login']))),
    catchError(() => of(router.createUrlTree(['/login']))),
  );
};
