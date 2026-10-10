import {
  ChangeDetectionStrategy,
   Component, OnInit, OnDestroy, signal, inject, computed, ElementRef, viewChild, effect
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TradeService } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import { CurrencyService } from '../../core/services/currency.service';
import { AccountStateService } from '../../core/services/account-state.service';
import { resolveErrorMessage } from '../../core/services/error-catalogue';
import { HttpErrorResponse } from '@angular/common/http';
import type { OrderHistoryEntry, OrderStatus } from '@trade-api/model/models';
import { explainRejection, type RejectionExplanation } from './rejection-reasons';
import { ChartSymbolDirective } from '../../shared/instrument-chart/chart-symbol.directive';

const POLL_INTERVAL_MS = 2500;
const MAX_POLL_ATTEMPTS = 40;
const FLASH_MS = 1800;
const SETTLED_STATUSES: OrderStatus[] = ['FILLED', 'REJECTED', 'CANCELLED'];

@Component({
  selector: 'app-blotter',
  standalone: true,
  imports: [CommonModule, RouterLink, ChartSymbolDirective],
  templateUrl: './blotter.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './blotter.component.scss',
})
export class BlotterComponent implements OnInit, OnDestroy {
  private readonly trade = inject(TradeService);
  private readonly tokens = inject(TokenService);
  readonly currency = inject(CurrencyService);
  readonly accountState = inject(AccountStateService);

  readonly maxPollAttempts = MAX_POLL_ATTEMPTS;
  readonly pollIntervalSeconds = POLL_INTERVAL_MS / 1000;
  readonly settledStatuses = SETTLED_STATUSES;

  readonly loading = signal(true);
  readonly refreshing = signal(false);
  readonly cancelling = signal<string | null>(null);
  readonly orders = signal<OrderHistoryEntry[]>([]);
  readonly errorMessage = signal<string | null>(null);
  readonly isPolling = signal(false);
  readonly pollAttempts = signal(0);
  readonly lastUpdated = signal<Date | null>(null);
  readonly secondsToRefresh = signal(0);
  readonly flashIds = signal<Set<string>>(new Set());
  readonly settledNotice = signal<string | null>(null);

  /** The rejected order whose reason is open in the pop-up, if any. */
  readonly rejectionOpen = signal<OrderHistoryEntry | null>(null);
  readonly rejection = computed<RejectionExplanation | null>(() => {
    const order = this.rejectionOpen();
    return order ? explainRejection(order.rejectionReason) : null;
  });
  private readonly dialogClose = viewChild<ElementRef<HTMLButtonElement>>('dialogClose');
  /** Where focus returns when the pop-up closes. */
  private returnFocus: HTMLElement | null = null;

  constructor() {
    effect(() => {
      // After the dialog has rendered, move focus into it so the keyboard follows.
      if (this.rejectionOpen()) setTimeout(() => this.dialogClose()?.nativeElement.focus());
    });
  }

  readonly hasOpenOrders = computed(() => this.orders().some((o) => o.status === 'NEW'));
  readonly openCount = computed(() => this.orders().filter((o) => o.status === 'NEW').length);
  readonly settledCount = computed(() => this.orders().length - this.openCount());
  readonly pollingExhausted = computed(
    () => !this.isPolling() && this.hasOpenOrders() && this.pollAttempts() >= MAX_POLL_ATTEMPTS
  );

  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private countdownTimer: ReturnType<typeof setInterval> | null = null;
  private noticeTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly previousStatus = new Map<string, OrderStatus>();

  ngOnInit(): void {
    this.loadOrders(true);
  }

  ngOnDestroy(): void {
    this.stopPolling();
    if (this.noticeTimer) clearTimeout(this.noticeTimer);
  }

  manualRefresh(): void {
    this.loadOrders(false);
  }

  cancelOrder(orderId: string): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId || !orderId) return;

    this.cancelling.set(orderId);
    this.trade.cancelOrder(orderId).subscribe({
      next: () => {
        this.cancelling.set(null);
        this.manualRefresh();
      },
      error: (err) => {
        this.cancelling.set(null);
        this.errorMessage.set('Failed to cancel order. Please try again.');
        console.error('Cancel order error:', err);
      },
    });
  }

  loadOrders(initial: boolean): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId) {
      this.loading.set(false);
      return;
    }
    if (initial) {
      this.loading.set(true);
    } else {
      this.refreshing.set(true);
    }

    this.trade.getOrders(accountId).subscribe({
      next: (orders) => {
        this.applyOrders(orders);
        this.loading.set(false);
        this.refreshing.set(false);
        this.errorMessage.set(null);
        this.managePoll();
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage.set(resolveErrorMessage(err.error?.errorCode, err.status));
        this.loading.set(false);
        this.refreshing.set(false);
        this.isPolling.set(false);
      },
    });
  }

  private applyOrders(orders: OrderHistoryEntry[]): void {
    const changed = new Set<string>();
    for (const order of orders) {
      const previous = this.previousStatus.get(order.orderId);
      if (previous && previous !== order.status && SETTLED_STATUSES.includes(order.status)) {
        changed.add(order.orderId);
      }
      this.previousStatus.set(order.orderId, order.status);
    }

    this.orders.set(orders);
    this.lastUpdated.set(new Date());

    if (changed.size > 0) {
      this.flashIds.set(changed);
      const settled = orders.filter((order) => changed.has(order.orderId));
      const message =
        settled.length === 1
          ? `${settled[0].symbol} order is now ${settled[0].status}.`
          : `${settled.length} orders just settled.`;
      this.settledNotice.set(message);
      if (this.noticeTimer) clearTimeout(this.noticeTimer);
      this.noticeTimer = setTimeout(() => this.settledNotice.set(null), 6000);
      setTimeout(() => this.flashIds.set(new Set()), FLASH_MS);
    }
  }

  private managePoll(): void {
    if (this.hasOpenOrders()) {
      if (!this.pollTimer) this.startPolling();
    } else {
      this.stopPolling();
    }
  }

  private startPolling(): void {
    this.isPolling.set(true);
    this.pollAttempts.set(0);
    this.secondsToRefresh.set(POLL_INTERVAL_MS / 1000);

    this.countdownTimer = setInterval(() => {
      this.secondsToRefresh.update((seconds) => (seconds > 1 ? seconds - 1 : 0));
    }, 1000);

    this.pollTimer = setInterval(() => {
      if (this.pollAttempts() >= MAX_POLL_ATTEMPTS) {
        this.stopPolling();
        return;
      }
      this.pollAttempts.update((n) => n + 1);
      this.secondsToRefresh.set(POLL_INTERVAL_MS / 1000);
      const accountId = this.tokens.getAccountId();
      if (!accountId) return;
      this.trade.getOrders(accountId).subscribe({
        next: (orders) => {
          this.applyOrders(orders);
          if (!this.hasOpenOrders()) this.stopPolling();
        },
      });
    }, POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
    if (this.countdownTimer) {
      clearInterval(this.countdownTimer);
      this.countdownTimer = null;
    }
    this.isPolling.set(false);
    this.secondsToRefresh.set(0);
  }

  isFlashing(orderId: string): boolean {
    return this.flashIds().has(orderId);
  }

  rejectionText(reason: string | null | undefined): string {
    return explainRejection(reason).label;
  }

  /** Opens the reason pop-up for a rejected order. Other statuses have nothing to explain. */
  showRejection(order: OrderHistoryEntry, event?: Event): void {
    if (order.status !== 'REJECTED') return;
    this.returnFocus = (event?.currentTarget as HTMLElement | null) ?? null;
    this.rejectionOpen.set(order);
  }

  closeRejection(): void {
    this.rejectionOpen.set(null);
    this.returnFocus?.focus();
    this.returnFocus = null;
  }

  /** An order with no type on record was placed before market orders existed: a limit. */
  isMarket(order: { orderType?: string | null }): boolean {
    return (order.orderType ?? 'LIMIT') === 'MARKET';
  }

  badgeClass(status: string): string {
    return `badge badge-${status.toLowerCase()}`;
  }

  formatDate(iso: string): string {
    return new Date(iso).toLocaleString();
  }

  formatTime(date: Date | null): string {
    return date
      ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      : '—';
  }

  /** A ledger-currency amount, shown in the currency picked in the top bar. */
  fmtAccountCurrency(amount: number | null | undefined): string {
    return this.currency.display(amount);
  }
}
