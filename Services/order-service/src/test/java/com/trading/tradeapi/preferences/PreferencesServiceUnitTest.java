package com.trading.tradeapi.preferences;

import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.exception.UnauthorizedAccountAccessException;
import com.trading.tradeapi.preferences.api.AlertChannel;
import com.trading.tradeapi.preferences.dto.PreferenceRequest;
import com.trading.tradeapi.preferences.dto.PreferenceResponse;
import com.trading.tradeapi.preferences.entity.CustomerPreferenceRecord;
import com.trading.tradeapi.preferences.mapper.PreferencesMapper;
import com.trading.tradeapi.preferences.service.PreferencesService;
import com.trading.tradeapi.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules that decide whether a body becomes a row, with the database replaced.
 *
 * <p>The table's check constraints say the same things this class says: a channel that
 * delivers has a destination and {@code NONE} does not. Every rule below is asserted here
 * because the point of it is the message and the {@code 422}, not the constraint - a constraint
 * violation would answer {@code 500}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Customer preference service rules")
class PreferencesServiceUnitTest {
    private static final long ACCOUNT = 1L;
    private static final long OTHER_USERS_ACCOUNT = 2L;
    private static final long NONEXISTENT = 999L;

    @Mock
    private PreferencesMapper preferencesMapper;

    @Mock
    private AccountService accountService;

    @InjectMocks
    private PreferencesService service;

    private CustomerPreferenceRecord stored;

    @BeforeEach
    void givenOwnAccount() {
        // Lenient: the one test that stops at the account lookup never reaches it.
        lenient().when(accountService.getAccountRecord(ACCOUNT)).thenReturn(account(ACCOUNT, 10L));
        stored = new CustomerPreferenceRecord();
        stored.setAccountId(ACCOUNT);
        stored.setUpdatedAt(Instant.parse("2026-10-06T09:00:00Z"));
    }

    private void assertRefused(Map<String, Object> fields, String expectedMessage) {
        assertThatThrownBy(() -> service.put(ACCOUNT, request(fields)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "VAL-422")
                .hasMessageContaining(expectedMessage);
        // Refused on the body: nothing was read and nothing was written.
        verify(preferencesMapper, never()).findByAccountId(anyLong());
        verify(preferencesMapper, never()).insert(anyLong(), any(), any(), any(), any());
        verify(preferencesMapper, never()).update(anyLong(), any(), any(), any(), any());
    }

    private static AccountRecord account(long accountId, long userId) {
        AccountRecord record = new AccountRecord();
        record.setAccountId(accountId);
        record.setUserId(userId);
        record.setCurrency("USD");
        return record;
    }

    private static PreferenceRequest request(Map<String, Object> fields) {
        Object channel = fields.getOrDefault("alertChannel", null);
        return new PreferenceRequest(
                (Long) fields.get("defaultAccountId"),
                channel == null ? null : AlertChannel.valueOf(channel.toString()),
                (String) fields.get("alertContact"),
                (String) fields.get("displayCurrency"));
    }

    @Nested
    @DisplayName("Reading")
    class Reading {

        @Test
        @DisplayName("No row reads back as NONE, not as an error")
        void unsetIsAValue() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(null);

            PreferenceResponse response = service.get(ACCOUNT);

            assertThat(response.accountId()).isEqualTo(ACCOUNT);
            assertThat(response.alertChannel()).isEqualTo(AlertChannel.NONE);
            assertThat(response.defaultAccountId()).isNull();
            assertThat(response.alertContact()).isNull();
            assertThat(response.displayCurrency()).isNull();
            assertThat(response.updatedAt()).isNull();
            verify(preferencesMapper, never()).insert(anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("An unknown account is 404 even when nothing is being written")
        void unknownAccountIsNotFoundOnRead() {
            when(accountService.getAccountRecord(NONEXISTENT)).thenThrow(new AccountNotFoundException());

            assertThatThrownBy(() -> service.get(NONEXISTENT))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasFieldOrPropertyWithValue("code", "ACC-404");
        }
    }

    @Nested
    @DisplayName("Writing")
    class Writing {

        @Test
        @DisplayName("The first save inserts and the second updates")
        void firstSaveInsertsSecondUpdates() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(null, stored);
            service.put(ACCOUNT, request(Map.of("alertChannel", "EMAIL", "alertContact", "a@b.co")));
            verify(preferencesMapper).insert(eq(ACCOUNT), eq(null), eq("EMAIL"), eq("a@b.co"), eq(null));

            CustomerPreferenceRecord existing = new CustomerPreferenceRecord();
            existing.setAccountId(ACCOUNT);
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(existing, stored);
            service.put(ACCOUNT, request(Map.of("alertChannel", "BOTH", "alertContact", "c@d.co")));

            verify(preferencesMapper).update(eq(ACCOUNT), eq(null), eq("BOTH"), eq("c@d.co"), eq(null));
            verify(preferencesMapper, times(1)).insert(anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("A saved preference carries the row's updatedAt")
        void responseCarriesUpdatedAt() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(null, stored);

            PreferenceResponse response =
                    service.put(ACCOUNT, request(Map.of("alertChannel", "NONE")));

            assertThat(response.updatedAt()).isEqualTo(stored.getUpdatedAt());
            assertThat(response.alertChannel()).isEqualTo(AlertChannel.NONE);
            assertThat(response.alertContact()).isNull();
        }

        @Test
        @DisplayName("A lowercase currency is stored as an ISO 4217 code")
        void currencyIsNormalisedToUpperCase() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(null, stored);

            service.put(ACCOUNT, request(Map.of(
                    "alertChannel", "PUSH",
                    "alertContact", "device-token-abc",
                    "displayCurrency", " usd ")));

            // In-app needs no destination: a stale one is dropped rather than stored.
            verify(preferencesMapper).insert(eq(ACCOUNT), eq(null), eq("PUSH"), eq(null), eq("USD"));
        }
    }

    @Nested
    @DisplayName("The rules the table would otherwise turn into a 500")
    class TheRulesTheTableWouldOtherwiseTurnIntoA500 {

        @Test
        @DisplayName("A channel with no destination is refused with VAL-422")
        void channelWithoutContactIsRefused() {
            assertRefused(Map.of("alertChannel", "EMAIL"), "An email address is required");
            assertRefused(Map.of("alertChannel", "BOTH"), "An email address is required");
        }

        @Test
        @DisplayName("NONE with a destination drops the destination")
        void noneWithContactDropsIt() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(null, stored);

            service.put(ACCOUNT, request(Map.of("alertChannel", "NONE", "alertContact", "a@b.co")));

            verify(preferencesMapper).insert(eq(ACCOUNT), eq(null), eq("NONE"), eq(null), eq(null));
        }

        @Test
        @DisplayName("A non-email contact for email is refused with VAL-422")
        void contactOnWrongChannelIsRefused() {
            assertRefused(Map.of("alertChannel", "EMAIL", "alertContact", "+91 98765 43210"),
                    "not an email address");
        }

        @Test
        @DisplayName("A currency that is not three letters is refused with VAL-422")
        void malformedCurrencyIsRefused() {
            assertRefused(Map.of(
                            "alertChannel", "PUSH",
                            "alertContact", "tok",
                            "displayCurrency", "DOLLARS"),
                    "three-letter ISO 4217");
        }
    }

    @Nested
    @DisplayName("The default account")
    class TheDefaultAccount {

        @Test
        @DisplayName("A default account that does not exist is VAL-422, not ACC-404")
        void unknownDefaultAccountIsVal422() {
            when(accountService.getAccountRecord(NONEXISTENT)).thenThrow(new AccountNotFoundException());

            assertThatThrownBy(() -> service.put(ACCOUNT, request(Map.of(
                    "defaultAccountId", NONEXISTENT,
                    "alertChannel", "NONE"))))
                    .isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("code", "VAL-422");
            verify(preferencesMapper, never()).insert(anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("A default account belonging to another user is ACC-403, and it is logged")
        void defaultAccountOfAnotherUserIsForbidden() {
            when(accountService.getAccountRecord(OTHER_USERS_ACCOUNT)).thenReturn(account(OTHER_USERS_ACCOUNT, 20L));

            assertThatThrownBy(() -> service.put(ACCOUNT, request(Map.of(
                    "defaultAccountId", OTHER_USERS_ACCOUNT,
                    "alertChannel", "NONE"))))
                    .isInstanceOf(UnauthorizedAccountAccessException.class)
                    .hasFieldOrPropertyWithValue("code", "ACC-403");
            verify(preferencesMapper, never()).insert(anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("No default account is allowed: null is not a probe")
        void nullDefaultAccountIsAllowed() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(null, stored);

            PreferenceResponse response = service.put(ACCOUNT, request(Map.of("alertChannel", "NONE")));

            assertThat(response.defaultAccountId()).isNull();
            verify(preferencesMapper).insert(eq(ACCOUNT), eq(null), eq("NONE"), eq(null), eq(null));
        }
    }

    @Nested
    @DisplayName("Theme")
    class Theme {

        @Test
        @DisplayName("Saves dark or light, inserting a row the first time, and refuses anything else")
        void savesTheTheme() {
            when(preferencesMapper.updateTheme(ACCOUNT, "light")).thenReturn(0);
            when(preferencesMapper.updateTheme(ACCOUNT, "dark")).thenReturn(1);
            stored.setTheme("light");
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(stored);

            assertThat(service.putTheme(ACCOUNT, " LIGHT ").theme()).isEqualTo("light");
            verify(preferencesMapper).insertTheme(ACCOUNT, "light");
            service.putTheme(ACCOUNT, "dark");
            verify(preferencesMapper, never()).insertTheme(ACCOUNT, "dark");

            assertThatThrownBy(() -> service.putTheme(ACCOUNT, "purple")).hasMessageContaining("dark or light");
            assertThatThrownBy(() -> service.putTheme(ACCOUNT, null)).hasMessageContaining("dark or light");
        }

        @Test
        @DisplayName("No stored theme reads as dark")
        void defaultsToDark() {
            when(preferencesMapper.findByAccountId(ACCOUNT)).thenReturn(stored);
            assertThat(service.get(ACCOUNT).theme()).isEqualTo("dark");
        }
    }
}
