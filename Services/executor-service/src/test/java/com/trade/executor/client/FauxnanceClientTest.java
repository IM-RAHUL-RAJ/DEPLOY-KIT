package com.trade.executor.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.client.FauxnanceClient.CandleOutcome;
import com.trade.executor.domain.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("FauxnanceClient")
class FauxnanceClientTest {

    private RestTemplate rest;
    private FauxnanceClient client;

    @BeforeEach
    void setUp() {
        rest = mock(RestTemplate.class);
        client = new FauxnanceClient(rest, new ObjectMapper());
        ReflectionTestUtils.setField(client, "baseUrl", "https://fauxnance.test/v1");
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
    }

    private void answer(String urlPart, String body) {
        when(rest.exchange(contains(urlPart), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    @Test
    @DisplayName("Refuses to start without an API key")
    void needsAKey() {
        client.verifyApiKeyConfigured();
        ReflectionTestUtils.setField(client, "apiKey", " ");
        assertThatThrownBy(client::verifyApiKeyConfigured).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(client, "apiKey", null);
        assertThatThrownBy(client::verifyApiKeyConfigured).isInstanceOf(IllegalStateException.class);
    }

    @Nested
    @DisplayName("Single quote")
    class SingleQuote {

        @Test
        @DisplayName("Reads the quote and its staleness; unparseable numbers become null")
        void parses() {
            answer("/quotes/AAPL", """
                    {"data":{"symbol":"AAPL","price":"101.5","bid":101,"ask":102,"currency":"USD",
                             "change":"oops","marketState":"open","asOf":"2026-10-08T10:00:00Z"},
                     "meta":{"stale":true}}""");

            Quote q = client.fetchSingleQuote("aapl");
            assertThat(q.price()).isEqualByComparingTo("101.5");
            assertThat(q.change()).isNull();
            assertThat(q.previousClose()).isNull();
            assertThat(q.stale()).isTrue();
        }

        @Test
        @DisplayName("No symbol, no data, an empty body or an error all answer null")
        void nothing() {
            assertThat(client.fetchSingleQuote(null)).isNull();
            assertThat(client.fetchSingleQuote(" ")).isNull();
            answer("/quotes/A", "{\"data\":null}");
            assertThat(client.fetchSingleQuote("A")).isNull();
            answer("/quotes/B", "");
            assertThat(client.fetchSingleQuote("B")).isNull();
            when(rest.exchange(contains("/quotes/C"), any(), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new ResourceAccessException("timeout"));
            assertThat(client.fetchSingleQuote("C")).isNull();
        }
    }

    @Nested
    @DisplayName("Batch quotes")
    class Batch {

        @Test
        @DisplayName("Keeps good items and skips per-symbol errors and items without a price")
        void parsesItems() {
            answer("symbols=", """
                    {"data":{"quotes":[
                      {"symbol":"AAPL","stale":false,"quote":{"symbol":"AAPL","price":100,"bid":99,"ask":101}},
                      {"symbol":"ZZZ","error":{"message":"unknown symbol"}},
                      {"symbol":"NOP","quote":{"symbol":"NOP"}}
                    ]}}""");

            assertThat(client.fetchQuotes(List.of("AAPL", "ZZZ", "NOP"))).extracting(Quote::symbol).containsExactly("AAPL");
        }

        @Test
        @DisplayName("Asks for 25 symbols per request and reports a failed request")
        void batchesOf25() {
            List<String> symbols = IntStream.range(0, 30).mapToObj(i -> "S" + i).toList();
            when(rest.exchange(contains("symbols="), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(ResponseEntity.ok(""))
                    .thenThrow(new ResourceAccessException("quota"));

            FauxnanceClient.BatchResult result = client.fetchQuoteBatch(symbols);
            assertThat(result.failed()).isTrue();
            assertThat(result.quotes()).isEmpty();
            verify(rest, times(2)).exchange(contains("symbols="), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        }

        @Test
        @DisplayName("No symbols costs no request")
        void empty() {
            assertThat(client.fetchQuoteBatch(List.of()).failed()).isFalse();
            assertThat(client.fetchQuoteBatch(null).quotes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Usage")
    class Usage {

        @Test
        @DisplayName("Reads today's quota; a malformed answer or an error is null")
        void reads() {
            answer("/usage", "{\"data\":{\"dailyQuota\":2000,\"usedToday\":2100}}");
            FauxnanceClient.Usage usage = client.fetchUsage();
            assertThat(usage.dailyQuota()).isEqualTo(2000);
            assertThat(usage.remaining()).isZero();
            assertThat(new FauxnanceClient.Usage(10, 4).remaining()).isEqualTo(6);

            answer("/usage", "{\"data\":{}}");
            assertThat(client.fetchUsage()).isNull();
            answer("/usage", "not json");
            assertThat(client.fetchUsage()).isNull();
        }
    }

    @Nested
    @DisplayName("Candles")
    class Candles {

        @Test
        @DisplayName("Found: the candles are kept as JSON with their currency")
        void found() {
            answer("/candles/AAPL", "{\"data\":{\"currency\":\"USD\",\"candles\":[{\"close\":1}]}}");
            FauxnanceClient.CandleResult result = client.fetchCandles("aapl");
            assertThat(result.outcome()).isEqualTo(CandleOutcome.FOUND);
            assertThat(result.series().candlesJson()).isEqualTo("[{\"close\":1}]");
            assertThat(result.series().currency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("A backfill (202), no array or an error is unavailable; 404 or no symbol is not found")
        void notFoundOrUnavailable() {
            when(rest.exchange(contains("/candles/NEW"), any(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).body(""));
            assertThat(client.fetchCandles("NEW").outcome()).isEqualTo(CandleOutcome.UNAVAILABLE);

            answer("/candles/ODD", "{\"data\":{\"candles\":{}}}");
            assertThat(client.fetchCandles("ODD").outcome()).isEqualTo(CandleOutcome.UNAVAILABLE);

            when(rest.exchange(contains("/candles/ZZZ"), any(), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nf", null, null, null));
            assertThat(client.fetchCandles("ZZZ").outcome()).isEqualTo(CandleOutcome.NOT_FOUND);

            when(rest.exchange(contains("/candles/ERR"), any(), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new ResourceAccessException("down"));
            assertThat(client.fetchCandles("ERR").outcome()).isEqualTo(CandleOutcome.UNAVAILABLE);

            assertThat(client.fetchCandles(null).outcome()).isEqualTo(CandleOutcome.NOT_FOUND);
            assertThat(client.fetchCandles("").outcome()).isEqualTo(CandleOutcome.NOT_FOUND);
        }
    }
}
