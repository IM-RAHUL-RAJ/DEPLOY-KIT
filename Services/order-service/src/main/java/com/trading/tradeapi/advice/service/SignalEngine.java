package com.trading.tradeapi.advice.service;

import com.trading.tradeapi.advice.dto.Methodology;
import com.trading.tradeapi.advice.dto.TradeSignal;
import com.trading.tradeapi.marketdata.Indicators;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * The one methodology this module uses, as a pure function: five standard technical
 * factors from daily closes, each scored +1 (bullish), 0 or -1 (bearish) and each
 * explained with its numbers.
 *
 * <ol>
 *   <li><b>Trend</b>: SMA(50) against SMA(200) when a year of history exists (the
 *       golden/death-cross regime), otherwise SMA(20) against SMA(50).</li>
 *   <li><b>Position</b>: the latest price against SMA(20).</li>
 *   <li><b>Momentum, RSI(14)</b> (Wilder): under 30 oversold +1, over 70 overbought -1.</li>
 *   <li><b>MACD(12, 26, 9)</b>: histogram (MACD line minus signal line) above zero +1,
 *       below zero -1.</li>
 *   <li><b>Bollinger %B(20, 2)</b>: price under the lower band +1 (stretched down),
 *       over the upper band -1 (stretched up), inside 0.</li>
 * </ol>
 * The score is the sum, -5 to +5: +2 or more is BUY, -2 or less is SELL, anything between
 * is HOLD. Strength is |score| / 5 for a BUY or SELL, and how close the score sits to zero
 * for a HOLD. The 52-week range and annualised volatility are reported as context, not
 * scored. Fewer than 50 daily closes cannot support SMA(50), so there is no signal.
 *
 * <p>Indicators are computed on daily closes (end of day). The live price, when there is
 * one, is what the position and %B factors compare against the averages and bands.
 */
public final class SignalEngine {

    public static final int MIN_CLOSES = 50;
    static final int FACTOR_COUNT = 5;

    public static final String METHODOLOGY =
            "Five factors from daily closes, each scored +1, 0 or -1: trend (SMA 50 vs SMA 200, or SMA 20 vs "
                    + "SMA 50 with under a year of history), position (price vs SMA 20), RSI(14), MACD(12,26,9) "
                    + "histogram and Bollinger %B(20,2). A total of +2 or more is BUY, -2 or less is SELL, "
                    + "otherwise HOLD.";

    private SignalEngine() {}

    /** The formulas behind every number, for the methodology route and the UI. */
    public static Methodology methodology(String disclaimer) {
        return new Methodology(METHODOLOGY, List.of(
                new Methodology.Formula("TREND", "Trend",
                        "SMA(n) = (C1 + C2 + ... + Cn) / n over the last n daily closes",
                        "SMA 50 above SMA 200 (golden cross regime) +1, below -1. Under 200 closes: SMA 20 vs SMA 50."),
                new Methodology.Formula("POSITION", "Price vs 20-day average",
                        "Price - SMA(20)",
                        "Price above its 20-day average +1, below -1."),
                new Methodology.Formula("RSI", "RSI (14)",
                        "RSI = 100 - 100 / (1 + RS), RS = Wilder-smoothed average gain / average loss over 14 days",
                        "Under 30 oversold +1, over 70 overbought -1, otherwise 0."),
                new Methodology.Formula("MACD", "MACD (12, 26, 9)",
                        "MACD = EMA(12) - EMA(26); Signal = EMA(9) of MACD; Histogram = MACD - Signal; "
                                + "EMA = a x Close + (1 - a) x EMA(prev), a = 2 / (n + 1)",
                        "Histogram above zero (momentum building) +1, below zero -1."),
                new Methodology.Formula("BOLLINGER", "Bollinger %B (20, 2)",
                        "Bands = SMA(20) +/- 2 x standard deviation(20); %B = (Price - Lower) / (Upper - Lower)",
                        "%B under 0 (below the lower band) +1, over 1 (above the upper band) -1, otherwise 0."),
                new Methodology.Formula("CONTEXT", "Context (not scored)",
                        "Volatility = stdev(ln(Ct / Ct-1)) x sqrt(252); 52-week range = min and max close",
                        "Shown to size the risk, never added to the score.")),
                "Score = sum of the five factors, -5 to +5. BUY at +2 or more, SELL at -2 or less, otherwise HOLD. "
                        + "Strength = |score| / 5 for BUY or SELL.",
                disclaimer);
    }

    /**
     * @param closes    daily closes, oldest first
     * @param livePrice the latest quote, or null to use the last close
     * @param holding   the customer's position, or null when they do not hold it
     */
    public static TradeSignal evaluate(String symbol, List<Double> closes, Double livePrice,
                                       Instant priceAsOf, TradeSignal.Holding holding, List<String> sources) {
        int n = closes == null ? 0 : closes.size();
        if (n < MIN_CLOSES) {
            String why = fmt("Needs at least %d daily closes to compute the 50-day average; %d available.",
                    MIN_CLOSES, n);
            return new TradeSignal(symbol, TradeSignal.INSUFFICIENT_DATA, null, 0, 0,
                    "No signal: not enough price history for " + symbol + ".",
                    List.of(why), List.of(),
                    new TradeSignal.Indicators(livePrice, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, n),
                    holding, sources, priceAsOf);
        }

        double price = livePrice != null ? livePrice : closes.get(n - 1);
        double sma20 = Indicators.sma(closes, 20).orElseThrow();
        double sma50 = Indicators.sma(closes, 50).orElseThrow();
        OptionalDouble sma200 = Indicators.sma(closes, 200);
        double rsi = Indicators.rsi(closes, 14).orElseThrow();
        Indicators.Macd macd = Indicators.macd(closes, 12, 26, 9).orElseThrow();
        Indicators.Bands bands = Indicators.bollinger(closes, 20, 2).orElseThrow();
        double width = bands.upper() - bands.lower();
        Double percentB = width > 0 ? (price - bands.lower()) / width : null;
        List<Double> lastYear = closes.subList(Math.max(0, n - 252), n);
        double high = lastYear.stream().mapToDouble(Double::doubleValue).max().orElse(price);
        double low = lastYear.stream().mapToDouble(Double::doubleValue).min().orElse(price);
        OptionalDouble volatility = Indicators.annualisedVolatility(closes, 252);

        List<TradeSignal.Factor> factors = new ArrayList<>();

        // 1. Trend
        if (sma200.isPresent()) {
            double s200 = sma200.getAsDouble();
            factors.add(sma50 > s200
                    ? factor("TREND", "Trend", 1, fmt("Long-term uptrend: the 50-day average (%.2f) is above the 200-day average (%.2f).", sma50, s200))
                    : factor("TREND", "Trend", -1, fmt("Long-term downtrend: the 50-day average (%.2f) is below the 200-day average (%.2f).", sma50, s200)));
        } else {
            factors.add(sma20 > sma50
                    ? factor("TREND", "Trend", 1, fmt("Uptrend: the 20-day average (%.2f) is above the 50-day average (%.2f).", sma20, sma50))
                    : factor("TREND", "Trend", -1, fmt("Downtrend: the 20-day average (%.2f) is below the 50-day average (%.2f).", sma20, sma50)));
        }

        // 2. Position
        double vs20 = (price / sma20 - 1) * 100;
        factors.add(price > sma20
                ? factor("POSITION", "Price vs 20-day average", 1, fmt("The price (%.2f) is %.1f%% above its 20-day average (%.2f).", price, vs20, sma20))
                : factor("POSITION", "Price vs 20-day average", -1, fmt("The price (%.2f) is %.1f%% below its 20-day average (%.2f).", price, -vs20, sma20)));

        // 3. RSI
        if (rsi < 30) {
            factors.add(factor("RSI", "RSI (14)", 1, fmt("Oversold: the 14-day RSI is %.1f, under 30.", rsi)));
        } else if (rsi > 70) {
            factors.add(factor("RSI", "RSI (14)", -1, fmt("Overbought: the 14-day RSI is %.1f, over 70.", rsi)));
        } else {
            factors.add(factor("RSI", "RSI (14)", 0, fmt("Momentum is neutral: the 14-day RSI is %.1f, between 30 and 70.", rsi)));
        }

        // 4. MACD
        double hist = macd.histogram();
        if (hist > 0) {
            factors.add(factor("MACD", "MACD (12, 26, 9)", 1, fmt("MACD (%.2f) is above its signal line (%.2f): momentum is building.", macd.line(), macd.signal())));
        } else if (hist < 0) {
            factors.add(factor("MACD", "MACD (12, 26, 9)", -1, fmt("MACD (%.2f) is below its signal line (%.2f): momentum is fading.", macd.line(), macd.signal())));
        } else {
            factors.add(factor("MACD", "MACD (12, 26, 9)", 0, fmt("MACD (%.2f) is on its signal line.", macd.line())));
        }

        // 5. Bollinger %B
        if (percentB != null && percentB < 0) {
            factors.add(factor("BOLLINGER", "Bollinger %B (20, 2)", 1, fmt("The price is below the lower band (%.2f), %%B %.2f: stretched to the downside.", bands.lower(), percentB)));
        } else if (percentB != null && percentB > 1) {
            factors.add(factor("BOLLINGER", "Bollinger %B (20, 2)", -1, fmt("The price is above the upper band (%.2f), %%B %.2f: stretched to the upside.", bands.upper(), percentB)));
        } else {
            factors.add(factor("BOLLINGER", "Bollinger %B (20, 2)", 0, percentB == null
                    ? "The bands are flat: no volatility to measure against."
                    : fmt("The price sits inside the bands (%.2f to %.2f), %%B %.2f.", bands.lower(), bands.upper(), percentB)));
        }

        int score = factors.stream().mapToInt(TradeSignal.Factor::score).sum();
        String action = score >= 2 ? "BUY" : score <= -2 ? "SELL" : "HOLD";
        int strength = "HOLD".equals(action)
                ? Math.round((2 - Math.abs(score)) * 100f / 2)
                : Math.round(Math.abs(score) * 100f / FACTOR_COUNT);

        List<String> reasons = new ArrayList<>(factors.stream().map(TradeSignal.Factor::reason).toList());
        reasons.add(fmt("Context: 52-week range %.2f to %.2f (price %.0f%% of the way up)%s.",
                low, high, high > low ? (price - low) / (high - low) * 100 : 100,
                volatility.isPresent() ? fmt(", annualised volatility %.1f%%", volatility.getAsDouble()) : ""));

        if (holding != null && holding.quantity() > 0) {
            if (holding.unrealisedPnlPercent() != null) {
                reasons.add(fmt("You hold %d at an average cost of %.2f, %s%.2f%% against the current price.",
                        holding.quantity(), nz(holding.averageCost()),
                        holding.unrealisedPnlPercent() >= 0 ? "+" : "", holding.unrealisedPnlPercent()));
            } else {
                reasons.add(fmt("You hold %d at an average cost of %.2f.", holding.quantity(), nz(holding.averageCost())));
            }
        } else if ("SELL".equals(action)) {
            reasons.add("You do not hold it: a SELL view here means the indicators argue against buying now.");
        }

        long bullish = factors.stream().filter(f -> f.score() > 0).count();
        long bearish = factors.stream().filter(f -> f.score() < 0).count();
        String summary = switch (action) {
            case "BUY" -> fmt("%s: BUY. %d of 5 factors are bullish (score %+d).", symbol, bullish, score);
            case "SELL" -> fmt("%s: SELL. %d of 5 factors are bearish (score %+d).", symbol, bearish, score);
            default -> fmt("%s: HOLD. The factors are mixed (score %+d): no clear edge.", symbol, score);
        };

        return new TradeSignal(symbol, TradeSignal.SIGNAL, action, score, strength, summary, reasons, factors,
                new TradeSignal.Indicators(round(price), round(sma20), round(sma50),
                        sma200.isPresent() ? round(sma200.getAsDouble()) : null,
                        round(rsi), round(macd.line()), round(macd.signal()), round(hist),
                        round(bands.upper()), round(bands.middle()), round(bands.lower()),
                        percentB == null ? null : Math.round(percentB * 1000) / 1000.0,
                        round(high), round(low),
                        volatility.isPresent() ? round(volatility.getAsDouble()) : null, n),
                holding, sources, priceAsOf);
    }

    private static TradeSignal.Factor factor(String key, String name, int score, String reason) {
        return new TradeSignal.Factor(key, name, score, reason);
    }

    private static String fmt(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    private static Double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
