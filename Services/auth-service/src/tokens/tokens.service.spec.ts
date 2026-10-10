import { ConfigService } from '@nestjs/config';
import { TokensService } from './tokens.service';

describe('TokensService', () => {
  let service: TokensService;

  beforeEach(() => {
    const config = {
      get: (key: string) => {
        const values: Record<string, unknown> = {
          'jwt.secret': 'test-secret-that-is-at-least-32-bytes-long-abc',
          'jwt.issuer': 'auth-service',
          'jwt.ttlSeconds': 900,
        };
        return values[key];
      },
    } as unknown as ConfigService;
    service = new TokensService(config);
  });

  it('signs an HS256 token with issuer, subject and roles claims', () => {
    const { accessToken } = service.signAccessToken({
      uuid: '11111111-1111-1111-1111-111111111111',
      accountId: 42,
      roles: ['CUSTOMER'],
    });
    expect(accessToken).toBeTruthy();

    const payload = service.verifyAccessToken(accessToken);
    expect(payload.sub).toBe('11111111-1111-1111-1111-111111111111');
    expect(payload.roles).toEqual(['CUSTOMER']);
    expect(payload.accountId).toBe(42);
    expect(payload.iss).toBe('auth-service');
    expect(payload.alg).toBeUndefined();
  });

  it('rejects a token signed by a different issuer', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const jwt = require('jsonwebtoken');
    const foreign = jwt.sign(
      { accountId: 1, roles: ['CUSTOMER'] },
      'test-secret-that-is-at-least-32-bytes-long-abc',
      {
        algorithm: 'HS256',
        issuer: 'evil-issuer',
        subject: '11111111-1111-1111-1111-111111111111',
        expiresIn: 900,
      },
    );
    expect(() => service.verifyAccessToken(foreign)).toThrow();
  });

  it('rejects a token without roles claim', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const jwt = require('jsonwebtoken');
    const noRoles = jwt.sign(
      { accountId: 1 },
      'test-secret-that-is-at-least-32-bytes-long-abc',
      {
        algorithm: 'HS256',
        issuer: 'auth-service',
        subject: '11111111-1111-1111-1111-111111111111',
        expiresIn: 900,
      },
    );
    expect(() => service.verifyAccessToken(noRoles)).toThrow(/roles/);
  });

  it('generates a unique refresh token and a stable sha256 digest', () => {
    const a = service.generateRefreshToken();
    const b = service.generateRefreshToken();
    expect(a).not.toBe(b);
    expect(service.hashRefreshToken(a)).toBe(service.hashRefreshToken(a));
    expect(service.hashRefreshToken(a)).not.toBe(a);
  });
});