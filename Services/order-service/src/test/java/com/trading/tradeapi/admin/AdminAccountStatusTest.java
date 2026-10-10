package com.trading.tradeapi.admin;

import com.trading.tradeapi.dto.AdminAccountDto;
import com.trading.tradeapi.dto.AdminUserDto;
import com.trading.tradeapi.mapper.AdminMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A status change on the Accounts page goes through the user lifecycle, so the
 * holder is suspended or reinstated with the account and is told about it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAccountStatusTest {

    private static final String ADMIN = "admin-uuid";

    @Mock
    private AdminMapper adminMapper;

    private AdminService service;

    @BeforeEach
    void setUp() {
        service = new AdminService(adminMapper);
        when(adminMapper.findUserIdByUuid(ADMIN)).thenReturn(2L);
    }

    private static AdminUserDto user(String status) {
        return new AdminUserDto(7L, "uuid-7", "Priya", "Menon", "priya", "priya@example.com", "client",
                "+91", "98", "India", "BALANCED", status, 11L, status, "INR", BigDecimal.TEN,
                null, null, null, null, null);
    }

    private static AdminAccountDto account(String status) {
        return new AdminAccountDto(11L, 7L, "Priya Menon", "priya", "client", "INR", BigDecimal.TEN, status, 1L);
    }

    @Test
    @DisplayName("suspending an active holder's account suspends the user too, with the reason, and owes a notice")
    void suspendGoesThroughTheUser() {
        when(adminMapper.findAccountById(11L)).thenReturn(account("ACTIVE"), account("SUSPENDED"));
        when(adminMapper.findUserById(7L)).thenReturn(user("ACTIVE"), user("ACTIVE"), user("SUSPENDED"));

        AdminService.AccountStatusChange change = service.changeAccountStatus(11L, "suspended", " Unusual activity ", ADMIN);

        assertThat(change.notice()).isEqualTo(AdminService.AccountNotice.SUSPENDED);
        assertThat(change.reason()).isEqualTo("Unusual activity");
        assertThat(change.owner().status()).isEqualTo("SUSPENDED");
        verify(adminMapper).updateUserStatus(eq(7L), eq("SUSPENDED"), eq(2L), eq("Unusual activity"));
        verify(adminMapper).logStatusChange(7L, "ACTIVE", "SUSPENDED", "Unusual activity", 2L);
        verify(adminMapper).updateAccountStatus(11L, "SUSPENDED");
    }

    @Test
    @DisplayName("a suspension without a reason gets a default one for the email")
    void suspendDefaultsReason() {
        when(adminMapper.findAccountById(11L)).thenReturn(account("ACTIVE"));
        when(adminMapper.findUserById(7L)).thenReturn(user("ACTIVE"));

        AdminService.AccountStatusChange change = service.changeAccountStatus(11L, "SUSPENDED", "  ", ADMIN);

        assertThat(change.reason()).isEqualTo("Suspended by an administrator");
    }

    @Test
    @DisplayName("activating a suspended holder's account reinstates the user and owes a notice")
    void activateReinstatesTheUser() {
        when(adminMapper.findAccountById(11L)).thenReturn(account("SUSPENDED"));
        when(adminMapper.findUserById(7L)).thenReturn(user("SUSPENDED"), user("SUSPENDED"), user("ACTIVE"));

        AdminService.AccountStatusChange change = service.changeAccountStatus(11L, "ACTIVE", null, ADMIN);

        assertThat(change.notice()).isEqualTo(AdminService.AccountNotice.REINSTATED);
        assertThat(change.reason()).isEqualTo("Reinstated by administrator");
        verify(adminMapper).updateUserStatus(eq(7L), eq("ACTIVE"), eq(2L), any());
        verify(adminMapper).updateAccountStatus(11L, "ACTIVE");
    }

    @Test
    @DisplayName("an account suspended on its own (older data) is restored and the active holder is told")
    void driftedAccountIsRestoredWithNotice() {
        when(adminMapper.findAccountById(11L)).thenReturn(account("SUSPENDED"), account("ACTIVE"));
        when(adminMapper.findUserById(7L)).thenReturn(user("ACTIVE"));

        AdminService.AccountStatusChange change = service.changeAccountStatus(11L, "ACTIVE", null, ADMIN);

        assertThat(change.notice()).isEqualTo(AdminService.AccountNotice.REINSTATED);
        verify(adminMapper).updateAccountStatus(11L, "ACTIVE");
        verify(adminMapper, never()).updateUserStatus(any(), any(), any(), any());
    }

    @Test
    @DisplayName("closing an account changes only the account and owes no notice")
    void closeOwesNoNotice() {
        when(adminMapper.findAccountById(11L)).thenReturn(account("ACTIVE"), account("CLOSED"));
        when(adminMapper.findUserById(7L)).thenReturn(user("ACTIVE"));

        AdminService.AccountStatusChange change = service.changeAccountStatus(11L, "CLOSED", null, ADMIN);

        assertThat(change.notice()).isEqualTo(AdminService.AccountNotice.NONE);
        assertThat(change.account().status()).isEqualTo("CLOSED");
        verify(adminMapper, never()).updateUserStatus(any(), any(), any(), any());
    }

    @Test
    @DisplayName("suspending an account whose holder is not active changes only the account")
    void inactiveHolderOnlyAccount() {
        when(adminMapper.findAccountById(11L)).thenReturn(account("INACTIVE"));
        when(adminMapper.findUserById(7L)).thenReturn(user("INACTIVE"));

        AdminService.AccountStatusChange change = service.changeAccountStatus(11L, "SUSPENDED", "x", ADMIN);

        assertThat(change.notice()).isEqualTo(AdminService.AccountNotice.NONE);
        verify(adminMapper).updateAccountStatus(11L, "SUSPENDED");
        verify(adminMapper, never()).updateUserStatus(any(), any(), any(), any());
    }
}
