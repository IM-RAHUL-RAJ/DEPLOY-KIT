import {
  ChangeDetectionStrategy,
   Component, OnInit, signal, inject, computed
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { TradeService } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import { CurrencyService } from '../../core/services/currency.service';
import { AccountStateService } from '../../core/services/account-state.service';
import { MarksService } from '../../core/services/marks.service';
import type {
  AccountResponse,
  BalanceResponse,
  PositionResponse,
  OrderHistoryEntry,
} from '@trade-api/model/models';
import { HttpErrorResponse } from '@angular/common/http';
import { resolveErrorMessage } from '../../core/services/error-catalogue';
import { ChartSymbolDirective } from '../../shared/instrument-chart/chart-symbol.directive';
import { InstrumentChartService } from '../../shared/instrument-chart/instrument-chart.service';
import { WatchlistCarouselComponent } from '../../shared/watchlist-carousel/watchlist-carousel.component';
import { WatchlistService, type WatchlistInstrument } from '../../core/services/watchlist.service';

export interface HoldingRow {
  symbol: string;
  quantity: number;
  averageCost: number;
  mark: number;
  /** False when no live quote was available and the row is marked at cost. */
  live: boolean;
  /** Invested value: average cost x quantity, in the ledger currency. */
  value: number;
  /** (live price - average cost) / average cost, or null without a live price. */
  returnPct: number | null;
  /** When this symbol was last bought (latest FILLED BUY), for the "recent" view. */
  lastBought: number;
}

export type HoldingsView = 'value' | 'recent';

/** The dashboard shows a short list; the Holdings page has the rest. */
const DASHBOARD_HOLDINGS = 3;

export interface AllocationSlice {
  label: string;
  value: number;
  pct: number;
  color: string;
}


/** Allocation ring, a red-only ramp. */
const ALLOCATION_COLORS = ['#e12d3e', '#ff5a3c', '#f5c542', '#8c0f1c', '#d8492c', '#5c0a12'];

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, ChartSymbolDirective, WatchlistCarouselComponent],
  templateUrl: './dashboard.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit {
  private readonly trade = inject(TradeService);
  private readonly tokens = inject(TokenService);
  readonly accountState = inject(AccountStateService);
  readonly currency = inject(CurrencyService);
  private readonly marksService = inject(MarksService);
  private readonly watchlistService = inject(WatchlistService);
  readonly charts = inject(InstrumentChartService);

  /** The primary watchlist, shown as a compact strip above the dashboard. */
  readonly watched = signal<WatchlistInstrument[]>([]);
  readonly watchedLoaded = signal(false);

  readonly loading = signal(true);
  readonly account = signal<AccountResponse | null>(null);
  readonly balance = signal<BalanceResponse | null>(null);
  readonly positions = signal<PositionResponse[]>([]);
  readonly orders = signal<OrderHistoryEntry[]>([]);
  readonly errorMessage = signal<string | null>(null);

  /** Live marks per held symbol, in the account currency, from our quote endpoint. */
  readonly marks = signal<Record<string, number>>({});

  readonly holdingsView = signal<HoldingsView>('value');

  /** Latest FILLED BUY per symbol, so "recently bought" reflects real fills. */
  private readonly lastBoughtAt = computed(() => {
    const latest: Record<string, number> = {};
    for (const o of this.orders()) {
      if (o.side !== 'BUY' || o.status !== 'FILLED') continue;
      const at = Date.parse(o.createdOn);
      if (!Number.isNaN(at) && at > (latest[o.symbol] ?? 0)) latest[o.symbol] = at;
    }
    return latest;
  });

  readonly holdings = computed<HoldingRow[]>(() =>
    this.positions().map((p) => {
      const averageCost = p.averageCost ?? 0;
      const live = this.marks()[p.symbol];
      const mark = live ?? averageCost;
      const value = p.quantity * averageCost;
      return {
        symbol: p.symbol,
        quantity: p.quantity,
        averageCost,
        mark,
        live: live !== undefined,
        value,
        returnPct: live !== undefined && averageCost > 0 ? ((live - averageCost) / averageCost) * 100 : null,
        lastBought: this.lastBoughtAt()[p.symbol] ?? 0,
      };
    })
  );

  /** The three largest holdings by invested value, or the three most recently bought. */
  readonly topHoldings = computed<HoldingRow[]>(() => {
    const rows = [...this.holdings()];
    if (this.holdingsView() === 'recent') {
      rows.sort((a, b) => b.lastBought - a.lastBought || b.value - a.value);
    } else {
      rows.sort((a, b) => b.value - a.value);
    }
    return rows.slice(0, DASHBOARD_HOLDINGS);
  });

  /** Positions at cost: the sum of average cost x quantity over every holding. */
  readonly positionsValue = computed(() => this.holdings().reduce((sum, h) => sum + h.value, 0));
  readonly cash = computed(() => this.balance()?.cashBalance ?? 0);
  /** Total equity: cash plus positions at cost. */
  readonly totalEquity = computed(() => this.cash() + this.positionsValue());

  readonly allocation = computed<AllocationSlice[]>(() => {
    const rows = [...this.holdings()].sort((a, b) => b.value - a.value);
    const cash = this.cash();
    const total = this.totalEquity() || 1;
    const slices: AllocationSlice[] = rows.slice(0, 5).map((h, i) => ({
      label: h.symbol,
      value: h.value,
      pct: (h.value / total) * 100,
      color: ALLOCATION_COLORS[i % ALLOCATION_COLORS.length],
    }));
    if (cash > 0) {
      slices.push({ label: 'Cash', value: cash, pct: (cash / total) * 100, color: 'rgba(173, 151, 155, 0.45)' });
    }
    return slices;
  });

  readonly donutGradient = computed(() => {
    const slices = this.allocation();
    if (!slices.length) return 'conic-gradient(rgba(173, 151, 155, 0.15) 0 100%)';
    let start = 0;
    const stops = slices.map((s) => {
      const from = start;
      const to = start + s.pct;
      start = to;
      return `${s.color} ${from.toFixed(2)}% ${to.toFixed(2)}%`;
    });
    return `conic-gradient(${stops.join(', ')})`;
  });

  readonly recentOrders = computed(() => this.orders().slice(0, 5));

  readonly openOrderCount = computed(() => this.orders().filter((o) => o.status === 'NEW').length);

  ngOnInit(): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId) {
      this.loading.set(false);
      return;
    }

    this.watchlistService.getWatchlist().subscribe({
      next: (list) => {
        this.watched.set(list?.instruments ?? []);
        this.watchedLoaded.set(true);
      },
      error: () => this.watchedLoaded.set(true),
    });

    forkJoin({
      account: this.trade.getAccount(accountId),
      balance: this.trade.getBalance(accountId),
      positions: this.trade.getPositions(accountId),
      orders: this.trade.getOrders(accountId),
    }).subscribe({
      next: ({ account, balance, positions, orders }) => {
        this.account.set(account);
        this.balance.set(balance);
        this.positions.set(positions);
        this.orders.set(orders);
        this.loading.set(false);
        this.marksService
          .load(positions.map((p) => p.symbol), account.currency ?? 'USD')
          .subscribe((marks) => this.marks.set(marks));
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage.set(resolveErrorMessage(err.error?.errorCode, err.status));
        this.loading.set(false);
      },
    });
  }

  /** A ledger-currency amount, shown in the currency picked in the top bar. */
  fmtAccountCurrency(amount: number | null | undefined): string {
    return this.currency.display(amount);
  }

  /** A short form for tight spaces such as the middle of the donut: $12.4K, ₹1.2M. */
  fmtCompact(amount: number | null | undefined): string {
    const value = this.currency.convertLedger(amount);
    const symbol = this.currency.current().symbol;
    const abs = Math.abs(value);
    const units: [number, string][] = [[1e12, 'T'], [1e9, 'B'], [1e6, 'M'], [1e3, 'K']];
    const unit = units.find(([size]) => abs >= size);
    const digits = unit ? (abs / unit[0]).toFixed(abs / unit[0] >= 100 ? 0 : 1) + unit[1] : abs.toFixed(0);
    return `${value < 0 ? '-' : ''}${symbol}${digits}`;
  }

  setHoldingsView(view: HoldingsView): void {
    this.holdingsView.set(view);
  }

  fmtInt(n: number | null | undefined): string {
    return (n ?? 0).toLocaleString('en-US');
  }

  statusClass(status: string): string {
    return `badge badge-${status.toLowerCase()}`;
  }
}
