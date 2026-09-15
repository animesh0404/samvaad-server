import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { toApiError } from './api-error';

describe('toApiError', () => {
  it('maps the server message envelope', () => {
    const err = new HttpErrorResponse({
      status: 409,
      error: { message: 'Username already exists' },
    });
    expect(toApiError(err)).toEqual({ status: 409, message: 'Username already exists' });
  });

  it('maps unreachable backends to a helpful message', () => {
    const err = new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') });
    expect(toApiError(err).status).toBe(0);
    expect(toApiError(err).message).toContain('Cannot reach the server');
  });

  it('falls back when the body has no message', () => {
    const err = new HttpErrorResponse({ status: 500, error: null });
    expect(toApiError(err).message).toContain('500');
  });

  it('handles non-HTTP failures', () => {
    expect(toApiError(new Error('boom')).status).toBe(-1);
  });
});
