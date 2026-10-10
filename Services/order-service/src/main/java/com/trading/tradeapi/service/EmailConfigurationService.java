package com.trading.tradeapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The SMTP settings, cleaned of the quoting a shared .env file carries.
 *
 * <p>sprint9/.env is read by every service. The auth service loads it with dotenv,
 * which strips surrounding quotes; this service imports it as a .properties file,
 * which does not. Left alone, {@code SMTP_FROM="Akatsuki <no-reply@example.com>"}
 * arrives here with its quotes, JavaMail reads the whole value as a display name
 * with no address, every send throws, and no account notice is ever delivered.
 * Each value is therefore unquoted before use, and a Gmail app password, which
 * Google displays in groups of four, loses its spaces.
 */
@Component
public class EmailConfigurationService {

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.from:}")
    private String fromAddress;

    @Value("${spring.mail.username:}")
    private String username;

    /** True when there is a host to send through and an address to send from. */
    public boolean isConfigured() {
        return !unquote(mailHost).isBlank() && !getFromAddress().isBlank();
    }

    /**
     * The sender, e.g. {@code Akatsuki <no-reply@example.com>}. Falls back to the
     * SMTP username when no From is set, which is what Gmail sends as anyway.
     */
    public String getFromAddress() {
        String from = unquote(fromAddress);
        return from.isBlank() ? unquote(username) : from;
    }

    /** Strips whitespace and one pair of matching surrounding quotes. */
    public static String unquote(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    /** An app password as Google shows it ("abcd efgh ...") is sent without the spaces. */
    public static String normalisePassword(String value) {
        return unquote(value).replaceAll("\\s+", "");
    }
}
