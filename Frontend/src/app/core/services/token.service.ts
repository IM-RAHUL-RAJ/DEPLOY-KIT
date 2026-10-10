import { Injectable } from '@angular/core';

const ACCESS_TOKEN_KEY = 'lb_access_token';
const USER_KEY = 'lb_user';

/** The claims the platform puts in an access token (auth-api.yaml, JWT claims). */
export interface AccessTokenClaims {
  sub?: string;
  accountId?: number | null;
  roles?: string[];
  exp?: number;
  iat?: number;
  iss?: string;
}

/**
 * Decodes a JWT payload. JWTs are base64url (RFC 7515): '-' and '_' instead of
 * '+' and '/', and no padding. atob() only accepts standard base64, so a payload
 * that happens to contain either character would fail to decode and the user
 * would look signed out straight after signing in.
 *
 * This reads claims for display and routing only. It does not verify the
 * signature: the services do that on every request.
 */
export function decodeJwtPayload(token: string): AccessTokenClaims | null {
  const part = token.split('.')[1];
  if (!part) return null;
  try {
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
    const binary = atob(padded);
    const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as AccessTokenClaims;
  } catch {
    return null;
  }
}

@Injectable({ providedIn: 'root' })
export class TokenService {
  getAccessToken(): string | null {
    return localStorage.getItem(ACCESS_TOKEN_KEY);
  }

  setAccessToken(accessToken: string): void {
    localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  }

  setUser(user: object): void {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  }

  getUser<T>(): T | null {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as T;
    } catch {
      return null;
    }
  }

  private claims(): AccessTokenClaims | null {
    const token = this.getAccessToken();
    return token ? decodeJwtPayload(token) : null;
  }

  isAuthenticated(): boolean {
    const exp = this.claims()?.exp;
    return typeof exp === 'number' && exp * 1000 > Date.now();
  }

  /**
   * The account this session may trade. The contract types the claim as an
   * integer; a numeric string from an older token is accepted too, anything else
   * is no account.
   */
  getAccountId(): number | null {
    const accountId: unknown = this.claims()?.accountId;
    if (typeof accountId === 'number' && Number.isSafeInteger(accountId)) return accountId;
    if (typeof accountId === 'string' && /^\d+$/.test(accountId)) return Number(accountId);
    return null;
  }

  getUsername(): string | null {
    const user = this.getUser<{ username: string }>();
    return user?.username ?? null;
  }

  /** Roles come from the signed token, not from the editable user copy in storage. */
  getUserRoles(): string[] {
    return this.claims()?.roles ?? [];
  }

  isAdmin(): boolean {
    return this.getUserRoles().includes('ADMIN');
  }

  clear(): void {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    // Left behind by earlier builds that stored the refresh token in JS.
    localStorage.removeItem('lb_refresh_token');
  }
}
