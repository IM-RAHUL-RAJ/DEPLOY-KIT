package com.trading.tradeapi.notifications.service;

import com.trading.tradeapi.notifications.service.NotificationService.OrderOutcome;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Decodes one trade-events payload into the outcome the notification table stores.
 *
 * <p>The topic carries two producers with the same contract but different habits: the executor
 * emits {@code TRADE_EXECUTED}/{@code TRADE_REJECTED} with a sparse payload and an
 * {@code occurredAt} envelope field, and this service emits {@code ORDER_CANCELLED} with the
 * full payload shape. This parser is tolerant of both: nothing it does assumes a field that the
 * other side may not have sent.
 */
public final class TradeEventPayload {

    private TradeEventPayload() {
    }

    /**
     * @return the canonical category, or {@code null} when the event type is not one the inbox
     *      shows (the message is then acknowledged and ignored, not routed to a dead-letter
     *      topic: it is simply not a lifecycle outcome).
     */
    public static String canonicalCategory(String eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case "ORDER_FILLED", "TRADE_EXECUTED" -> "ORDER_FILLED";
            case "ORDER_REJECTED", "TRADE_REJECTED" -> "ORDER_REJECTED";
            case "ORDER_CANCELLED" -> "ORDER_CANCELLED";
            default -> null;
        };
    }

    /**
     * The notification key for one outcome of one order: {@code order-<id>-<category>}. The
     * Kafka consumer and {@link OrderOutcomeSweeper} both record under it, so an outcome that
     * reaches the service by both paths books one notification. Without an order id the
     * event's own id is the only key there is.
     */
    public static String outcomeKey(Long orderId, String category, String eventId) {
        return orderId == null ? eventId : "order-" + orderId + "-" + category;
    }

    public static OrderOutcome asOutcome(String eventId, String category, Map<String, Object> payload) {
        if (payload == null) {
            payload = Map.of();
        }
        Object orderIdValue = payload.get("orderId");
        Object quantityValue = payload.get("quantity");
        Object priceValue = payload.get("executedPrice") != null ? payload.get("executedPrice") : payload.get("price");

        return new OrderOutcome(
                outcomeKey(longOrNull(orderIdValue), category, eventId),
                category,
                longOrNull(payload.get("accountId")),
                stringOrNull(payload.get("symbol")),
                longOrNull(orderIdValue),
                stringOrNull(payload.get("side")),
                longOrNull(quantityValue),
                decimalOrNull(priceValue),
                stringOrNull(payload.get("reason")));
    }

    private static Long longOrNull(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimalOrNull(Object value) {
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value != null) {
            try {
                return new BigDecimal(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}