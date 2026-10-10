package com.trading.tradeapi.preferences;

import com.trading.tradeapi.preferences.api.AlertChannel;
import com.trading.tradeapi.preferences.api.ResolvedChannel;
import com.trading.tradeapi.preferences.entity.CustomerPreferenceRecord;
import com.trading.tradeapi.preferences.entity.NotificationPreferenceRecord;
import com.trading.tradeapi.preferences.mapper.PreferencesMapper;
import com.trading.tradeapi.preferences.service.PreferenceChannelResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * The contract the notifications module relies on, asserted from the caller's side: this
 * object is called from a Kafka consumer thread with no HTTP request in flight, so "it always
 * answers and never throws" is the whole of the interface's value.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Alert channel resolver contract")
class PreferenceChannelResolverUnitTest {

    private static final long ACCOUNT = 1L;

    @Mock
    private PreferencesMapper preferencesMapper;

    @InjectMocks
    private PreferenceChannelResolver resolver;

    @Test
    @DisplayName("No preference answers a value, never null")
    void noPreferenceIsNoneNotNull() {
        when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(null);

        ResolvedChannel resolved = resolver.resolveChannelForAccount(ACCOUNT);

        assertThat(resolved).isNotNull();
        assertThat(resolved.deliverable()).isFalse();
        assertThat(resolved.channel()).isEqualTo(AlertChannel.NONE);
        assertThat(resolved.contact()).isNull();
        assertThat(ResolvedChannel.none()).isEqualTo(resolved);
    }

    @Test
    @DisplayName("A stored NONE is distinguishable from no answer only by the caller's own state")
    void storedNoneIsNotDeliverable() {
        when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(record("NONE", null));

        assertThat(resolver.resolveChannelForAccount(ACCOUNT).deliverable()).isFalse();
    }

    @Test
    @DisplayName("A stored channel answers with its destination")
    void storedChannelIsDeliverable() {
        when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(record("EMAIL", "alex@example.com"));

        ResolvedChannel resolved = resolver.resolveChannelForAccount(ACCOUNT);

        assertThat(resolved.deliverable()).isTrue();
        assertThat(resolved.channel()).isEqualTo(AlertChannel.EMAIL);
        assertThat(resolved.contact()).isEqualTo("alex@example.com");
    }

    @Test
    @DisplayName("A blank destination is not deliverable even on a channel that takes one")
    void blankContactIsNotDeliverable() {
        when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(record("EMAIL", "   "));

        assertThat(resolver.resolveChannelForAccount(ACCOUNT).deliverable()).isFalse();
    }

    @Test
    @DisplayName("A failed read answers rather than taking the consumer thread down")
    void aFailedReadStillAnswers() {
        when(preferencesMapper.findByAccountId(ACCOUNT)).thenThrow(new IllegalStateException("connection gone"));

        assertThatCode(() -> resolver.resolveChannelForAccount(ACCOUNT))
                .doesNotThrowAnyException();
        assertThat(resolver.resolveChannelForAccount(ACCOUNT)).isEqualTo(ResolvedChannel.none());
    }

    @Test
    @DisplayName("An unknown account is an answer, not an error")
    void unknownAccountIsAnAnswer() {
        when(preferencesMapper.findByAccountId(999L)).thenReturn(null);

        assertThat(resolver.resolveChannelForAccount(999L)).isEqualTo(ResolvedChannel.none());
    }

    @Test
    @DisplayName("A per-category channel wins over the account-wide channel")
    void perCategoryChannelWins() {
        when(preferencesMapper.findNotificationPreference(ACCOUNT, "PRICE_RELATED"))
                .thenReturn(perCategory("PRICE_RELATED", "EMAIL", "alerts@example.com"));

        ResolvedChannel resolved = resolver.resolveChannelForAccount(ACCOUNT, "PRICE_ALERT");

        assertThat(resolved.deliverable()).isTrue();
        assertThat(resolved.channel()).isEqualTo(AlertChannel.EMAIL);
        assertThat(resolved.contact()).isEqualTo("alerts@example.com");
    }

    @Test
    @DisplayName("A per-category NONE overrides an account-wide channel that would send")
    void perCategoryNoneOverridesGlobal() {
        when(preferencesMapper.findNotificationPreference(ACCOUNT, "ORDER_RELATED"))
                .thenReturn(perCategory("ORDER_RELATED", "NONE", null));

        // The per-category NONE answers without even reading the account-wide row.
        assertThat(resolver.resolveChannelForAccount(ACCOUNT, "ORDER_REJECTED"))
                .isEqualTo(ResolvedChannel.none());
    }

    @Test
    @DisplayName("A per-category INHERIT falls back to the account-wide channel")
    void perCategoryInheritFallsBackToGlobal() {
        when(preferencesMapper.findNotificationPreference(ACCOUNT, "ORDER_RELATED"))
                .thenReturn(perCategory("ORDER_RELATED", "INHERIT", null));
        when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(record("PUSH", null));

        ResolvedChannel resolved = resolver.resolveChannelForAccount(ACCOUNT, "ORDER_FILLED");

        assertThat(resolved.deliverable()).isTrue();
        assertThat(resolved.channel()).isEqualTo(AlertChannel.PUSH);
        assertThat(resolved.contact()).isNull();
    }

    @Test
    @DisplayName("An unknown type counts as account-related and falls back to the account-wide channel")
    void unknownCategoryFallsBackToGlobal() {
        when(preferencesMapper.findNotificationPreference(ACCOUNT, "ACCOUNT_RELATED"))
                .thenReturn(null);
        when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(record("EMAIL", "alex@example.com"));

        assertThat(resolver.resolveChannelForAccount(ACCOUNT, "FUTURE_THING").deliverable()).isTrue();
    }

    private static CustomerPreferenceRecord record(String channel, String contact) {
        CustomerPreferenceRecord record = new CustomerPreferenceRecord();
        record.setAccountId(ACCOUNT);
        record.setAlertChannel(channel);
        record.setAlertContact(contact);
        return record;
    }

    private static NotificationPreferenceRecord perCategory(
            String category, String channel, String contact) {
        NotificationPreferenceRecord row = new NotificationPreferenceRecord();
        row.setAccountId(ACCOUNT);
        row.setCategory(category);
        row.setChannel(channel);
        row.setContact(contact);
        return row;
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Channel values")
    class ChannelValues {

        @Test
        @DisplayName("Only in-app is deliverable without a destination; no channel, INHERIT and a blank contact are none")
        void resolvedChannelRules() {
            assertThat(ResolvedChannel.of(null, "a@b.co")).isEqualTo(ResolvedChannel.none());
            assertThat(ResolvedChannel.of(AlertChannel.INHERIT, "a@b.co")).isEqualTo(ResolvedChannel.none());
            assertThat(ResolvedChannel.of(AlertChannel.EMAIL, " ")).isEqualTo(ResolvedChannel.none());
            assertThat(ResolvedChannel.of(AlertChannel.PUSH, null).deliverable()).isTrue();
            assertThat(ResolvedChannel.of(AlertChannel.BOTH, "a@b.co").deliverable()).isTrue();
            assertThat(new ResolvedChannel(AlertChannel.EMAIL, null).deliverable()).isFalse();
            assertThat(new ResolvedChannel(AlertChannel.EMAIL, " ").deliverable()).isFalse();
            assertThat(new ResolvedChannel(AlertChannel.NONE, "a@b.co").deliverable()).isFalse();
        }

        @Test
        @DisplayName("A stored value that is not a channel reads as NONE")
        void fromStorage() {
            assertThat(AlertChannel.fromStorage("EMAIL")).isEqualTo(AlertChannel.EMAIL);
            assertThat(AlertChannel.fromStorage("PIGEON")).isEqualTo(AlertChannel.NONE);
            assertThat(AlertChannel.fromStorage(null)).isEqualTo(AlertChannel.NONE);
        }
    }
}
