import { ApiPropertyOptional } from '@nestjs/swagger';
import { IsNotEmpty, IsOptional, IsString } from 'class-validator';

export class RefreshGrantDto {
  @ApiPropertyOptional({
    description:
      'Refresh token. Optional — the token is read from the HttpOnly refresh_token cookie when present.',
  })
  @IsOptional()
  @IsString()
  @IsNotEmpty()
  refreshToken?: string;
}