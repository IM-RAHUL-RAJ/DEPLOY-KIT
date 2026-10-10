import { Global, Module } from '@nestjs/common';
import { TokensService } from './tokens.service';

@Global()
@Module({
  providers: [TokensService],
  exports: [TokensService],
})
export class TokensModule {}