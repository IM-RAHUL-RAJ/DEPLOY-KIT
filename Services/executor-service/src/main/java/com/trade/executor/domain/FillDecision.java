package com.trade.executor.domain;

import java.math.BigDecimal;

public record FillDecision(
        String status, // FILLED, REJECTED, or PENDING
        BigDecimal executedPrice,
        String reason,
        BigDecimal cashDelta,
        Long positionQuantityAfter,
        BigDecimal averageCostAfter
) {
    public static FillDecision filled(BigDecimal executedPrice, BigDecimal cashDelta, Long positionQuantityAfter, BigDecimal averageCostAfter) {
        return new FillDecision("FILLED", executedPrice, null, cashDelta, positionQuantityAfter, averageCostAfter);
    }

    public static FillDecision rejected(String reason) {
        return new FillDecision("REJECTED", null, reason, BigDecimal.ZERO, 0L, BigDecimal.ZERO);
    }

    public static FillDecision pending(String reason) {
        return new FillDecision("PENDING", null, reason, BigDecimal.ZERO, 0L, BigDecimal.ZERO);
    }

    /** Returns true if this decision is a fill (not a rejection or pending). */
    public boolean filled() {
        return "FILLED".equals(status);
    }

    /** Returns true if this decision is pending (limit order waiting for price). */
    public boolean pending() {
        return "PENDING".equals(status);
    }

    /** Alias for reason — the rejection reason code. */
    public String rejectionReason() {
        return reason;
    }

    /** Alias for positionQuantityAfter — new holding quantity after settlement. */
    public Long newHoldingQty() {
        return positionQuantityAfter;
    }

    /** Alias for averageCostAfter — new average cost per unit after settlement. */
    public BigDecimal newAvgCost() {
        return averageCostAfter;
    }
}
