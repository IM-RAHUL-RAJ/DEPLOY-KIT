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
  selector: 'app-chatbot-page',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './chatbot-page.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './chatbot-page.component.scss',
})
export class ChatbotPageComponent {
  private readonly trade = inject(TradeService);
  private readonly tokens = inject(TokenService);
  private readonly router = inject(Router);
  private readonly currency = inject(CurrencyService);
  private readonly accountState = inject(AccountStateService);
  private readonly chatbot = inject(ChatbotService);

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

  /** Prompts offered on the welcome screen and in the side rail, grouped by topic. */
  readonly suggestionGroups: { title: string; icon: string; prompts: string[] }[] = [
    { title: 'Portfolio', icon: 'M3 3v18h18M7 14l3-4 3 3 4-6', prompts: ['What do I hold?', 'How is my portfolio doing?'] },
    { title: 'Orders', icon: 'M4 4h16v16H4zM4 9h16M9 9v11', prompts: ['Show my recent orders', 'Do I have any open orders?'] },
    { title: 'Markets', icon: 'M12 2v20M2 12h20', prompts: ['What is the price of AAPL?', 'Compare MSFT and GOOGL'] },
    { title: 'Trade', icon: 'M12 5v14M5 12h14', prompts: ['Buy 1 AAPL at market', 'Sell 5 MSFT'] },
  ];

  /** True until the customer has said anything: the welcome screen shows. */
  readonly fresh = computed(() => !this.messages().some((m) => m.role === 'user'));

  ask(prompt: string): void {
    if (!this.busy()) {
      this.sendChatMessage(prompt);
    }
  }

  newChat(): void {
    this.runAction({ id: 'menu', label: 'New chat' });
  }

  constructor() {
    effect(() => {
      this.messages();
      this.busy();
      this.scrollToBottom();
    });
    this.greet();
  }

  greet(): void {
    if (this.greeted) return;
    this.greeted = true;
    this.addMessage({
      id: this.seq++,
      role: 'bot',
      text: 'Hello! I\'m the Akatsuki Assistant. I can help you with portfolio queries, instrument research, order placement, and more. How can I assist you today?',
    });
  }

  runAction(action: AssistantAction): void {
    const { id, label, payload } = action;

    if (id === 'menu') {
      this.messages.set([]);
      this.seq = 0;
      this.greeted = false;
      this.confirmError.set(null);
      this.draft.set({});
      this.pendingConfirmationToken = null;
      this.greet();
      return;
    }

    if (id === 'place-order') {
      this.sendChatMessage('I want to place an order');
      return;
    }

    // Handle order confirmation responses
    if (id === 'confirm-order') {
      this.sendChatMessage('yes');
      return;
    }

    if (id === 'cancel-order') {
      this.sendChatMessage('no');
      return;
    }

    this.sendChatMessage(label);
  }

  sendChatMessage(text?: string): void {
    const message = text ?? (this.chatInput()?.nativeElement.value || '').trim();
    if (!message) return;

    const input = this.chatInput()?.nativeElement;
    if (input) {
      input.focus();
      input.value = '';
    }
    this.confirmError.set(null);

    this.addMessage({
      id: this.seq++,
      role: 'user',
      text: message,
    });

    this.busy.set(true);

    // Prepare request - convert messages to the format expected by backend
    const conversationHistory: ChatMessage[] = this.messages()
      .filter((m) => m.role === 'user' || m.role === 'bot')
      .map((m) => ({
        role: m.role === 'bot' ? 'assistant' : 'user',
        content: m.text,
        timestamp: new Date().toISOString(),
        messageType: 'text' as const,
      })) as ChatMessage[];

    const request = {
      message,
      conversationHistory,
      confirmationToken: this.pendingConfirmationToken ?? undefined,
    };

    // Send to backend
    this.chatbot.chat(request).subscribe({
      next: (response) => {
        this.busy.set(false);

        // Handle order confirmation flow
        if (response.messageType === 'order_confirmation' && response.orderConfirmation) {
          const orderData = response.orderConfirmation;
          this.pendingConfirmationToken = response.confirmationToken || null;
          this.draft.set({
            symbol: orderData.symbol,
            side: orderData.side as 'BUY' | 'SELL',
            orderType: orderData.orderType as 'MARKET' | 'LIMIT',
            quantity: orderData.quantity,
            price: orderData.price || undefined,
          });

          this.addMessage({
            id: this.seq++,
            role: 'bot',
            text: response.response,
            actions: [
              { id: 'confirm-order', label: 'Yes, confirm' },
              { id: 'cancel-order', label: 'No, cancel' },
            ],
          });
          return;
        }

        // Handle order success
        if (response.messageType === 'order_success') {
          this.pendingConfirmationToken = null;
          this.draft.set({});
          this.addMessage({
            id: this.seq++,
            role: 'bot',
            text: response.response,
            actions: [{ id: 'menu', label: 'Main menu' }],
          });
          return;
        }

        // Handle errors
        if (response.messageType === 'error') {
          this.pendingConfirmationToken = null;
          this.confirmError.set(response.response);
          this.addMessage({
            id: this.seq++,
            role: 'bot',
            text: response.response,
            actions: [
              { id: 'menu', label: 'Main menu' },
              { id: 'place-order', label: 'Try again' },
            ],
          });
          return;
        }

        // Handle regular responses
        this.addMessage({
          id: this.seq++,
          role: 'bot',
          text: response.response,
          actions: [{ id: 'menu', label: 'Main menu' }],
        });
      },
      error: (error: any) => {
        this.busy.set(false);
        this.pendingConfirmationToken = null;
        const errorCode = error?.error?.errorCode;
        const status = error?.status ?? 0;
        const errorMessage = resolveErrorMessage(errorCode, status);
        this.confirmError.set(errorMessage);
        this.addMessage({
          id: this.seq++,
          role: 'bot',
          text: errorMessage,
          actions: [{ id: 'menu', label: 'Main menu' }],
        });
      },
    });
  }

  private addMessage(message: AssistantMessage): void {
    this.messages.update((msgs) => [...msgs, message]);
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      const thread = this.threadRef()?.nativeElement;
      if (thread) {
        thread.scrollTop = thread.scrollHeight;
      }
    }, 0);
  }
}
