import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import type { RiskAppetite, UserResponse } from '@auth-api/model/models';
import { AuthService } from '../../core/services/auth.service';
import { AccountStateService } from '../../core/services/account-state.service';
import { TokenService } from '../../core/services/token.service';
import { TradeService } from '../../core/services/trade.service';
import {
  PreferencesService,
  type NotificationPreferencesResponse,
  type PreferenceResponse,
} from '../../core/services/preferences.service';
import { ProfileComponent } from './profile.component';

const NOTIFICATIONS: NotificationPreferencesResponse = {
  accountId: 42,
  preferences: [
    { category: 'ACCOUNT_RELATED', channel: 'INHERIT', contact: null, updatedAt: null },
    { category: 'ORDER_RELATED', channel: 'EMAIL', contact: 'a@b.com', updatedAt: null },
    { category: 'PRICE_RELATED', channel: 'PUSH', contact: 'Laptop', updatedAt: null },
  ],
};

function buildUser(overrides: Partial<UserResponse> = {}): UserResponse {
  const base: UserResponse = {
    userId: 1,
    uuid: '11111111-1111-1111-1111-111111111111',
    firstName: 'Priya',
    middleName: 'K',
    lastName: 'Menon',
    username: 'priya.menon',
    email: 'priya@example.com',
    roles: ['CUSTOMER' as any],
    accountId: 42,
    status: 'ACTIVE' as any,
    country: 'India',
    phoneCountryCode: '+91',
    phoneNumber: '9876543210',
    riskAppetite: 'BALANCED' as RiskAppetite,
  };
  return { ...base, ...overrides };
}

describe('ProfileComponent', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let trade: jasmine.SpyObj<TradeService>;
  let preferences: jasmine.SpyObj<PreferencesService>;

  function createFixture() {
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['me', 'updateProfile', 'changePassword', 'logout']);
    auth.me.and.returnValue(of(buildUser()));
    auth.updateProfile.and.returnValue(of(buildUser({ riskAppetite: 'AGGRESSIVE' as RiskAppetite })));
    auth.changePassword.and.returnValue(of({ message: 'Password changed' }));

    const tokens = jasmine.createSpyObj('TokenService', ['getAccountId', 'getUsername', 'getUserRoles']);
    tokens.getAccountId.and.returnValue(42);
    tokens.getUsername.and.returnValue('priya.menon');
    tokens.getUserRoles.and.returnValue(['TRADER']);

    trade = jasmine.createSpyObj('TradeService', ['getAccount']);
    trade.getAccount.and.returnValue(
      of({
        id: 42,
        accountId: 'ACC-42',
        holderName: 'Akatsuki Trader',
        cashBalance: 125000,
        currency: 'INR',
        status: 'ACTIVE',
        version: 1,
        lastUpdated: '2026-10-05T09:00:00Z',
      }),
    );

    preferences = jasmine.createSpyObj('PreferencesService', ['getNotifications']);
    preferences.getNotifications.and.returnValue(of(NOTIFICATIONS));

    const accountState = {
      preference: () =>
        ({
          accountId: 42,
          defaultAccountId: 42,
          alertChannel: 'NONE',
          alertContact: null,
          displayCurrency: null,
          updatedAt: null,
        }) as PreferenceResponse,
      preferenceLoaded: () => false,
      loadPreference: jasmine.createSpy('loadPreference'),
      retryPreference: jasmine.createSpy('retryPreference'),
    };

    TestBed.configureTestingModule({
      imports: [ProfileComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
        { provide: TokenService, useValue: tokens },
        { provide: TradeService, useValue: trade },
        { provide: PreferencesService, useValue: preferences },
        { provide: AccountStateService, useValue: accountState },
      ],
    });
  });

  it('shows the account, the session and their per-type delivery', () => {
    const fixture = createFixture();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Akatsuki Trader');
    expect(el.textContent).toContain('INR');
    expect(el.textContent).toContain('priya.menon');
    expect(el.textContent).toContain('Trader');
    expect(el.textContent).toContain('Order Related');
    expect(el.textContent).toContain('Email');
    expect(el.textContent).toContain('In-app');
    fixture.destroy();
  });

  it('keeps the identity on screen when the per-type summary fails to load', () => {
    preferences.getNotifications.and.returnValue(throwError(() => new Error('x')));
    const fixture = createFixture();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Akatsuki Trader');
    expect(fixture.componentInstance.delivery().length).toBe(0);
    fixture.destroy();
  });

  it('reports when the account cannot be loaded', () => {
    trade.getAccount.and.returnValue(throwError(() => new Error('x')));
    const fixture = createFixture();
    expect(fixture.componentInstance.accountError()).toContain('could not be loaded');
    fixture.destroy();
  });

  it('loads and masks the account number by default', () => {
    const fixture = createFixture();
    const component = fixture.componentInstance;
    expect(auth.me).toHaveBeenCalled();
    expect(component.visibleAccountNumber()).toBe('••••0042');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('priya.menon');
    fixture.destroy();
  });

  it('reveals the account number when toggled', () => {
    const fixture = createFixture();
    const component = fixture.componentInstance;
    component.toggleAccountMask();
    expect(component.visibleAccountNumber()).toBe('00000042');
    fixture.destroy();
  });

  it('saves a changed risk appetite', () => {
    const fixture = createFixture();
    const component = fixture.componentInstance;
    component.profileForm.controls.riskAppetite.setValue('AGGRESSIVE');
    component.saveProfile();
    expect(auth.updateProfile).toHaveBeenCalledWith({ riskAppetite: 'AGGRESSIVE' });
    expect(component.profile()?.riskAppetite).toBe('AGGRESSIVE');
    fixture.destroy();
  });

  it('has no password section, and picks a risk profile from the tiles', () => {
    const fixture = createFixture();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent?.toLowerCase()).not.toContain('password');
    fixture.componentInstance.chooseRisk('CONSERVATIVE');
    expect(fixture.componentInstance.profileForm.controls.riskAppetite.value).toBe('CONSERVATIVE');
    expect(fixture.componentInstance.selectedRisk()).toBe('CONSERVATIVE');
    expect(fixture.componentInstance.initials().length).toBeGreaterThan(0);
    expect(fixture.componentInstance.fullName()).toBeTruthy();
    fixture.destroy();
  });

  describe('fallbacks', () => {
    function tokens(): jasmine.SpyObj<TokenService> {
      return TestBed.inject(TokenService) as jasmine.SpyObj<TokenService>;
    }

    it('names an anonymous session Trader, with no account number and nothing to load', () => {
      tokens().getAccountId.and.returnValue(null as unknown as number);
      tokens().getUsername.and.returnValue(null);
      tokens().getUserRoles.and.returnValue([]);
      auth.me.and.returnValue(of(buildUser({ firstName: undefined, middleName: undefined, lastName: undefined,
        accountId: undefined, riskAppetite: undefined } as never)));
      const c = createFixture().componentInstance;
      expect(c.fullName()).toBe('Trader');
      expect(c.initials()).toBe('?');
      expect(c.roleLabel()).toBe('Trader');
      expect(c.visibleAccountNumber()).toBe('Unavailable');
      expect(c.selectedRisk()).toBe('');
      expect(trade.getAccount).not.toHaveBeenCalled();
      expect(preferences.getNotifications).not.toHaveBeenCalled();
    });

    it('labels unknown roles, defaults the ledger to USD and a missing category to Follow default', () => {
      tokens().getUserRoles.and.returnValue(['RISK_DESK']);
      trade.getAccount.and.returnValue(of({ id: 42, currency: undefined } as any));
      preferences.getNotifications.and.returnValue(of({ accountId: 42, preferences: [] }));
      const c = createFixture().componentInstance;
      expect(c.roleLabel()).toBe('RISK DESK');
      expect(c.delivery().every((d) => d.channel === 'INHERIT')).toBeTrue();
    });

    it('shows the chosen display currency, and treats a missing channel as not delivered', () => {
      const state = TestBed.inject(AccountStateService) as unknown as { preference: () => unknown };
      state.preference = () => ({ alertChannel: undefined, displayCurrency: 'EUR' });
      const c = createFixture().componentInstance;
      expect(c.display()).toBe('EUR (chosen in Settings)');
      expect(c.defaultChannelLabel()).toBe('Not delivered');
      expect(c.deliveryNote({ channel: 'INHERIT' })).toBe('Not delivered');
      state.preference = () => null;
      expect(c.deliveryNote({ channel: 'INHERIT' })).toBe('Not delivered');
    });

    it('does not save twice while a save is on its way', () => {
      const c = createFixture().componentInstance;
      c.savingProfile.set(true);
      c.saveProfile();
      expect(auth.updateProfile).not.toHaveBeenCalled();
    });
  });
});
