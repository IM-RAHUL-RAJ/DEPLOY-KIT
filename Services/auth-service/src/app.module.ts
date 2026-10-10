import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { configuration } from './config/configuration';
import { AuthModule } from './auth/auth.module';
import { DbModule } from './db/db.service';
import { TokensModule } from './tokens/tokens.module';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      envFilePath: ['.env', '../../.env', '../../../.env'],
      load: [configuration],
    }),
    DbModule,
    TokensModule,
    AuthModule,
  ],
})
export class AppModule {}