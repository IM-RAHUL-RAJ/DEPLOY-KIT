package com.trading.tradeapi.kafka.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.entity.HoldingRecord;
import com.trading.tradeapi.entity.OrderRecord;
import com.trading.tradeapi.kafka.event.KafkaEventEnvelope;
import com.trading.tradeapi.mapper.HoldingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes ORDER_CANCELLED to trade-events (contracts/kafka-topics.md).
 *
 * <p>A cancellation is decided here, in the Trade REST API, rather than by the
 * executor, so this is the one lifecycle outcome the API announces itself. Without
 * it every consumer of trade-events would see the order placed and never resolved.
 */
@Service
public class TradeEventProducer {

    private static final Logger logger = LoggerFactory.getLogger(TradeEventProducer.class);

    @org.springframework.beans.factory.annotation.Value("${topics.trade-events}")
    private String topic;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final HoldingMapper holdingMapper;

    public TradeEventProducer(KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper,
                              HoldingMapper holdingMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.holdingMapper = holdingMapper;
    }

    public void publishCancelled(OrderRecord order) {
        try {
            HoldingRecord holding = holdingMapper.findByAccountAndTicker(order.getAccountId(), order.getTicker());
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", order.getOrderId());
            payload.put("accountId", order.getAccountId());
            payload.put("symbol", order.getTicker());
            payload.put("side", order.getSide() == null ? null : order.getSide().name());
            payload.put("quantity", order.getQuantity() == null ? null : order.getQuantity().longValue());
            payload.put("price", order.getPrice());
            payload.put("executedPrice", null);
            payload.put("status", "CANCELLED");
            payload.put("reason", "CANCELLED_BY_CUSTOMER");
            payload.put("cashDelta", BigDecimal.ZERO);
            payload.put("positionQuantityAfter",
                    holding == null || holding.getQuantity() == null ? 0L : holding.getQuantity().longValue());
            payload.put("averageCostAfter",
                    holding == null || holding.getAveragePrice() == null ? BigDecimal.ZERO : holding.getAveragePrice());
            payload.put("executedOn", now.toString());

            String json = objectMapper.writeValueAsString(new KafkaEventEnvelope<>(
                    UUID.randomUUID().toString(), "ORDER_CANCELLED", now.toString(), "trade-api", 1, payload));

            kafkaTemplate.send(topic, String.valueOf(order.getAccountId()), json).whenComplete((result, error) -> {
                if (error != null) {
                    logger.error("ORDER_CANCELLED for orderId={} was not acknowledged by Kafka", order.getOrderId(), error);
                }
            });
        } catch (Exception e) {
            logger.error("Could not publish ORDER_CANCELLED for orderId={}", order.getOrderId(), e);
        }
    }
}
