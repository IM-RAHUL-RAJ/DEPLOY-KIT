import { CanActivate, ExecutionContext, Injectable } from '@nestjs/common';
import { Request } from 'express';
import { unauthorized } from '../common/error.envelope.filter';
import { TokensService } from '../tokens/tokens.service';

export interface AuthUser {
  uuid: string;
  accountId: number | null;
  roles: string[];
}

export interface AuthenticatedRequest extends Request {
  authUser?: AuthUser;
}

@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(private readonly tokens: TokensService) {}

  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const header = request.headers.authorization;
    if (!header || !header.startsWith('Bearer ')) {
      throw unauthorized();
    }
    const token = header.slice('Bearer '.length).trim();
    let payload;
    try {
      payload = this.tokens.verifyAccessToken(token);
    } catch {
      throw unauthorized();
    }

    const accountId = payload.accountId as number | null;
    request.authUser = {
      uuid: payload.sub as string,
      accountId: typeof accountId === 'number' && Number.isInteger(accountId) ? accountId : null,
      roles: payload.roles as string[],
    };
    return true;
  }
}