package com.trading.tradeapi.watchlist.alerts;

import com.trading.tradeapi.watchlist.alerts.controller.PriceAlertController;
import com.trading.tradeapi.watchlist.alerts.dto.PriceAlertDto;
import com.trading.tradeapi.watchlist.alerts.mapper.PriceAlertMapper;
import com.trading.tradeapi.watchlist.alerts.service.AlertService;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The alert routes in the slice shape the module's siblings use: the real controller and the
 * real {@code GlobalExceptionHandler}, everything below the service mocked.
 */
@WebMvcTest(value = PriceAlertController.class,
        excludeAutoConfiguration = {
                MybatisAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class
        })
@Import(com.trading.tradeapi.exception.GlobalExceptionHandler.class)
class PriceAlertControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertService alertService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    // Mappers registered by the application's mapper scan — mocked so the slice starts
    // without a datasource, as every slice in the suite does.
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
    private com.trading.tradeapi.preferences.mapper.PreferencesMapper preferencesMapper;
    @MockitoBean
    private com.trading.tradeapi.notifications.mapper.NotificationMapper notificationMapper;
    @MockitoBean
    private PriceAlertMapper priceAlertMapper;

    @BeforeEach
    void letRequestsReachTheController() throws Exception {
        org.mockito.Mockito.doAnswer(inv -> {
            jakarta.servlet.FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    void listReturnsTheAccountsAlerts() throws Exception {
        PriceAlertDto dto = new PriceAlertDto(12L, 1L, "AAPL", "ABOVE", new BigDecimal("240.00"),
                "USD", "ACTIVE", null, null, Instant.parse("2026-10-19T12:00:00Z"));
        when(alertService.list(1L)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/watchlist/alerts").requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void createReturnsTheArmedAlert() throws Exception {
        PriceAlertDto dto = new PriceAlertDto(12L, 1L, "AAPL", "ABOVE", new BigDecimal("240.00"),
                "USD", "ACTIVE", null, null, Instant.parse("2026-10-19T12:00:00Z"));
        when(alertService.create(any(), any())).thenReturn(dto);

        mockMvc.perform(post("/api/v1/watchlist/alerts")
                        .requestAttr("authenticatedAccountId", 1L)
                        .contentType("application/json")
                        .content("""
                                {"symbol":"AAPL","direction":"ABOVE","threshold":240.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertId").value(12L))
                .andExpect(jsonPath("$.direction").value("ABOVE"));
    }

    @Test
    void validationFailuresAre422Not500() throws Exception {
        mockMvc.perform(post("/api/v1/watchlist/alerts")
                        .requestAttr("authenticatedAccountId", 1L)
                        .contentType("application/json")
                        .content("""
                                {"symbol":"","direction":"SIDEWAYS","threshold":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
    }

    @Test
    void cancelReturnsTheAlertWithItsCurrentStatus() throws Exception {
        PriceAlertDto dto = new PriceAlertDto(12L, 1L, "AAPL", "ABOVE", new BigDecimal("240.00"),
                "USD", "CANCELLED", null, null, Instant.parse("2026-10-19T12:00:00Z"));
        when(alertService.cancel(1L, 12L)).thenReturn(dto);

        mockMvc.perform(delete("/api/v1/watchlist/alerts/12").requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelOnAForeignAlertIsNotFound() throws Exception {
        when(alertService.cancel(2L, 12L))
                .thenThrow(new DomainException("ACC-404", "Alert not found", HttpStatus.NOT_FOUND));

        mockMvc.perform(delete("/api/v1/watchlist/alerts/12").requestAttr("authenticatedAccountId", 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACC-404"));
    }

    @Test
    void noAccountClaimIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/watchlist/alerts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }
}