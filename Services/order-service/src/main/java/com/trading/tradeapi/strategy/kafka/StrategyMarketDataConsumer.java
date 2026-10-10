package com.trading.tradeapi.strategy.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.marketdata.QuoteEvent;
import com.trading.tradeapi.strategy.service.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the price stream to the strategy engine, in its own consumer group
 * ({@code strategy-service}) so it sees every quote independently of alerts.
 * Unreadable messages are acknowledged and skipped rather than blocking the partition.
 */
@Component
@ConditionalOnProperty(name = "trade-api.kafka.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class StrategyMarketDataConsumer {

    private static final Logger log = LoggerFactory.getLogger(StrategyMarketDataConsumer.class);

    private final StrategyEngine engine;
    private final ObjectMapper objectMapper;

    public StrategyMarketDataConsumer(StrategyEngine engine, ObjectMapper objectMapper) {
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${topics.market-data}", groupId = "strategy-service")
    public void consume(String message) {
        QuoteEvent.decode(objectMapper, message).ifPresentOrElse(
                engine::onQuote,
                () -> log.debug("market-data message is not a quote; skipped"));
    }
}
