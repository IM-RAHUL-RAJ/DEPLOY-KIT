import { TestBed } from '@angular/core/testing';
import type { Route } from '@angular/router';
import { routes } from './app.routes';
import { appConfig } from './app.config';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';
import { Configuration as TradeApiConfiguration } from '@trade-api/configuration';
import { Configuration as AuthApiConfiguration } from '@auth-api/configuration';
import { environment } from '../environments/environment';

/** Every route in the table, with the guards inherited from its parents. */
function flatten(list: Route[], prefix = '', inherited: unknown[] = []): { path: string; route: Route; guards: unknown[] }[] {
  return list.flatMap((route) => {
    const path = [prefix, route.path].filter((p) => p !== undefined && p !== '').join('/');
    const guards = [...inherited, ...(route.canActivate ?? [])];
    return [{ path, route, guards }, ...flatten(route.children ?? [], path, guards)];
  });
}

describe('route table', () => {
  const all = flatten(routes);

  it('leaves only sign-in and registration open', () => {
    const open = all
      .filter((r) => r.route.loadComponent && r.guards.length === 0)
      .map((r) => r.path);
    expect(open.sort()).toEqual(['login', 'register']);
  });

  it('guards every trader page with the auth guard', () => {
    for (const path of ['dashboard', 'order', 'blotter', 'holdings', 'portfolio', 'advice', 'automated-trading', 'profile', 'watchlist', 'notifications', 'alerts', 'settings']) {
      const entry = all.find((r) => r.path === path)!;
      expect(entry).withContext(path).toBeDefined();
      expect(entry.guards).withContext(path).toContain(authGuard);
    }
  });

  it('guards every admin page with the admin guard', () => {
    const admin = all.filter((r) => r.path.startsWith('admin/') && r.route.loadComponent);
    expect(admin.map((r) => r.path).sort()).toEqual([
      'admin/accounts',
      'admin/approvals',
      'admin/orders',
      'admin/overview',
      'admin/users',
    ]);
    for (const r of admin) expect(r.guards).withContext(r.path).toContain(adminGuard);
  });

  it('no longer has an instruments page', () => {
    expect(all.some((r) => r.path.includes('instruments'))).toBeFalse();
  });

  it('sends an unknown address to the dashboard', () => {
    expect(routes.at(-1)).toEqual({ path: '**', redirectTo: 'dashboard' });
  });

  it('resolves every lazy page to a component', async () => {
    for (const r of all.filter((x) => x.route.loadComponent)) {
      const component = await (r.route.loadComponent as () => Promise<unknown>)();
      expect(component).withContext(r.path).toBeTruthy();
    }
  });
});

describe('application config', () => {
  it('points both generated clients at the configured services', () => {
    TestBed.configureTestingModule({ providers: appConfig.providers });
    expect(TestBed.inject(TradeApiConfiguration).basePath).toBe(environment.tradeApiUrl);
    const auth = TestBed.inject(AuthApiConfiguration);
    expect(auth.basePath).toBe(environment.authApiUrl);
    expect(auth.withCredentials).toBeTrue();
  });

  it('only ever sends the token to the two platform origins', () => {
    expect(environment.platformOrigins).toEqual([
      new URL(environment.tradeApiUrl).origin,
      new URL(environment.authApiUrl).origin,
    ]);
  });
});
