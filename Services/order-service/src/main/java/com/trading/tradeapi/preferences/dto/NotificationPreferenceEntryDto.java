package com.trading.tradeapi.preferences.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.trading.tradeapi.preferences.api.AlertChannel;

import java.time.Instant;

/**
 * How one notification category is delivered for an account. {@code INHERIT} means "follow the
 * global channel the customer set", which is also what an account with no row for the category
 * answers; the UI explains it by showing the effective fallback next to it.
 */
public record NotificationPreferenceEntryDto(
        String category,
        AlertChannel channel,
        String contact,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant updatedAt) {
}