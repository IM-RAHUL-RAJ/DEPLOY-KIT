package com.trading.tradeapi.notifications.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.kafka.event.KafkaEventEnvelope;
import com.trading.tradeapi.notifications.service.NotificationService;
import com.trading.tradeapi.notifications.service.TradeEventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes the order lifecycle outcomes from {@code trade-events} in the {@code notification-service}
 * consumer group (design/kafka.md).
 *
 * <p>The listener is gated behind {@code trade-api.kafka.consumers.enabled} so the integration
 * tests run without a broker: they exercise this consumer's decode path through
 * {@link TradeEventPayload} and the recording through the service. A real deployment enables the
 * property; the service's own producer and the executor both feed this topic.
 */
@Component
@ConditionalOnProperty(name = "trade-api.kafka.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class TradeEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradeEventConsumer.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public TradeEventConsumer(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${topics.trade-events}", groupId = "notification-service")
    public void consume(String message) {
        KafkaEventEnvelope<Map<String, Object>> envelope;
        try {
            envelope = objectMapper.readValue(message, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            // A poison message blocks only itself. A real deployment routes it to
            // trade-events.DLT with the failure headers (design/kafka.md); here it is
            // logged and acknowledged so the partition keeps moving.
            log.error("Unparseable trade-events message; would go to trade-events.DLT: {}", e.getMessage());
            return;
        }

        String eventType = envelope.getEventType();
        String eventId = envelope.getEventId();
        String category = TradeEventPayload.canonicalCategory(eventType);

        if (category == null) {
            log.debug("Trade event {} ({}) is not an inbox category; acknowledged and ignored", eventId, eventType);
            return;
        }
        if (eventId == null || eventId.isBlank()) {
            // Without a key there is nothing to deduplicate against on replay.
            log.error("Trade-events message in category {} without an eventId; would go to trade-events.DLT", category);
            return;
        }

        notificationService.recordOrderOutcome(TradeEventPayload.asOutcome(eventId, category, envelope.getPayload()));
    }
}