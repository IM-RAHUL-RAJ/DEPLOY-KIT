package com.trading.tradeapi.strategy.service;

import com.trading.tradeapi.marketdata.Indicators;
import com.trading.tradeapi.strategy.entity.StrategyRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The two rules as pure functions, so the decision is testable without a broker,
 * a database or a clock.
 *
 * <p>A rule is evaluated over the daily closes with the live price appended as the
 * latest point, and reduced to a <em>state</em>:
 * <ul>
 *   <li><b>MA_CROSSOVER</b>: {@code ABOVE} when the short average is above the long
 *       one, else {@code BELOW}.</li>
 *   <li><b>BOLLINGER</b>: {@code BELOW} under the lower band, {@code ABOVE} over the
 *       upper band, else {@code INSIDE}.</li>
 * </ul>
 * A trigger is a <em>change</em> of state, never a state: a short average that has sat
 * above the long one for a month is not a fresh crossover. With no previous state
 * (a new or just-resumed strategy) nothing triggers; the first quote only records
 * where things stand.
 */
public final class StrategyRules {

    public static final String PRICE_TARGET = "PRICE_TARGET";
    public static final String MA_CROSSOVER = "MA_CROSSOVER";
    public static final String BOLLINGER = "BOLLINGER";

    public static final String ABOVE = "ABOVE";
    public static final String BELOW = "BELOW";
    public static final String INSIDE = "INSIDE";
    /** Price target: the price has reached the target. */
    public static final String HIT = "HIT";
    /** Price target: not there yet. */
    public static final String WAITING = "WAITING";

    public static final String BUY = "BUY";
    public static final String SELL = "SELL";

    private StrategyRules() {}

    /** What the rule reads on these closes and this price; empty when the history is too short. */
    public static Optional<Reading> read(StrategyRecord s, List<Double> closes, double price) {
        if (PRICE_TARGET.equals(s.getStrategyType())) {
            if (s.getTargetPrice() == null) {
                return Optional.empty();
            }
            double target = s.getTargetPrice().doubleValue();
            boolean hit = BUY.equals(s.getSide()) ? price <= target : price >= target;
            return Optional.of(new Reading(hit ? HIT : WAITING, price, target, null, null, null));
        }
        List<Double> series = new ArrayList<>(closes == null ? List.of() : closes);
        series.add(price);
        if (MA_CROSSOVER.equals(s.getStrategyType())) {
            OptionalDouble shortMa = Indicators.sma(series, s.getShortWindow());
            OptionalDouble longMa = Indicators.sma(series, s.getLongWindow());
            if (shortMa.isEmpty() || longMa.isEmpty()) {
                return Optional.empty();
            }
            double shortValue = shortMa.getAsDouble();
            double longValue = longMa.getAsDouble();
            String state = shortValue > longValue ? ABOVE : BELOW;
            return Optional.of(new Reading(state, price, shortValue, longValue, null, null));
        }
        if (BOLLINGER.equals(s.getStrategyType())) {
            return Indicators.bollinger(series, s.getBandPeriod(), s.getBandK().doubleValue())
                    .map(b -> new Reading(
                            price < b.lower() ? BELOW : price > b.upper() ? ABOVE : INSIDE,
                            price, b.middle(), null, b.lower(), b.upper()));
        }
        return Optional.empty();
    }

    /** The minimum number of daily closes the rule needs before it can read anything. */
    public static int closesNeeded(StrategyRecord s) {
        if (PRICE_TARGET.equals(s.getStrategyType())) {
            return 0; // a target needs only the live price
        }
        int window = MA_CROSSOVER.equals(s.getStrategyType()) ? s.getLongWindow() : s.getBandPeriod();
        return Math.max(0, window - 1); // the live price is the last point
    }

    /**
     * The order a strategy calls for on this reading. A price target trades as soon as the
     * price is at or through the target, the first reading included: "buy when it reaches
     * 180" with the price already at 175 buys now, which is what was asked for. The
     * indicator rules trade only on a change of state ({@link #trigger}).
     */
    public static Optional<String> triggerFor(StrategyRecord s, String previous, String current) {
        if (PRICE_TARGET.equals(s.getStrategyType())) {
            return HIT.equals(current) ? Optional.ofNullable(s.getSide()) : Optional.empty();
        }
        return trigger(s.getStrategyType(), previous, current);
    }

    /**
     * The order a change of state calls for, if any.
     * <ul>
     *   <li>Crossover: short crossing above long is BUY (golden cross); crossing below is SELL.</li>
     *   <li>Bollinger, mean reversion: breaking below the lower band is BUY; breaking above
     *       the upper band is SELL. Staying outside a band does not trade again.</li>
     * </ul>
     */
    public static Optional<String> trigger(String strategyType, String previous, String current) {
        if (previous == null || current == null || previous.equals(current)) {
            return Optional.empty();
        }
        if (MA_CROSSOVER.equals(strategyType)) {
            if (BELOW.equals(previous) && ABOVE.equals(current)) {
                return Optional.of(BUY);
            }
            if (ABOVE.equals(previous) && BELOW.equals(current)) {
                return Optional.of(SELL);
            }
            return Optional.empty();
        }
        if (BOLLINGER.equals(strategyType)) {
            if (BELOW.equals(current)) {
                return Optional.of(BUY);
            }
            if (ABOVE.equals(current)) {
                return Optional.of(SELL);
            }
        }
        return Optional.empty();
    }

    /**
     * One evaluation. For a crossover {@code primary}/{@code secondary} are the short and
     * long averages; for bands {@code primary} is the middle band and lower/upper are set.
     */
    public record Reading(String state, double price, Double primary, Double secondary,
                          Double lower, Double upper) {}
}
