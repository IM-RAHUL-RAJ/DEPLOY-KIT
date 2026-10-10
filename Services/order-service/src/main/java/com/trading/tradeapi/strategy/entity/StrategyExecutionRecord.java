package com.trading.tradeapi.strategy.entity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of {@code strategy_execution}: what a strategy did when its rule fired on a
 * quote. Unique per strategy and market-data event, so a redelivered quote cannot
 * trade twice.
 */
public class StrategyExecutionRecord {

    private Long executionId;
    private Long strategyId;
    private String eventId;
    private String signal;
    private BigDecimal price;
    private Long quantity;
    private String status;
    private String orderId;
    private String reason;
    private Instant createdAt;

    public StrategyExecutionRecord() {
    }

    public StrategyExecutionRecord(Long strategyId, String eventId, String signal, BigDecimal price,
                                   Long quantity, String status, String reason) {
        this.strategyId = strategyId;
        this.eventId = eventId;
        this.signal = signal;
        this.price = price;
        this.quantity = quantity;
        this.status = status;
        this.reason = reason;
    }

    public Long getExecutionId() { return executionId; }
    public void setExecutionId(Long executionId) { this.executionId = executionId; }
    public Long getStrategyId() { return strategyId; }
    public void setStrategyId(Long strategyId) { this.strategyId = strategyId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getSignal() { return signal; }
    public void setSignal(String signal) { this.signal = signal; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Long getQuantity() { return quantity; }
    public void setQuantity(Long quantity) { this.quantity = quantity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
