package com.trading.tradeapi.preferences;

import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.preferences.api.AlertChannel;
import com.trading.tradeapi.preferences.dto.NotificationPreferenceEntryDto;
import com.trading.tradeapi.preferences.dto.NotificationPreferenceRequest;
import com.trading.tradeapi.preferences.dto.NotificationPreferencesPutRequest;
import com.trading.tradeapi.preferences.dto.NotificationPreferencesResponse;
import com.trading.tradeapi.preferences.entity.NotificationPreferenceRecord;
import com.trading.tradeapi.preferences.mapper.PreferencesMapper;
import com.trading.tradeapi.preferences.service.NotificationPreferenceService;
import com.trading.tradeapi.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-category delivery rules with the database replaced: category/channel/contact shape,
 * and the read-back that always answers the same four categories.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Notification preference service rules")
class NotificationPreferenceServiceUnitTest {

    private static final long ACCOUNT = 1L;

    @Mock
    private PreferencesMapper preferencesMapper;

    @Mock
    private AccountService accountService;

    @InjectMocks
    private NotificationPreferenceService service;

    @BeforeEach
    void givenOwnAccount() {
        lenient().when(accountService.getAccountRecord(ACCOUNT)).thenReturn(new AccountRecord());
    }

    @Test
    @DisplayName("Read always answers all three categories; unset ones read back as INHERIT")
    void unsetCategoriesReadBackAsInherit() {
        when(preferencesMapper.findNotificationPreferences(ACCOUNT)).thenReturn(List.of());

        NotificationPreferencesResponse response = service.get(ACCOUNT);

        assertThat(response.preferences()).hasSize(3);
        assertThat(response.preferences()).allSatisfy(entry -> {
            assertThat(entry.channel()).isEqualTo(AlertChannel.INHERIT);
            assertThat(entry.contact()).isNull();
        });
        assertThat(response.preferences()).extracting(NotificationPreferenceEntryDto::category)
                .containsExactly("ACCOUNT_RELATED", "ORDER_RELATED", "PRICE_RELATED");
    }

    @Test
    @DisplayName("The first save inserts; a second save for the same category updates")
    void firstSaveInsertsSecondUpdates() {
        when(preferencesMapper.updateNotificationPreference(ACCOUNT, "PRICE_RELATED", "EMAIL", "a@b.co"))
                .thenReturn(0);
        when(preferencesMapper.findNotificationPreferences(ACCOUNT)).thenReturn(List.of());

        service.put(ACCOUNT, put(entry("PRICE_RELATED", "EMAIL", "a@b.co")));

        verify(preferencesMapper).insertNotificationPreference(ACCOUNT, "PRICE_RELATED", "EMAIL", "a@b.co");

        when(preferencesMapper.updateNotificationPreference(ACCOUNT, "PRICE_RELATED", "BOTH", "c@d.co"))
                .thenReturn(1);
        when(preferencesMapper.findNotificationPreferences(ACCOUNT)).thenReturn(List.of());

        service.put(ACCOUNT, put(entry("PRICE_RELATED", "BOTH", "c@d.co")));

        verify(preferencesMapper).updateNotificationPreference(ACCOUNT, "PRICE_RELATED", "BOTH", "c@d.co");
    }

    @Test
    @DisplayName("A per-category save returns the stored contact")
    void saveReturnsStoredContact() {
        when(preferencesMapper.updateNotificationPreference(ACCOUNT, "ORDER_RELATED", "EMAIL", "a@b.co"))
                .thenReturn(0);
        NotificationPreferenceRecord row = new NotificationPreferenceRecord();
        row.setAccountId(ACCOUNT);
        row.setCategory("ORDER_RELATED");
        row.setChannel("EMAIL");
        row.setContact("a@b.co");
        row.setUpdatedAt(Instant.parse("2026-10-06T09:00:00Z"));
        when(preferencesMapper.findNotificationPreferences(ACCOUNT)).thenReturn(List.of(row));

        NotificationPreferencesResponse response =
                service.put(ACCOUNT, put(entry("ORDER_RELATED", "EMAIL", "a@b.co")));

        NotificationPreferenceEntryDto filled = response.preferences().stream()
                .filter(e -> e.category().equals("ORDER_RELATED")).findFirst().orElseThrow();
        assertThat(filled.channel()).isEqualTo(AlertChannel.EMAIL);
        assertThat(filled.contact()).isEqualTo("a@b.co");
        assertThat(filled.updatedAt()).isEqualTo(row.getUpdatedAt());
    }

    @Test
    @DisplayName("A lowercase category is stored as the canonical name")
    void categoryIsNormalisedToUpperCase() {
        when(preferencesMapper.updateNotificationPreference(ACCOUNT, "PRICE_RELATED", "EMAIL", "a@b.co"))
                .thenReturn(0);
        when(preferencesMapper.findNotificationPreferences(ACCOUNT)).thenReturn(List.of());

        service.put(ACCOUNT, put(entry(" price_related ", "EMAIL", "a@b.co")));

        verify(preferencesMapper).insertNotificationPreference(eq(ACCOUNT), eq("PRICE_RELATED"), eq("EMAIL"), eq("a@b.co"));
    }

    // ───── The rules the table would otherwise turn into a 500 ─────

    @Test
    @DisplayName("An unknown category is refused with VAL-422")
    void unknownCategoryIsRefused() {
        assertThatThrownBy(() -> service.put(ACCOUNT, put(entry("SOMEDAY", "EMAIL", "a@b.co"))))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "VAL-422");
        verify(preferencesMapper, never()).insertNotificationPreference(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("A delivering channel without a destination is refused with VAL-422")
    void deliveringChannelWithoutContactIsRefused() {
        assertThatThrownBy(() -> service.put(ACCOUNT, put(entry("PRICE_RELATED", "EMAIL", null))))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "VAL-422");
    }

    @Test
    @DisplayName("A channel that needs no destination drops a stale one instead of storing it")
    void nonEmailChannelsDropTheContact() {
        when(preferencesMapper.updateNotificationPreference(ACCOUNT, "PRICE_RELATED", "PUSH", null)).thenReturn(0);
        when(preferencesMapper.findNotificationPreferences(ACCOUNT)).thenReturn(List.of());

        service.put(ACCOUNT, put(entry("PRICE_RELATED", "PUSH", "a@b.co")));

        verify(preferencesMapper).insertNotificationPreference(ACCOUNT, "PRICE_RELATED", "PUSH", null);
    }

    @Test
    @DisplayName("Each notification type belongs to one category")
    void typesMapToCategories() {
        assertThat(NotificationPreferenceService.categoryOf("ORDER_FILLED")).isEqualTo("ORDER_RELATED");
        assertThat(NotificationPreferenceService.categoryOf("order_rejected")).isEqualTo("ORDER_RELATED");
        assertThat(NotificationPreferenceService.categoryOf("PRICE_ALERT")).isEqualTo("PRICE_RELATED");
        assertThat(NotificationPreferenceService.categoryOf("ACCOUNT_ACTIVATED")).isEqualTo("ACCOUNT_RELATED");
        assertThat(NotificationPreferenceService.categoryOf("PRICE_RELATED")).isEqualTo("PRICE_RELATED");
        assertThat(NotificationPreferenceService.categoryOf(null)).isEqualTo("ACCOUNT_RELATED");
    }

    @Test
    @DisplayName("A non-email contact for email is refused with VAL-422")
    void contactOnWrongChannelIsRefused() {
        assertThatThrownBy(() -> service.put(ACCOUNT, put(entry("PRICE_RELATED", "EMAIL", "+91 98765 43210"))))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "VAL-422");
    }

    @Test
    @DisplayName("An empty request is refused with VAL-422")
    void emptyRequestIsRefused() {
        assertThatThrownBy(() -> service.put(ACCOUNT, new NotificationPreferencesPutRequest(List.of())))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "VAL-422");
    }

    private static NotificationPreferenceRequest entry(String category, String channel, String contact) {
        return new NotificationPreferenceRequest(category, AlertChannel.valueOf(channel), contact);
    }

    private static NotificationPreferencesPutRequest put(NotificationPreferenceRequest... entries) {
        return new NotificationPreferencesPutRequest(List.of(entries));
    }

    @Nested
    @DisplayName("Missing fields")
    class MissingFields {

        @Test
        @DisplayName("A null request, a missing category, and both without an email are refused with VAL-422")
        void refused() {
            assertThatThrownBy(() -> service.put(ACCOUNT, null)).hasFieldOrPropertyWithValue("code", "VAL-422");
            assertThatThrownBy(() -> service.put(ACCOUNT, new NotificationPreferencesPutRequest(null)))
                    .hasFieldOrPropertyWithValue("code", "VAL-422");
            assertThatThrownBy(() -> service.put(ACCOUNT, put(new NotificationPreferenceRequest(null, AlertChannel.PUSH, null))))
                    .hasMessageContaining("category is required");
            assertThatThrownBy(() -> service.put(ACCOUNT, put(new NotificationPreferenceRequest(" ", AlertChannel.PUSH, null))))
                    .hasFieldOrPropertyWithValue("code", "VAL-422");
            assertThatThrownBy(() -> service.put(ACCOUNT, put(entry("PRICE_RELATED", "BOTH", null))))
                    .hasMessageContaining("email and in-app delivery");
        }

        @Test
        @DisplayName("A category with no channel is stored as NONE")
        void noChannelIsNone() {
            when(preferencesMapper.findNotificationPreferences(ACCOUNT)).thenReturn(List.of());
            service.put(ACCOUNT, put(new NotificationPreferenceRequest("ORDER_RELATED", null, null)));
            verify(preferencesMapper).updateNotificationPreference(ACCOUNT, "ORDER_RELATED", "NONE", null);
        }
    }
}
