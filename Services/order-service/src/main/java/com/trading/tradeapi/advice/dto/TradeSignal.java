package com.trading.tradeapi.advice.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

/**
 * A stated view on one instrument: BUY, SELL or HOLD, how strongly, and every reason
 * behind it, with the numbers. When the data cannot support a view, {@code action} is
 * null and {@code status} says why: a signal computed from too little history would
 * be a guess presented as research.
 *
 * @param status       SIGNAL, or INSUFFICIENT_DATA
 * @param action       BUY, SELL or HOLD; null without a signal
 * @param score        the sum of the factor scores, -5 to +5
 * @param strength     0 to 100: how firmly the factors support the view
 * @param summary      one sentence a customer can read
 * @param reasons      each factor that produced the view, in plain language
 * @param factors      the scored factors, one per indicator, with their values
 * @param indicators   the numbers behind the reasons
 * @param holding      the customer's position in it, when they hold it
 * @param sources      why it is on this list: HOLDING, WATCHLIST, or REQUESTED
 */
public record TradeSignal(
        String symbol,
        String status,
        String action,
        int score,
        int strength,
        String summary,
        List<String> reasons,
        List<Factor> factors,
        Indicators indicators,
        Holding holding,
        List<String> sources,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant priceAsOf
) {
    public static final String SIGNAL = "SIGNAL";
    public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

    /**
     * One scored input to the view.
     *
     * @param key    TREND, POSITION, RSI, MACD or BOLLINGER
     * @param name   what a customer reads
     * @param score  +1 bullish, 0 neutral, -1 bearish
     * @param reason the plain-language finding, with its numbers
     */
    public record Factor(String key, String name, int score, String reason) {}

    /** The indicator values, rounded for display. Null where not computable. */
    public record Indicators(
            Double lastPrice,
            Double sma20,
            Double sma50,
            Double sma200,
            Double rsi14,
            Double macd,
            Double macdSignal,
            Double macdHistogram,
            Double bollingerUpper,
            Double bollingerMiddle,
            Double bollingerLower,
            Double percentB,
            Double high52w,
            Double low52w,
            Double volatility,
            int dailyCloses) {
    }

    /** The customer's position, in the account's ledger currency. */
    public record Holding(long quantity, Double averageCost, Double unrealisedPnlPercent) {}
}
