import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  MAX_ACTIVE_ALERTS,
  PriceAlertsService,
  type AlertDirection,
  type PriceAlert,
} from '../../core/services/price-alerts.service';
import { AccountStateService } from '../../core/services/account-state.service';
import { CurrencyService } from '../../core/services/currency.service';
import { TradeService } from '../../core/services/trade.service';
import { ActivatedRoute } from '@angular/router';
import { ChartSymbolDirective } from '../../shared/instrument-chart/chart-symbol.directive';

/** One destructive action awaiting the customer's explicit go-ahead in the dialog. */
interface ConfirmRequest {
  title: string;
  message: string;
  confirmLabel: string;
  run: () => void;
}

@Component({
  selector: 'app-price-alerts',
  standalone: true,
  imports: [CommonModule, FormsModule, ChartSymbolDirective],
  templateUrl: './price-alerts.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './price-alerts.component.scss',
})
export class PriceAlertsComponent implements OnInit {
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly alertsService = inject(PriceAlertsService);
  private readonly trade = inject(TradeService);
  private readonly currency = inject(CurrencyService);
  private readonly accountState = inject(AccountStateService);

  readonly maxActiveAlerts = MAX_ACTIVE_ALERTS;

  readonly loading = signal(true);
  /** True when the list itself failed to load, so the page shows the error, not "empty". */
  readonly loadFailed = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly successMessage = signal<string | null>(null);
  readonly alerts = signal<PriceAlert[]>([]);

  readonly newSymbol = signal<string>('');
  readonly newDirection = signal<AlertDirection>('ABOVE');
  readonly newThreshold = signal<string>('');
  readonly creating = signal(false);

  readonly symbolCurrency = signal<string | null>(null);
  readonly confirmRequest = signal<ConfirmRequest | null>(null);

  readonly activeCount = computed(() => this.alerts().filter((a) => a.status === 'ACTIVE').length);
  readonly atActiveCap = computed(() => this.activeCount() >= MAX_ACTIVE_ALERTS);
  readonly isEmpty = computed(() => this.alerts().length === 0);

  /** The currency the Settings page shows amounts in, which is what a threshold is entered in. */
  readonly displayCurrency = computed(() => {
    this.accountState.preference();
    return this.currency.selected();
  });

  readonly thresholdHint = computed(() => {
    const symbol = this.newSymbol().trim().toUpperCase();
    const display = this.displayCurrency();
    if (!symbol) {
      return `The threshold you enter is in ${display}. It is compared against the symbol\u2019s own market price, so a symbol quoted in another currency is converted.`;
    }
    const currency = this.symbolCurrency();
    if (currency && currency !== display) {
      return `The threshold you enter is in ${display} and is converted to the ${currency} market price of ${symbol}.`;
    }
    return currency
      ? `The threshold you enter is in ${display} and is compared against the current ${currency} market price of ${symbol}.`
      : `The threshold you enter is in ${display} and is compared against ${symbol}\u2019s own market price.`;
  });

  ngOnInit(): void {
    this.load();
    // Opened from a chart: start on that instrument.
    const linked = this.route?.snapshot.queryParamMap.get('symbol');
    if (linked) {
      this.setSymbol(linked.trim().toUpperCase());
    }
  }

  load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.errorMessage.set(null);
    this.alertsService.list().subscribe({
      next: (alerts) => {
        this.alerts.set(alerts);
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('Your price alerts could not be loaded. Try again in a moment.');
        this.loadFailed.set(true);
        this.loading.set(false);
      },
    });
  }

  setSymbol(value: string): void {
    this.newSymbol.set(value);
    this.lookupSymbolCurrency();
  }

  private lookupSymbolCurrency(): void {
    const symbol = this.newSymbol().trim().toUpperCase();
    if (!symbol) {
      this.symbolCurrency.set(null);
      return;
    }
    this.trade.getInstrumentQuote(symbol).subscribe({
      next: (quote) => this.symbolCurrency.set(quote?.quoteCurrency ?? null),
      error: () => this.symbolCurrency.set(null),
    });
  }

  create(): void {
    const symbol = this.newSymbol().trim().toUpperCase();
    const threshold = Number(this.newThreshold());
    if (!symbol) {
      this.errorMessage.set('Enter a symbol, for example AAPL.');
      return;
    }
    if (!this.newThreshold() || !Number.isFinite(threshold) || threshold <= 0) {
      this.errorMessage.set('Enter a price greater than zero.');
      return;
    }
    if (this.atActiveCap()) {
      this.errorMessage.set(
        `You have ${MAX_ACTIVE_ALERTS} armed alerts. Cancel one before arming another.`,
      );
      return;
    }

    // The threshold is typed in the display currency; the server holds and compares it
    // in the symbol's quote currency, so it is converted before the alert is armed.
    const thresholdQuote = this.toQuoteCurrency(threshold, this.symbolCurrency());

    this.creating.set(true);
    this.errorMessage.set(null);
    this.successMessage.set(null);
    this.alertsService
      .create({ symbol, direction: this.newDirection(), threshold: thresholdQuote })
      .subscribe({
        next: (alert) => {
          const existed = this.alerts().some((a) => a.alertId === alert.alertId);
          this.alerts.update((all) => {
            const existing = all.findIndex((a) => a.alertId === alert.alertId);
            if (existing >= 0) {
              return all.map((a) => (a.alertId === alert.alertId ? alert : a));
            }
            return [alert, ...all];
          });
          this.newSymbol.set('');
          this.newThreshold.set('');
          this.successMessage.set(
            existed
              ? `A ${alert.symbol} alert at ${this.alertLevel(alert)} was already active.`
              : `Armed ${alert.symbol} ${directionWord(alert.direction)} ${this.alertLevel(alert)}.`,
          );
          this.creating.set(false);
        },
        error: (error) => {
          const serverMessage =
            typeof error?.error?.message === 'string' && error.error.message
              ? error.error.message
              : 'That alert could not be armed. Check the symbol and try again.';
          this.errorMessage.set(serverMessage);
          this.creating.set(false);
        },
      });
  }

  cancel(alert: PriceAlert): void {
    if (alert.status !== 'ACTIVE') {
      return;
    }
    this.askConfirm({
      title: 'Cancel price alert?',
      message: `The ${alert.symbol} alert (${directionWord(alert.direction)} ${this.alertLevel(
        alert,
      )}) will be disarmed.`,
      confirmLabel: 'Cancel alert',
      run: () => this.performCancel(alert),
    });
  }

  private performCancel(alert: PriceAlert): void {
    this.alertsService.cancel(alert.alertId).subscribe({
      next: (cancelled) => {
        this.alerts.update((all) =>
          all.map((a) => (a.alertId === cancelled.alertId ? cancelled : a)),
        );
        this.successMessage.set(`Cancelled the ${cancelled.symbol} alert.`);
      },
      error: (error) => {
        const serverMessage =
          typeof error?.error?.message === 'string' && error.error.message
            ? error.error.message
            : 'That alert could not be cancelled. Try again in a moment.';
        this.errorMessage.set(serverMessage);
      },
    });
  }

  askConfirm(request: ConfirmRequest): void {
    this.errorMessage.set(null);
    this.successMessage.set(null);
    this.confirmRequest.set(request);
  }

  dismissConfirm(): void {
    this.confirmRequest.set(null);
  }

  runConfirm(): void {
    const request = this.confirmRequest();
    if (!request) {
      return;
    }
    this.confirmRequest.set(null);
    request.run();
  }

  statusLabel(status: PriceAlert['status']): string {
    switch (status) {
      case 'ACTIVE':
        return 'Armed';
      case 'TRIGGERED':
        return 'Fired';
      case 'CANCELLED':
        return 'Cancelled';
    }
  }

  /**
   * A threshold as the customer should read it: converted into the display currency, with the
   * quote-currency original shown when the two differ so the number is never a surprise.
   */
  alertLevel(alert: PriceAlert): string {
    const display = this.displayCurrency();
    const quote = alert.currency;
    if (quote && quote !== display) {
      const converted = this.currency.fromUsd(display, this.currency.toUsd(quote, alert.threshold));
      return `${fmtThreshold(converted)} ${display} (${fmtThreshold(alert.threshold)} ${quote})`;
    }
    return `${fmtThreshold(alert.threshold)} ${quote ?? display}`;
  }

  /** The triggered/held price, shown in the display currency like the threshold. */
  alertLevelPrice(price: number | null, quoteCurrency: string | null): string {
    const display = this.displayCurrency();
    if (price == null) {
      return '—';
    }
    if (quoteCurrency && quoteCurrency !== display) {
      return this.currency.formatNative(display, this.currency.fromUsd(display, this.currency.toUsd(quoteCurrency, price)));
    }
    return fmtThreshold(price);
  }

  /** How many thousandths the server should call equal: a 4dp conversion hides float noise. */
  private toQuoteCurrency(threshold: number, quoteCurrency: string | null): number {
    const display = this.displayCurrency();
    if (!quoteCurrency || quoteCurrency === display) {
      return threshold;
    }
    const usd = this.currency.toUsd(display, threshold);
    return Math.round(this.currency.fromUsd(quoteCurrency, usd) * 1e4) / 1e4;
  }
}

function directionWord(direction: AlertDirection): string {
  return direction === 'ABOVE' ? 'above' : 'below';
}

function fmtThreshold(threshold: number): string {
  return threshold.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}