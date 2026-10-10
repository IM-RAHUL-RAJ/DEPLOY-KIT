import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MarketDataService, previousClose, type Candle } from './market-data.service';
import { UiPreferencesService } from './ui-preferences.service';
import { TradeService } from './trade.service';
import { environment } from '../../../environments/environment';

const URL = `${environment.tradeApiUrl}/api/v1/instruments/AAPL/candles`;

describe('MarketDataService', () => {
  let service: MarketDataService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: TradeService, useValue: { getInstrumentQuote: jasmine.createSpy('q') } }],
    });
    service = TestBed.inject(MarketDataService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('maps candles to dated bars, oldest first, and caches them', () => {
    let first: Candle[] = [];
    service.candles('aapl').subscribe((s) => (first = s.candles));
    http.expectOne(URL).flush({
      symbol: 'AAPL', currency: 'USD', interval: '1d', asOf: null,
      candles: [{ t: 1759881600, o: 2, h: 3, l: 1, c: 2.5, v: 10 }, { t: 1759795200, o: 1, h: 2, l: 0.5, c: 1.5, v: null }],
    });
    expect(first.map((c) => c.close)).toEqual([1.5, 2.5]);
    expect(first[0].date).toBe('2025-10-07');
    expect(first[0].volume).toBeNull();

    service.candles('AAPL').subscribe();
    http.expectNone(URL);
  });

  it('does not cache a failure', () => {
    service.candles('AAPL').subscribe({ error: () => undefined });
    http.expectOne(URL).flush({}, { status: 503, statusText: 'Unavailable' });
    service.candles('AAPL').subscribe();
    http.expectOne(URL).flush({ symbol: 'AAPL', candles: [] });
  });

  it('can be told to forget a symbol', () => {
    service.candles('AAPL').subscribe();
    http.expectOne(URL).flush({ symbol: 'AAPL', candles: [] });
    service.invalidate('aapl');
    service.candles('AAPL').subscribe();
    http.expectOne(URL).flush({ symbol: 'AAPL', candles: [] });
  });

  it('takes the previous close from before today, if today is still forming', () => {
    const bar = (date: string, close: number) => ({ time: 0, date, open: close, high: close, low: close, close, volume: null });
    const today = new Date('2026-10-07T15:00:00Z');
    expect(previousClose([bar('2026-10-06', 10), bar('2026-10-07', 11)], today)).toBe(10);
    expect(previousClose([bar('2026-10-05', 9), bar('2026-10-06', 10)], today)).toBe(10);
    expect(previousClose([bar('2026-10-07', 11)], today)).toBeNull();
    expect(previousClose([], today)).toBeNull();
  });
});

describe('UiPreferencesService', () => {
  beforeEach(() => localStorage.removeItem('trading_ui_preferences'));

  it('defaults to market orders, candles, three months, averages and volume', () => {
    const prefs = TestBed.inject(UiPreferencesService);
    expect(prefs.defaultOrderType()).toBe('MARKET');
    expect(prefs.defaultQuantity()).toBe(1);
    expect(prefs.chartStyle()).toBe('candles');
    expect(prefs.chartRange()).toBe('3M');
    expect(prefs.showAverages()).toBeTrue();
    expect(prefs.showVolume()).toBeTrue();
  });

  it('persists a change and restores the defaults on reset', () => {
    const prefs = TestBed.inject(UiPreferencesService);
    prefs.defaultOrderType.set('LIMIT');
    prefs.chartRange.set('1Y');
    TestBed.tick();
    expect(JSON.parse(localStorage.getItem('trading_ui_preferences')!).defaultOrderType).toBe('LIMIT');

    prefs.reset();
    expect(prefs.defaultOrderType()).toBe('MARKET');
    expect(prefs.chartRange()).toBe('3M');
  });

  it('reads stored preferences and survives a corrupt entry', () => {
    localStorage.setItem('trading_ui_preferences', JSON.stringify({ chartStyle: 'line' }));
    expect(TestBed.inject(UiPreferencesService).chartStyle()).toBe('line');

    TestBed.resetTestingModule();
    localStorage.setItem('trading_ui_preferences', '{not json');
    expect(TestBed.inject(UiPreferencesService).chartStyle()).toBe('candles');
  });
});
