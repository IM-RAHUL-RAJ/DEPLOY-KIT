import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Patch,
  Post,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { ApiBearerAuth, ApiOperation, ApiTags } from '@nestjs/swagger';
import { Request, Response } from 'express';
import { AuthService, AuthResponse } from './auth.service';
import {
  RegistrationService,
  RegistrationStarted,
  RegistrationCompleted,
} from './registration.service';
import { PasswordResetService, PasswordResetStarted } from './password-reset.service';
import { JwtAuthGuard, AuthenticatedRequest } from './auth.guard';
import { LoginDto } from './dto/login.dto';
import { RefreshGrantDto } from './dto/refresh-grant.dto';
import { RegisterDto } from './dto/register.dto';
import { VerifyOtpDto } from './dto/verify-otp.dto';
import { ResendOtpDto } from './dto/resend-otp.dto';
import { RequestPasswordResetDto } from './dto/request-password-reset.dto';
import { ResetPasswordDto } from './dto/reset-password.dto';
import { UpdateProfileDto } from './dto/update-profile.dto';
import { ChangePasswordDto } from './dto/change-password.dto';
import { unauthorized } from '../common/error.envelope.filter';
import {
  clearRefreshCookie,
  setRefreshCookie,
  RefreshCookieConfig,
} from '../common/cookies';

export type AuthResponseBody = Omit<AuthResponse, 'refreshToken'>;

@ApiTags('auth')
@Controller('auth')
export class AuthController {
  private readonly cookieConfig: RefreshCookieConfig;
  private readonly refreshTtlSeconds: number;

  constructor(
    private readonly authService: AuthService,
    private readonly registration: RegistrationService,
    private readonly passwordReset: PasswordResetService,
    private readonly config: ConfigService,
  ) {
    this.cookieConfig = this.config.get<RefreshCookieConfig>('cookie')!;
    this.refreshTtlSeconds = this.config.get<number>('refresh.ttlSeconds')!;
  }

  @Post('register')
  @HttpCode(HttpStatus.CREATED)
  @ApiOperation({
    summary: 'Start registration: validate the profile and email a one-time code',
    description:
      'No user row is created here. The profile is held as a pending challenge until '
      + 'POST /auth/register/verify confirms the emailed code.',
  })
  async register(@Body() dto: RegisterDto): Promise<RegistrationStarted> {
    return this.registration.start(dto);
  }

  @Post('register/verify')
  @HttpCode(HttpStatus.CREATED)
  @ApiOperation({
    summary: 'Confirm the emailed code, creating the user and its pending account',
    description:
      'Creates auth.users with status INACTIVE and a matching INACTIVE '
      + 'trading account. No tokens are issued until an admin approves the user.',
  })
  async verifyRegistration(@Body() dto: VerifyOtpDto): Promise<RegistrationCompleted> {
    return this.registration.verify(dto);
  }

  @Post('register/resend')
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: 'Re-send the verification code for a pending registration' })
  async resendCode(@Body() dto: ResendOtpDto): Promise<{ message: string; emailDelivered: boolean; expiresInSeconds: number }> {
    return this.registration.resend(dto.email);
  }

  @Post('password-reset/request')
  @HttpCode(HttpStatus.OK)
  @ApiOperation({
    summary: 'Email a password reset code for a registered, active account',
    description:
      'The response is the same whether the address has an account or not, so this '
      + 'endpoint cannot be used to discover who is registered.',
  })
  async requestPasswordReset(
    @Body() dto: RequestPasswordResetDto,
  ): Promise<PasswordResetStarted> {
    return this.passwordReset.request(dto);
  }

  @Post('password-reset/reset')
  @HttpCode(HttpStatus.OK)
  @ApiOperation({
    summary: 'Verify the code and set a new password, revoking every existing session',
    description:
      'The code is consumed in the same transaction that writes the new password, and '
      + 'all refresh tokens of the user are revoked, so a replayed code changes nothing '
      + 'and a stolen open session does not survive the reset.',
  })
  async resetPassword(
    @Body() dto: ResetPasswordDto,
  ): Promise<{ message: string }> {
    return this.passwordReset.reset(dto);
  }

  @Post('login')
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: 'Log in with username/email and password' })
  async login(
    @Body() dto: LoginDto,
    @Res({ passthrough: true }) res: Response,
  ): Promise<AuthResponseBody> {
    const result = await this.authService.login(dto);
    setRefreshCookie(res, result.refreshToken, this.cookieConfig, this.refreshTtlSeconds);
    return this.stripRefreshToken(result);
  }

  @Post('refresh')
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: 'Rotate a refresh token and issue a new access token' })
  async refresh(
    @Req() req: Request,
    @Body() dto: RefreshGrantDto,
    @Res({ passthrough: true }) res: Response,
  ): Promise<AuthResponseBody> {
    const token = this.extractRefreshToken(req, dto);
    if (!token) {
      throw unauthorized();
    }
    const result = await this.authService.refresh({ refreshToken: token });
    setRefreshCookie(res, result.refreshToken, this.cookieConfig, this.refreshTtlSeconds);
    return this.stripRefreshToken(result);
  }

  @Post('logout')
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: 'Revoke the refresh token and clear the session cookie' })
  async logout(
    @Req() req: Request,
    @Body() dto: RefreshGrantDto,
    @Res({ passthrough: true }) res: Response,
  ) {
    const token = this.extractRefreshToken(req, dto);
    if (token) {
      await this.authService.logout(token);
    }
    clearRefreshCookie(res, this.cookieConfig);
    return { message: 'Logged out' };
  }

  @Get('me')
  @UseGuards(JwtAuthGuard)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Return the authenticated user profile' })
  me(@Req() req: AuthenticatedRequest): Promise<AuthResponse['user']> {
    return this.authService.me(req.authUser!.uuid);
  }

  @Patch('me')
  @UseGuards(JwtAuthGuard)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Update the authenticated user profile type' })
  updateProfile(
    @Req() req: AuthenticatedRequest,
    @Body() dto: UpdateProfileDto,
  ): Promise<AuthResponse['user']> {
    return this.authService.updateProfile(req.authUser!.uuid, dto);
  }

  @Post('me/password')
  @UseGuards(JwtAuthGuard)
  @ApiBearerAuth()
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: 'Change the authenticated user password' })
  changePassword(
    @Req() req: AuthenticatedRequest,
    @Body() dto: ChangePasswordDto,
  ): Promise<{ message: string }> {
    return this.authService.changePassword(req.authUser!.uuid, dto);
  }

  private extractRefreshToken(req: Request, dto: RefreshGrantDto): string | null {
    const fromCookie: unknown =
      req.cookies && (req.cookies as Record<string, unknown>)[this.cookieConfig.name];
    if (typeof fromCookie === 'string' && fromCookie.length > 0) {
      return fromCookie;
    }
    return dto.refreshToken && dto.refreshToken.length > 0 ? dto.refreshToken : null;
  }

  private stripRefreshToken(result: AuthResponse): AuthResponseBody {
    const { refreshToken: _omitted, ...body } = result;
    return body;
  }
}