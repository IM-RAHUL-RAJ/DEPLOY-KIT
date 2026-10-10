import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { OrderTicketComponent } from './order-ticket.component';
import { TradeService } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import { CurrencyService } from '../../core/services/currency.service';
import { fakeTrade, position, quote, type FakeTradeData } from '../../testing/fake-trade';
import type { AccountResponse, OrderResponse, PlaceOrderRequest } from '@trade-api/model/models';

describe('OrderTicketComponent', () => {
  let trade: jasmine.SpyObj<TradeService>;

  function create(data: FakeTradeData = {}): OrderTicketComponent {
    trade = fakeTrade({
      cash: 1000,
      positions: [position('AAPL', 5, 80)],
      quotes: [{ ...quote('AAPL', 100), bid: 99, ask: 101 }],
      ...data,
    });
    const tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
    tokens.getAccountId.and.returnValue(7);
    TestBed.configureTestingModule({
      imports: [OrderTicketComponent],
      providers: [
        provideRouter([]),
        { provide: TradeService, useValue: trade },
        { provide: TokenService, useValue: tokens },
      ],
    });
    const ticket = TestBed.createComponent(OrderTicketComponent).componentInstance;
    ticket.ngOnInit();
    return ticket;
  }

  /** Fills the form and lets the debounced quote lookup run. */
  function fill(ticket: OrderTicketComponent, values: { symbol?: string; side?: string; quantity?: number | null; price?: number | null }): void {
    ticket.form.patchValue({ symbol: 'AAPL', side: 'BUY', ...values });
    tick(450);
  }

  describe('defaults', () => {
    it('opens on a market order by default', fakeAsync(() => {
      localStorage.removeItem('trading_ui_preferences');
      const ticket = create();
      expect(ticket.orderType()).toBe('MARKET');
      expect(ticket.form.getRawValue().orderType).toBe('MARKET');
      ticket.reset();
      expect(ticket.orderType()).toBe('MARKET');
    }));

    it('starts on the linked symbol with the default quantity', fakeAsync(() => {
      trade = fakeTrade({ cash: 1000, quotes: [quote('MSFT', 400)] });
      const tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
      tokens.getAccountId.and.returnValue(7);
      TestBed.configureTestingModule({
        imports: [OrderTicketComponent],
        providers: [
          provideRouter([]),
          { provide: TradeService, useValue: trade },
          { provide: TokenService, useValue: tokens },
          { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ symbol: 'msft' }) } } },
        ],
      });
      const ticket = TestBed.createComponent(OrderTicketComponent).componentInstance;
      ticket.ngOnInit();
      tick(450);
      expect(ticket.form.getRawValue().symbol).toBe('MSFT');
      expect(ticket.form.getRawValue().quantity).toBe(1);
    }));
  });

  describe('account field', () => {
    it('is the account from the token, and read-only', fakeAsync(() => {
      const ticket = create();
      expect(ticket.form.controls.accountId.disabled).toBeTrue();
      expect(ticket.form.getRawValue().accountId).toBe('7');
    }));
  });

  describe('order summary', () => {
    it('shows amounts in the display currency while the order settles in the account currency', fakeAsync(() => {
      const ticket = create();
      expect(ticket.displayDiffers()).toBeFalse();
      expect(ticket.money(10)).toBe('$10.00');
      expect(ticket.money(null)).toBe('$0.00');
      TestBed.inject(CurrencyService).set('INR');
      expect(ticket.displayCode()).toBe('INR');
      expect(ticket.displayDiffers()).toBeTrue();
      expect(ticket.money(10)).toBe('₹833.00');
    }));

    it('stays hidden until the symbol and quantity are valid', fakeAsync(() => {
      const ticket = create();
      expect(ticket.summary()).toBeNull();
      fill(ticket, { quantity: null });
      expect(ticket.summary()).toBeNull();
      fill(ticket, { quantity: 0 });
      expect(ticket.summary()).toBeNull();
    }));

    it('summarises a market buy at the ask, with cash and holding before and after', fakeAsync(() => {
      const ticket = create();
      ticket.setOrderType('MARKET');
      fill(ticket, { quantity: 3 });

      const s = ticket.summary()!;
      expect(s.side).toBe('BUY');
      expect(s.unitPrice).toBe(101);
      expect(s.total).toBe(303);
      expect(s.cashBefore).toBe(1000);
      expect(s.cashAfter).toBe(697);
      expect(s.heldBefore).toBe(5);
      expect(s.heldAfter).toBe(8);
    }));

    it('summarises a market sell at the bid with realised P&L', fakeAsync(() => {
      const ticket = create();
      ticket.setOrderType('MARKET');
      fill(ticket, { side: 'SELL', quantity: 2 });

      const s = ticket.summary()!;
      expect(s.unitPrice).toBe(99);
      expect(s.total).toBe(198);
      expect(s.cashAfter).toBe(1198);
      expect(s.heldAfter).toBe(3);
      expect(s.realisedPnl).toBe(38); // (99 - 80) x 2
    }));

    it('summarises a limit order at the limit price', fakeAsync(() => {
      const ticket = create();
      ticket.setOrderType('LIMIT');
      fill(ticket, { quantity: 4, price: 95 });
      expect(ticket.summary()!.total).toBe(380);
    }));

    it('shows the exchange rate for an instrument in another currency', fakeAsync(() => {
      const ticket = create({ quotes: [quote('INFY.NS', 1000, 'INR')] });
      ticket.setOrderType('MARKET');
      fill(ticket, { symbol: 'INFY.NS', quantity: 2 });

      const s = ticket.summary()!;
      expect(s.foreign).toBeTrue();
      expect(s.quoteCurrency).toBe('INR');
      expect(s.totalNative).toBe(2000);
      // The executor rounds the converted unit price to cents before multiplying:
      // 1,000 INR = $12.0048, priced at $12.00, x 2 = $24.00.
      expect(s.unitPrice).toBe(12);
      expect(s.total).toBe(24);
    }));
  });

  describe('funds and holdings are the executor\'s decision', () => {
    it('warns about a buy beyond cash but still sends it', fakeAsync(() => {
      const ticket = create({ cash: 100 });
      trade.placeOrder.and.returnValue(of({ orderId: 'O1', status: 'NEW' } as OrderResponse));
      ticket.setOrderType('LIMIT');
      fill(ticket, { quantity: 10, price: 50 });

      expect(ticket.summary()!.cashShortfall).toBe(400);
      expect(ticket.canSubmit()).toBeTrue();
      ticket.submit();
      expect(trade.placeOrder).toHaveBeenCalled();
      ticket.ngOnDestroy();
    }));

    it('warns about a sell beyond the holding but still sends it', fakeAsync(() => {
      const ticket = create();
      trade.placeOrder.and.returnValue(of({ orderId: 'O2', status: 'NEW' } as OrderResponse));
      fill(ticket, { side: 'SELL', quantity: 50, price: 90 });

      expect(ticket.summary()!.holdingShortfall).toBe(45);
      ticket.submit();
      expect(trade.placeOrder).toHaveBeenCalled();
      ticket.ngOnDestroy();
    }));
  });

  describe('submission', () => {
    it('does not send an invalid order and marks the fields so the reasons show', fakeAsync(() => {
      const ticket = create();
      fill(ticket, { quantity: -1, price: 10 });
      ticket.submit();
      expect(trade.placeOrder).not.toHaveBeenCalled();
      expect(ticket.form.controls.quantity.touched).toBeTrue();
    }));

    it('sends no price with a market order', fakeAsync(() => {
      const ticket = create();
      trade.placeOrder.and.returnValue(of({ orderId: 'O3', status: 'NEW' } as OrderResponse));
      ticket.setOrderType('MARKET');
      fill(ticket, { quantity: 1 });
      ticket.submit();

      const sent = trade.placeOrder.calls.mostRecent().args[0] as PlaceOrderRequest;
      expect(sent.orderType).toBe('MARKET');
      expect(sent.price).toBeUndefined();
      expect(sent.accountId).toBe(7);
      ticket.ngOnDestroy();
    }));

    it('re-sends the same idempotency key after a failure', fakeAsync(() => {
      const ticket = create();
      trade.placeOrder.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
      fill(ticket, { quantity: 1, price: 10 });
      ticket.submit();
      ticket.submit();

      const [first, second] = trade.placeOrder.calls.all().map((c) => c.args[0].idempotencyKey);
      expect(first).toBe(second);
      expect(ticket.errorMessage()).toBeTruthy();
    }));

    it('shows the catalogue sentence, not the server message, for a refusal', fakeAsync(() => {
      const ticket = create();
      trade.placeOrder.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 404, error: { errorCode: 'INS-404', message: 'raw dev text' } })),
      );
      fill(ticket, { quantity: 1, price: 10 });
      ticket.submit();
      expect(ticket.errorMessage()).not.toContain('raw dev text');
    }));
  });

  describe('rendered ticket', () => {
    it('shows the summary box with the total, and no shortfall warning', fakeAsync(() => {
      create({ cash: 10 });
      const fixture = TestBed.createComponent(OrderTicketComponent);
      fixture.detectChanges();
      const t = fixture.componentInstance;
      t.setOrderType('MARKET');
      t.form.patchValue({ symbol: 'AAPL', side: 'BUY', quantity: 3 });
      tick(450);
      fixture.detectChanges();

      const el = fixture.nativeElement as HTMLElement;
      const box = el.querySelector('[data-testid=trade-summary]') as HTMLElement;
      expect(box).toBeTruthy();
      expect(box.querySelector('[data-testid=summary-total]')!.textContent).toContain('303.00');
      expect(el.querySelector('[data-testid=summary-warning]')).toBeNull();
      expect(el.querySelector('#price')).toBeNull();
      expect(el.textContent).not.toContain('JWT');
      expect(el.textContent).not.toContain('Consideration');
    }));

    it('shows the exchange rate row for a foreign instrument', fakeAsync(() => {
      create({ quotes: [quote('INFY.NS', 1000, 'INR')] });
      const fixture = TestBed.createComponent(OrderTicketComponent);
      fixture.detectChanges();
      fixture.componentInstance.form.patchValue({ symbol: 'INFY.NS', quantity: 2, price: 12 });
      tick(450);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('[data-testid=summary-fx]').textContent).toContain('1 INR =');
    }));

    it('confirms a placed order, counts down to the blotter and can be held', fakeAsync(() => {
      create();
      trade.placeOrder.and.returnValue(
        of({ orderId: 'O9', status: 'NEW', side: 'BUY', quantity: 1, symbol: 'AAPL', price: 10 } as OrderResponse),
      );
      const fixture = TestBed.createComponent(OrderTicketComponent);
      fixture.detectChanges();
      const t = fixture.componentInstance;
      const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

      t.form.patchValue({ symbol: 'AAPL', quantity: 1, price: 10 });
      tick(450);
      t.submit();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.success-panel')).toBeTruthy();
      expect(t.redirectIn()).toBe(5);

      tick(2000);
      t.holdBlotterRedirect();
      expect(t.redirectIn()).toBeNull();
      tick(5000);
      expect(navigate).not.toHaveBeenCalled();

      t.goToBlotter();
      expect(navigate).toHaveBeenCalledWith(['/blotter']);
    }));

    it('goes to the blotter by itself when the countdown ends', fakeAsync(() => {
      create();
      trade.placeOrder.and.returnValue(of({ orderId: 'O10', status: 'NEW' } as OrderResponse));
      const t = TestBed.createComponent(OrderTicketComponent).componentInstance;
      t.ngOnInit();
      const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
      t.form.patchValue({ symbol: 'AAPL', quantity: 1, price: 10 });
      tick(450);
      t.submit();
      tick(5000);
      expect(navigate).toHaveBeenCalledWith(['/blotter']);
    }));

    it('shows the suspended panel instead of the form for a suspended account', fakeAsync(() => {
      create();
      trade.getAccount.and.returnValue(of({ status: 'SUSPENDED', currency: 'USD' } as unknown as AccountResponse));
      const fixture = TestBed.createComponent(OrderTicketComponent);
      fixture.detectChanges();
      expect(fixture.componentInstance.isSuspended()).toBeTrue();
      expect(fixture.nativeElement.querySelector('.suspended-panel')).toBeTruthy();
      expect(fixture.nativeElement.querySelector('form')).toBeNull();
    }));

    it('clears a quote when the symbol is emptied, and survives a failed quote', fakeAsync(() => {
      const t = create();
      fill(t, { quantity: 1 });
      expect(t.quote()).not.toBeNull();
      t.form.patchValue({ symbol: '' });
      tick(450);
      expect(t.quote()).toBeNull();

      trade.getInstrumentQuote.and.returnValue(throwError(() => new Error('x')));
      t.form.patchValue({ symbol: 'MSFT' });
      tick(450);
      expect(t.quote()).toBeNull();
      expect(t.quoteLoading()).toBeFalse();
    }));

    it('switching order type back and forth keeps the form usable', fakeAsync(() => {
      const t = create();
      t.setOrderType('MARKET');
      t.setOrderType('MARKET');
      t.setOrderType('LIMIT');
      expect(t.isMarket()).toBeFalse();
    }));
  });
});
