import { buildTradeSummary, type TradeSummaryInput } from './trade-summary';

/** Reference rates, units per USD, as CurrencyService holds them. */
const PER_USD: Record<string, number> = { USD: 1, INR: 83.3 };
const convert = (amount: number, from: string, to: string): number => (amount / PER_USD[from]) * PER_USD[to];

function input(overrides: Partial<TradeSummaryInput> = {}): TradeSummaryInput {
  return {
    symbol: 'AAPL',
    side: 'BUY',
    orderType: 'MARKET',
    quantity: 10,
    limitPrice: null,
    quote: { price: 150, bid: 149.5, ask: 150.5, quoteCurrency: 'USD' },
    accountCurrency: 'USD',
    cashBalance: 10_000,
    heldQuantity: 0,
    averageCost: null,
    convert,
    ...overrides,
  };
}

describe('buildTradeSummary', () => {
  describe('BUY', () => {
    it('prices a market buy at the ask and takes the cost off cash', () => {
      const s = buildTradeSummary(input());
      expect(s.unitPrice).toBe(150.5);
      expect(s.total).toBe(1505);
      expect(s.cashAfter).toBe(8495);
      expect(s.heldAfter).toBe(10);
      expect(s.cashShortfall).toBe(0);
    });

    it('prices a limit buy at the limit, not the market', () => {
      const s = buildTradeSummary(input({ orderType: 'LIMIT', limitPrice: 140 }));
      expect(s.unitPrice).toBe(140);
      expect(s.total).toBe(1400);
      expect(s.marketPrice).toBe(150.5);
    });

    it('reports a cash shortfall without blocking: the executor decides', () => {
      const s = buildTradeSummary(input({ cashBalance: 1000 }));
      expect(s.total).toBe(1505);
      expect(s.cashShortfall).toBe(505);
    });

    it('shows no cash or holding movement for a buy the executor would reject', () => {
      const s = buildTradeSummary(input({ cashBalance: 1000, heldQuantity: 2 }));
      expect(s.cashAfter).toBeNull();
      expect(s.heldAfter).toBe(2);
    });

    it('treats cash that exactly covers the cost as enough', () => {
      const s = buildTradeSummary(input({ cashBalance: 1505 }));
      expect(s.cashShortfall).toBe(0);
      expect(s.cashAfter).toBe(0);
    });

    it('adds to an existing holding', () => {
      expect(buildTradeSummary(input({ heldQuantity: 5 })).heldAfter).toBe(15);
    });

    it('has no realised P&L on a buy', () => {
      expect(buildTradeSummary(input({ heldQuantity: 5, averageCost: 100 })).realisedPnl).toBeNull();
    });
  });

  describe('SELL', () => {
    it('prices a market sell at the bid and adds the proceeds to cash', () => {
      const s = buildTradeSummary(input({ side: 'SELL', heldQuantity: 20, averageCost: 120 }));
      expect(s.unitPrice).toBe(149.5);
      expect(s.total).toBe(1495);
      expect(s.cashAfter).toBe(11_495);
      expect(s.heldAfter).toBe(10);
    });

    it('works out realised P&L against the average cost', () => {
      const s = buildTradeSummary(input({ side: 'SELL', heldQuantity: 20, averageCost: 120 }));
      expect(s.realisedPnl).toBe(295); // (149.5 - 120) x 10
    });

    it('reports a realised loss as negative', () => {
      const s = buildTradeSummary(input({ side: 'SELL', heldQuantity: 20, averageCost: 160 }));
      expect(s.realisedPnl).toBe(-105);
    });

    it('reports a holdings shortfall and shows no movement for the rejected sell', () => {
      const s = buildTradeSummary(input({ side: 'SELL', heldQuantity: 4, averageCost: 100 }));
      expect(s.holdingShortfall).toBe(6);
      expect(s.heldAfter).toBe(4);
      expect(s.cashAfter).toBeNull();
      expect(s.realisedPnl).toBeNull();
    });

    it('sells the whole holding down to zero', () => {
      const s = buildTradeSummary(input({ side: 'SELL', heldQuantity: 10 }));
      expect(s.holdingShortfall).toBe(0);
      expect(s.heldAfter).toBe(0);
    });

    it('treats a sell with nothing held as a full shortfall', () => {
      const s = buildTradeSummary(input({ side: 'SELL', heldQuantity: 0 }));
      expect(s.holdingShortfall).toBe(10);
      expect(s.realisedPnl).toBeNull();
    });
  });

  describe('currency', () => {
    it('converts a foreign (INR) quote into a USD account', () => {
      const s = buildTradeSummary(
        input({ symbol: 'INFY.NS', quote: { price: 1500, bid: 1499, ask: 1501, quoteCurrency: 'INR' } }),
      );
      expect(s.foreign).toBeTrue();
      expect(s.quoteCurrency).toBe('INR');
      expect(s.fxRate).toBeCloseTo(1 / 83.3, 6);
      expect(s.marketPriceNative).toBe(1501);
      expect(s.marketPrice).toBe(18.02); // 1501 / 83.3
      expect(s.totalNative).toBe(15_010);
      expect(s.total).toBe(180.2);
    });

    it('converts a USD quote into an INR account', () => {
      const s = buildTradeSummary(input({ accountCurrency: 'INR', cashBalance: 1_000_000 }));
      expect(s.fxRate).toBeCloseTo(83.3, 6);
      expect(s.marketPrice).toBe(12_536.65); // 150.5 x 83.3
      expect(s.total).toBe(125_366.5);
    });

    it('shows a limit price in the instrument currency too', () => {
      const s = buildTradeSummary(
        input({
          orderType: 'LIMIT',
          limitPrice: 20,
          quote: { price: 1500, bid: 1499, ask: 1501, quoteCurrency: 'INR' },
        }),
      );
      expect(s.unitPriceNative).toBeCloseTo(1666, 0);
    });

    it('is not foreign when the quote is in the account currency', () => {
      const s = buildTradeSummary(input());
      expect(s.foreign).toBeFalse();
      expect(s.fxRate).toBe(1);
    });
  });

  describe('missing inputs', () => {
    it('has no total for a market order without a quote', () => {
      const s = buildTradeSummary(input({ quote: null }));
      expect(s.unitPrice).toBeNull();
      expect(s.total).toBeNull();
      expect(s.cashAfter).toBeNull();
    });

    it('still prices a limit order without a quote', () => {
      const s = buildTradeSummary(input({ orderType: 'LIMIT', limitPrice: 10, quote: null }));
      expect(s.total).toBe(100);
    });

    it('falls back to the last price when the book has no side', () => {
      const s = buildTradeSummary(input({ quote: { price: 150, quoteCurrency: 'USD' } }));
      expect(s.unitPrice).toBe(150);
    });

    it('has no total for a zero or invalid quantity', () => {
      expect(buildTradeSummary(input({ quantity: 0 })).total).toBeNull();
      expect(buildTradeSummary(input({ quantity: Number.NaN })).total).toBeNull();
    });

    it('has no cash-after when the balance is unknown', () => {
      expect(buildTradeSummary(input({ cashBalance: null })).cashAfter).toBeNull();
    });
  });
});
