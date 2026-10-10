import { HttpException, Injectable, Logger } from '@nestjs/common';
import * as bcrypt from 'bcryptjs';
import { DbService } from '../db/db.service';
import { invalidInput } from '../common/error.envelope.filter';
import { OtpService } from './otp.service';
import { MailService } from './mail.service';
import { RequestPasswordResetDto } from './dto/request-password-reset.dto';
import { ResetPasswordDto } from './dto/reset-password.dto';
import { UserStatus } from '../common/risk-appetite';

const BCRYPT_ROUNDS = 12;

export interface PasswordResetStarted {
  message: string;
  expiresInSeconds: number;
  emailDelivered: boolean;
}

interface ResetChallengeRow {
  challenge_id: number;
  user_id: number;
  otp_hash: string;
  attempts: number;
  expires_at: Date;
  consumed_on: Date | null;
}

/**
 * Two-step password reset: an emailed code first, the password change second.
 *
 * The first step does not answer "is this address registered?" - the response is
 * the same whether the account exists or not, so the endpoint cannot be used to
 * harvest addresses. The second step changes the password and revokes every
 * refresh token of the user inside one transaction, and consumes the challenge
 * in that same transaction, so a replayed code can neither change the password
 * twice nor keep an old session alive.
 */
@Injectable()
export class PasswordResetService {
  private readonly logger = new Logger(PasswordResetService.name);

  constructor(
    private readonly db: DbService,
    private readonly otp: OtpService,
    private readonly mail: MailService,
  ) {}

  /** Step 1: for a registered, ACTIVE account, email a code and store the challenge. */
  async request(dto: RequestPasswordResetDto): Promise<PasswordResetStarted> {
    const email = dto.email.trim().toLowerCase();

    const result = await this.db.query(
      `SELECT user_id, first_name
         FROM auth.users
        WHERE lower(email) = lower($1) AND status = $2
        LIMIT 1`,
      [email, UserStatus.ACTIVE],
    );

    // Same answer with or without an account: asking must not reveal one.
    if (result.rowCount === 0) {
      return {
        message: 'If an account exists for that address, a reset code has been sent.',
        expiresInSeconds: this.otp.ttl,
        emailDelivered: false,
      };
    }

    const userId = result.rows[0].user_id as number;
    const firstName = result.rows[0].first_name as string;
    const code = this.otp.generate();

    // Supersede any earlier unconsumed challenge so only the newest code is valid.
    await this.db.query(
      `UPDATE auth.password_reset_challenge
          SET consumed_on = now()
        WHERE user_id = $1 AND consumed_on IS NULL`,
      [userId],
    );

    const inserted = await this.db.query(
      `INSERT INTO auth.password_reset_challenge
         (user_id, email, otp_hash, attempts, expires_at)
       VALUES ($1, $2, $3, 0, now() + ($4 || ' seconds')::interval)`,
      [userId, email, this.otp.hash(code), String(this.otp.ttl)],
    );

    const emailDelivered = await this.mail.sendPasswordReset({
      to: email,
      code,
      firstName,
      expiresInSeconds: this.otp.ttl,
    });

    this.logger.log(
      `Password reset requested: userId=${userId} challengeId=${inserted.rows[0].challenge_id} delivered=${emailDelivered}`,
    );

    return {
      message: emailDelivered
        ? 'A password reset code has been sent to your email. Enter it together with your new password.'
        : 'A password reset code has been generated. Check the service log for it (SMTP is not configured).',
      expiresInSeconds: this.otp.ttl,
      emailDelivered,
    };
  }

  /** Step 2: check the code, then change the password and revoke the sessions. */
  async reset(dto: ResetPasswordDto): Promise<{ message: string }> {
    if (dto.password !== dto.confirmPassword) {
      throw invalidInput('Passwords do not match');
    }

    const email = dto.email.trim().toLowerCase();
    const result = await this.db.query(
      `SELECT challenge_id, user_id, otp_hash, attempts, expires_at, consumed_on
         FROM auth.password_reset_challenge
        WHERE lower(email) = lower($1) AND consumed_on IS NULL
        ORDER BY challenge_id DESC
        LIMIT 1`,
      [email],
    );

    if (result.rowCount === 0) {
      // Same answer as a wrong code: do not confirm that an address is registered.
      throw this.rejected();
    }

    const challenge = result.rows[0] as ResetChallengeRow;

    if (new Date(challenge.expires_at).getTime() <= Date.now()) {
      throw new HttpException(
        { errorCode: 'AUTH-410', message: 'The reset code has expired. Request a new one.' },
        410,
      );
    }

    if (challenge.attempts >= this.otp.attempts) {
      throw new HttpException(
        { errorCode: 'RATE-429', message: 'Too many incorrect codes. Request a new one.' },
        429,
      );
    }

    if (!this.otp.verify(dto.code, challenge.otp_hash)) {
      await this.db.query(
        'UPDATE auth.password_reset_challenge SET attempts = attempts + 1 WHERE challenge_id = $1',
        [challenge.challenge_id],
      );
      throw this.rejected();
    }

    const client = await this.db.getClient();
    try {
      await client.query('BEGIN');

      const consumed = await client.query(
        `UPDATE auth.password_reset_challenge
            SET consumed_on = now()
          WHERE challenge_id = $1 AND consumed_on IS NULL`,
        [challenge.challenge_id],
      );
      if ((consumed.rowCount ?? 0) === 0) {
        throw this.rejected();
      }

      const passwordHash = await bcrypt.hash(dto.password, BCRYPT_ROUNDS);
      await client.query('UPDATE auth.users SET password_hash = $1 WHERE user_id = $2', [
        passwordHash,
        challenge.user_id,
      ]);

      // The new password invalidates every existing session immediately: an open
      // laptop token must not survive a password the customer just rotated.
      await client.query(
        `UPDATE auth.refresh_token
            SET revoked_on = now()
          WHERE user_id = $1 AND revoked_on IS NULL`,
        [challenge.user_id],
      );

      await client.query('COMMIT');

      this.logger.log(`Password reset completed: userId=${challenge.user_id}`);
      return { message: 'Password reset. Sign in with your new password.' };
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }

  private rejected(): HttpException {
    return new HttpException(
      { errorCode: 'AUTH-401', message: 'The reset code is incorrect or has expired' },
      401,
    );
  }
}