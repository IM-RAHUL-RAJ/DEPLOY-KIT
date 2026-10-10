import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
   Component, OnInit, computed, inject, signal
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { TradeService } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import { CurrencyService } from '../../core/services/currency.service';
import { AccountStateService } from '../../core/services/account-state.service';
import { MarksService } from '../../core/services/marks.service';
import type { BalanceResponse, PositionResponse } from '@trade-api/model/models';
import { HttpErrorResponse } from '@angular/common/http';
import { resolveErrorMessage } from '../../core/services/error-catalogue';
import { ChartSymbolDirective } from '../../shared/instrument-chart/chart-symbol.directive';

interface HoldingRow {
  symbol: string;
  quantity: number;
  averageCost: number;
  /** Live price in the ledger currency, or null when no quote is available. */
  mark: number | null;
  /** Invested value: average cost x quantity. */
  cost: number;
  /** (live price - average cost) / average cost, or null without a live price. */
  returnPct: number | null;
  /** Share of the invested total. */
  weight: number;
}


@Component({
  selector: 'app-holdings',
  standalone: true,
  imports: [CommonModule, RouterLink, ChartSymbolDirective],
  templateUrl: './holdings.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './holdings.component.scss',
})
export class HoldingsComponent implements OnInit {
  private readonly trade = inject(TradeService);
  private readonly tokens = inject(TokenService);
  readonly accountState = inject(AccountStateService);
  readonly currency = inject(CurrencyService);
  private readonly marksService = inject(MarksService);

  readonly loading = signal(true);
  readonly errorMessage = signal<string | null>(null);
  readonly balance = signal<BalanceResponse | null>(null);
  readonly positions = signal<PositionResponse[]>([]);
  readonly marks = signal<Record<string, number>>({});

  readonly rows = computed<HoldingRow[]>(() => {
    const costOf = (p: PositionResponse) => p.quantity * (p.averageCost ?? 0);
    const total = this.positions().reduce((sum, p) => sum + costOf(p), 0) || 1;
    return this.positions()
      .map((p) => {
        const averageCost = p.averageCost ?? 0;
        const mark = this.marks()[p.symbol] ?? null;
        const cost = costOf(p);
        return {
          symbol: p.symbol,
          quantity: p.quantity,
          averageCost,
          mark,
          cost,
          returnPct: mark !== null && averageCost > 0 ? ((mark - averageCost) / averageCost) * 100 : null,
          weight: (cost / total) * 100,
        };
      })
      .sort((a, b) => b.cost - a.cost);
  });

  /** Positions at cost: the sum of average cost x quantity. */
  readonly positionsValue = computed(() => this.rows().reduce((sum, r) => sum + r.cost, 0));
  readonly cash = computed(() => this.balance()?.cashBalance ?? 0);
  readonly totalEquity = computed(() => this.cash() + this.positionsValue());

  /**
   * Return on the part of the portfolio that has a live price, weighted by what
   * was invested in each holding. Holdings without a quote are left out rather
   * than counted as a 0% return.
   */
  readonly portfolioReturn = computed<number | null>(() => {
    const priced = this.rows().filter((r) => r.mark !== null);
    const invested = priced.reduce((sum, r) => sum + r.cost, 0);
    if (invested <= 0) return null;
    const current = priced.reduce((sum, r) => sum + r.quantity * (r.mark ?? 0), 0);
    return ((current - invested) / invested) * 100;
  });

  /** A ledger-currency amount, shown in the currency picked in the top bar. */
  fmtAccountCurrency(amount: number | null | undefined): string {
    return this.currency.display(amount);
  }

  ngOnInit(): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId) {
      this.loading.set(false);
      return;
    }
    forkJoin({
      balance: this.trade.getBalance(accountId),
      positions: this.trade.getPositions(accountId),
    }).subscribe({
      next: ({ balance, positions }) => {
        this.balance.set(balance);
        this.positions.set(positions);
        this.loading.set(false);
        this.marksService
          .load(positions.map((p) => p.symbol), balance.currency ?? 'USD')
          .subscribe((marks) => this.marks.set(marks));
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage.set(resolveErrorMessage(err.error?.errorCode, err.status));
        this.loading.set(false);
      },
    });
  }
}
