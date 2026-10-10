import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal, type WritableSignal } from '@angular/core';
import { ShellComponent } from './shell.component';
import { AuthService } from '../../core/services/auth.service';
import { TokenService } from '../../core/services/token.service';
import { TradeService } from '../../core/services/trade.service';
import { CurrencyService } from '../../core/services/currency.service';
import { PreferencesService } from '../../core/services/preferences.service';
import {
  NotificationCenterService,
  type AppToast,
} from '../../core/services/notification-center.service';
import { fakeTrade } from '../../testing/fake-trade';

describe('ShellComponent', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let notificationsCenter: jasmine.SpyObj<NotificationCenterService>;
  let unreadCount: WritableSignal<number>;
  let toasts: WritableSignal<AppToast[]>;

  function create() {
    const fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    localStorage.clear();
    auth = jasmine.createSpyObj('AuthService', ['logout']);
    const tokens = jasmine.createSpyObj('TokenService', ['getAccountId', 'getUsername', 'getUser']);
    tokens.getAccountId.and.returnValue(7);
    tokens.getUsername.and.returnValue('demo');
    const preferences = jasmine.createSpyObj('PreferencesService', ['get', 'put', 'putTheme']);
    preferences.get.and.returnValue(
      of({ accountId: 7, defaultAccountId: 7, alertChannel: 'NONE', alertContact: null, displayCurrency: null, updatedAt: null }),
    );
    preferences.putTheme.and.returnValue(
      of({ accountId: 7, defaultAccountId: 7, alertChannel: 'NONE', alertContact: null, displayCurrency: null, theme: 'light', updatedAt: null }),
    );
    preferences.put.and.returnValue(
      of({ accountId: 7, defaultAccountId: 7, alertChannel: 'NONE', alertContact: null, displayCurrency: 'USD', updatedAt: null }),
    );
    notificationsCenter = jasmine.createSpyObj('NotificationCenterService', [
      'start',
      'stop',
      'dismissToast',
    ]);
    unreadCount = signal(0);
    toasts = signal<AppToast[]>([]);
    Object.assign(notificationsCenter, { unreadCount, toasts });
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
        { provide: TokenService, useValue: tokens },
        { provide: TradeService, useValue: fakeTrade({ currency: 'INR' }) },
        { provide: PreferencesService, useValue: preferences },
        { provide: NotificationCenterService, useValue: notificationsCenter },
      ],
    });
  });

  afterEach(() => localStorage.clear());

  it('shows the cloud mark, the trader and every workspace link', () => {
    const fixture = create();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-cloud-mark')).toBeTruthy();
    expect(el.textContent).toContain('demo');
    const links = Array.from(el.querySelectorAll('.nav-item')).map((a) => a.textContent!.trim());
    expect(links).toEqual(['Dashboard', 'Order Ticket', 'Blotter', 'Holdings', 'Portfolio', 'Watchlist', 'Notifications', 'Price Alerts', 'AI Assistant', 'Trade Advice', 'Automated Trading', 'Settings', 'Profile']);
    expect(fixture.componentInstance.initials()).toBe('DE');
    fixture.destroy();
  });

  it('loads the account currency, so an INR account is shown in INR', () => {
    const fixture = create();
    const currency = TestBed.inject(CurrencyService);
    expect(currency.ledger()).toBe('INR');
    expect(currency.selected()).toBe('INR');
    fixture.destroy();
  });

  it('opens the currency picker with rates per unit of the account currency', () => {
    const fixture = create();
    fixture.componentInstance.toggleFx();
    fixture.detectChanges();
    const popover = fixture.nativeElement.querySelector('.fx-popover') as HTMLElement;
    expect(popover.textContent).toContain('1 INR =');
    expect(popover.textContent).toContain('remain in INR');
    fixture.destroy();
  });

  it('changes the display currency and closes the picker', () => {
    const fixture = create();
    fixture.componentInstance.toggleFx();
    fixture.componentInstance.selectCurrency('USD');
    expect(TestBed.inject(CurrencyService).selected()).toBe('USD');
    expect(fixture.componentInstance.showFx()).toBeFalse();
    fixture.destroy();
  });

  it('titles the page after the route', async () => {
    const fixture = create();
    const router = TestBed.inject(Router);
    router.resetConfig([{ path: 'blotter', children: [] }, { path: 'elsewhere', children: [] }]);
    await router.navigateByUrl('/blotter');
    expect(fixture.componentInstance.page().title).toBe('Order Blotter');
    await router.navigateByUrl('/elsewhere');
    expect(fixture.componentInstance.page().tag).toBe('PLATFORM');
    fixture.destroy();
  });

  it('toggles the theme and opens a sign-out dialog', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    const before = c.currentTheme;
    c.toggleTheme();
    expect(c.currentTheme).not.toBe(before);
    c.requestLogout();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.confirm-dialog')?.textContent).toContain('Do you really want to sign out?');
    c.logout();
    expect(auth.logout).toHaveBeenCalled();
    fixture.destroy();
  });

  it('collapses the navigation to icons and remembers it', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    const el = fixture.nativeElement as HTMLElement;
    c.ui.sidebarCollapsed.set(false);
    fixture.detectChanges();
    (el.querySelector('[data-testid="sidebar-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(c.ui.sidebarCollapsed()).toBeTrue();
    expect(el.querySelector('.shell')?.classList).toContain('collapsed');
    c.toggleSidebar();
    expect(c.ui.sidebarCollapsed()).toBeFalse();
    fixture.destroy();
  });

  it('polls the notification centre for the signed-in account and stops on destroy', () => {
    const fixture = create();
    expect(notificationsCenter.start).toHaveBeenCalledWith(7);
    fixture.destroy();
    expect(notificationsCenter.stop).toHaveBeenCalled();
  });

  it('shows the unread badge and Push toasts, and closes a toast from the centre', () => {
    const fixture = create();
    unreadCount.set(2);
    toasts.set([
      { id: 9, title: 'Price alert', body: 'AAPL crossed its 240.00 threshold' },
    ]);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const navItem = Array.from(el.querySelectorAll('.nav-item')).find((a) =>
      a.textContent!.includes('Notifications'),
    )!;
    expect(navItem.querySelector('.nav-badge')!.textContent!.trim()).toBe('2');
    expect(el.querySelector('.toast-title')!.textContent).toContain('Price alert');
    expect(el.querySelector('.toast-text')!.textContent).toContain('AAPL crossed');

    (el.querySelector('.toast-close') as HTMLButtonElement).click();
    expect(notificationsCenter.dismissToast).toHaveBeenCalledWith(9);
    fixture.destroy();
  });

  it('does not sign out when the sign-out dialog is cancelled', () => {
    const fixture = create();
    fixture.componentInstance.requestLogout();
    fixture.componentInstance.cancelLogout();
    expect(auth.logout).not.toHaveBeenCalled();
    expect(fixture.componentInstance.showLogoutConfirm()).toBeFalse();
    fixture.destroy();
  });
});
