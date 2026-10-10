import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { rateLimit } from '../common/error.envelope.filter';

interface AttemptState {
  count: number;
  windowStart: number;
}

/**
 * In-memory login throttling. Failed attempts accumulate per identifier within
 * a sliding window; once exceeded, further attempts are rejected with 429.
 * A small fixed delay is applied on failed logins so responses are uniform in
 * time regardless of whether the user exists (mitigates user enumeration).
 */
@Injectable()
export class LoginThrottleService {
  private readonly logger = new Logger(LoginThrottleService.name);
  private readonly store = new Map<string, AttemptState>();
  private readonly maxAttempts: number;
  private readonly windowMs: number;

  constructor(config: ConfigService) {
    this.maxAttempts = config.get<number>('throttle.maxAttempts')!;
    this.windowMs = config.get<number>('throttle.windowMs')!;
  }

  check(identifier: string): void {
    const state = this.store.get(this.key(identifier));
    if (!state) {
      return;
    }
    if (Date.now() - state.windowStart >= this.windowMs) {
      this.store.delete(this.key(identifier));
      return;
    }
    if (state.count >= this.maxAttempts) {
      this.logger.warn(`Login throttle tripped for identifier="${identifier}"`);
      throw rateLimit();
    }
  }

  recordFailure(identifier: string): void {
    const key = this.key(identifier);
    const existing = this.store.get(key);
    const now = Date.now();
    if (!existing || now - existing.windowStart >= this.windowMs) {
      this.store.set(key, { count: 1, windowStart: now });
      return;
    }
    existing.count += 1;
  }

  reset(identifier: string): void {
    this.store.delete(this.key(identifier));
  }

  /** Uniform small delay applied on failed login attempts. */
  async delay(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 150));
  }

  private key(identifier: string): string {
    return identifier.trim().toLowerCase();
  }
}