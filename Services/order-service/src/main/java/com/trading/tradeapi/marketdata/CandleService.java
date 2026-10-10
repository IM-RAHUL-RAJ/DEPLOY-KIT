package com.trading.tradeapi.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.exception.MarketDataUnavailableException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Daily candles for the instrument charts, from what the market-data poller stored.
 *
 * <p>Daily candles change once a day, so the poller fetches a symbol's year of
 * history at most once a day and every chart reads that. The response is the shape
 * the chart draws: one point per day with a Unix-seconds timestamp.
 */
@Service
public class CandleService {

    private final MarketDataService marketData;
    private final ObjectMapper objectMapper;

    public CandleService(MarketDataService marketData, ObjectMapper objectMapper) {
        this.marketData = marketData;
        this.objectMapper = objectMapper;
    }

    /** One candle: t is the trading day as Unix seconds (UTC midnight). */
    public record Candle(long t, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c, Long v) {}

    public record CandlesResponse(String symbol, String currency, String interval,
                                  LocalDate asOf, List<Candle> candles) {}

    public CandlesResponse candles(String symbol, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        MarketDataService.CandlesLookup lookup = marketData.candles(symbol);
        MarketCandles stored = lookup.candles();
        if (stored == null) {
            if (lookup.notFound()) {
                throw new InstrumentNotFoundException();
            }
            // Requested from the poller and not fetched yet.
            throw new MarketDataUnavailableException(symbol == null ? "" : symbol.trim().toUpperCase());
        }
        return new CandlesResponse(stored.symbol(), stored.currency(), "1d", stored.fetchedOn(),
                parse(stored.candles(), from, to));
    }

    /**
     * Daily closing prices, oldest first, for indicator maths. Uses {@code adjclose}
     * (restated for splits and dividends) when present, so a split does not read as
     * a crash. Symbols with no candles are absent.
     */
    public java.util.Map<String, List<Double>> dailyCloses(java.util.Collection<String> symbols) {
        return closesOf(marketData.candles(symbols));
    }

    /** As {@link #dailyCloses}, from what is stored now, without waiting for today's fetch. */
    public java.util.Map<String, List<Double>> storedDailyCloses(java.util.Collection<String> symbols) {
        return closesOf(marketData.candles(symbols, 0));
    }

    private java.util.Map<String, List<Double>> closesOf(java.util.Map<String, MarketCandles> candles) {
        java.util.Map<String, List<Double>> closes = new java.util.HashMap<>();
        candles.forEach((symbol, stored) -> {
            List<Double> series = new ArrayList<>();
            try {
                for (JsonNode node : objectMapper.readTree(stored.candles())) {
                    JsonNode value = node.path("adjclose").isNumber() ? node.path("adjclose") : node.path("close");
                    if (value.isNumber()) {
                        series.add(value.asDouble());
                    }
                }
            } catch (Exception e) {
                return;
            }
            closes.put(symbol, series);
        });
        return closes;
    }

    private List<Candle> parse(String json, LocalDate from, LocalDate to) {
        List<Candle> candles = new ArrayList<>();
        try {
            for (JsonNode node : objectMapper.readTree(json)) {
                LocalDate day = LocalDate.parse(node.path("date").asText());
                if ((from != null && day.isBefore(from)) || (to != null && day.isAfter(to))) {
                    continue;
                }
                candles.add(new Candle(
                        day.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                        decimal(node, "open"),
                        decimal(node, "high"),
                        decimal(node, "low"),
                        decimal(node, "close"),
                        node.path("volume").isNumber() ? node.path("volume").asLong() : null));
            }
        } catch (Exception e) {
            throw new MarketDataUnavailableException("candles");
        }
        return candles;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }
}
