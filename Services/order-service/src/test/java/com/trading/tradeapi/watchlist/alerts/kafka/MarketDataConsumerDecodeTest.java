package com.trading.tradeapi.watchlist.alerts.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.watchlist.alerts.service.AlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The market-data decode, contracted envelope and older flat form. Kafka is off in the tests (design/kafka.md); this is the
 * same parser the listener runs.
 */
@DisplayName("Market data payload decode")
class MarketDataConsumerDecodeTest {

    private final AlertService alertService = mock(AlertService.class);
    private final MarketDataConsumer consumer = new MarketDataConsumer(alertService, new ObjectMapper());

    @Test
    @DisplayName("The executor's flat MARKET_DATA_UPDATE yields symbol, price and timestamp")
    void flatUpdateDecodes() {
        MarketQuoteEnvelope quote = consumer.parse("""
                {"eventType":"MARKET_DATA_UPDATE","symbol":"AAPL","price":241.75,"bid":241.70,
                 "ask":241.80,"currency":"USD","change":1.23,"changePercent":0.51,"stale":false,
                 "timestamp":"2026-10-19T14:00:00Z"}
                """);

        assertThat(quote).isNotNull();
        assertThat(quote.symbol()).isEqualTo("AAPL");
        assertThat(quote.price()).isEqualByComparingTo("241.75");
        assertThat(quote.occurringAt()).isEqualTo(Instant.parse("2026-10-19T14:00:00Z"));
    }

    @Test
    @DisplayName("The poller's contracted QUOTE envelope yields the payload's symbol, price and quoteAsOf")
    void quoteEnvelopeDecodes() {
        MarketQuoteEnvelope quote = consumer.parse("""
                {"eventId":"0c7a9d3e-1111-2222-3333-444455556666","eventType":"QUOTE",
                 "eventTime":"2026-10-19T14:00:05Z","source":"executor-service","schemaVersion":1,
                 "payload":{"symbol":"aapl","price":241.75,"bid":241.70,"ask":241.80,"currency":"USD",
                            "stale":false,"quoteAsOf":"2026-10-19T14:00:00Z"}}
                """);

        assertThat(quote).isNotNull();
        assertThat(quote.symbol()).isEqualTo("AAPL");
        assertThat(quote.price()).isEqualByComparingTo("241.75");
        assertThat(quote.occurringAt()).isEqualTo(Instant.parse("2026-10-19T14:00:00Z"));
    }

    @Test
    @DisplayName("A QUOTE envelope without a price in its payload is dropped")
    void quoteEnvelopeWithoutPriceIsDropped() {
        assertThat(consumer.parse("""
                {"eventType":"QUOTE","payload":{"symbol":"AAPL"}}
                """)).isNull();
    }

    @Test
    @DisplayName("A non-quote event in the topic is acknowledged and ignored")
    void otherEventIsIgnored() {
        MarketQuoteEnvelope quote = consumer.parse("""
                {"eventType":"SOMETHING_ELSE","symbol":"AAPL","price":241.75}
                """);

        assertThat(quote).isNull();
    }

    @Test
    @DisplayName("A message without an eventType is still a quote")
    void missingEventTypeStillDecodes() {
        MarketQuoteEnvelope quote = consumer.parse("""
                {"symbol":"AAPL","price":100.00,"timestamp":"2026-10-19T14:00:00Z"}
                """);

        assertThat(quote).isNotNull();
        assertThat(quote.symbol()).isEqualTo("AAPL");
    }

    @Test
    @DisplayName("A quote without a price cannot be evaluated and is dropped")
    void missingPriceIsDropped() {
        assertThat(consumer.parse("""
                {"symbol":"AAPL","bid":100.00,"ask":101.00}
                """)).isNull();
    }

    @Test
    @DisplayName("A symbol without a price field or a missing symbol is dropped")
    void missingSymbolIsDropped() {
        assertThat(consumer.parse("""  
                {"price":100.00}
                """)).isNull();
    }

    @Test
    @DisplayName("An unparseable message is dropped, not thrown")
    void poisonMessageIsDropped() {
        assertThat(consumer.parse("{not json")).isNull();
        assertThat(consumer.parse("[1,2,3]")).isNull();
    }

    @Test
    @DisplayName("A missing timestamp falls back to now, never to a null evaluator key")
    void missingTimestampFallsBackToNow() {
        MarketQuoteEnvelope quote = consumer.parse("""
                {"symbol":"GOOGL","price":170.00}
                """);

        assertThat(quote.occurringAt()).isNotNull();
        assertThat(BigDecimal.valueOf(170.00)).isEqualByComparingTo(quote.price());
    }
}