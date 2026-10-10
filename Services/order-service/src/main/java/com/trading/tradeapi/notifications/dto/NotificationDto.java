package com.trading.tradeapi.notifications.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.trading.tradeapi.notifications.entity.NotificationRecord;

import java.time.Instant;

/**
 * The inbox item as a customer sees it. {@code read} is derived from {@code read_at} so the
 * wire has one source of truth for it.
 */
public record NotificationDto(
        Long notificationId,
        Long accountId,
        String category,
        String symbol,
        String title,
        String body,
        String channel,
        String status,
        boolean read,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt) {

    public static NotificationDto from(NotificationRecord record) {
        return new NotificationDto(
                record.getNotificationId(),
                record.getAccountId(),
                record.getCategory(),
                record.getSymbol(),
                record.getTitle(),
                record.getBody(),
                record.getChannel(),
                record.getStatus(),
                record.getReadAt() != null,
                record.getCreatedAt());
    }
}