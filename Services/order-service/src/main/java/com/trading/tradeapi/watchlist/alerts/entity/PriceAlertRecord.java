package com.trading.tradeapi.watchlist.alerts.entity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of {@code price_alert}: a one-shot threshold the customer pinned to a symbol.
 */
public class PriceAlertRecord {

    private Long priceAlertId;
    private Long accountId;
    private String symbol;
    private String direction;
    private BigDecimal threshold;
    private String currency;
    private String status;
    private BigDecimal triggeredPrice;
    private Instant triggeredAt;
    private Instant createdAt;

    public PriceAlertRecord() {
    }

    public PriceAlertRecord(Long accountId, String symbol, String direction, BigDecimal threshold) {
        this.accountId = accountId;
        this.symbol = symbol;
        this.direction = direction;
        this.threshold = threshold;
        this.status = "ACTIVE";
    }

    public Long getPriceAlertId() {
        return priceAlertId;
    }

    public void setPriceAlertId(Long priceAlertId) {
        this.priceAlertId = priceAlertId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public void setThreshold(BigDecimal threshold) {
        this.threshold = threshold;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getTriggeredPrice() {
        return triggeredPrice;
    }

    public void setTriggeredPrice(BigDecimal triggeredPrice) {
        this.triggeredPrice = triggeredPrice;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public void setTriggeredAt(Instant triggeredAt) {
        this.triggeredAt = triggeredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}