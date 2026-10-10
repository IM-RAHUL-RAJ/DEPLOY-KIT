package com.trade.executor.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Decides whether an order fills, waits or is rejected against a quote, with no
 * database and no I/O, so every rule can be unit tested.
 *
 * <p>A MARKET order fills at the touch: the ask to buy, the bid to sell. A LIMIT
 * order fills only when the touch is at or better than its limit, and otherwise
 * stays PENDING, still NEW in the database, for the poller to re-evaluate. A
 * missing or stale quote is a feed gap, not a verdict on the order: a LIMIT order
 * waits for a fresh one. A MARKET order has no price of its own to wait on, so with
 * no quote at all it is rejected, and with a stale one it fills at the last price.
 */
@Component
public class FillRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FillRuleEvaluator.class);

    public FillDecision evaluate(String side,
                                 Long quantity,
                                 BigDecimal limitPrice,
                                 Quote quote,
                                 String accountStatus,
                                 Long currentHoldingQty,
                                 BigDecimal currentHoldingAvgCost,
                                 BigDecimal cashBalance,
                                 String orderType) {

        boolean isMarket = "MARKET".equals(orderType == null ? "LIMIT" : orderType.trim().toUpperCase());

        if (!"ACTIVE".equalsIgnoreCase(accountStatus)) {
            return FillDecision.rejected("ACCOUNT_NOT_ACTIVE");
        }
        if (quote == null) {
            return isMarket
                    ? FillDecision.rejected("PRICE_NOT_AVAILABLE")
                    : FillDecision.pending("QUOTE_UNAVAILABLE - waiting for the next quote");
        }
        if (quote.stale() && !isMarket) {
            return FillDecision.pending("PRICE_STALE - waiting for a fresh quote");
        }
        if (quote.stale()) {
            log.warn("Filling MARKET order for {} against a stale quote", quote.symbol());
        }

        long held = currentHoldingQty != null ? currentHoldingQty : 0L;
        BigDecimal heldCost = currentHoldingAvgCost != null ? currentHoldingAvgCost : BigDecimal.ZERO;

        if ("BUY".equalsIgnoreCase(side)) {
            BigDecimal ask = quote.ask() != null ? quote.ask() : quote.price();
            if (ask == null || ask.signum() <= 0) {
                return FillDecision.rejected("PRICE_NOT_AVAILABLE");
            }
            if (!isMarket && ask.compareTo(limitPrice) > 0) {
                return FillDecision.pending("PRICE_NOT_MET");
            }

            BigDecimal executedPrice = ask.setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalCost = executedPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
            if (cashBalance.compareTo(totalCost) < 0) {
                return FillDecision.rejected("INSUFFICIENT_FUNDS");
            }

            long newQty = held + quantity;
            BigDecimal newAvgCost = heldCost.multiply(BigDecimal.valueOf(held))
                    .add(totalCost)
                    .divide(BigDecimal.valueOf(newQty), 8, RoundingMode.HALF_UP);
            return FillDecision.filled(executedPrice, totalCost.negate(), newQty, newAvgCost);
        }

        if ("SELL".equalsIgnoreCase(side)) {
            BigDecimal bid = quote.bid() != null ? quote.bid() : quote.price();
            if (bid == null || bid.signum() <= 0) {
                return FillDecision.rejected("PRICE_NOT_AVAILABLE");
            }
            if (!isMarket && bid.compareTo(limitPrice) < 0) {
                return FillDecision.pending("PRICE_NOT_MET");
            }
            if (held < quantity) {
                return FillDecision.rejected("INSUFFICIENT_HOLDINGS");
            }

            BigDecimal executedPrice = bid.setScale(2, RoundingMode.HALF_UP);
            BigDecimal proceeds = executedPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
            long newQty = held - quantity;
            // Selling does not change the cost of what is left.
            return FillDecision.filled(executedPrice, proceeds, newQty, newQty == 0 ? BigDecimal.ZERO : heldCost);
        }

        return FillDecision.rejected("INVALID_SIDE");
    }
}
