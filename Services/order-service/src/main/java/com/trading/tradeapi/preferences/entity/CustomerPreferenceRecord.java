package com.trading.tradeapi.preferences.entity;

import java.time.Instant;

/**
 * A row of {@code customer_preference}. Owned by the preferences module; no other module
 * reads this class, let alone the table behind it.
 */
public class CustomerPreferenceRecord {

    private Long accountId;
    private Long defaultAccountId;
    private String alertChannel;
    private String alertContact;
    private String displayCurrency;
    private String theme;
    private Instant updatedAt;

    public CustomerPreferenceRecord() {}

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public Long getDefaultAccountId() { return defaultAccountId; }
    public void setDefaultAccountId(Long defaultAccountId) { this.defaultAccountId = defaultAccountId; }

    public String getAlertChannel() { return alertChannel; }
    public void setAlertChannel(String alertChannel) { this.alertChannel = alertChannel; }

    public String getAlertContact() { return alertContact; }
    public void setAlertContact(String alertContact) { this.alertContact = alertContact; }

    public String getDisplayCurrency() { return displayCurrency; }
    public void setDisplayCurrency(String displayCurrency) { this.displayCurrency = displayCurrency; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }
}
