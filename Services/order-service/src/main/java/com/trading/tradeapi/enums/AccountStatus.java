package com.trading.tradeapi.enums;

/**
 * Mirrors trading.account.status. INACTIVE and REJECTED are part of the same
 * gate as auth.users.status, so a verified applicant cannot be silently treated as
 * tradeable when the account row says otherwise.
 */
public enum AccountStatus {
    INACTIVE,
    ACTIVE,
    SUSPENDED,
    REJECTED,
    CLOSED
}
