import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { TokenService } from '../services/token.service';
import { safeReturnUrl } from './return-url';

/**
 * Guard: usability control, not a security control. The bundle is public and
 * every route in it is readable; authorisation is the Trade REST API's decision on
 * every /api/v1/** call.
 *
 * Redirects unauthenticated visitors to /login carrying where they were going,
 * and only ever carries a path on this origin.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const tokenService = inject(TokenService);
  const router = inject(Router);

  if (tokenService.isAuthenticated()) {
    return true;
  }

  return router.createUrlTree(['/login'], {
    queryParams: { returnUrl: safeReturnUrl(state.url, '/') },
  });
};
