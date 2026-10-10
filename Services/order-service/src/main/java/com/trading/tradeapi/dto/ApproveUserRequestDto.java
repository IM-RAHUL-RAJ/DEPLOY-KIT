package com.trading.tradeapi.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of an admin approval or rejection. {@code reason} is stored on the user and
 * shown to them, so a rejection without one is refused.
 *
 * <p>Approval credits nothing: a new account opens at zero and an administrator
 * funds it separately through the balance adjustment route, which is audited.
 */
public record ApproveUserRequestDto(
        @Size(max = 255, message = "reason must be at most 255 characters")
        String reason
) {}
