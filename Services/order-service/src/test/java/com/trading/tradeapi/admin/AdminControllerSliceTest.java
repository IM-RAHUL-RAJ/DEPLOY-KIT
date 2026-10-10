package com.trading.tradeapi.admin;

import com.trading.tradeapi.dto.AdminUserDto;
import com.trading.tradeapi.dto.SuspendUserRequestDto;
import com.trading.tradeapi.exception.GlobalExceptionHandler;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer integration for the admin API: routing, JSON, bean validation, the
 * real {@link AdminAuthorizer}, the real {@link AdminNotifications} and the error
 * envelope, with only the service and the mail sender replaced.
 */
@WebMvcTest(value = AdminController.class,
        excludeAutoConfiguration = {
                MybatisAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class
        })
@Import({GlobalExceptionHandler.class, AdminAuthorizer.class, AdminNotifications.class})
class AdminControllerSliceTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminService adminService;
    @MockitoBean
    private EmailService emailService;
    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private com.trading.tradeapi.mapper.AccountMapper accountMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.UserMapper userMapper;
    @MockitoBean
    private com.trading.tradeapi.watchlist.mapper.WatchlistMapper watchlistMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.InstrumentMapper instrumentMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.OrderMapper orderMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.AdminMapper adminMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.HoldingMapper holdingMapper;

    @BeforeEach
    void passThroughFilter() throws Exception {
        org.mockito.Mockito.doAnswer(inv -> {
            jakarta.servlet.FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    private static AdminUserDto user(String status, String email) {
        return new AdminUserDto(5L, "uuid-5", "Ada", "Lovelace", "ada", email, "client", "+44", "77",
                "UK", "BALANCED", status, 50L, status, "GBP", null,
                Instant.parse("2026-10-01T00:00:00Z"), null, null, null, null);
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request
                .requestAttr(JwtAuthenticationFilter.AUTHENTICATED_ROLES_ATTR, List.of("ADMIN"))
                .requestAttr(JwtAuthenticationFilter.AUTHENTICATED_UUID_ATTR, "admin-uuid");
    }

    private static MockHttpServletRequestBuilder asTrader(MockHttpServletRequestBuilder request) {
        return request.requestAttr(JwtAuthenticationFilter.AUTHENTICATED_ROLES_ATTR, List.of("CUSTOMER"));
    }

    private static com.trading.tradeapi.dto.AdminAccountDto account(String status) {
        return new com.trading.tradeapi.dto.AdminAccountDto(50L, 5L, "Ada Lovelace", "ada", "client", "GBP",
                java.math.BigDecimal.TEN, status, 1L);
    }

    @Nested
    @DisplayName("Suspending and reinstating users")
    class SuspendingAndReinstatingUsers {

        @Test
        @DisplayName("suspending sends the notice and reports that it was delivered")
        void suspendReportsDeliveredEmail() throws Exception {
            when(adminService.suspendUser(eq(5L), eq("admin-uuid"), any())).thenReturn(user("SUSPENDED", "ada@example.com"));
            when(emailService.sendAccountSuspensionEmail("ada@example.com", "Ada", "Suspicious activity")).thenReturn(true);

            mockMvc.perform(asAdmin(post("/api/v1/admin/users/5/suspend"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"  Suspicious activity  \"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUSPENDED"))
                    .andExpect(jsonPath("$.emailDelivered").value(true));

            verify(emailService).sendAccountSuspensionEmail("ada@example.com", "Ada", "Suspicious activity");
        }

        @Test
        @DisplayName("a suspension still succeeds, and says so, when the email cannot be sent")
        void suspendReportsUndeliveredEmail() throws Exception {
            when(adminService.suspendUser(eq(5L), any(), any())).thenReturn(user("SUSPENDED", "ada@example.com"));
            when(emailService.sendAccountSuspensionEmail(anyString(), anyString(), anyString())).thenReturn(false);

            mockMvc.perform(asAdmin(post("/api/v1/admin/users/5/suspend"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"review\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUSPENDED"))
                    .andExpect(jsonPath("$.emailDelivered").value(false));
        }

        @Test
        @DisplayName("a suspension without a reason is refused before anything changes")
        void suspendNeedsReason() throws Exception {
            mockMvc.perform(asAdmin(post("/api/v1/admin/users/5/suspend"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"  \"}"))
                    .andExpect(status().is4xxClientError());

            verifyNoInteractions(adminService);
            verify(emailService, never()).sendAccountSuspensionEmail(any(), any(), any());
        }

        @Test
        @DisplayName("reinstating sends its own notice, with a default reason when none is given")
        void reinstateDefaultsReason() throws Exception {
            when(adminService.reinstateUser(eq(5L), any(), any())).thenReturn(user("ACTIVE", "ada@example.com"));
            when(emailService.sendAccountReinstatedEmail("ada@example.com", "Ada", "Reinstated by administrator"))
                    .thenReturn(true);

            mockMvc.perform(asAdmin(post("/api/v1/admin/users/5/reinstate")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.emailDelivered").value(true));
        }
    }

    @Nested
    @DisplayName("Approving")
    class Approving {

        @Test
        @DisplayName("approving needs no body, emails the applicant and reports the delivery")
        void approveEmailsApplicant() throws Exception {
            when(adminService.approveUser(eq(5L), eq("admin-uuid"), any())).thenReturn(user("ACTIVE", "ada@example.com"));
            when(emailService.sendAccountApprovedEmail("ada@example.com", "Ada", "ada", 50L, "GBP"))
                    .thenReturn(true);

            mockMvc.perform(asAdmin(post("/api/v1/admin/users/5/approve")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.inactive").value(false))
                    .andExpect(jsonPath("$.emailDelivered").value(true));

            verify(emailService).sendAccountApprovedEmail("ada@example.com", "Ada", "ada", 50L, "GBP");
        }

        @Test
        @DisplayName("an approval still succeeds, and says so, when the email cannot be sent")
        void approveReportsUndeliveredEmail() throws Exception {
            when(adminService.approveUser(eq(5L), any(), any())).thenReturn(user("ACTIVE", "ada@example.com"));

            mockMvc.perform(asAdmin(post("/api/v1/admin/users/5/approve")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.emailDelivered").value(false));
        }

        @Test
        @DisplayName("no approval email is sent when the approval itself is refused")
        void refusedApprovalSendsNothing() throws Exception {
            when(adminService.approveUser(eq(5L), any(), any()))
                    .thenThrow(new IllegalArgumentException("Only an INACTIVE user can be approved"));

            mockMvc.perform(asAdmin(post("/api/v1/admin/users/5/approve")))
                    .andExpect(status().isUnprocessableEntity());

            verify(emailService, never()).sendAccountApprovedEmail(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an applicant awaiting approval is serialised with inactive=true, which the console's Approve button needs")
        void pendingUserIsMarkedInactive() throws Exception {
            when(adminService.getUsers()).thenReturn(List.of(user("INACTIVE", "p@example.com")));

            mockMvc.perform(asAdmin(get("/api/v1/admin/users")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].status").value("INACTIVE"))
                    .andExpect(jsonPath("$[0].inactive").value(true));
        }
    }

    @Nested
    @DisplayName("Account status")
    class AccountStatus {

        @Test
        @DisplayName("an illegal transition comes back as VAL-422 in the error envelope")
        void illegalTransition() throws Exception {
            when(adminService.suspendUser(eq(5L), any(), any(SuspendUserRequestDto.class)))
                    .thenThrow(new IllegalArgumentException("Only an ACTIVE user can be suspended"));

            mockMvc.perform(asAdmin(post("/api/v1/admin/users/5/suspend"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"x\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("VAL-422"))
                    .andExpect(jsonPath("$.message").exists());
            verify(emailService, never()).sendAccountSuspensionEmail(any(), any(), any());
        }

        @Test
        @DisplayName("suspending from the Accounts page emails the holder the reason and reports delivery")
        void accountSuspendEmailsHolder() throws Exception {
            when(adminService.changeAccountStatus(50L, "SUSPENDED", "Unusual activity", "admin-uuid"))
                    .thenReturn(new AdminService.AccountStatusChange(account("SUSPENDED"), user("SUSPENDED", "ada@example.com"),
                            AdminService.AccountNotice.SUSPENDED, "Unusual activity"));
            when(emailService.sendAccountSuspensionEmail("ada@example.com", "Ada", "Unusual activity")).thenReturn(true);

            mockMvc.perform(asAdmin(post("/api/v1/admin/accounts/50/status"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"SUSPENDED\",\"reason\":\"Unusual activity\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(50))
                    .andExpect(jsonPath("$.status").value("SUSPENDED"))
                    .andExpect(jsonPath("$.emailDelivered").value(true));

            verify(emailService).sendAccountSuspensionEmail("ada@example.com", "Ada", "Unusual activity");
        }

        @Test
        @DisplayName("re-activating from the Accounts page emails the reinstatement notice")
        void accountActivateEmailsHolder() throws Exception {
            when(adminService.changeAccountStatus(eq(50L), eq("ACTIVE"), any(), any()))
                    .thenReturn(new AdminService.AccountStatusChange(account("ACTIVE"), user("ACTIVE", "ada@example.com"),
                            AdminService.AccountNotice.REINSTATED, "Reinstated by administrator"));
            when(emailService.sendAccountReinstatedEmail("ada@example.com", "Ada", "Reinstated by administrator"))
                    .thenReturn(true);

            mockMvc.perform(asAdmin(post("/api/v1/admin/accounts/50/status"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ACTIVE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.emailDelivered").value(true));
        }

        @Test
        @DisplayName("closing an account sends no email and reports none")
        void accountCloseSendsNothing() throws Exception {
            when(adminService.changeAccountStatus(eq(50L), eq("CLOSED"), any(), any()))
                    .thenReturn(new AdminService.AccountStatusChange(account("CLOSED"), user("ACTIVE", "ada@example.com"),
                            AdminService.AccountNotice.NONE, null));

            mockMvc.perform(asAdmin(post("/api/v1/admin/accounts/50/status"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"CLOSED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"))
                    .andExpect(jsonPath("$.emailDelivered").doesNotExist());

            verify(emailService, never()).sendAccountSuspensionEmail(any(), any(), any());
            verify(emailService, never()).sendAccountReinstatedEmail(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Access and filters")
    class AccessAndFilters {

        @Test
        @DisplayName("a trader is refused with AUTH-403 and the service is never called")
        void traderRefused() throws Exception {
            mockMvc.perform(asTrader(get("/api/v1/admin/users")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-403"));

            mockMvc.perform(asTrader(post("/api/v1/admin/users/5/suspend"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"x\"}"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(adminService);
        }

        @Test
        @DisplayName("a request with no roles at all is refused")
        void anonymousRefused() throws Exception {
            mockMvc.perform(get("/api/v1/admin/stats"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-403"));
        }

        @Test
        @DisplayName("the order book passes its filters through")
        void orderFilters() throws Exception {
            when(adminService.getOrders("REJECTED", 3L, 20)).thenReturn(List.of());

            mockMvc.perform(asAdmin(get("/api/v1/admin/orders"))
                            .param("status", "REJECTED").param("accountId", "3").param("limit", "20"))
                    .andExpect(status().isOk());

            verify(adminService).getOrders("REJECTED", 3L, 20);
        }
    }
}
