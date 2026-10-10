package com.trading.tradeapi.preferences.dto;

import com.trading.tradeapi.preferences.api.AlertChannel;

/**
 * One category's delivery choice as the customer states it. The contact is required exactly
 * when the channel delivers (EMAIL, PUSH, BOTH) and must be null for INHERIT and NONE, which the
 * service enforces as a 422 rather than leaving it to the database constraint.
 */
public record NotificationPreferenceRequest(
        String category,
        AlertChannel channel,
        String contact) {
}