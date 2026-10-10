import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdviceComponent } from './advice.component';
import { AdviceService, type TradeSignal } from '../../core/services/advice.service';
import { TokenService } from '../../core/services/token.service';
import { InstrumentChartService } from '../../shared/instrument-chart/instrument-chart.service';

function signal(symbol: string, action: TradeSignal['action'], score: number, overrides: Partial<TradeSignal> = {}): TradeSignal {
  return {
    symbol,
    status: action ? 'SIGNAL' : 'INSUFFICIENT_DATA',
    action,
    score,
    strength: Math.abs(score) * 20,
    summary: `${symbol}: ${action ?? 'No signal'}.`,
    reasons: ['Reason one.', 'Context: 52-week range 90.00 to 130.00.'],
    factors: action ? [
      { key: 'TREND', name: 'Trend', score: 1, reason: 'Uptrend.' },
      { key: 'POSITION', name: 'Price vs 20-day average', score: 1, reason: 'Above.' },
      { key: 'RSI', name: 'RSI (14)', score: 0, reason: 'Neutral.' },
      { key: 'MACD', name: 'MACD (12, 26, 9)', score: -1, reason: 'Fading.' },
      { key: 'BOLLINGER', name: 'Bollinger %B (20, 2)', score: 0, reason: 'Inside.' },
    ] : [],
    indicators: {
      lastPrice: 120, sma20: 115, sma50: 110, sma200: null, rsi14: 55, macd: 1.2, macdSignal: 1.4,
      macdHistogram: -0.2, bollingerUpper: 125, bollingerMiddle: 115, bollingerLower: 105, percentB: 0.75,
      high52w: 130, low52w: 90, volatility: 24.5, dailyCloses: 250,
    },
    holding: null,
    sources: ['WATCHLIST'],
    priceAsOf: '2026-10-07T14:00:00Z',
    ...overrides,
  };
}

describe('AdviceComponent', () => {
  let advice: jasmine.SpyObj<AdviceService>;
  let accountId: number | null;
  let linked: string | null;

  beforeEach(() => {
    accountId = 1;
    linked = null;
    advice = jasmine.createSpyObj('AdviceService', ['forAccount', 'forSymbol', 'methodology']);
    advice.forAccount.and.returnValue(of({
      accountId: 1, methodology: 'Five factors.', disclaimer: 'Not advice.', asOf: '2026-10-07T14:00:00Z',
      signals: [
        signal('MSFT', 'HOLD', 1),
        signal('AAPL', 'BUY', 3, { holding: { quantity: 5, averageCost: 100, unrealisedPnlPercent: 20 }, sources: ['HOLDING', 'WATCHLIST'] }),
        signal('NEWCO', null, 0),
        signal('TSLA', 'SELL', -4),
      ],
    }));
    advice.methodology.and.returnValue(of({
      summary: 'Five factors.', scoring: 'Sum.', disclaimer: 'Not advice.',
      factors: [{ key: 'RSI', name: 'RSI (14)', formula: 'RSI = 100 - 100 / (1 + RS)', rule: 'Under 30 +1.' }],
    }));
    TestBed.configureTestingModule({
      imports: [AdviceComponent],
      providers: [
        provideRouter([]),
        { provide: AdviceService, useValue: advice },
        { provide: TokenService, useValue: { getAccountId: () => accountId } },
        { provide: ActivatedRoute, useFactory: () => ({ snapshot: { queryParamMap: convertToParamMap(linked ? { symbol: linked } : {}) } }) },
      ],
    });
    // The chart has its own spec; here it is an inert tag.
    TestBed.overrideComponent(AdviceComponent, {
      set: { imports: [DatePipe, DecimalPipe, FormsModule], schemas: [CUSTOM_ELEMENTS_SCHEMA] },
    });
  });

  function create() {
    const fixture = TestBed.createComponent(AdviceComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('lists signals strongest first, no-data last, and opens the first', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    expect(c.signals().map((s) => s.symbol)).toEqual(['TSLA', 'AAPL', 'MSFT', 'NEWCO']);
    expect(c.selected()!.symbol).toBe('TSLA');
    expect(c.counts()).toEqual({ ALL: 4, BUY: 1, SELL: 1, HOLD: 1 });
    expect(fixture.nativeElement.querySelectorAll('.sig-row').length).toBe(4);
  });

  it('shows the verdict, the five factors, the indicators and the position', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    c.select('AAPL');
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('BUY');
    expect(fixture.nativeElement.querySelectorAll('.factor').length).toBe(5);
    expect(text).toContain('Your position');
    expect(text).toContain('+20.00%');
    expect(text).toContain('needs 200 days');
    expect(c.scorePos(3)).toBe(80);
    expect(c.between(120, 90, 130)).toBe(75);
  });

  it('filters by action', () => {
    const c = create().componentInstance;
    c.filter.set('SELL');
    expect(c.filtered().map((s) => s.symbol)).toEqual(['TSLA']);
  });

  it('explains the methodology on request', () => {
    const fixture = create();
    fixture.componentInstance.showMethod.set(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('RSI = 100 - 100 / (1 + RS)');
  });

  it('analyses a symbol that is not listed, and validates the input', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    c.query.set('bad symbol!');
    c.analyse();
    expect(c.searchError()).toContain('Enter a symbol');

    advice.forSymbol.and.returnValue(of(signal('NVDA', 'BUY', 2, { sources: ['REQUESTED'] })));
    c.query.set('nvda');
    c.analyse();
    expect(advice.forSymbol).toHaveBeenCalledWith(1, 'NVDA');
    expect(c.selected()!.symbol).toBe('NVDA');
    expect(c.query()).toBe('');

    c.analyse('AAPL');
    expect(c.selected()!.symbol).toBe('AAPL');
  });

  it('reports a failed analysis', () => {
    const c = create().componentInstance;
    advice.forSymbol.and.returnValue(throwError(() => ({ status: 404 })));
    c.analyse('ZZZZ');
    expect(c.searchError()).toContain('not a known instrument');
    advice.forSymbol.and.returnValue(throwError(() => ({ status: 500 })));
    c.analyse('ZZZZ');
    expect(c.searchError()).toContain('could not be analysed');
  });

  it('opens on a linked symbol', () => {
    linked = 'msft';
    expect(create().componentInstance.selected()!.symbol).toBe('MSFT');
  });

  it('shows why there is no signal', () => {
    const fixture = create();
    fixture.componentInstance.select('NEWCO');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Not enough history yet');
  });

  it('reports a failed load and loads nothing without an account', () => {
    advice.forAccount.and.returnValue(throwError(() => ({ status: 500 })));
    const c = create().componentInstance;
    expect(c.error()).toContain('could not be loaded');

    accountId = null;
    c.load();
    expect(c.loading()).toBeFalse();
  });

  it('hands off to the chart, the ticket and automation', () => {
    const c = create().componentInstance;
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);
    c.trade('AAPL');
    expect(navigate).toHaveBeenCalledWith(['/order'], { queryParams: { symbol: 'AAPL' } });
    c.automate('AAPL');
    expect(navigate).toHaveBeenCalledWith(['/automated-trading'], { queryParams: { symbol: 'AAPL' } });
    c.openChart('AAPL');
    expect(TestBed.inject(InstrumentChartService).symbol()).toBe('AAPL');
  });

  it('labels zones and factors', () => {
    const c = create().componentInstance;
    expect(c.rsiZone(25)).toBe('Oversold');
    expect(c.rsiZone(75)).toBe('Overbought');
    expect(c.rsiZone(50)).toBe('Neutral');
    expect(c.rsiZone(null)).toBe('');
    expect(c.scoreLabel(1)).toBe('Bullish');
    expect(c.scoreLabel(-1)).toBe('Bearish');
    expect(c.scoreLabel(0)).toBe('Neutral');
    expect(c.sourceLabel('HOLDING')).toBe('Held');
    expect(c.sourceLabel('REQUESTED')).toBe('Searched');
    expect(c.between(null, 1, 2)).toBe(50);
    expect(c.actionClass(signal('X', null, 0))).toBe('act-none');
  });
});
