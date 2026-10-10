import { ApiProperty } from '@nestjs/swagger';
import { IsEmail, IsNotEmpty, IsString, Length } from 'class-validator';

/** Second step: the emailed code, checked against the stored digest. */
export class VerifyOtpDto {
  @ApiProperty({ example: 'priya.menon@example.com' })
  @IsEmail()
  @IsNotEmpty()
  email!: string;

  @ApiProperty({ example: '123456' })
  @IsString()
  @Length(6, 6, { message: 'code must be exactly six digits' })
  code!: string;
}