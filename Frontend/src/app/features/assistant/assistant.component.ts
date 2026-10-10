import {
  ChangeDetectionStrategy,
  
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { TradeService } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import { resolveErrorMessage } from '../../core/services/error-catalogue';
import { CurrencyService } from '../../core/services/currency.service';
import { AccountStateService } from '../../core/services/account-state.service';
import { ChatbotService, type ChatMessage } from '../../core/services/chatbot.service';
import type { OrderResponse } from '@trade-api/model/models';

interface AssistantAction {
  id: string;
  label: string;
  payload?: string;
}

interface AssistantMessage {
  id: number;
  role: 'user' | 'bot';
  text: string;
  actions?: AssistantAction[];
}

interface OrderDraft {
  symbol?: string;
  side?: 'BUY' | 'SELL';
  orderType?: 'MARKET' | 'LIMIT';
  quantity?: number;
  price?: number;
}

const SYMBOLS = ['AAPL', 'MSFT', 'GOOG', 'AMZN', 'TSLA'];
const QUANTITIES = [1, 5, 10, 25, 50];

@Component({
  selector: 'app-assistant',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './assistant.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './assistant.component.scss',
})
export class AssistantComponent {
  private readonly trade = inject(TradeService);
  private readonly tokens = inject(TokenService);
  private readonly router = inject(Router);
  private readonly currency = inject(CurrencyService);
  private readonly accountState = inject(AccountStateService);
  private readonly chatbot = inject(ChatbotService);

  readonly open = signal(false);
  readonly busy = signal(false);
  readonly messages = signal<AssistantMessage[]>([]);
  readonly draft = signal<OrderDraft>({});
  readonly confirmError = signal<string | null>(null);

  private readonly threadRef = viewChild<ElementRef<HTMLDivElement>>('thread');
  private readonly chatInput = viewChild<ElementRef<HTMLInputElement>>('chatInput');
  private seq = 0;
  private greeted = false;
  private pendingConfirmationToken: string | null = null;

  readonly canNavigate = computed(() => !!this.tokens.getAccountId());

  constructor() {
    effect(() => {
      this.messages();
      this.busy();
      this.scrollToBottom();
    });
  }

  toggle(): void {
    if (this.open()) {
      this.open.set(false);
      return;
    }
    this.open.set(true);
    if (!this.greeted) {
      this.greeted = true;
      this.greet();
    }
  }

  close(): void {
    this.open.set(false);
  }

  /** The full-page assistant, for a longer conversation. */
  openFullPage(): void {
    this.open.set(false);
    this.router.navigate(['/chatbot']);
  }

  runAction(action: AssistantAction): void {
    this.user(action.label);
    switch (action.id) {
      case 'menu':
        this.menu();
        return;
      case 'nav':
        this.router.navigate([action.payload ?? '/dashboard']);
        this.open.set(false);
        return;
      case 'account':
        this.withAccount();
        return;
      case 'orders':
        this.withOrders();
        return;
      case 'positions':
        this.withPositions();
        return;
      case 'help':
        this.help();
        return;
      case 'place-order':
        this.startOrder();
        return;
      case 'symbol':
        this.chooseSymbol(action.payload!);
        return;
      case 'side':
        this.chooseSide(action.payload as 'BUY' | 'SELL');
        return;
      case 'qty':
        this.chooseQuantity(Number(action.payload));
        return;
      case 'otype':
        this.chooseOrderType(action.payload as 'MARKET' | 'LIMIT');
        return;
      case 'price':
        this.choosePrice(action.payload!);
        return;
      case 'confirm':
        this.confirmOrder();
        return;
      case 'cancel':
        this.bot('Order cancelled.', this.backMenu());
        return;
      case 'confirm-order':
        this.confirmChatbotOrder();
        return;
      case 'cancel-order':
        this.cancelChatbotOrder();
        return;
    }
  }

  private navActions(extra: AssistantAction[] = []): AssistantAction[] {
    return [
      ...extra,
      { id: 'menu', label: 'Main menu' },
    ];
  }

  private backMenu(): AssistantAction[] {
    return [{ id: 'menu', label: 'Main menu' }];
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      const el = this.threadRef()?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    }, 40);
  }

  /** Money on this account is held, priced and shown in the account's ledger currency. */
  private money(amount: number | null | undefined): string {
    return this.currency.formatNative(this.accountState.accountCurrency(), amount);
  }

  private nextId(): number {
    return ++this.seq;
  }

  private bot(text: string, actions?: AssistantAction[]): void {
    this.messages.update((m) => [...m, { id: this.nextId(), role: 'bot', text, actions }]);
  }

  private user(text: string): void {
    this.messages.update((m) => [...m, { id: this.nextId(), role: 'user', text }]);
  }

  private greet(): void {
    const name = this.tokens.getUsername() ?? 'trader';
    this.bot(
      `Hi ${name} — I'm your Akatsuki trading assistant. Choose an option below and I'll take it from there.`,
      [
        { id: 'account', label: 'Account overview' },
        { id: 'orders', label: 'My orders' },
        { id: 'positions', label: 'My positions' },
        { id: 'place-order', label: 'Place an order' },
        { id: 'help', label: 'Help' },
      ]
    );
  }

  private menu(): void {
    this.bot('What would you like to do?', [
      { id: 'account', label: 'Account overview' },
      { id: 'orders', label: 'My orders' },
      { id: 'positions', label: 'My positions' },
      { id: 'place-order', label: 'Place an order' },
      { id: 'help', label: 'Help' },
    ]);
  }

  private help(): void {
    this.bot(
      'I work entirely through the buttons below. I can show your account, orders and positions, ' +
        'and I can place an order step by step (choose instrument → side → quantity → market or limit → confirm).',
      this.navActions([{ id: 'place-order', label: 'Start an order' }])
    );
  }

  private withAccount(): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId) {
      this.bot('I could not find an account on your session. Please sign in again.');
      return;
    }
    this.busy.set(true);
    this.trade.getAccount(accountId).subscribe({
      next: (acc) => {
        this.busy.set(false);
        this.bot(
          `Account #${acc.accountId} · ${acc.holderName}\nStatus: ${acc.status}\nCash balance: ${this.money(acc.cashBalance)}`,
          this.navActions([
            { id: 'nav', label: 'Open dashboard', payload: '/dashboard' },
            { id: 'place-order', label: 'Place an order' },
          ])
        );
      },
      error: () => {
        this.busy.set(false);
        this.bot('I could not reach the account service right now.', this.backMenu());
      },
    });
  }

  private withOrders(): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId) return;
    this.busy.set(true);
    this.trade.getOrders(accountId).subscribe({
      next: (orders) => {
        this.busy.set(false);
        if (orders.length === 0) {
          this.bot('You have no orders in the audit trail yet.', this.navActions([
            { id: 'place-order', label: 'Place an order' },
          ]));
          return;
        }
        const open = orders.filter((o) => o.status === 'NEW').length;
        const filled = orders.filter((o) => o.status === 'FILLED').length;
        const last = orders[0];
        this.bot(
          `You have ${orders.length} order(s): ${filled} filled, ${open} working.\n` +
            `Most recent: ${last.side} ${last.quantity} ${last.symbol} — ${last.status}.`,
          this.navActions([{ id: 'nav', label: 'Open blotter', payload: '/blotter' }])
        );
      },
      error: () => {
        this.busy.set(false);
        this.bot('I could not retrieve your orders right now.', this.backMenu());
      },
    });
  }

  private withPositions(): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId) return;
    this.busy.set(true);
    this.trade.getPositions(accountId).subscribe({
      next: (positions) => {
        this.busy.set(false);
        if (positions.length === 0) {
          this.bot('You hold no open positions yet.', this.navActions([
            { id: 'place-order', label: 'Place an order' },
          ]));
          return;
        }
        // Valued at cost: the assistant has no live marks, and an invented price
        // would show the trader a P&L that does not exist.
        const cost = positions.reduce((sum, p) => sum + p.quantity * (p.averageCost ?? 0), 0);
        const lines = positions
          .slice(0, 5)
          .map((p) => `• ${p.symbol}: ${p.quantity} @ ${this.money(p.averageCost)}`)
          .join('\n');
        this.bot(`You hold ${positions.length} position(s), cost basis ${this.money(cost)}:\n${lines}`, this.navActions([
          { id: 'nav', label: 'Open dashboard', payload: '/dashboard' },
        ]));
      },
      error: () => {
        this.busy.set(false);
        this.bot('I could not retrieve your positions right now.', this.backMenu());
      },
    });
  }

  // ── Guided order flow ────────────────────────────────────────────────
  private startOrder(): void {
    this.draft.set({});
    this.confirmError.set(null);
    this.bot(
      'Let’s place an order. Pick an instrument:',
      [
        ...SYMBOLS.map((s) => ({ id: 'symbol', label: s, payload: s })),
        { id: 'menu', label: 'Cancel' },
      ]
    );
  }

  private chooseSymbol(symbol: string): void {
    this.draft.update((d) => ({ ...d, symbol }));
    this.bot(`${symbol} selected. Buy or sell?`, [
      { id: 'side', label: 'BUY (long)', payload: 'BUY' },
      { id: 'side', label: 'SELL (close/short)', payload: 'SELL' },
      { id: 'menu', label: 'Cancel' },
    ]);
  }

  private chooseSide(side: 'BUY' | 'SELL'): void {
    this.draft.update((d) => ({ ...d, side }));
    this.bot(`${side} ${this.draft().symbol}. Choose a quantity:`, [
      ...QUANTITIES.map((q) => ({ id: 'qty', label: String(q), payload: String(q) })),
      { id: 'menu', label: 'Cancel' },
    ]);
  }

  private chooseQuantity(quantity: number): void {
    this.draft.update((d) => ({ ...d, quantity }));
    this.bot(`${quantity} × ${this.draft().symbol}. Market or limit order?`, [
      { id: 'otype', label: 'Market — fill immediately', payload: 'MARKET' },
      { id: 'otype', label: 'Limit — set my price', payload: 'LIMIT' },
      { id: 'menu', label: 'Cancel' },
    ]);
  }

  private chooseOrderType(type: 'MARKET' | 'LIMIT'): void {
    this.draft.update((d) => ({ ...d, orderType: type }));
    if (type === 'MARKET') {
      // The service prices a market order from the live book on submission.
      this.draft.update((d) => ({ ...d, price: undefined }));
      this.showConfirmation();
      return;
    }
    this.promptLimitPrice();
  }

  /**
   * Offers limit prices around the live quote, read through our own quote
   * endpoint and converted into the account currency the limit is stated in.
   */
  private promptLimitPrice(): void {
    const d = this.draft();
    this.busy.set(true);
    this.trade.getInstrumentQuote(d.symbol ?? '').subscribe({
      next: (quote) => {
        this.busy.set(false);
        const native = (d.side === 'SELL' ? quote.bid : quote.ask) ?? quote.price;
        if (native == null) {
          this.noReferencePrice();
          return;
        }
        const usd = this.currency.toUsd(quote.quoteCurrency ?? 'USD', native);
        const ref = this.currency.fromUsd(this.accountState.accountCurrency(), usd);
        this.offerLimitPrices(ref, quote.stale);
      },
      error: () => {
        this.busy.set(false);
        this.noReferencePrice();
      },
    });
  }

  private noReferencePrice(): void {
    this.bot(
      `There is no live price for ${this.draft().symbol} right now, so I cannot suggest a limit. ` +
        'You can enter your own price on the order ticket.',
      this.navActions([{ id: 'nav', label: 'Open order ticket', payload: '/order' }])
    );
  }

  private offerLimitPrices(ref: number, stale: boolean): void {
    const preset = (mult: number) => Math.max(0.01, Math.round(ref * mult * 100) / 100);
    const label = stale ? 'last known price' : 'live reference';
    this.bot(
      `${this.draft().quantity} × ${this.draft().symbol}. Choose a limit price (${label} ${this.money(ref)}):`,
      [
        { id: 'price', label: `Reference ${this.money(preset(1))}`, payload: String(preset(1)) },
        { id: 'price', label: `−1% ${this.money(preset(0.99))}`, payload: String(preset(0.99)) },
        { id: 'price', label: `+1% ${this.money(preset(1.01))}`, payload: String(preset(1.01)) },
        { id: 'price', label: `−2% ${this.money(preset(0.98))}`, payload: String(preset(0.98)) },
        { id: 'price', label: `+2% ${this.money(preset(1.02))}`, payload: String(preset(1.02)) },
        { id: 'menu', label: 'Cancel' },
      ]
    );
  }

  private choosePrice(price: string): void {
    this.draft.update((d) => ({ ...d, price: Number(price) }));
    this.showConfirmation();
  }

  private showConfirmation(): void {
    const d = this.draft();
    const isMarket = d.orderType === 'MARKET';
    this.confirmError.set(null);
    this.bot(
      `Confirm your order:\n` +
        `• ${d.side} ${d.quantity} ${d.symbol}\n` +
        `• Type: ${isMarket ? 'Market (priced by the service from the live book)' : 'Limit'}` +
        (isMarket
          ? ''
          : `\n• Limit price: ${this.money(d.price)}\n` +
            `• Estimated consideration: ${this.money((d.quantity ?? 0) * (d.price ?? 0))}`),
      [
        { id: 'confirm', label: 'Confirm & submit' },
        { id: 'place-order', label: 'Start over' },
        { id: 'menu', label: 'Cancel' },
      ]
    );
  }

  private confirmOrder(): void {
    const accountId = this.tokens.getAccountId();
    const d = this.draft();
    const isMarket = d.orderType === 'MARKET';
    if (!accountId || !d.symbol || !d.side || !d.quantity || (!isMarket && !d.price)) {
      this.bot('Something is missing from the order. Let’s start again.', this.backMenu());
      return;
    }
    this.busy.set(true);
    this.confirmError.set(null);
    this.trade
      .placeOrder({
        accountId,
        symbol: d.symbol,
        side: d.side,
        orderType: d.orderType ?? 'LIMIT',
        quantity: d.quantity,
        ...(isMarket ? {} : { price: d.price }),
        idempotencyKey: crypto.randomUUID(),
      })
      .subscribe({
        next: (order: OrderResponse) => {
          this.busy.set(false);
          this.draft.set({});
          const type = order.orderType ?? 'LIMIT';
          this.bot(
            `Order submitted.\n• ID ${order.orderId.slice(0, 18)}…\n• ${order.side} ${order.quantity} ${order.symbol} (${type}) @ ${this.money(order.price)}\n• Status: ${order.status}`,
            [
              { id: 'nav', label: 'View in blotter', payload: '/blotter' },
              { id: 'place-order', label: 'Place another order' },
              { id: 'menu', label: 'Main menu' },
            ]
          );
        },
        error: (err) => {
          this.busy.set(false);
          const message = resolveErrorMessage(err.error?.errorCode, err.status);
          this.confirmError.set(message);
          this.bot(`Order rejected: ${message}`, [
            { id: 'place-order', label: 'Try again' },
            { id: 'menu', label: 'Main menu' },
          ]);
        },
      });
  }

  /**
   * Handle natural-language chatbot message input
   */
  sendChatMessage(event: Event): void {
    const input = this.chatInput()?.nativeElement;
    if (!input) return;

    const message = input.value.trim();
    if (!message) return;

    input.value = '';

    const accountId = this.tokens.getAccountId();
    if (!accountId) {
      this.bot('You must be signed in to use the chatbot.', this.backMenu());
      return;
    }

    // Add user message to the thread
    this.user(message);
    this.busy.set(true);

    // Call chatbot backend
    const conversationHistory: ChatMessage[] = this.messages().map(msg => ({
      role: msg.role === 'user' ? 'user' : 'assistant',
      content: msg.text,
      timestamp: new Date().toISOString(),
      messageType: 'text' as const
    }));

    this.chatbot.chat({
      message,
      conversationHistory,
      confirmationToken: this.pendingConfirmationToken ?? undefined
    }).subscribe({
      next: (response) => {
        this.busy.set(false);

        if (response.messageType === 'order_confirmation') {
          // Order confirmation flow
          this.pendingConfirmationToken = response.confirmationToken;
          this.bot(response.response, [
            { id: 'confirm-order', label: 'Yes, place order' },
            { id: 'cancel-order', label: 'No, cancel' }
          ]);
        } else if (response.messageType === 'order_success') {
          this.pendingConfirmationToken = null;
          this.bot(response.response, this.backMenu());
        } else if (response.messageType === 'error') {
          this.pendingConfirmationToken = null;
          this.bot(response.response, this.backMenu());
        } else {
          this.pendingConfirmationToken = null;
          this.bot(response.response, [
            { id: 'menu', label: 'Main menu' }
          ]);
        }
      },
      error: (err) => {
        this.busy.set(false);
        this.pendingConfirmationToken = null;
        const errorMsg = resolveErrorMessage(err.error?.errorCode, err.status);
        this.bot(`An error occurred: ${errorMsg}`, this.backMenu());
      }
    });
  }

  /**
   * Handle order confirmation via chatbot
   */
  private confirmChatbotOrder(): void {
    if (!this.pendingConfirmationToken) {
      this.bot('No pending order to confirm.', this.backMenu());
      return;
    }

    this.user('Yes');
    this.busy.set(true);

    const conversationHistory: ChatMessage[] = this.messages().map(msg => ({
      role: msg.role === 'user' ? 'user' : 'assistant',
      content: msg.text,
      timestamp: new Date().toISOString(),
      messageType: 'text' as const
    }));

    this.chatbot.chat({
      message: 'yes',
      conversationHistory,
      confirmationToken: this.pendingConfirmationToken
    }).subscribe({
      next: (response) => {
        this.busy.set(false);
        this.pendingConfirmationToken = null;

        if (response.messageType === 'order_success') {
          this.bot(response.response, this.backMenu());
        } else {
          this.bot(response.response, this.backMenu());
        }
      },
      error: (err) => {
        this.busy.set(false);
        this.pendingConfirmationToken = null;
        const errorMsg = resolveErrorMessage(err.error?.errorCode, err.status);
        this.bot(`Error: ${errorMsg}`, this.backMenu());
      }
    });
  }

  /**
   * Handle order cancellation via chatbot
   */
  private cancelChatbotOrder(): void {
    this.pendingConfirmationToken = null;
    this.user('No');
    this.bot('Order cancelled.', this.backMenu());
  }
}
