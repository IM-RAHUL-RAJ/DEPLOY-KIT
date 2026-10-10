import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { SettingsComponent } from './settings.component';
import { AccountStateService } from '../../core/services/account-state.service';
import { TokenService } from '../../core/services/token.service';
import { TradeService } from '../../core/services/trade.service';
import {
  PreferencesService,
  type NotificationPreferencesResponse,
  type PreferenceRequest,
  type PreferenceResponse,
} from '../../core/services/preferences.service';
import { fakeTrade } from '../../testing/fake-trade';

const UNSET: PreferenceResponse = {
  accountId: 7,
  defaultAccountId: null,
  alertChannel: 'NONE',
  alertContact: null,
  displayCurrency: null,
  updatedAt: null,
};

const NOTIFICATIONS: NotificationPreferencesResponse = {
  accountId: 7,
  preferences: [
    { category: 'ACCOUNT_RELATED', channel: 'INHERIT', contact: null, updatedAt: null },
    { category: 'ORDER_RELATED', channel: 'INHERIT', contact: null, updatedAt: null },
    { category: 'PRICE_RELATED', channel: 'EMAIL', contact: 'alerts@example.com', updatedAt: null },
  ],
};

describe('SettingsComponent', () => {
  let preferences: jasmine.SpyObj<PreferencesService>;

  function create() {
    const fixture = TestBed.createComponent(SettingsComponent);
    fixture.detectChanges();
    return fixture;
  }

  /** Flush the effects that hydrate the form from the account-state preference. */
  async function settle(fixture: ReturnType<typeof create>) {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    localStorage.clear();
    preferences = jasmine.createSpyObj('PreferencesService', [
      'get',
      'put',
      'getNotifications',
      'putNotifications',
      'putTheme',
    ]);
    preferences.get.and.returnValue(of(UNSET));
    preferences.put.and.returnValue(of({ ...UNSET, defaultAccountId: 7 }));
    preferences.getNotifications.and.returnValue(of(NOTIFICATIONS));
    preferences.putNotifications.and.returnValue(of(NOTIFICATIONS));
    const tokens = jasmine.createSpyObj('TokenService', ['getAccountId', 'getUsername']);
    tokens.getAccountId.and.returnValue(7);
    tokens.getUsername.and.returnValue('demo');
    TestBed.configureTestingModule({
      imports: [SettingsComponent],
      providers: [
        provideRouter([]),
        { provide: TokenService, useValue: tokens },
        { provide: TradeService, useValue: fakeTrade({ currency: 'INR' }) },
        { provide: PreferencesService, useValue: preferences },
      ],
    });
  });

  afterEach(() => localStorage.clear());

  it('shows a spinner until the preference has been read', () => {
    const pending = new Subject<PreferenceResponse>();
    preferences.get.and.returnValue(pending.asObservable());
    const fixture = create();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.spinner')).toBeTruthy();
    expect(el.textContent).toContain('Loading your preferences');
    pending.complete();
    fixture.destroy();
  });

  it('shows the preference cards when the preference has been read', async () => {
    const fixture = create();
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.settings-card').length).toBe(6);
    expect(el.textContent).toContain('Appearance');
    expect(el.textContent).toContain('Trading defaults');
    expect(el.textContent).toContain('Charts');
    expect(el.textContent).toContain('Default delivery');
    expect(el.textContent).toContain('Delivery by category');
    expect(el.textContent).toContain('Display currency');
    expect(el.textContent).not.toContain('Default account');
    fixture.destroy();
  });

  it('hydrates the form from a stored preference', async () => {
    preferences.get.and.returnValue(
      of({
        accountId: 7,
        defaultAccountId: 7,
        alertChannel: 'EMAIL',
        alertContact: 'trader@example.com',
        displayCurrency: 'EUR',
        updatedAt: '2026-01-01T00:00:00Z',
      }),
    );
    const fixture = create();
    await settle(fixture);
    expect(fixture.componentInstance.channel()).toBe('EMAIL');
    expect(fixture.componentInstance.contact()).toBe('trader@example.com');
    expect(fixture.componentInstance.displayCurrency()).toBe('EUR');
    fixture.destroy();
  });

  it('hydrates each delivery category from its stored answer', async () => {
    const fixture = create();
    await settle(fixture);
    const c = fixture.componentInstance;
    const priceAlert = c.categories().find((r) => r.categoryLevel === 'PRICE_RELATED')!;
    expect(priceAlert.channel).toBe('EMAIL');
    expect(priceAlert.contact).toBe('alerts@example.com');
    const orderFilled = c.categories().find((r) => r.categoryLevel === 'ORDER_RELATED')!;
    expect(orderFilled.channel).toBe('INHERIT');
    expect(c.categories().length).toBe(3);
    fixture.destroy();
  });

  it('shows the effective global channel next to a Follow default row', async () => {
    preferences.get.and.returnValue(
      of({ ...UNSET, alertChannel: 'EMAIL', alertContact: 'trader@example.com' }),
    );
    const fixture = create();
    await settle(fixture);
    const c = fixture.componentInstance;
    const row = c.categories().find((r) => r.categoryLevel === 'ORDER_RELATED')!;
    expect(row.channel).toBe('INHERIT');
    expect(c.deliveryNote(row)).toBe('→ Email');
    fixture.destroy();
  });

  it('hides the contact field while No alerts is chosen', async () => {
    const fixture = create();
    await settle(fixture);
    expect(fixture.nativeElement.querySelector('#alert-contact')).toBeNull();
    fixture.destroy();
  });

  it('offers in-app, email or both - no SMS - and asks for an email only when email is involved', async () => {
    const fixture = create();
    await settle(fixture);
    const c = fixture.componentInstance;
    const text = () => fixture.nativeElement.textContent as string;
    expect(c.channels.map((ch) => ch.value)).toEqual(['PUSH', 'EMAIL', 'BOTH', 'NONE']);
    expect(text()).not.toContain('SMS');

    c.chooseChannel('PUSH');
    fixture.detectChanges();
    expect(text()).toMatch(/In-app is a toast/);
    expect(fixture.nativeElement.querySelector('#alert-contact')).toBeNull();
    expect(c.contactProblem()).toBeNull();

    c.chooseChannel('BOTH');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#alert-contact')).toBeTruthy();
    expect(c.contactProblem()).toMatch(/email address is required/);
    fixture.destroy();
  });

  it('saves the theme to the preference straight away', async () => {
    preferences.putTheme.and.returnValue(of({ ...UNSET, theme: 'light' }));
    const fixture = create();
    await settle(fixture);
    const buttons = fixture.nativeElement.querySelectorAll('[aria-label="Theme"] button');
    (buttons[1] as HTMLButtonElement).click();
    expect(preferences.putTheme).toHaveBeenCalledWith(7, 'light');
    expect(localStorage.getItem('trading_app_theme')).toBeNull();
    fixture.componentInstance.theme.apply('dark');
    fixture.destroy();
  });

  it('shows the contact field for a reachable channel and validates it before saving', async () => {
    const fixture = create();
    await settle(fixture);
    const c = fixture.componentInstance;
    c.chooseChannel('EMAIL');
    c.contact.set('not-an-address');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#alert-contact')).toBeTruthy();

    c.save();
    fixture.detectChanges();
    expect(c.contactError()).toMatch(/valid email/);
    expect(c.saved()).toBeFalse();
    expect(preferences.put).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it('saves the whole preference and reports success', async () => {
    const fixture = create();
    await settle(fixture);
    const c = fixture.componentInstance;
    c.chooseChannel('EMAIL');
    c.contact.set('trader@example.com');
    c.displayCurrency.set('GBP');
    c.save();
    await settle(fixture);

    const expected: PreferenceRequest = {
      defaultAccountId: 7,
      alertChannel: 'EMAIL',
      alertContact: 'trader@example.com',
      displayCurrency: 'GBP',
    };
    expect(preferences.put).toHaveBeenCalledWith(7, expected);
    expect(c.saving()).toBeFalse();
    expect(c.saved()).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Preferences saved.');
    fixture.destroy();
  });

  it('saves the contact for the chosen channel', async () => {
    const fixture = create();
    await settle(fixture);
    const c = fixture.componentInstance;
    c.chooseChannel('PUSH');
    c.contact.set('left over');
    c.save();
    await settle(fixture);

    // In-app needs no destination, so none is sent.
    expect(preferences.put).toHaveBeenCalledWith(7, jasmine.objectContaining({ alertChannel: 'PUSH', alertContact: null }));
    fixture.destroy();
  });

  it('saves each delivery category with its channel and contact', async () => {
    const fixture = create();
    await settle(fixture);
    const c = fixture.componentInstance;
    c.updateCategory(c.categories().find((r) => r.categoryLevel === 'ORDER_RELATED')!, {
      channel: 'BOTH',
      contact: 'orders@example.com',
    });
    c.updateCategory(c.categories().find((r) => r.categoryLevel === 'ACCOUNT_RELATED')!, {
      channel: 'PUSH',
      contact: 'ignored',
    });
    c.save();
    await settle(fixture);

    expect(preferences.putNotifications).toHaveBeenCalledWith(7, [
      { category: 'ACCOUNT_RELATED', channel: 'PUSH', contact: null },
      { category: 'ORDER_RELATED', channel: 'BOTH', contact: 'orders@example.com' },
      { category: 'PRICE_RELATED', channel: 'EMAIL', contact: 'alerts@example.com' },
    ]);
    fixture.destroy();
  });

  it('refuses to save a delivery category whose contact does not fit its channel', async () => {
    const fixture = create();
    await settle(fixture);
    const c = fixture.componentInstance;
    c.updateCategory(c.categories().find((r) => r.categoryLevel === 'ORDER_RELATED')!, {
      channel: 'EMAIL',
      contact: 'nope',
    });
    c.save();
    fixture.detectChanges();
    expect(c.categorySaveError()).toMatch(/valid email address/);
    expect(preferences.putNotifications).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it('shows the refusal message when the service refuses a save', async () => {
    preferences.put.and.returnValue(
      throwError(() => ({ status: 422, error: { errorCode: 'VAL-422', message: 'Channel does not match contact' } })),
    );
    const fixture = create();
    await settle(fixture);
    const c = fixture.componentInstance;
    c.chooseChannel('EMAIL');
    c.contact.set('trader@example.com');
    c.save();
    await settle(fixture);

    expect(c.saving()).toBeFalse();
    expect(c.saved()).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain('Channel does not match contact');
    fixture.destroy();
  });
});