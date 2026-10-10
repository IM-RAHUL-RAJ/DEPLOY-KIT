import { ApiProperty } from '@nestjs/swagger';
import { IsNotEmpty, IsString, MaxLength } from 'class-validator';

export class LoginDto {
  @ApiProperty({ example: 'priya.menon' })
  @IsString()
  @IsNotEmpty()
  @MaxLength(255)
  identifier!: string;

  @ApiProperty({ example: 'Capstone@2026' })
  @IsString()
  @IsNotEmpty()
  @MaxLength(255)
  password!: string;
}