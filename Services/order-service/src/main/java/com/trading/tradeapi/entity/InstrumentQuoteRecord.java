package com.trading.tradeapi.entity;

import java.math.BigDecimal;
import java.time.Instant;

public class InstrumentQuoteRecord {
    private Long quoteId;
    private Long instrumentId;
    private String symbol;
    private BigDecimal currentPrice;
    private String currency;
    private BigDecimal bid;
    private BigDecimal ask;
    private BigDecimal change;
    private BigDecimal changePercent;
    private Instant lastUpdated;
    private Boolean stale;

    public InstrumentQuoteRecord() {}

    public InstrumentQuoteRecord(Long quoteId, Long instrumentId, String symbol, BigDecimal currentPrice,
                                 String currency, BigDecimal bid, BigDecimal ask, BigDecimal change,
                                 BigDecimal changePercent, Instant lastUpdated, Boolean stale) {
        this.quoteId = quoteId;
        this.instrumentId = instrumentId;
        this.symbol = symbol;
        this.currentPrice = currentPrice;
        this.currency = currency;
        this.bid = bid;
        this.ask = ask;
        this.change = change;
        this.changePercent = changePercent;
        this.lastUpdated = lastUpdated;
        this.stale = stale;
    }

    public Long getQuoteId() { return quoteId; }
    public void setQuoteId(Long quoteId) { this.quoteId = quoteId; }

    public Long getInstrumentId() { return instrumentId; }
    public void setInstrumentId(Long instrumentId) { this.instrumentId = instrumentId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getBid() { return bid; }
    public void setBid(BigDecimal bid) { this.bid = bid; }

    public BigDecimal getAsk() { return ask; }
    public void setAsk(BigDecimal ask) { this.ask = ask; }

    public BigDecimal getChange() { return change; }
    public void setChange(BigDecimal change) { this.change = change; }

    public BigDecimal getChangePercent() { return changePercent; }
    public void setChangePercent(BigDecimal changePercent) { this.changePercent = changePercent; }

    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }

    public Boolean getStale() { return stale; }
    public void setStale(Boolean stale) { this.stale = stale; }
}
