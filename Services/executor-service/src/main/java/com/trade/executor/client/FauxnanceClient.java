package com.trade.executor.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.domain.CandleSeries;
import com.trade.executor.domain.Quote;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client for the Fauxnance market data API.
 * Supports:
 *   1. fetchBatchQuotes: GET /quotes?symbols=A,B,C (up to 25 symbols = 1 quota unit)
 *   2. fetchSingleQuote: GET /quotes/{symbol} (single symbol quote check during order execution)
 *   3. fetchCandles: GET /candles/{symbol} (one year of daily candles, for the charts)
 * Auth header: X-Api-Key
 */
@Component
public class FauxnanceClient {

    private static final Logger log = LoggerFactory.getLogger(FauxnanceClient.class);
    private static final int MAX_SYMBOLS_PER_REQUEST = 25;

    @Value("${fauxnance.base-url}")
    private String baseUrl;

    @Value("${fauxnance.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public FauxnanceClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void verifyApiKeyConfigured() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Fauxnance API key is required. Set the FAUXNANCE_API_KEY environment variable "
                            + "(see sprint9/.env.example) before starting the executor service.");
        }
    }

    /**
     * Single quote fetch: GET /quotes/{symbol}
     * Used by Trade Executor during execution of an order.
     */
    public Quote fetchSingleQuote(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/quotes/" + symbol.toUpperCase())
                    .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", apiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            return parseSingleResponse(response.getBody(), symbol);
        } catch (Exception e) {
            log.error("Fauxnance single quote API error for symbol {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /** Quotes from a batched fetch, and whether any of its calls failed outright. */
    public record BatchResult(List<Quote> quotes, boolean failed) {}

    /**
     * Batch quote fetch: GET /quotes?symbols=A,B,C, one request per 25 symbols.
     * Used by MarketDataPoller for every quote that is not an execution.
     */
    public List<Quote> fetchQuotes(List<String> symbols) {
        return fetchQuoteBatch(symbols).quotes();
    }

    /**
     * As {@link #fetchQuotes}, also reporting whether a request failed (network,
     * quota, upstream error). A symbol missing from a successful answer is one
     * Fauxnance does not know; one missing after a failure might simply be late.
     */
    public BatchResult fetchQuoteBatch(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return new BatchResult(Collections.emptyList(), false);
        }

        List<Quote> results = new ArrayList<>();
        boolean failed = false;

        for (int i = 0; i < symbols.size(); i += MAX_SYMBOLS_PER_REQUEST) {
            List<String> batch = symbols.subList(i, Math.min(i + MAX_SYMBOLS_PER_REQUEST, symbols.size()));
            try {
                String symbolsParam = String.join(",", batch);
                String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                        .path("/quotes")
                        .queryParam("symbols", symbolsParam)
                        .toUriString();

                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Api-Key", apiKey);
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, entity, String.class);

                results.addAll(parseBatchResponse(response.getBody()));
            } catch (Exception e) {
                failed = true;
                log.error("Fauxnance batch API error starting at index {}: {}", i, e.getMessage());
            }
        }

        return new BatchResult(results, failed);
    }

    /** The key's quota for today, from GET /usage. */
    public record Usage(int dailyQuota, int usedToday) {
        public int remaining() {
            return Math.max(dailyQuota - usedToday, 0);
        }
    }

    /**
     * The key's quota status: GET /usage. This request itself costs one quota unit,
     * so the poller calls it at most hourly and stores the answer for everyone else.
     */
    public Usage fetchUsage() {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl).path("/usage").toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", apiKey);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode data = objectMapper.readTree(response.getBody()).path("data");
            if (!data.path("dailyQuota").isNumber() || !data.path("usedToday").isNumber()) {
                return null;
            }
            return new Usage(data.path("dailyQuota").asInt(), data.path("usedToday").asInt());
        } catch (Exception e) {
            log.warn("Fauxnance usage check failed: {}", e.getMessage());
            return null;
        }
    }

    /** What a candle request came back with. */
    public enum CandleOutcome { FOUND, NOT_FOUND, UNAVAILABLE }

    /** The candles, or why there are none. {@code series} is set only when FOUND. */
    public record CandleResult(CandleOutcome outcome, CandleSeries series) {}

    /**
     * One year of daily candles: GET /candles/{symbol}. One request per symbol, made
     * at most once a day per symbol by the poller, because daily candles change only
     * once a day.
     */
    public CandleResult fetchCandles(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return new CandleResult(CandleOutcome.NOT_FOUND, null);
        }
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/candles/" + symbol.toUpperCase())
                    .toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", apiKey);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getStatusCode().value() == 202) {
                // Fauxnance is backfilling this symbol; ask again on a later tick.
                return new CandleResult(CandleOutcome.UNAVAILABLE, null);
            }
            JsonNode data = objectMapper.readTree(response.getBody()).path("data");
            JsonNode candles = data.path("candles");
            if (!candles.isArray()) {
                return new CandleResult(CandleOutcome.UNAVAILABLE, null);
            }
            String currency = data.path("currency").isTextual() ? data.path("currency").asText() : null;
            return new CandleResult(CandleOutcome.FOUND,
                    new CandleSeries(symbol.toUpperCase(), currency, objectMapper.writeValueAsString(candles)));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return new CandleResult(CandleOutcome.NOT_FOUND, null);
        } catch (Exception e) {
            log.error("Fauxnance candles API error for symbol {}: {}", symbol, e.getMessage());
            return new CandleResult(CandleOutcome.UNAVAILABLE, null);
        }
    }

    private Quote parseSingleResponse(String json, String requestedSymbol) throws Exception {
        if (json == null || json.isBlank()) return null;

        JsonNode root = objectMapper.readTree(json);
        JsonNode data = root.path("data");
        JsonNode meta = root.path("meta");

        if (data.isMissingNode() || data.isNull()) return null;

        boolean stale = meta.path("stale").asBoolean(false);

        return new Quote(
                data.path("symbol").asText(requestedSymbol),
                parseBigDecimal(data, "price"),
                parseBigDecimal(data, "bid"),
                parseBigDecimal(data, "ask"),
                data.path("currency").asText(null),
                parseBigDecimal(data, "change"),
                parseBigDecimal(data, "changePercent"),
                parseBigDecimal(data, "previousClose"),
                data.path("marketState").asText(null),
                stale,
                data.path("asOf").asText(null)
        );
    }

    private List<Quote> parseBatchResponse(String json) throws Exception {
        List<Quote> quotes = new ArrayList<>();
        if (json == null || json.isBlank()) return quotes;

        JsonNode root = objectMapper.readTree(json);
        JsonNode quotesArray = root.path("data").path("quotes");

        for (JsonNode item : quotesArray) {
            if (!item.path("error").isMissingNode()) {
                log.warn("Fauxnance batch item error for {}: {}",
                        item.path("symbol").asText(), item.path("error").path("message").asText());
                continue;
            }

            try {
                boolean stale = item.path("stale").asBoolean(false);
                JsonNode q = item.path("quote");

                Quote quote = new Quote(
                        q.path("symbol").asText(null),
                        parseBigDecimal(q, "price"),
                        parseBigDecimal(q, "bid"),
                        parseBigDecimal(q, "ask"),
                        q.path("currency").asText(null),
                        parseBigDecimal(q, "change"),
                        parseBigDecimal(q, "changePercent"),
                        parseBigDecimal(q, "previousClose"),
                        q.path("marketState").asText(null),
                        stale,
                        q.path("asOf").asText(null)
                );

                if (quote.symbol() != null && quote.price() != null) {
                    quotes.add(quote);
                }
            } catch (Exception e) {
                log.warn("Failed to parse batch item: {}", item, e);
            }
        }
        return quotes;
    }

    private BigDecimal parseBigDecimal(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) return null;
        try {
            return new BigDecimal(field.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
