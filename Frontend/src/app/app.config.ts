import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import {
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withPreloading,
  PreloadAllModules,
  withViewTransitions,
} from '@angular/router';
import { provideHttpClient, withInterceptors, withFetch } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';
import { Configuration as TradeApiConfiguration } from '@trade-api/configuration';
import { Configuration as AuthApiConfiguration } from '@auth-api/configuration';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { environment } from '../environments/environment';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(
      routes,
      withComponentInputBinding(),
      // Every route is already lazy, so a page switch is a component swap and
      // nothing more. Preloading fetches those chunks after the first paint, which
      // is what removes the white gap on the second navigation.
      withPreloading(PreloadAllModules),
      // The browser's own cross-fade, driven by the router rather than by a
      // hand-rolled animation on each shell. Falls back to an instant swap where
      // the browser has no View Transition support.
      withViewTransitions(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
    ),
    provideHttpClient(
      withFetch(),
      withInterceptors([authInterceptor])
    ),
    provideAnimations(),
    // The generated clients default to the server URLs written in the contracts.
    // Point them at the services this build talks to. The auth client sends
    // credentials so the browser carries the HttpOnly refresh cookie.
    {
      provide: TradeApiConfiguration,
      useFactory: () => new TradeApiConfiguration({ basePath: environment.tradeApiUrl }),
    },
    {
      provide: AuthApiConfiguration,
      useFactory: () =>
        new AuthApiConfiguration({ basePath: environment.authApiUrl, withCredentials: true }),
    },
  ],
};
