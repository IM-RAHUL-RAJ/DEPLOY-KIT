package com.trading.tradeapi.watchlist.alerts.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.watchlist.alerts.service.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Consumes the market-data stream in the {@code watchlist-service} consumer group
 * (design/kafka.md) and asks the alerts service whether any armed threshold crossed.
 *
 * <p>The executor's poller publishes the contracted envelope ({@code eventType: QUOTE}, the
 * quote under {@code payload}); the older flat {@code MARKET_DATA_UPDATE} form ({@code symbol,
 * price, timestamp} at the top level) is still read. Anything else in the topic
 * is acknowledged and ignored, as is a message without a symbol or a last price. Like the
 * notifications consumer, this one is gated behind {@code trade-api.kafka.consumers.enabled} and
 * a poison message is logged and acknowledged rather than allowed to stop the partition.
 */
@Component
@ConditionalOnProperty(name = "trade-api.kafka.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class MarketDataConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarketDataConsumer.class);

    private static final String EVENT_TYPE = "MARKET_DATA_UPDATE";

    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    public MarketDataConsumer(AlertService alertService, ObjectMapper objectMapper) {
        this.alertService = alertService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${topics.market-data}", groupId = "watchlist-service")
    public void consume(String message) {
        MarketQuoteEnvelope quote = parse(message);
        if (quote == null) {
            return;
        }
        alertService.evaluate(quote.symbol(), quote.price(), quote.occurringAt());
    }

    /**
     * Tolerant decode: null or the same {@code MarketQuoteEnvelope} when a field the evaluator
     * needs is missing. Package-private so the decode path is unit-testable without a broker.
     */
    MarketQuoteEnvelope parse(String message) {
        JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (JsonProcessingException e) {
            log.error("Unparseable market-data message; acknowledged and ignored: {}", e.getMessage());
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }

        // The contracted envelope the market-data poller publishes (contracts/kafka-topics.md):
        // eventType QUOTE with the quote under payload. Without this branch every real quote
        // was ignored and an alert could only fire from a quote somebody happened to view.
        if ("QUOTE".equals(textOf(root, "eventType"))) {
            return com.trading.tradeapi.marketdata.QuoteEvent.decode(objectMapper, message)
                    .map(q -> new MarketQuoteEnvelope(q.symbol(), q.price(), q.observedAt()))
                    .orElse(null);
        }

        String eventType = textOf(root, "eventType");
        if (eventType != null && !EVENT_TYPE.equals(eventType)) {
            return null;
        }
        String symbol = textOf(root, "symbol");
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        BigDecimal price = decimalOf(root, "price");
        if (price == null) {
            return null;
        }
        Instant occurringAt = instantOf(root);
        return new MarketQuoteEnvelope(symbol.toUpperCase(), price, occurringAt != null ? occurringAt : Instant.now());
    }

    private String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private BigDecimal decimalOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.decimalValue();
    }

    /** Tolerates both the executor's {@code timestamp} and a quoted ISO instant. */
    private Instant instantOf(JsonNode node) {
        JsonNode value = node.get("timestamp");
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Instant.parse(value.asText());
        } catch (RuntimeException e) {
            return null;
        }
    }
}