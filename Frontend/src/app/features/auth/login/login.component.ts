import {
  ChangeDetectionStrategy,
   Component, signal, inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { passwordProblems } from './password-rules';
import { Router, ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService } from '../../../core/services/auth.service';
import { TokenService } from '../../../core/services/token.service';
import { resolveErrorMessage } from '../../../core/services/error-catalogue';
import { safeReturnUrl } from '../../../core/guards/return-url';
import { CloudMarkComponent } from '../../../shared/cloud-mark.component';
import { AuthShowcaseComponent } from '../auth-showcase.component';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink, CloudMarkComponent, AuthShowcaseComponent],
  templateUrl: './login.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly tokens = inject(TokenService);

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly successMessage = signal<string | null>(null);
  readonly showPassword = signal(false);
  readonly capsLock = signal(false);

  /** Warn when Caps Lock is on: the commonest reason a right password is refused. */
  checkCaps(event: KeyboardEvent): void {
    this.capsLock.set(!!event.getModifierState?.('CapsLock'));
  }

  readonly form = this.fb.group({
    username: ['', [Validators.required]],
    password: ['', [Validators.required]],
  });

  // ----- forgot password: email a code, then the code and a new password ----------
  /** Which card is showing: sign in, ask for a code, or set the new password. */
  readonly mode = signal<'login' | 'forgot' | 'reset'>('login');
  readonly resetBusy = signal(false);
  readonly resetError = signal<string | null>(null);
  readonly resetNotice = signal<string | null>(null);

  readonly forgotForm = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
  });

  readonly resetForm = this.fb.group({
    code: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
    password: ['', [Validators.required]],
    confirmPassword: ['', [Validators.required]],
  });

  /** What the new password still needs, in the auth service's own rules. */
  newPasswordProblems(): string[] {
    const { password, confirmPassword } = this.resetForm.value;
    return passwordProblems(password ?? '', confirmPassword ?? '');
  }

  openForgot(): void {
    const typed = (this.form.value.username ?? '').trim();
    if (typed.includes('@') && !this.forgotForm.value.email) {
      this.forgotForm.patchValue({ email: typed });
    }
    this.resetError.set(null);
    this.resetNotice.set(null);
    this.mode.set('forgot');
  }

  backToLogin(): void {
    this.resetError.set(null);
    this.mode.set('login');
  }

  requestCode(): void {
    if (this.forgotForm.invalid || this.resetBusy()) {
      this.forgotForm.markAllAsTouched();
      return;
    }
    this.resetBusy.set(true);
    this.resetError.set(null);
    const email = this.forgotForm.value.email!.trim();
    this.auth.requestPasswordReset(email).subscribe({
      next: (res) => {
        this.resetBusy.set(false);
        // The same answer whether or not the address is registered: nothing to enumerate.
        this.resetNotice.set(res?.message || `If ${email} belongs to an active account, a code is on its way.`);
        this.mode.set('reset');
      },
      error: (err: HttpErrorResponse) => {
        this.resetBusy.set(false);
        this.resetError.set(err.error?.message && typeof err.error.message === 'string'
          ? err.error.message
          : resolveErrorMessage(err.error?.errorCode, err.status));
      },
    });
  }

  resetPassword(): void {
    if (this.resetForm.invalid || this.newPasswordProblems().length || this.resetBusy()) {
      this.resetForm.markAllAsTouched();
      return;
    }
    this.resetBusy.set(true);
    this.resetError.set(null);
    const { code, password, confirmPassword } = this.resetForm.value;
    const email = this.forgotForm.value.email!.trim();
    this.auth.resetPassword({ email, code: code!, password: password!, confirmPassword: confirmPassword! }).subscribe({
      next: () => {
        this.resetBusy.set(false);
        this.resetForm.reset();
        this.form.patchValue({ username: email, password: '' });
        this.successMessage.set('Your password has been changed. Sign in with the new one.');
        this.mode.set('login');
      },
      error: (err: HttpErrorResponse) => {
        this.resetBusy.set(false);
        const message = err.error?.message;
        this.resetError.set(Array.isArray(message) ? message.join(' ') : typeof message === 'string' && message
          ? message
          : resolveErrorMessage(err.error?.errorCode, err.status));
      },
    });
  }

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    // Arriving straight from the OTP step: the account exists but is not usable
    // yet, so say why the sign-in that follows will fail until it is approved.
    if (params.get('verified') === '1') {
      const email = params.get('email');
      this.successMessage.set(
        'Your email is confirmed and your account has been created. An administrator ' +
          'must approve it before you can sign in' +
          (email ? ` (${email}).` : '.')
      );
    }
  }

  submit(): void {
    if (this.form.invalid || this.loading()) return;
    this.loading.set(true);
    this.errorMessage.set(null);

    const { username, password } = this.form.value;
    this.auth.login({ identifier: username!, password: password! }).subscribe({
      next: () => {
        // Only a path on this origin is followed (open-redirect prevention).
        const requested = this.route.snapshot.queryParamMap.get('returnUrl');
        if (this.tokens.isAdmin()) {
          const adminReturn = safeReturnUrl(requested, '/admin');
          this.router.navigateByUrl(adminReturn.startsWith('/admin') ? adminReturn : '/admin');
          return;
        }
        this.router.navigateByUrl(safeReturnUrl(requested, '/dashboard'));
      },
      error: (err: HttpErrorResponse) => {
        const code = err.error?.errorCode;
        this.errorMessage.set(resolveErrorMessage(code, err.status));
        this.loading.set(false);
      },
    });
  }
}