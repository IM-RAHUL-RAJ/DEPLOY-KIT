package com.trading.tradeapi.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * One quote from the {@code market-data} topic, decoded from the envelope
 * {@code contracts/kafka-topics.md} fixes: {@code eventId}, {@code eventType: QUOTE},
 * {@code eventTime}, and the quote under {@code payload}.
 *
 * <p>Shared by every consumer of the topic in this service so they agree on what a
 * quote is. Decoding is tolerant: anything unreadable yields empty, and the caller
 * acknowledges and moves on rather than blocking a partition on one bad message.
 */
public record QuoteEvent(String eventId, String symbol, BigDecimal price, String currency,
                         boolean stale, Instant observedAt) {

    public static Optional<QuoteEvent> decode(ObjectMapper mapper, String message) {
        try {
            JsonNode root = mapper.readTree(message);
            if (root != null && root.isTextual()) {
                root = mapper.readTree(root.asText()); // a double-encoded producer
            }
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            if (!"QUOTE".equals(root.path("eventType").asText(null))) {
                return Optional.empty();
            }
            JsonNode p = root.path("payload");
            String symbol = p.path("symbol").asText(null);
            if (symbol == null || symbol.isBlank() || !p.path("price").isNumber()) {
                return Optional.empty();
            }
            Instant observed = instant(p.path("quoteAsOf").asText(null));
            if (observed == null) {
                observed = instant(root.path("eventTime").asText(null));
            }
            return Optional.of(new QuoteEvent(
                    root.path("eventId").asText(null),
                    symbol.toUpperCase(),
                    p.path("price").decimalValue(),
                    p.path("currency").asText(null),
                    p.path("stale").asBoolean(false),
                    observed != null ? observed : Instant.now()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
