package com.trading.tradeapi.watchlist.entity;

import java.time.Instant;

public class WatchlistInstrumentRecord {
    private Long watchlistInstrumentId;
    private Long watchlistId;
    private Long instrumentId;
    private Instant addedAt;

    public WatchlistInstrumentRecord() {}

    public WatchlistInstrumentRecord(Long watchlistInstrumentId, Long watchlistId, Long instrumentId, Instant addedAt) {
        this.watchlistInstrumentId = watchlistInstrumentId;
        this.watchlistId = watchlistId;
        this.instrumentId = instrumentId;
        this.addedAt = addedAt;
    }

    public Long getWatchlistInstrumentId() { return watchlistInstrumentId; }
    public void setWatchlistInstrumentId(Long watchlistInstrumentId) { this.watchlistInstrumentId = watchlistInstrumentId; }

    public Long getWatchlistId() { return watchlistId; }
    public void setWatchlistId(Long watchlistId) { this.watchlistId = watchlistId; }

    public Long getInstrumentId() { return instrumentId; }
    public void setInstrumentId(Long instrumentId) { this.instrumentId = instrumentId; }

    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }
}
