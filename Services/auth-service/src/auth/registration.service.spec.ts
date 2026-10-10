import { HttpException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { DbService } from '../db/db.service';
import { OtpService } from './otp.service';
import { MailService } from './mail.service';
import { RegistrationService } from './registration.service';
import { RegisterDto } from './dto/register.dto';
import { RiskAppetite } from '../common/risk-appetite';

function buildConfig(values: Record<string, unknown> = {}): ConfigService {
  const defaults: Record<string, unknown> = {
    'otp.ttlSeconds': 600,
    'otp.maxAttempts': 5,
  };
  return { get: (key: string) => ({ ...defaults, ...values })[key] } as unknown as ConfigService;
}

describe('RegistrationService', () => {
  let db: { query: jest.Mock; getClient: jest.Mock };
  let mail: { sendOtp: jest.Mock; isConfigured: boolean };
  let otp: OtpService;
  let service: RegistrationService;
  let client: { query: jest.Mock; release: jest.Mock };

  function dto(overrides: Partial<RegisterDto> = {}): RegisterDto {
    return Object.assign(new RegisterDto(), {
      firstName: 'Priya',
      lastName: 'Menon',
      username: 'priya.menon',
      email: 'priya.menon@example.com',
      password: 'Capstone@2026',
      confirmPassword: 'Capstone@2026',
      countryCode: 'IN',
      phoneCountryCode: '+91',
      phoneNumber: '9876543210',
      riskAppetite: RiskAppetite.BALANCED,
      ...overrides,
    });
  }

  beforeEach(() => {
    db = { query: jest.fn(), getClient: jest.fn() };
    mail = { sendOtp: jest.fn().mockResolvedValue(true), isConfigured: true };
    otp = new OtpService(buildConfig());
    service = new RegistrationService(db as unknown as DbService, otp, mail as unknown as MailService);

    client = { query: jest.fn().mockResolvedValue({ rows: [], rowCount: 0 }), release: jest.fn() };
    db.getClient.mockResolvedValue(client);
  });

  describe('start', () => {
    it('stores a challenge and emails a code without creating a user', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.users')) return Promise.resolve({ rows: [], rowCount: 0 });
        if (sql.includes('INSERT INTO auth.registration_challenge')) {
          return Promise.resolve({ rows: [{ challenge_id: 11 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      const result = await service.start(dto());

      expect(result.challengeId).toBe(11);
      expect(result.emailDelivered).toBe(true);
      expect(mail.sendOtp).toHaveBeenCalledTimes(1);
      // The whole point: no user row on this path.
      expect(
        db.query.mock.calls.filter(([sql]) => String(sql).includes('INSERT INTO auth.users')),
      ).toHaveLength(0);
      expect(client.query).not.toHaveBeenCalled();
    });

    it('stores the country name and risk appetite from the payload', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('INSERT INTO auth.registration_challenge')) {
          return Promise.resolve({ rows: [{ challenge_id: 1 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      await service.start(dto({ countryCode: 'GB', phoneCountryCode: '+44' }));

      const insert = db.query.mock.calls.find(([sql]) =>
        String(sql).includes('INSERT INTO auth.registration_challenge'),
      );
      const args = insert![1] as unknown[];
      expect(args).toContain('GB');
      expect(args).toContain('United Kingdom');
      expect(args).toContain('+44');
    });

    it('rejects a mismatched confirm password before touching the database', async () => {
      await expect(
        service.start(dto({ confirmPassword: 'Different@11' })),
      ).rejects.toMatchObject({ status: 422, response: { errorCode: 'VAL-422' } });
      expect(db.query).not.toHaveBeenCalled();
    });

    it('rejects a country that is not in the catalogue', async () => {
      await expect(service.start(dto({ countryCode: 'ZZ' }))).rejects.toMatchObject({
        status: 422,
      });
    });

    it('rejects a username or email already in use with AUTH-409', async () => {
      db.query.mockResolvedValueOnce({ rows: [{ '?column?': 1 }], rowCount: 1 });
      await expect(service.start(dto())).rejects.toMatchObject({
        status: 409,
        response: { errorCode: 'AUTH-409' },
      });
      expect(mail.sendOtp).not.toHaveBeenCalled();
    });

    it('reports honestly when SMTP is not configured', async () => {
      mail.sendOtp.mockResolvedValue(false);
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('INSERT INTO auth.registration_challenge')) {
          return Promise.resolve({ rows: [{ challenge_id: 3 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      const result = await service.start(dto());

      expect(result.emailDelivered).toBe(false);
      expect(result.message).toContain('service log');
    });

    it('supersedes an earlier unconsumed challenge for the same address', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('INSERT INTO auth.registration_challenge')) {
          return Promise.resolve({ rows: [{ challenge_id: 5 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      await service.start(dto());

      const supersede = db.query.mock.calls.find(([sql]) =>
        String(sql).includes('SET consumed_on = now()'),
      );
      expect(supersede).toBeDefined();
    });

    it('groups the clash check with OR so a consumed challenge cannot block re-registration', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('INSERT INTO auth.registration_challenge')) {
          return Promise.resolve({ rows: [{ challenge_id: 6 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      await service.start(dto());

      const clash = db.query.mock.calls.find(([sql]) => String(sql).includes('auth.users'));
      expect(clash).toBeDefined();
      // AND binds tighter than OR, so the identity test must be bracketed before
      // it is combined with the challenge-state checks.
      expect(String(clash![0])).toMatch(/\(\s*lower\(username\)\s*=/);
    });

    it('lets an abandoned registration for the same email be started again', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('INSERT INTO auth.registration_challenge')) {
          return Promise.resolve({ rows: [{ challenge_id: 7 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      await service.start(dto());

      const clash = db.query.mock.calls.find(([sql]) => String(sql).includes('auth.users'));
      // A pending challenge only blocks the username when someone else holds it;
      // the same address simply supersedes its earlier attempt.
      expect(String(clash![0])).toContain('lower(email) <> lower($2)');
    });
  });

  describe('verify', () => {
    const validCode = '246813';

    function challengeRow(overrides: Record<string, unknown> = {}) {
      return {
        challenge_id: 11,
        otp_hash: otp.hash(validCode),
        attempts: 0,
        expires_at: new Date(Date.now() + 600_000),
        consumed_on: null,
        ...overrides,
      };
    }

    function wireVerify(row: Record<string, unknown>) {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.registration_challenge') && sql.includes('ORDER BY')) {
          return Promise.resolve({ rows: [row], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });
      client.query.mockImplementation((sql: string) => {
        if (sql.startsWith('BEGIN')) return Promise.resolve({ rows: [], rowCount: 0 });
        if (sql.includes('SET verified_on = now()')) {
          return Promise.resolve({ rows: [], rowCount: 1 });
        }
        if (sql.startsWith('SELECT email, username')) {
          return Promise.resolve({
            rows: [
              {
                email: 'priya.menon@example.com',
                username: 'priya.menon',
                first_name: 'Priya',
                middle_name: null,
                last_name: 'Menon',
                password_hash: 'hash',
                phone_country_code: '+91',
                phone_number: '9876543210',
                country_code: 'IN',
                country: 'India',
                risk_appetite: 'BALANCED',
              },
            ],
            rowCount: 1,
          });
        }
        if (sql.startsWith('INSERT INTO auth.users')) {
          return Promise.resolve({
            rows: [
              {
                user_id: 21,
                uuid: '22222222-2222-2222-2222-222222222222',
                first_name: 'Priya',
                middle_name: null,
                last_name: 'Menon',
                username: 'priya.menon',
                email: 'priya.menon@example.com',
                roles: ['CUSTOMER'],
              },
            ],
            rowCount: 1,
          });
        }
        if (sql.startsWith('INSERT INTO trading.account')) {
          return Promise.resolve({ rows: [{ account_id: 77 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });
    }

    it('creates an INACTIVE user and account in the account currency', async () => {
      wireVerify(challengeRow());

      const result = await service.verify({ email: 'priya.menon@example.com', code: validCode });

      expect(result.user.status).toBe('INACTIVE');
      expect(result.user.accountId).toBe(77);
      expect(client.query).toHaveBeenCalledWith(
        expect.stringContaining('INSERT INTO auth.users'),
        expect.arrayContaining(['INACTIVE']),
      );
      // INR derived from country IN, never from the request body.
      const accountInsert = client.query.mock.calls.find(([sql]) =>
        String(sql).startsWith('INSERT INTO trading.account'),
      );
      expect(accountInsert![1]).toEqual(expect.arrayContaining(['INR', '0.00', 'INACTIVE']));
      expect(result).not.toHaveProperty('accessToken');
      expect(client.query).not.toHaveBeenCalledWith(
        expect.stringContaining('INSERT INTO auth.refresh_token'),
        expect.any(Array),
      );
    });

    it('rejects a wrong code with AUTH-401 and counts the attempt', async () => {
      wireVerify(challengeRow());
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.registration_challenge') && sql.includes('ORDER BY')) {
          return Promise.resolve({ rows: [challengeRow()], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      await expect(
        service.verify({ email: 'priya.menon@example.com', code: '000000' }),
      ).rejects.toMatchObject({ status: 401, response: { errorCode: 'AUTH-401' } });

      const attempt = db.query.mock.calls.find(([sql]) => String(sql).includes('attempts + 1'));
      expect(attempt).toBeDefined();
      expect(client.query).not.toHaveBeenCalled();
    });

    it('refuses once the attempt budget is spent', async () => {
      wireVerify(challengeRow({ attempts: 5 }));
      await expect(
        service.verify({ email: 'priya.menon@example.com', code: validCode }),
      ).rejects.toMatchObject({ status: 429, response: { errorCode: 'RATE-429' } });
    });

    it('refuses an expired code with AUTH-410', async () => {
      wireVerify(challengeRow({ expires_at: new Date(Date.now() - 1000) }));
      await expect(
        service.verify({ email: 'priya.menon@example.com', code: validCode }),
      ).rejects.toMatchObject({ status: 410 });
    });

    it('gives the same answer for an unknown address as for a wrong code', async () => {
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });
      const unknown = await service
        .verify({ email: 'nobody@example.com', code: '000000' })
        .then(() => null, (e) => e as HttpException);
      wireVerify(challengeRow());
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.registration_challenge') && sql.includes('ORDER BY')) {
          return Promise.resolve({ rows: [challengeRow()], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });
      const wrong = await service
        .verify({ email: 'priya.menon@example.com', code: '000000' })
        .then(() => null, (e) => e as HttpException);

      expect(unknown?.getStatus()).toBe(401);
      expect(unknown?.getResponse()).toEqual(wrong?.getResponse());
    });

    it('rolls back and does not create a user when the challenge was already consumed', async () => {
      wireVerify(challengeRow());
      client.query.mockImplementation((sql: string) => {
        if (sql.startsWith('BEGIN')) return Promise.resolve({ rows: [], rowCount: 0 });
        if (sql.includes('SET verified_on = now()')) {
          return Promise.resolve({ rows: [], rowCount: 0 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      await expect(
        service.verify({ email: 'priya.menon@example.com', code: validCode }),
      ).rejects.toMatchObject({ status: 401 });

      expect(client.query).toHaveBeenCalledWith('ROLLBACK');
      expect(client.query).not.toHaveBeenCalledWith(
        expect.stringContaining('INSERT INTO auth.users'),
        expect.any(Array),
      );
    });
  });

  describe('resend', () => {
    it('issues a fresh code for a pending registration', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.registration_challenge')) {
          return Promise.resolve({ rows: [{ challenge_id: 11, first_name: 'Priya' }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      const result = await service.resend('priya.menon@example.com');

      expect(result.emailDelivered).toBe(true);
      const update = db.query.mock.calls.find(([sql]) =>
        String(sql).includes('attempts = 0'),
      );
      expect(update).toBeDefined();
    });

    it('reports AUTH-404 when there is nothing pending for the address', async () => {
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });
      await expect(service.resend('nobody@example.com')).rejects.toMatchObject({
        status: 404,
      });
    });
  });
});