package com.trading.tradeapi.preferences.dto;

import java.util.List;

/**
 * The stored per-category delivery answers for one account. Always the same four categories,
 * in the same order, so a client never has to ask "is it missing?" - an absent row is
 * represented as {@code INHERIT} with no contact.
 */
public record NotificationPreferencesResponse(
        Long accountId,
        List<NotificationPreferenceEntryDto> preferences) {
}