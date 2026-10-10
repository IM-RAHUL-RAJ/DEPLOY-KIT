package com.trading.tradeapi.strategy.service;

/**
 * How a strategy places an order. The production implementation goes through the
 * Trade REST API ({@code POST /api/v1/orders}), so a strategy order passes every check
 * a customer's order does: account active, funds or holdings, symbol, idempotency.
 */
public interface StrategyOrderPlacer {

    /** @throws OrderPlacementException when the Trade API refused or could not be reached */
    PlacedOrder place(long strategyId, long accountId, String symbol, String side, long quantity,
                      String orderType, java.math.BigDecimal limitPrice, String idempotencyKey);

    record PlacedOrder(String orderId, String status) {}

    class OrderPlacementException extends RuntimeException {
        public OrderPlacementException(String message) {
            super(message);
        }
    }
}
