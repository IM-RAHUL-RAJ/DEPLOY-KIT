package com.trade.executor.service;

import com.trade.executor.domain.FillDecision;
import com.trade.executor.domain.FillRuleEvaluator;
import com.trade.executor.domain.Quote;
import com.trade.executor.exception.NonRetryableOrderException;
import com.trade.executor.kafka.TradeEventPublisher;
import com.trade.executor.kafka.TradeEventPublisher.OrderFacts;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Takes a NEW order to its outcome: evaluate it against a quote, write the result,
 * and publish the event. The Kafka consumer, the market-data poller and the
 * end-of-day job all resolve orders through here, so the three paths cannot drift
 * apart, and an event is published only by the path whose guarded update won.
 */
@Service
public class OrderResolver {

    public enum Outcome { FILLED, REJECTED, PENDING, ALREADY_RESOLVED }

    private final ExecutionService executionService;
    private final FillRuleEvaluator fillRuleEvaluator;
    private final CurrencyConverter currencyConverter;
    private final TradeEventPublisher publisher;

    public OrderResolver(ExecutionService executionService,
                         FillRuleEvaluator fillRuleEvaluator,
                         CurrencyConverter currencyConverter,
                         TradeEventPublisher publisher) {
        this.executionService = executionService;
        this.fillRuleEvaluator = fillRuleEvaluator;
        this.currencyConverter = currencyConverter;
        this.publisher = publisher;
    }

    /**
     * @param quote the instrument's quote in its own currency, or null when none could be read
     */
    public Outcome resolve(OrderFacts order, String orderType, Quote quote) {
        Map<String, Object> account = executionService.fetchAccount(order.accountId());
        if (account == null) {
            throw new NonRetryableOrderException(
                    "Account " + order.accountId() + " not found for order " + order.orderId());
        }
        Map<String, Object> holding = executionService.fetchHolding(order.accountId(), order.symbol());
        Long heldQty = holding == null ? 0L : ((Number) holding.get("quantity")).longValue();
        BigDecimal heldCost = holding == null ? BigDecimal.ZERO : (BigDecimal) holding.get("avg_cost");

        // Re-denominate the quote into the ledger currency, so the limit check, the
        // cash movement and the position are all in the account's own currency.
        String accountCurrency = account.get("currency") == null ? "USD" : account.get("currency").toString();
        Quote accountQuote = quote == null ? null : currencyConverter.toAccountCurrency(quote, accountCurrency);

        FillDecision decision = fillRuleEvaluator.evaluate(
                order.side(), order.quantity(), order.limitPrice(), accountQuote,
                (String) account.get("status"), heldQty, heldCost,
                (BigDecimal) account.get("cash_balance"), orderType);

        if (decision.pending()) {
            return Outcome.PENDING;
        }
        if (decision.filled()) {
            if (!executionService.settle(order.orderId(), order.accountId(), order.symbol(), order.side(),
                    order.quantity(), decision, account)) {
                return Outcome.ALREADY_RESOLVED;
            }
            publisher.publishFilled(order, decision);
            return Outcome.FILLED;
        }
        if (!executionService.reject(order.orderId(), decision.rejectionReason())) {
            return Outcome.ALREADY_RESOLVED;
        }
        publisher.publishRejected(order, decision.rejectionReason(), heldQty, heldCost);
        return Outcome.REJECTED;
    }

    /** Rejects an order that was never filled, e.g. at the close. */
    public boolean expire(OrderFacts order, String reason) {
        if (!executionService.reject(order.orderId(), reason)) {
            return false;
        }
        Map<String, Object> holding = executionService.fetchHolding(order.accountId(), order.symbol());
        publisher.publishRejected(order, reason,
                holding == null ? 0L : ((Number) holding.get("quantity")).longValue(),
                holding == null ? BigDecimal.ZERO : (BigDecimal) holding.get("avg_cost"));
        return true;
    }

    /** Builds the order facts from an orders row. */
    public static OrderFacts facts(Map<String, Object> row) {
        return new OrderFacts(
                ((Number) row.get("order_id")).longValue(),
                ((Number) row.get("account_id")).longValue(),
                (String) row.get("ticker"),
                (String) row.get("side"),
                ((Number) row.get("quantity")).longValue(),
                (BigDecimal) row.get("price"));
    }

    public static String orderType(Map<String, Object> row) {
        Object type = row.get("order_type");
        return type == null || type.toString().isBlank() ? "LIMIT" : type.toString().trim().toUpperCase();
    }
}
