package com.trading.tradeapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of a suspension. The reason is shown to the suspended user. */
public record SuspendUserRequestDto(
        @NotBlank(message = "reason is required")
        @Size(max = 255, message = "reason must be at most 255 characters")
        String reason
) {}