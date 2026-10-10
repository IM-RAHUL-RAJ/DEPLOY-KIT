import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as nodemailer from 'nodemailer';
import type { Transporter } from 'nodemailer';

export interface OtpMail {
  to: string;
  code: string;
  firstName: string;
  expiresInSeconds: number;
}

export interface PasswordResetMail {
  to: string;
  code: string;
  firstName: string;
  expiresInSeconds: number;
}

/**
 * Delivers the registration OTP over SMTP.
 *
 * Configured entirely from the environment (SMTP_HOST, SMTP_PORT, SMTP_USER,
 * SMTP_PASS, SMTP_FROM). When SMTP is not configured the service logs the code
 * instead of sending it and reports `delivered: false`, so a developer can walk
 * the whole flow locally without a mail account while the response never claims
 * an email was sent that was not.
 */
@Injectable()
export class MailService implements OnModuleInit {
  private readonly logger = new Logger(MailService.name);
  private readonly host: string;
  private readonly port: number;
  private readonly user: string;
  private readonly pass: string;
  private readonly from: string;
  private transporter: Transporter | null = null;

  constructor(private readonly config: ConfigService) {
    this.host = this.config.get<string>('smtp.host')!;
    this.port = this.config.get<number>('smtp.port')!;
    this.user = this.config.get<string>('smtp.user')!;
    this.pass = this.config.get<string>('smtp.pass')!;
    this.from = this.config.get<string>('smtp.from')!;
  }

  onModuleInit(): void {
    if (!this.isConfigured) {
      this.logger.warn(
        'SMTP is not configured (SMTP_HOST/SMTP_USER); registration codes will be logged instead of emailed.',
      );
      return;
    }
    this.transporter = nodemailer.createTransport({
      host: this.host,
      port: this.port,
      secure: this.port === 465,
      auth: this.user ? { user: this.user, pass: this.pass } : undefined,
    });
    this.logger.log(`SMTP transport ready: ${this.host}:${this.port}`);
  }

  get isConfigured(): boolean {
    return this.host.length > 0 && this.from.length > 0;
  }

  /**
   * Sends the OTP. Returns whether an email actually left the process: a
   * delivery failure is logged and reported, never silently swallowed.
   */
  async sendOtp(mail: OtpMail): Promise<boolean> {
    const minutes = Math.max(1, Math.round(mail.expiresInSeconds / 60));
    const subject = 'Your Akatsuki Terminal verification code';
    const text = [
      `Hello ${mail.firstName},`,
      '',
      `Your verification code is ${mail.code}`,
      `It expires in ${minutes} minute${minutes === 1 ? '' : 's'}.`,
      '',
      'If you did not try to register, ignore this email.',
    ].join('\n');

    if (!this.transporter) {
      this.logger.warn(
        `SMTP unconfigured; verification code for ${this.maskEmail(mail.to)} is ${mail.code} (expires in ${minutes}m)`,
      );
      return false;
    }

    try {
      await this.transporter.sendMail({
        from: this.from,
        to: mail.to,
        subject,
        text,
      });
      this.logger.log(`Verification code emailed to ${this.maskEmail(mail.to)}`);
      return true;
    } catch (error) {
      const reason = error instanceof Error ? error.message : 'unknown error';
      this.logger.error(`Failed to send verification code: ${reason}`);
      return false;
    }
  }

  /**
   * Sends the password-reset code. Same honesty rule as {@link sendOtp}: when SMTP is
   * off the code is logged and false is returned, so the response never claims an email
   * left the process that did not.
   */
  async sendPasswordReset(mail: PasswordResetMail): Promise<boolean> {
    const minutes = Math.max(1, Math.round(mail.expiresInSeconds / 60));
    const subject = 'Your Akatsuki Terminal password reset code';
    const text = [
      `Hello ${mail.firstName},`,
      '',
      `Your password reset code is ${mail.code}`,
      `It expires in ${minutes} minute${minutes === 1 ? '' : 's'}.`,
      '',
      'If you did not ask to reset your password, ignore this email.',
    ].join('\n');

    if (!this.transporter) {
      this.logger.warn(
        `SMTP unconfigured; password reset code for ${this.maskEmail(mail.to)} is ${mail.code} (expires in ${minutes}m)`,
      );
      return false;
    }

    try {
      await this.transporter.sendMail({
        from: this.from,
        to: mail.to,
        subject,
        text,
      });
      this.logger.log(`Password reset code emailed to ${this.maskEmail(mail.to)}`);
      return true;
    } catch (error) {
      const reason = error instanceof Error ? error.message : 'unknown error';
      this.logger.error(`Failed to send password reset code: ${reason}`);
      return false;
    }
  }

  /** Never writes a full address to the log. */
  private maskEmail(email: string): string {
    const [local, domain] = email.split('@');
    if (!local || !domain) {
      return '***';
    }
    const head = local.slice(0, 1);
    return `${head}${'*'.repeat(Math.max(1, local.length - 1))}@${domain}`;
  }
}