import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PriceAlertsComponent } from './price-alerts.component';
import { PriceAlertsService, type PriceAlert } from '../../core/services/price-alerts.service';
import { CurrencyService } from '../../core/services/currency.service';
import { AccountStateService } from '../../core/services/account-state.service';
import { TradeService } from '../../core/services/trade.service';
import { fakeTrade, quote } from '../../testing/fake-trade';

function alert(id: number, overrides: Partial<PriceAlert> = {}): PriceAlert {
  return {
    alertId: id,
    accountId: 7,
    symbol: 'AAPL',
    direction: 'ABOVE',
    threshold: 240,
    currency: 'USD',
    status: 'ACTIVE',
    triggeredPrice: null,
    triggeredAt: null,
    createdAt: '2026-10-05T09:00:00Z',
    ...overrides,
  };
}

describe('PriceAlertsComponent', () => {
  let service: jasmine.SpyObj<PriceAlertsService>;

  function create(): PriceAlertsComponent {
    const fixture = TestBed.createComponent(PriceAlertsComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj('PriceAlertsService', ['list', 'create', 'cancel']);
    service.list.and.returnValue(of([alert(1), alert(2, { status: 'TRIGGERED' })]));
    TestBed.configureTestingModule({
      imports: [PriceAlertsComponent],
      providers: [
        provideRouter([]),
        { provide: PriceAlertsService, useValue: service },
        { provide: AccountStateService, useValue: { preference: () => null } },
        { provide: TradeService, useValue: fakeTrade({ currency: 'INR', quotes: [quote('AAPL', 240, 'USD')] }) },
      ],
    });
  });

  it('loads the alerts and counts the armed ones', () => {
    const c = create();
    expect(c.loading()).toBeFalse();
    expect(c.alerts().length).toBe(2);
    expect(c.activeCount()).toBe(1);
    expect(c.atActiveCap()).toBeFalse();
  });

  it('reports a failed load', () => {
    service.list.and.returnValue(throwError(() => new Error('x')));
    expect(create().errorMessage()).toContain('could not be loaded');
  });

  it('arms an alert, upper-casing the symbol, and prepends it', () => {
    service.create.and.returnValue(of(alert(3, { symbol: 'MSFT' })));
    const c = create();
    c.newSymbol.set('  msft ');
    c.newDirection.set('BELOW');
    c.newThreshold.set('380.5');
    c.create();
    expect(service.create).toHaveBeenCalledWith({ symbol: 'MSFT', direction: 'BELOW', threshold: 380.5 });
    expect(c.alerts()[0].symbol).toBe('MSFT');
    expect(c.newSymbol()).toBe('');
    expect(c.newThreshold()).toBe('');
    expect(c.successMessage()).toContain('MSFT');
    expect(c.creating()).toBeFalse();
  });

  it('reports when the alert for the symbol is already armed', () => {
    service.create.and.returnValue(of(alert(1)));
    const c = create();
    c.newSymbol.set('AAPL');
    c.newThreshold.set('240');
    c.create();
    expect(service.create).toHaveBeenCalledWith({ symbol: 'AAPL', direction: 'ABOVE', threshold: 240 });
    expect(c.alerts().length).toBe(2);
    expect(c.successMessage()).toContain('already active');
  });

  it('requires a symbol', () => {
    const c = create();
    c.create();
    expect(service.create).not.toHaveBeenCalled();
    expect(c.errorMessage()).toContain('symbol');
  });

  it('requires a positive threshold', () => {
    const c = create();
    c.newSymbol.set('AAPL');
    c.newThreshold.set('0');
    c.create();
    expect(service.create).not.toHaveBeenCalled();
    expect(c.errorMessage()).toContain('greater than zero');
  });

  it('refuses to arm past the cap of 20', () => {
    service.list.and.returnValue(
      of(Array.from({ length: 20 }, (_, i) => alert(i, { symbol: `S${i}` }))),
    );
    const c = create();
    c.newSymbol.set('X');
    c.newThreshold.set('10');
    c.create();
    expect(c.atActiveCap()).toBeTrue();
    expect(service.create).not.toHaveBeenCalled();
  });

  it('shows the server message when a symbol cannot be armed', () => {
    service.create.and.returnValue(throwError(() => ({ error: { message: 'Unknown symbol' } })));
    const c = create();
    c.newSymbol.set('ZZZ');
    c.newThreshold.set('10');
    c.create();
    expect(c.errorMessage()).toBe('Unknown symbol');
    expect(c.creating()).toBeFalse();
  });

  it('cancels an alert only after confirmation and only while armed', () => {
    service.cancel.and.returnValue(of(alert(1, { status: 'CANCELLED' })));
    const c = create();

    c.cancel(c.alerts()[0]);
    expect(service.cancel).not.toHaveBeenCalled();
    expect(c.confirmRequest()?.title).toBe('Cancel price alert?');

    c.runConfirm();
    expect(service.cancel).toHaveBeenCalledWith(1);
    expect(c.alerts()[0].status).toBe('CANCELLED');
    expect(c.successMessage()).toContain('Cancelled');
    expect(c.confirmRequest()).toBeNull();

    const triggered = c.alerts().find((a) => a.status === 'TRIGGERED')!;
    c.cancel(triggered);
    expect(service.cancel).toHaveBeenCalledTimes(1);
    expect(triggered.status).toBe('TRIGGERED');
  });

  it('does not cancel when the confirmation is dismissed', () => {
    const c = create();
    c.cancel(c.alerts()[0]);
    c.dismissConfirm();
    expect(service.cancel).not.toHaveBeenCalled();
    expect(c.confirmRequest()).toBeNull();
  });

  it('converts a threshold typed in the display currency into the quote currency', () => {
    const currency = TestBed.inject(CurrencyService);
    currency.set('EUR');
    service.create.and.returnValue(of(alert(3, { symbol: 'AAPL', currency: 'USD' })));
    const c = create();
    c.newSymbol.set('AAPL');
    c.setSymbol('AAPL');
    c.newThreshold.set('200');
    c.create();
    expect(service.create).toHaveBeenCalledWith({
      symbol: 'AAPL',
      direction: 'ABOVE',
      threshold: jasmine.any(Number) as unknown as number,
    });
    const { threshold } = service.create.calls.mostRecent().args[0] as {
      threshold: number;
    };
    expect(Math.round(threshold * 100) / 100).toBe(217.39);
  });

  it('passes the threshold through unchanged when the quote currency equals the display currency', () => {
    service.create.and.returnValue(of(alert(3, { symbol: 'AAPL', currency: 'USD' })));
    const c = create();
    c.newSymbol.set('AAPL');
    c.setSymbol('AAPL');
    c.newThreshold.set('380.5');
    c.create();
    expect(service.create).toHaveBeenCalledWith({ symbol: 'AAPL', direction: 'ABOVE', threshold: 380.5 });
  });

  it('shows thresholds in the display currency with the quote amount next to it', () => {
    const currency = TestBed.inject(CurrencyService);
    currency.set('EUR');
    const c = create();
    expect(c.alertLevel(alert(1))).toContain('EUR');
    expect(c.alertLevel(alert(1))).toContain('USD');
    expect(c.alertLevel(alert(1, { currency: 'EUR' }))).not.toContain('USD');
  });

  it('names the symbol currency in the threshold hint once the quote is known', () => {
    const c = create();
    c.setSymbol('AAPL');
    expect(c.symbolCurrency()).toBe('USD');
    expect(c.thresholdHint()).toContain('USD market price of AAPL');
  });

  it('labels alert statuses for the customer', () => {
    const c = create();
    expect(c.statusLabel('ACTIVE')).toBe('Armed');
    expect(c.statusLabel('TRIGGERED')).toBe('Fired');
    expect(c.statusLabel('CANCELLED')).toBe('Cancelled');
  });

  it('shows the load error alone, without the "no price alerts" empty state', () => {
    service.list.and.returnValue(throwError(() => new Error('x')));
    const fixture = TestBed.createComponent(PriceAlertsComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.loadFailed()).toBeTrue();
    expect(fixture.nativeElement.querySelector('.state-title')).toBeNull();
  });

  it('shows the symbol being typed, not the signal behind it', () => {
    const fixture = TestBed.createComponent(PriceAlertsComponent);
    fixture.detectChanges();
    fixture.componentInstance.setSymbol('MSFT');
    fixture.detectChanges();
    return fixture.whenStable().then(() => {
      const input: HTMLInputElement = fixture.nativeElement.querySelector('#symbol');
      expect(input.value).toBe('MSFT');
    });
  });
});
