import { TokenService, decodeJwtPayload } from './token.service';

/** Builds an unsigned JWT-shaped string whose payload is base64url, as a real token's is. */
function tokenWith(claims: object): string {
  const base64url = (value: string) =>
    btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${base64url('{"alg":"HS256"}')}.${base64url(JSON.stringify(claims))}.signature`;
}

describe('TokenService', () => {
  let service: TokenService;

  beforeEach(() => {
    localStorage.clear();
    service = new TokenService();
  });

  afterEach(() => localStorage.clear());

  it('decodes a payload that uses the base64url alphabet', () => {
    // '?>?' encodes to 'Pz4_' in base64url and 'Pz4/' in standard base64.
    const token = tokenWith({ sub: '?>?', accountId: 7 });
    expect(token.split('.')[1]).toMatch(/[-_]/);
    expect(decodeJwtPayload(token)).toEqual(jasmine.objectContaining({ sub: '?>?', accountId: 7 }));
  });

  it('treats an unexpired token as authenticated and an expired one as not', () => {
    const now = Math.floor(Date.now() / 1000);
    service.setAccessToken(tokenWith({ exp: now + 60 }));
    expect(service.isAuthenticated()).toBeTrue();

    service.setAccessToken(tokenWith({ exp: now - 60 }));
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('treats a malformed token as signed out rather than throwing', () => {
    service.setAccessToken('not-a-jwt');
    expect(service.isAuthenticated()).toBeFalse();
    expect(service.getAccountId()).toBeNull();
  });

  it('reads an account id signed as a numeric string', () => {
    service.setAccessToken(tokenWith({ accountId: '1' }));
    expect(service.getAccountId()).toBe(1);

    service.setAccessToken(tokenWith({ accountId: 'abc' }));
    expect(service.getAccountId()).toBeNull();
  });

  it('reads the account and roles from the token, not from the stored user copy', () => {
    service.setAccessToken(tokenWith({ accountId: 3, roles: ['CUSTOMER'] }));
    service.setUser({ roles: ['ADMIN'] });

    expect(service.getAccountId()).toBe(3);
    expect(service.isAdmin()).toBeFalse();
  });
});
