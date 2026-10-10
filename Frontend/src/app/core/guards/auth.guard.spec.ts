import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { authGuard } from './auth.guard';
import { TokenService } from '../services/token.service';

describe('authGuard', () => {
  let tokenService: jasmine.SpyObj<TokenService>;
  let router: Router;

  const route = {} as ActivatedRouteSnapshot;
  const stateFor = (url: string): RouterStateSnapshot => ({ url }) as RouterStateSnapshot;

  beforeEach(() => {
    tokenService = jasmine.createSpyObj('TokenService', ['isAuthenticated']);

    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: TokenService, useValue: tokenService }],
    });

    router = TestBed.inject(Router);
  });

  const run = (url: string) =>
    TestBed.runInInjectionContext(() => authGuard(route, stateFor(url)));

  it('blocks unauthenticated navigation and redirects to login carrying the return address', () => {
    tokenService.isAuthenticated.and.returnValue(false);

    const result = run('/blotter') as UrlTree;

    expect(result instanceof UrlTree).toBeTrue();
    expect(router.serializeUrl(result)).toBe('/login?returnUrl=%2Fblotter');
  });

  it('allows authenticated navigation', () => {
    tokenService.isAuthenticated.and.returnValue(true);

    expect(run('/dashboard')).toBe(true);
  });

  it('refuses an off-origin return address', () => {
    tokenService.isAuthenticated.and.returnValue(false);

    const result = run('https://evil.example.com') as UrlTree;

    expect(router.serializeUrl(result)).toBe('/login?returnUrl=%2F');
  });

  it('refuses a protocol-relative return address', () => {
    tokenService.isAuthenticated.and.returnValue(false);

    const result = run('//evil.example.com') as UrlTree;

    expect(router.serializeUrl(result)).toBe('/login?returnUrl=%2F');
  });
});
