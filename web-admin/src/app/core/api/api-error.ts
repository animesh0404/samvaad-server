import { HttpErrorResponse } from '@angular/common/http';

export interface ApiError {
  status: number;
  message: string;
}

/**
 * Maps backend failures to a displayable shape. The server envelope is
 * `{"message": "..."}`; anything else (network failure, empty body)
 * degrades to a generic message. Never throws.
 */
export function toApiError(error: unknown): ApiError {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return { status: 0, message: 'Cannot reach the server. Is the backend running?' };
    }
    const body = error.error as { message?: unknown } | null | undefined;
    const message =
      typeof body?.message === 'string' && body.message.length > 0
        ? body.message
        : `Request failed (HTTP ${error.status})`;
    return { status: error.status, message };
  }
  return { status: -1, message: 'An unexpected error occurred.' };
}
