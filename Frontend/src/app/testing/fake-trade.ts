import { of } from 'rxjs';
import type { TradeService, InstrumentQuote } from '../core/services/trade.service';
import type {
  AccountResponse,
  BalanceResponse,
  OrderHistoryEntry,
  OrderStatus,
  PositionResponse,
} from '@trade-api/model/models';

/** Test data builders and a TradeService stand-in for component specs. */

export function position(symbol: string, quantity: number, averageCost: number): PositionResponse {
  return { accountId: 1, symbol, quantity, averageCost } as PositionResponse;
}

export function quote(symbol: string, price: number, quoteCurrency = 'USD'): InstrumentQuote {
  return { symbol, price, bid: price, ask: price, quoteCurrency, stale: false };
}

let seq = 0;
export function historyEntry(
  overrides: Partial<OrderHistoryEntry> & { status: OrderStatus },
): OrderHistoryEntry {
  seq += 1;
  return {
    orderId: `ORD-${seq}`,
    accountId: 1,
    symbol: 'AAPL',
    side: 'BUY',
    quantity: 1,
    price: 10,
    createdOn: '2026-10-05T09:00:00Z',
    ...overrides,
  };
}

export interface FakeTradeData {
  currency?: string;
  cash?: number;
  positions?: PositionResponse[];
  orders?: OrderHistoryEntry[];
  quotes?: InstrumentQuote[];
}

export function fakeTrade(data: FakeTradeData = {}): jasmine.SpyObj<TradeService> {
  const currency = data.currency ?? 'USD';
  const trade = jasmine.createSpyObj<TradeService>('TradeService', [
    'getAccount',
    'getBalance',
    'getPositions',
    'getOrders',
    'getInstrumentQuote',
    'placeOrder',
    'cancelOrder',
  ]);
  trade.getAccount.and.returnValue(
    of({ accountId: 1, currency, status: 'ACTIVE' } as unknown as AccountResponse),
  );
  trade.getBalance.and.returnValue(
    of({ accountId: 1, cashBalance: data.cash ?? 0, currency, asOf: '' } as BalanceResponse),
  );
  trade.getPositions.and.returnValue(of(data.positions ?? []));
  trade.getOrders.and.returnValue(of(data.orders ?? []));
  const quotes = new Map((data.quotes ?? []).map((q) => [q.symbol, q]));
  trade.getInstrumentQuote.and.callFake((symbol: string) => {
    const q = quotes.get(symbol);
    return q ? of(q) : of(null as unknown as InstrumentQuote);
  });
  return trade;
}
