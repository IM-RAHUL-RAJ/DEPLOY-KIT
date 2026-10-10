import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { createHash, randomInt, timingSafeEqual } from 'crypto';

/**
 * Generation, hashing and verification of the email one-time password.
 *
 * The code is never stored: only its SHA-256 digest is, so read access to
 * auth.registration_challenge is not enough to complete somebody's
 * registration. Comparison is constant-time so a wrong code cannot be discovered
 * a character at a time.
 */
@Injectable()
export class OtpService {
  private readonly logger = new Logger(OtpService.name);
  private readonly ttlSeconds: number;
  private readonly maxAttempts: number;

  constructor(private readonly config: ConfigService) {
    this.ttlSeconds = this.config.get<number>('otp.ttlSeconds')!;
    this.maxAttempts = this.config.get<number>('otp.maxAttempts')!;
  }

  /** Cryptographically random six-digit code, zero-padded. */
  generate(): string {
    return String(randomInt(0, 1_000_000)).padStart(6, '0');
  }

  hash(code: string): string {
    return createHash('sha256').update(code.trim(), 'utf8').digest('hex');
  }

  /** Constant-time comparison of a submitted code against a stored digest. */
  verify(code: string, storedHash: string): boolean {
    const submitted = Buffer.from(this.hash(code), 'hex');
    const stored = Buffer.from(storedHash, 'hex');
    if (submitted.length !== stored.length) {
      return false;
    }
    return timingSafeEqual(submitted, stored);
  }

  get ttl(): number {
    return this.ttlSeconds;
  }

  get attempts(): number {
    return this.maxAttempts;
  }

  get maskedForLog(): string {
    return '******';
  }

  debugLog(message: string): void {
    this.logger.debug(message);
  }
}