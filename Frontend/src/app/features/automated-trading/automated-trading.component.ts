import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subscription, catchError, forkJoin, interval, of } from 'rxjs';
import {
  MAX_STRATEGIES,
  StrategiesService,
  type CreateStrategy,
  type OrderSide,
  type Strategy,
  type StrategyExecution,
  type StrategyPreview,
  type StrategyStatus,
} from '../../core/services/strategies.service';
import { MarketDataService, previousClose } from '../../core/services/market-data.service';
import { TradeService, type InstrumentQuote } from '../../core/services/trade.service';
import { WatchlistService } from '../../core/services/watchlist.service';
import { TokenService } from '../../core/services/token.service';
import { CurrencyService } from '../../core/services/currency.service';
import { PriceChartComponent } from '../../shared/price-chart/price-chart.component';
import { InstrumentChartService } from '../../shared/instrument-chart/instrument-chart.service';

/** An instrument the customer can pick without typing: held or watched. */
interface Pick {
  symbol: string;
  price: number | null;
  currency: string | null;
  held: number;
  watched: boolean;
}

interface Detail {
  preview: StrategyPreview | null;
  executions: StrategyExecution[];
  loading: boolean;
}

type BoardFilter = 'ALL' | StrategyStatus;

const SYMBOL_RE = /^[A-Z0-9.:-]{1,20}$/;

/**
 * Automated trading, kept to one idea: pick an instrument, buy or sell, a quantity and the
 * price to act at. Once armed, the platform reads the market poller's quotes and places a
 * market order the first time the price reaches the target, then marks it done.
 *
 * <p>Prices are typed and shown in the customer's display currency and converted to the
 * instrument's own currency before they are stored, because that is the currency the
 * poller's quotes are compared in.
 */
@Component({
  selector: 'app-automated-trading',
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule, PriceChartComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './automated-trading.component.html',
  styleUrl: './automated-trading.component.scss',
})
export class AutomatedTradingComponent implements OnInit {
  private readonly api = inject(StrategiesService);
  private readonly market = inject(MarketDataService);
  private readonly trade = inject(TradeService);
  private readonly watchlists = inject(WatchlistService);
  private readonly tokens = inject(TokenService);
  private readonly route = inject(ActivatedRoute, { optional: true });
  readonly money = inject(CurrencyService);
  readonly charts = inject(InstrumentChartService);

  readonly maxStrategies = MAX_STRATEGIES;

  // ----- board -------------------------------------------------------------------
  readonly strategies = signal<Strategy[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly boardFilter = signal<BoardFilter>('ALL');
  readonly details = signal<Record<number, Detail>>({});
  readonly busyId = signal<number | null>(null);
  readonly confirmDelete = signal<Strategy | null>(null);
  /** Each instrument's own currency, learnt from its quote, so stored prices can be shown converted. */
  private readonly nativeCurrency = signal<Record<string, string>>({});

  readonly counts = computed(() => {
    const list = this.strategies();
    return {
      ALL: list.length,
      ACTIVE: list.filter((s) => s.status === 'ACTIVE').length,
      DISABLED: list.filter((s) => s.status === 'DISABLED').length,
      STOPPED: list.filter((s) => s.status === 'STOPPED').length,
      COMPLETED: list.filter((s) => s.status === 'COMPLETED').length,
    };
  });
  /** Completed targets keep their history but free their slot. */
  readonly slotsLeft = computed(() =>
    Math.max(0, MAX_STRATEGIES - this.strategies().filter((s) => s.status !== 'COMPLETED').length));
  readonly board = computed(() => {
    const f = this.boardFilter();
    return f === 'ALL' ? this.strategies() : this.strategies().filter((s) => s.status === f);
  });
  readonly filters: { value: BoardFilter; label: string }[] = [
    { value: 'ALL', label: 'All' },
    { value: 'ACTIVE', label: 'Active' },
    { value: 'DISABLED', label: 'Paused' },
    { value: 'COMPLETED', label: 'Done' },
    { value: 'STOPPED', label: 'Stopped' },
  ];

  // ----- composer: instrument ----------------------------------------------------
  readonly picks = signal<Pick[]>([]);
  readonly symbol = signal<string | null>(null);
  readonly symbolInput = signal('');
  readonly quote = signal<InstrumentQuote | null>(null);
  readonly prevClose = signal<number | null>(null);
  readonly quoteLoading = signal(false);
  readonly quoteError = signal<string | null>(null);
  private quoteSub?: Subscription;
  private boardRefreshSub?: Subscription;
  private static readonly BOARD_REFRESH_MS = 10000;

  /** The instrument's price and currency as the poller has them. */
  readonly price = computed(() => this.quote()?.price ?? this.picks().find((p) => p.symbol === this.symbol())?.price ?? null);
  readonly currency = computed(() => this.quote()?.quoteCurrency ?? this.picks().find((p) => p.symbol === this.symbol())?.currency ?? '');
  /** The currency the customer types and reads prices in. */
  readonly display = computed(() => this.money.selected());
  /** Today's price in the display currency. */
  readonly displayPrice = computed(() => {
    const p = this.price();
    return p === null ? null : round2(this.money.convert(this.currency(), this.display(), p));
  });
  readonly held = computed(() => this.picks().find((p) => p.symbol === this.symbol())?.held ?? 0);
  readonly dayChange = computed(() => {
    const p = this.price();
    const prev = this.prevClose();
    return p !== null && prev ? { change: p - prev, percent: ((p - prev) / prev) * 100 } : null;
  });

  // ----- composer: the order -------------------------------------------------------
  readonly side = signal<OrderSide>('BUY');
  /** In the display currency. */
  readonly targetPrice = signal<number | null>(null);
  readonly quantity = signal(1);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  private targetEdited = false;

  /** How far the target is from today's price, in percent; negative is below. */
  readonly targetGap = computed(() => {
    const p = this.displayPrice();
    const t = this.targetPrice();
    return p && t ? ((t - p) / p) * 100 : null;
  });
  /** True when the target is already met, so the order would go on the next quote. */
  readonly targetMetNow = computed(() => {
    const p = this.displayPrice();
    const t = this.targetPrice();
    if (p === null || t === null) {
      return false;
    }
    return this.side() === 'BUY' ? p <= t : p >= t;
  });
  readonly targetValue = computed(() => {
    const t = this.targetPrice();
    return t !== null && this.quantity() > 0 ? t * this.quantity() : null;
  });

  ngOnInit(): void {
    this.loadBoard();
    this.loadPicks();
    this.startBoardRefresh();
    const linked = this.route?.snapshot.queryParamMap.get('symbol');
    if (linked) {
      this.chooseSymbol(linked);
    }
  }

  ngOnDestroy(): void {
    this.quoteSub?.unsubscribe();
    this.boardRefreshSub?.unsubscribe();
  }

  private startBoardRefresh(): void {
    this.boardRefreshSub?.unsubscribe();
    this.boardRefreshSub = interval(AutomatedTradingComponent.BOARD_REFRESH_MS).subscribe(() =>
      this.loadBoardQuietly());
  }

  private accountId(): number | null {
    return this.tokens.getAccountId();
  }

  loadBoard(): void {
    const id = this.accountId();
    if (!id) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.api.list(id).subscribe({
      next: (list) => {
        this.strategies.set(list);
        this.loading.set(false);
        this.learnCurrencies(list.map((s) => s.symbol));
      },
      error: () => {
        this.error.set('Your automated orders could not be loaded.');
        this.loading.set(false);
      },
    });
  }

  /** Reads the currency of each instrument on the board that is not known yet. */
  private learnCurrencies(symbols: string[]): void {
    const known = this.nativeCurrency();
    for (const symbol of [...new Set(symbols)].filter((s) => !known[s])) {
      this.market.quote(symbol).pipe(catchError(() => of(null))).subscribe((q) => {
        if (q?.quoteCurrency) {
          this.rememberCurrency(symbol, q.quoteCurrency);
        }
      });
    }
  }

  private rememberCurrency(symbol: string, currency: string | null | undefined): void {
    if (currency) {
      this.nativeCurrency.update((m) => (m[symbol] === currency ? m : { ...m, [symbol]: currency }));
    }
  }

  private loadPicks(): void {
    const id = this.accountId();
    if (!id) {
      return;
    }
    forkJoin({
      positions: this.trade.getPositions(id).pipe(catchError(() => of([]))),
      watchlist: this.watchlists.getWatchlist().pipe(catchError(() => of(null))),
    }).subscribe(({ positions, watchlist }) => {
      const map = new Map<string, Pick>();
      for (const p of positions ?? []) {
        if (p.quantity > 0) {
          map.set(p.symbol, { symbol: p.symbol, price: null, currency: null, held: p.quantity, watched: false });
        }
      }
      for (const i of watchlist?.instruments ?? []) {
        const s = i.symbol || i.ticker;
        const existing = map.get(s);
        map.set(s, {
          symbol: s,
          price: i.currentPrice ?? existing?.price ?? null,
          currency: i.priceCurrency ?? existing?.currency ?? null,
          held: existing?.held ?? 0,
          watched: true,
        });
        this.rememberCurrency(s, i.priceCurrency);
      }
      const list = [...map.values()].sort((a, b) => b.held - a.held || a.symbol.localeCompare(b.symbol));
      this.picks.set(list);
      if (!this.symbol() && list.length) {
        this.chooseSymbol(list[0].symbol);
      }
    });
  }

  // ----- composer actions --------------------------------------------------------

  submitSymbol(): void {
    const s = this.symbolInput().trim().toUpperCase();
    if (!SYMBOL_RE.test(s)) {
      this.quoteError.set('Enter a symbol such as AAPL, MSFT or INFY.NS.');
      return;
    }
    this.symbolInput.set('');
    this.chooseSymbol(s);
  }

  chooseSymbol(raw: string): void {
    const symbol = raw.trim().toUpperCase();
    if (!symbol) {
      return;
    }
    this.symbol.set(symbol);
    this.quote.set(null);
    this.prevClose.set(null);
    this.quoteError.set(null);
    this.formError.set(null);
    this.targetEdited = false;
    this.targetPrice.set(null);
    this.quoteLoading.set(true);
    this.quoteSub?.unsubscribe();
    this.quoteSub = forkJoin({
      quote: this.market.quote(symbol).pipe(catchError((e: unknown) => of({ failed: e }))),
      candles: this.market.candles(symbol).pipe(catchError(() => of(null))),
    }).subscribe(({ quote, candles }) => {
      this.quoteLoading.set(false);
      if ('failed' in quote) {
        const status = (quote.failed as { status?: number })?.status;
        this.quoteError.set(status === 404
          ? `${symbol} is not a known instrument.`
          : 'The live price is not available yet; it will appear shortly.');
      } else {
        this.quote.set(quote);
        this.rememberCurrency(symbol, quote.quoteCurrency);
      }
      this.prevClose.set(candles ? previousClose(candles.candles) : null);
      this.suggestTarget();
    });
  }

  setSide(side: OrderSide): void {
    this.side.set(side);
    this.suggestTarget();
  }

  setTarget(v: number | string | null): void {
    this.targetEdited = true;
    this.targetPrice.set(v === null || v === '' ? null : Number(v));
  }

  /** Nudge the target a percentage away from today's price. */
  targetAt(percent: number): void {
    const p = this.displayPrice();
    if (p !== null) {
      this.targetEdited = true;
      this.targetPrice.set(round2(p * (1 + percent / 100)));
    }
  }

  /** Until the customer types one, suggest a target 2% better than today's price. */
  private suggestTarget(): void {
    if (this.targetEdited) {
      return;
    }
    const p = this.displayPrice();
    this.targetPrice.set(p === null ? null : round2(p * (this.side() === 'BUY' ? 0.98 : 1.02)));
  }

  setQuantity(q: number | string): void {
    this.quantity.set(Math.max(1, Math.floor(Number(q) || 1)));
  }

  step(delta: number): void {
    this.setQuantity(this.quantity() + delta);
  }

  create(): void {
    // A second click while the first is on its way is the same order, not another one.
    if (this.saving()) {
      return;
    }
    const id = this.accountId();
    const symbol = this.symbol();
    this.formError.set(null);
    this.notice.set(null);
    if (!id) {
      return;
    }
    if (!symbol) {
      this.formError.set('Choose an instrument first.');
      return;
    }
    const target = this.targetPrice();
    if (!target || target <= 0) {
      this.formError.set('Set a target price greater than zero.');
      return;
    }
    if (this.side() === 'SELL' && this.quantity() > this.held()) {
      this.formError.set(`You hold ${this.held()} ${symbol}; a sell can be for at most that many.`);
      return;
    }
    const nativeTarget = round4(this.money.convert(this.display(), this.currency(), target));
    const body: CreateStrategy = {
      symbol,
      strategyType: 'PRICE_TARGET',
      side: this.side(),
      orderType: 'MARKET',
      targetPrice: nativeTarget,
      quantity: this.quantity(),
    };
    this.saving.set(true);
    this.api.create(id, body).subscribe({
      next: (created) => {
        this.saving.set(false);
        // Creating the same target twice answers the one already armed: replace, never duplicate.
        this.strategies.update((list) => [created, ...list.filter((s) => s.strategyId !== created.strategyId)]);
        const shown = this.money.formatNative(this.display(), target);
        this.notice.set(this.targetMetNow()
          ? `${this.side() === 'BUY' ? 'Buy' : 'Sell'} ${this.quantity()} ${symbol} is armed, and the price is already there: it trades at market on the next quote.`
          : `${this.side() === 'BUY' ? 'Buy' : 'Sell'} ${this.quantity()} ${symbol} is armed. It trades once, at market, when the price reaches ${shown}.`);
        this.loadBoardQuietly();
      },
      error: (e) => {
        this.saving.set(false);
        this.formError.set(messageOf(e, 'The order could not be set up.'));
      },
    });
  }

  /** A target already met is traded straight away on the server; pick up its new status. */
  private loadBoardQuietly(): void {
    const id = this.accountId();
    if (id) {
      this.api.list(id).pipe(catchError(() => of(null))).subscribe((list) => list && this.strategies.set(list));
    }
  }

  // ----- board actions ---------------------------------------------------------------

  toggleStatus(s: Strategy): void {
    if (!this.canToggleStatus(s)) {
      return;
    }
    const id = this.accountId();
    if (!id) {
      return;
    }
    this.busyId.set(s.strategyId);
    this.api.setStatus(id, s.strategyId, s.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE').subscribe({
      next: (updated) => {
        this.busyId.set(null);
        this.strategies.update((list) => list.map((x) => (x.strategyId === updated.strategyId ? updated : x)));
      },
      error: (e) => {
        this.busyId.set(null);
        this.error.set(messageOf(e, 'The order could not be updated.'));
      },
    });
  }

  askDelete(s: Strategy): void {
    if (!this.canDelete(s)) {
      return;
    }
    this.confirmDelete.set(s);
  }

  doDelete(): void {
    const id = this.accountId();
    const s = this.confirmDelete();
    this.confirmDelete.set(null);
    if (!id || !s) {
      return;
    }
    this.api.delete(id, s.strategyId).subscribe({
      next: () => this.strategies.update((list) => list.filter((x) => x.strategyId !== s.strategyId)),
      error: (e) => this.error.set(messageOf(e, 'The order could not be deleted.')),
    });
  }

  toggleDetail(s: Strategy): void {
    const id = this.accountId();
    if (!id) {
      return;
    }
    if (this.details()[s.strategyId]) {
      this.details.update(({ [s.strategyId]: _, ...rest }) => rest);
      return;
    }
    this.details.update((d) => ({ ...d, [s.strategyId]: { preview: null, executions: [], loading: true } }));
    forkJoin({
      preview: this.api.preview(id, s.strategyId).pipe(catchError(() => of(null))),
      executions: this.api.executions(id, s.strategyId).pipe(catchError(() => of([] as StrategyExecution[]))),
    }).subscribe(({ preview, executions }) =>
      this.details.update((d) => (d[s.strategyId]
        ? { ...d, [s.strategyId]: { preview, executions, loading: false } }
        : d)));
  }

  // ----- presentation ----------------------------------------------------------------

  /** A price stored in the instrument's currency, shown in the display currency. */
  priceOf(symbol: string, amount: number | null | undefined): string {
    return this.money.displayFrom(this.nativeCurrency()[symbol], amount);
  }

  describe(s: Strategy): string {
    if (s.strategyType === 'PRICE_TARGET') {
      return `${s.side === 'BUY' ? 'Buy' : 'Sell'} ${s.quantity} at ${s.side === 'BUY' ? '≤' : '≥'} ${this.priceOf(s.symbol, s.targetPrice)} · market`;
    }
    // Rules created before automated trading was simplified still show what they are.
    return s.strategyType === 'MA_CROSSOVER'
      ? `${s.shortWindow}/${s.longWindow}-day moving-average crossover`
      : `Bollinger bands · ${s.bandPeriod} days · ${s.bandK}σ`;
  }

  stateLabel(s: Strategy): string {
    if (s.status === 'COMPLETED') {
      return s.stopReason ?? 'Target reached and traded';
    }
    if (!s.lastState) {
      return 'Waiting for the first quote';
    }
    if (s.strategyType === 'PRICE_TARGET') {
      return s.lastState === 'HIT'
        ? 'Target reached'
        : `Waiting for ${s.side === 'BUY' ? '≤' : '≥'} ${this.priceOf(s.symbol, s.targetPrice)} · last ${this.priceOf(s.symbol, s.lastPrice)}`;
    }
    return s.lastState === 'ABOVE' ? 'Above the signal line' : s.lastState === 'BELOW' ? 'Below the signal line' : 'Waiting for a signal';
  }

  statusLabel(status: StrategyStatus): string {
    return status === 'DISABLED' ? 'Paused' : status === 'STOPPED' ? 'Stopped' : status === 'COMPLETED' ? 'Done' : 'Active';
  }

  canToggleStatus(s: Strategy): boolean {
    return s.status !== 'COMPLETED';
  }

  canDelete(s: Strategy): boolean {
    return s.status !== 'COMPLETED';
  }
}

function round2(n: number): number {
  return Math.round(n * 100) / 100;
}

function round4(n: number): number {
  return Math.round(n * 10000) / 10000;
}

function messageOf(e: unknown, fallback: string): string {
  const message = (e as { error?: { message?: unknown } })?.error?.message;
  return typeof message === 'string' && message ? message : fallback;
}
