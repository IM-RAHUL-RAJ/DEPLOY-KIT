import { ConfigService } from '@nestjs/config';
import { HttpException } from '@nestjs/common';
import { LoginThrottleService } from './login-throttle.service';

describe('LoginThrottleService', () => {
  function buildThrottle(maxAttempts = 5, windowMs = 900000): LoginThrottleService {
    const config = {
      get: (key: string) =>
        ({
          'throttle.maxAttempts': maxAttempts,
          'throttle.windowMs': windowMs,
        })[key],
    } as unknown as ConfigService;
    return new LoginThrottleService(config);
  }

  it('allows attempts below the limit', () => {
    const throttle = buildThrottle(3);
    throttle.recordFailure('user@example.com');
    throttle.recordFailure('user@example.com');
    expect(() => throttle.check('user@example.com')).not.toThrow();
  });

  it('rejects attempts once the limit is exceeded', () => {
    const throttle = buildThrottle(2, 60000);
    throttle.recordFailure('user');
    throttle.recordFailure('user');
    expect(() => throttle.check('user')).toThrow(HttpException);
    try {
      throttle.check('user');
    } catch (error) {
      const httpError = error as HttpException;
      expect(httpError.getStatus()).toBe(429);
      expect(httpError.getResponse()).toMatchObject({
        errorCode: 'RATE-429',
      });
    }
  });

  it('is case-insensitive on the identifier', () => {
    const throttle = buildThrottle(2, 60000);
    throttle.recordFailure('User');
    throttle.recordFailure('USER');
    expect(() => throttle.check('uSeR')).toThrow(HttpException);
  });

  it('resets the failure count after a successful login', () => {
    const throttle = buildThrottle(1, 60000);
    throttle.recordFailure('ok@example.com');
    expect(() => throttle.check('ok@example.com')).toThrow(HttpException);
    throttle.reset('ok@example.com');
    expect(() => throttle.check('ok@example.com')).not.toThrow();
  });
});