import { ApiProperty } from '@nestjs/swagger';
import { IsEmail, IsNotEmpty } from 'class-validator';

/** First step: the address the account was registered with. */
export class RequestPasswordResetDto {
  @ApiProperty({ example: 'priya.menon@example.com' })
  @IsEmail()
  @IsNotEmpty()
  email!: string;
}