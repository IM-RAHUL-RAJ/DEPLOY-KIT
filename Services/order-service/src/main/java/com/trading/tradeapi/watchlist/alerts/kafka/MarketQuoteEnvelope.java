package com.trading.tradeapi.watchlist.alerts.kafka;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The business fields the alerts evaluator needs out of the flat market-data message the executor
 * publishes (design/kafka.md): symbol, last price, involvement timestamp. Everything else in the
 * payload — bid, ask, currency, change, stale — is the rest of the platform's, not the evaluator's.
 */
public record MarketQuoteEnvelope(String symbol, BigDecimal price, Instant occurringAt) {
}