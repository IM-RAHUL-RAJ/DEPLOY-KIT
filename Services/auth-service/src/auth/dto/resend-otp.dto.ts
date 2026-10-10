import { ApiProperty } from '@nestjs/swagger';
import { IsEmail, IsNotEmpty } from 'class-validator';

/** Asks for a fresh code for an address that still has a pending challenge. */
export class ResendOtpDto {
  @ApiProperty({ example: 'priya.menon@example.com' })
  @IsEmail()
  @IsNotEmpty()
  email!: string;
}