import { ExecutionContext, HttpException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Request, Response } from 'express';
import { AuthController } from './auth.controller';
import { AuthenticatedRequest, JwtAuthGuard } from './auth.guard';
import { AuthService } from './auth.service';
import { PasswordResetService } from './password-reset.service';
import { RegistrationService } from './registration.service';
import { TokensService } from '../tokens/tokens.service';

const COOKIE = { name: 'refresh_token', secure: true, sameSite: 'lax' as const };

function response(): Response {
  return { cookie: jest.fn(), clearCookie: jest.fn() } as unknown as Response;
}

function request(cookies?: Record<string, unknown>): Request {
  return { cookies } as unknown as Request;
}

describe('AuthController', () => {
  let auth: jest.Mocked<AuthService>;
  let registration: jest.Mocked<RegistrationService>;
  let passwordReset: jest.Mocked<PasswordResetService>;
  let controller: AuthController;
  const session = {
    accessToken: 'access',
    refreshToken: 'refresh',
    tokenType: 'Bearer',
    expiresIn: 900,
    user: { uuid: 'u-1' },
  } as never;

  beforeEach(() => {
    auth = {
      login: jest.fn().mockResolvedValue(session),
      refresh: jest.fn().mockResolvedValue(session),
      logout: jest.fn().mockResolvedValue(undefined),
      me: jest.fn().mockResolvedValue({ uuid: 'u-1' }),
      updateProfile: jest.fn().mockResolvedValue({ uuid: 'u-1' }),
      changePassword: jest.fn().mockResolvedValue({ message: 'ok' }),
    } as unknown as jest.Mocked<AuthService>;
    registration = {
      start: jest.fn().mockResolvedValue({ message: 'sent' }),
      verify: jest.fn().mockResolvedValue({ message: 'created' }),
      resend: jest.fn().mockResolvedValue({ message: 'again', emailDelivered: false, expiresInSeconds: 600 }),
    } as unknown as jest.Mocked<RegistrationService>;
    passwordReset = {
      request: jest.fn().mockResolvedValue({ message: 'code sent' }),
      reset: jest.fn().mockResolvedValue({ message: 'changed' }),
    } as unknown as jest.Mocked<PasswordResetService>;
    const config = {
      get: (key: string) => (key === 'cookie' ? COOKIE : 604800),
    } as unknown as ConfigService;
    controller = new AuthController(auth, registration, passwordReset, config);
  });

  describe('registration and password reset delegate to their services', () => {
    it('starts, verifies and resends a registration', async () => {
      await expect(controller.register({} as never)).resolves.toEqual({ message: 'sent' });
      await expect(controller.verifyRegistration({} as never)).resolves.toEqual({ message: 'created' });
      await expect(controller.resendCode({ email: 'a@b.co' })).resolves.toMatchObject({ message: 'again' });
      expect(registration.resend).toHaveBeenCalledWith('a@b.co');
    });

    it('requests a reset code and resets the password', async () => {
      await expect(controller.requestPasswordReset({ email: 'a@b.co' })).resolves.toEqual({ message: 'code sent' });
      await expect(controller.resetPassword({} as never)).resolves.toEqual({ message: 'changed' });
    });
  });

  describe('session', () => {
    it('logs in, sets the refresh cookie and never returns the refresh token in the body', async () => {
      const res = response();
      const body = await controller.login({ identifier: 'demo', password: 'x' } as never, res);
      expect(body).not.toHaveProperty('refreshToken');
      expect(res.cookie).toHaveBeenCalledWith('refresh_token', 'refresh', expect.objectContaining({ httpOnly: true, maxAge: 604800000 }));
    });

    it('refreshes from the cookie first, then the body, and refuses with neither', async () => {
      await controller.refresh(request({ refresh_token: 'from-cookie' }), {}, response());
      expect(auth.refresh).toHaveBeenLastCalledWith({ refreshToken: 'from-cookie' });
      await controller.refresh(request(), { refreshToken: 'from-body' }, response());
      expect(auth.refresh).toHaveBeenLastCalledWith({ refreshToken: 'from-body' });
      await expect(controller.refresh(request({ refresh_token: '' }), { refreshToken: '' }, response()))
        .rejects.toBeInstanceOf(HttpException);
    });

    it('logs out, revoking a token when there is one, and always clears the cookie', async () => {
      const res = response();
      await expect(controller.logout(request({ refresh_token: 't' }), {}, res)).resolves.toEqual({ message: 'Logged out' });
      expect(auth.logout).toHaveBeenCalledWith('t');
      auth.logout.mockClear();
      await controller.logout(request(), {}, res);
      expect(auth.logout).not.toHaveBeenCalled();
      expect(res.clearCookie).toHaveBeenCalledTimes(2);
    });
  });

  describe('the signed-in user', () => {
    const req = { authUser: { uuid: 'u-1', accountId: 1, roles: [] } } as unknown as AuthenticatedRequest;

    it('reads and updates the profile and changes the password for the token user', async () => {
      await controller.me(req);
      await controller.updateProfile(req, { riskAppetite: 'LOW' } as never);
      await controller.changePassword(req, {} as never);
      expect(auth.me).toHaveBeenCalledWith('u-1');
      expect(auth.updateProfile).toHaveBeenCalledWith('u-1', { riskAppetite: 'LOW' });
      expect(auth.changePassword).toHaveBeenCalledWith('u-1', {});
    });
  });
});

describe('JwtAuthGuard', () => {
  const tokens = { verifyAccessToken: jest.fn() } as unknown as jest.Mocked<TokensService>;
  const guard = new JwtAuthGuard(tokens);

  function context(authorization?: string): { ctx: ExecutionContext; req: AuthenticatedRequest } {
    const req = { headers: { authorization } } as unknown as AuthenticatedRequest;
    const ctx = { switchToHttp: () => ({ getRequest: () => req }) } as unknown as ExecutionContext;
    return { ctx, req };
  }

  it('accepts a valid bearer token and attaches the user', () => {
    tokens.verifyAccessToken.mockReturnValue({ sub: 'u-1', accountId: 7, roles: ['CUSTOMER'] } as never);
    const { ctx, req } = context('Bearer good');
    expect(guard.canActivate(ctx)).toBe(true);
    expect(req.authUser).toEqual({ uuid: 'u-1', accountId: 7, roles: ['CUSTOMER'] });
  });

  it('keeps an account id only when it is an integer', () => {
    tokens.verifyAccessToken.mockReturnValue({ sub: 'u-2', accountId: '7', roles: [] } as never);
    const { ctx, req } = context('Bearer good');
    guard.canActivate(ctx);
    expect(req.authUser?.accountId).toBeNull();
  });

  it.each([undefined, 'Basic abc'])('refuses a missing or non-bearer header (%s)', (header) => {
    expect(() => guard.canActivate(context(header).ctx)).toThrow(HttpException);
  });

  it('refuses a token that does not verify', () => {
    tokens.verifyAccessToken.mockImplementation(() => {
      throw new Error('expired');
    });
    expect(() => guard.canActivate(context('Bearer bad').ctx)).toThrow(HttpException);
  });
});
