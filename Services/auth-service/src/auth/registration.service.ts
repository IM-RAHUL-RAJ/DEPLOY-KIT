import { HttpException, Injectable, Logger } from '@nestjs/common';
import * as bcrypt from 'bcryptjs';
import { DbService } from '../db/db.service';
import { invalidInput } from '../common/error.envelope.filter';
import { OtpService } from './otp.service';
import { MailService } from './mail.service';
import { RegisterDto } from './dto/register.dto';
import { VerifyOtpDto } from './dto/verify-otp.dto';
import { currencyForCountry, findCountry } from '../common/country-catalog';
import { UserStatus } from '../common/risk-appetite';

const BCRYPT_ROUNDS = 12;
/** Opening balance for a brand-new account. Zero until an admin funds it. */
const OPENING_BALANCE = '0.00';

export interface RegistrationStarted {
  message: string;
  email: string;
  challengeId: number;
  expiresInSeconds: number;
  /** False when SMTP is unconfigured and the code was only logged. */
  emailDelivered: boolean;
}

export interface RegistrationCompleted {
  message: string;
  user: {
    userId: number;
    uuid: string;
    firstName: string | null;
    middleName: string | null;
    lastName: string | null;
    username: string;
    email: string;
    roles: string[];
    accountId: number | null;
    status: UserStatus;
    country: string;
    riskAppetite: string;
  };
}

interface ChallengeRow {
  challenge_id: number;
  otp_hash: string;
  attempts: number;
  expires_at: Date;
  consumed_on: Date | null;
}

/**
 * Two-step registration: an emailed OTP first, a user row second.
 *
 * Nothing in auth.users is written until the code is verified. The pending
 * application lives in auth.registration_challenge, so an unverified address
 * cannot occupy a username, appear in the admin console, or be logged into. The
 * new user is INACTIVE and their account mirrors that status, which is
 * what stops them trading until an admin approves them.
 */
@Injectable()
export class RegistrationService {
  private readonly logger = new Logger(RegistrationService.name);

  constructor(
    private readonly db: DbService,
    private readonly otp: OtpService,
    private readonly mail: MailService,
  ) {}

  /** Step 1: validate, hash the password, email the code, store the challenge. */
  async start(dto: RegisterDto): Promise<RegistrationStarted> {
    if (dto.password !== dto.confirmPassword) {
      throw invalidInput('Passwords do not match');
    }

    const country = findCountry(dto.countryCode);
    if (!country) {
      throw invalidInput(`countryCode ${dto.countryCode} is not supported`);
    }

    const clash = await this.db.query(
      `SELECT 1 FROM auth.users
        WHERE lower(username) = lower($1) OR lower(email) = lower($2)
        UNION ALL
       SELECT 1 FROM auth.registration_challenge
        WHERE (lower(username) = lower($1) AND lower(email) <> lower($2))
          AND consumed_on IS NULL AND expires_at > now()
        LIMIT 1`,
      [dto.username, dto.email],
    );
    if (clash.rowCount && clash.rowCount > 0) {
      throw new HttpException(
        { errorCode: 'AUTH-409', message: 'Registration failed' },
        409,
      );
    }

    const passwordHash = await bcrypt.hash(dto.password, BCRYPT_ROUNDS);
    const code = this.otp.generate();

    // Supersede any earlier unconsumed challenge for the same address so only
    // the newest code can complete the registration.
    await this.db.query(
      `UPDATE auth.registration_challenge
          SET consumed_on = now()
        WHERE (lower(email) = lower($1) OR lower(username) = lower($2))
          AND consumed_on IS NULL`,
      [dto.email, dto.username],
    );

    const inserted = await this.db.query(
      `INSERT INTO auth.registration_challenge
         (email, username, first_name, middle_name, last_name, password_hash,
          phone_country_code, phone_number, country_code, country, risk_appetite,
          otp_hash, attempts, expires_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, 0, now() + ($13 || ' seconds')::interval)
       RETURNING challenge_id`,
      [
        dto.email.trim(),
        dto.username.trim(),
        dto.firstName.trim(),
        dto.middleName?.trim() || null,
        dto.lastName.trim(),
        passwordHash,
        dto.phoneCountryCode.trim(),
        dto.phoneNumber.trim(),
        country.code,
        country.name,
        dto.riskAppetite,
        this.otp.hash(code),
        String(this.otp.ttl),
      ],
    );
    const challengeId = inserted.rows[0].challenge_id as number;

    const emailDelivered = await this.mail.sendOtp({
      to: dto.email.trim(),
      code,
      firstName: dto.firstName.trim(),
      expiresInSeconds: this.otp.ttl,
    });

    this.logger.log(
      `Registration started: challengeId=${challengeId} country=${country.code} currency=${country.currency} delivered=${emailDelivered}`,
    );

    return {
      message: emailDelivered
        ? 'Verification code sent. Enter it to finish creating your account.'
        : 'Verification code generated. Check the service log for the code (SMTP is not configured).',
      email: dto.email.trim(),
      challengeId,
      expiresInSeconds: this.otp.ttl,
      emailDelivered,
    };
  }

  /** Step 2: check the code, then create the user and its account atomically. */
  async verify(dto: VerifyOtpDto): Promise<RegistrationCompleted> {
    const result = await this.db.query(
      `SELECT challenge_id, otp_hash, attempts, expires_at, consumed_on
         FROM auth.registration_challenge
        WHERE lower(email) = lower($1) AND consumed_on IS NULL
        ORDER BY challenge_id DESC
        LIMIT 1`,
      [dto.email.trim()],
    );
    if (result.rowCount === 0) {
      // Same answer as a wrong code: do not confirm that an address is registered.
      throw this.otpRejected();
    }

    const challenge = result.rows[0] as ChallengeRow;

    if (new Date(challenge.expires_at).getTime() <= Date.now()) {
      throw new HttpException(
        { errorCode: 'AUTH-410', message: 'Verification code has expired. Please register again.' },
        410,
      );
    }

    if (challenge.attempts >= this.otp.attempts) {
      throw new HttpException(
        { errorCode: 'RATE-429', message: 'Too many incorrect codes. Please register again.' },
        429,
      );
    }

    if (!this.otp.verify(dto.code, challenge.otp_hash)) {
      await this.db.query(
        'UPDATE auth.registration_challenge SET attempts = attempts + 1 WHERE challenge_id = $1',
        [challenge.challenge_id],
      );
      throw this.otpRejected();
    }

    const client = await this.db.getClient();
    try {
      await client.query('BEGIN');

      // Consume first: a concurrent request that read the same challenge now
      // finds consumed_on set and cannot create a second user.
      const consumed = await client.query(
        `UPDATE auth.registration_challenge
            SET verified_on = now(), consumed_on = now()
          WHERE challenge_id = $1 AND consumed_on IS NULL`,
        [challenge.challenge_id],
      );
      if ((consumed.rowCount ?? 0) === 0) {
        throw this.otpRejected();
      }

      const challengeData = await client.query(
        `SELECT email, username, first_name, middle_name, last_name, password_hash,
                phone_country_code, phone_number, country_code, country, risk_appetite
           FROM auth.registration_challenge
          WHERE challenge_id = $1`,
        [challenge.challenge_id],
      );
      const pending = challengeData.rows[0];

      const userRes = await client.query(
        `INSERT INTO auth.users
           (uuid, first_name, middle_name, last_name, username, email, password_hash,
            phone_country_code, phone_number, country_code, country, risk_appetite,
            roles, status, email_verified_on)
         VALUES (gen_random_uuid(), $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11,
                 ARRAY['CUSTOMER'], $12, now())
         RETURNING user_id, uuid, first_name, middle_name, last_name, username, email, roles`,
        [
          pending.first_name,
          pending.middle_name,
          pending.last_name,
          pending.username,
          pending.email,
          pending.password_hash,
          pending.phone_country_code,
          pending.phone_number,
          pending.country_code,
          pending.country,
          pending.risk_appetite,
          UserStatus.INACTIVE,
        ],
      );
      const user = userRes.rows[0];

      // The currency comes from the catalogue, not from the request, so it always
      // matches the country the account was opened for.
      const currency = currencyForCountry(pending.country_code) ?? 'USD';
      const accountRes = await client.query(
        `INSERT INTO trading.account (user_id, currency, cash_balance, status, version)
         VALUES ($1, $2, $3, $4, 1)
         RETURNING account_id`,
        [user.user_id, currency, OPENING_BALANCE, UserStatus.INACTIVE],
      );

      await client.query(
        `INSERT INTO auth.status_change_log (user_id, old_status, new_status, reason)
         VALUES ($1, NULL, $2, $3)`,
        [user.user_id, UserStatus.INACTIVE, 'Email verified; awaiting admin approval'],
      );

      await client.query('COMMIT');

      this.logger.log(
        `Registration completed: userId=${user.user_id} accountId=${accountRes.rows[0].account_id} status=${UserStatus.INACTIVE}`,
      );

      // No tokens: the account is not tradeable yet, so there is nothing to sign into.
      return {
        message:
          'Email verified. Your account is awaiting administrator approval; you will be able to sign in once it is approved.',
        user: {
          userId: user.user_id,
          uuid: user.uuid,
          firstName: user.first_name,
          middleName: user.middle_name,
          lastName: user.last_name,
          username: user.username,
          email: user.email,
          roles: user.roles,
          accountId: accountRes.rows[0].account_id as number,
          status: UserStatus.INACTIVE,
          country: pending.country,
          riskAppetite: pending.risk_appetite,
        },
      };
    } catch (error) {
      await client.query('ROLLBACK');
      if (this.isUniqueViolation(error)) {
        throw new HttpException(
          { errorCode: 'AUTH-409', message: 'Registration failed' },
          409,
        );
      }
      throw error;
    } finally {
      client.release();
    }
  }

  /** Re-sends a code for an unconsumed challenge, or reports that there is none. */
  async resend(email: string): Promise<{ message: string; emailDelivered: boolean; expiresInSeconds: number }> {
    const result = await this.db.query(
      `SELECT challenge_id, first_name FROM auth.registration_challenge
        WHERE lower(email) = lower($1) AND consumed_on IS NULL
        ORDER BY challenge_id DESC LIMIT 1`,
      [email.trim()],
    );
    if (result.rowCount === 0) {
      throw new HttpException(
        { errorCode: 'AUTH-404', message: 'No pending registration for that address' },
        404,
      );
    }

    const code = this.otp.generate();
    await this.db.query(
      `UPDATE auth.registration_challenge
          SET otp_hash = $2, attempts = 0, expires_at = now() + ($3 || ' seconds')::interval
        WHERE challenge_id = $1`,
      [result.rows[0].challenge_id, this.otp.hash(code), String(this.otp.ttl)],
    );

    const emailDelivered = await this.mail.sendOtp({
      to: email.trim(),
      code,
      firstName: result.rows[0].first_name,
      expiresInSeconds: this.otp.ttl,
    });

    return {
      message: emailDelivered
        ? 'A new verification code has been sent.'
        : 'A new verification code was generated. Check the service log for it (SMTP is not configured).',
      emailDelivered,
      expiresInSeconds: this.otp.ttl,
    };
  }

  private otpRejected(): HttpException {
    return new HttpException(
      { errorCode: 'AUTH-401', message: 'Verification code is incorrect or has expired' },
      401,
    );
  }

  private isUniqueViolation(error: unknown): boolean {
    return (
      typeof error === 'object' &&
      error !== null &&
      (error as { code?: string }).code === '23505'
    );
  }
}