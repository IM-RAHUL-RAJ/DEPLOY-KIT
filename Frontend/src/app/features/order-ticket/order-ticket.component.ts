import {
  Component,
  OnInit,
  OnDestroy,
  signal,
  inject,
  computed,
  DestroyRef,
  ChangeDetectionStrategy,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import {
  ReactiveFormsModule,
  FormBuilder,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { UiPreferencesService } from '../../core/services/ui-preferences.service';
import { TradeService, type InstrumentQuote } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import { CurrencyService } from '../../core/services/currency.service';
import { resolveErrorMessage } from '../../core/services/error-catalogue';
import type { OrderResponse, PlaceOrderRequest, PositionResponse } from '@trade-api/model/models';
import { priceValidator, quantityValidators, symbolValidators } from './order-validators';
import { buildTradeSummary, type TradeSummary } from './trade-summary';

@Component({
  selector: 'app-order-ticket',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './order-ticket.component.html',
  styleUrl: './order-ticket.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderTicketComponent implements OnInit, OnDestroy {
  private readonly trade = inject(TradeService);
  private readonly tokens = inject(TokenService);
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly uiPrefs = inject(UiPreferencesService);
  private readonly destroyRef = inject(DestroyRef);
  readonly currency = inject(CurrencyService);

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly successOrder = signal<OrderResponse | null>(null);
  readonly accountId = signal<number | null>(null);
  readonly accountStatus = signal<string | null>(null);
  readonly accountCurrency = signal<string | null>(null);
  readonly accountLoading = signal(false);
  readonly quote = signal<InstrumentQuote | null>(null);
  readonly quoteLoading = signal(false);
  /** Cash and holdings, for the summary's before/after lines. */
  readonly cashBalance = signal<number | null>(null);
  readonly positions = signal<PositionResponse[]>([]);

  readonly orderType = signal<'MARKET' | 'LIMIT'>(this.uiPrefs.defaultOrderType());
  readonly isMarket = computed(() => this.orderType() === 'MARKET');
  readonly isSuspended = computed(() => this.accountStatus() === 'SUSPENDED');

  /**
   * Seconds still to wait before the blotter takes over, or null when no redirect
   * is scheduled. A placed order is not a screen the trader wants to stay on, so
   * the confirmation hands off by itself - but a countdown is shown and can be
   * held, because a trader reading the fill price still needs those seconds.
   */
  readonly redirectIn = signal<number | null>(null);

  /** Long enough to read the fill price, short enough not to feel stranded. */
  private static readonly REDIRECT_SECONDS = 5;

  private redirectTimer: ReturnType<typeof setInterval> | null = null;

  /**
   * Idempotency key for the order the form currently describes. It survives a
   * failed submission, so pressing submit again after a dropped connection resends
   * the same order (the service answers ORD-409 if the first one landed) rather
   * than placing a second one. Editing the form or a successful placement starts
   * a new key, because that is a new order.
   */
  private idempotencyKey: string | null = null;

  /**
   * The form is a plain Reactive Form, so a computed() reading it would not
   * re-evaluate when a control changes. This snapshot is a signal, republished on
   * every value change, so the totals and the indicative price track the inputs.
   */
  private readonly snapshot = signal<{ symbol: string; quantity: number; price: number | null }>({
    symbol: '',
    quantity: 0,
    price: null,
  });

  /** Form validity republished as a signal so computed() can depend on it. */
  private readonly formState = signal({
    symbolValid: false,
    quantityValid: false,
    priceValid: false,
    priceProvided: false,
  });
  readonly side = signal<'BUY' | 'SELL'>('BUY');

  readonly quoteCurrency = computed(() => this.quote()?.quoteCurrency ?? null);

  /** True when the instrument trades in a currency other than the account's ledger currency. */
  readonly isForeign = computed(() => {
    const stockCode = this.quoteCurrency();
    const accountCode = this.accountCurrency();
    return !!stockCode && !!accountCode && stockCode.toUpperCase() !== accountCode.toUpperCase();
  });

  /** The price the server will most likely use: ask to buy, bid to sell. */
  readonly indicativeNativePrice = computed(() => {
    const q = this.quote();
    if (!q) return null;
    const sidePrice = this.side() === 'SELL' ? q.bid : q.ask;
    return sidePrice ?? q.price ?? null;
  });

  /** Current stock price in account currency (converted from stock currency). */
  readonly indicativeInAccountCurrency = computed(() => {
    const nativePrice = this.indicativeNativePrice();
    if (nativePrice == null) return null;
    const accountCurrency = this.accountCurrency() ?? 'USD';
    const stockCurrency = this.quoteCurrency() ?? 'USD';
    if (accountCurrency === stockCurrency) {
      return nativePrice;
    }
    const usd = this.currency.toUsd(stockCurrency, nativePrice);
    return this.currency.fromUsd(accountCurrency, usd);
  });

  readonly form = this.fb.group({
    accountId: [{ value: '', disabled: true }],
    symbol: ['', symbolValidators],
    side: ['BUY', Validators.required],
    orderType: [this.uiPrefs.defaultOrderType() as string, Validators.required],
    quantity: [null as number | null, quantityValidators],
    price: [null as number | null, [priceValidator]],
  });

  ngOnInit(): void {
    const id = this.tokens.getAccountId();
    this.accountId.set(id);
    this.form.patchValue({ accountId: String(id ?? '') });

    // Fetch account details to check status and get currency
    if (id) {
      this.accountLoading.set(true);
      this.trade.getAccount(id).subscribe({
        next: (account) => {
          this.accountStatus.set(account.status);
          this.accountCurrency.set(account.currency ?? 'USD');
          this.accountLoading.set(false);
        },
        error: () => {
          this.accountLoading.set(false);
        }
      });
      this.loadCashAndHoldings(id);
    }

    this.form
      .get('symbol')!
      .valueChanges.pipe(
        debounceTime(400),
        distinctUntilChanged(),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((symbol) => this.loadQuote(symbol));

    // Keep the signals the computeds read in step with the controls.
    this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((value) => {
      this.idempotencyKey = null;
      this.snapshot.set({
        symbol: (value.symbol ?? '').trim().toUpperCase(),
        quantity: Number(value.quantity ?? 0),
        price: value.price === null || value.price === undefined ? null : Number(value.price),
      });
      const side = value.side;
      if (side === 'BUY' || side === 'SELL') {
        this.side.set(side);
      }
      this.publishFormState();
    });
    this.form.statusChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.publishFormState();
    });
    this.publishFormState();

    // Opened from a chart or a link: start on that instrument, with the default size.
    const linked = this.route?.snapshot.queryParamMap.get('symbol');
    if (linked) {
      this.form.patchValue({ symbol: linked.trim().toUpperCase() });
      if (this.form.controls.quantity.value === null && this.uiPrefs.defaultQuantity() > 0) {
        this.form.patchValue({ quantity: this.uiPrefs.defaultQuantity() });
      }
    }
  }

  private publishFormState(): void {
    const price = this.form.getRawValue().price;
    this.formState.set({
      symbolValid: this.form.controls.symbol.valid,
      quantityValid: this.form.controls.quantity.valid,
      priceValid: this.form.controls.price.valid,
      priceProvided: price !== null && price !== undefined && !Number.isNaN(Number(price)),
    });
  }

  /** Best effort: without them the summary simply leaves out the before/after lines. */
  private loadCashAndHoldings(accountId: number): void {
    this.trade.getBalance(accountId).subscribe({
      next: (balance) => this.cashBalance.set(balance.cashBalance ?? null),
      error: () => this.cashBalance.set(null),
    });
    this.trade.getPositions(accountId).subscribe({
      next: (positions) => this.positions.set(positions),
      error: () => this.positions.set([]),
    });
  }

  /**
   * The live order summary, shown once the symbol and quantity are valid. Funds
   * and holdings shortfalls are warnings only: the executor decides at fill time.
   */
  readonly summary = computed<TradeSummary | null>(() => {
    const state = this.formState();
    const { symbol, quantity, price } = this.snapshot();
    if (!state.symbolValid || !state.quantityValid || !symbol || !(quantity > 0)) return null;
    const held = this.positions().find((p) => p.symbol.toUpperCase() === symbol);
    const quote = this.quote();
    return buildTradeSummary({
      symbol,
      side: this.side(),
      orderType: this.orderType(),
      quantity,
      limitPrice: state.priceValid ? price : null,
      // A quote still loading, or for a different symbol, must not price this one.
      quote: quote && quote.symbol?.toUpperCase() === symbol ? quote : null,
      accountCurrency: this.accountCurrency() ?? 'USD',
      cashBalance: this.cashBalance(),
      heldQuantity: held?.quantity ?? 0,
      averageCost: held?.averageCost ?? null,
      convert: (amount, from, to) => this.currency.fromUsd(to, this.currency.toUsd(from, amount)),
    });
  });

  /** The currency amounts are shown in: the customer's display currency. */
  readonly displayCode = computed(() => this.currency.selected());

  /** True when amounts are shown in a currency other than the one the order settles in. */
  readonly displayDiffers = computed(() => this.displayCode() !== (this.accountCurrency() ?? 'USD'));

  /**
   * Money in the account currency (what the order settles in), shown in the display currency
   * so the ticket reads in the same currency as the rest of the terminal.
   */
  money(amount: number | null | undefined): string {
    if (amount == null) {
      return this.currency.formatNative(this.displayCode(), amount);
    }
    const display = this.displayCode();
    return this.currency.formatNative(display, this.currency.convert(this.accountCurrency() ?? 'USD', display, amount));
  }

  /** Money in the instrument's own currency. */
  nativeMoney(code: string, amount: number | null | undefined): string {
    return this.currency.formatNative(code, amount);
  }

  private loadQuote(symbol: string | null): void {
    const value = (symbol ?? '').trim().toUpperCase();
    if (!value) {
      this.quote.set(null);
      return;
    }
    this.quoteLoading.set(true);
    this.trade.getInstrumentQuote(value).subscribe({
      next: (quote) => {
        this.quote.set(quote);
        this.quoteLoading.set(false);
      },
      // A missing quote is not an error here: the ticket still works for a LIMIT
      // order, and a MARKET order is refused by the server with MKT-503 anyway.
      error: () => {
        this.quote.set(null);
        this.quoteLoading.set(false);
      },
    });
  }

  /** A MARKET order must arrive with no price for the server to price it itself. */
  private buildRequest(): PlaceOrderRequest {
    const raw = this.form.getRawValue();
    const orderType = raw.orderType === 'MARKET' ? 'MARKET' : 'LIMIT';

    const request: PlaceOrderRequest = {
      accountId: this.accountId()!,
      symbol: raw.symbol!.toUpperCase(),
      side: raw.side as 'BUY' | 'SELL',
      orderType,
      quantity: Number(raw.quantity),
      idempotencyKey: (this.idempotencyKey ??= crypto.randomUUID()),
    };
    if (orderType === 'LIMIT') {
      request.price = Number(raw.price);
    }
    return request;
  }

  /**
   * A LIMIT order with no price cannot be sent; a MARKET order never needs one.
   *
   * Validity is read through `formState`, a signal republished on every change:
   * reading the controls directly here would leave this computed stuck on the
   * value it saw at construction, because a Reactive Form is not a signal source.
   */
  readonly canSubmit = computed(() => {
    const state = this.formState();
    if (!state.symbolValid || !state.quantityValid) return false;
    if (!this.accountId()) return false;
    if (this.isMarket()) return true;
    return state.priceProvided && state.priceValid;
  });

  submit(): void {
    if (!this.canSubmit() || this.loading()) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading.set(true);
    this.errorMessage.set(null);
    this.successOrder.set(null);

    this.trade.placeOrder(this.buildRequest()).subscribe({
      next: (order) => {
        this.idempotencyKey = null;
        this.successOrder.set(order);
        this.loading.set(false);
        if (this.accountId()) this.loadCashAndHoldings(this.accountId()!);
        this.scheduleBlotterRedirect();
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage.set(resolveErrorMessage(err.error?.errorCode, err.status));
        this.loading.set(false);
      },
    });
  }

  /** The confirmation is a summary; the working view of orders is the blotter. */
  goToBlotter(): void {
    this.cancelBlotterRedirect();
    this.router.navigate(['/blotter']);
  }

  /**
   * Count the confirmation down to the blotter. Every path that leaves the
   * success panel - the countdown reaching zero, the button, the component being
   * destroyed, or "place another order" - clears the same timer, so a stale
   * interval cannot navigate out from under a form the trader is still filling in.
   */
  private scheduleBlotterRedirect(): void {
    this.cancelBlotterRedirect();
    this.redirectIn.set(OrderTicketComponent.REDIRECT_SECONDS);
    this.redirectTimer = setInterval(() => {
      const remaining = this.redirectIn();
      if (remaining === null) return;
      if (remaining <= 1) {
        this.cancelBlotterRedirect();
        this.router.navigate(['/blotter']);
        return;
      }
      this.redirectIn.set(remaining - 1);
    }, 1000);
  }

  /** Hold the confirmation open without leaving, then let it resume. */
  holdBlotterRedirect(): void {
    if (this.redirectIn() === null) return;
    this.cancelBlotterRedirect();
    this.redirectIn.set(null);
  }

  private cancelBlotterRedirect(): void {
    if (this.redirectTimer !== null) {
      clearInterval(this.redirectTimer);
      this.redirectTimer = null;
    }
  }

  ngOnDestroy(): void {
    this.cancelBlotterRedirect();
  }

  setOrderType(type: 'MARKET' | 'LIMIT'): void {
    if (this.orderType() === type) return;
    this.orderType.set(type);
    this.form.patchValue({ orderType: type });
    if (type === 'MARKET') {
      // Leaving a stale limit price behind would be confusing next to the
      // market view, and it is not sent with a MARKET order anyway.
      this.form.controls.price.reset(null);
      this.form.controls.price.markAsUntouched();
    }
  }

  reset(): void {
    // Placing another order cancels the pending hand-off: the timer must not pull
    // the trader off a form they have only just started filling in.
    this.cancelBlotterRedirect();
    this.successOrder.set(null);
    this.errorMessage.set(null);
    const defaultType = this.uiPrefs.defaultOrderType();
    this.orderType.set(defaultType);
    this.form.reset({
      side: 'BUY',
      orderType: defaultType,
      accountId: String(this.accountId() ?? ''),
      symbol: '',
      quantity: null,
      price: null,
    });
  }

  statusClass(status: string): string {
    return `badge badge-${status.toLowerCase()}`;
  }
}