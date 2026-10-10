import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { TokenService } from '../services/token.service';
import { safeReturnUrl } from './return-url';

/**
 * Guard for the administrator area. A usability control backed by the
 * server-side ADMIN role check on every /api/v1/admin request; it keeps
 * client users out of the admin routes and sends signed-out visitors to login,
 * carrying the admin page they asked for.
 */
export const adminGuard: CanActivateFn = (_route, state) => {
  const tokens = inject(TokenService);
  const router = inject(Router);

  if (!tokens.isAuthenticated()) {
    return router.createUrlTree(['/login'], {
      queryParams: { returnUrl: safeReturnUrl(state.url, '/admin') },
    });
  }
  if (tokens.isAdmin()) {
    return true;
  }
  return router.createUrlTree(['/dashboard']);
};
