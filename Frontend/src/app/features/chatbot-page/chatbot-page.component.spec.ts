import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ChatbotPageComponent } from './chatbot-page.component';
import { ChatbotService, type ChatRequest, type ChatResponse } from '../../core/services/chatbot.service';
import { TradeService } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import { CurrencyService } from '../../core/services/currency.service';
import { AccountStateService } from '../../core/services/account-state.service';

function reply(overrides: Partial<ChatResponse> = {}): ChatResponse {
  return {
    response: 'Your portfolio holds 10 AAPL.',
    messageType: 'text',
    timestamp: '2026-10-08T10:00:00Z',
    orderConfirmation: null,
    confirmationToken: null,
    success: true,
    ...overrides,
  };
}

describe('ChatbotPageComponent', () => {
  let chat: jasmine.Spy;

  beforeEach(() => {
    chat = jasmine.createSpy('chat').and.returnValue(of(reply()));
    TestBed.configureTestingModule({
      imports: [ChatbotPageComponent],
      providers: [
        provideRouter([]),
        { provide: ChatbotService, useValue: { chat } },
        { provide: TradeService, useValue: {} },
        { provide: TokenService, useValue: { getAccountId: () => 1 } },
        { provide: CurrencyService, useValue: {} },
        { provide: AccountStateService, useValue: {} },
      ],
    });
  });

  function create() {
    const fixture = TestBed.createComponent(ChatbotPageComponent);
    fixture.detectChanges();
    return fixture;
  }

  function lastRequest(): ChatRequest {
    return chat.calls.mostRecent().args[0] as ChatRequest;
  }

  it('greets once and can navigate with an account', () => {
    const c = create().componentInstance;
    expect(c.messages().length).toBe(1);
    expect(c.messages()[0].role).toBe('bot');
    c.greet();
    expect(c.messages().length).toBe(1);
    expect(c.canNavigate()).toBeTrue();
  });

  it('sends typed text with the conversation so far and shows the answer', () => {
    const fixture = create();
    const input: HTMLInputElement = fixture.nativeElement.querySelector('input');
    input.value = '  what do I hold?  ';
    fixture.componentInstance.sendChatMessage();

    expect(lastRequest().message).toBe('what do I hold?');
    expect(lastRequest().conversationHistory.map((m) => m.role)).toEqual(['assistant', 'user']);
    expect(input.value).toBe('');
    const messages = fixture.componentInstance.messages();
    expect(messages[messages.length - 1].text).toBe('Your portfolio holds 10 AAPL.');
    expect(fixture.componentInstance.busy()).toBeFalse();
  });

  it('ignores an empty message', () => {
    const fixture = create();
    fixture.componentInstance.sendChatMessage();
    expect(chat).not.toHaveBeenCalled();
  });

  it('walks an order through confirmation, sending the token back with the answer', () => {
    const c = create().componentInstance;
    chat.and.returnValue(of(reply({
      messageType: 'order_confirmation',
      response: 'Buy 5 AAPL at market?',
      confirmationToken: 'tok-1',
      orderConfirmation: {
        instrument: 'Apple', symbol: 'AAPL', side: 'BUY', quantity: 5, orderType: 'MARKET', price: null,
        estimatedCost: 950, userConfirmationQuestion: 'Confirm?',
      },
    })));
    c.runAction({ id: 'place-order', label: 'Place order' });
    expect(lastRequest().message).toBe('I want to place an order');
    expect(c.draft()).toEqual(jasmine.objectContaining({ symbol: 'AAPL', side: 'BUY', quantity: 5 }));
    const prompt = c.messages()[c.messages().length - 1];
    expect(prompt.actions!.map((a) => a.id)).toEqual(['confirm-order', 'cancel-order']);

    chat.and.returnValue(of(reply({ messageType: 'order_success', response: 'Order placed.' })));
    c.runAction({ id: 'confirm-order', label: 'Yes, confirm' });
    expect(lastRequest().message).toBe('yes');
    expect(lastRequest().confirmationToken).toBe('tok-1');
    expect(c.draft()).toEqual({});

    c.runAction({ id: 'cancel-order', label: 'No, cancel' });
    expect(lastRequest().message).toBe('no');
    expect(lastRequest().confirmationToken).toBeUndefined();
  });

  it('shows an assistant error with retry actions', () => {
    const c = create().componentInstance;
    chat.and.returnValue(of(reply({ messageType: 'error', response: 'Insufficient funds.' })));
    c.runAction({ id: 'other', label: 'Buy 1000 TSLA' });
    expect(lastRequest().message).toBe('Buy 1000 TSLA');
    expect(c.confirmError()).toBe('Insufficient funds.');
    expect(c.messages()[c.messages().length - 1].actions!.map((a) => a.id)).toEqual(['menu', 'place-order']);
  });

  it('turns a transport failure into the catalogue message', () => {
    const c = create().componentInstance;
    chat.and.returnValue(throwError(() => ({ status: 0 })));
    c.sendChatMessage('hello');
    expect(c.busy()).toBeFalse();
    expect(c.confirmError()).toBeTruthy();
    expect(c.messages()[c.messages().length - 1].role).toBe('bot');
  });

  it('starts over from the main menu', () => {
    const c = create().componentInstance;
    c.sendChatMessage('hello');
    expect(c.messages().length).toBe(3);
    c.runAction({ id: 'menu', label: 'Main menu' });
    expect(c.messages().length).toBe(1);
    expect(c.draft()).toEqual({});
    expect(c.confirmError()).toBeNull();
  });

  it('offers prompts on a fresh chat, sends one on click, and starts over', () => {
    const c = create().componentInstance;
    expect(c.fresh()).toBeTrue();
    expect(c.suggestionGroups.length).toBe(4);
    c.ask('What do I hold?');
    expect(lastRequest().message).toBe('What do I hold?');
    expect(c.fresh()).toBeFalse();
    c.busy.set(true);
    c.ask('ignored while busy');
    expect(lastRequest().message).toBe('What do I hold?');
    c.busy.set(false);
    c.newChat();
    expect(c.fresh()).toBeTrue();
    expect(c.messages().length).toBe(1);
  });
});
