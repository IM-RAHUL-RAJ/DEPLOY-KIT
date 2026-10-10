import { HttpException, Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as bcrypt from 'bcryptjs';
import { randomUUID } from 'node:crypto';
import { DbService } from '../db/db.service';
import { invalidInput, unauthorized } from '../common/error.envelope.filter';
import { TokensService } from '../tokens/tokens.service';
import { LoginThrottleService } from './login-throttle.service';
import { LoginDto } from './dto/login.dto';
import { RefreshGrantDto } from './dto/refresh-grant.dto';
import { UpdateProfileDto } from './dto/update-profile.dto';
import { ChangePasswordDto } from './dto/change-password.dto';

interface UserRow {
  user_id: number;
  uuid: string;
  first_name: string | null;
  middle_name: string | null;
  last_name: string | null;
  username: string;
  email: string;
  password_hash: string;
  roles: string[];
  status: string;
  country?: string | null;
  risk_appetite?: string | null;
  phone_country_code?: string | null;
  phone_number?: string | null;
}

const USER_COLUMNS = `user_id, uuid, first_name, middle_name, last_name, username, email,
       password_hash, roles, status, country, risk_appetite, phone_country_code, phone_number`;

/** Why a login was refused, so the UI can explain the next step. */
const STATUS_MESSAGE: Record<string, string> = {
  INACTIVE:
    'Your account is awaiting administrator approval. You will be able to sign in once it is approved.',
  SUSPENDED: 'Your account is suspended. Please contact support.',
  REJECTED: 'Your registration was rejected. Please contact support.',
};

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
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
    status: string;
    country: string | null;
    riskAppetite: string | null;
  };
}

@Injectable()
export class AuthService {
  private readonly logger = new Logger(AuthService.name);
  private readonly refreshTtlSeconds: number;

  constructor(
    private readonly db: DbService,
    private readonly config: ConfigService,
    private readonly tokens: TokensService,
    private readonly throttle: LoginThrottleService,
  ) {
    this.refreshTtlSeconds = this.config.get<number>('refresh.ttlSeconds')!;
  }

  async login(dto: LoginDto): Promise<AuthResponse> {
    const identifier = dto.identifier.trim();
    this.throttle.check(identifier);

    const result = await this.db.query(
      `SELECT ${USER_COLUMNS}
         FROM auth.users
        WHERE lower(username) = lower($1) OR lower(email) = lower($1)`,
      [identifier],
    );
    const user = result.rows[0] as UserRow | undefined;

    const passwordOk = user ? await bcrypt.compare(dto.password, user.password_hash) : false;
    // Uniform timing: always wait the same amount before responding.
    await this.throttle.delay();

    if (!user || !passwordOk) {
      this.throttle.recordFailure(identifier);
      this.logger.warn(`Login failed for identifier="${identifier}"`);
      throw unauthorized();
    }

    // A correct password is not enough: only an APPROVED user gets a session.
    // This is the same gate the Trade REST API applies to the account.
    if (user.status !== 'ACTIVE') {
      this.throttle.reset(identifier);
      this.logger.warn(`Login refused for user id=${user.user_id}: status=${user.status}`);
      throw this.notActive(user.status);
    }

    this.throttle.reset(identifier);
    return this.issueTokens(user, await this.accountIdFor(user.user_id));
  }

  /**
   * Exchanges a refresh token for a new pair, rotating it.
   *
   * The presented token is revoked by the same statement that checks it is live,
   * so two concurrent refreshes with one token cannot both succeed: exactly one
   * update matches, and the loser is treated as a replay. Presenting a token that
   * was already rotated revokes its whole family, because one of the two holders
   * is not the customer.
   */
  async refresh(dto: RefreshGrantDto): Promise<AuthResponse> {
    if (!dto.refreshToken) {
      throw unauthorized();
    }
    const tokenHash = this.tokens.hashRefreshToken(dto.refreshToken);

    const claimed = await this.db.query(
      `UPDATE auth.refresh_token
          SET revoked_on = now()
        WHERE token_hash = $1 AND revoked_on IS NULL AND expires_at > now()
        RETURNING user_id, family_id`,
      [tokenHash],
    );

    if ((claimed.rowCount ?? 0) === 0) {
      const known = await this.db.query(
        'SELECT family_id, revoked_on FROM auth.refresh_token WHERE token_hash = $1',
        [tokenHash],
      );
      const row = known.rows[0] as { family_id: string; revoked_on: Date | null } | undefined;
      if (row?.revoked_on) {
        this.logger.warn(`Replayed refresh token detected; revoking family=${row.family_id}`);
        await this.revokeFamily(row.family_id);
      }
      throw unauthorized();
    }
    const { user_id: userId, family_id: familyId } = claimed.rows[0] as {
      user_id: number;
      family_id: string;
    };

    const userRes = await this.db.query(
      `SELECT ${USER_COLUMNS}
         FROM auth.users
        WHERE user_id = $1`,
      [userId],
    );
    const user = userRes.rows[0] as UserRow | undefined;
    if (!user) {
      throw unauthorized();
    }

    // A user suspended after the session started loses it on refresh too, not
    // only at the next login.
    if (user.status !== 'ACTIVE') {
      await this.revokeFamily(familyId);
      throw this.notActive(user.status);
    }

    this.logger.log(`Rotated refresh token for user id=${userId} family=${familyId}`);
    return this.issueTokens(user, await this.accountIdFor(user.user_id), familyId);
  }

  async logout(refreshToken: string): Promise<void> {
    const tokenHash = this.tokens.hashRefreshToken(refreshToken);
    const result = await this.db.query(
      'UPDATE auth.refresh_token SET revoked_on = now() WHERE token_hash = $1 AND revoked_on IS NULL',
      [tokenHash],
    );
    if ((result.rowCount ?? 0) > 0) {
      this.logger.log('Logged out: refresh token revoked');
    }
  }

  async me(uuid: string): Promise<AuthResponse['user']> {
    const user = await this.userByUuid(uuid);
    return this.toPublicUser(user, await this.accountIdFor(user.user_id));
  }

  async updateProfile(uuid: string, dto: UpdateProfileDto): Promise<AuthResponse['user']> {
    const updated = await this.db.query(
      `UPDATE auth.users
          SET risk_appetite = $1
        WHERE uuid = $2
      RETURNING ${USER_COLUMNS}`,
      [dto.riskAppetite, uuid],
    );
    if ((updated.rowCount ?? 0) === 0) {
      throw unauthorized();
    }
    const user = updated.rows[0] as UserRow;
    return this.toPublicUser(user, await this.accountIdFor(user.user_id));
  }

  async changePassword(uuid: string, dto: ChangePasswordDto): Promise<{ message: string }> {
    if (dto.newPassword !== dto.confirmPassword) {
      throw invalidInput('New password and confirmation must match');
    }

    const user = await this.userByUuid(uuid);
    const currentPasswordOk = await bcrypt.compare(dto.currentPassword, user.password_hash);
    if (!currentPasswordOk) {
      throw new HttpException(
        { errorCode: 'AUTH-422', message: 'The current password is incorrect.' },
        422,
      );
    }

    const nextHash = await bcrypt.hash(dto.newPassword, 12);
    await this.db.query(
      'UPDATE auth.users SET password_hash = $1 WHERE user_id = $2',
      [nextHash, user.user_id],
    );
    await this.db.query(
      'UPDATE auth.refresh_token SET revoked_on = now() WHERE user_id = $1 AND revoked_on IS NULL',
      [user.user_id],
    );
    return { message: 'Password changed' };
  }

  private async issueTokens(
    user: UserRow,
    accountId: number | null,
    familyId?: string,
  ): Promise<AuthResponse> {
    const { accessToken, expiresIn } = this.tokens.signAccessToken({
      uuid: user.uuid,
      accountId,
      roles: user.roles,
    });

    const refreshToken = this.tokens.generateRefreshToken();
    const refreshTokenHash = this.tokens.hashRefreshToken(refreshToken);
    const family = familyId ?? this.randomFamilyId();

    await this.db.query(
      `INSERT INTO auth.refresh_token (user_id, token_hash, family_id, expires_at, revoked_on)
       VALUES ($1, $2, $3, now() + ($4 || ' seconds')::interval, NULL)`,
      [user.user_id, refreshTokenHash, family, this.refreshTtlSeconds],
    );

    return {
      accessToken,
      refreshToken,
      expiresIn,
      user: this.toPublicUser(user, accountId),
    };
  }

  private toPublicUser(user: UserRow, accountId: number | null): AuthResponse['user'] {
    return {
      userId: user.user_id,
      uuid: user.uuid,
      firstName: user.first_name,
      middleName: user.middle_name,
      lastName: user.last_name,
      username: user.username,
      email: user.email,
      roles: user.roles,
      accountId,
      status: user.status,
      country: user.country ?? null,
      riskAppetite: user.risk_appetite ?? null,
    };
  }

  private async accountIdFor(userId: number): Promise<number | null> {
    const res = await this.db.query(
      'SELECT account_id FROM trading.account WHERE user_id = $1',
      [userId],
    );
    return res.rows.length > 0 ? (res.rows[0].account_id as number) : null;
  }

  private async revokeFamily(familyId: string): Promise<void> {
    await this.db.query(
      'UPDATE auth.refresh_token SET revoked_on = now() WHERE family_id = $1 AND revoked_on IS NULL',
      [familyId],
    );
  }

  private async userByUuid(uuid: string): Promise<UserRow> {
    const userRes = await this.db.query(
      `SELECT ${USER_COLUMNS}
         FROM auth.users
        WHERE uuid = $1`,
      [uuid],
    );
    if (userRes.rows.length === 0) {
      throw unauthorized();
    }
    return userRes.rows[0] as UserRow;
  }

  /** Why a correct password still gets no session, so the UI can say what to do next. */
  private notActive(status: string): HttpException {
    return new HttpException(
      {
        errorCode: 'AUTH-403',
        message: STATUS_MESSAGE[status] ?? 'Your account is not active. Please contact support.',
      },
      403,
    );
  }

  private randomFamilyId(): string {
    return randomUUID();
  }
}