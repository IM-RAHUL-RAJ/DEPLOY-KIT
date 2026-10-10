import { Module } from '@nestjs/common';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { RegistrationService } from './registration.service';
import { PasswordResetService } from './password-reset.service';
import { OtpService } from './otp.service';
import { MailService } from './mail.service';
import { JwtAuthGuard } from './auth.guard';
import { LoginThrottleService } from './login-throttle.service';
import { ExpiryCleanupService } from './expiry-cleanup.service';
import { TokensModule } from '../tokens/tokens.module';

@Module({
  imports: [TokensModule],
  controllers: [AuthController],
  providers: [
    AuthService,
    RegistrationService,
    PasswordResetService,
    OtpService,
    MailService,
    LoginThrottleService,
    ExpiryCleanupService,
    JwtAuthGuard,
  ],
  exports: [AuthService, RegistrationService],
})
export class AuthModule {}