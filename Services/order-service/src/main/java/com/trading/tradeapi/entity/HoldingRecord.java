package com.trading.tradeapi.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public class HoldingRecord {
    private Long holdingId;
    private Long accountId;
    private String ticker;
    private BigDecimal quantity;
    private BigDecimal averagePrice;
    private LocalDate asOfDate;

    public HoldingRecord() {}

    public HoldingRecord(Long holdingId, Long accountId, String ticker,
                         BigDecimal quantity, BigDecimal averagePrice, LocalDate asOfDate) {
        this.holdingId = holdingId;
        this.accountId = accountId;
        this.ticker = ticker;
        this.quantity = quantity;
        this.averagePrice = averagePrice;
        this.asOfDate = asOfDate;
    }

    public Long getHoldingId() { return holdingId; }
    public void setHoldingId(Long holdingId) { this.holdingId = holdingId; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getAveragePrice() { return averagePrice; }
    public void setAveragePrice(BigDecimal averagePrice) { this.averagePrice = averagePrice; }

    public LocalDate getAsOfDate() { return asOfDate; }
    public void setAsOfDate(LocalDate asOfDate) { this.asOfDate = asOfDate; }
}
