import { TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { BlotterComponent } from './blotter.component';
import { TradeService } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import type { AccountResponse, OrderHistoryEntry, OrderStatus } from '@trade-api/model/models';

const POLL_MS = 2500;

function order(status: OrderStatus): OrderHistoryEntry {
  return {
    orderId: 'ORD-1',
    accountId: 1,
    symbol: 'AAPL',
    side: 'BUY',
    quantity: 1,
    price: 1,
    status,
    createdOn: '2026-10-05T09:00:00Z',
  };
}

describe('BlotterComponent polling', () => {
  let trade: jasmine.SpyObj<TradeService>;

  beforeEach(() => {
    trade = jasmine.createSpyObj('TradeService', ['getOrders', 'getAccount']);
    trade.getAccount.and.returnValue(of({ currency: 'USD' } as AccountResponse));
    const tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
    tokens.getAccountId.and.returnValue(1);

    TestBed.configureTestingModule({
      imports: [BlotterComponent],
      providers: [
        provideRouter([]),
        { provide: TradeService, useValue: trade },
        { provide: TokenService, useValue: tokens },
      ],
    });
  });

  it('re-reads order history while an order is at NEW and stops once it settles', fakeAsync(() => {
    trade.getOrders.and.returnValues(of([order('NEW')]), of([order('NEW')]), of([order('FILLED')]));
    const blotter = TestBed.createComponent(BlotterComponent).componentInstance;
    blotter.ngOnInit();
    expect(blotter.isPolling()).toBeTrue();

    tick(POLL_MS);
    expect(blotter.isPolling()).toBeTrue();
    tick(POLL_MS);

    expect(blotter.isPolling()).toBeFalse();
    expect(trade.getOrders).toHaveBeenCalledTimes(3);
    tick(POLL_MS * 4);
    expect(trade.getOrders).toHaveBeenCalledTimes(3);
    blotter.ngOnDestroy();
  }));

  it('shows the order type, a limit price only for limit orders, and the executed price', fakeAsync(() => {
    trade.getOrders.and.returnValue(of([
      { ...order('FILLED'), orderId: 'M-1', orderType: 'MARKET', price: 99, executedPrice: 101 } as OrderHistoryEntry,
      { ...order('NEW'), orderId: 'L-1', orderType: 'LIMIT', price: 95 } as OrderHistoryEntry,
    ]));
    const fixture = TestBed.createComponent(BlotterComponent);
    fixture.detectChanges();
    const rows = fixture.nativeElement.querySelectorAll('tbody tr') as NodeListOf<HTMLElement>;
    const cell = (row: HTMLElement, id: string) => (row.querySelector('[data-testid="' + id + '"]')!.textContent ?? '').trim();
    expect(cell(rows[0], 'order-type')).toBe('Market');
    expect(cell(rows[0], 'limit-price')).toBe('—');
    expect(cell(rows[0], 'executed-price')).toBe('$101.00');
    expect(rows[0].classList).toContain('row-filled');
    expect(cell(rows[1], 'order-type')).toBe('Limit');
    expect(cell(rows[1], 'limit-price')).toBe('$95.00');
    expect(cell(rows[1], 'executed-price')).toBe('—');
    expect(fixture.componentInstance.isMarket({})).toBeFalse();
    fixture.destroy();
    discardPeriodicTasks();
  }));

  it('does not poll when nothing is working', fakeAsync(() => {
    trade.getOrders.and.returnValue(of([order('FILLED'), order('REJECTED')]));
    const blotter = TestBed.createComponent(BlotterComponent).componentInstance;
    blotter.ngOnInit();

    expect(blotter.isPolling()).toBeFalse();
    tick(POLL_MS * 3);
    expect(trade.getOrders).toHaveBeenCalledTimes(1);
  }));

  it('stops after the attempt limit and reports that the order is still working', fakeAsync(() => {
    trade.getOrders.and.returnValue(of([order('NEW')]));
    const blotter = TestBed.createComponent(BlotterComponent).componentInstance;
    blotter.ngOnInit();

    tick(POLL_MS * (blotter.maxPollAttempts + 2));

    expect(blotter.isPolling()).toBeFalse();
    expect(blotter.pollingExhausted()).toBeTrue();
    expect(trade.getOrders).toHaveBeenCalledTimes(blotter.maxPollAttempts + 1);
    discardPeriodicTasks();
  }));

  describe('rejection pop-up', () => {
    function rejected(reason?: string): OrderHistoryEntry {
      return { ...order('REJECTED'), rejectionReason: reason };
    }

    it("opens for a rejected order with the executor's reason explained", () => {
      trade.getOrders.and.returnValue(of([rejected('INSUFFICIENT_FUNDS')]));
      const blotter = TestBed.createComponent(BlotterComponent).componentInstance;
      blotter.ngOnInit();

      blotter.showRejection(blotter.orders()[0]);

      expect(blotter.rejectionOpen()?.orderId).toBe('ORD-1');
      expect(blotter.rejection()?.code).toBe('INSUFFICIENT_FUNDS');
      expect(blotter.rejection()?.label).toContain('cash');
    });

    it('does not open for an order that was not rejected', () => {
      const blotter = TestBed.createComponent(BlotterComponent).componentInstance;
      blotter.showRejection(order('FILLED'));
      blotter.showRejection(order('NEW'));
      expect(blotter.rejectionOpen()).toBeNull();
    });

    it('still explains a rejection with no recorded reason', () => {
      const blotter = TestBed.createComponent(BlotterComponent).componentInstance;
      blotter.showRejection(rejected(undefined));
      expect(blotter.rejection()?.label).toBeTruthy();
    });

    it('closes and returns focus to what opened it', () => {
      const blotter = TestBed.createComponent(BlotterComponent).componentInstance;
      const trigger = document.createElement('button');
      document.body.appendChild(trigger);
      const focus = spyOn(trigger, 'focus');

      blotter.showRejection(rejected('INSUFFICIENT_HOLDINGS'), { currentTarget: trigger } as unknown as Event);
      blotter.closeRejection();

      expect(blotter.rejectionOpen()).toBeNull();
      expect(focus).toHaveBeenCalled();
      trigger.remove();
    });

    it('renders the dialog with the reason and order facts', fakeAsync(() => {
      trade.getOrders.and.returnValue(of([rejected('INSUFFICIENT_HOLDINGS')]));
      const fixture = TestBed.createComponent(BlotterComponent);
      fixture.detectChanges();
      fixture.componentInstance.showRejection(fixture.componentInstance.orders()[0]);
      fixture.detectChanges();
      tick();

      const dialog = fixture.nativeElement.querySelector('[data-testid=rejection-dialog]') as HTMLElement;
      expect(dialog).toBeTruthy();
      expect(dialog.getAttribute('role')).toBe('dialog');
      expect(dialog.textContent).toContain('Not enough of the holding to sell');
      expect(dialog.textContent).toContain('INSUFFICIENT_HOLDINGS');
      expect(dialog.textContent).toContain('ORD-1');
      fixture.destroy();
    }));
  });
});
