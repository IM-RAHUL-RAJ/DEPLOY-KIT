package com.trading.tradeapi.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A user as the admin console sees them, including the approval gate that decides
 * whether they can trade. {@code status} mirrors auth.users.status and
 * {@code accountStatus} mirrors trading.account.status; both move together, so a
 * mismatch means data has drifted and is surfaced rather than hidden.
 */
public record AdminUserDto(
        Long userId,
        String uuid,
        String firstName,
        String lastName,
        String username,
        String email,
        String userType,
        String phoneCountryCode,
        String phoneNumber,
        String country,
        String riskAppetite,
        String status,
        Long accountId,
        String accountStatus,
        String currency,
        BigDecimal cashBalance,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdOn,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant approvedOn,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant suspendedOn,
        String rejectionReason,
        Boolean emailDelivered
) {
    /** True when the user has been verified but is still waiting on an admin. */
    public boolean isInactive() {
        return "INACTIVE".equals(status);
    }

    /** The same user, reporting whether the notification email for this action was sent. */
    public AdminUserDto withEmailDelivered(Boolean delivered) {
        return new AdminUserDto(userId, uuid, firstName, lastName, username, email, userType,
                phoneCountryCode, phoneNumber, country, riskAppetite, status, accountId,
                accountStatus, currency, cashBalance, createdOn, approvedOn, suspendedOn,
                rejectionReason, delivered);
    }
}