import {
  UnprocessableEntityException,
  ValidationPipe,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { NestFactory } from '@nestjs/core';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { AppModule } from './app.module';
import { ErrorEnvelopeFilter } from './common/error.envelope.filter';
// eslint-disable-next-line @typescript-eslint/no-require-imports
import cookieParser = require('cookie-parser');

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule);
  const configService = app.get(ConfigService);

  // Credentialed CORS to the configured frontend origins only. Reflecting any
  // origin with credentials would let any site the user visits drive the
  // refresh endpoint with their cookie.
  app.enableCors({
    origin: configService.get<string[]>('corsOrigins'),
    credentials: true,
  });

  app.use(cookieParser());

  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true,
      transform: true,
      stopAtFirstError: false,
      exceptionFactory: (errors) => {
        const messages = errors.flatMap((error) =>
          Object.values(error.constraints ?? {}),
        );
        return new UnprocessableEntityException({
          errorCode: 'VAL-422',
          message: messages.join('; ') || 'Invalid input',
        });
      },
    }),
  );
  app.useGlobalFilters(new ErrorEnvelopeFilter());

  const config = new DocumentBuilder()
    .setTitle('Sprint 08 Auth Service')
    .setDescription('Standalone authentication service per the Sprint 08 contract')
    .setVersion('1.0.0')
    .addBearerAuth()
    .build();
  const document = SwaggerModule.createDocument(app, config);
  SwaggerModule.setup('docs', app, document, { jsonDocumentUrl: 'docs/json' });

  const port = configService.get<number>('port')!;
  const publicUrl = configService.get<string>('publicUrl')!;
  await app.listen(port);
  // eslint-disable-next-line no-console
  console.log(`Auth service listening on port ${port}: ${publicUrl}`);
  // eslint-disable-next-line no-console
  console.log(`Swagger UI at ${publicUrl}/docs`);
}
bootstrap();