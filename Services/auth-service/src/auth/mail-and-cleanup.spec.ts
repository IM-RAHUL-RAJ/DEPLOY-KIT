import { ConfigService } from '@nestjs/config';
import * as nodemailer from 'nodemailer';
import { MailService } from './mail.service';
import { ExpiryCleanupService } from './expiry-cleanup.service';
import { OtpService } from './otp.service';
import { DbService } from '../db/db.service';

jest.mock('nodemailer', () => ({ createTransport: jest.fn() }));

function config(values: Record<string, unknown>): ConfigService {
  return { get: (key: string) => values[key] } as unknown as ConfigService;
}

const SMTP = { 'smtp.host': 'smtp.test', 'smtp.port': 465, 'smtp.user': 'me', 'smtp.pass': 'pw', 'smtp.from': 'no-reply@test' };
const OTP_MAIL = { to: 'priya@example.com', code: '123456', firstName: 'Priya', expiresInSeconds: 60 };

describe('MailService', () => {
  const sendMail = jest.fn();

  beforeEach(() => {
    sendMail.mockReset();
    (nodemailer.createTransport as jest.Mock).mockReturnValue({ sendMail });
  });

  describe('without SMTP', () => {
    const mail = new MailService(config({ ...SMTP, 'smtp.host': '', 'smtp.user': '' }));

    it('is not configured, creates no transport and reports nothing delivered', async () => {
      mail.onModuleInit();
      expect(mail.isConfigured).toBe(false);
      expect(nodemailer.createTransport).not.toHaveBeenCalled();
      await expect(mail.sendOtp(OTP_MAIL)).resolves.toBe(false);
      await expect(mail.sendPasswordReset({ ...OTP_MAIL, to: 'bad-address', expiresInSeconds: 600 })).resolves.toBe(false);
    });
  });

  describe('with SMTP', () => {
    it('sends the registration and reset codes, with a secure transport on port 465', async () => {
      const mail = new MailService(config(SMTP));
      mail.onModuleInit();
      expect(nodemailer.createTransport).toHaveBeenCalledWith(expect.objectContaining({ secure: true, auth: { user: 'me', pass: 'pw' } }));
      sendMail.mockResolvedValue({});

      await expect(mail.sendOtp(OTP_MAIL)).resolves.toBe(true);
      expect(sendMail.mock.calls[0][0].text).toContain('1 minute.');
      await expect(mail.sendPasswordReset({ ...OTP_MAIL, expiresInSeconds: 600 })).resolves.toBe(true);
      expect(sendMail.mock.calls[1][0].text).toContain('10 minutes.');
    });

    it('reports a delivery failure instead of throwing', async () => {
      const mail = new MailService(config({ ...SMTP, 'smtp.port': 587, 'smtp.user': '' }));
      mail.onModuleInit();
      expect(nodemailer.createTransport).toHaveBeenCalledWith(expect.objectContaining({ secure: false, auth: undefined }));
      sendMail.mockRejectedValueOnce(new Error('refused')).mockRejectedValueOnce('odd');

      await expect(mail.sendOtp(OTP_MAIL)).resolves.toBe(false);
      await expect(mail.sendPasswordReset(OTP_MAIL)).resolves.toBe(false);
    });
  });
});

describe('ExpiryCleanupService', () => {
  const query = jest.fn();
  const service = new ExpiryCleanupService({ query } as unknown as DbService);

  beforeEach(() => query.mockReset());

  it('purges expired challenges and tokens, logging only when something went', async () => {
    query.mockResolvedValueOnce({ rowCount: 2 }).mockResolvedValueOnce({ rowCount: null }).mockResolvedValueOnce({ rowCount: 1 });
    await service.purge();
    query.mockResolvedValue({ rowCount: 0 });
    await service.purge();
    expect(query).toHaveBeenCalledTimes(6);
  });

  it('survives a database failure', async () => {
    query.mockRejectedValueOnce(new Error('down'));
    await expect(service.purge()).resolves.toBeUndefined();
    query.mockRejectedValueOnce('down');
    await expect(service.purge()).resolves.toBeUndefined();
  });

  it('runs hourly from start-up and stops on shutdown', () => {
    jest.useFakeTimers();
    const spy = jest.spyOn(service, 'purge').mockResolvedValue();
    service.onModuleInit();
    jest.advanceTimersByTime(60 * 60 * 1000);
    expect(spy).toHaveBeenCalledTimes(1);
    service.onModuleDestroy();
    jest.advanceTimersByTime(60 * 60 * 1000);
    expect(spy).toHaveBeenCalledTimes(1);
    new ExpiryCleanupService({ query } as unknown as DbService).onModuleDestroy();
    jest.useRealTimers();
    spy.mockRestore();
  });
});

describe('OtpService', () => {
  const otp = new OtpService(config({ 'otp.ttlSeconds': 600, 'otp.maxAttempts': 5 }));

  it('generates six digits and verifies against the stored digest only', () => {
    const code = otp.generate();
    expect(code).toMatch(/^\d{6}$/);
    expect(otp.verify(` ${code} `, otp.hash(code))).toBe(true);
    expect(otp.verify('000000x', otp.hash(code))).toBe(false);
    expect(otp.verify(code, 'abcd')).toBe(false);
    expect([otp.ttl, otp.attempts, otp.maskedForLog]).toEqual([600, 5, '******']);
    otp.debugLog('fine');
  });
});

describe('DbService', () => {
  it('queries through the pool, lends a client and closes the pool', async () => {
    const db = new DbService(config({ 'database.host': 'localhost', 'database.port': 5432 }));
    const pool = { query: jest.fn().mockResolvedValue({ rows: [], rowCount: 0 }), connect: jest.fn().mockResolvedValue('client'), end: jest.fn() };
    (db as unknown as { pool: typeof pool }).pool.end();
    (db as unknown as { pool: typeof pool }).pool = pool;
    await expect(db.query('SELECT 1')).resolves.toEqual({ rows: [], rowCount: 0 });
    await expect(db.getClient()).resolves.toBe('client');
    await db.onModuleDestroy();
    expect(pool.end).toHaveBeenCalled();
  });
});
