package com.trading.tradeapi.preferences.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.trading.tradeapi.preferences.api.AlertChannel;
import java.time.Instant;

/**
 * The body of {@code GET /api/v1/preferences/{accountId}}.
 *
 * <p>An account that has never saved a preference answers with {@code alertChannel: NONE},
 * every other optional field {@code null}, and {@code updatedAt: null}. That is not the same
 * answer as an error: a caller can tell "never answered" from "this failed", which is what
 * lets the sign-in path apply a documented default instead of guessing.
 */
public record PreferenceResponse(
        Long accountId,
        Long defaultAccountId,
        AlertChannel alertChannel,
        String alertContact,
        String displayCurrency,
        /** dark or light: the colour theme the terminal opens in for this customer. */
        String theme,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant updatedAt
) {}
