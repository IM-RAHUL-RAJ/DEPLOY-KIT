package com.trading.tradeapi.entity;

import java.time.Instant;

public class InstrumentRecord {
    private Long instrumentId;
    private String symbol;
    private String ticker;
    private String assetClass;
    private String quoteCurrency;
    private String status;
    private Instant delistedAt;

    public InstrumentRecord() {}

    public InstrumentRecord(Long instrumentId, String symbol, String ticker, String assetClass,
                            String quoteCurrency, String status, Instant delistedAt) {
        this.instrumentId = instrumentId;
        this.symbol = symbol;
        this.ticker = ticker;
        this.assetClass = assetClass;
        this.quoteCurrency = quoteCurrency;
        this.status = status;
        this.delistedAt = delistedAt;
    }

    public Long getInstrumentId() { return instrumentId; }
    public void setInstrumentId(Long instrumentId) { this.instrumentId = instrumentId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public String getAssetClass() { return assetClass; }
    public void setAssetClass(String assetClass) { this.assetClass = assetClass; }

    public String getQuoteCurrency() { return quoteCurrency; }
    public void setQuoteCurrency(String quoteCurrency) { this.quoteCurrency = quoteCurrency; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getDelistedAt() { return delistedAt; }
    public void setDelistedAt(Instant delistedAt) { this.delistedAt = delistedAt; }
}
