import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  AdviceService,
  type Methodology,
  type SignalAction,
  type TradeSignal,
} from '../../core/services/advice.service';
import { TokenService } from '../../core/services/token.service';
import { PriceChartComponent } from '../../shared/price-chart/price-chart.component';
import { InstrumentChartService } from '../../shared/instrument-chart/instrument-chart.service';

type Filter = 'ALL' | SignalAction;

const SYMBOL_RE = /^[A-Z0-9.:-]{1,20}$/;

/**
 * Trade advice: a BUY, SELL or HOLD view on every instrument the customer holds or
 * watches, or any symbol they ask about, with the five scored factors, the indicator
 * values and the formulas behind them. It informs; it never trades.
 */
@Component({
  selector: 'app-advice',
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule, PriceChartComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './advice.component.html',
  styleUrl: './advice.component.scss',
})
export class AdviceComponent implements OnInit {
  private readonly advice = inject(AdviceService);
  private readonly tokens = inject(TokenService);
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly router = inject(Router);
  private readonly charts = inject(InstrumentChartService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly signals = signal<TradeSignal[]>([]);
  readonly asOf = signal<string | null>(null);
  readonly disclaimer = signal<string>('Educational signal computed from delayed public price data. Not investment advice.');
  readonly methodology = signal<Methodology | null>(null);
  readonly showMethod = signal(false);

  readonly filter = signal<Filter>('ALL');
  readonly selectedSymbol = signal<string | null>(null);
  /** A symbol analysed on request that is not held or watched. */
  readonly extra = signal<TradeSignal | null>(null);
  readonly query = signal('');
  readonly searching = signal(false);
  readonly searchError = signal<string | null>(null);

  readonly counts = computed(() => {
    const list = this.signals();
    return {
      ALL: list.length,
      BUY: list.filter((s) => s.action === 'BUY').length,
      SELL: list.filter((s) => s.action === 'SELL').length,
      HOLD: list.filter((s) => s.action === 'HOLD').length,
    };
  });

  readonly filtered = computed(() => {
    const f = this.filter();
    return f === 'ALL' ? this.signals() : this.signals().filter((s) => s.action === f);
  });

  readonly selected = computed<TradeSignal | null>(() => {
    const symbol = this.selectedSymbol();
    if (!symbol) {
      return null;
    }
    const extra = this.extra();
    if (extra?.symbol === symbol) {
      return extra;
    }
    return this.signals().find((s) => s.symbol === symbol) ?? null;
  });

  readonly filters: Filter[] = ['ALL', 'BUY', 'SELL', 'HOLD'];

  ngOnInit(): void {
    this.load();
    this.advice.methodology().subscribe({ next: (m) => this.methodology.set(m), error: () => undefined });
  }

  load(): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.advice.forAccount(accountId).subscribe({
      next: (r) => {
        this.signals.set(this.sorted(r.signals));
        this.asOf.set(r.asOf);
        this.disclaimer.set(r.disclaimer);
        this.loading.set(false);
        const linked = this.route?.snapshot.queryParamMap.get('symbol')?.trim().toUpperCase();
        if (linked && !this.selectedSymbol()) {
          this.select(linked);
        } else if (!this.selectedSymbol() && r.signals.length) {
          this.selectedSymbol.set(this.sorted(r.signals)[0].symbol);
        }
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Advice could not be loaded. Try again in a moment.');
      },
    });
  }

  /** Signals first, strongest conviction first; no-signal rows last. */
  private sorted(list: TradeSignal[]): TradeSignal[] {
    const rank = (s: TradeSignal) => (s.status === 'SIGNAL' ? (s.action === 'HOLD' ? 1 : 2) : 0);
    return [...list].sort((a, b) => rank(b) - rank(a) || Math.abs(b.score) - Math.abs(a.score) || a.symbol.localeCompare(b.symbol));
  }

  /** Selects a listed symbol, or analyses one that is not listed. */
  select(symbol: string): void {
    const s = symbol.trim().toUpperCase();
    if (this.signals().some((x) => x.symbol === s)) {
      this.selectedSymbol.set(s);
      return;
    }
    this.analyse(s);
  }

  analyse(raw?: string): void {
    const symbol = (raw ?? this.query()).trim().toUpperCase();
    this.searchError.set(null);
    if (!SYMBOL_RE.test(symbol)) {
      this.searchError.set('Enter a symbol such as AAPL, MSFT or INFY.NS.');
      return;
    }
    if (this.signals().some((x) => x.symbol === symbol)) {
      this.selectedSymbol.set(symbol);
      this.query.set('');
      return;
    }
    const accountId = this.tokens.getAccountId();
    if (!accountId) {
      return;
    }
    this.searching.set(true);
    this.advice.forSymbol(accountId, symbol).subscribe({
      next: (signal) => {
        this.extra.set(signal);
        this.selectedSymbol.set(signal.symbol);
        this.searching.set(false);
        this.query.set('');
      },
      error: (e: { status?: number; error?: { message?: string } }) => {
        this.searching.set(false);
        this.searchError.set(e?.status === 404
          ? `${symbol} is not a known instrument.`
          : e?.error?.message ?? `${symbol} could not be analysed right now.`);
      },
    });
  }

  openChart(symbol: string): void {
    this.charts.open(symbol);
  }

  trade(symbol: string): void {
    this.router.navigate(['/order'], { queryParams: { symbol } });
  }

  automate(symbol: string): void {
    this.router.navigate(['/automated-trading'], { queryParams: { symbol } });
  }

  // ----- presentation helpers --------------------------------------------------

  actionClass(s: TradeSignal): string {
    return s.status === 'SIGNAL' && s.action ? 'act-' + s.action.toLowerCase() : 'act-none';
  }

  /** Marker position on the -5..+5 score scale, 0 to 100 percent. */
  scorePos(score: number): number {
    return ((Math.max(-5, Math.min(5, score)) + 5) / 10) * 100;
  }

  /** Where a value sits between a low and a high, 0 to 100 percent, clamped. */
  between(value: number | null, low: number | null, high: number | null): number {
    if (value === null || low === null || high === null || high <= low) {
      return 50;
    }
    return Math.max(0, Math.min(100, ((value - low) / (high - low)) * 100));
  }

  rsiZone(rsi: number | null): string {
    if (rsi === null) return '';
    if (rsi < 30) return 'Oversold';
    if (rsi > 70) return 'Overbought';
    return 'Neutral';
  }

  scoreLabel(score: number): string {
    return score > 0 ? 'Bullish' : score < 0 ? 'Bearish' : 'Neutral';
  }

  sourceLabel(source: string): string {
    return source === 'HOLDING' ? 'Held' : source === 'WATCHLIST' ? 'Watching' : 'Searched';
  }

  pct(value: number | null): number {
    return value === null ? 0 : value * 100;
  }
}
