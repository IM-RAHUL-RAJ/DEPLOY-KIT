package com.trading.tradeapi.admin;

import com.trading.tradeapi.dto.AdjustBalanceRequestDto;
import com.trading.tradeapi.dto.AdminAccountDto;
import com.trading.tradeapi.dto.AdminUserDto;
import com.trading.tradeapi.dto.ApproveUserRequestDto;
import com.trading.tradeapi.dto.SuspendUserRequestDto;
import com.trading.tradeapi.dto.CreateInstrumentRequestDto;
import com.trading.tradeapi.dto.InstrumentDto;
import com.trading.tradeapi.exception.AccountNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import com.trading.tradeapi.exception.UserNotFoundException;
import com.trading.tradeapi.mapper.AdminMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
public class AdminServiceApprovalTest {
    private static final String ADMIN_UUID = "33333333-3333-3333-3333-333333333333";
    private static final Long ACTOR_ID = 2L;

    @Mock
    private AdminMapper adminMapper;

    private AdminService service;

    @BeforeEach
    public void setup() {
        service = new AdminService(adminMapper);
    }

    private AdminUserDto user(String status, Long accountId, String userType) {
        return new AdminUserDto(
                7L, "uuid-7", "Priya", "Menon", "priya.menon", "priya.menon@example.com", userType,
                "+91", "9876543210", "India", "BALANCED", status, accountId, status, "INR",
                new BigDecimal("0.00"), null, null, null, null, true);
    }

    private AdminAccountDto account(Long accountId) {
        return new AdminAccountDto(accountId, 7L, "Priya Menon", "priya.menon", "client",
                "INR", BigDecimal.ZERO, "INACTIVE", 1L);
    }

    @Nested
    @DisplayName("Approving")
    class Approving {

        @Test
        @DisplayName("Approving a user activates their account in the same transaction")
        public void approveActivatesAccount() {
            // Staged, not a second override: the first read is the INACTIVE state
            // the gate checks, the second is what the transition returns.
            when(adminMapper.findUserById(7L))
                    .thenReturn(user("INACTIVE", 11L, "client"), user("ACTIVE", 11L, "client"));
            when(adminMapper.findUserIdByUuid(ADMIN_UUID)).thenReturn(ACTOR_ID);

            AdminUserDto result = service.approveUser(7L, ADMIN_UUID, new ApproveUserRequestDto(null));

            assertEquals("ACTIVE", result.status());
            verify(adminMapper).updateUserStatus(eq(7L), eq("ACTIVE"), eq(ACTOR_ID), any());
            // The account must move in the same transaction, or the user would be
            // ACTIVE against an INACTIVE account and still unable to trade.
            verify(adminMapper).updateAccountStatus(11L, "ACTIVE");
        }

        @Test
        @DisplayName("Approving credits nothing: the account opens at zero and is funded separately")
        public void approveDoesNotCreditBalance() {
            when(adminMapper.findUserById(7L)).thenReturn(user("INACTIVE", 11L, "client"));

            service.approveUser(7L, ADMIN_UUID, new ApproveUserRequestDto("looks good"));

            verify(adminMapper, never()).addBalanceIfNotOverdrawn(anyLong(), any());
            verify(adminMapper, never()).logBalanceAdjustment(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Approving writes an audit row naming the administrator who acted")
        public void approveLogsActor() {
            when(adminMapper.findUserById(7L)).thenReturn(user("INACTIVE", 11L, "client"));
            when(adminMapper.findUserIdByUuid(ADMIN_UUID)).thenReturn(ACTOR_ID);

            service.approveUser(7L, ADMIN_UUID, new ApproveUserRequestDto("cleared KYC"));

            verify(adminMapper).logStatusChange(7L, "INACTIVE", "ACTIVE", "cleared KYC", ACTOR_ID);
        }

        @Test
        @DisplayName("Approval is refused for a suspended user; reinstatement is the route back")
        public void approveRefusesSuspendedUser() {
            when(adminMapper.findUserById(7L)).thenReturn(user("SUSPENDED", 11L, "client"));

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> service.approveUser(7L, ADMIN_UUID, new ApproveUserRequestDto("x")));
            assertTrue(thrown.getMessage().contains("reinstate"));
        }
    }

    @Nested
    @DisplayName("Rejecting")
    class Rejecting {

        @Test
        @DisplayName("Rejecting requires a reason, because the applicant is told the reason")
        public void rejectWithoutReasonRefused() {
            when(adminMapper.findUserById(7L)).thenReturn(user("INACTIVE", 11L, "client"));

            assertThrows(IllegalArgumentException.class,
                    () -> service.rejectUser(7L, ADMIN_UUID, new ApproveUserRequestDto("  ")));
            verify(adminMapper, never()).updateUserStatus(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Rejecting is refused for an active user, who must be suspended instead")
        public void rejectRefusesActiveUser() {
            // Rejection decides an application. An ACTIVE user already has fills and
            // open orders, so closing their account out here would strand that history.
            when(adminMapper.findUserById(7L)).thenReturn(user("ACTIVE", 11L, "client"));

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> service.rejectUser(7L, ADMIN_UUID, new ApproveUserRequestDto("changed my mind")));
            assertTrue(thrown.getMessage().contains("suspend"));
            verify(adminMapper, never()).updateUserStatus(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Rejecting closes the account as well as the user")
        public void rejectSuspendsAccount() {
            when(adminMapper.findUserById(7L)).thenReturn(user("INACTIVE", 11L, "client"));
            when(adminMapper.findUserIdByUuid(ADMIN_UUID)).thenReturn(ACTOR_ID);

            service.rejectUser(7L, ADMIN_UUID, new ApproveUserRequestDto("documents unreadable"));

            verify(adminMapper).updateUserStatus(eq(7L), eq("REJECTED"), eq(ACTOR_ID), eq("documents unreadable"));
            verify(adminMapper).updateAccountStatus(11L, "REJECTED");
        }
    }

    @Nested
    @DisplayName("Suspending and reinstating")
    class SuspendingAndReinstating {

        @Test
        @DisplayName("Suspending a user suspends their account in the same transaction")
        public void suspendCascadesToAccount() {
            when(adminMapper.findUserById(7L)).thenReturn(user("ACTIVE", 11L, "client"));
            when(adminMapper.findUserIdByUuid(ADMIN_UUID)).thenReturn(ACTOR_ID);

            service.suspendUser(7L, ADMIN_UUID, new SuspendUserRequestDto("unusual activity"));

            verify(adminMapper).updateUserStatus(eq(7L), eq("SUSPENDED"), eq(ACTOR_ID), eq("unusual activity"));
            verify(adminMapper).updateAccountStatus(11L, "SUSPENDED");
            // A suspension must not move money.
            verify(adminMapper, never()).addBalanceIfNotOverdrawn(anyLong(), any());
        }

        @Test
        @DisplayName("Suspending a user who is not ACTIVE is refused as a no-op")
        public void suspendOnlyActiveUser() {
            when(adminMapper.findUserById(7L)).thenReturn(user("INACTIVE", 11L, "client"));

            assertThrows(IllegalArgumentException.class,
                    () -> service.suspendUser(7L, ADMIN_UUID, new SuspendUserRequestDto("because")));
        }

        @Test
        @DisplayName("Reinstate returns a suspended user and their account to ACTIVE")
        public void reinstateRestoresSuspendedUser() {
            when(adminMapper.findUserById(7L))
                    .thenReturn(user("SUSPENDED", 11L, "client"), user("ACTIVE", 11L, "client"));
            when(adminMapper.findUserIdByUuid(ADMIN_UUID)).thenReturn(ACTOR_ID);

            service.reinstateUser(7L, ADMIN_UUID, new SuspendUserRequestDto("cleared"));

            verify(adminMapper).updateUserStatus(eq(7L), eq("ACTIVE"), eq(ACTOR_ID), eq("cleared"));
            verify(adminMapper).updateAccountStatus(11L, "ACTIVE");
        }

        @Test
        @DisplayName("Reinstatement leaves the balance alone")
        public void reinstateDoesNotCreditBalance() {
            when(adminMapper.findUserById(7L)).thenReturn(user("SUSPENDED", 11L, "client"));

            service.reinstateUser(7L, ADMIN_UUID, new SuspendUserRequestDto("cleared"));

            verify(adminMapper, never()).addBalanceIfNotOverdrawn(anyLong(), any(BigDecimal.class));
        }

        @Test
        @DisplayName("Only a SUSPENDED user can be reinstated")
        public void reinstateRejectsOtherStatuses() {
            when(adminMapper.findUserById(7L)).thenReturn(user("ACTIVE", 11L, "client"));

            assertThrows(IllegalArgumentException.class,
                    () -> service.reinstateUser(7L, ADMIN_UUID, new SuspendUserRequestDto("why")));
        }
    }

    @Nested
    @DisplayName("Users and accounts that cannot be acted on")
    class UsersAndAccountsThatCannotBeActedOn {

        @Test
        @DisplayName("An unknown user id is a 404, not a silent success")
        public void unknownUserThrows() {
            when(adminMapper.findUserById(99L)).thenReturn(null);

            assertThrows(UserNotFoundException.class,
                    () -> service.approveUser(99L, ADMIN_UUID, new ApproveUserRequestDto("x")));
        }

        @Test
        @DisplayName("A user with no trading account cannot be approved")
        public void userWithoutAccountRefused() {
            when(adminMapper.findUserById(7L)).thenReturn(user("INACTIVE", null, "client"));

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> service.approveUser(7L, ADMIN_UUID, new ApproveUserRequestDto("x")));
            assertTrue(thrown.getMessage().contains("no trading account"));
        }

        @Test
        @DisplayName("An account cannot be set ACTIVE while its holder is still awaiting approval")
        public void accountStatusFollowsUser() {
            when(adminMapper.findAccountById(11L)).thenReturn(account(11L));
            when(adminMapper.findUserById(7L)).thenReturn(user("INACTIVE", 11L, "client"));

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> service.updateAccountStatus(11L, "ACTIVE"));
            assertTrue(thrown.getMessage().contains("approve"));
            verify(adminMapper, never()).updateAccountStatus(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("Balances")
    class Balances {

        @Test
        @DisplayName("A deposit is applied in one guarded statement and recorded with its reason")
        public void depositIsAuditedAndAtomic() {
            when(adminMapper.findAccountById(11L)).thenReturn(account(11L));
            when(adminMapper.findUserIdByUuid(ADMIN_UUID)).thenReturn(ACTOR_ID);
            when(adminMapper.addBalanceIfNotOverdrawn(11L, new BigDecimal("5000.00"))).thenReturn(1);

            service.adjustBalance(11L, new AdjustBalanceRequestDto(new BigDecimal("5000.00"), "initial funding"), ADMIN_UUID);

            verify(adminMapper).logBalanceAdjustment(11L, new BigDecimal("5000.00"), "initial funding", ACTOR_ID);
        }

        @Test
        @DisplayName("A withdrawal that would overdraw is refused and not recorded")
        public void overdrawRefused() {
            when(adminMapper.findAccountById(11L)).thenReturn(account(11L));
            when(adminMapper.addBalanceIfNotOverdrawn(11L, new BigDecimal("-1.00"))).thenReturn(0);

            assertThrows(IllegalArgumentException.class,
                    () -> service.adjustBalance(11L, new AdjustBalanceRequestDto(new BigDecimal("-1.00"), null), ADMIN_UUID));
            verify(adminMapper, never()).logBalanceAdjustment(anyLong(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Approval queue")
    class ApprovalQueue {

        @Test
        @DisplayName("The pending queue lists only users awaiting approval")
        public void pendingQueue() {
            when(adminMapper.findUsersByStatus("INACTIVE"))
                    .thenReturn(List.of(user("INACTIVE", 11L, "client")));

            assertEquals(1, service.getPendingUsers().size());
            assertTrue(service.getPendingUsers().get(0).isInactive());
        }
    }

    @Nested
    @DisplayName("Administration beyond approvals")
    class Administration {

        private AdminAccountDto account(Long userId, String status) {
            return new AdminAccountDto(5L, userId, "Priya Menon", "priya.menon", "client",
                    "INR", BigDecimal.ZERO, status, 1L);
        }

        @Test
        @DisplayName("Stats count everything and read no cash as zero")
        void stats() {
            when(adminMapper.sumCashBalance()).thenReturn(null);
            assertThat(service.getStats().totalCash()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Lists users, accounts, instruments, pending users and orders, bounding the order limit")
        void lists() {
            service.getUsers();
            service.getAccounts();
            service.getInstruments();
            service.countPendingUsers();
            service.getOrders("NEW", 1L, null);
            service.getOrders(null, null, 0);
            service.getOrders(null, null, 10_000);
            service.getOrders(null, null, 5);
            verify(adminMapper).findAllUsers();
            verify(adminMapper).findAllAccounts();
            verify(adminMapper).findAllInstruments();
            verify(adminMapper).countUsersByStatus("INACTIVE");
            verify(adminMapper).findAllOrders(null, null, 5);
        }

        @Test
        @DisplayName("Creates an instrument with defaults, or as stated, and refuses a duplicate")
        void createsInstruments() {
            InstrumentDto aapl = new InstrumentDto(1L, "AAPL", "AAPL", "EQUITY", "USD", "ACTIVE");
            InstrumentDto infy = new InstrumentDto(2L, "INFY", "INFY.NS", "ETF", "INR", "SUSPENDED");
            when(adminMapper.findAllInstruments()).thenReturn(List.of(aapl, infy));

            assertThat(service.createInstrument(new CreateInstrumentRequestDto(" aapl ", null, " ", null, ""))).isEqualTo(aapl);
            verify(adminMapper).insertInstrument("AAPL", "AAPL", "EQUITY", "USD", "ACTIVE");
            assertThat(service.createInstrument(new CreateInstrumentRequestDto("infy", "infy.ns", "etf", "inr", "suspended"))).isEqualTo(infy);

            when(adminMapper.insertInstrument(eq("MSFT"), any(), any(), any(), any())).thenThrow(new DuplicateKeyException("dup"));
            assertThatThrownBy(() -> service.createInstrument(new CreateInstrumentRequestDto("MSFT", null, null, null, null)))
                    .hasMessageContaining("already exists");
            assertThatThrownBy(() -> service.createInstrument(new CreateInstrumentRequestDto("TSLA", " ", null, " ", null)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Changes an instrument's status, refusing an unknown instrument or status")
        void instrumentStatus() {
            InstrumentDto aapl = new InstrumentDto(1L, "AAPL", "AAPL", "EQUITY", "USD", "DELISTED");
            when(adminMapper.updateInstrumentStatus(1L, "DELISTED")).thenReturn(1);
            when(adminMapper.findAllInstruments()).thenReturn(List.of(aapl));
            assertThat(service.updateInstrumentStatus(1L, " delisted ")).isEqualTo(aapl);

            assertThatThrownBy(() -> service.updateInstrumentStatus(9L, "ACTIVE")).hasMessageContaining("not found");
            lenient().when(adminMapper.updateInstrumentStatus(3L, "ACTIVE")).thenReturn(1);
            assertThatThrownBy(() -> service.updateInstrumentStatus(3L, "ACTIVE")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.updateInstrumentStatus(1L, "BOGUS")).hasMessageContaining("Unsupported");
            assertThatThrownBy(() -> service.updateInstrumentStatus(1L, " ")).hasMessageContaining("required");
            assertThatThrownBy(() -> service.updateInstrumentStatus(1L, null)).hasMessageContaining("required");
        }

        @Test
        @DisplayName("Refuses a non-client approval, a missing user id, and users with no account to act on")
        void userGuards() {
            when(adminMapper.findUserById(7L)).thenReturn(
                    user("INACTIVE", 5L, "admin"), user("SUSPENDED", null, "client"),
                    user("INACTIVE", null, "client"), user("ACTIVE", null, "client"));
            assertThatThrownBy(() -> service.approveUser(7L, ADMIN_UUID, null)).hasMessageContaining("client");
            assertThatThrownBy(() -> service.reinstateUser(7L, ADMIN_UUID, null)).hasMessageContaining("reinstate");
            assertThatThrownBy(() -> service.rejectUser(7L, ADMIN_UUID, new ApproveUserRequestDto("no"))).hasMessageContaining("reject");
            assertThatThrownBy(() -> service.suspendUser(7L, ADMIN_UUID, new SuspendUserRequestDto("x"))).hasMessageContaining("suspend");
            assertThatThrownBy(() -> service.approveUser(null, ADMIN_UUID, null)).hasMessageContaining("userId");
        }

        @Test
        @DisplayName("Reinstating without a reason uses the default; no actor is recorded as null")
        void reinstateDefaults() {
            when(adminMapper.findUserById(7L)).thenReturn(user("SUSPENDED", 5L, "client"));
            service.reinstateUser(7L, null, new SuspendUserRequestDto(" "));
            verify(adminMapper).updateUserStatus(7L, "ACTIVE", null, "Reinstated by administrator");
        }

        @Test
        @DisplayName("Account status: unknown or missing accounts and statuses are refused")
        void accountGuards() {
            assertThatThrownBy(() -> service.updateAccountStatus(null, "ACTIVE")).hasMessageContaining("accountId");
            assertThatThrownBy(() -> service.updateAccountStatus(99L, "ACTIVE")).isInstanceOf(AccountNotFoundException.class);
            when(adminMapper.findAccountById(5L)).thenReturn(account(7L, "ACTIVE"));
            assertThatThrownBy(() -> service.updateAccountStatus(5L, " ")).hasMessageContaining("required");
            assertThatThrownBy(() -> service.updateAccountStatus(5L, null)).hasMessageContaining("required");
            assertThatThrownBy(() -> service.updateAccountStatus(5L, "FROZEN")).hasMessageContaining("Unsupported");
            when(adminMapper.findUserById(7L)).thenReturn(null);
            assertThatThrownBy(() -> service.updateAccountStatus(5L, "ACTIVE")).hasMessageContaining("not ACTIVE");
        }

        @Test
        @DisplayName("An account suspended on its own is reported reinstated once active again; an orphan account just changes")
        void accountStatusChanges() {
            when(adminMapper.findAccountById(5L)).thenReturn(account(7L, "SUSPENDED"));
            when(adminMapper.findUserById(7L)).thenReturn(user("ACTIVE", 5L, "client"));
            AdminService.AccountStatusChange change = service.changeAccountStatus(5L, "ACTIVE", null, ADMIN_UUID);
            assertThat(change.notice()).isEqualTo(AdminService.AccountNotice.REINSTATED);
            assertThat(change.reason()).isEqualTo("Reinstated by administrator");

            when(adminMapper.findAccountById(5L)).thenReturn(account(null, "ACTIVE"));
            AdminService.AccountStatusChange orphan = service.changeAccountStatus(5L, "closed", " ", ADMIN_UUID);
            assertThat(orphan.notice()).isEqualTo(AdminService.AccountNotice.NONE);
            assertThat(orphan.owner()).isNull();
        }

        @Test
        @DisplayName("Balance adjustments must be non-zero with two decimals; a withdrawal gets its own default reason")
        void balanceRules() {
            when(adminMapper.findAccountById(5L)).thenReturn(account(7L, "ACTIVE"));
            assertThatThrownBy(() -> service.adjustBalance(5L, new AdjustBalanceRequestDto(null, null), ADMIN_UUID)).hasMessageContaining("non-zero");
            assertThatThrownBy(() -> service.adjustBalance(5L, new AdjustBalanceRequestDto(BigDecimal.ZERO, null), ADMIN_UUID)).hasMessageContaining("non-zero");
            assertThatThrownBy(() -> service.adjustBalance(5L, new AdjustBalanceRequestDto(new BigDecimal("1.234"), null), ADMIN_UUID)).hasMessageContaining("2 decimal");

            when(adminMapper.addBalanceIfNotOverdrawn(5L, new BigDecimal("-10"))).thenReturn(1);
            service.adjustBalance(5L, new AdjustBalanceRequestDto(new BigDecimal("-10"), " "), null);
            verify(adminMapper).logBalanceAdjustment(5L, new BigDecimal("-10"), "Withdrawal by administrator", null);
        }
    }
}
