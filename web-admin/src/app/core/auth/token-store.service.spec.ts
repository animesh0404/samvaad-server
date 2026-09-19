import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { TokenStoreService } from './token-store.service';

const TOKENS = { accessToken: 'a', refreshToken: 'r', sessionId: 's' };

function stored(key: string): string | null {
  return sessionStorage.getItem(key);
}

describe('TokenStoreService', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it('starts empty and tracks set/clear', () => {
    TestBed.configureTestingModule({});
    const store = TestBed.inject(TokenStoreService);
    expect(store.hasTokens()).toBe(false);

    store.set(TOKENS);
    expect(store.hasTokens()).toBe(true);
    expect(store.session()).toEqual(TOKENS);

    store.clear();
    expect(store.hasTokens()).toBe(false);
    expect(store.session()).toBeNull();
  });

  it('persists the complete session to sessionStorage on set', () => {
    TestBed.configureTestingModule({});
    TestBed.inject(TokenStoreService).set(TOKENS);

    expect(stored('samvaad.web-admin.accessToken')).toBe('a');
    expect(stored('samvaad.web-admin.refreshToken')).toBe('r');
    expect(stored('samvaad.web-admin.sessionId')).toBe('s');
  });

  it('restores the persisted session on initialization', () => {
    sessionStorage.setItem('samvaad.web-admin.accessToken', 'a');
    sessionStorage.setItem('samvaad.web-admin.refreshToken', 'r');
    sessionStorage.setItem('samvaad.web-admin.sessionId', 's');

    TestBed.configureTestingModule({});
    const store = TestBed.inject(TokenStoreService);
    expect(store.hasTokens()).toBe(true);
    expect(store.session()).toEqual(TOKENS);
  });

  it('clears both memory and sessionStorage', () => {
    TestBed.configureTestingModule({});
    const store = TestBed.inject(TokenStoreService);
    store.set(TOKENS);
    store.clear();

    expect(store.hasTokens()).toBe(false);
    expect(stored('samvaad.web-admin.accessToken')).toBeNull();
    expect(stored('samvaad.web-admin.refreshToken')).toBeNull();
    expect(stored('samvaad.web-admin.sessionId')).toBeNull();
  });

  it('rejects partial persisted state and removes leftovers', () => {
    sessionStorage.setItem('samvaad.web-admin.accessToken', 'a');

    TestBed.configureTestingModule({});
    const store = TestBed.inject(TokenStoreService);
    expect(store.hasTokens()).toBe(false);
    expect(store.session()).toBeNull();
    expect(stored('samvaad.web-admin.accessToken')).toBeNull();
    expect(stored('samvaad.web-admin.refreshToken')).toBeNull();
    expect(stored('samvaad.web-admin.sessionId')).toBeNull();
  });

  it('rejects blank persisted values without manufacturing a session', () => {
    sessionStorage.setItem('samvaad.web-admin.accessToken', '');
    sessionStorage.setItem('samvaad.web-admin.refreshToken', 'r');
    sessionStorage.setItem('samvaad.web-admin.sessionId', 's');

    TestBed.configureTestingModule({});
    const store = TestBed.inject(TokenStoreService);
    expect(store.hasTokens()).toBe(false);
    expect(store.session()).toBeNull();
  });
});
