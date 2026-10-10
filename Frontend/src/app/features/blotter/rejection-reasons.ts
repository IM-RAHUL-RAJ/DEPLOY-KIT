/**
 * The Trade Executor's rejection reasons, explained for a trader.
 *
 * Funds and holdings are checked by the executor at the price an order actually
 * fills at, not when it is placed, so an order the trader cannot afford is still
 * accepted as NEW and then moves to REJECTED with one of these reasons recorded
 * on it. The blotter shows the short label in the row and the full explanation in
 * a pop-up when the row is opened.
 *
 * The executor may append detail after the code ("PRICE_NOT_AVAILABLE - feed
 * down"), so a reason is matched on its leading code.
 */
export interface RejectionExplanation {
  /** The executor's code, e.g. INSUFFICIENT_FUNDS. */
  code: string;
  /** A few words for the table row. */
  label: string;
  /** What happened, in a sentence. */
  detail: string;
  /** What the trader can do about it. */
  nextStep: string;
}

const REASONS: Record<string, Omit<RejectionExplanation, 'code'>> = {
  INSUFFICIENT_FUNDS: {
    label: 'Not enough cash at the fill price',
    detail:
      'When the executor came to fill this buy, the cost at the market price was more than the cash in your account.',
    nextStep: 'Reduce the quantity, or ask an administrator to add funds, then place the order again.',
  },
  INSUFFICIENT_HOLDINGS: {
    label: 'Not enough of the holding to sell',
    detail: 'When the executor came to fill this sell, you held fewer units of this instrument than the order asked to sell.',
    nextStep: 'Check your Holdings page and sell at most the quantity you hold.',
  },
  ACCOUNT_NOT_ACTIVE: {
    label: 'Account not active',
    detail: 'Your account was not active when the order was due to fill, so no trade could be made against it.',
    nextStep: 'Contact an administrator to have the account reviewed.',
  },
  PRICE_NOT_AVAILABLE: {
    label: 'No usable market price',
    detail: 'The market data service had no price for this instrument when the order was due to fill.',
    nextStep: 'Try again shortly, or place a limit order, which waits for a price.',
  },
  MARKET_CLOSED: {
    label: 'Not filled before the market closed',
    detail: 'This limit order was still working at the end-of-day close, and working orders do not carry over.',
    nextStep: 'Place the order again in the next session, or set a limit price nearer the market.',
  },
  INSTRUMENT_NOT_FOUND: {
    label: 'Instrument not tradable',
    detail: 'The instrument could not be traded when the order was due to fill.',
    nextStep: 'Check the symbol, or choose another instrument.',
  },
  INVALID_SIDE: {
    label: 'Order direction not recognised',
    detail: 'The order was neither a buy nor a sell, so the executor could not act on it.',
    nextStep: 'Place the order again from the order ticket.',
  },
};

const UNKNOWN: Omit<RejectionExplanation, 'code'> = {
  label: 'Rejected by the executor',
  detail: 'The executor refused this order for a reason this screen does not recognise.',
  nextStep: 'If it happens again, contact support and quote the order ID.',
};

/** Every code with a written explanation, for completeness checks. */
export const KNOWN_REJECTION_CODES = Object.keys(REASONS);

export function explainRejection(reason: string | null | undefined): RejectionExplanation {
  const raw = (reason ?? '').trim();
  const code = raw.split(/[\s-]/)[0].toUpperCase();
  const known = REASONS[code];
  return known ? { code, ...known } : { code: code || 'UNKNOWN', ...UNKNOWN };
}
