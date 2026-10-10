package com.trading.tradeapi.dto;

import java.math.BigDecimal;

/**
 * An account after a status change, plus whether the account holder was emailed
 * about it. {@code emailDelivered} is null when the change sends no notice (e.g.
 * closing an account), true or false when it does (suspension, reinstatement).
 *
 * <p>Kept apart from {@link AdminAccountDto}, which MyBatis maps by constructor.
 */
public record AdminAccountStatusDto(
        Long accountId,
        Long userId,
        String holderName,
        String username,
        String userType,
        String currency,
        BigDecimal cashBalance,
        String status,
        Long version,
        Boolean emailDelivered
) {
    public static AdminAccountStatusDto of(AdminAccountDto a, Boolean emailDelivered) {
        return new AdminAccountStatusDto(a.accountId(), a.userId(), a.holderName(), a.username(),
                a.userType(), a.currency(), a.cashBalance(), a.status(), a.version(), emailDelivered);
    }
}
