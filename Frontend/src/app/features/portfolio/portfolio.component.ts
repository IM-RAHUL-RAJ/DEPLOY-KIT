import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { forkJoin } from 'rxjs';
import { TokenService } from '../../core/services/token.service';
import { TradeService } from '../../core/services/trade.service';
import { CurrencyService } from '../../core/services/currency.service';
import {
  PortfolioService,
  type PnlResponse,
  type PricedPosition,
  type PortfolioSummary,
} from '../../core/services/portfolio.service';
import type { PositionResponse } from '@trade-api/model/models';
import { resolveErrorMessage } from '../../core/services/error-catalogue';
import { ChartSymbolDirective } from '../../shared/instrument-chart/chart-symbol.directive';

@Component({
  selector: 'app-portfolio',
  standalone: true,
  imports: [CommonModule, ChartSymbolDirective],
  templateUrl: './portfolio.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './portfolio.component.scss',
})
export class PortfolioComponent implements OnInit {
  private readonly portfolioService = inject(PortfolioService);
  private readonly trade = inject(TradeService);
  private readonly tokens = inject(TokenService);
  readonly currency = inject(CurrencyService);

  readonly loading = signal(true);
  readonly errorMessage = signal<string | null>(null);
  readonly summary = signal<PortfolioSummary | null>(null);
  readonly positions = signal<PricedPosition[]>([]);
  readonly pnl = signal<PnlResponse | null>(null);

  /** Pricing is down for every symbol: the page degrades to unpriced holdings. */
  readonly degraded = signal(false);
  readonly degradedPositions = signal<PositionResponse[]>([]);

  readonly hasPositions = computed(() => this.positions().length > 0);
  readonly bySymbol = computed(() => this.pnl()?.bySymbol ?? []);
  readonly pricedCount = computed(
    () => this.positions().filter((p) => p.lastPrice !== null).length,
  );

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId) {
      this.loading.set(false);
      this.errorMessage.set('No account is signed in.');
      return;
    }
    this.loading.set(true);
    this.errorMessage.set(null);
    this.degraded.set(false);
    forkJoin({
      summary: this.portfolioService.summary(accountId),
      positions: this.portfolioService.positions(accountId),
      pnl: this.portfolioService.pnl(accountId, { bySymbol: true }),
    }).subscribe({
      next: ({ summary, positions, pnl }) => {
        this.summary.set(summary);
        this.positions.set(positions);
        this.pnl.set(pnl);
        this.loading.set(false);
      },
      error: (error) => {
        if (error?.error?.errorCode === 'MKT-503') {
          this.degradeToUnpriced(accountId);
          return;
        }
        this.errorMessage.set(resolveErrorMessage(error?.error?.errorCode, error.status));
        this.loading.set(false);
      },
    });
  }

  /**
   * MKT-503 means nothing could be priced, but the positions themselves still
   * exist on the account routes. Show them without prices rather than an empty
   * page (the portfolio contract asks for exactly this degradation).
   */
  private degradeToUnpriced(accountId: number): void {
    this.errorMessage.set(resolveErrorMessage('MKT-503', 503));
    this.trade.getPositions(accountId).subscribe({
      next: (positions) => {
        this.degradedPositions.set(positions);
        this.degraded.set(true);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
      },
    });
  }

  fmtBase(amount: number | null | undefined): string {
    return this.currency.display(amount);
  }

  fmtNative(price: number | null | undefined, code: string): string {
    if (price === null || price === undefined) {
      return 'N/A';
    }
    const option = this.currency.option(code);
    return `${option.symbol}${price.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`;
  }

  pnlClass(value: number): string {
    if (value > 0) return 'positive';
    if (value < 0) return 'negative';
    return 'flat';
  }
}