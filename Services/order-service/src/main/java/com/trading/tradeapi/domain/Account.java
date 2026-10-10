package com.trading.tradeapi.domain;

import com.trading.tradeapi.exception.InsufficientFundsException;
import com.trading.tradeapi.enums.AccountStatus;

import java.math.BigDecimal;
import java.util.Objects;

public class Account {
    private final long accountId;
    private final String accountReference;
    private BigDecimal accountBalance;
    private AccountStatus accountStatus;

    public Account(long accountId, String accountReference,
                   BigDecimal accountBalance, AccountStatus accountStatus) {
        if (accountId < 1) {
            throw new IllegalArgumentException("accountId must be positive");
        }
        if (accountReference == null || accountReference.isBlank()) {
            throw new IllegalArgumentException("accountReference is required");
        }
        if (accountBalance == null
                || accountBalance.scale() > 2
                || accountBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "accountBalance must be non-negative with at most 2 decimal places");
        }

        this.accountId = accountId;
        this.accountReference = accountReference;
        this.accountBalance = accountBalance.setScale(2);
        this.accountStatus = Objects.requireNonNull(accountStatus);
    }

    public long getAccountId() {
        return accountId;
    }

    public String getAccountReference() {
        return accountReference;
    }

    public BigDecimal getAccountBalance() {
        return accountBalance;
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(AccountStatus accountStatus) {
        this.accountStatus = Objects.requireNonNull(accountStatus);
    }

    public boolean canAfford(BigDecimal amount) {
        Objects.requireNonNull(amount);
        return accountBalance.subtract(amount).compareTo(BigDecimal.ZERO) >= 0;
    }

    public void debit(BigDecimal amount) {
        Objects.requireNonNull(amount);

        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Debit amount cannot be negative");
        }

        if (accountBalance.subtract(amount).compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientFundsException();
        }

        accountBalance = accountBalance.subtract(amount).setScale(2);
    }

    public void credit(BigDecimal amount) {
        Objects.requireNonNull(amount);

        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Credit amount cannot be negative");
        }

        accountBalance = accountBalance.add(amount).setScale(2);
    }
}
