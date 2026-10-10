package com.trading.tradeapi.marketdata;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Technical indicators over a price series, oldest first. Pure functions: no state,
 * no I/O, so the advice and strategy modules compute exactly the same numbers and
 * every rule built on them is unit-testable.
 *
 * <p>Each answers empty when the series is too short for the window, rather than a
 * number computed from fewer points than the indicator is defined on.
 */
public final class Indicators {

    private Indicators() {}

    /** Simple moving average of the last {@code window} values. */
    public static OptionalDouble sma(List<Double> series, int window) {
        if (window <= 0 || series.size() < window) {
            return OptionalDouble.empty();
        }
        double sum = 0;
        for (int i = series.size() - window; i < series.size(); i++) {
            sum += series.get(i);
        }
        return OptionalDouble.of(sum / window);
    }

    /** Population standard deviation of the last {@code window} values. */
    public static OptionalDouble stdDev(List<Double> series, int window) {
        OptionalDouble mean = sma(series, window);
        if (mean.isEmpty()) {
            return OptionalDouble.empty();
        }
        double m = mean.getAsDouble();
        double sumSq = 0;
        for (int i = series.size() - window; i < series.size(); i++) {
            double d = series.get(i) - m;
            sumSq += d * d;
        }
        return OptionalDouble.of(Math.sqrt(sumSq / window));
    }

    /**
     * Relative strength index over {@code period} changes (Wilder's average). Needs
     * {@code period + 1} points. 100 when there were no losses in the window.
     */
    public static OptionalDouble rsi(List<Double> series, int period) {
        if (period <= 0 || series.size() < period + 1) {
            return OptionalDouble.empty();
        }
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= period; i++) {
            double change = series.get(i) - series.get(i - 1);
            if (change >= 0) gain += change; else loss -= change;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        for (int i = period + 1; i < series.size(); i++) {
            double change = series.get(i) - series.get(i - 1);
            avgGain = (avgGain * (period - 1) + Math.max(change, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-change, 0)) / period;
        }
        if (avgLoss == 0) {
            return OptionalDouble.of(100);
        }
        double rs = avgGain / avgLoss;
        return OptionalDouble.of(100 - 100 / (1 + rs));
    }

    /**
     * Exponential moving average series, aligned to the input: entry {@code i} is the EMA
     * ending at {@code series[i]}, null before the first full window. Seeded with the SMA
     * of the first {@code period} values, then {@code EMA = a * x + (1 - a) * EMA_prev}
     * with {@code a = 2 / (period + 1)}.
     */
    public static List<Double> emaSeries(List<Double> series, int period) {
        List<Double> out = new java.util.ArrayList<>(java.util.Collections.nCopies(series.size(), (Double) null));
        if (period <= 0 || series.size() < period) {
            return out;
        }
        double alpha = 2.0 / (period + 1);
        double ema = 0;
        for (int i = 0; i < period; i++) {
            ema += series.get(i);
        }
        ema /= period;
        out.set(period - 1, ema);
        for (int i = period; i < series.size(); i++) {
            ema = alpha * series.get(i) + (1 - alpha) * ema;
            out.set(i, ema);
        }
        return out;
    }

    /** MACD at the latest point: line = EMA(fast) - EMA(slow), signal = EMA(signal) of the line. */
    public record Macd(double line, double signal, double histogram) {}

    public static java.util.Optional<Macd> macd(List<Double> series, int fast, int slow, int signalPeriod) {
        if (series.size() < slow + signalPeriod - 1) {
            return java.util.Optional.empty();
        }
        List<Double> fastEma = emaSeries(series, fast);
        List<Double> slowEma = emaSeries(series, slow);
        List<Double> line = new java.util.ArrayList<>();
        for (int i = slow - 1; i < series.size(); i++) {
            line.add(fastEma.get(i) - slowEma.get(i));
        }
        List<Double> signal = emaSeries(line, signalPeriod);
        double l = line.get(line.size() - 1);
        double s = signal.get(signal.size() - 1);
        return java.util.Optional.of(new Macd(l, s, l - s));
    }

    /**
     * Annualised volatility in percent: the sample standard deviation of the daily log
     * returns over the last {@code window} days, times the square root of 252 trading days.
     */
    public static OptionalDouble annualisedVolatility(List<Double> series, int window) {
        int n = Math.min(window, series.size() - 1);
        if (n < 2) {
            return OptionalDouble.empty();
        }
        double[] r = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) {
            int j = series.size() - n + i;
            r[i] = Math.log(series.get(j) / series.get(j - 1));
            mean += r[i];
        }
        mean /= n;
        double ss = 0;
        for (double x : r) {
            ss += (x - mean) * (x - mean);
        }
        return OptionalDouble.of(Math.sqrt(ss / (n - 1)) * Math.sqrt(252) * 100);
    }

    /** Bollinger bands: middle = SMA(period), upper/lower = middle ± k standard deviations. */
    public record Bands(double lower, double middle, double upper) {}

    public static java.util.Optional<Bands> bollinger(List<Double> series, int period, double k) {
        OptionalDouble mid = sma(series, period);
        OptionalDouble sd = stdDev(series, period);
        if (mid.isEmpty() || sd.isEmpty()) {
            return java.util.Optional.empty();
        }
        double m = mid.getAsDouble();
        double s = sd.getAsDouble();
        return java.util.Optional.of(new Bands(m - k * s, m, m + k * s));
    }
}
