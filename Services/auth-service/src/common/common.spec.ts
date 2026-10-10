import { ArgumentsHost, HttpException, HttpStatus } from '@nestjs/common';
import { Response } from 'express';
import { clearRefreshCookie, refreshCookieOptions, setRefreshCookie } from './cookies';
import { ErrorEnvelopeFilter, invalidInput, rateLimit, unauthorized } from './error.envelope.filter';
import { configuration } from '../config/configuration';

describe('common', () => {
  describe('refresh cookie', () => {
    const config = { name: 'rt', secure: false, sameSite: 'strict' as const };

    it('is HttpOnly on the whole site, and replaces any older cookie', () => {
      expect(refreshCookieOptions(config, 60)).toEqual({ httpOnly: true, secure: false, sameSite: 'strict', path: '/', maxAge: 60000 });
      const res = { cookie: jest.fn(), clearCookie: jest.fn() } as unknown as Response;
      setRefreshCookie(res, 'token', config, 60);
      expect(res.clearCookie).toHaveBeenCalledWith('rt', expect.objectContaining({ path: '/' }));
      expect(res.cookie).toHaveBeenCalledWith('rt', 'token', expect.objectContaining({ maxAge: 60000 }));
      clearRefreshCookie(res, config);
      expect(res.clearCookie).toHaveBeenCalledTimes(2);
    });
  });

  describe('error envelope', () => {
    const filter = new ErrorEnvelopeFilter();

    function render(exception: unknown): { status: number; body: unknown } {
      const out = { status: 0, body: undefined as unknown };
      const res = {
        status(code: number) {
          out.status = code;
          return this;
        },
        json(body: unknown) {
          out.body = body;
        },
      };
      filter.catch(exception, { switchToHttp: () => ({ getResponse: () => res }) } as unknown as ArgumentsHost);
      return out;
    }

    it.each([
      [unauthorized(), 401, { errorCode: 'AUTH-401', message: 'Unauthorised' }],
      [invalidInput('bad'), 422, { errorCode: 'VAL-422', message: 'bad' }],
      [rateLimit(), 429, { errorCode: 'RATE-429', message: 'Too many attempts' }],
      [new HttpException('plain', HttpStatus.NOT_FOUND), 404, { errorCode: 'AUTH-404', message: 'plain' }],
      [new HttpException({ message: ['a', 'b'] }, HttpStatus.BAD_REQUEST), 400, { errorCode: 'SRV-500', message: 'a, b' }],
      [new HttpException({ error: 'Conflict' }, HttpStatus.CONFLICT), 409, { errorCode: 'AUTH-409', message: 'Conflict' }],
      [new HttpException({}, HttpStatus.FORBIDDEN), 403, { errorCode: 'AUTH-403', message: 'Request failed' }],
    ])('renders %s as %i', (exception, status, body) => {
      expect(render(exception)).toEqual({ status, body });
    });

    it.each([new Error('db password in here'), 'a string'])('hides anything unexpected behind SRV-500', (exception) => {
      expect(render(exception)).toEqual({ status: 500, body: { errorCode: 'SRV-500', message: 'Internal server error' } });
    });
  });

  describe('configuration', () => {
    const saved = { ...process.env };
    afterEach(() => {
      process.env = { ...saved };
    });

    it('needs a JWT secret of at least 32 bytes', () => {
      process.env.DB_PASSWORD = 'pw';
      process.env.JWT_SECRET = 'short';
      expect(() => configuration()).toThrow(/JWT_SECRET/);
      delete process.env.JWT_SECRET;
      expect(() => configuration()).toThrow(/JWT_SECRET/);
    });

    it('reads the environment first, then the shared Config/application.yml', () => {
      process.env = { JWT_SECRET: 'x'.repeat(32), AUTH_SERVICE_PORT: '3999', DB_PASSWORD: 'pw',
        CORS_ALLOWED_ORIGINS: 'http://a, ,http://b', COOKIE_SECURE: 'false' };
      const config = configuration();
      expect(config.port).toBe(3999);
      expect(config.corsOrigins).toEqual(['http://a', 'http://b']);
      expect(config.cookie.secure).toBe(false);
      expect(config.cookie.sameSite).toBe('lax');
      // From Application/Config/application.yml, found above the service directory.
      expect(config.jwt.ttlSeconds).toBe(900);
      expect(config.database.host).toBe('localhost');
      expect(config.database.password).toBe('pw');
      expect(config.publicUrl).toBe('http://localhost:3999');
    });

    it('builds the CORS origin from the frontend address when none is given', () => {
      process.env = { JWT_SECRET: 'x'.repeat(32), DB_PASSWORD: 'pw', FRONTEND_URL: 'https://app.example' };
      expect(configuration().corsOrigins).toEqual(['https://app.example']);
      process.env = { JWT_SECRET: 'x'.repeat(32), DB_PASSWORD: 'pw' };
      expect(configuration().corsOrigins).toEqual(['http://localhost:4200']);
    });

    it('stops at start-up when a required value is in neither the environment nor Config', () => {
      process.env = { JWT_SECRET: 'x'.repeat(32) };
      expect(() => configuration()).toThrow(/DB_PASSWORD is not set/);
      process.env = { JWT_SECRET: 'x'.repeat(32), DB_PASSWORD: 'pw', SHARED_CONFIG: 'no-such-file.yml' };
      // SHARED_CONFIG is tried first, then the usual places; point at an empty directory to lose them all.
      const cwd = process.cwd();
      process.chdir(require('os').tmpdir());
      try {
        expect(() => configuration()).toThrow(/AUTH_SERVICE_PORT is not set/);
      } finally {
        process.chdir(cwd);
      }
    });
  });
});
