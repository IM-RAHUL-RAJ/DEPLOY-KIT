import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import {
  IsEmail,
  IsEnum,
  IsNotEmpty,
  IsOptional,
  IsString,
  Matches,
  MaxLength,
  MinLength,
} from 'class-validator';
import { RiskAppetite } from '../../common/risk-appetite';
import { COUNTRIES } from '../../common/country-catalog';
import { IsDialCodeForCountry } from '../../common/is-dial-code-for-country.decorator';

export class RegisterDto {
  @ApiProperty({ example: 'Priya' })
  @IsString()
  @IsNotEmpty()
  @MaxLength(100)
  firstName!: string;

  @ApiPropertyOptional({ example: 'K' })
  @IsOptional()
  @IsString()
  @MaxLength(100)
  middleName?: string;

  @ApiProperty({ example: 'Menon' })
  @IsString()
  @IsNotEmpty()
  @MaxLength(100)
  lastName!: string;

  @ApiProperty({ example: 'priya.menon' })
  @IsString()
  @MinLength(3)
  @MaxLength(50)
  @Matches(/^[a-zA-Z0-9._-]+$/, {
    message: 'username may only contain letters, numbers, dot, dash and underscore',
  })
  username!: string;

  @ApiProperty({ example: 'priya.menon@example.com' })
  @IsEmail()
  @MaxLength(255)
  email!: string;

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

  @ApiProperty({
    example: 'IN',
    enum: COUNTRIES.map((c) => c.code),
    description: 'ISO 3166-1 alpha-2 country; the account currency is derived from it',
  })
  @IsString()
  @IsNotEmpty()
  @MaxLength(2)
  @Matches(/^[A-Za-z]{2}$/, { message: 'countryCode must be a two-letter ISO country code' })
  countryCode!: string;

  @ApiProperty({
    example: '+91',
    description: 'Dial code for the selected country; rejected if it does not match it',
  })
  @IsString()
  @IsNotEmpty()
  @MaxLength(8)
  @Matches(/^\+?[0-9]{1,5}$/, { message: 'phoneCountryCode must be a dial code such as +91' })
  @IsDialCodeForCountry('countryCode')
  phoneCountryCode!: string;

  @ApiProperty({ example: '9876543210' })
  @IsString()
  @IsNotEmpty()
  @MaxLength(32)
  @Matches(/^[0-9]{4,15}$/, {
    message: 'phoneNumber must be 4-15 digits, without the country code',
  })
  phoneNumber!: string;

  @ApiProperty({ enum: RiskAppetite, example: RiskAppetite.BALANCED })
  @IsEnum(RiskAppetite, {
    message: 'riskAppetite must be one of CONSERVATIVE, BALANCED, AGGRESSIVE',
  })
  riskAppetite!: RiskAppetite;
}