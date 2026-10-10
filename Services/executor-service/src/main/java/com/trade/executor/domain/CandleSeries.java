package com.trade.executor.domain;

/**
 * One symbol's daily candle history as Fauxnance returned it.
 *
 * @param symbol     the symbol asked for
 * @param currency   the currency every price in the series is in, or null
 * @param candlesJson the {@code data.candles} array, oldest first, kept as JSON
 */
public record CandleSeries(String symbol, String currency, String candlesJson) {}
