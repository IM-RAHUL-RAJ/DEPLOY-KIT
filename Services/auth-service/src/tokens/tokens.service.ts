import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { createHash, randomBytes } from 'crypto';
import * as jwt from 'jsonwebtoken';

export interface TokenSubject {
  uuid: string;
  accountId: number | null;
  roles: string[];
}

export interface AccessTokenPair {
  accessToken: string;
  expiresIn: number;
}

@Injectable()
export class TokensService {
  private readonly secret: string;
  private readonly issuer: string;
  private readonly ttlSeconds: number;

  constructor(config: ConfigService) {
    this.secret = config.get<string>('jwt.secret')!;
    this.issuer = config.get<string>('jwt.issuer')!;
    this.ttlSeconds = config.get<number>('jwt.ttlSeconds')!;
  }

  signAccessToken(sub: TokenSubject): AccessTokenPair {
    const accessToken = jwt.sign(
      {
        accountId: sub.accountId,
        roles: sub.roles,
      },
      this.secret,
      {
        algorithm: 'HS256',
        issuer: this.issuer,
        subject: sub.uuid,
        expiresIn: this.ttlSeconds,
      },
    );
    return { accessToken, expiresIn: this.ttlSeconds };
  }

  /**
   * Verifies signature, algorithm, issuer, expiration, and mandatory claims
   * (sub + roles). Returns the decoded payload.
   */
  verifyAccessToken(token: string): jwt.JwtPayload {
    const payload = jwt.verify(token, this.secret, {
      algorithms: ['HS256'],
      issuer: this.issuer,
    }) as jwt.JwtPayload;

    if (!payload.sub || typeof payload.sub !== 'string' || payload.sub.length === 0) {
      throw new jwt.JsonWebTokenError('Token subject is missing');
    }
    if (!Array.isArray(payload.roles) || payload.roles.length === 0) {
      throw new jwt.JsonWebTokenError('Token roles are missing');
    }
    return payload;
  }

  generateRefreshToken(): string {
    return randomBytes(48).toString('hex');
  }

  /**
   * Refresh tokens are stored as SHA-256 digests so a database leak does not
   * expose usable tokens.
   */
  hashRefreshToken(token: string): string {
    return createHash('sha256').update(token).digest('hex');
  }
}