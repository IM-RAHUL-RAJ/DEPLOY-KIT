import { ApiProperty } from '@nestjs/swagger';
import { IsNotEmpty, IsString, Matches, MaxLength, MinLength } from 'class-validator';

export class ChangePasswordDto {
    @ApiProperty({ example: 'Capstone@2026' })
    @IsString()
    @IsNotEmpty()
    currentPassword!: string;

    @ApiProperty({ example: 'BetterCapstone@2027' })
    @IsString()
    @MinLength(12, { message: 'newPassword must be at least 12 characters long' })
    @MaxLength(72, { message: 'newPassword must be at most 72 characters long' })
    @Matches(/[a-z]/, { message: 'newPassword must contain a lowercase letter' })
    @Matches(/[A-Z]/, { message: 'newPassword must contain an uppercase letter' })
    @Matches(/[0-9]/, { message: 'newPassword must contain a digit' })
    @Matches(/[^a-zA-Z0-9]/, { message: 'newPassword must contain a special character' })
    newPassword!: string;

    @ApiProperty({ example: 'BetterCapstone@2027' })
    @IsString()
    @IsNotEmpty()
    confirmPassword!: string;
}