package com.trading.tradeapi.marketdata;

import java.time.Instant;
import java.time.LocalDate;

/** A symbol's daily candles as the poller stored them: the Fauxnance array, as JSON. */
public record MarketCandles(String symbol, String currency, String candles, LocalDate fetchedOn, Instant fetchedAt) {}
