package com.trading.tradeapi.strategy.entity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of {@code trading_strategy}: a rule a customer set once, which the platform
 * evaluates on every quote for its symbol and trades on when the rule fires.
 */
public class StrategyRecord {

    private Long strategyId;
    private Long accountId;
    private String name;
    private String symbol;
    private String strategyType;
    private String side;
    private BigDecimal targetPrice;
    private String orderType;
    private Integer shortWindow;
    private Integer longWindow;
    private Integer bandPeriod;
    private BigDecimal bandK;
    private Long quantity;
    private BigDecimal maxNotional;
    private Integer maxOrdersPerDay;
    private Long maxPosition;
    private String status;
    private String stopReason;
    private String lastState;
    private BigDecimal lastPrice;
    private Instant lastEvaluatedAt;
    private Integer consecutiveFailures;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getStrategyId() { return strategyId; }
    public void setStrategyId(Long strategyId) { this.strategyId = strategyId; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public BigDecimal getTargetPrice() { return targetPrice; }
    public void setTargetPrice(BigDecimal targetPrice) { this.targetPrice = targetPrice; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public Integer getShortWindow() { return shortWindow; }
    public void setShortWindow(Integer shortWindow) { this.shortWindow = shortWindow; }
    public Integer getLongWindow() { return longWindow; }
    public void setLongWindow(Integer longWindow) { this.longWindow = longWindow; }
    public Integer getBandPeriod() { return bandPeriod; }
    public void setBandPeriod(Integer bandPeriod) { this.bandPeriod = bandPeriod; }
    public BigDecimal getBandK() { return bandK; }
    public void setBandK(BigDecimal bandK) { this.bandK = bandK; }
    public Long getQuantity() { return quantity; }
    public void setQuantity(Long quantity) { this.quantity = quantity; }
    public BigDecimal getMaxNotional() { return maxNotional; }
    public void setMaxNotional(BigDecimal maxNotional) { this.maxNotional = maxNotional; }
    public Integer getMaxOrdersPerDay() { return maxOrdersPerDay; }
    public void setMaxOrdersPerDay(Integer maxOrdersPerDay) { this.maxOrdersPerDay = maxOrdersPerDay; }
    public Long getMaxPosition() { return maxPosition; }
    public void setMaxPosition(Long maxPosition) { this.maxPosition = maxPosition; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public String getLastState() { return lastState; }
    public void setLastState(String lastState) { this.lastState = lastState; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public void setLastPrice(BigDecimal lastPrice) { this.lastPrice = lastPrice; }
    public Instant getLastEvaluatedAt() { return lastEvaluatedAt; }
    public void setLastEvaluatedAt(Instant lastEvaluatedAt) { this.lastEvaluatedAt = lastEvaluatedAt; }
    public Integer getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(Integer consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
