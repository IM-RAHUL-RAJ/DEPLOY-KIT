package com.trading.tradeapi.strategy.dto;

/**
 * Where a strategy's rule stands right now, on the stored closes and latest quote.
 * {@code ready} is false while there is not enough history; {@code closesAvailable}
 * against {@code closesNeeded} says how far off it is.
 */
public record StrategyPreview(
        long strategyId,
        boolean ready,
        String state,
        Double price,
        Double shortAverage,
        Double longAverage,
        Double middleBand,
        Double lowerBand,
        Double upperBand,
        int closesAvailable,
        int closesNeeded,
        String explanation
) {}
