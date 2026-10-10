package com.trading.tradeapi.preferences.dto;

import com.trading.tradeapi.preferences.api.AlertChannel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /api/v1/preferences/{accountId}}. A whole-record replace rather
 * than a patch: the caller states the preference it wants, so there is no partial update for
 * two callers to interleave.
 *
 * <p>Shape validation lives here so that a bad shape is refused with {@code 422 VAL-422}
 * before any database is touched. Cross-field rules — a contact on a channel that takes
 * none, a default account this customer does not own — are in the service, because they
 * need data this object does not have.
 */
public record PreferenceRequest(
        Long defaultAccountId,
        @NotNull(message = "alertChannel is required")
        AlertChannel alertChannel,
        @Size(max = 255, message = "alertContact must be at most 255 characters")
        String alertContact,
        @Size(min = 3, max = 3, message = "displayCurrency must be a three-letter ISO 4217 code")
        String displayCurrency
) {}
