import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, of } from 'rxjs';
import { catchError, finalize, map, shareReplay, tap } from 'rxjs/operators';
import { AuthService as AuthApi } from '@auth-api/api/auth.service';
import { ProfileService as ProfileApi } from '@auth-api/api/profile.service';
import { TokenService } from './token.service';
import type {
  AuthResponse,
  LoginRequest,
  RiskAppetite,
  OtpDispatchResponse,
  RegisterRequest,
  RegistrationCompletedResponse,
  RegistrationStartedResponse,
  ResendOtpRequest,
  UserResponse,
  VerifyOtpRequest,
} from '@auth-api/model/models';
import { environment } from '../../../environments/environment';

interface UpdateProfileRequest {
  riskAppetite: RiskAppetite;
}

interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

interface MessageResponse {
  message: string;
}

/**
 * The application's view of the Auth service, over the client generated from
 * auth-api.yaml.
 *
 * Registration is email-verified and administrator-approved, so it is three calls
 * rather than one: start the registration and send the code, verify the code, and
 * only then does a pending user exist. No registration call returns a token.
 *
 * The refresh token never reaches JavaScript: the service sets it as an HttpOnly
 * cookie, and the generated client is configured with credentials so the browser
 * sends it back on refresh and logout.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(AuthApi);
  private readonly profile = inject(ProfileApi);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly tokens = inject(TokenService);

  /** One refresh at a time: concurrent 401s share the same exchange. */
  private refreshInFlight: Observable<string | null> | null = null;

  /** Step 1: validate the profile and email the six-digit code. */
  register(request: RegisterRequest): Observable<RegistrationStartedResponse> {
    return this.api.register(request);
  }

  /** Step 2: confirm the code. Creates the INACTIVE user and account. */
  verifyRegistration(request: VerifyOtpRequest): Observable<RegistrationCompletedResponse> {
    return this.api.verifyRegistration(request);
  }

  /** Asks for a fresh code; only the newest code can complete a registration. */
  resendRegistrationCode(request: ResendOtpRequest): Observable<OtpDispatchResponse> {
    return this.api.resendRegistrationCode(request);
  }

  login(credentials: LoginRequest): Observable<AuthResponse> {
    return this.api.login(credentials).pipe(tap((res) => this.store(res)));
  }

  me(): Observable<UserResponse> {
    return this.profile.getCurrentUser().pipe(tap((user) => this.tokens.setUser(user)));
  }

  updateProfile(request: UpdateProfileRequest): Observable<UserResponse> {
    return this.http
      .patch<UserResponse>(`${environment.authApiUrl}/auth/me`, request, { withCredentials: true })
      .pipe(tap((user) => this.tokens.setUser(user)));
  }

  /** Forgot password, step 1: email a six-digit code to a registered, active account. */
  requestPasswordReset(email: string): Observable<MessageResponse & { emailDelivered?: boolean }> {
    return this.http.post<MessageResponse & { emailDelivered?: boolean }>(
      `${environment.authApiUrl}/auth/password-reset/request`,
      { email },
    );
  }

  /** Forgot password, step 2: the code and the new password together. */
  resetPassword(request: { email: string; code: string; password: string; confirmPassword: string }): Observable<MessageResponse> {
    return this.http.post<MessageResponse>(`${environment.authApiUrl}/auth/password-reset/reset`, request);
  }

  changePassword(request: ChangePasswordRequest): Observable<MessageResponse> {
    return this.http.post<MessageResponse>(`${environment.authApiUrl}/auth/me/password`, request, {
      withCredentials: true,
    });
  }

  /**
   * Exchanges the refresh cookie for a new access token. Emits the new token, or
   * null when the session cannot be renewed (expired, revoked, or the user is no
   * longer active), in which case the local session is cleared.
   */
  refresh(): Observable<string | null> {
    this.refreshInFlight ??= this.api.refresh().pipe(
      tap((res) => this.store(res)),
      map((res) => res.accessToken),
      catchError(() => {
        this.tokens.clear();
        return of(null);
      }),
      finalize(() => (this.refreshInFlight = null)),
      shareReplay(1),
    );
    return this.refreshInFlight;
  }

  /** Revokes the refresh token server-side, then forgets the session locally. */
  logout(): void {
    this.api
      .logout()
      .pipe(catchError(() => of(null)))
      .subscribe(() => {
        this.tokens.clear();
        this.router.navigate(['/login']);
      });
  }

  /** Ends the local session without a server call, e.g. after a failed refresh. */
  expireSession(returnUrl: string): void {
    this.tokens.clear();
    this.router.navigate(['/login'], { queryParams: { returnUrl } });
  }

  private store(res: AuthResponse): void {
    this.tokens.setAccessToken(res.accessToken);
    this.tokens.setUser(res.user);
  }
}
