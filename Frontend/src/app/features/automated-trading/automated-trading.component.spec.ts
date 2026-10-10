import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { AutomatedTradingComponent } from './automated-trading.component';
import { StrategiesService, type Strategy, type StrategyPreview } from '../../core/services/strategies.service';
import { MarketDataService } from '../../core/services/market-data.service';
import { TradeService } from '../../core/services/trade.service';
import { WatchlistService } from '../../core/services/watchlist.service';
import { TokenService } from '../../core/services/token.service';
import { CurrencyService } from '../../core/services/currency.service';

function strategy(overrides: Partial<Strategy> = {}): Strategy {
  return {
    strategyId: 7, accountId: 1, name: 'AAPL MA 20/50', symbol: 'AAPL', strategyType: 'MA_CROSSOVER',
    side: null, targetPrice: null, orderType: 'MARKET',
    shortWindow: 20, longWindow: 50, bandPeriod: null, bandK: null, quantity: 1, maxNotional: 300,
    maxOrdersPerDay: 5, maxPosition: 5, status: 'ACTIVE', stopReason: null, lastState: 'BELOW',
    lastPrice: 190, lastEvaluatedAt: '2026-10-07T10:00:00Z', consecutiveFailures: 0,
    createdAt: '2026-10-07T09:00:00Z', ...overrides,
  };
}

describe('AutomatedTradingComponent', () => {
  let api: jasmine.SpyObj<StrategiesService>;
  let market: jasmine.SpyObj<MarketDataService>;
  let accountId: number | null;
  let linked: string | null;

  beforeEach(() => {
    accountId = 1;
    linked = null;
    api = jasmine.createSpyObj('StrategiesService', ['list', 'create', 'setStatus', 'delete', 'executions', 'preview']);
    api.list.and.returnValue(of([
      strategy(),
      strategy({ strategyId: 8, symbol: 'MSFT', name: 'MSFT Bollinger 20/2', strategyType: 'BOLLINGER',
        bandPeriod: 20, bandK: 2, shortWindow: null, longWindow: null, status: 'STOPPED',
        stopReason: 'Stopped after 3 failed orders.', lastState: 'INSIDE' }),
      strategy({ strategyId: 9, status: 'DISABLED', lastState: null, lastEvaluatedAt: null }),
    ]));
    market = jasmine.createSpyObj('MarketDataService', ['quote', 'candles', 'invalidate']);
    market.quote.and.returnValue(of({ symbol: 'AAPL', price: 200, bid: 199.9, ask: 200.1, quoteCurrency: 'USD', stale: false }));
    const day = (d: string, c: number) => ({ time: Date.parse(d), date: d, open: c, high: c, low: c, close: c, volume: 1 });
    market.candles.and.returnValue(of({ symbol: 'AAPL', currency: 'USD', candles: [day('2020-01-01', 190), day('2020-01-02', 195)] }));

    TestBed.configureTestingModule({
      imports: [AutomatedTradingComponent],
      providers: [
        { provide: StrategiesService, useValue: api },
        { provide: MarketDataService, useValue: market },
        { provide: TradeService, useValue: { getPositions: () => of([{ accountId: 1, symbol: 'AAPL', quantity: 4, averageCost: 150 }]) } },
        { provide: WatchlistService, useValue: { getWatchlist: () => of({ instruments: [
          { symbol: 'MSFT', ticker: 'MSFT', currentPrice: 400, priceCurrency: 'USD' },
          { symbol: 'AAPL', ticker: 'AAPL', currentPrice: 199, priceCurrency: 'USD' },
        ] }) } },
        { provide: TokenService, useValue: { getAccountId: () => accountId } },
        { provide: ActivatedRoute, useFactory: () => ({ snapshot: { queryParamMap: convertToParamMap(linked ? { symbol: linked } : {}) } }) },
      ],
    });
    TestBed.overrideComponent(AutomatedTradingComponent, {
      set: { imports: [DatePipe, DecimalPipe, FormsModule], schemas: [CUSTOM_ELEMENTS_SCHEMA] },
    });
  });

  function create() {
    const fixture = TestBed.createComponent(AutomatedTradingComponent);
    fixture.detectChanges();
    return fixture;
  }

  describe('board', () => {
    it('summarises the board and lists every automated order', () => {
      const fixture = create();
      const c = fixture.componentInstance;
      expect(c.counts()).toEqual({ ALL: 3, ACTIVE: 1, DISABLED: 1, STOPPED: 1, COMPLETED: 0 });
      expect(c.slotsLeft()).toBe(c.maxStrategies - 3);
      c.boardFilter.set('DISABLED');
      expect(c.board().map((s) => s.strategyId)).toEqual([9]);
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Your price targets');
    });

    it('reports a board that cannot be loaded, and loads nothing without an account', () => {
      api.list.and.returnValue(throwError(() => new Error('down')));
      expect(create().componentInstance.error()).toContain('could not be loaded');
      accountId = null;
      api.list.calls.reset();
      const c = create().componentInstance;
      expect(api.list).not.toHaveBeenCalled();
      expect(c.loading()).toBeFalse();
    });

    it('pauses, resumes and deletes after confirmation', () => {
      const c = create().componentInstance;
      const s = c.strategies()[0];
      api.setStatus.and.returnValue(of({ ...s, status: 'DISABLED' }));
      c.toggleStatus(s);
      expect(api.setStatus).toHaveBeenCalledWith(1, 7, 'DISABLED');
      expect(c.strategies()[0].status).toBe('DISABLED');

      api.setStatus.and.returnValue(throwError(() => ({ error: { message: 'Nope' } })));
      c.toggleStatus(c.strategies()[0]);
      expect(c.error()).toBe('Nope');

      api.delete.and.returnValue(of(void 0));
      c.askDelete(s);
      expect(c.confirmDelete()).toBe(s);
      c.doDelete();
      expect(api.delete).toHaveBeenCalledWith(1, 7);
      expect(c.strategies().some((x) => x.strategyId === 7)).toBeFalse();

      api.delete.and.returnValue(throwError(() => new Error('x')));
      c.askDelete(c.strategies()[0]);
      c.doDelete();
      expect(c.error()).toContain('could not be deleted');
    });

    it('opens and closes an order’s activity', () => {
      const c = create().componentInstance;
      api.preview.and.returnValue(of({ explanation: 'Waiting.' } as unknown as StrategyPreview));
      api.executions.and.returnValue(of([]));
      const s = c.strategies()[0];
      c.toggleDetail(s);
      expect(c.details()[7]?.loading).toBeFalse();
      expect(c.details()[7]?.preview?.explanation).toBe('Waiting.');
      c.toggleDetail(s);
      expect(c.details()[7]).toBeUndefined();
    });

    it('hides resume and delete actions for completed targets', () => {
      const fixture = create();
      const c = fixture.componentInstance;
      const completed = strategy({
        strategyId: 70,
        strategyType: 'PRICE_TARGET',
        side: 'BUY',
        targetPrice: 180,
        status: 'COMPLETED',
        lastState: 'HIT',
        stopReason: null,
      });
      c.strategies.set([completed]);
      fixture.detectChanges();

      const text = fixture.nativeElement.textContent as string;
      expect(text).toContain('Activity');
      expect(text).not.toContain('Resume');
      expect(text).not.toContain('Delete');

      c.toggleStatus(completed);
      c.askDelete(completed);
      expect(api.setStatus).not.toHaveBeenCalled();
      expect(c.confirmDelete()).toBeNull();
    });

    it('describes targets in the display currency, and older rules in plain words', () => {
      const c = create().componentInstance;
      const t = strategy({ strategyType: 'PRICE_TARGET', side: 'SELL', targetPrice: 210, lastState: 'WAITING', lastPrice: 200 });
      expect(c.describe(t)).toBe('Sell 1 at ≥ $210.00 · market');
      expect(c.stateLabel(t)).toBe('Waiting for ≥ $210.00 · last $200.00');
      expect(c.stateLabel({ ...t, lastState: 'HIT' })).toBe('Target reached');
      expect(c.stateLabel({ ...t, status: 'COMPLETED', stopReason: null })).toBe('Target reached and traded');
      expect(c.stateLabel({ ...t, lastState: null })).toBe('Waiting for the first quote');
      expect(c.describe(c.strategies()[0])).toContain('20/50-day');
      expect(c.describe(c.strategies()[1])).toContain('Bollinger');
      expect(c.stateLabel(c.strategies()[0])).toBe('Below the signal line');
      expect(c.stateLabel({ ...c.strategies()[0], lastState: 'ABOVE' })).toBe('Above the signal line');
      expect(c.stateLabel(c.strategies()[1])).toBe('Waiting for a signal');
      expect(c.statusLabel('DISABLED')).toBe('Paused');
      expect(c.statusLabel('STOPPED')).toBe('Stopped');
      expect(c.statusLabel('COMPLETED')).toBe('Done');
      expect(c.statusLabel('ACTIVE')).toBe('Active');
    });
  });

  describe('instrument', () => {
    it('offers held and watched instruments, held first, and prices the first one', () => {
      const c = create().componentInstance;
      expect(c.picks().map((p) => p.symbol)).toEqual(['AAPL', 'MSFT']);
      expect(c.picks()[0].held).toBe(4);
      expect(c.symbol()).toBe('AAPL');
      expect(c.price()).toBe(200);
      expect(c.dayChange()?.change).toBeCloseTo(5, 6);
    });

    it('picks any symbol, rejecting a malformed one and naming an unknown one', () => {
      const c = create().componentInstance;
      c.symbolInput.set('bad symbol!');
      c.submitSymbol();
      expect(c.quoteError()).toContain('Enter a symbol');
      market.quote.and.returnValue(throwError(() => ({ status: 404 })));
      c.symbolInput.set('zzz');
      c.submitSymbol();
      expect(c.symbol()).toBe('ZZZ');
      expect(c.quoteError()).toContain('not a known instrument');
      market.quote.and.returnValue(throwError(() => ({ status: 503 })));
      c.chooseSymbol('ABC');
      expect(c.quoteError()).toContain('not available yet');
      c.chooseSymbol('  ');
      expect(c.symbol()).toBe('ABC');
    });

    it('opens on a linked symbol', () => {
      linked = 'msft';
      expect(create().componentInstance.symbol()).toBe('MSFT');
    });
  });

  describe('price target', () => {
    it('has no indicator rule and no order type: only side, quantity and target', () => {
      const fixture = create();
      const text = fixture.nativeElement.textContent as string;
      expect(text).not.toContain('Indicator rule');
      expect(text).not.toContain('Limit');
      expect(fixture.nativeElement.querySelector('#tgt')).toBeTruthy();
      expect(fixture.nativeElement.querySelector('#qty-t')).toBeTruthy();
    });

    it('suggests a target 2% better than today, follows the side, and lets the customer nudge it', () => {
      const c = create().componentInstance;
      expect(c.targetPrice()).toBe(196);
      c.setSide('SELL');
      expect(c.targetPrice()).toBe(204);
      expect(c.targetMetNow()).toBeFalse();
      c.targetAt(-5);
      expect(c.targetPrice()).toBe(190);
      expect(c.targetMetNow()).toBeTrue(); // a sell at 190 is already met at 200
      c.setSide('BUY');
      expect(c.targetPrice()).toBe(190); // the customer's own target stays
      expect(c.targetGap()).toBeCloseTo(-5, 6);
      c.setTarget(250);
      expect(c.targetMetNow()).toBeTrue();
      c.setQuantity(3);
      c.step(-1);
      expect(c.quantity()).toBe(2);
      expect(c.targetValue()).toBe(500);
      c.setTarget('');
      expect(c.targetPrice()).toBeNull();
    });

    it('arms a market order from instrument, side, quantity and target', () => {
      const c = create().componentInstance;
      api.create.and.returnValue(of(strategy({ strategyId: 11, strategyType: 'PRICE_TARGET', side: 'BUY', targetPrice: 190 })));
      c.setTarget(190);
      c.setQuantity(2);
      c.create();
      expect(api.create).toHaveBeenCalledWith(1, {
        symbol: 'AAPL', strategyType: 'PRICE_TARGET', side: 'BUY', orderType: 'MARKET', targetPrice: 190, quantity: 2,
      });
      expect(c.notice()).toContain('at market');
      // The board is read again, so a target the server traded at once shows as done.
      expect(api.list).toHaveBeenCalledTimes(2);
    });

    it('converts a target typed in the display currency to the instrument’s currency', () => {
      const money = TestBed.inject(CurrencyService);
      money.setLedger('INR');
      const c = create().componentInstance;
      expect(c.display()).toBe('INR');
      expect(c.displayPrice()).toBeCloseTo(16660, 2);
      api.create.and.returnValue(of(strategy({ strategyType: 'PRICE_TARGET', side: 'BUY', targetPrice: 190 })));
      c.setTarget(15827);
      c.create();
      expect(api.create.calls.mostRecent().args[1].targetPrice).toBeCloseTo(190, 2);
      expect(c.notice()).toContain('₹15,827.00');
    });

    it('ignores a second click while the first is on its way, and never lists the same target twice', () => {
      const c = create().componentInstance;
      c.saving.set(true);
      c.create();
      expect(api.create).not.toHaveBeenCalled();
      c.saving.set(false);
      api.create.and.returnValue(of(strategy()));
      c.create();
      expect(c.strategies().filter((s) => s.strategyId === 7).length).toBe(1);
    });

    it('refuses a missing target, a missing instrument, an oversized sell, and shows a server refusal', () => {
      const c = create().componentInstance;
      c.setTarget(0);
      c.create();
      expect(c.formError()).toContain('greater than zero');
      c.setTarget(210);
      c.setSide('SELL');
      c.setQuantity(9);
      c.create();
      expect(c.formError()).toContain('You hold 4 AAPL');
      c.setQuantity(1);
      api.create.and.returnValue(throwError(() => ({ error: { message: 'Not enough shares' } })));
      c.create();
      expect(c.formError()).toBe('Not enough shares');
      expect(c.saving()).toBeFalse();
      c.symbol.set(null);
      c.create();
      expect(c.formError()).toContain('Choose an instrument');
    });
  });

  describe('edge cases', () => {
    it('ignores zero-quantity positions when building picks', () => {
      TestBed.overrideProvider(TradeService, { useValue: { getPositions: () => of([
        { accountId: 1, symbol: 'AAPL', quantity: 4, averageCost: 150 },
        { accountId: 1, symbol: 'NVDA', quantity: 0, averageCost: 150 },
      ]) } });
      TestBed.overrideProvider(WatchlistService, { useValue: { getWatchlist: () => of({ instruments: [] }) } });
      const c = create().componentInstance;
      expect(c.picks().map((p) => p.symbol)).toEqual(['AAPL']);
    });

    it('builds picks from positions when watchlist loading fails', () => {
      TestBed.overrideProvider(TradeService, { useValue: { getPositions: () => of([
        { accountId: 1, symbol: 'AAPL', quantity: 4, averageCost: 150 },
      ]) } });
      TestBed.overrideProvider(WatchlistService, { useValue: { getWatchlist: () => throwError(() => new Error('down')) } });
      const c = create().componentInstance;
      expect(c.picks().map((p) => p.symbol)).toEqual(['AAPL']);
    });

    it('falls back to the watchlist price and currency when the live quote and history are missing', () => {
      market.quote.and.returnValue(throwError(() => ({ status: 503 })));
      market.candles.and.returnValue(throwError(() => new Error('x')));
      const c = create().componentInstance;
      expect(c.symbol()).toBe('AAPL');
      expect(c.price()).toBe(199);
      expect(c.currency()).toBe('USD');
      expect(c.held()).toBe(4);
      expect(c.dayChange()).toBeNull();
      c.chooseSymbol('ZZZ');
      expect(c.currency()).toBe('');
      expect(c.held()).toBe(0);
      expect(c.price()).toBeNull();
      expect(c.targetGap()).toBeNull();
      expect(c.targetMetNow()).toBeFalse();
      c.targetAt(5);
      expect(c.targetPrice()).toBeNull();
    });

    it('reads quantities defensively and reports the sell notices both ways', () => {
      const c = create().componentInstance;
      c.setQuantity('abc');
      expect(c.quantity()).toBe(1);
      expect(c.targetValue()).toBe(196);
      c.setTarget(null);
      expect(c.targetValue()).toBeNull();

      api.create.and.returnValue(of(strategy({ strategyId: 12 })));
      c.setSide('SELL');
      c.setTarget(150);
      c.create();
      expect(c.notice()).toContain('Sell 1 AAPL is armed, and the price is already there');
      c.setTarget(300);
      c.create();
      expect(c.notice()).toContain('when the price reaches');
    });

    it('reports that a buy target already reached will trade on the next quote', () => {
      const c = create().componentInstance;
      api.create.and.returnValue(of(strategy({ strategyId: 13, strategyType: 'PRICE_TARGET', side: 'BUY', targetPrice: 250 })));
      c.setSide('BUY');
      c.setTarget(250);
      c.create();
      expect(c.notice()).toContain('is armed, and the price is already there');
    });

    it('does nothing on the board without an account, and ignores a delete with nothing confirmed', () => {
      const c = create().componentInstance;
      accountId = null;
      const s = c.strategies()[0];
      c.toggleStatus(s);
      c.toggleDetail(s);
      c.askDelete(s);
      c.doDelete();
      c.setTarget(190);
      c.create();
      expect(api.setStatus).not.toHaveBeenCalled();
      expect(api.delete).not.toHaveBeenCalled();
      expect(api.create).not.toHaveBeenCalled();
      accountId = 1;
      c.doDelete();
      expect(api.delete).not.toHaveBeenCalled();
    });

    it('describes buy targets and their waiting state', () => {
      const c = create().componentInstance;
      const t = strategy({ strategyType: 'PRICE_TARGET', side: 'BUY', targetPrice: 180, lastState: 'WAITING', lastPrice: 190 });
      expect(c.describe(t)).toBe('Buy 1 at ≤ $180.00 · market');
      expect(c.stateLabel(t)).toContain('Waiting for ≤ $180.00');
      expect(c.priceOf('UNKNOWN', 12)).toBe('12.00');
    });

    it('does not restore a detail row if it was closed before async detail data arrives', () => {
      const preview$ = new Subject<StrategyPreview>();
      const executions$ = new Subject<never[]>();
      api.preview.and.returnValue(preview$.asObservable());
      api.executions.and.returnValue(executions$.asObservable());

      const c = create().componentInstance;
      const s = c.strategies()[0];
      c.toggleDetail(s);
      expect(c.details()[s.strategyId]).toBeTruthy();
      c.toggleDetail(s);
      expect(c.details()[s.strategyId]).toBeUndefined();

      preview$.next({ explanation: 'Late update' } as StrategyPreview);
      executions$.next([]);
      preview$.complete();
      executions$.complete();

      expect(c.details()[s.strategyId]).toBeUndefined();
    });

    it('builds picks from tickers and partial watchlist rows, held instruments first', () => {
      TestBed.overrideProvider(WatchlistService, { useValue: { getWatchlist: () => of({ instruments: [
        { symbol: '', ticker: 'TSLA' },
        { symbol: 'AAPL', ticker: 'AAPL' },
      ] }) } });
      TestBed.overrideProvider(TradeService, { useValue: { getPositions: () => of(undefined) } });
      const c = create().componentInstance;
      expect(c.picks().map((p) => p.symbol)).toEqual(['AAPL', 'TSLA']);
      expect(c.picks()[0].price).toBeNull();
    });
  });
});
