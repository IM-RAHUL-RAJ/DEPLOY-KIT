import { ApiProperty } from '@nestjs/swagger';
import { IsEnum } from 'class-validator';
import { RiskAppetite } from '../../common/risk-appetite';

export class UpdateProfileDto {
    @ApiProperty({ enum: RiskAppetite, example: RiskAppetite.BALANCED })
    @IsEnum(RiskAppetite, {
        message: 'riskAppetite must be one of CONSERVATIVE, BALANCED, AGGRESSIVE',
    })
    riskAppetite!: RiskAppetite;
}