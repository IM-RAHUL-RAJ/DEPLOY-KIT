package com.trading.tradeapi.preferences.service;

import com.trading.tradeapi.preferences.api.AlertChannel;
import com.trading.tradeapi.preferences.api.AlertChannelResolver;
import com.trading.tradeapi.preferences.api.ResolvedChannel;
import com.trading.tradeapi.preferences.entity.CustomerPreferenceRecord;
import com.trading.tradeapi.preferences.entity.NotificationPreferenceRecord;
import com.trading.tradeapi.preferences.mapper.PreferencesMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


/**
 * The one implementation of the seam. Read fresh on every call, so a customer changing their
 * mind takes effect on the next message rather than after a restart.
 */
@Component
public class PreferenceChannelResolver implements AlertChannelResolver {

    private static final Logger log = LoggerFactory.getLogger(PreferenceChannelResolver.class);

    private final PreferencesMapper preferencesMapper;

    public PreferenceChannelResolver(PreferencesMapper preferencesMapper) {
        this.preferencesMapper = preferencesMapper;
    }

    /** Message without a classification: the account-wide channel decides. */
    @Override
    @Transactional(readOnly = true)
    public ResolvedChannel resolveChannelForAccount(long accountId) {
        return resolveChannelForAccount(accountId, null);
    }

    /**
     * A classified message: the per-category row, when one exists, decides; otherwise the
     * account-wide channel does. {@code NONE} and {@code INHERIT} are both handled on the row so
     * the account-wide read is skipped for the common path where the customer set the category.
     *
     * <p>Never throws and never answers {@code null}. This is called from a Kafka consumer thread
     * with no HTTP request in flight; a preference read that fails has to leave the caller able
     * to decide what to do, not take the partition down with it. The failure is logged without
     * the contact detail, which is personal data.
     */
    @Override
    @Transactional(readOnly = true)
    public ResolvedChannel resolveChannelForAccount(long accountId, String category) {
        try {
            NotificationPreferenceRecord perCategory =
                    category == null ? null : preferencesMapper.findNotificationPreference(accountId,
                            NotificationPreferenceService.categoryOf(category));
            if (perCategory != null) {
                AlertChannel channel = AlertChannel.fromStorage(perCategory.getChannel());
                if (channel == AlertChannel.NONE) {
                    log.info("Notification preference suppressed for account {}: category {} is NONE",
                            accountId, perCategory.getCategory());
                    return ResolvedChannel.none();
                }
                if (channel != AlertChannel.INHERIT) {
                    return ResolvedChannel.of(channel, perCategory.getContact());
                }
                // INHERIT: fall through to the account-wide channel below.
            }

            CustomerPreferenceRecord record = preferencesMapper.findByAccountId(accountId);
            if (record == null) {
                return ResolvedChannel.none();
            }
            return ResolvedChannel.of(
                    AlertChannel.fromStorage(record.getAlertChannel()),
                    record.getAlertContact());
        } catch (RuntimeException e) {
            log.warn("Could not resolve alert channel for account {}: {}", accountId, e.getMessage());
            return ResolvedChannel.none();
        }
    }
}