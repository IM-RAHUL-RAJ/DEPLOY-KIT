import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { InstrumentChartDialogComponent } from './instrument-chart-dialog.component';
import { InstrumentChartService } from './instrument-chart.service';
import { ChartSymbolDirective } from './chart-symbol.directive';
import { TradeService } from '../../core/services/trade.service';
import { environment } from '../../../environments/environment';

const CANDLES = `${environment.tradeApiUrl}/api/v1/instruments/AAPL/candles`;

@Component({
  standalone: true,
  imports: [ChartSymbolDirective],
  template: `<span id="sym" [appChartSymbol]="'aapl'">AAPL</span>`,
})
class HostComponent {}

describe('Instrument chart', () => {
  let http: HttpTestingController;
  let quote: jasmine.Spy;

  beforeEach(() => {
    quote = jasmine.createSpy('getInstrumentQuote').and.returnValue(
      of({ symbol: 'AAPL', price: 110, bid: 109.9, ask: 110.1, quoteCurrency: 'USD', stale: false }));
    TestBed.configureTestingModule({
      imports: [InstrumentChartDialogComponent, HostComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        { provide: TradeService, useValue: { getInstrumentQuote: quote } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('opens from any symbol marked with the directive, by click or Enter', () => {
    const charts = TestBed.inject(InstrumentChartService);
    const host = TestBed.createComponent(HostComponent);
    host.detectChanges();
    const el = host.nativeElement.querySelector('#sym') as HTMLElement;
    expect(el.getAttribute('role')).toBe('button');
    expect(el.classList).toContain('chart-symbol');
    el.click();
    expect(charts.symbol()).toBe('AAPL');
    charts.close();
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(charts.symbol()).toBe('AAPL');
    charts.open('  ');
    expect(charts.symbol()).toBe('AAPL');
  });

  it('shows the live price, the change on the previous close, the quote and the chart', () => {
    const charts = TestBed.inject(InstrumentChartService);
    const fixture = TestBed.createComponent(InstrumentChartDialogComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.icd')).toBeNull();

    charts.open('AAPL');
    fixture.detectChanges();
    // Two closes on past days: the previous close is the last one, 100.
    const day = 86_400;
    const t = Date.UTC(2020, 0, 2) / 1000;
    for (const req of http.match(CANDLES)) {
      req.flush({ symbol: 'AAPL', currency: 'USD', interval: '1d', asOf: null,
        candles: [{ t, o: 98, h: 99, l: 97, c: 98, v: 1 }, { t: t + day, o: 99, h: 101, l: 98, c: 100, v: 2 }] });
    }
    fixture.detectChanges();

    const c = fixture.componentInstance;
    expect(c.livePrice()).toBe(110);
    expect(c.prevClose()).toBe(100);
    expect(c.dayChange()!.percent).toBeCloseTo(10, 6);
    expect(c.spread()).toBeCloseTo(0.2, 6);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('AAPL');
    expect(text).toContain('110.00');
    expect(text).toContain('+10.00%');
    expect(fixture.nativeElement.querySelector('app-price-chart')).not.toBeNull();
  });

  it('closes on Escape and hands the symbol to the next page', () => {
    const charts = TestBed.inject(InstrumentChartService);
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);
    const fixture = TestBed.createComponent(InstrumentChartDialogComponent);
    charts.open('AAPL');
    fixture.detectChanges();
    http.match(CANDLES).forEach((r) => r.flush({ symbol: 'AAPL', candles: [] }));

    fixture.componentInstance.go('/advice', 'AAPL');
    expect(navigate).toHaveBeenCalledWith(['/advice'], { queryParams: { symbol: 'AAPL' } });
    expect(charts.symbol()).toBeNull();

    charts.open('AAPL');
    fixture.detectChanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(charts.symbol()).toBeNull();
  });
});
