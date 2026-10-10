package com.trading.tradeapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of an account status change from the admin Accounts page. The reason is
 * optional; for a suspension it is quoted to the user in the notice email.
 */
public record UpdateStatusRequestDto(
        @NotBlank(message = "status is required")
        String status,

        @Size(max = 255, message = "reason must be at most 255 characters")
        String reason
) {
    /** The one-argument form, for callers that give no reason. */
    public UpdateStatusRequestDto(String status) {
        this(status, null);
    }
}
