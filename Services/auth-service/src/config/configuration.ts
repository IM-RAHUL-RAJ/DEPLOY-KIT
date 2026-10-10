import { loadSharedConfig } from './shared-config';

/**
 * The signing secret is shared with the Trade REST API and has no default: a
 * service that started with one baked into the source would issue tokens anyone
 * reading the repository could forge. HS256 needs at least 32 bytes.
 */
function jwtSecret(): string {
  const secret = process.env.JWT_SECRET ?? '';
  if (Buffer.byteLength(secret, 'utf8') < 32) {
    throw new Error('JWT_SECRET must be set to at least 32 bytes, and must match the Trade REST API.');
  }
  return secret;
}

/**
 * The auth service's settings. Each value comes from an environment variable
 * (Application/.env) or else from the shared Config/application.yml; nothing
 * environment-specific is written here. A required value found in neither stops the
 * service at start-up with the name of what is missing, rather than starting against
 * a guessed host.
 */
export const configuration = () => {
  const shared = loadSharedConfig();

  const required = (env: string, sharedKey: string | null): string => {
    const value = process.env[env] || (sharedKey ? shared[sharedKey] : undefined);
    if (!value) {
      throw new Error(
        `${env} is not set: add it to Application/.env` +
          (sharedKey ? ` or ${sharedKey} to Application/Config/application.yml` : ''),
      );
    }
    return value;
  };
  const optional = (env: string, sharedKey: string | null, fallback: string): string =>
    process.env[env] || (sharedKey ? shared[sharedKey] : undefined) || fallback;
  /** The frontend's full origin: FRONTEND_URL, else frontend.url + frontend.port from Config. */
  const frontendOrigin = (): string =>
    process.env.FRONTEND_URL || `${required('FRONTEND_URL', 'frontend.url')}:${required('FRONTEND_PORT', 'frontend.port')}`;

  return {
    port: parseInt(required('AUTH_SERVICE_PORT', 'services.auth.port'), 10),
    /** Where this service is reached from outside, for the start-up log. */
    publicUrl:
      process.env.AUTH_SERVICE_URL ||
      `${required('AUTH_SERVICE_URL', 'services.auth.url')}:${required('AUTH_SERVICE_PORT', 'services.auth.port')}`,
    database: {
      host: required('DB_HOST', 'database.host'),
      port: parseInt(required('DB_PORT', 'database.port'), 10),
      user: required('DB_USER', 'database.user'),
      password: required('DB_PASSWORD', null),
      name: required('DB_NAME', 'database.name'),
    },
    jwt: {
      secret: jwtSecret(),
      issuer: required('JWT_ISSUER', 'auth.jwt-issuer'),
      ttlSeconds: parseInt(required('JWT_TTL_SECONDS', 'auth.jwt-ttl-seconds'), 10),
    },
    refresh: {
      ttlSeconds: parseInt(required('REFRESH_TTL_SECONDS', 'auth.refresh-ttl-seconds'), 10),
    },
    /** Browser origins allowed to call this service with credentials (comma-separated). */
    corsOrigins: (process.env.CORS_ALLOWED_ORIGINS || frontendOrigin())
      .split(',')
      .map((origin) => origin.trim())
      .filter(Boolean),
    cookie: {
      name: optional('COOKIE_NAME', null, 'refresh_token'),
      // Secure is honoured by browsers even over http://localhost (trusted origin);
      // set COOKIE_SECURE=false only for plain-HTTP demos on non-local hosts.
      secure: process.env.COOKIE_SECURE !== 'false',
      sameSite: optional('COOKIE_SAMESITE', null, 'lax') as 'lax' | 'strict' | 'none',
    },
    throttle: {
      maxAttempts: parseInt(optional('THROTTLE_MAX_ATTEMPTS', null, '5'), 10),
      windowMs: parseInt(optional('THROTTLE_WINDOW_MS', null, '900000'), 10),
    },
    otp: {
      ttlSeconds: parseInt(optional('OTP_TTL_SECONDS', null, '600'), 10),
      maxAttempts: parseInt(optional('OTP_MAX_ATTEMPTS', null, '5'), 10),
    },
    smtp: {
      // Empty host: verification codes are logged instead of emailed (local work).
      host: process.env.SMTP_HOST ?? '',
      port: parseInt(optional('SMTP_PORT', 'email.smtp-port', '587'), 10),
      user: process.env.SMTP_USER || '',
      pass: process.env.SMTP_PASS || '',
      from: process.env.SMTP_FROM || '',
    },
  };
};
