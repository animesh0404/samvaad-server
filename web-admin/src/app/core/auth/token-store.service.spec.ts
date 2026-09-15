import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { TokenStoreService } from './token-store.service';

describe('TokenStoreService', () => {
  it('starts empty and tracks set/clear', () => {
    TestBed.configureTestingModule({});
    const store = TestBed.inject(TokenStoreService);
    expect(store.hasTokens()).toBe(false);

    store.set({ accessToken: 'a', refreshToken: 'r', sessionId: 's' });
    expect(store.hasTokens()).toBe(true);
    expect(store.session()).toEqual({ accessToken: 'a', refreshToken: 'r', sessionId: 's' });

    store.clear();
    expect(store.hasTokens()).toBe(false);
    expect(store.session()).toBeNull();
  });
});
