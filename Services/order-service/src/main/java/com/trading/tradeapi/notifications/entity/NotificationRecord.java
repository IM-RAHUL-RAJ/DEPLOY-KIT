package com.trading.tradeapi.notifications.entity;

import java.time.Instant;

/**
 * One row of {@code notification}: one order lifecycle outcome or one crossed price alert.
 * The outbound attempt is a column of the row, never just a log line.
 */
public class NotificationRecord {

    private Long notificationId;
    private Long accountId;
    private String eventId;
    private String category;
    private String symbol;
    private String title;
    private String body;
    private String channel;
    private String status;
    private Instant readAt;
    private Instant createdAt;

    public NotificationRecord() {
    }

    public NotificationRecord(Long accountId, String eventId, String category, String symbol,
                              String title, String body, String channel) {
        this.accountId = accountId;
        this.eventId = eventId;
        this.category = category;
        this.symbol = symbol;
        this.title = title;
        this.body = body;
        this.channel = channel;
        this.status = "QUEUED";
    }

    public Long getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(Long notificationId) {
        this.notificationId = notificationId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}