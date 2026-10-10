package com.trading.tradeapi.watchlist.entity;

import java.time.Instant;

public class WatchlistRecord {
    private Long watchlistId;
    private Long accountId;
    private String name;
    private Instant createdAt;
    private Instant updatedAt;

    public WatchlistRecord() {}

    public WatchlistRecord(Long watchlistId, Long accountId, String name, Instant createdAt, Instant updatedAt) {
        this.watchlistId = watchlistId;
        this.accountId = accountId;
        this.name = name;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getWatchlistId() { return watchlistId; }
    public void setWatchlistId(Long watchlistId) { this.watchlistId = watchlistId; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
