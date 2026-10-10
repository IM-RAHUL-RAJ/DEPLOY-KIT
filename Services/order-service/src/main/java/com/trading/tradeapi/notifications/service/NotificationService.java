package com.trading.tradeapi.notifications.service;

import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.notifications.api.AlertDelivery;
import com.trading.tradeapi.notifications.api.PriceAlertCrossed;
import com.trading.tradeapi.notifications.dto.NotificationDto;
import com.trading.tradeapi.notifications.entity.NotificationRecord;
import com.trading.tradeapi.notifications.mapper.NotificationMapper;
import com.trading.tradeapi.preferences.api.AlertChannelResolver;
import com.trading.tradeapi.preferences.api.ResolvedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Owns {@code notification}: records order lifecycle outcomes and crossed price alerts, resolves
 * where to send each one, and records what happened to the attempt.
 *
 * <p>Delivery order matters. The {@code QUEUED} row is committed first; only then is anything
 * sent; only then is the {@code SENT}/{@code FAILED}/{@code SUPPRESSED} status written. A crash
 * at any point leaves a row that says what the customer should know happened, not a gap.
 *
 * <p>Implements {@link AlertDelivery} so the watchlists module reaches the inbox through the
 * published seam, never through this class's HTTP surface.
 */
@Service
public class NotificationService implements AlertDelivery {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final int HISTORY_LIMIT_MAX = 100;

    private final NotificationMapper notificationMapper;
    private final AlertChannelResolver alertChannelResolver;
    private final ChannelDispatcher channelDispatcher;

    public NotificationService(NotificationMapper notificationMapper,
                               AlertChannelResolver alertChannelResolver,
                               ChannelDispatcher channelDispatcher) {
        this.notificationMapper = notificationMapper;
        this.alertChannelResolver = alertChannelResolver;
        this.channelDispatcher = channelDispatcher;
    }

    // -- Inbound from the Kafka consumer (order lifecycle) -------------------------------

    /**
     * Books one order lifecycle outcome. Idempotent on {@code eventId}: a redelivered Kafka
     * message finds the existing row and stops, so nothing is booked twice and nothing is sent
     * twice.
     */
    @Transactional
    public void recordOrderOutcome(OrderOutcome outcome) {
        if (outcome.accountId() == null) {
            log.warn("Trade event {} skipped: no account id", outcome.eventId());
            return;
        }
        record(new NotificationRecord(
                outcome.accountId(),
                outcome.eventId(),
                outcome.category(),
                firstNonBlank(outcome.symbol(), outcome.orderId() == null ? null : "order-" + outcome.orderId()),
                titleFor(outcome.category()),
                bodyFor(outcome),
                "NONE"));
    }

    // -- Inbound from the watchlists module (price alerts) ------------------------------

    /** The {@link AlertDelivery} seam: nothing about delivery lives in the alerts module. */
    @Override
    @Transactional
    public void deliverPriceAlert(PriceAlertCrossed crossed) {
        String direction = PriceAlertCrossed.ABOVE.equals(crossed.direction())
                ? "above" : "below";
        record(new NotificationRecord(
                crossed.accountId(),
                crossed.eventId(),
                "PRICE_ALERT",
                crossed.symbol(),
                "Price alert triggered",
                crossed.symbol() + " crossed " + direction + " " + plain(crossed.threshold())
                        + "; last price " + plain(crossed.lastPrice()) + ".",
                "NONE"));
    }

    // -- The one recording path ---------------------------------------------------------

    /**
     * Persist the row (with the channel it was resolved to), dispatch, then record the outcome.
     * A duplicate event id (a Kafka replay that somehow raced past the first check) is absorbed
     * by the unique constraint and treated as already recorded. Dispatch never throws, so a
     * provider outage fails the row to {@code FAILED} instead of taking the consumer thread down.
     */
    private void record(NotificationRecord record) {
        if (notificationMapper.findByEventId(record.getEventId()) != null) {
            log.debug("Notification for event {} already recorded; replay ignored", record.getEventId());
            return;
        }

        ResolvedChannel resolved = alertChannelResolver.resolveChannelForAccount(
                record.getAccountId(), record.getCategory());
        record.setChannel(resolved.channel().name());
        try {
            notificationMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.debug("Notification for event {} lost the insert race; replay ignored", record.getEventId());
            return;
        }

        if (!resolved.deliverable()) {
            notificationMapper.updateStatus(record.getNotificationId(), "SUPPRESSED");
            log.info("Notification {} suppressed: account {} ({}) had no deliverable channel",
                    record.getEventId(), record.getAccountId(), record.getSymbol());
            return;
        }

        boolean sent = dispatch(record, resolved);
        notificationMapper.updateStatus(record.getNotificationId(), sent ? "SENT" : "FAILED");
        log.info("Notification {} ({} on {}) ended {}",
                record.getEventId(), resolved.channel(), record.getSymbol(), sent ? "SENT" : "FAILED");
    }

    private boolean dispatch(NotificationRecord record, ResolvedChannel resolved) {
        return switch (resolved.channel()) {
            case EMAIL -> channelDispatcher.sendEmail(resolved.contact(), record.getTitle(), record.getBody());
            case PUSH -> channelDispatcher.sendPush("in-app", record.getBody());
            // Both: the in-app toast always shows; the record is SENT when the email went too.
            case BOTH -> {
                channelDispatcher.sendPush("in-app", record.getBody());
                yield channelDispatcher.sendEmail(resolved.contact(), record.getTitle(), record.getBody());
            }
            default -> false;
        };
    }

    // -- The inbox ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<NotificationDto> listFor(long accountId, Boolean unreadOnly, Integer limit) {
        int actualLimit = limit == null ? HISTORY_LIMIT_MAX : limit;
        if (actualLimit < 1 || actualLimit > HISTORY_LIMIT_MAX) {
            throw new DomainException("VAL-422", "limit must be between 1 and " + HISTORY_LIMIT_MAX,
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return notificationMapper.findByAccount(accountId, Boolean.TRUE.equals(unreadOnly), actualLimit)
                .stream()
                .map(NotificationDto::from)
                .toList();
    }

    /** Idempotent: marking an already-read notification returns it unchanged. */
    @Transactional
    public NotificationDto markRead(long accountId, long notificationId) {
        NotificationRecord record = notificationMapper.findById(notificationId);
        if (record == null || !record.getAccountId().equals(accountId)) {
            // One answer for "not yours" and "does not exist": never a hint that the id exists.
            throw new DomainException("ACC-404", "Notification not found",
                    HttpStatus.NOT_FOUND);
        }
        notificationMapper.markRead(notificationId, accountId);
        return NotificationDto.from(notificationMapper.findById(notificationId));
    }

    // -- Copy ------------

    /**
     * A single lifecycle outcome as decoded from the trade-events topic. The consumer normalises
     * event names before it gets here, so categories are always the canonical three.
     */
    public record OrderOutcome(String eventId, String category, Long accountId, String symbol,
                               Long orderId, String side, Long quantity, BigDecimal executedPrice,
                               String reason) {
    }

    private String titleFor(String category) {
        return switch (category) {
            case "ORDER_FILLED" -> "Order filled";
            case "ORDER_REJECTED" -> "Order rejected";
            case "ORDER_CANCELLED" -> "Order cancelled";
            default -> "Notification";
        };
    }

    private String bodyFor(OrderOutcome outcome) {
        return switch (outcome.category()) {
            case "ORDER_FILLED" -> "Order " + safe(outcome.orderId()) + " " + safe(outcome.side())
                    + " " + safe(outcome.quantity()) + " " + safe(outcome.symbol())
                    + " filled at " + plain(outcome.executedPrice()) + ".";
            case "ORDER_REJECTED" -> "Order " + safe(outcome.orderId()) + " " + safe(outcome.symbol())
                    + " was rejected"
                    + (outcome.reason() == null || outcome.reason().isBlank() ? "." : ": " + outcome.reason());
            case "ORDER_CANCELLED" -> "Order " + safe(outcome.orderId()) + " " + safe(outcome.symbol())
                    + " was cancelled.";
            default -> "Your order status changed.";
        };
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "n/a" : value.stripTrailingZeros().toPlainString();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}