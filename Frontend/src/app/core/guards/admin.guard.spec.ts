import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { adminGuard } from './admin.guard';
import { TokenService } from '../services/token.service';

describe('adminGuard', () => {
  let tokens: jasmine.SpyObj<TokenService>;
  let router: Router;

  const run = (url: string) =>
    TestBed.runInInjectionContext(() =>
      adminGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );

  beforeEach(() => {
    tokens = jasmine.createSpyObj('TokenService', ['isAuthenticated', 'isAdmin']);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: TokenService, useValue: tokens }],
    });
    router = TestBed.inject(Router);
  });

  it('sends a signed-out visitor to sign-in carrying the admin page they asked for', () => {
    tokens.isAuthenticated.and.returnValue(false);

    const result = run('/admin/approvals') as UrlTree;

    expect(router.serializeUrl(result)).toBe('/login?returnUrl=%2Fadmin%2Fapprovals');
  });

  it('lets an administrator in', () => {
    tokens.isAuthenticated.and.returnValue(true);
    tokens.isAdmin.and.returnValue(true);

    expect(run('/admin/overview')).toBeTrue();
  });

  it('sends a signed-in trader to their dashboard rather than the admin area', () => {
    tokens.isAuthenticated.and.returnValue(true);
    tokens.isAdmin.and.returnValue(false);

    const result = run('/admin/users') as UrlTree;

    expect(router.serializeUrl(result)).toBe('/dashboard');
  });

  it('never carries an off-origin return address', () => {
    tokens.isAuthenticated.and.returnValue(false);

    const result = run('https://evil.example.com/admin') as UrlTree;

    expect(router.serializeUrl(result)).not.toContain('evil');
  });
});
