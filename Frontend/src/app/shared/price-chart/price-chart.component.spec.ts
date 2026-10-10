import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PriceChartComponent, niceStep, rangeStart, rollingSma, rollingStd } from './price-chart.component';
import type { Candle } from '../../core/services/market-data.service';
import { environment } from '../../../environments/environment';

const URL = `${environment.tradeApiUrl}/api/v1/instruments/AAPL/candles`;
const DAY = 86_400_000;

/** n daily candles ending 2026-10-07, closing 100, 101, 102 ... */
function series(n: number) {
  const end = Date.UTC(2026, 9, 7);
  return Array.from({ length: n }, (_, i) => {
    const c = 100 + i;
    return { t: (end - (n - 1 - i) * DAY) / 1000, o: c - 0.5, h: c + 1, l: c - 1, c, v: 1000 + i };
  });
}

function candles(n: number): Candle[] {
  return series(n).map((s) => ({
    time: s.t * 1000, date: new Date(s.t * 1000).toISOString().slice(0, 10),
    open: s.o, high: s.h, low: s.l, close: s.c, volume: s.v,
  }));
}

describe('price chart maths', () => {
  it('computes a rolling SMA aligned to the series, null before a full window', () => {
    expect(rollingSma([1, 2, 3, 4, 5], 3)).toEqual([null, null, 2, 3, 4]);
  });

  it('computes a rolling population standard deviation', () => {
    const sd = rollingStd([2, 4, 4, 4, 5, 5, 7, 9], 8);
    expect(sd[7]).toBe(2);
    expect(sd[6]).toBeNull();
  });

  it('cuts a range from the last candle backwards', () => {
    const c = candles(400);
    expect(c.length - rangeStart(c, '1W')).toBe(7);
    expect(c.length - rangeStart(c, '1M')).toBe(30);
    expect(rangeStart(c, '1Y')).toBe(c.length - 365);
    // Year to date: from 1 January of the last candle's year.
    expect(c[rangeStart(c, 'YTD')].date).toBe('2026-01-01');
    expect(rangeStart([], '1M')).toBe(0);
  });

  it('chooses round tick steps', () => {
    expect(niceStep(100, 5)).toBe(20);
    expect(niceStep(9, 5)).toBe(2);
    expect(niceStep(7, 5)).toBe(1);
    expect(niceStep(0.37, 5)).toBe(0.1);
    expect(niceStep(0, 5)).toBe(1);
  });
});

describe('PriceChartComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem('trading_ui_preferences');
    TestBed.configureTestingModule({
      imports: [PriceChartComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function create(n = 120) {
    const fixture = TestBed.createComponent(PriceChartComponent);
    fixture.componentRef.setInput('symbol', 'AAPL');
    fixture.detectChanges();
    http.expectOne(URL).flush({ symbol: 'AAPL', currency: 'USD', interval: '1d', asOf: null, candles: series(n) });
    fixture.detectChanges();
    return fixture;
  }

  it('draws candles, volume and averages for the default three months', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    const el = fixture.nativeElement as HTMLElement;
    expect(c.range()).toBe('3M');
    expect(el.querySelectorAll('rect.body').length).toBe(c.visible().length);
    expect(el.querySelectorAll('rect.vol').length).toBe(c.visible().length);
    expect(el.querySelectorAll('path.overlay').length).toBe(2); // SMA 20 and 50
    expect(el.textContent).toContain('USD');
  });

  it('measures the range change against the close before the range began', () => {
    const c = create().componentInstance;
    const all = c.candles();
    const start = c.start();
    const s = c.stats()!;
    expect(s.last).toBe(all[all.length - 1].close);
    expect(s.change).toBeCloseTo(all[all.length - 1].close - all[start - 1].close, 6);
    expect(s.high).toBe(Math.max(...c.visible().map((x) => x.high)));
    expect(s.low).toBe(Math.min(...c.visible().map((x) => x.low)));
  });

  it('switches range, style and overlays', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    c.setRange('1W');
    fixture.detectChanges();
    expect(c.visible().length).toBe(7);

    c.setStyle('line');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('path.price-line')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('rect.body')).toBeNull();

    c.setStyle('area');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('path.area')).not.toBeNull();

    c.toggleOverlay('bollinger');
    c.toggleOverlay('sma20');
    fixture.detectChanges();
    expect(c.active().has('bollinger')).toBeTrue();
    expect(c.active().has('sma20')).toBeFalse();
    expect(fixture.nativeElement.querySelectorAll('path.overlay.dashed').length).toBe(2);

    c.showVolume.set(false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('rect.vol')).toBeNull();
  });

  it('reads the bar under the pointer and clears on leave', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    c.hover.set(c.visible().length - 1);
    fixture.detectChanges();
    const h = c.hovered()!;
    expect(h.bar.close).toBe(c.visible()[c.visible().length - 1].close);
    expect(h.change).toBeCloseTo(1, 6);
    expect(h.sma20).not.toBeNull();
    expect(c.crosshair()).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('O ');
    c.onPointerLeave();
    expect(c.hovered()).toBeNull();
  });

  it('maps a pointer position to a bar', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    const svg = fixture.nativeElement.querySelector('svg.pc-svg') as SVGSVGElement;
    const rect = svg.getBoundingClientRect();
    c.onPointerMove({ currentTarget: svg, clientX: rect.left + rect.width * 0.5 } as unknown as PointerEvent);
    expect(c.hover()).not.toBeNull();
    c.onPointerMove({ currentTarget: svg, clientX: rect.left - 50 } as unknown as PointerEvent);
    expect(c.hover()).toBeNull();
  });

  it('waits and retries while the poller fetches the history', fakeAsync(() => {
    const fixture = TestBed.createComponent(PriceChartComponent);
    fixture.componentRef.setInput('symbol', 'AAPL');
    fixture.detectChanges();
    http.expectOne(URL).flush({ errorCode: 'MKT-503' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.componentInstance.pending()).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Fetching price history');
    tick(2600);
    http.expectOne(URL).flush({ symbol: 'AAPL', currency: 'USD', interval: '1d', asOf: null, candles: series(30) });
    fixture.detectChanges();
    expect(fixture.componentInstance.pending()).toBeFalse();
    expect(fixture.componentInstance.candles().length).toBe(30);
  }));

  it('says so when there is no history, and can try again', () => {
    const fixture = TestBed.createComponent(PriceChartComponent);
    fixture.componentRef.setInput('symbol', 'AAPL');
    fixture.detectChanges();
    http.expectOne(URL).flush({}, { status: 404, statusText: 'Not found' });
    fixture.detectChanges();
    expect(fixture.componentInstance.error()).toContain('No price history for AAPL');

    fixture.componentInstance.retry();
    http.expectOne(URL).flush({}, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(fixture.componentInstance.error()).toContain('unavailable');
  });

  it('formats volumes and dates for the readout', () => {
    const c = create().componentInstance;
    expect(c.formatVolume(1_500)).toBe('1.5K');
    expect(c.formatVolume(2_340_000)).toBe('2.34M');
    expect(c.formatVolume(7_100_000_000)).toBe('7.10B');
    expect(c.formatVolume(12)).toBe('12');
    expect(c.formatVolume(null)).toBe('—');
    expect(c.formatDate(Date.UTC(2026, 9, 7))).toContain('2026');
  });

  it('uses the overlays it is given', () => {
    const fixture = TestBed.createComponent(PriceChartComponent);
    fixture.componentRef.setInput('symbol', 'AAPL');
    fixture.componentRef.setInput('overlays', ['bollinger']);
    fixture.detectChanges();
    http.expectOne(URL).flush({ symbol: 'AAPL', currency: 'USD', interval: '1d', asOf: null, candles: series(60) });
    expect([...fixture.componentInstance.active()]).toEqual(['bollinger']);
    expect(fixture.componentInstance.availableOverlays()).not.toContain('sma200');
  });
});
