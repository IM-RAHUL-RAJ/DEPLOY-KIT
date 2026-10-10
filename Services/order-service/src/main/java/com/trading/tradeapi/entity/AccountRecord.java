package com.trading.tradeapi.entity;

import com.trading.tradeapi.enums.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;

public class AccountRecord {
    private Long accountId;
    private Long userId;
    private String holderName;
    private String currency;
    private BigDecimal cashBalance;
    private AccountStatus status;
    private Long version;
    private Instant closedAt;

    public AccountRecord() {}

    public AccountRecord(Long accountId, Long userId, String holderName,
                         String currency, BigDecimal cashBalance, AccountStatus status,
                         Long version, Instant closedAt) {
        this.accountId = accountId;
        this.userId = userId;
        this.holderName = holderName;
        this.currency = currency;
        this.cashBalance = cashBalance;
        this.status = status;
        this.version = version;
        this.closedAt = closedAt;
    }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getHolderName() { return holderName; }
    public void setHolderName(String holderName) { this.holderName = holderName; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getCashBalance() { return cashBalance; }
    public void setCashBalance(BigDecimal cashBalance) { this.cashBalance = cashBalance; }

    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}