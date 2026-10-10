import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ThemeService } from './theme.service';
import { CountryCatalogService, COUNTRIES, RISK_APPETITES } from './country-catalog.service';
import { AccountStateService } from './account-state.service';
import { TradeService } from './trade.service';
import { TokenService } from './token.service';
import { CurrencyService } from './currency.service';
import { AppComponent } from '../../app.component';
import type { AccountResponse } from '@trade-api/model/models';

describe('ThemeService', () => {
  it('starts dark, applies a change and hands a choice to the saver, never to the browser', () => {
    localStorage.clear();
    const theme = TestBed.inject(ThemeService);
    const saved: string[] = [];
    theme.registerSaver((t) => saved.push(t));
    expect(theme.currentTheme()).toBe('dark');
    theme.toggleTheme();
    TestBed.flushEffects();
    expect(theme.currentTheme()).toBe('light');
    expect(saved).toEqual(['light']);
    expect(localStorage.length).toBe(0);
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(document.body.classList).toContain('theme-light');
    theme.setTheme('dark');
    TestBed.flushEffects();
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('applies a stored answer without saving it, and reads anything unknown as dark', () => {
    const theme = TestBed.inject(ThemeService);
    const saved: string[] = [];
    theme.registerSaver((t) => saved.push(t));
    theme.apply('light');
    expect(theme.currentTheme()).toBe('light');
    theme.apply('purple');
    expect(theme.currentTheme()).toBe('dark');
    theme.apply(null);
    expect(theme.currentTheme()).toBe('dark');
    expect(saved).toEqual([]);
    theme.registerSaver(null);
    theme.toggleTheme();
    expect(theme.currentTheme()).toBe('light');
  });

  it('is started by the root component', () => {
    TestBed.configureTestingModule({ imports: [AppComponent], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('router-outlet')).toBeTruthy();
  });
});

describe('CountryCatalogService', () => {
  const catalog = () => TestBed.inject(CountryCatalogService);

  it('finds a country by code, whatever its case or spacing', () => {
    expect(catalog().find(' in ')?.name).toBe('India');
    expect(catalog().find('GB')?.currency).toBe('GBP');
  });

  it('finds nothing for an unknown or missing code', () => {
    expect(catalog().find('XX')).toBeUndefined();
    expect(catalog().find(null)).toBeUndefined();
    expect(catalog().find('')).toBeUndefined();
  });

  it('gives every country a dial code and a currency the display service knows', () => {
    const currency = TestBed.inject(CurrencyService);
    for (const c of COUNTRIES) {
      expect(c.dialCode).withContext(c.code).toMatch(/^\+\d+$/);
      expect(currency.options.some((o) => o.code === c.currency)).withContext(c.code).toBeTrue();
    }
  });

  it('offers three risk appetites', () => {
    expect(RISK_APPETITES.map((r) => r.value)).toEqual(['CONSERVATIVE', 'BALANCED', 'AGGRESSIVE']);
  });
});

describe('AccountStateService', () => {
  let trade: jasmine.SpyObj<TradeService>;
  let tokens: jasmine.SpyObj<TokenService>;

  beforeEach(() => {
    localStorage.clear();
    trade = jasmine.createSpyObj('TradeService', ['getAccount']);
    tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
    TestBed.configureTestingModule({
      providers: [
        { provide: TradeService, useValue: trade },
        { provide: TokenService, useValue: tokens },
      ],
    });
  });

  it('loads the account currency once per account and sets the ledger', () => {
    tokens.getAccountId.and.returnValue(3);
    trade.getAccount.and.returnValue(of({ currency: 'INR' } as AccountResponse));
    const state = TestBed.inject(AccountStateService);

    state.fetchAccountCurrency();
    state.fetchAccountCurrency();

    expect(trade.getAccount).toHaveBeenCalledTimes(1);
    expect(state.accountCurrency()).toBe('INR');
    expect(TestBed.inject(CurrencyService).ledger()).toBe('INR');
  });

  it('does nothing without an account', () => {
    tokens.getAccountId.and.returnValue(null);
    TestBed.inject(AccountStateService).fetchAccountCurrency();
    expect(trade.getAccount).not.toHaveBeenCalled();
  });

  it('falls back to USD when the account cannot be read', () => {
    tokens.getAccountId.and.returnValue(3);
    trade.getAccount.and.returnValue(throwError(() => new Error('x')));
    const state = TestBed.inject(AccountStateService);
    state.fetchAccountCurrency();
    expect(state.accountCurrency()).toBe('USD');
    expect(TestBed.inject(CurrencyService).ledger()).toBe('USD');
  });

  it('defaults a missing currency to USD', () => {
    tokens.getAccountId.and.returnValue(4);
    trade.getAccount.and.returnValue(of({} as AccountResponse));
    const state = TestBed.inject(AccountStateService);
    state.fetchAccountCurrency();
    expect(state.accountCurrency()).toBe('USD');
  });
});

describe('TokenService storage', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('stores and reads the user copy, and survives a corrupt one', () => {
    const tokens = TestBed.inject(TokenService);
    tokens.setUser({ username: 'demo' });
    expect(tokens.getUsername()).toBe('demo');
    localStorage.setItem('lb_user', '{not json');
    expect(tokens.getUser()).toBeNull();
    expect(tokens.getUsername()).toBeNull();
  });

  it('clears the session, including a refresh token left by an older build', () => {
    const tokens = TestBed.inject(TokenService);
    tokens.setAccessToken('a.b.c');
    tokens.setUser({ username: 'demo' });
    localStorage.setItem('lb_refresh_token', 'old');
    tokens.clear();
    expect(tokens.getAccessToken()).toBeNull();
    expect(tokens.getUser()).toBeNull();
    expect(localStorage.getItem('lb_refresh_token')).toBeNull();
  });

  it('has no account, roles or admin rights without a token', () => {
    const tokens = TestBed.inject(TokenService);
    expect(tokens.getAccountId()).toBeNull();
    expect(tokens.getUserRoles()).toEqual([]);
    expect(tokens.isAdmin()).toBeFalse();
    expect(tokens.isAuthenticated()).toBeFalse();
  });
});
