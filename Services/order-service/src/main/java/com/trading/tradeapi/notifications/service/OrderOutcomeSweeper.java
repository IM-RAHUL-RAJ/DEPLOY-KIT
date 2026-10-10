package com.trading.tradeapi.notifications.service;

import com.trading.tradeapi.notifications.mapper.NotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The safety net under order notifications. An order's outcome normally arrives as a
 * {@code trade-events} message; when the broker is down or the message is lost, the order
 * still fills (the executor's poller works the book from the database) but nobody would be
 * told. Every {@code notifications.order-sweep.interval-ms} this finds orders that reached
 * an outcome recently and have no notification, and records one through the same path the
 * Kafka consumer uses, under the same key, so a late message never books a second one.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "notifications.order-sweep.enabled", havingValue = "true", matchIfMissing = true)
public class OrderOutcomeSweeper {

    private static final Logger log = LoggerFactory.getLogger(OrderOutcomeSweeper.class);
    private static final int BATCH = 100;

    private final NotificationMapper mapper;
    private final NotificationService notifications;
    private final Duration lookBack;

    public OrderOutcomeSweeper(NotificationMapper mapper, NotificationService notifications,
                               @Value("${notifications.order-sweep.look-back-hours:24}") long lookBackHours) {
        this.mapper = mapper;
        this.notifications = notifications;
        this.lookBack = Duration.ofHours(lookBackHours);
    }

    @Scheduled(fixedDelayString = "${notifications.order-sweep.interval-ms:20000}",
               initialDelayString = "${notifications.order-sweep.initial-delay-ms:15000}")
    public void sweep() {
        try {
            List<Map<String, Object>> rows = mapper.findUnnotifiedOutcomes(Instant.now().minus(lookBack), BATCH);
            for (Map<String, Object> row : rows) {
                NotificationService.OrderOutcome outcome = toOutcome(row);
                notifications.recordOrderOutcome(outcome);
            }
            if (!rows.isEmpty()) {
                log.info("Recorded {} order notification(s) whose trade event never arrived", rows.size());
            }
        } catch (RuntimeException e) {
            log.warn("Order notification sweep failed: {}", e.getMessage());
        }
    }

    static NotificationService.OrderOutcome toOutcome(Map<String, Object> row) {
        Long orderId = number(row.get("orderId")) == null ? null : number(row.get("orderId")).longValue();
        String category = "ORDER_" + row.get("status");
        BigDecimal executed = number(row.get("executedPrice"));
        BigDecimal price = executed != null ? executed : number(row.get("price"));
        BigDecimal quantity = number(row.get("quantity"));
        BigDecimal account = number(row.get("accountId"));
        return new NotificationService.OrderOutcome(
                TradeEventPayload.outcomeKey(orderId, category, null),
                category,
                account == null ? null : account.longValue(),
                row.get("symbol") == null ? null : row.get("symbol").toString(),
                orderId,
                row.get("side") == null ? null : row.get("side").toString(),
                quantity == null ? null : quantity.longValue(),
                price,
                row.get("reason") == null ? null : row.get("reason").toString());
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return null;
    }
}
