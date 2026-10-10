import { HttpException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { DbService } from '../db/db.service';
import { OtpService } from './otp.service';
import { PasswordResetService } from './password-reset.service';
import { MailService } from './mail.service';
import { RequestPasswordResetDto } from './dto/request-password-reset.dto';
import { ResetPasswordDto } from './dto/reset-password.dto';

function buildConfig(values: Record<string, unknown> = {}): ConfigService {
  const defaults: Record<string, unknown> = {
    'otp.ttlSeconds': 600,
    'otp.maxAttempts': 5,
  };
  return { get: (key: string) => ({ ...defaults, ...values })[key] } as unknown as ConfigService;
}

describe('PasswordResetService', () => {
  let db: { query: jest.Mock; getClient: jest.Mock };
  let mail: { sendPasswordReset: jest.Mock; isConfigured: boolean };
  let otp: OtpService;
  let service: PasswordResetService;
  let client: { query: jest.Mock; release: jest.Mock };

  const email = 'priya.menon@example.com';

  function requestDto(overrides: Partial<RequestPasswordResetDto> = {}): RequestPasswordResetDto {
    return Object.assign(new RequestPasswordResetDto(), { email, ...overrides });
  }

  function resetDto(overrides: Partial<ResetPasswordDto> = {}): ResetPasswordDto {
    return Object.assign(new ResetPasswordDto(), {
      email,
      code: '246813',
      password: 'Capstone@2026',
      confirmPassword: 'Capstone@2026',
      ...overrides,
    });
  }

  beforeEach(() => {
    db = { query: jest.fn(), getClient: jest.fn() };
    mail = { sendPasswordReset: jest.fn().mockResolvedValue(true), isConfigured: true };
    otp = new OtpService(buildConfig());
    service = new PasswordResetService(db as unknown as DbService, otp, mail as unknown as MailService);

    client = { query: jest.fn().mockResolvedValue({ rows: [], rowCount: 0 }), release: jest.fn() };
    db.getClient.mockResolvedValue(client);
  });

  describe('request', () => {
    it('stores a challenge and emails a code for an ACTIVE account', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.users')) {
          return Promise.resolve({
            rows: [{ user_id: 21, first_name: 'Priya' }],
            rowCount: 1,
          });
        }
        if (sql.includes('INSERT INTO auth.password_reset_challenge')) {
          return Promise.resolve({ rows: [{ challenge_id: 9 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      const result = await service.request(requestDto());

      expect(result.emailDelivered).toBe(true);
      expect(result.message).toContain('A password reset code has been sent');
      expect(mail.sendPasswordReset).toHaveBeenCalledTimes(1);
      expect(mail.sendPasswordReset.mock.calls[0][0]).toMatchObject({
        to: email,
        firstName: 'Priya',
        expiresInSeconds: 600,
      });
      const insert = db.query.mock.calls.find(([sql]) =>
        String(sql).includes('INSERT INTO auth.password_reset_challenge'),
      );
      expect(insert).toBeDefined();
    });

    it('gives the same answer when no account exists, without writing anything', async () => {
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });

      const result = await service.request(requestDto({ email: 'nobody@example.com' }));

      expect(result.emailDelivered).toBe(false);
      expect(result.message).toBe('If an account exists for that address, a reset code has been sent.');
      expect(
        db.query.mock.calls.filter(([sql]) =>
          String(sql).includes('auth.password_reset_challenge'),
        ),
      ).toHaveLength(0);
      expect(mail.sendPasswordReset).not.toHaveBeenCalled();
    });

    it('never issues a code for an account that is not ACTIVE', async () => {
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });

      const result = await service.request(requestDto());

      expect(result.emailDelivered).toBe(false);
      expect(mail.sendPasswordReset).not.toHaveBeenCalled();
      const lookup = db.query.mock.calls.find(([sql]) => String(sql).includes('FROM auth.users'));
      expect(String(lookup![0])).toContain("status = $2");
    });

    it('supersedes an earlier unconsumed challenge before storing the new one', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.users')) {
          return Promise.resolve({ rows: [{ user_id: 21, first_name: 'Priya' }], rowCount: 1 });
        }
        if (sql.includes('INSERT INTO auth.password_reset_challenge')) {
          return Promise.resolve({ rows: [{ challenge_id: 10 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      await service.request(requestDto());

      const supersede = db.query.mock.calls.find(([sql]) =>
        String(sql).includes('SET consumed_on = now()'),
      );
      expect(supersede).toBeDefined();
      expect(supersede![1]).toEqual([21]);
    });

    it('reports honestly when SMTP is not configured', async () => {
      mail.sendPasswordReset.mockResolvedValue(false);
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.users')) {
          return Promise.resolve({ rows: [{ user_id: 21, first_name: 'Priya' }], rowCount: 1 });
        }
        if (sql.includes('INSERT INTO auth.password_reset_challenge')) {
          return Promise.resolve({ rows: [{ challenge_id: 11 }], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      const result = await service.request(requestDto());

      expect(result.emailDelivered).toBe(false);
      expect(result.message).toContain('service log');
    });
  });

  describe('reset', () => {
    const validCode = '246813';

    function challengeRow(): unknown {
      return {
        challenge_id: 9,
        user_id: 21,
        otp_hash: otp.hash(validCode),
        attempts: 0,
        expires_at: new Date(Date.now() + 600_000),
        consumed_on: null,
      };
    }

    function wireReset() {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.password_reset_challenge') && sql.includes('ORDER BY')) {
          return Promise.resolve({ rows: [challengeRow()], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });
      client.query.mockImplementation((sql: string | { startsWith: (p: string) => boolean }) => {
        const text = String(sql);
        if (text.startsWith('BEGIN')) return Promise.resolve({ rows: [], rowCount: 0 });
        if (text.includes('SET consumed_on = now()')) {
          return Promise.resolve({ rows: [], rowCount: 1 });
        }
        if (text.includes('UPDATE auth.users SET password_hash')) {
          return Promise.resolve({ rows: [], rowCount: 1 });
        }
        if (text.includes('UPDATE auth.refresh_token')) {
          return Promise.resolve({ rows: [], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });
    }

    it('changes the password, consumes the code and revokes every session in one transaction', async () => {
      wireReset();

      const result = await service.reset(resetDto());

      expect(result.message).toContain('Sign in with your new password');
      expect(client.query).toHaveBeenCalledWith('BEGIN');
      expect(client.query).toHaveBeenCalledWith(expect.stringContaining('SET consumed_on = now()'), [9]);
      expect(client.query).toHaveBeenCalledWith(
        expect.stringContaining('UPDATE auth.users SET password_hash'),
        expect.arrayContaining([expect.any(String), 21]),
      );
      expect(client.query).toHaveBeenCalledWith(
        expect.stringContaining('UPDATE auth.refresh_token'),
        [21],
      );
      expect(client.query).toHaveBeenCalledWith('COMMIT');
      expect(client.release).toHaveBeenCalled();
    });

    it('rejects a mismatched confirm password before touching the database', async () => {
      await expect(
        service.reset(resetDto({ confirmPassword: 'Different@11' })),
      ).rejects.toMatchObject({ status: 422, response: { errorCode: 'VAL-422' } });
      expect(db.query).not.toHaveBeenCalled();
    });

    it('rejects a wrong code with AUTH-401 and counts the attempt', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.password_reset_challenge') && sql.includes('ORDER BY')) {
          return Promise.resolve({ rows: [challengeRow()], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      await expect(
        service.reset(resetDto({ code: '000000' })),
      ).rejects.toMatchObject({ status: 401, response: { errorCode: 'AUTH-401' } });

      const attempt = db.query.mock.calls.find(([sql]) => String(sql).includes('attempts + 1'));
      expect(attempt).toBeDefined();
      expect(client.query).not.toHaveBeenCalled();
    });

    it('refuses once the attempt budget is spent', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.password_reset_challenge') && sql.includes('ORDER BY')) {
          return Promise.resolve({
            rows: [{ ...(challengeRow() as object), attempts: 5 }],
            rowCount: 1,
          });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      await expect(service.reset(resetDto())).rejects.toMatchObject({
        status: 429,
        response: { errorCode: 'RATE-429' },
      });
    });

    it('refuses an expired code with AUTH-410', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.password_reset_challenge') && sql.includes('ORDER BY')) {
          return Promise.resolve({
            rows: [{ ...(challengeRow() as object), expires_at: new Date(Date.now() - 1000) }],
            rowCount: 1,
          });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      await expect(service.reset(resetDto())).rejects.toMatchObject({ status: 410 });
    });

    it('gives the same answer for an unknown address as for a wrong code', async () => {
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });
      const unknown = await service
        .reset(resetDto({ email: 'nobody@example.com' }))
        .then(() => null, (e) => e as HttpException);
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.password_reset_challenge') && sql.includes('ORDER BY')) {
          return Promise.resolve({ rows: [challengeRow()], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });
      const wrong = await service
        .reset(resetDto({ code: '000000' }))
        .then(() => null, (e) => e as HttpException);

      expect(unknown?.getStatus()).toBe(401);
      expect(unknown?.getResponse()).toEqual(wrong?.getResponse());
    });

    it('rolls back when the challenge was already consumed, so a replay cannot reset again', async () => {
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.password_reset_challenge') && sql.includes('ORDER BY')) {
          return Promise.resolve({ rows: [challengeRow()], rowCount: 1 });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });
      client.query.mockImplementation(() => Promise.resolve({ rows: [], rowCount: 0 }));

      await expect(service.reset(resetDto())).rejects.toMatchObject({ status: 401 });

      expect(client.query).toHaveBeenCalledWith('ROLLBACK');
      expect(
        client.query.mock.calls.filter(([sql]) => String(sql).includes('UPDATE auth.users')),
      ).toHaveLength(0);
    });
  });
});