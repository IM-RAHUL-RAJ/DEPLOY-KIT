import { ChangeDetectionStrategy, Component, HostListener, computed, effect, inject, signal, untracked } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Router } from '@angular/router';
import { Subscription, combineLatest, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { InstrumentChartService } from './instrument-chart.service';
import { PriceChartComponent } from '../price-chart/price-chart.component';
import { MarketDataService, previousClose, type CandleSeries } from '../../core/services/market-data.service';
import type { InstrumentQuote } from '../../core/services/trade.service';

/**
 * The instrument view opened by clicking any symbol: live price, day change, the chart
 * across ranges, and the next step from here (trade, advice, automate, alert).
 */
@Component({
  selector: 'app-instrument-chart-dialog',
  standalone: true,
  imports: [DecimalPipe, PriceChartComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './instrument-chart-dialog.component.scss',
  template: `
    @if (charts.symbol(); as symbol) {
      <div class="icd-backdrop" (click)="close()"></div>
      <section class="icd" role="dialog" aria-modal="true" [attr.aria-label]="symbol + ' chart'">
        <header class="icd-head">
          <div class="icd-title">
            <span class="icd-symbol">{{ symbol }}</span>
            @if (quote(); as q) {
              @if (q.stale) {
                <span class="pill pill-warn" title="The last quote is older than the refresh window">Delayed</span>
              }
            }
          </div>
          <div class="icd-price">
            @if (livePrice() !== null) {
              <span class="icd-last">{{ livePrice() | number: '1.2-2' }}</span>
              <span class="icd-ccy">{{ currency() }}</span>
              @if (dayChange(); as d) {
                <span class="icd-change" [class.up]="d.change >= 0" [class.down]="d.change < 0">
                  {{ d.change >= 0 ? '▲' : '▼' }} {{ d.change | number: '1.2-2' }}
                  ({{ d.percent >= 0 ? '+' : '' }}{{ d.percent | number: '1.2-2' }}%) today
                </span>
              }
            } @else {
              <span class="icd-muted">Fetching price…</span>
            }
          </div>
          <button type="button" class="icd-close" (click)="close()" aria-label="Close chart">
            <svg viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></svg>
          </button>
        </header>

        @if (quote(); as q) {
          <div class="icd-quote">
            <div><span>Bid</span><b>{{ q.bid !== undefined && q.bid !== null ? (q.bid | number: '1.2-2') : '—' }}</b></div>
            <div><span>Ask</span><b>{{ q.ask !== undefined && q.ask !== null ? (q.ask | number: '1.2-2') : '—' }}</b></div>
            <div><span>Spread</span><b>{{ spread() !== null ? (spread()! | number: '1.2-4') : '—' }}</b></div>
            <div><span>Prev close</span><b>{{ prevClose() !== null ? (prevClose()! | number: '1.2-2') : '—' }}</b></div>
          </div>
        }

        <app-price-chart [symbol]="symbol" [height]="400" />

        <footer class="icd-actions">
          <button type="button" class="btn-accent" (click)="go('/order', symbol)">Trade {{ symbol }}</button>
          <button type="button" class="btn-ghost" (click)="go('/advice', symbol)">Trade advice</button>
          <button type="button" class="btn-ghost" (click)="go('/automated-trading', symbol)">Automate</button>
          <button type="button" class="btn-ghost" (click)="go('/alerts', symbol)">Set alert</button>
        </footer>
      </section>
    }
  `,
})
export class InstrumentChartDialogComponent {
  readonly charts = inject(InstrumentChartService);
  private readonly market = inject(MarketDataService);
  private readonly router = inject(Router);

  readonly quote = signal<InstrumentQuote | null>(null);
  readonly series = signal<CandleSeries | null>(null);
  private sub?: Subscription;

  readonly livePrice = computed(() => {
    const q = this.quote();
    if (q?.price !== undefined && q?.price !== null) {
      return q.price;
    }
    const c = this.series()?.candles;
    return c && c.length ? c[c.length - 1].close : null;
  });

  readonly currency = computed(() => this.quote()?.quoteCurrency ?? this.series()?.currency ?? '');
  readonly prevClose = computed(() => previousClose(this.series()?.candles ?? []));

  readonly dayChange = computed(() => {
    const price = this.livePrice();
    const prev = this.prevClose();
    if (price === null || prev === null || !prev) {
      return null;
    }
    return { change: price - prev, percent: ((price - prev) / prev) * 100 };
  });

  readonly spread = computed(() => {
    const q = this.quote();
    return q?.bid !== undefined && q?.bid !== null && q?.ask !== undefined && q?.ask !== null ? q.ask - q.bid : null;
  });

  constructor() {
    effect(() => {
      const symbol = this.charts.symbol();
      untracked(() => {
        this.sub?.unsubscribe();
        this.quote.set(null);
        this.series.set(null);
        if (!symbol) {
          return;
        }
        this.sub = combineLatest([
          this.market.quote(symbol).pipe(catchError(() => of(null))),
          this.market.candles(symbol).pipe(catchError(() => of(null))),
        ]).subscribe(([q, s]) => {
          this.quote.set(q);
          this.series.set(s);
        });
      });
    });
  }

  @HostListener('document:keydown.escape')
  close(): void {
    this.charts.close();
  }

  go(path: string, symbol: string): void {
    this.charts.close();
    this.router.navigate([path], { queryParams: { symbol } });
  }
}
