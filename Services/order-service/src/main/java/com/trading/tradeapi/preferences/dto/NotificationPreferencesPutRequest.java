package com.trading.tradeapi.preferences.dto;

import java.util.List;

/**
 * A whole-record replace of the per-category delivery answers. The caller states each category
 * it wants changed; categories not named keep their stored value. A named category is always
 * written, so sending the full four with {@code INHERIT} resets the lot.
 */
public record NotificationPreferencesPutRequest(
        List<NotificationPreferenceRequest> preferences) {
}