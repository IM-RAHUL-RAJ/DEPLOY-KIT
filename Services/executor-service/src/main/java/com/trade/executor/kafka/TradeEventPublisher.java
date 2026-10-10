package com.trade.executor.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.domain.FillDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes order outcomes to trade-events in the shape contracts/kafka-topics.md
 * fixes: the five-field envelope, eventType ORDER_FILLED or ORDER_REJECTED, and a
 * payload carrying the cash and position after the event so a consumer can keep
 * its own projection of the portfolio. Keyed by accountId for per-account order.
 *
 * <p>Every path that resolves an order publishes here: the Kafka consumer, the
 * poller and the end-of-day job. A rejection is an event too; a consumer that
 * only ever saw fills would report a fill rate of 100 per cent.
 */
@Component
public class TradeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TradeEventPublisher.class);

    @Value("${topics.trade-events}")
    private String tradeEventsTopic;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public TradeEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /** The order the event is about, as it stood when it was resolved. */
    public record OrderFacts(Long orderId, Long accountId, String symbol, String side,
                             Long quantity, BigDecimal limitPrice) {}

    public void publishFilled(OrderFacts order, FillDecision fill) {
        Map<String, Object> payload = payload(order);
        payload.put("executedPrice", fill.executedPrice());
        payload.put("status", "FILLED");
        payload.put("reason", null);
        payload.put("cashDelta", fill.cashDelta());
        payload.put("positionQuantityAfter", fill.newHoldingQty());
        payload.put("averageCostAfter", fill.newAvgCost());
        publish("ORDER_FILLED", order.accountId(), payload);
    }

    /**
     * @param heldQuantity the position, unchanged by a rejection
     * @param heldAverageCost the average cost of that position
     */
    public void publishRejected(OrderFacts order, String reason, Long heldQuantity, BigDecimal heldAverageCost) {
        Map<String, Object> payload = payload(order);
        payload.put("executedPrice", null);
        payload.put("status", "REJECTED");
        payload.put("reason", reason);
        payload.put("cashDelta", BigDecimal.ZERO);
        payload.put("positionQuantityAfter", heldQuantity == null ? 0L : heldQuantity);
        payload.put("averageCostAfter", heldAverageCost == null ? BigDecimal.ZERO : heldAverageCost);
        publish("ORDER_REJECTED", order.accountId(), payload);
    }

    private static Map<String, Object> payload(OrderFacts order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId());
        payload.put("accountId", order.accountId());
        payload.put("symbol", order.symbol());
        payload.put("side", order.side());
        payload.put("quantity", order.quantity());
        payload.put("price", order.limitPrice());
        payload.put("executedOn", now());
        return payload;
    }

    private void publish(String eventType, Long accountId, Map<String, Object> payload) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", UUID.randomUUID().toString());
            envelope.put("eventType", eventType);
            envelope.put("eventTime", now());
            envelope.put("source", "trade-executor");
            envelope.put("schemaVersion", 1);
            envelope.put("payload", payload);
            String json = objectMapper.writeValueAsString(envelope);

            kafkaTemplate.send(tradeEventsTopic, accountId.toString(), json).whenComplete((result, error) -> {
                if (error != null) {
                    log.error("{} for order {} was not acknowledged by Kafka", eventType, payload.get("orderId"), error);
                }
            });
        } catch (Exception e) {
            // The database already holds the outcome; a lost event degrades
            // downstream projections but must not undo or retry the fill.
            log.error("Could not publish {} for order {}", eventType, payload.get("orderId"), e);
        }
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
