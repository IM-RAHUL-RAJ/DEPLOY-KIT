package com.trade.executor.service;

import com.trade.executor.domain.FillDecision;
import com.trade.executor.exception.RetryableOrderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The database side of a fill or a rejection, each in one transaction.
 *
 * <p>Fill: the order moves NEW to FILLED with a guarded update, then cash moves
 * under an optimistic lock on the account version, then the holding is upserted.
 * Rejection: the order moves NEW to REJECTED with the same guard.
 *
 * <p>The guard ({@code WHERE status = 'NEW'}) is the idempotency mechanism: the
 * Kafka consumer, the poller and the end-of-day job can all reach the same order,
 * and a replayed ORDER_PLACED arrives twice, but only one update ever matches.
 * Both methods report whether theirs did, so the caller publishes an outcome only
 * for a change it actually made.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final JdbcTemplate jdbc;

    public ExecutionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ----- Reads -----

    public Map<String, Object> fetchOrder(Long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_id, account_id, ticker, side, quantity, price, order_type, status FROM orders WHERE order_id = ?",
                orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> fetchAccount(Long accountId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT account_id, status, cash_balance, currency, version FROM account WHERE account_id = ?",
                accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The account's holding in a symbol. Orders and holdings both record the
     * symbol as the ticker, so this reads the holding directly: going through the
     * instrument table would miss every symbol that is not catalogued there, and a
     * second buy would then overwrite the first instead of adding to it.
     */
    public Map<String, Object> fetchHolding(Long accountId, String symbol) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT quantity, average_price AS avg_cost FROM holding WHERE account_id = ? AND ticker = ?",
                accountId, normalise(symbol));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ----- Writes -----

    /**
     * Settles a fill. Returns false, having changed nothing, when the order was no
     * longer NEW. Throws {@link RetryableOrderException} when the account changed
     * underneath, which rolls the whole fill back for a retry against fresh state.
     */
    @Transactional
    public boolean settle(Long orderId, Long accountId, String symbol, String side,
                          Long quantity, FillDecision decision, Map<String, Object> account) {
        int updated = jdbc.update(
                "UPDATE orders SET status = 'FILLED', executed_price = ?, executed_on = ? "
                        + "WHERE order_id = ? AND status = 'NEW'",
                decision.executedPrice().setScale(8, RoundingMode.HALF_UP), OffsetDateTime.now(), orderId);
        if (updated == 0) {
            log.info("Order {} is no longer NEW; fill skipped", orderId);
            return false;
        }

        BigDecimal newBalance = ((BigDecimal) account.get("cash_balance")).add(decision.cashDelta());
        long version = ((Number) account.get("version")).longValue();
        int cashUpdated = jdbc.update(
                "UPDATE account SET cash_balance = ?, version = version + 1 WHERE account_id = ? AND version = ?",
                newBalance, accountId, version);
        if (cashUpdated == 0) {
            throw new RetryableOrderException(
                    "Account " + accountId + " changed while filling order " + orderId + "; retrying with fresh state");
        }

        String ticker = normalise(symbol);
        if (decision.newHoldingQty() == 0) {
            jdbc.update("DELETE FROM holding WHERE account_id = ? AND ticker = ?", accountId, ticker);
        } else {
            jdbc.update(
                    "INSERT INTO holding (account_id, ticker, quantity, average_price, as_of_date) "
                            + "VALUES (?, ?, ?, ?, CURRENT_DATE) "
                            + "ON CONFLICT (account_id, ticker) DO UPDATE "
                            + "SET quantity = EXCLUDED.quantity, average_price = EXCLUDED.average_price, "
                            + "as_of_date = CURRENT_DATE",
                    accountId, ticker, decision.newHoldingQty(), decision.newAvgCost());
        }

        log.info("Filled order {} for account {}: {} {} {} at {}",
                orderId, accountId, side, quantity, ticker, decision.executedPrice());
        return true;
    }

    /** Rejects a NEW order. Returns false when it had already been resolved. */
    @Transactional
    public boolean reject(Long orderId, String reason) {
        int updated = jdbc.update(
                "UPDATE orders SET status = 'REJECTED', rejection_reason = ? WHERE order_id = ? AND status = 'NEW'",
                reason, orderId);
        if (updated == 0) {
            log.info("Order {} is no longer NEW; rejection skipped", orderId);
        }
        return updated > 0;
    }

    private static String normalise(String symbol) {
        return symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
