package com.trading.tradeapi.preferences.service;

import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.preferences.api.AlertChannel;
import com.trading.tradeapi.preferences.dto.NotificationPreferenceEntryDto;
import com.trading.tradeapi.preferences.dto.NotificationPreferenceRequest;
import com.trading.tradeapi.preferences.dto.NotificationPreferencesPutRequest;
import com.trading.tradeapi.preferences.dto.NotificationPreferencesResponse;
import com.trading.tradeapi.preferences.entity.NotificationPreferenceRecord;
import com.trading.tradeapi.preferences.mapper.PreferencesMapper;
import com.trading.tradeapi.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Owns {@code notification_preference}: which category of notification goes where. The four
 * categories match the {@code notification.category} check constraint so a preference can never
 * name a message type that does not exist.
 *
 * <p>The global {@code customer_preference.alert_channel} stays the account-wide default. A
 * category with no row, or a row whose channel is {@code INHERIT}, follows it; {@code NONE} on a
 * row is an explicit refusal that overrides an inherited channel, because a customer saying "no
 * email for order rejections" expects that to win.
 */
@Service
public class NotificationPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(NotificationPreferenceService.class);

    /** The three categories, in the order every response returns them. */
    public static final List<String> CATEGORIES = List.of(
            "ACCOUNT_RELATED", "ORDER_RELATED", "PRICE_RELATED");

    /**
     * The category a notification type belongs to: every {@code ORDER_*} type is order related,
     * every {@code PRICE_*} type price related, everything else account related. A preference is
     * set per category, and every type in it is delivered the same way.
     */
    public static String categoryOf(String notificationType) {
        String t = notificationType == null ? "" : notificationType.trim().toUpperCase(Locale.ROOT);
        if (CATEGORIES.contains(t)) {
            return t;
        }
        if (t.startsWith("ORDER_")) {
            return "ORDER_RELATED";
        }
        if (t.startsWith("PRICE_")) {
            return "PRICE_RELATED";
        }
        return "ACCOUNT_RELATED";
    }

    private static final Set<String> KNOWN_CATEGORIES = Set.copyOf(CATEGORIES);

    /** Deliberately loose: it has to reject obvious rubbish, not win an RFC argument. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final PreferencesMapper preferencesMapper;
    private final AccountService accountService;

    public NotificationPreferenceService(PreferencesMapper preferencesMapper,
                                         AccountService accountService) {
        this.preferencesMapper = preferencesMapper;
        this.accountService = accountService;
    }

    /**
     * Reads the per-category answers for an account. Every one of the three categories is present:
     * a category the account never configured is reported as {@code INHERIT} with no contact.
     */
    @Transactional(readOnly = true)
    public NotificationPreferencesResponse get(long accountId) {
        accountService.getAccountRecord(accountId);
        List<NotificationPreferenceRecord> stored = preferencesMapper.findNotificationPreferences(accountId);

        List<NotificationPreferenceEntryDto> entries = new ArrayList<>();
        for (String category : CATEGORIES) {
            NotificationPreferenceRecord row = stored.stream()
                    .filter(r -> category.equals(r.getCategory()))
                    .findFirst()
                    .orElse(null);
            entries.add(toEntry(category, row));
        }
        return new NotificationPreferencesResponse(accountId, entries);
    }

    /** Whole-record replace over the named categories; unnamed categories keep their stored value. */
    @Transactional
    public NotificationPreferencesResponse put(long accountId, NotificationPreferencesPutRequest request) {
        accountService.getAccountRecord(accountId);

        if (request == null || request.preferences() == null || request.preferences().isEmpty()) {
            throw new DomainException("VAL-422", "preferences must name at least one category",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        for (NotificationPreferenceRequest entry : request.preferences()) {
            String category = normaliseCategory(entry.category());
            AlertChannel channel = entry.channel() == null ? AlertChannel.NONE : entry.channel();
            String contact = normaliseContact(channel, entry.contact());

            int written = preferencesMapper.updateNotificationPreference(accountId, category, channel.name(), contact);
            if (written == 0) {
                preferencesMapper.insertNotificationPreference(accountId, category, channel.name(), contact);
            }
            log.info("Notification preference saved for account {}: category={} channel={}",
                    accountId, category, channel);
        }
        return get(accountId);
    }

    private String normaliseCategory(String category) {
        if (category == null) {
            throw new DomainException("VAL-422", "category is required",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String upper = category.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_CATEGORIES.contains(upper)) {
            throw new DomainException("VAL-422",
                    "category must be one of " + CATEGORIES,
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return upper;
    }

    /**
     * Delivery is in-app ({@code PUSH}), email, or both. In-app needs no destination, so any
     * contact sent with it is dropped; email and both need an email address.
     */
    private String normaliseContact(AlertChannel channel, String contact) {
        String trimmed = contact == null ? null : contact.trim();
        boolean needsEmail = channel == AlertChannel.EMAIL || channel == AlertChannel.BOTH;
        if (!needsEmail) {
            return null;
        }
        if (trimmed == null || trimmed.isEmpty()) {
            throw new DomainException("VAL-422", "An email address is required for " + label(channel),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!EMAIL.matcher(trimmed).matches()) {
            throw new DomainException("VAL-422", "That is not an email address",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return trimmed;
    }

    private static String label(AlertChannel channel) {
        return channel == AlertChannel.BOTH ? "email and in-app delivery" : "email delivery";
    }

    private NotificationPreferenceEntryDto toEntry(String category, NotificationPreferenceRecord row) {
        if (row == null) {
            return new NotificationPreferenceEntryDto(category, AlertChannel.INHERIT, null, null);
        }
        return new NotificationPreferenceEntryDto(
                category,
                AlertChannel.fromStorage(row.getChannel()),
                row.getContact(),
                row.getUpdatedAt());
    }
}