package com.trade.executor.scheduler;

import com.trade.executor.service.OrderResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Scheduled task that runs at end of trading day (e.g., 3:30 PM EST / 8:00 PM UTC)
 * and rejects all remaining NEW orders that couldn't be filled during the day.
 */
@Component
public class EndOfDayTask {

    private static final Logger log = LoggerFactory.getLogger(EndOfDayTask.class);

    private final JdbcTemplate jdbc;
    private final OrderResolver orderResolver;

    public EndOfDayTask(JdbcTemplate jdbc, OrderResolver orderResolver) {
        this.jdbc = jdbc;
        this.orderResolver = orderResolver;
    }

    /**
     * Runs every day at 8:00 PM UTC (3:30 PM EST, approximately end of trading day)
     * Cron: second minute hour day month dayOfWeek
     * "0 0 20 * * MON-FRI" = 20:00 (8 PM) UTC on weekdays only
     */
    @Scheduled(cron = "0 0 20 * * MON-FRI", zone = "UTC")
    public void rejectRemainingNewOrders() {
        try {
            log.info("[*] End-of-day task: rejecting all remaining NEW orders...");

            // Fetch all orders still in NEW status
            List<Map<String, Object>> newOrders = jdbc.queryForList(
                    "SELECT order_id, account_id, ticker, side, quantity, price FROM orders "
                            + "WHERE status = 'NEW' ORDER BY order_id");

            if (newOrders.isEmpty()) {
                log.info("[OK] No NEW orders to reject at end of day");
                return;
            }

            log.info("[*] Found {} NEW orders to reject", newOrders.size());

            int rejectedCount = 0;
            for (Map<String, Object> orderRecord : newOrders) {
                Long orderId = ((Number) orderRecord.get("order_id")).longValue();
                try {
                    // Published as ORDER_REJECTED like any other rejection, so the
                    // blotter, notifications and analytics all see the order resolved.
                    if (orderResolver.expire(OrderResolver.facts(orderRecord), "MARKET_CLOSED")) {
                        rejectedCount++;
                    }
                } catch (Exception e) {
                    log.error("[ERROR] Failed to reject order {}: {}", orderId, e.getMessage());
                }
            }

            log.info("[OK] End-of-day task complete: rejected {} orders", rejectedCount);

        } catch (Exception e) {
            log.error("[ERROR] End-of-day task failed: {}", e.getMessage(), e);
        }
    }
}
