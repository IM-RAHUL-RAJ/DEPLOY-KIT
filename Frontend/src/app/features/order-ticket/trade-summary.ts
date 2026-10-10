/**
 * The order ticket's live summary: everything a trader needs to see about the
 * order the form describes, before sending it.
 *
 * Pure so it can be tested without a component. Every amount comes back in the
 * account's ledger currency unless its name says "native" (the instrument's own
 * trading currency).
 *
 * This is a preview. Whether a buy can be paid for or a sell can be delivered is
 * decided by the Trade Executor at the price the order actually fills at, so a
 * shortfall here is a warning, never a block: the order is still accepted as NEW
 * and moves to REJECTED with the executor's reason if the shortfall is real.
 */
export interface TradeSummaryInput {
  symbol: string;
  side: 'BUY' | 'SELL';
  orderType: 'MARKET' | 'LIMIT';
  quantity: number;
  /** The limit price, already in the account currency. Ignored for MARKET. */
  limitPrice: number | null;
  /** Live quote in the instrument's own currency, if one was available. */
  quote: { price?: number | null; bid?: number | null; ask?: number | null; quoteCurrency?: string | null } | null;
  accountCurrency: string;
  cashBalance: number | null;
  heldQuantity: number;
  /** Average cost of the current holding, in the account currency. */
  averageCost: number | null;
  /** Converts an amount between two currency codes at the reference rate. */
  convert: (amount: number, from: string, to: string) => number;
}

export interface TradeSummary {
  symbol: string;
  side: 'BUY' | 'SELL';
  orderType: 'MARKET' | 'LIMIT';
  quantity: number;
  quoteCurrency: string;
  /** True when the instrument trades in another currency than the account. */
  foreign: boolean;
  /** Units of the account currency per one unit of the quote currency. */
  fxRate: number;
  /** The live side of the book this order would trade against (ask to buy, bid to sell). */
  marketPriceNative: number | null;
  marketPrice: number | null;
  /** The price the totals are worked out at: the limit for LIMIT, the market for MARKET. */
  unitPrice: number | null;
  unitPriceNative: number | null;
  /** quantity x unitPrice: what the buy costs, or what the sell brings in. */
  total: number | null;
  totalNative: number | null;
  cashBefore: number | null;
  /**
   * Cash after a fill at unitPrice: less the cost for a buy, plus the proceeds for
   * a sell. Null when unknown, or when a shortfall means the order would be rejected.
   */
  cashAfter: number | null;
  heldBefore: number;
  heldAfter: number;
  /** Sell only: (unitPrice - averageCost) x quantity, when the cost is known. */
  realisedPnl: number | null;
  /** Buy only: how much cash is missing at unitPrice, or 0. */
  cashShortfall: number;
  /** Sell only: how many units are missing, or 0. */
  holdingShortfall: number;
}

const round2 = (n: number): number => Math.round(n * 100) / 100;

export function buildTradeSummary(input: TradeSummaryInput): TradeSummary {
  const { side, orderType, quantity, accountCurrency, convert } = input;
  const quoteCurrency = (input.quote?.quoteCurrency || accountCurrency).toUpperCase();
  const foreign = quoteCurrency !== accountCurrency.toUpperCase();
  const fxRate = foreign ? convert(1, quoteCurrency, accountCurrency) : 1;

  const book = side === 'BUY' ? input.quote?.ask : input.quote?.bid;
  const marketPriceNative = book ?? input.quote?.price ?? null;
  const marketPrice = marketPriceNative == null ? null : round2(marketPriceNative * fxRate);

  const limit = orderType === 'LIMIT' && input.limitPrice != null && input.limitPrice > 0 ? input.limitPrice : null;
  const unitPrice = orderType === 'LIMIT' ? limit : marketPrice;
  const unitPriceNative =
    orderType === 'LIMIT' ? (limit == null || !fxRate ? null : limit / fxRate) : marketPriceNative;

  const qty = Number.isFinite(quantity) && quantity > 0 ? quantity : 0;
  const total = unitPrice == null || !qty ? null : round2(unitPrice * qty);
  const totalNative = unitPriceNative == null || !qty ? null : round2(unitPriceNative * qty);

  const cashBefore = input.cashBalance;
  const heldBefore = input.heldQuantity > 0 ? input.heldQuantity : 0;

  const cashShortfall =
    side === 'BUY' && total != null && cashBefore != null && total > cashBefore ? round2(total - cashBefore) : 0;
  const holdingShortfall = side === 'SELL' && qty > heldBefore ? qty - heldBefore : 0;
  // The executor rejects the whole order on a shortfall, so nothing would change:
  // no before/after movement and no realised P&L is shown for it.
  const rejectedIfFilledNow = cashShortfall > 0 || holdingShortfall > 0;

  const cashAfter =
    cashBefore == null || total == null || rejectedIfFilledNow
      ? null
      : round2(side === 'BUY' ? cashBefore - total : cashBefore + total);
  const heldAfter = rejectedIfFilledNow ? heldBefore : side === 'BUY' ? heldBefore + qty : heldBefore - qty;

  const realisedPnl =
    side === 'SELL' && !rejectedIfFilledNow && unitPrice != null && input.averageCost != null && input.averageCost > 0 && qty
      ? round2((unitPrice - input.averageCost) * qty)
      : null;

  return {
    symbol: input.symbol,
    side,
    orderType,
    quantity: qty,
    quoteCurrency,
    foreign,
    fxRate,
    marketPriceNative,
    marketPrice,
    unitPrice,
    unitPriceNative,
    total,
    totalNative,
    cashBefore,
    cashAfter,
    heldBefore,
    heldAfter,
    realisedPnl,
    cashShortfall,
    holdingShortfall,
  };
}
