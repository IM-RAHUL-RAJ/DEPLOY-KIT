import { ApiProperty } from '@nestjs/swagger';
import {
  IsEmail,
  IsNotEmpty,
  IsString,
  Length,
  Matches,
  MaxLength,
  MinLength,
} from 'class-validator';

/**
 * Second step: the code and the new password, stated twice. The rules mirror
 * RegisterDto so a customer cannot be pushed into a weaker password by going
 * through the reset path instead of registration.
 */
export class ResetPasswordDto {
  @ApiProperty({ example: 'priya.menon@example.com' })
  @IsEmail()
  @IsNotEmpty()
  email!: string;

  @ApiProperty({ example: '123456' })
  @IsString()
  @Length(6, 6, { message: 'code must be exactly six digits' })
  code!: string;

  @ApiProperty({ example: 'Capstone@2026' })
  @IsString()
  @MinLength(12, { message: 'password must be at least 12 characters long' })
  @MaxLength(72, { message: 'password must be at most 72 characters long' })
  @Matches(/[a-z]/, { message: 'password must contain a lowercase letter' })
  @Matches(/[A-Z]/, { message: 'password must contain an uppercase letter' })
  @Matches(/[0-9]/, { message: 'password must contain a digit' })
  @Matches(/[^a-zA-Z0-9]/, { message: 'password must contain a special character' })
  password!: string;

  @ApiProperty({ example: 'Capstone@2026' })
  @IsString()
  @IsNotEmpty()
  confirmPassword!: string;
}