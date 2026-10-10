package com.trading.tradeapi.preferences.entity;

import java.time.Instant;

/**
 * A row of {@code notification_preference}: how one notification category should be delivered
 * for one account. {@code INHERIT} means "do what the global {@code customer_preference} says",
 * which is also what an absent row means; {@code NONE} is an explicit refusal that beats an
 * inherited channel. Owned by the preferences module like {@link CustomerPreferenceRecord}.
 */
public class NotificationPreferenceRecord {

    private Long accountId;
    private String category;
    private String channel;
    private String contact;
    private Instant updatedAt;

    public NotificationPreferenceRecord() {}

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}