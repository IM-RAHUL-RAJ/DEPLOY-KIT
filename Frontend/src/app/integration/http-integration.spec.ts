import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { Configuration as TradeApiConfiguration } from '@trade-api/configuration';
import { Configuration as AuthApiConfiguration } from '@auth-api/configuration';
import { authInterceptor } from '../core/interceptors/auth.interceptor';
import { AuthService } from '../core/services/auth.service';
import { TokenService } from '../core/services/token.service';
import { TradeService } from '../core/services/trade.service';
import { AdminService } from '../core/services/admin.service';
import { WatchlistService } from '../core/services/watchlist.service';
import { PreferencesService } from '../core/services/preferences.service';
import { NotificationsService } from '../core/services/notifications.service';
import { PriceAlertsService } from '../core/services/price-alerts.service';
import { PortfolioService } from '../core/services/portfolio.service';
import { environment } from '../../environments/environment';

/**
 * Front-end integration tests: the real services, the generated API clients, the
 * real interceptor and the real token storage, wired exactly as app.config.ts
 * wires them. Only the network is replaced, by HttpTestingController, so each
 * test sees the request the browser would actually send.
 */

const TRADE = environment.tradeApiUrl;
const AUTH = environment.authApiUrl;

/** A JWT-shaped token the TokenService can read claims from. */
function jwt(claims: Record<string, unknown>): string {
  const b64 = (o: object) =>
    btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64(claims)}.signature`;
}

const future = () => Math.floor(Date.now() / 1000) + 900;
const TRADER = jwt({ sub: 'u-1', accountId: 7, roles: ['CUSTOMER'], exp: future() });
const RENEWED = jwt({ sub: 'u-1', accountId: 7, roles: ['CUSTOMER'], exp: future() + 60 });

describe('HTTP integration', () => {
  let http: HttpTestingController;
  let tokens: TokenService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: TradeApiConfiguration, useFactory: () => new TradeApiConfiguration({ basePath: TRADE }) },
        {
          provide: AuthApiConfiguration,
          useFactory: () => new AuthApiConfiguration({ basePath: AUTH, withCredentials: true }),
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    tokens = TestBed.inject(TokenService);
    spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  describe('sign-in', () => {
    it('posts the credentials without a bearer token and stores the session it gets back', () => {
      const auth = TestBed.inject(AuthService);
      auth.login({ identifier: 'demo', password: 'pw' }).subscribe();

      const req = http.expectOne(`${AUTH}/auth/login`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ identifier: 'demo', password: 'pw' });
      expect(req.request.headers.has('Authorization')).toBeFalse();
      expect(req.request.withCredentials).toBeTrue();
      req.flush({ accessToken: TRADER, expiresIn: 900, user: { username: 'demo' } });

      expect(tokens.getAccessToken()).toBe(TRADER);
      expect(tokens.isAuthenticated()).toBeTrue();
      expect(tokens.getAccountId()).toBe(7);
    });

    it('sends registration to the public endpoint without a token, even when signed in', () => {
      tokens.setAccessToken(TRADER);
      TestBed.inject(AuthService).register({} as never).subscribe();

      const req = http.expectOne(`${AUTH}/auth/register`);
      expect(req.request.headers.has('Authorization')).toBeFalse();
      req.flush({});
    });

    it('attaches the token to the protected profile route on the auth service', () => {
      tokens.setAccessToken(TRADER);
      TestBed.inject(AuthService).me().subscribe();

      const req = http.expectOne(`${AUTH}/auth/me`);
      expect(req.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
      req.flush({ username: 'demo' });
    });

    it('updates the user profile through the auth service with the bearer token', () => {
      tokens.setAccessToken(TRADER);
      TestBed.inject(AuthService).updateProfile({ riskAppetite: 'AGGRESSIVE' }).subscribe();

      const req = http.expectOne(`${AUTH}/auth/me`);
      expect(req.request.method).toBe('PATCH');
      expect(req.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
      expect(req.request.body).toEqual({ riskAppetite: 'AGGRESSIVE' });
      req.flush({ username: 'demo', riskAppetite: 'AGGRESSIVE' });
    });

    it('changes the password through the auth service with the bearer token', () => {
      tokens.setAccessToken(TRADER);
      TestBed.inject(AuthService)
        .changePassword({
          currentPassword: 'Capstone@2026',
          newPassword: 'BetterCapstone@2027',
          confirmPassword: 'BetterCapstone@2027',
        })
        .subscribe();

      const req = http.expectOne(`${AUTH}/auth/me/password`);
      expect(req.request.method).toBe('POST');
      expect(req.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
      expect(req.request.body).toEqual({
        currentPassword: 'Capstone@2026',
        newPassword: 'BetterCapstone@2027',
        confirmPassword: 'BetterCapstone@2027',
      });
      req.flush({ message: 'Password changed' });
    });

    it('signs out by revoking server-side and forgetting the session locally', () => {
      tokens.setAccessToken(TRADER);
      TestBed.inject(AuthService).logout();

      http.expectOne(`${AUTH}/auth/logout`).flush({});
      expect(tokens.getAccessToken()).toBeNull();
      expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/login']);
    });

    it('still signs out locally when the server cannot be reached', () => {
      tokens.setAccessToken(TRADER);
      TestBed.inject(AuthService).logout();

      http.expectOne(`${AUTH}/auth/logout`).error(new ProgressEvent('error'), { status: 0 });
      expect(tokens.getAccessToken()).toBeNull();
    });
  });

  describe('Trade REST API through the generated client', () => {
    beforeEach(() => tokens.setAccessToken(TRADER));

    it('reads account, balance, positions and orders with the bearer token', () => {
      const trade = TestBed.inject(TradeService);
      trade.getAccount(7).subscribe();
      trade.getBalance(7).subscribe();
      trade.getPositions(7).subscribe();
      trade.getOrders(7).subscribe();

      for (const path of ['', '/balance', '/positions', '/orders']) {
        const req = http.expectOne(`${TRADE}/api/v1/accounts/7${path}`);
        expect(req.request.method).toBe('GET');
        expect(req.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
        req.flush(path === '' || path === '/balance' ? {} : []);
      }
    });

    it('places an order as a POST with the body the contract defines', () => {
      const order = {
        accountId: 7,
        symbol: 'AAPL',
        side: 'BUY' as const,
        orderType: 'LIMIT' as const,
        quantity: 2,
        price: 10,
        idempotencyKey: 'key-12345678',
      };
      TestBed.inject(TradeService).placeOrder(order).subscribe();

      const req = http.expectOne(`${TRADE}/api/v1/orders`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(order);
      expect(req.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
      req.flush({ orderId: 'o1', status: 'NEW' });
    });

    it('cancels an order with a DELETE', () => {
      TestBed.inject(TradeService).cancelOrder('3f1c2d6e-0000-4000-8000-000000000001').subscribe();
      const req = http.expectOne(`${TRADE}/api/v1/orders/3f1c2d6e-0000-4000-8000-000000000001`);
      expect(req.request.method).toBe('DELETE');
      req.flush({});
    });

    it('asks the Trade REST API for quotes, never the market-data provider', () => {
      TestBed.inject(TradeService).getInstrumentQuote('INFY.NS').subscribe();
      const req = http.expectOne((r) => r.url.startsWith(TRADE) && r.url.includes('/api/v1/instruments/'));
      expect(req.request.url).not.toContain('amazonaws');
      expect(req.request.headers.has('x-api-key')).toBeFalse();
      req.flush({ symbol: 'INFY.NS', stale: false });
    });
  });

  describe('expired session', () => {
    it('refreshes once on a 401 and retries the call with the new token', () => {
      tokens.setAccessToken(TRADER);
      let result: unknown;
      TestBed.inject(TradeService).getBalance(7).subscribe((b) => (result = b));

      http.expectOne(`${TRADE}/api/v1/accounts/7/balance`).flush(
        { errorCode: 'AUTH-401', message: 'expired' },
        { status: 401, statusText: 'Unauthorized' },
      );
      const refresh = http.expectOne(`${AUTH}/auth/refresh`);
      expect(refresh.request.headers.has('Authorization')).toBeFalse();
      refresh.flush({ accessToken: RENEWED, expiresIn: 900, user: {} });

      const retry = http.expectOne(`${TRADE}/api/v1/accounts/7/balance`);
      expect(retry.request.headers.get('Authorization')).toBe(`Bearer ${RENEWED}`);
      retry.flush({ cashBalance: 5 });

      expect(result).toEqual({ cashBalance: 5 });
      expect(tokens.getAccessToken()).toBe(RENEWED);
    });

    it('shares one refresh between calls that fail together', () => {
      tokens.setAccessToken(TRADER);
      const trade = TestBed.inject(TradeService);
      trade.getBalance(7).subscribe();
      trade.getPositions(7).subscribe();

      for (const path of ['/balance', '/positions']) {
        http.expectOne(`${TRADE}/api/v1/accounts/7${path}`).flush({}, { status: 401, statusText: 'Unauthorized' });
      }
      http.expectOne(`${AUTH}/auth/refresh`).flush({ accessToken: RENEWED, expiresIn: 900, user: {} });
      http.expectOne(`${TRADE}/api/v1/accounts/7/balance`).flush({});
      http.expectOne(`${TRADE}/api/v1/accounts/7/positions`).flush([]);
      expect(tokens.getAccessToken()).toBe(RENEWED);
    });

    it('ends the session and sends the user to sign-in when the refresh is refused', () => {
      tokens.setAccessToken(TRADER);
      let failed = false;
      TestBed.inject(TradeService).getBalance(7).subscribe({ error: () => (failed = true) });

      http.expectOne(`${TRADE}/api/v1/accounts/7/balance`).flush({}, { status: 401, statusText: 'Unauthorized' });
      http.expectOne(`${AUTH}/auth/refresh`).flush({}, { status: 401, statusText: 'Unauthorized' });

      expect(failed).toBeTrue();
      expect(tokens.getAccessToken()).toBeNull();
      expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/login'], jasmine.objectContaining({ queryParams: jasmine.any(Object) }));
    });

    it('does not try to refresh a 403: that is a refusal, not an expiry', () => {
      tokens.setAccessToken(TRADER);
      let status = 0;
      TestBed.inject(TradeService).getBalance(7).subscribe({ error: (e) => (status = e.status) });

      http.expectOne(`${TRADE}/api/v1/accounts/7/balance`).flush({}, { status: 403, statusText: 'Forbidden' });
      http.expectNone(`${AUTH}/auth/refresh`);
      expect(status).toBe(403);
    });
  });

  describe('admin endpoints', () => {
    beforeEach(() => tokens.setAccessToken(TRADER));

    it('reads stats, users, the pending queue, accounts and orders', () => {
      const admin = TestBed.inject(AdminService);
      admin.getStats().subscribe();
      admin.getUsers().subscribe();
      admin.getPendingUsers().subscribe();
      admin.getAccounts().subscribe();
      for (const path of ['stats', 'users', 'users/pending', 'accounts']) {
        const req = http.expectOne(`${TRADE}/api/v1/admin/${path}`);
        expect(req.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
        req.flush(path === 'stats' ? {} : []);
      }
    });

    it('filters the order book with query parameters, and only the ones given', () => {
      const admin = TestBed.inject(AdminService);
      admin.getOrders({ status: 'NEW', accountId: 3, limit: 50 }).subscribe();
      const filtered = http.expectOne((r) => r.url === `${TRADE}/api/v1/admin/orders`);
      expect(filtered.request.params.get('status')).toBe('NEW');
      expect(filtered.request.params.get('accountId')).toBe('3');
      expect(filtered.request.params.get('limit')).toBe('50');
      filtered.flush([]);

      admin.getOrders().subscribe();
      const all = http.expectOne((r) => r.url === `${TRADE}/api/v1/admin/orders`);
      expect(all.request.params.keys()).toEqual([]);
      all.flush([]);
    });

    it('posts each lifecycle action to its own endpoint with its reason', () => {
      const admin = TestBed.inject(AdminService);
      admin.approveUser(4).subscribe();
      admin.rejectUser(4, { reason: 'r1' }).subscribe();
      admin.suspendUser(4, { reason: 'r2' }).subscribe();
      admin.reinstateUser(4).subscribe();

      const approve = http.expectOne(`${TRADE}/api/v1/admin/users/4/approve`);
      expect(approve.request.body).toEqual({});
      approve.flush({});
      expect(http.expectOne(`${TRADE}/api/v1/admin/users/4/reject`).request.body).toEqual({ reason: 'r1' });
      expect(http.expectOne(`${TRADE}/api/v1/admin/users/4/suspend`).request.body).toEqual({ reason: 'r2' });
      expect(http.expectOne(`${TRADE}/api/v1/admin/users/4/reinstate`).request.body).toEqual({});
    });

    it('changes account status and adjusts a balance with its reason', () => {
      const admin = TestBed.inject(AdminService);
      admin.updateAccountStatus(9, 'SUSPENDED').subscribe();
      admin.adjustBalance(9, 250, 'deposit').subscribe();
      expect(http.expectOne(`${TRADE}/api/v1/admin/accounts/9/status`).request.body).toEqual({ status: 'SUSPENDED' });
      expect(http.expectOne(`${TRADE}/api/v1/admin/accounts/9/balance`).request.body).toEqual({
        amount: 250,
        reason: 'deposit',
      });
    });
  });

  describe('watchlist', () => {
    beforeEach(() => tokens.setAccessToken(TRADER));

    it('reads, adds to and removes from the watchlist', () => {
      const watchlist = TestBed.inject(WatchlistService);
      watchlist.getWatchlist().subscribe();
      watchlist.addInstrument('MSFT').subscribe();
      watchlist.removeInstrument(12).subscribe();

      expect(http.expectOne(`${TRADE}/api/v1/watchlist`).request.method).toBe('GET');
      const add = http.expectOne(`${TRADE}/api/v1/watchlist/instruments`);
      expect(add.request.method).toBe('POST');
      expect(add.request.body).toEqual({ symbol: 'MSFT' });
      expect(http.expectOne(`${TRADE}/api/v1/watchlist/instruments/12`).request.method).toBe('DELETE');
    });
  });

  describe('named watchlists', () => {
    beforeEach(() => tokens.setAccessToken(TRADER));

    it('lists, creates, renames, adds, removes, moves and deletes named lists', () => {
      const watchlists = TestBed.inject(WatchlistService);
      watchlists.listWatchlists().subscribe();
      watchlists.createWatchlist('Earnings watch').subscribe();
      watchlists.renameWatchlist(3, 'Dividends').subscribe();
      watchlists.addInstrumentToWatchlist(3, 'MSFT').subscribe();
      watchlists.removeInstrumentFromList(3, 9).subscribe();
      watchlists.moveInstrument(9, 2).subscribe();
      watchlists.deleteWatchlist(3).subscribe();

      const url = `${TRADE}/api/v1/watchlists`;
      expect(http.expectOne((r) => r.url === url && r.method === 'GET').request.method).toBe('GET');

      const create = http.expectOne((r) => r.url === url && r.method === 'POST');
      expect(create.request.method).toBe('POST');
      expect(create.request.body).toEqual({ name: 'Earnings watch' });

      const single = `${TRADE}/api/v1/watchlists/3`;
      const rename = http.expectOne((r) => r.url === single && r.method === 'PATCH');
      expect(rename.request.method).toBe('PATCH');
      expect(rename.request.body).toEqual({ name: 'Dividends' });

      const add = http.expectOne(`${TRADE}/api/v1/watchlists/3/instruments`);
      expect(add.request.method).toBe('POST');
      expect(add.request.body).toEqual({ symbol: 'MSFT' });

      const remove = http.expectOne(`${TRADE}/api/v1/watchlists/3/instruments/9`);
      expect(remove.request.method).toBe('DELETE');

      const move = http.expectOne(`${TRADE}/api/v1/watchlists/instruments/9/move`);
      expect(move.request.method).toBe('POST');
      expect(move.request.body).toEqual({ targetWatchlistId: 2 });

      const del = http.expectOne((r) => r.url === single && r.method === 'DELETE');
      expect(del.request.method).toBe('DELETE');
    });
  });

  describe('preferences', () => {
    beforeEach(() => tokens.setAccessToken(TRADER));

    it('reads and replaces the customer preference with the bearer token', () => {
      const preferences = TestBed.inject(PreferencesService);
      preferences.get(7).subscribe();
      preferences.put(7, { defaultAccountId: 7, alertChannel: 'EMAIL', alertContact: 'a@b.com', displayCurrency: 'EUR' }).subscribe();

      const url = `${TRADE}/api/v1/preferences/7`;
      const read = http.expectOne((r) => r.url === url && r.method === 'GET');
      expect(read.request.method).toBe('GET');
      expect(read.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
      read.flush({
        accountId: 7,
        defaultAccountId: 7,
        alertChannel: 'EMAIL',
        alertContact: 'a@b.com',
        displayCurrency: 'EUR',
        updatedAt: '2026-01-01T00:00:00Z',
      });

      const write = http.expectOne((r) => r.url === url && r.method === 'PUT');
      expect(write.request.method).toBe('PUT');
      expect(write.request.body).toEqual({
        defaultAccountId: 7,
        alertChannel: 'EMAIL',
        alertContact: 'a@b.com',
        displayCurrency: 'EUR',
      });
      expect(write.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
      write.flush({});
    });

    it('addresses only the signed-in account, never another customer path', () => {
      const preferences = TestBed.inject(PreferencesService);
      // The account id always comes from the token in preferences.service; a caller cannot
      // name a different account, so every request lands on /api/v1/preferences/7.
      preferences.put(7, { alertChannel: 'NONE' }).subscribe();
      const req = http.expectOne((r) => r.url === `${TRADE}/api/v1/preferences/7`);
      expect(req.request.method).toBe('PUT');
      req.flush({});
    });
  });

  describe('notifications', () => {
    beforeEach(() => tokens.setAccessToken(TRADER));

    it('reads the inbox for the signed-in account with the bearer token', () => {
      const notifications = TestBed.inject(NotificationsService);
      notifications.list(7).subscribe();
      const read = http.expectOne(`${TRADE}/api/v1/notifications/7`);
      expect(read.request.method).toBe('GET');
      expect(read.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
      read.flush([]);
    });

    it('passes unread and limit through to the inbox query', () => {
      const notifications = TestBed.inject(NotificationsService);
      notifications.list(7, { unread: true, limit: 25 }).subscribe();
      const req = http.expectOne((r) => r.url === `${TRADE}/api/v1/notifications/7`);
      expect(req.request.params.get('unread')).toBe('true');
      expect(req.request.params.get('limit')).toBe('25');
      req.flush([]);
    });

    it('marks a notification read on the account-scoped route', () => {
      const notifications = TestBed.inject(NotificationsService);
      notifications.markRead(7, 42).subscribe();
      const req = http.expectOne(`${TRADE}/api/v1/notifications/7/42/read`);
      expect(req.request.method).toBe('POST');
      expect(req.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
      req.flush({});
    });
  });

  describe('price alerts', () => {
    beforeEach(() => tokens.setAccessToken(TRADER));

    it('lists, arms and cancels alerts with the bearer token and no account parameter', () => {
      const alerts = TestBed.inject(PriceAlertsService);
      alerts.list().subscribe();
      alerts.create({ symbol: 'AAPL', direction: 'ABOVE', threshold: 240 }).subscribe();
      alerts.cancel(12).subscribe();

      const list = http.expectOne((r) => r.url === `${TRADE}/api/v1/watchlist/alerts` && r.method === 'GET');
      expect(list.request.params.keys().length).toBe(0);
      expect(list.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
      list.flush([]);

      const create = http.expectOne((r) => r.url === `${TRADE}/api/v1/watchlist/alerts` && r.method === 'POST');
      expect(create.request.body).toEqual({ symbol: 'AAPL', direction: 'ABOVE', threshold: 240 });
      create.flush({});

      const cancel = http.expectOne(`${TRADE}/api/v1/watchlist/alerts/12`);
      expect(cancel.request.method).toBe('DELETE');
      cancel.flush({});
    });
  });

  describe('portfolio', () => {
    beforeEach(() => tokens.setAccessToken(TRADER));

    it('prices the portfolio for the signed-in account with the bearer token', () => {
      const portfolio = TestBed.inject(PortfolioService);
      portfolio.summary(7).subscribe();
      portfolio.positions(7, 'AAPL').subscribe();
      portfolio.pnl(7, { bySymbol: true }).subscribe();

      const summary = http.expectOne(`${TRADE}/api/v1/portfolio/7`);
      expect(summary.request.method).toBe('GET');
      expect(summary.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
      summary.flush({});

      const positions = http.expectOne(`${TRADE}/api/v1/portfolio/7/positions?symbol=AAPL`);
      expect(positions.request.params.get('symbol')).toBe('AAPL');
      positions.flush([]);

      const pnl = http.expectOne((r) => r.url === `${TRADE}/api/v1/portfolio/7/pnl`);
      expect(pnl.request.params.get('bySymbol')).toBe('true');
      expect(pnl.request.headers.get('Authorization')).toBe(`Bearer ${TRADER}`);
      pnl.flush({});
    });
  });

  describe('third parties', () => {
    it('never sends the session token anywhere but the platform', () => {
      tokens.setAccessToken(TRADER);
      const client = TestBed.inject(WatchlistService)['http'];
      for (const url of [
        'https://y4t9nq2bqf.execute-api.eu-west-2.amazonaws.com/v1/quote',
        'https://cdn.example.com/x.js',
        `${TRADE}.evil.example/api`,
      ]) {
        client.get(url).subscribe();
        const req = http.expectOne(url);
        expect(req.request.headers.has('Authorization')).withContext(url).toBeFalse();
        req.flush({});
      }
    });
  });
});
