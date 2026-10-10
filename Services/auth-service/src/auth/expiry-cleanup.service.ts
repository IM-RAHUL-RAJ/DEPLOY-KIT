import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { DbService } from '../db/db.service';

const HOUR_MS = 60 * 60 * 1000;

/**
 * Deletes registration and password-reset challenges and refresh tokens that
 * can never be used again, so the tables do not grow for ever.
 *
 * A day's grace is kept past expiry: a refresh token revoked a moment ago is
 * still needed to recognise a replay of it, which revokes its whole family.
 */
@Injectable()
export class ExpiryCleanupService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(ExpiryCleanupService.name);
  private timer: NodeJS.Timeout | null = null;

  constructor(private readonly db: DbService) {}

  onModuleInit(): void {
    this.timer = setInterval(() => void this.purge(), HOUR_MS);
    this.timer.unref();
  }

  onModuleDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }

  async purge(): Promise<void> {
    try {
      const challenges = await this.db.query(
        `DELETE FROM auth.registration_challenge
          WHERE expires_at < now() - interval '1 day'
             OR consumed_on < now() - interval '1 day'`,
      );
      const resets = await this.db.query(
        `DELETE FROM auth.password_reset_challenge
          WHERE expires_at < now() - interval '1 day'
             OR consumed_on < now() - interval '1 day'`,
      );
      const tokens = await this.db.query(
        `DELETE FROM auth.refresh_token WHERE expires_at < now() - interval '1 day'`,
      );
      const removed =
        (challenges.rowCount ?? 0) + (resets.rowCount ?? 0) + (tokens.rowCount ?? 0);
      if (removed > 0) {
        this.logger.log(
          `Purged ${challenges.rowCount ?? 0} registration challenge(s), ${resets.rowCount ?? 0} password reset challenge(s) and ${tokens.rowCount ?? 0} refresh token(s)`,
        );
      }
    } catch (error) {
      this.logger.warn(`Expiry clean-up failed: ${error instanceof Error ? error.message : error}`);
    }
  }
}
