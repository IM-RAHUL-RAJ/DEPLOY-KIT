import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { RegisterComponent } from './register.component';
import { AuthService } from '../../../core/services/auth.service';

/* eslint-disable @typescript-eslint/no-explicit-any -- partial API responses in fixtures */
describe('RegisterComponent', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let router: Router;

  function create(): RegisterComponent {
    const fixture = TestBed.createComponent(RegisterComponent);
    fixture.detectChanges();
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    return fixture.componentInstance;
  }

  function fillProfile(c: RegisterComponent, overrides: Record<string, string> = {}): void {
    c.form.patchValue({
      firstName: 'Ada',
      lastName: 'Lovelace',
      username: 'ada.l',
      email: 'ada@example.com',
      password: 'Str0ng!Passw0rd',
      confirmPassword: 'Str0ng!Passw0rd',
      countryCode: 'IN',
      phoneNumber: '9876543210',
      riskAppetite: 'BALANCED',
      ...overrides,
    });
    c.onCountryChange();
  }

  beforeEach(() => {
    auth = jasmine.createSpyObj('AuthService', ['register', 'verifyRegistration', 'resendRegistrationCode']);
    TestBed.configureTestingModule({
      imports: [RegisterComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
  });

  describe('profile step', () => {
    it('lists countries alphabetically', () => {
      const c = create();
      const names = c.countries.map((x) => x.name);
      expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b)));
    });

    it('sets the dial code and currency from the country', () => {
      const c = create();
      fillProfile(c);
      expect(c.form.controls.phoneCountryCode.value).toBe('+91');
      expect(c.selectedCountry()?.currency).toBe('INR');
    });

    it('is invalid until every profile field is valid, and ignores the code field', () => {
      const c = create();
      expect(c.profileInvalid()).toBeTrue();
      fillProfile(c);
      expect(c.form.controls.code.invalid).toBeTrue();
      expect(c.profileInvalid()).toBeFalse();
    });

    it('refuses mismatched passwords', () => {
      const c = create();
      fillProfile(c, { confirmPassword: 'Different!Pass1' });
      expect(c.profileInvalid()).toBeTrue();
    });

    it('refuses a weak password and a bad username', () => {
      const c = create();
      fillProfile(c, { password: 'short', confirmPassword: 'short' });
      expect(c.form.controls.password.invalid).toBeTrue();
      fillProfile(c, { username: 'no spaces allowed' });
      expect(c.form.controls.username.invalid).toBeTrue();
    });

    it('does not send an invalid profile and marks the fields', () => {
      const c = create();
      c.submitProfile();
      expect(auth.register).not.toHaveBeenCalled();
      expect(c.form.controls.firstName.touched).toBeTrue();
    });

    it('sends the profile and moves to the code step', () => {
      auth.register.and.returnValue(
        of({ email: 'ada@example.com', emailDelivered: true, message: 'Code sent' } as any),
      );
      const c = create();
      fillProfile(c, { middleName: '' });
      c.submitProfile();

      const sent = auth.register.calls.mostRecent().args[0];
      expect(sent.middleName).toBeUndefined();
      expect(sent.countryCode).toBe('IN');
      expect(c.step()).toBe('verify');
      expect(c.codeSentTo()).toBe('ada@example.com');
      expect(c.infoMessage()).toBe('Code sent');
    });

    it('flags an email already in use', () => {
      auth.register.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 409, error: { errorCode: 'AUTH-409' } })),
      );
      const c = create();
      fillProfile(c);
      c.submitProfile();
      expect(c.emailInUse()).toBe('ada@example.com');
      expect(c.errorMessage()).toBeTruthy();
      expect(c.step()).toBe('profile');
    });
  });

  describe('code step', () => {
    function atVerify(): RegisterComponent {
      auth.register.and.returnValue(of({ email: 'ada@example.com', emailDelivered: false, message: 'm' } as any));
      const c = create();
      fillProfile(c);
      c.submitProfile();
      return c;
    }

    it('says when the code could not be emailed', () => {
      expect(atVerify().emailDelivered()).toBeFalse();
    });

    it('refuses a code that is not six digits', () => {
      const c = atVerify();
      c.form.controls.code.setValue('12ab');
      c.submitCode();
      expect(auth.verifyRegistration).not.toHaveBeenCalled();
    });

    it('confirms the code and sends the applicant to sign-in to wait for approval', () => {
      auth.verifyRegistration.and.returnValue(of({ user: { email: 'ada@example.com' } } as any));
      const c = atVerify();
      c.form.controls.code.setValue('123456');
      c.submitCode();

      expect(auth.verifyRegistration).toHaveBeenCalledWith({ email: 'ada@example.com', code: '123456' });
      expect(router.navigate).toHaveBeenCalledWith(['/login'], {
        queryParams: { verified: '1', email: 'ada@example.com' },
      });
    });

    it('shows a readable error for a wrong code', () => {
      auth.verifyRegistration.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 422, error: { errorCode: 'VAL-422' } })),
      );
      const c = atVerify();
      c.form.controls.code.setValue('000000');
      c.submitCode();
      expect(c.errorMessage()).toBeTruthy();
      expect(c.loading()).toBeFalse();
    });

    it('resends a code, and reports a failed resend', () => {
      auth.resendRegistrationCode.and.returnValue(of({ emailDelivered: true, message: 'Sent again' } as any));
      const c = atVerify();
      c.resendCode();
      expect(auth.resendRegistrationCode).toHaveBeenCalledWith({ email: 'ada@example.com' });
      expect(c.infoMessage()).toBe('Sent again');
      expect(c.emailDelivered()).toBeTrue();

      auth.resendRegistrationCode.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
      c.resendCode();
      expect(c.errorMessage()).toBeTruthy();
      expect(c.resending()).toBeFalse();
    });

    it('goes back to the profile to correct a field', () => {
      const c = atVerify();
      c.form.controls.code.setValue('123456');
      c.editProfile();
      expect(c.step()).toBe('profile');
      expect(c.form.controls.code.value).toBe('');
    });
  });

  it('rates the password against the same five rules the server enforces', () => {
    const c = create();
    c.form.controls.password.setValue('abc');
    expect(c.strength().score).toBe(1);
    expect(c.strength().label).toBe('Weak');
    c.form.controls.password.setValue('Abcdefgh1');
    expect(c.strength().score).toBe(3);
    expect(c.strength().label).toBe('Almost there');
    c.form.controls.password.setValue('Abcdefgh12!x');
    expect(c.strength().score).toBe(5);
    expect(c.strength().label).toBe('Strong');
    expect(c.strength().rules.every((r) => r.ok)).toBeTrue();
  });
});
