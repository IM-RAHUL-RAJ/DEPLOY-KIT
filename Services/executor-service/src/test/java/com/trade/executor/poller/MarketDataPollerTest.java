package com.trade.executor.poller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.client.FauxnanceClient;
import com.trade.executor.client.FauxnanceClient.BatchResult;
import com.trade.executor.client.FauxnanceClient.CandleOutcome;
import com.trade.executor.client.FauxnanceClient.CandleResult;
import com.trade.executor.domain.CandleSeries;
import com.trade.executor.domain.Quote;
import com.trade.executor.service.CurrencyConverter;
import com.trade.executor.service.OrderResolver;
import com.trade.executor.service.QuoteCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The poller's scheduled work, with every collaborator mocked. Its pure rule is in MarketDataPollerRulesTest. */
@DisplayName("MarketDataPoller")
class MarketDataPollerTest {

    private static final String TRACKED = "SELECT s.symbol FROM (";
    private static final String REQUESTED_QUOTES = "i.reason = 'VIEWED'";
    private static final String REQUESTED_CANDLES = "i.reason = 'CANDLES'";
    private static final String WORKING = "FROM orders o JOIN account a";

    private FauxnanceClient fauxnance;
    private QuoteCache cache;
    private JdbcTemplate jdbc;
    private KafkaTemplate<String, String> kafka;
    private OrderResolver resolver;
    private MarketDataPoller poller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        fauxnance = mock(FauxnanceClient.class);
        cache = new QuoteCache();
        jdbc = mock(JdbcTemplate.class);
        kafka = mock(KafkaTemplate.class);
        resolver = mock(OrderResolver.class);
        poller = new MarketDataPoller(fauxnance, cache, jdbc, kafka, new ObjectMapper(), resolver, new CurrencyConverter());
        ReflectionTestUtils.setField(poller, "marketDataTopic", "market-data");
        ReflectionTestUtils.setField(poller, "onDemandRetrySeconds", 60L);
    }

    private static Quote quote(String symbol, String bid, String ask, boolean stale) {
        return new Quote(symbol, new BigDecimal("100"), new BigDecimal(bid), new BigDecimal(ask), null,
                null, null, null, null, stale, "2026-10-08T10:00:00Z");
    }

    private void symbols(String sqlPart, String... values) {
        when(jdbc.queryForList(contains(sqlPart), eq(String.class), any())).thenReturn(List.of(values));
    }

    private Map<String, Object> working(String type, String side, String limit, String currency) {
        Map<String, Object> row = new HashMap<>(Map.of("order_id", 7L, "account_id", 1L, "ticker", "AAPL",
                "side", side, "quantity", 1L, "order_type", type));
        row.put("price", limit == null ? null : new BigDecimal(limit));
        row.put("account_currency", currency);
        return row;
    }

    @Nested
    @DisplayName("Scheduled poll")
    class Poll {

        @Test
        @DisplayName("Quotes the tracked symbols, stores and publishes each, and records a healthy feed")
        void storesAndPublishes() {
            symbols(TRACKED, "AAPL");
            when(fauxnance.fetchQuoteBatch(List.of("AAPL")))
                    .thenReturn(new BatchResult(List.of(quote("AAPL", "99", "101", false)), false));
            when(jdbc.queryForList(contains(WORKING), eq("AAPL"))).thenReturn(List.of());

            poller.poll();

            assertThat(cache.get("AAPL")).isNotNull();
            verify(jdbc).update(startsWith("INSERT INTO market_quote"), any(Object[].class));
            verify(jdbc).update(startsWith("INSERT INTO instrument_quote"), any(Object[].class));
            verify(kafka).send(eq("market-data"), eq("AAPL"), anyString());
            verify(jdbc).update(contains("last_success_at"));
        }

        @Test
        @DisplayName("Nothing tracked costs no request; a failed batch is recorded; an exception never escapes")
        void quietCases() {
            symbols(TRACKED);
            poller.poll();
            verify(fauxnance, never()).fetchQuoteBatch(any());

            symbols(TRACKED, "AAPL");
            when(fauxnance.fetchQuoteBatch(any())).thenReturn(new BatchResult(List.of(), true));
            poller.poll();
            verify(jdbc).update(contains("last_failure_at"));

            when(jdbc.queryForList(contains(TRACKED), eq(String.class), any())).thenThrow(new IllegalStateException("db"));
            poller.poll();
        }

        @Test
        @DisplayName("Storage and publishing failures are logged, not fatal")
        void storageFailures() {
            when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new IllegalStateException("db"));
            when(jdbc.update(anyString())).thenThrow(new IllegalStateException("db"));
            when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("kafka"));
            when(jdbc.queryForList(contains(WORKING), eq("AAPL"))).thenReturn(List.of());

            assertThat(poller.process(List.of(quote("AAPL", "99", "101", false)))).isZero();
        }
    }

    @Nested
    @DisplayName("Working the book")
    class WorkingTheBook {

        @Test
        @DisplayName("A reachable limit order is priced from a fresh quote and counted when resolved")
        void reachableOrderResolves() {
            when(jdbc.queryForList(contains(WORKING), eq("AAPL")))
                    .thenReturn(List.of(working("LIMIT", "BUY", "102", "USD"), working("MARKET", "SELL", null, null)));
            when(fauxnance.fetchSingleQuote("AAPL")).thenReturn(quote("99", "99", "101", false));
            when(resolver.resolve(any(), anyString(), any()))
                    .thenReturn(OrderResolver.Outcome.FILLED, OrderResolver.Outcome.PENDING);

            assertThat(poller.process(List.of(quote("AAPL", "99", "101", false)))).isEqualTo(1);
            verify(fauxnance, times(2)).fetchSingleQuote("AAPL");
        }

        @Test
        @DisplayName("An unreachable limit costs nothing; a missing fresh quote or a failure leaves it working")
        void leftWorking() {
            when(jdbc.queryForList(contains(WORKING), eq("AAPL"))).thenReturn(List.of(
                    working("LIMIT", "BUY", "90", "USD"),
                    working("MARKET", "BUY", null, "USD"),
                    working("MARKET", "SELL", null, "USD")));
            when(fauxnance.fetchSingleQuote("AAPL")).thenReturn(null).thenThrow(new IllegalStateException("boom"));

            assertThat(poller.process(List.of(quote("AAPL", "99", "101", false)))).isZero();
            verify(fauxnance, times(2)).fetchSingleQuote("AAPL");
            verify(resolver, never()).resolve(any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("On-demand fetches")
    class OnDemand {

        @Test
        @DisplayName("A returned symbol is stored, an unknown one remembered, a failed call backed off")
        void requestedQuotes() {
            symbols(REQUESTED_QUOTES, "AAPL", "ZZZ");
            symbols(REQUESTED_CANDLES);
            when(fauxnance.fetchQuoteBatch(List.of("AAPL", "ZZZ")))
                    .thenReturn(new BatchResult(List.of(quote("AAPL", "99", "101", false)), false));
            when(jdbc.queryForList(contains(WORKING), eq("AAPL"))).thenReturn(List.of());

            poller.fetchRequested();
            verify(jdbc).update(contains("SET not_found_at = now()"), eq("ZZZ"), eq("VIEWED"));

            // The whole call fails: ZZZ is backed off, so the next tick does not ask again.
            symbols(REQUESTED_QUOTES, "ZZZ");
            when(fauxnance.fetchQuoteBatch(List.of("ZZZ"))).thenReturn(new BatchResult(List.of(), true));
            poller.fetchRequested();
            poller.fetchRequested();
            verify(fauxnance, times(1)).fetchQuoteBatch(List.of("ZZZ"));
        }

        @Test
        @DisplayName("Candles are stored when found, remembered when unknown, and retried later when unavailable")
        void requestedCandles() {
            symbols(REQUESTED_QUOTES);
            symbols(REQUESTED_CANDLES, "AAPL", "ZZZ", "NEW");
            when(fauxnance.fetchCandles("AAPL"))
                    .thenReturn(new CandleResult(CandleOutcome.FOUND, new CandleSeries("AAPL", "USD", "[]")));
            when(fauxnance.fetchCandles("ZZZ")).thenReturn(new CandleResult(CandleOutcome.NOT_FOUND, null));
            when(fauxnance.fetchCandles("NEW")).thenReturn(new CandleResult(CandleOutcome.UNAVAILABLE, null));

            poller.fetchRequested();
            poller.fetchRequested();

            verify(jdbc, times(2)).update(startsWith("INSERT INTO market_candles"), eq("AAPL"), eq("USD"), eq("[]"));
            verify(fauxnance, times(1)).fetchCandles("NEW");
            verify(jdbc, times(2)).update(contains("SET not_found_at = now()"), eq("ZZZ"), eq("CANDLES"));
        }

        @Test
        @DisplayName("A failure storing candles or reading the requests is not fatal")
        void failures() {
            symbols(REQUESTED_QUOTES);
            symbols(REQUESTED_CANDLES, "AAPL");
            when(fauxnance.fetchCandles("AAPL"))
                    .thenReturn(new CandleResult(CandleOutcome.FOUND, new CandleSeries("AAPL", null, "[]")));
            when(jdbc.update(startsWith("INSERT INTO market_candles"), any(), any(), any()))
                    .thenThrow(new IllegalStateException("db"));
            poller.fetchRequested();

            when(jdbc.queryForList(contains(REQUESTED_QUOTES), eq(String.class), any())).thenThrow(new IllegalStateException("db"));
            poller.fetchRequested();
        }
    }

    @Nested
    @DisplayName("Quota")
    class Quota {

        @Test
        @DisplayName("Today's usage is stored; no answer or a failure stores nothing")
        void usage() {
            when(fauxnance.fetchUsage()).thenReturn(new FauxnanceClient.Usage(2000, 12), null);
            poller.checkUsage();
            poller.checkUsage();
            verify(jdbc, times(1)).update(contains("daily_quota"), eq(2000), eq(12));

            when(fauxnance.fetchUsage()).thenThrow(new IllegalStateException("x"));
            poller.checkUsage();
        }
    }
}
