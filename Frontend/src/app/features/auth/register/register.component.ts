import {
  ChangeDetectionStrategy,
   Component, computed, inject, signal
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { startWith } from 'rxjs';
import { CommonModule } from '@angular/common';
import {
  AbstractControl,
  ReactiveFormsModule,
  FormBuilder,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService } from '../../../core/services/auth.service';
import { resolveErrorMessage } from '../../../core/services/error-catalogue';
import { CountryCatalogService, RISK_APPETITES } from '../../../core/services/country-catalog.service';
import type { RiskAppetite } from '@auth-api/model/models';
import { CloudMarkComponent } from '../../../shared/cloud-mark.component';
import { AuthShowcaseComponent } from '../auth-showcase.component';

const USERNAME_PATTERN = /^[a-zA-Z0-9._-]+$/;
const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[^a-zA-Z0-9]).+$/;
const PHONE_PATTERN = /^[0-9]{4,15}$/;

const passwordsMatch: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
  const password = group.get('password')?.value;
  const confirm = group.get('confirmPassword')?.value;
  if (password && confirm && password !== confirm) {
    return { passwordsMismatch: true };
  }
  return null;
};

/** Which of the two screens is showing. */
type Step = 'profile' | 'verify';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink, CloudMarkComponent, AuthShowcaseComponent],
  templateUrl: './register.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './register.component.scss',
})
export class RegisterComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);
  private readonly catalog = inject(CountryCatalogService);

  readonly countries = this.catalog.sortedCountries;
  readonly riskAppetites = RISK_APPETITES;

  readonly step = signal<Step>('profile');
  readonly loading = signal(false);
  readonly resending = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly infoMessage = signal<string | null>(null);
  readonly emailInUse = signal<string | null>(null);
  readonly codeSentTo = signal<string | null>(null);
  readonly emailDelivered = signal(true);
  readonly showPassword = signal(false);

  /** Profile, then the emailed code. The code is its own group so it never blocks step 1. */
  readonly form = this.fb.group(
    {
      firstName: ['', [Validators.required, Validators.maxLength(100)]],
      middleName: ['', [Validators.maxLength(100)]],
      lastName: ['', [Validators.required, Validators.maxLength(100)]],
      username: [
        '',
        [
          Validators.required,
          Validators.minLength(3),
          Validators.maxLength(50),
          Validators.pattern(USERNAME_PATTERN),
        ],
      ],
      email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
      password: [
        '',
        [
          Validators.required,
          Validators.minLength(12),
          Validators.maxLength(128),
          Validators.pattern(PASSWORD_PATTERN),
        ],
      ],
      confirmPassword: ['', [Validators.required]],
      countryCode: ['', [Validators.required]],
      phoneCountryCode: ['', [Validators.required]],
      phoneNumber: [
        '',
        [Validators.required, Validators.pattern(PHONE_PATTERN)],
      ],
      riskAppetite: ['', [Validators.required]],
      code: ['', [Validators.required, Validators.pattern(/^[0-9]{6}$/)]],
    },
    { validators: passwordsMatch }
  );

  /**
   * The selected country drives the dial code and the currency preview.
   *
   * The code is bridged into a signal first: a Reactive Form is not a signal
   * source, so a computed reading `countryCode.value` directly would keep showing
   * the country chosen at construction.
   */
  private readonly countryCode = toSignal(
    this.form.controls.countryCode.valueChanges.pipe(startWith('')),
    { initialValue: '' },
  );

  readonly selectedCountry = computed(() => this.catalog.find(this.countryCode()));

  private readonly passwordValue = toSignal(this.form.controls.password.valueChanges, { initialValue: '' });

  /** The password rules, live: the same ones the server enforces. */
  readonly strength = computed(() => {
    const value = this.passwordValue() ?? '';
    const rules = [
      { text: '12+ characters', ok: value.length >= 12 },
      { text: 'Lowercase', ok: /[a-z]/.test(value) },
      { text: 'Uppercase', ok: /[A-Z]/.test(value) },
      { text: 'Number', ok: /[0-9]/.test(value) },
      { text: 'Symbol', ok: /[^a-zA-Z0-9]/.test(value) },
    ];
    const score = rules.filter((r) => r.ok).length;
    const label = score <= 2 ? 'Weak' : score <= 4 ? 'Almost there' : 'Strong';
    return { score, label, rules };
  });

  /**
   * The dial code is chosen by the country, not typed by the applicant, so the
   * phone field cannot disagree with the country. Selecting a new country
   * overwrites it.
   */
  onCountryChange(): void {
    const country = this.catalog.find(this.form.controls.countryCode.value);
    if (country) {
      this.form.controls.phoneCountryCode.setValue(country.dialCode);
    }
  }

  /**
   * Validity of step 1 only.
   *
   * `code` shares the group but belongs to step 2 and is empty on arrival, so
   * `form.invalid` would keep the Continue button disabled forever. Every other
   * control plus the group-level match check are what gate step 1.
   */
  profileInvalid(): boolean {
    if (this.form.hasError('passwordsMismatch')) return true;
    const { code, ...profile } = this.form.controls;
    void code;
    return Object.values(profile).some((control) => control.invalid);
  }

  /** Step 1: validate the profile, email the code, then move to verification. */
  submitProfile(): void {
    if (this.profileInvalid() || this.loading()) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading.set(true);
    this.errorMessage.set(null);
    this.infoMessage.set(null);

    const value = this.form.getRawValue();
    this.auth
      .register({
        firstName: value.firstName!,
        middleName: value.middleName ? value.middleName : undefined,
        lastName: value.lastName!,
        username: value.username!,
        email: value.email!,
        password: value.password!,
        confirmPassword: value.confirmPassword!,
        countryCode: value.countryCode!,
        phoneCountryCode: value.phoneCountryCode!,
        phoneNumber: value.phoneNumber!,
        riskAppetite: value.riskAppetite as RiskAppetite,
      })
      .subscribe({
        next: (res) => {
          // The account is only created once the code is confirmed, so nothing
          // can be signed into between these two steps.
          this.emailDelivered.set(res.emailDelivered);
          this.codeSentTo.set(res.email);
          this.infoMessage.set(res.message);
          this.form.controls.code.setValue('');
          this.step.set('verify');
          this.loading.set(false);
        },
        error: (err: HttpErrorResponse) => {
          if (err.error?.errorCode === 'AUTH-409') {
            this.emailInUse.set(value.email!);
          }
          this.errorMessage.set(resolveErrorMessage(err.error?.errorCode, err.status));
          this.loading.set(false);
        },
      });
  }

  /** Step 2: confirm the code. The server creates the pending user and account. */
  submitCode(): void {
    if (this.form.controls.code.invalid || this.loading()) {
      this.form.controls.code.markAsTouched();
      return;
    }
    this.loading.set(true);
    this.errorMessage.set(null);

    this.auth
      .verifyRegistration({
        email: this.codeSentTo() ?? this.form.controls.email.value!,
        code: this.form.controls.code.value!,
      })
      .subscribe({
        next: (res) => {
          // Verified, but not yet approved: send them to sign in and explain
          // that the account is waiting on an administrator.
          this.router.navigate(['/login'], {
            queryParams: { verified: '1', email: res.user.email },
          });
        },
        error: (err: HttpErrorResponse) => {
          this.errorMessage.set(resolveErrorMessage(err.error?.errorCode, err.status));
          this.loading.set(false);
        },
      });
  }

  /** A fresh code replaces the old one; the previous code stops working. */
  resendCode(): void {
    if (this.resending()) {
      return;
    }
    this.resending.set(true);
    this.errorMessage.set(null);
    this.infoMessage.set(null);

    const email = this.codeSentTo() ?? this.form.controls.email.value!;
    this.auth.resendRegistrationCode({ email }).subscribe({
      next: (res) => {
        this.emailDelivered.set(res.emailDelivered);
        this.infoMessage.set(res.message);
        this.form.controls.code.setValue('');
        this.resending.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage.set(resolveErrorMessage(err.error?.errorCode, err.status));
        this.resending.set(false);
      },
    });
  }

  /** Back to the profile screen to correct a field and start again. */
  editProfile(): void {
    this.step.set('profile');
    this.errorMessage.set(null);
    this.infoMessage.set(null);
    this.form.controls.code.setValue('');
  }
}