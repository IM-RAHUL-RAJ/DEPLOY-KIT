package com.trading.tradeapi.notifications;

import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.notifications.controller.NotificationsController;
import com.trading.tradeapi.notifications.dto.NotificationDto;
import com.trading.tradeapi.notifications.mapper.NotificationMapper;
import com.trading.tradeapi.notifications.service.NotificationService;
import com.trading.tradeapi.preferences.mapper.PreferencesMapper;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The notifications routes in the same slice shape as the order routes: the real controller and
 * the real {@code GlobalExceptionHandler}, everything below the service mocked.
 */
@WebMvcTest(value = NotificationsController.class,
        excludeAutoConfiguration = {
                MybatisAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class
        })
@Import(com.trading.tradeapi.exception.GlobalExceptionHandler.class)
class NotificationsControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    // Mappers registered by the application's mapper scan — mocked so the slice starts
    // without a datasource, exactly as the order slice does.
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
    @MockitoBean
    private PreferencesMapper preferencesMapper;
    @MockitoBean
    private NotificationMapper notificationMapper;

    @BeforeEach
    void letRequestsReachTheController() throws Exception {
        org.mockito.Mockito.doAnswer(inv -> {
            jakarta.servlet.FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    void listReturnsOwnedNotifications() throws Exception {
        NotificationDto dto = new NotificationDto(42L, 1L, "ORDER_FILLED", "AAPL",
                "Order filled", "Bought 40 AAPL at 232.71.", "PUSH", "SENT", false,
                Instant.parse("2026-10-19T14:05:11Z"));
        when(notificationService.listFor(1L, null, null)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/notifications/1").requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("ORDER_FILLED"))
                .andExpect(jsonPath("$[0].read").value(false));
    }

    @Test
    void crossAccountIsForbiddenNotNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/1").requestAttr("authenticatedAccountId", 2L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }

    @Test
    void markReadRespondsWithTheUpdatedRecord() throws Exception {
        NotificationDto dto = new NotificationDto(42L, 1L, "PRICE_ALERT", "AAPL",
                "Price alert triggered", "AAPL crossed above 230.00.", "PUSH", "SENT", true,
                Instant.parse("2026-10-19T14:05:11Z"));
        when(notificationService.markRead(1L, 42L)).thenReturn(dto);

        mockMvc.perform(post("/api/v1/notifications/1/42/read").requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
    }

    @Test
    void markReadOnADifferentAccountIsNotFound() throws Exception {
        when(notificationService.markRead(eq(2L), eq(42L)))
                .thenThrow(new DomainException("ACC-404", "Notification not found", HttpStatus.NOT_FOUND));

        mockMvc.perform(post("/api/v1/notifications/2/42/read").requestAttr("authenticatedAccountId", 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACC-404"));
    }
}