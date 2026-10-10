package com.trading.tradeapi.admin;

import com.trading.tradeapi.dto.AdjustBalanceRequestDto;
import com.trading.tradeapi.dto.AdminUserDto;
import com.trading.tradeapi.dto.ApproveUserRequestDto;
import com.trading.tradeapi.dto.CreateInstrumentRequestDto;
import com.trading.tradeapi.dto.SuspendUserRequestDto;
import com.trading.tradeapi.dto.UpdateStatusRequestDto;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The admin routes without HTTP: each one checks the caller is an admin, then delegates. */
@DisplayName("AdminController")
class AdminControllerUnitTest {

    private static final String ACTOR = "admin-uuid";

    private AdminService service;
    private AdminNotifications notifications;
    private AdminController controller;
    private final HttpServletRequest request = new MockHttpServletRequest();
    private final AdminUserDto user = mock(AdminUserDto.class);

    @BeforeEach
    void setUp() {
        service = mock(AdminService.class);
        notifications = mock(AdminNotifications.class);
        AdminAuthorizer authorizer = mock(AdminAuthorizer.class);
        when(authorizer.requireAdmin(request)).thenReturn(ACTOR);
        controller = new AdminController(service, authorizer, notifications);
    }

    @Nested
    @DisplayName("Reads")
    class Reads {

        @Test
        @DisplayName("Stats, pending users, accounts, orders and instruments come straight from the service")
        void delegates() {
            assertThat(controller.getStats(request).getStatusCode()).isEqualTo(HttpStatus.OK);
            controller.getPendingUsers(request);
            controller.getAccounts(request);
            controller.getOrders("NEW", 1L, 10, request);
            controller.getInstruments(request);
            verify(service).getStats();
            verify(service).getPendingUsers();
            verify(service).getAccounts();
            verify(service).getOrders("NEW", 1L, 10);
            verify(service).getInstruments();
        }
    }

    @Nested
    @DisplayName("Decisions")
    class Decisions {

        @Test
        @DisplayName("Reinstating tells the user, with the default reason when none is given")
        void reinstateReason() {
            when(service.reinstateUser(any(), any(), any())).thenReturn(user);
            controller.reinstateUser(7L, null, request);
            controller.reinstateUser(7L, new SuspendUserRequestDto(null), request);
            controller.reinstateUser(7L, new SuspendUserRequestDto("  "), request);
            controller.reinstateUser(7L, new SuspendUserRequestDto(" back in good standing "), request);
            verify(notifications, org.mockito.Mockito.times(3)).reinstated(user, "Reinstated by administrator");
            verify(notifications).reinstated(user, "back in good standing");
        }

        @Test
        @DisplayName("Rejecting, funding and instrument changes pass the acting admin through")
        void delegatesWithActor() {
            ApproveUserRequestDto reject = new ApproveUserRequestDto("incomplete");
            AdjustBalanceRequestDto deposit = new AdjustBalanceRequestDto(new BigDecimal("100"), "deposit");
            CreateInstrumentRequestDto instrument = new CreateInstrumentRequestDto("AAPL", null, null, null, null);
            UpdateStatusRequestDto status = mock(UpdateStatusRequestDto.class);
            when(status.status()).thenReturn("DELISTED");

            controller.rejectUser(7L, reject, request);
            controller.adjustBalance(5L, deposit, request);
            assertThat(controller.createInstrument(instrument, request).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            controller.updateInstrumentStatus(3L, status, request);

            verify(service).rejectUser(7L, ACTOR, reject);
            verify(service).adjustBalance(5L, deposit, ACTOR);
            verify(service).createInstrument(instrument);
            verify(service).updateInstrumentStatus(3L, "DELISTED");
        }
    }
}
