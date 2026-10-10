import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { authInterceptor } from './auth.interceptor';
import { TokenService } from '../services/token.service';
import { AuthService } from '../services/auth.service';
import { environment } from '../../../environments/environment';

describe('authInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  let tokenService: jasmine.SpyObj<TokenService>;
  let authService: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    tokenService = jasmine.createSpyObj('TokenService', ['getAccessToken']);
    tokenService.getAccessToken.and.returnValue('test-jwt-token');
    authService = jasmine.createSpyObj('AuthService', ['refresh', 'expireSession']);

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: TokenService, useValue: tokenService },
        { provide: AuthService, useValue: authService },
      ],
    });

    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  function authorizationFor(url: string): string | null {
    http.get(url).subscribe({ error: () => undefined });
    const req = controller.expectOne(url);
    const header = req.request.headers.get('Authorization');
    req.flush({});
    return header;
  }

  it('attaches the Authorization header to a platform API request', () => {
    expect(authorizationFor(`${environment.tradeApiUrl}/api/v1/accounts/1`)).toBe('Bearer test-jwt-token');
  });

  it('does not attach the Authorization header to a third-party origin request', () => {
    expect(authorizationFor('https://third-party-api.example.com/quotes')).toBeNull();
  });

  it('does not attach the Authorization header to a host that merely starts with a platform origin', () => {
    expect(authorizationFor(`${environment.authApiUrl}.evil.example/steal`)).toBeNull();
    expect(authorizationFor(`${environment.authApiUrl}1/steal`)).toBeNull();
  });

  it('does not attach the Authorization header to an unauthenticated auth route', () => {
    expect(authorizationFor(`${environment.authApiUrl}/auth/login`)).toBeNull();
    expect(authorizationFor(`${environment.authApiUrl}/auth/register/verify`)).toBeNull();
    expect(authorizationFor(`${environment.authApiUrl}/auth/refresh`)).toBeNull();
  });

  it('attaches the Authorization header to the protected auth route', () => {
    expect(authorizationFor(`${environment.authApiUrl}/auth/me`)).toBe('Bearer test-jwt-token');
  });

  it('refreshes once on a 401 and replays the request with the new token', () => {
    authService.refresh.and.returnValue(of('renewed-token'));
    const url = `${environment.tradeApiUrl}/api/v1/accounts/1`;
    let body: unknown;
    http.get(url).subscribe((b) => (body = b));

    controller.expectOne(url).flush({}, { status: 401, statusText: 'Unauthorized' });
    const retry = controller.expectOne(url);
    expect(retry.request.headers.get('Authorization')).toBe('Bearer renewed-token');
    retry.flush({ ok: true });

    expect(body).toEqual({ ok: true });
    expect(authService.expireSession).not.toHaveBeenCalled();
  });

  it('ends the session when the refresh is refused', () => {
    authService.refresh.and.returnValue(of(null));
    const url = `${environment.tradeApiUrl}/api/v1/accounts/1`;
    let failed = false;
    http.get(url).subscribe({ error: () => (failed = true) });

    controller.expectOne(url).flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBeTrue();
    expect(authService.expireSession).toHaveBeenCalled();
  });
});
