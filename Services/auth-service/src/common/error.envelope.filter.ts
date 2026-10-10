import {
  ArgumentsHost,
  Catch,
  ExceptionFilter,
  HttpException,
  HttpStatus,
  Logger,
} from '@nestjs/common';
import { Response } from 'express';

interface ErrorEnvelope {
  errorCode: string;
  message: string;
}

const CODE_BY_STATUS: Record<number, string> = {
  [HttpStatus.UNAUTHORIZED]: 'AUTH-401',
  [HttpStatus.FORBIDDEN]: 'AUTH-403',
  [HttpStatus.NOT_FOUND]: 'AUTH-404',
  [HttpStatus.CONFLICT]: 'AUTH-409',
  [HttpStatus.UNPROCESSABLE_ENTITY]: 'VAL-422',
  [HttpStatus.TOO_MANY_REQUESTS]: 'RATE-429',
};

/**
 * Renders every error as the contract error envelope: { errorCode, message }.
 * No unexpected details are leaked to the caller.
 */
@Catch()
export class ErrorEnvelopeFilter implements ExceptionFilter {
  private readonly logger = new Logger(ErrorEnvelopeFilter.name);

  catch(exception: unknown, host: ArgumentsHost): void {
    const response = host.switchToHttp().getResponse<Response>();
    let status = HttpStatus.INTERNAL_SERVER_ERROR;
    let envelope: ErrorEnvelope = {
      errorCode: 'SRV-500',
      message: 'Internal server error',
    };

    if (exception instanceof HttpException) {
      status = exception.getStatus();
      const raw = exception.getResponse();
      const body =
        typeof raw === 'string' ? { message: raw } : (raw as Record<string, unknown>);

      const message =
        typeof body.message === 'string'
          ? body.message
          : Array.isArray(body.message)
            ? body.message.join(', ')
            : body.error
              ? String(body.error)
              : 'Request failed';

      envelope = {
        errorCode:
          (typeof body.errorCode === 'string' && body.errorCode) ||
          CODE_BY_STATUS[status] ||
          'SRV-500',
        message,
      };
    } else {
      // The caller sees only SRV-500; the cause has to reach the log or nobody
      // can ever find out what happened.
      this.logger.error(
        'Unhandled exception',
        exception instanceof Error ? exception.stack : String(exception),
      );
    }

    response.status(status).json(envelope);
  }
}

export function unauthorized(): HttpException {
  return new HttpException(
    { errorCode: 'AUTH-401', message: 'Unauthorised' },
    HttpStatus.UNAUTHORIZED,
  );
}

export function invalidInput(message: string): HttpException {
  return new HttpException(
    { errorCode: 'VAL-422', message },
    HttpStatus.UNPROCESSABLE_ENTITY,
  );
}

export function rateLimit(): HttpException {
  return new HttpException(
    { errorCode: 'RATE-429', message: 'Too many attempts' },
    HttpStatus.TOO_MANY_REQUESTS,
  );
}