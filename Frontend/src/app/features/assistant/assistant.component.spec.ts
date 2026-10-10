import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AssistantComponent } from './assistant.component';
import { TradeService } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import { ChatbotService } from '../../core/services/chatbot.service';
import { fakeTrade, historyEntry, position, quote } from '../../testing/fake-trade';
import type { AccountResponse, OrderResponse } from '@trade-api/model/models';

describe('AssistantComponent', () => {
  let trade: jasmine.SpyObj<TradeService>;
  let tokens: jasmine.SpyObj<TokenService>;

  function create(): AssistantComponent {
    const fixture = TestBed.createComponent(AssistantComponent);
    fixture.detectChanges();
    spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    return fixture.componentInstance;
  }

  const lastBot = (c: AssistantComponent) => c.messages().filter((m) => m.role === 'bot').at(-1)!;
  const press = (c: AssistantComponent, label: string) => {
    const action = lastBot(c).actions!.find((a) => a.label.startsWith(label))!;
    expect(action).withContext(label).toBeDefined();
    c.runAction(action);
  };

  beforeEach(() => {
    trade = fakeTrade({
      cash: 500,
      positions: [position('AAPL', 3, 100)],
      orders: [historyEntry({ status: 'NEW' }), historyEntry({ status: 'FILLED' })],
      quotes: [quote('AAPL', 100)],
    });
    trade.getAccount.and.returnValue(
      of({ accountId: 7, holderName: 'Ada', status: 'ACTIVE', cashBalance: 500 } as unknown as AccountResponse),
    );
    tokens = jasmine.createSpyObj('TokenService', ['getAccountId', 'getUsername']);
    tokens.getAccountId.and.returnValue(7);
    tokens.getUsername.and.returnValue('ada');
    TestBed.configureTestingModule({
      imports: [AssistantComponent],
      providers: [
        provideRouter([]),
        { provide: TradeService, useValue: trade },
        { provide: TokenService, useValue: tokens },
      ],
    });
  });

  it('greets once, by name, when first opened', fakeAsync(() => {
    const c = create();
    c.toggle();
    expect(c.open()).toBeTrue();
    expect(lastBot(c).text).toContain('ada');
    c.toggle();
    c.toggle();
    expect(c.messages().filter((m) => m.role === 'bot').length).toBe(1);
    c.close();
    expect(c.open()).toBeFalse();
    tick(100);
  }));

  it('summarises the account, the orders and the positions', fakeAsync(() => {
    const c = create();
    c.toggle();
    press(c, 'Account overview');
    expect(lastBot(c).text).toContain('Account #7');
    press(c, 'Main menu');
    press(c, 'My orders');
    expect(lastBot(c).text).toContain('1 filled, 1 working');
    press(c, 'Main menu');
    press(c, 'My positions');
    expect(lastBot(c).text).toContain('AAPL: 3');
    expect(lastBot(c).text).toContain('cost basis');
    press(c, 'Main menu');
    press(c, 'Help');
    expect(lastBot(c).text).toContain('buttons');
    tick(100);
  }));

  it('says so when there are no orders or positions', fakeAsync(() => {
    trade.getOrders.and.returnValue(of([]));
    trade.getPositions.and.returnValue(of([]));
    const c = create();
    c.toggle();
    press(c, 'My orders');
    expect(lastBot(c).text).toContain('no orders');
    press(c, 'Place an order');
    press(c, 'Cancel');
    press(c, 'My positions');
    expect(lastBot(c).text).toContain('no open positions');
    tick(100);
  }));

  it('reports a service it cannot reach', fakeAsync(() => {
    trade.getAccount.and.returnValue(throwError(() => new Error('x')));
    trade.getOrders.and.returnValue(throwError(() => new Error('x')));
    trade.getPositions.and.returnValue(throwError(() => new Error('x')));
    const c = create();
    c.toggle();
    press(c, 'Account overview');
    expect(lastBot(c).text).toContain('could not reach');
    press(c, 'Main menu');
    press(c, 'My orders');
    expect(lastBot(c).text).toContain('could not retrieve your orders');
    press(c, 'Main menu');
    press(c, 'My positions');
    expect(lastBot(c).text).toContain('could not retrieve your positions');
    tick(100);
  }));

  it('asks for a session when there is no account', fakeAsync(() => {
    tokens.getAccountId.and.returnValue(null);
    const c = create();
    c.toggle();
    press(c, 'Account overview');
    expect(lastBot(c).text).toContain('sign in again');
    tick(100);
  }));

  it('walks a market order through to submission without a price', fakeAsync(() => {
    trade.placeOrder.and.returnValue(
      of({ orderId: 'abcdefghijklmnopqrstuvwxyz', side: 'BUY', quantity: 5, symbol: 'AAPL', status: 'NEW', price: 100, orderType: 'MARKET' } as OrderResponse),
    );
    const c = create();
    c.toggle();
    press(c, 'Place an order');
    press(c, 'AAPL');
    press(c, 'BUY');
    press(c, '5');
    press(c, 'Market');
    expect(lastBot(c).text).toContain('Confirm your order');
    press(c, 'Confirm');

    const sent = trade.placeOrder.calls.mostRecent().args[0];
    expect(sent.orderType).toBe('MARKET');
    expect(sent.price).toBeUndefined();
    expect(sent.accountId).toBe(7);
    expect(lastBot(c).text).toContain('Order submitted');
    press(c, 'View in blotter');
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/blotter']);
    expect(c.open()).toBeFalse();
    tick(100);
  }));

  it('offers limit prices around the live price and sends the chosen one', fakeAsync(() => {
    trade.placeOrder.and.returnValue(of({ orderId: 'o-1-xxxxxxxxxxxxxxxxx', status: 'NEW' } as OrderResponse));
    const c = create();
    c.toggle();
    press(c, 'Place an order');
    press(c, 'AAPL');
    press(c, 'SELL');
    press(c, '1');
    press(c, 'Limit');
    expect(lastBot(c).text).toContain('Choose a limit price');
    press(c, '+1%');
    press(c, 'Confirm');
    expect(trade.placeOrder.calls.mostRecent().args[0].price).toBe(101);
    tick(100);
  }));

  it('explains when there is no price to suggest a limit from', fakeAsync(() => {
    trade.getInstrumentQuote.and.returnValue(throwError(() => new Error('x')));
    const c = create();
    c.toggle();
    press(c, 'Place an order');
    press(c, 'MSFT');
    press(c, 'BUY');
    press(c, '10');
    press(c, 'Limit');
    expect(lastBot(c).text).toContain('no live price');
    tick(100);
  }));

  it('shows the catalogue reason when the order is refused', fakeAsync(() => {
    trade.placeOrder.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 403, error: { errorCode: 'ACC-403' } })),
    );
    const c = create();
    c.toggle();
    press(c, 'Place an order');
    press(c, 'AAPL');
    press(c, 'BUY');
    press(c, '1');
    press(c, 'Market');
    press(c, 'Confirm');
    expect(c.confirmError()).toBeTruthy();
    expect(lastBot(c).text).toContain('Order rejected');
    press(c, 'Main menu');
    tick(100);
  }));

  it('refuses to submit an incomplete order', fakeAsync(() => {
    const c = create();
    c.toggle();
    c.runAction({ id: 'confirm', label: 'Confirm & submit' });
    expect(trade.placeOrder).not.toHaveBeenCalled();
    expect(lastBot(c).text).toContain('missing');
    c.runAction({ id: 'cancel', label: 'Cancel' });
    expect(lastBot(c).text).toContain('cancelled');
    tick(100);
  }));
});

describe('AssistantComponent chatbot', () => {
  let chat: jasmine.Spy;
  let accountId: number | null;

  const reply = (messageType: string, response: string, confirmationToken: string | null = null) =>
    of({ response, messageType, timestamp: '', orderConfirmation: null, confirmationToken, success: true });

  beforeEach(() => {
    accountId = 7;
    chat = jasmine.createSpy('chat').and.returnValue(reply('text', 'You hold 3 AAPL.'));
    const tokens = { getAccountId: () => accountId, getUsername: () => 'ada' };
    TestBed.configureTestingModule({
      imports: [AssistantComponent],
      providers: [
        provideRouter([]),
        { provide: TradeService, useValue: fakeTrade({}) },
        { provide: TokenService, useValue: tokens },
        { provide: ChatbotService, useValue: { chat } },
      ],
    });
  });

  function open() {
    const fixture = TestBed.createComponent(AssistantComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggle();
    fixture.detectChanges();
    return fixture;
  }

  function say(fixture: ReturnType<typeof open>, text: string) {
    const input = fixture.nativeElement.querySelector('.chatbot-input') as HTMLInputElement;
    input.value = text;
    fixture.componentInstance.sendChatMessage(new Event('keydown'));
    return input;
  }

  const lastBotText = (fixture: ReturnType<typeof open>) =>
    fixture.componentInstance.messages().filter((m) => m.role === 'bot').at(-1)!;

  it('sends a typed question with the conversation and shows the answer', fakeAsync(() => {
    const fixture = open();
    const input = say(fixture, '  what do I hold?  ');
    expect(input.value).toBe('');
    expect(chat.calls.mostRecent().args[0].message).toBe('what do I hold?');
    expect(lastBotText(fixture).text).toBe('You hold 3 AAPL.');
    expect(lastBotText(fixture).actions![0].id).toBe('menu');
    tick(100);
  }));

  it('ignores an empty message and asks for a session without an account', fakeAsync(() => {
    const fixture = open();
    say(fixture, '   ');
    expect(chat).not.toHaveBeenCalled();
    accountId = null;
    say(fixture, 'hello');
    expect(chat).not.toHaveBeenCalled();
    expect(lastBotText(fixture).text).toContain('signed in');
    tick(100);
  }));

  it('confirms an order with its token, and cancels one', fakeAsync(() => {
    const fixture = open();
    const c = fixture.componentInstance;
    chat.and.returnValue(reply('order_confirmation', 'Buy 5 AAPL?', 'tok-1'));
    say(fixture, 'buy 5 aapl');
    expect(lastBotText(fixture).actions!.map((a) => a.id)).toEqual(['confirm-order', 'cancel-order']);

    chat.and.returnValue(reply('order_success', 'Order placed.'));
    c.runAction({ id: 'confirm-order', label: 'Yes, place order' });
    expect(chat.calls.mostRecent().args[0].confirmationToken).toBe('tok-1');
    expect(lastBotText(fixture).text).toBe('Order placed.');

    c.runAction({ id: 'confirm-order', label: 'Yes, place order' });
    expect(lastBotText(fixture).text).toContain('No pending order');

    chat.and.returnValue(reply('order_confirmation', 'Sell 1 AAPL?', 'tok-2'));
    say(fixture, 'sell 1 aapl');
    c.runAction({ id: 'cancel-order', label: 'No, cancel' });
    expect(lastBotText(fixture).text).toBe('Order cancelled.');
    tick(100);
  }));

  it('shows a refusal and a transport failure', fakeAsync(() => {
    const fixture = open();
    const c = fixture.componentInstance;
    chat.and.returnValue(reply('error', 'Insufficient funds.'));
    say(fixture, 'buy 1000 tsla');
    expect(lastBotText(fixture).text).toBe('Insufficient funds.');

    chat.and.returnValue(throwError(() => ({ status: 0, error: {} })));
    say(fixture, 'hello');
    expect(lastBotText(fixture).text).toContain('An error occurred');
    expect(c.busy()).toBeFalse();

    chat.and.returnValue(reply('order_confirmation', 'Buy 1 AAPL?', 'tok-3'));
    say(fixture, 'buy 1 aapl');
    chat.and.returnValue(throwError(() => ({ status: 500, error: {} })));
    c.runAction({ id: 'confirm-order', label: 'Yes, place order' });
    expect(lastBotText(fixture).text).toContain('Error:');

    chat.and.returnValue(reply('order_confirmation', 'Buy 1 AAPL?', 'tok-4'));
    say(fixture, 'buy 1 aapl');
    chat.and.returnValue(reply('error', 'Market closed.'));
    c.runAction({ id: 'confirm-order', label: 'Yes, place order' });
    expect(lastBotText(fixture).text).toBe('Market closed.');
    tick(100);
  }));

  it('expands into the full-page assistant', fakeAsync(() => {
    const fixture = open();
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    fixture.componentInstance.openFullPage();
    expect(fixture.componentInstance.open()).toBeFalse();
    expect(navigate).toHaveBeenCalledWith(['/chatbot']);
    tick(100);
  }));
});
