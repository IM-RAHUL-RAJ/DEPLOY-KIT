import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { LoginComponent } from './login.component';
import { passwordProblems } from './password-rules';
import type { AuthResponse } from '@auth-api/model/models';
import { AuthService } from '../../../core/services/auth.service';
import { TokenService } from '../../../core/services/token.service';

describe('LoginComponent', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let tokens: jasmine.SpyObj<TokenService>;
  let router: Router;

  function create(query: Record<string, string> = {}): LoginComponent {
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { snapshot: { queryParamMap: convertToParamMap(query) } },
    });
    const component = TestBed.createComponent(LoginComponent).componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    return component;
  }

  function signIn(component: LoginComponent): void {
    component.form.setValue({ username: 'demo', password: 'secret' });
    component.submit();
  }

  beforeEach(() => {
    auth = jasmine.createSpyObj('AuthService', ['login', 'requestPasswordReset', 'resetPassword']);
    tokens = jasmine.createSpyObj('TokenService', ['isAdmin']);
    tokens.isAdmin.and.returnValue(false);
    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
        { provide: TokenService, useValue: tokens },
      ],
    });
  });

  it('does not call the auth service while the form is empty', () => {
    const component = create();
    component.submit();
    expect(auth.login).not.toHaveBeenCalled();
  });

  it('sends the username as the identifier', () => {
    auth.login.and.returnValue(of({} as AuthResponse));
    signIn(create());
    expect(auth.login).toHaveBeenCalledWith({ identifier: 'demo', password: 'secret' });
  });

  it('takes a trader to the dashboard by default', () => {
    auth.login.and.returnValue(of({} as AuthResponse));
    signIn(create());
    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
  });

  it('takes a trader back to where they were going', () => {
    auth.login.and.returnValue(of({} as AuthResponse));
    signIn(create({ returnUrl: '/blotter' }));
    expect(router.navigateByUrl).toHaveBeenCalledWith('/blotter');
  });

  it('refuses an off-origin return address (open redirect)', () => {
    auth.login.and.returnValue(of({} as AuthResponse));
    signIn(create({ returnUrl: 'https://evil.example.com' }));
    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
  });

  it('takes an administrator to the admin area, and only to an admin return address', () => {
    tokens.isAdmin.and.returnValue(true);
    auth.login.and.returnValue(of({} as AuthResponse));
    signIn(create({ returnUrl: '/admin/approvals' }));
    expect(router.navigateByUrl).toHaveBeenCalledWith('/admin/approvals');
  });

  it('does not send an administrator to a trader page', () => {
    tokens.isAdmin.and.returnValue(true);
    auth.login.and.returnValue(of({} as AuthResponse));
    signIn(create({ returnUrl: '/blotter' }));
    expect(router.navigateByUrl).toHaveBeenCalledWith('/admin');
  });

  it('shows a readable message for a refused sign-in and stays on the page', () => {
    auth.login.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 401, error: { errorCode: 'AUTH-401', message: 'x' } })),
    );
    const component = create();
    signIn(component);

    expect(component.errorMessage()).toBeTruthy();
    expect(component.errorMessage()).not.toBe('x');
    expect(component.loading()).toBeFalse();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('says the service is unreachable when the request never arrives', () => {
    auth.login.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
    const component = create();
    signIn(component);
    expect(component.errorMessage()).toBeTruthy();
  });

  it('tells a newly verified applicant that approval is still needed', () => {
    const component = create({ verified: '1', email: 'a@example.com' });
    expect(component.successMessage()).toContain('approve');
    expect(component.successMessage()).toContain('a@example.com');
  });

  it('shows and hides the password, and warns about Caps Lock', () => {
    const component = create();
    expect(component.showPassword()).toBeFalse();
    component.showPassword.set(true);
    expect(component.showPassword()).toBeTrue();
    component.checkCaps({ getModifierState: (k: string) => k === 'CapsLock' } as unknown as KeyboardEvent);
    expect(component.capsLock()).toBeTrue();
    component.checkCaps({ getModifierState: () => false } as unknown as KeyboardEvent);
    expect(component.capsLock()).toBeFalse();
  });

  describe('forgot password', () => {
    it('opens with the typed email, asks for a code and moves to the reset step', () => {
      const c = create();
      c.form.patchValue({ username: 'demo@example.com' });
      c.openForgot();
      expect(c.mode()).toBe('forgot');
      expect(c.forgotForm.value.email).toBe('demo@example.com');

      auth.requestPasswordReset.and.returnValue(of({ message: 'A code has been sent.' }));
      c.requestCode();
      expect(auth.requestPasswordReset).toHaveBeenCalledWith('demo@example.com');
      expect(c.mode()).toBe('reset');
      expect(c.resetNotice()).toBe('A code has been sent.');
    });

    it('needs a valid email, and shows a refusal', () => {
      const c = create();
      c.openForgot();
      c.requestCode();
      expect(auth.requestPasswordReset).not.toHaveBeenCalled();
      c.forgotForm.setValue({ email: 'x@y.co' });
      auth.requestPasswordReset.and.returnValue(throwError(() => new HttpErrorResponse({ status: 429, error: { message: 'Too many requests' } })));
      c.requestCode();
      expect(c.resetError()).toBe('Too many requests');
      expect(c.mode()).toBe('forgot');
      auth.requestPasswordReset.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
      c.requestCode();
      expect(c.resetError()).toBeTruthy();
      c.backToLogin();
      expect(c.mode()).toBe('login');
    });

    it('changes the password with the code and returns to sign in', () => {
      const c = create();
      c.forgotForm.setValue({ email: 'x@y.co' });
      c.mode.set('reset');
      c.resetForm.setValue({ code: '123456', password: 'weak', confirmPassword: 'weak' });
      c.resetPassword();
      expect(auth.resetPassword).not.toHaveBeenCalled();

      c.resetForm.setValue({ code: '123456', password: 'Capstone@2026!', confirmPassword: 'Capstone@2026!' });
      auth.resetPassword.and.returnValue(throwError(() => new HttpErrorResponse({ status: 400, error: { message: ['code is wrong', 'try again'] } })));
      c.resetPassword();
      expect(c.resetError()).toBe('code is wrong try again');

      auth.resetPassword.and.returnValue(of({ message: 'ok' }));
      c.resetPassword();
      expect(auth.resetPassword).toHaveBeenCalledWith({
        email: 'x@y.co', code: '123456', password: 'Capstone@2026!', confirmPassword: 'Capstone@2026!',
      });
      expect(c.mode()).toBe('login');
      expect(c.form.value.username).toBe('x@y.co');
      expect(c.successMessage()).toContain('password has been changed');

      auth.resetPassword.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
      c.resetForm.setValue({ code: '123456', password: 'Capstone@2026!', confirmPassword: 'Capstone@2026!' });
      c.resetPassword();
      expect(c.resetError()).toBeTruthy();
    });

    it('renders each step', () => {
      const fixture = TestBed.createComponent(LoginComponent);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      (el.querySelector('[data-testid="forgot-link"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(el.querySelector('[data-testid="reset-email"]')).toBeTruthy();
      fixture.componentInstance.mode.set('reset');
      fixture.componentInstance.resetForm.patchValue({ password: 'short' });
      fixture.detectChanges();
      expect(el.querySelector('[data-testid="reset-code"]')).toBeTruthy();
      expect(el.textContent).toContain('At least 12 characters');
    });

    it('lists what a new password still needs', () => {
      expect(passwordProblems('Capstone@2026!', 'Capstone@2026!')).toEqual([]);
      expect(passwordProblems('abc', 'abd')).toEqual([
        'At least 12 characters', 'An uppercase letter', 'A digit', 'A special character', 'Both passwords must match',
      ]);
      expect(passwordProblems('A'.repeat(73), '')).toContain('At most 72 characters');
      expect(passwordProblems('ABCDEFGHIJKL1!', '')).toContain('A lowercase letter');
    });
  });
});
