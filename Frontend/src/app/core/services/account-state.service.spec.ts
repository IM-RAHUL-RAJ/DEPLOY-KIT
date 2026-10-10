import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AccountStateService } from './account-state.service';
import { TokenService } from './token.service';
import { TradeService } from './trade.service';
import { CurrencyService } from './currency.service';
import { PreferencesService, type PreferenceResponse } from './preferences.service';
import { fakeTrade } from '../../testing/fake-trade';
import { ThemeService } from './theme.service';

const PREF: (overrides?: Partial<PreferenceResponse>) => PreferenceResponse = (overrides = {}) => ({
  accountId: 7,
  defaultAccountId: 7,
  alertChannel: 'EMAIL',
  alertContact: 'trader@example.com',
  displayCurrency: 'EUR',
  updatedAt: '2026-01-01T00:00:00Z',
  ...overrides,
});

describe('AccountStateService', () => {
  let state: AccountStateService;
  let currency: CurrencyService;
  let tokens: jasmine.SpyObj<TokenService>;
  let trade: jasmine.SpyObj<TradeService>;
  let preferences: jasmine.SpyObj<PreferencesService>;

  function user(id: number) {
    tokens.getAccountId.and.returnValue(id);
  }

  beforeEach(() => {
    localStorage.clear();
    tokens = jasmine.createSpyObj('TokenService', ['getAccountId', 'getUsername']);
    user(7);
    trade = fakeTrade({ currency: 'INR' });
    preferences = jasmine.createSpyObj('PreferencesService', ['get', 'put', 'putTheme']);
    TestBed.configureTestingModule({
      providers: [
        { provide: TokenService, useValue: tokens },
        { provide: TradeService, useValue: trade },
        { provide: PreferencesService, useValue: preferences },
      ],
    });
    state = TestBed.inject(AccountStateService);
    currency = TestBed.inject(CurrencyService);
  });

  afterEach(() => localStorage.clear());

  it('loads the ledger currency and the stored preference once per account', () => {
    preferences.get.and.returnValue(of(PREF({ displayCurrency: 'GBP' })));

    state.fetchAccountCurrency();

    expect(currency.ledger()).toBe('INR');
    expect(state.accountId()).toBe(7);
    expect(preferences.get).toHaveBeenCalledWith(7);
    expect(state.preference()?.alertChannel).toBe('EMAIL');
    expect(currency.selected()).toBe('GBP');
  });

  it('lets the stored display currency win over the per-browser choice', () => {
    localStorage.setItem('akatsuki.displayCurrency.INR', 'USD');
    preferences.get.and.returnValue(of(PREF({ displayCurrency: 'EUR' })));

    state.fetchAccountCurrency();

    expect(currency.selected()).toBe('EUR');
  });

  describe('theme', () => {
    it('applies the stored theme when the preference is read', () => {
      preferences.get.and.returnValue(of(PREF({ theme: 'light' })));
      state.fetchAccountCurrency();
      expect(TestBed.inject(ThemeService).currentTheme()).toBe('light');
    });

    it('saves a theme the customer chooses to their preference', () => {
      preferences.get.and.returnValue(of(PREF()));
      preferences.putTheme.and.returnValue(of(PREF({ theme: 'light', updatedAt: '2026-02-02T00:00:00Z' })));
      state.fetchAccountCurrency();
      TestBed.inject(ThemeService).setTheme('light');
      expect(preferences.putTheme).toHaveBeenCalledWith(7, 'light');
      expect(state.preference()?.theme).toBe('light');
    });

    it('reports a theme that could not be saved, and saves nothing when signed out', () => {
      preferences.putTheme.and.returnValue(throwError(() => new Error('down')));
      state.saveTheme('dark');
      expect(state.preferenceSaveError()).toContain('theme');
      tokens.getAccountId.and.returnValue(null as unknown as number);
      preferences.putTheme.calls.reset();
      state.saveTheme('light');
      expect(preferences.putTheme).not.toHaveBeenCalled();
    });
  });

  it('does not fetch the preference twice for the same account', () => {
    preferences.get.and.returnValue(of(PREF()));

    state.fetchAccountCurrency();
    state.loadPreference();
    state.fetchAccountCurrency();

    expect(preferences.get).toHaveBeenCalledTimes(1);
  });

  it('notices when the stored default account is not the account in the token', () => {
    user(7);
    preferences.get.and.returnValue(of(PREF({ defaultAccountId: 11 })));

    state.fetchAccountCurrency();

    expect(state.accountNotice()).toContain('11');
    expect(state.accountNotice()).toContain('7');
  });

  it('stays quiet when the stored default account is the session account', () => {
    preferences.get.and.returnValue(of(PREF({ defaultAccountId: 7 })));

    state.fetchAccountCurrency();

    expect(state.accountNotice()).toBeNull();
  });

  it('marks a failed preference read so the screen can offer a retry', () => {
    preferences.get.and.returnValue(throwError(() => new Error('down')));

    state.fetchAccountCurrency();

    expect(state.preferenceLoaded()).toBeTrue();
    expect(state.preference()).toBeNull();
  });

  it('applies a saved preference and clears the previous refusal', () => {
    preferences.put.and.returnValue(of(PREF({ displayCurrency: 'JPY' })));

    state.savePreference({
      defaultAccountId: 7,
      alertChannel: 'EMAIL',
      alertContact: 'trader@example.com',
      displayCurrency: 'JPY',
    });

    expect(preferences.put).toHaveBeenCalledWith(7, {
      defaultAccountId: 7,
      alertChannel: 'EMAIL',
      alertContact: 'trader@example.com',
      displayCurrency: 'JPY',
    });
    expect(state.preference()?.displayCurrency).toBe('JPY');
    expect(currency.selected()).toBe('JPY');
    expect(state.preferenceSaveError()).toBeNull();
  });

  it('surfaces the service message when a save is refused', () => {
    preferences.put.and.returnValue(
      throwError(() => ({ status: 422, error: { errorCode: 'VAL-422', message: 'Channel does not match contact' } })),
    );

    state.savePreference({ alertChannel: 'EMAIL' });

    expect(state.preferenceSaveError()).toContain('Channel does not match contact');
  });

  it('applies the FX picker change locally and persists it against the stored preference', () => {
    preferences.get.and.returnValue(of(PREF()));
    preferences.put.and.returnValue(of(PREF({ displayCurrency: 'SGD' })));
    state.fetchAccountCurrency();

    state.selectDisplayCurrency('SGD');

    expect(currency.selected()).toBe('SGD');
    expect(preferences.put).toHaveBeenCalledWith(7, jasmine.objectContaining({ displayCurrency: 'SGD', alertChannel: 'EMAIL' }));
  });

  it('does not overwrite an unseen preference when the FX picker is used before the read finished', () => {
    preferences.get.and.returnValue(of(PREF()));
    preferences.put.and.returnValue(of(PREF()));

    // No fetchAccountCurrency - the trip says the preference has not been seen yet.
    state.selectDisplayCurrency('CHF');

    expect(currency.selected()).toBe('CHF');
    expect(preferences.put).not.toHaveBeenCalled();
  });
});