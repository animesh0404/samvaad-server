import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { TokenStoreService } from './token-store.service';

/** Marks a request that already went through one refresh-retry. */
const AUTH_RETRIED = new HttpContextToken(() => false);

const LOGIN_URL = '/api/auth/login';
const REFRESH_URL = '/api/auth/refresh';

function isCredentialCall(url: string): boolean {
  return url.includes(LOGIN_URL) || url.includes(REFRESH_URL);
}

/**
 * Attaches the in-memory Bearer token and recovers from expiry with a
 * single-flight refresh + one retry. When refresh fails, memory state is
 * cleared and the user is routed to /login.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const tokens = inject(TokenStoreService);
  const auth = inject(AuthService);

  if (isCredentialCall(req.url)) {
    return next(req);
  }

  const session = tokens.session();
  const outgoing = session
    ? req.clone({ setHeaders: { Authorization: `Bearer ${session.accessToken}` } })
    : req;

  return next(outgoing).pipe(
    catchError((err: unknown) => {
      if (
        err instanceof HttpErrorResponse &&
        err.status === 401 &&
        session &&
        !req.context.get(AUTH_RETRIED)
      ) {
        return auth.refreshAccessToken().pipe(
          switchMap((accessToken) =>
            next(
              req.clone({
                setHeaders: { Authorization: `Bearer ${accessToken}` },
                context: req.context.set(AUTH_RETRIED, true),
              }),
            ),
          ),
          catchError((refreshError: unknown) => {
            auth.clearAndGoLogin();
            return throwError(() => refreshError);
          }),
        );
      }
      return throwError(() => err);
    }),
  );
};
