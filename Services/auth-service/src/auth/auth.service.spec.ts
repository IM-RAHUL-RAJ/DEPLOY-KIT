import { HttpException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as bcrypt from 'bcryptjs';
import { DbService } from '../db/db.service';
import { TokensService } from '../tokens/tokens.service';
import { AuthService } from './auth.service';
import { LoginThrottleService } from './login-throttle.service';
import { LoginDto } from './dto/login.dto';

describe('AuthService', () => {
  const secret = 'test-secret-that-is-at-least-32-bytes-long-abc';
  let db: {
    query: jest.Mock;
    getClient: jest.Mock;
  };
  let service: AuthService;
  let tokens: TokensService;

  function buildConfig(values: Record<string, unknown> = {}) {
    const defaults: Record<string, unknown> = {
      'database.host': 'localhost',
      'database.port': 5432,
      'database.user': 'postgres',
      'database.password': 'x',
      'database.name': 'trading_system_db',
      'jwt.secret': secret,
      'jwt.issuer': 'auth-service',
      'jwt.ttlSeconds': 900,
      'refresh.ttlSeconds': 604800,
      'throttle.maxAttempts': 5,
      'throttle.windowMs': 900000,
    };
    return {
      get: (key: string) => ({ ...defaults, ...values })[key],
    } as unknown as ConfigService;
  }

  beforeEach(() => {
    db = {
      query: jest.fn(),
      getClient: jest.fn(),
    };
    tokens = new TokensService(buildConfig());
    const throttle = new LoginThrottleService(buildConfig());
    service = new AuthService(
      db as unknown as DbService,
      buildConfig(),
      tokens,
      throttle,
    );
  });

  function activeUser(hash: string) {
    return {
      user_id: 1,
      uuid: '11111111-1111-1111-1111-111111111111',
      first_name: 'Priya',
      middle_name: null,
      last_name: 'Menon',
      username: 'priya.menon',
      email: 'priya.menon@example.com',
      password_hash: hash,
      roles: ['CUSTOMER'],
      status: 'ACTIVE',
      country: 'India',
      risk_appetite: 'BALANCED',
      phone_country_code: '+91',
      phone_number: '9876543210',
    };
  }

  describe('login approval gate', () => {
    async function loginAs(status: string) {
      const hash = await bcrypt.hash('Capstone@2026', 4);
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.users')) {
          return Promise.resolve({ rows: [{ ...activeUser(hash), status }] });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });
      return service.login({
        identifier: 'priya.menon',
        password: 'Capstone@2026',
      } as LoginDto);
    }

    it.each([
      ['INACTIVE', 'awaiting administrator approval'],
      ['SUSPENDED', 'suspended'],
      ['REJECTED', 'rejected'],
    ])('refuses a %s user with AUTH-403 and issues no tokens', async (status, expected) => {
      await expect(loginAs(status)).rejects.toMatchObject({
        status: 403,
        response: { errorCode: 'AUTH-403' },
      });
      expect(db.query).not.toHaveBeenCalledWith(
        expect.stringContaining('INSERT INTO auth.refresh_token'),
        expect.any(Array),
      );
      await expect(loginAs(status)).rejects.toThrow(new RegExp(expected, 'i'));
    });

    it('tells a pending user the truth: correct password, no session yet', async () => {
      const error = await loginAs('INACTIVE').then(
        () => null,
        (e) => e as HttpException,
      );
      expect(error?.getResponse()).toMatchObject({ errorCode: 'AUTH-403' });
      expect(String((error?.getResponse() as { message: string }).message)).toContain(
        'awaiting administrator approval',
      );
    });
  });

  describe('login', () => {
    it('matches the username or email case-insensitively, as registration does', async () => {
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });
      const dto: LoginDto = { identifier: 'Priya.Menon', password: 'x' };
      await service.login(dto).catch(() => undefined);

      const [sql] = db.query.mock.calls[0];
      expect(String(sql)).toContain('lower(username) = lower($1)');
    });

    it('returns identical AUTH-401 for unknown user and wrong password', async () => {
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });
      const unknown = await service
        .login({ identifier: 'nobody', password: 'whatever@12' } as LoginDto)
        .then(
          () => null,
          (e) => e as HttpException,
        );
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });
      const wrongPass = await service
        .login({
          identifier: 'priya.menon',
          password: 'WrongPass@123',
        } as LoginDto)
        .then(
          () => null,
          (e) => e as HttpException,
        );
      expect(unknown?.getStatus()).toBe(401);
      expect(wrongPass?.getStatus()).toBe(401);
      expect(unknown?.getResponse()).toEqual(wrongPass?.getResponse());
    });

    it('issues tokens on valid credentials', async () => {
      const hash = await bcrypt.hash('Capstone@2026', 4);
      const userRow = activeUser(hash);
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.users')) {
          return Promise.resolve({ rows: [userRow] });
        }
        if (sql.includes('FROM trading.account')) {
          return Promise.resolve({ rows: [{ account_id: 42 }] });
        }
        if (sql.startsWith('INSERT INTO auth.refresh_token')) {
          return Promise.resolve({ rows: [] });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      const result = await service.login({
        identifier: 'priya.menon',
        password: 'Capstone@2026',
      } as LoginDto);

      expect(result.accessToken).toBeTruthy();
      expect(result.refreshToken).toBeTruthy();
      expect(result.user.accountId).toBe(42);
      expect(result.user.status).toBe('ACTIVE');
      expect(result.user.country).toBe('India');
    });
  });

  describe('refresh', () => {
    it('rejects an unknown refresh token with AUTH-401', async () => {
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });
      await expect(
        service.refresh({ refreshToken: 'not-a-token' }),
      ).rejects.toMatchObject({ status: 401, response: { errorCode: 'AUTH-401' } });
    });

    it('claims the token in the same statement that checks it is live', async () => {
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });
      await service.refresh({ refreshToken: 'any' }).catch(() => undefined);

      const [sql] = db.query.mock.calls[0];
      expect(String(sql)).toMatch(/UPDATE auth\.refresh_token[\s\S]*revoked_on IS NULL[\s\S]*RETURNING/);
    });

    it('revokes the whole family when an already-rotated token is presented again', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.startsWith('SELECT family_id, revoked_on')) {
          return Promise.resolve({ rows: [{ family_id: 'fam-1', revoked_on: new Date() }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      await expect(service.refresh({ refreshToken: 'replayed' })).rejects.toMatchObject({ status: 401 });

      const revoke = db.query.mock.calls.find(([sql]) => String(sql).includes('WHERE family_id = $1'));
      expect(revoke?.[1]).toEqual(['fam-1']);
    });

    it('rotates a live token and issues a new pair in the same family', async () => {
      const hash = await bcrypt.hash('irrelevant', 4);
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('RETURNING user_id, family_id')) {
          return Promise.resolve({ rows: [{ user_id: 1, family_id: 'fam-2' }], rowCount: 1 });
        }
        if (sql.includes('FROM auth.users')) {
          return Promise.resolve({ rows: [activeUser(hash)], rowCount: 1 });
        }
        if (sql.includes('FROM trading.account')) {
          return Promise.resolve({ rows: [{ account_id: 1 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 1 });
      });

      const result = await service.refresh({ refreshToken: 'live' });

      expect(result.accessToken).toBeTruthy();
      const insert = db.query.mock.calls.find(([sql]) => String(sql).startsWith('INSERT INTO auth.refresh_token'));
      expect(insert?.[1]?.[2]).toBe('fam-2');
    });
  });

  describe('profile maintenance', () => {
    it('updates the risk appetite for the authenticated user', async () => {
      const hash = await bcrypt.hash('Capstone@2026', 4);
      db.query.mockImplementation((sql: string) => {
        if (sql.startsWith('UPDATE auth.users')) {
          return Promise.resolve({
            rows: [{ ...activeUser(hash), risk_appetite: 'AGGRESSIVE' }],
            rowCount: 1,
          });
        }
        if (sql.includes('FROM trading.account')) {
          return Promise.resolve({ rows: [{ account_id: 42 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      const result = await service.updateProfile('11111111-1111-1111-1111-111111111111', {
        riskAppetite: 'AGGRESSIVE' as never,
      });

      expect(result.riskAppetite).toBe('AGGRESSIVE');
    });

    it('rejects a password change when the current password is wrong', async () => {
      const hash = await bcrypt.hash('Capstone@2026', 4);
      db.query.mockResolvedValue({ rows: [activeUser(hash)], rowCount: 1 });

      await expect(
        service.changePassword('11111111-1111-1111-1111-111111111111', {
          currentPassword: 'WrongPass@2026',
          newPassword: 'BetterCapstone@2027',
          confirmPassword: 'BetterCapstone@2027',
        }),
      ).rejects.toMatchObject({ status: 422, response: { errorCode: 'AUTH-422' } });
    });

    it('changes the password and revokes active refresh tokens', async () => {
      const hash = await bcrypt.hash('Capstone@2026', 4);
      db.query.mockImplementation((sql: string, params?: unknown[]) => {
        if (sql.includes('FROM auth.users')) {
          return Promise.resolve({ rows: [activeUser(hash)], rowCount: 1 });
        }
        if (sql.startsWith('UPDATE auth.users SET password_hash')) {
          expect(params?.[1]).toBe(1);
          expect(typeof params?.[0]).toBe('string');
          expect(params?.[0]).not.toBe(hash);
          return Promise.resolve({ rows: [], rowCount: 1 });
        }
        if (sql.startsWith('UPDATE auth.refresh_token SET revoked_on = now()')) {
          expect(params).toEqual([1]);
          return Promise.resolve({ rows: [], rowCount: 2 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      const result = await service.changePassword('11111111-1111-1111-1111-111111111111', {
        currentPassword: 'Capstone@2026',
        newPassword: 'BetterCapstone@2027',
        confirmPassword: 'BetterCapstone@2027',
      });

      expect(result).toEqual({ message: 'Password changed' });
    });
  });

  describe('edge cases', () => {
    const rows = (r: unknown[], rowCount = r.length) => ({ rows: r, rowCount });

    it('refuses a refresh with no token, for a vanished user, and for a user suspended mid-session', async () => {
      await expect(service.refresh({ refreshToken: '' })).rejects.toBeInstanceOf(HttpException);

      db.query.mockResolvedValueOnce(rows([{ user_id: 1, family_id: 'f' }])).mockResolvedValueOnce(rows([]));
      await expect(service.refresh({ refreshToken: 'r' })).rejects.toMatchObject({ status: 401 });

      db.query
        .mockResolvedValueOnce(rows([{ user_id: 1, family_id: 'f' }]))
        .mockResolvedValueOnce(rows([{ ...activeUser('h'), status: 'SUSPENDED' }]))
        .mockResolvedValueOnce(rows([]));
      await expect(service.refresh({ refreshToken: 'r' })).rejects.toMatchObject({ status: 403 });
      expect(db.query).toHaveBeenLastCalledWith(expect.stringContaining('WHERE family_id = $1'), ['f']);
    });

    it('logs out whether or not the token was still live', async () => {
      db.query.mockResolvedValueOnce({ rows: [], rowCount: 1 }).mockResolvedValueOnce({ rows: [], rowCount: null });
      await service.logout('t');
      await service.logout('t');
      expect(db.query).toHaveBeenCalledTimes(2);
    });

    it('reads the profile with its account, and refuses an unknown user', async () => {
      db.query.mockResolvedValueOnce(rows([{ ...activeUser('h'), country: undefined, risk_appetite: undefined }]))
        .mockResolvedValueOnce(rows([]));
      const me = await service.me('u');
      expect(me.accountId).toBeNull();
      expect(me.country).toBeNull();
      expect(me.riskAppetite).toBeNull();

      db.query.mockResolvedValueOnce(rows([]));
      await expect(service.me('nobody')).rejects.toMatchObject({ status: 401 });
    });

    it('refuses a profile update for an unknown user and a password change that does not match its confirmation', async () => {
      db.query.mockResolvedValueOnce({ rows: [], rowCount: 0 });
      await expect(service.updateProfile('u', { riskAppetite: 'LOW' } as never)).rejects.toMatchObject({ status: 401 });
      await expect(
        service.changePassword('u', { currentPassword: 'a', newPassword: 'b', confirmPassword: 'c' } as never),
      ).rejects.toMatchObject({ status: 422 });
    });

    it('explains an unknown account status generically', async () => {
      const hash = await bcrypt.hash('pw', 4);
      db.query.mockResolvedValueOnce(rows([{ ...activeUser(hash), status: 'ODD' }]));
      await expect(service.login({ identifier: 'priya', password: 'pw' } as LoginDto)).rejects.toMatchObject({
        response: { errorCode: 'AUTH-403', message: expect.stringContaining('contact support') },
      });
    });
  });
});
