package com.trading.tradeapi.characterisation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.controller.OrderController;
import com.trading.tradeapi.dto.OrderResponseDto;
import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderStatus;
import com.trading.tradeapi.enums.OrderType;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.exception.DuplicateOrderException;
import com.trading.tradeapi.exception.GlobalExceptionHandler;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.exception.InsufficientFundsException;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.security.JwtService;
import com.trading.tradeapi.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 7 Characterisation Test Suite.
 * Pins observable order placement edge behavior for OrderController.
 * Package: com.trading.tradeapi.characterisation
 */
@WebMvcTest(value = OrderController.class,
        excludeAutoConfiguration = {
                MybatisAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class
        })
@Import({GlobalExceptionHandler.class})
public class OrderPlacementCharacterisationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private JwtService jwtService;

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
    private com.trading.tradeapi.mapper.HoldingMapper holdingMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.AdminMapper adminMapper;

    private PlaceOrderRequestDto sampleOrderRequest;

    @BeforeEach
    public void setup() throws Exception {
        org.mockito.Mockito.doAnswer(inv -> {
            jakarta.servlet.FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        sampleOrderRequest = new PlaceOrderRequestDto(
                101L,
                "AAPL",
                OrderSide.BUY,
                100L,
                new BigDecimal("233.00"),
                OrderType.LIMIT,
                "6f2b1c2a-6a1e-4a4f-9c0d-2f7a1b3c4d5e"
        );
    }

    @Test
    @DisplayName("Characterisation: Valid order placement returns expected fields and status")
    public void pinOrderPlacementSuccessBehavior() throws Exception {
        OrderResponseDto expectedResponse = new OrderResponseDto(
                "ORD-6f2b1c2a-6a1e-4a4f-9c0d-2f7a1b3c4d5e",
                OrderStatus.NEW,
                "Order recorded awaiting execution",
                "AAPL",
                OrderSide.BUY,
                100L,
                new BigDecimal("233.00"),
                OrderType.LIMIT,
                null
        );

        when(orderService.placeOrder(any())).thenReturn(expectedResponse);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleOrderRequest))
                        .requestAttr("authenticatedAccountId", 101L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ORD-6f2b1c2a-6a1e-4a4f-9c0d-2f7a1b3c4d5e"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.side").value("BUY"))
                .andExpect(jsonPath("$.quantity").value(100))
                .andExpect(jsonPath("$.price").value(233.00));
    }

    @Test
    @DisplayName("Characterisation: Reused idempotency key returns 409 Conflict with ORD-409 code")
    public void pinDuplicateIdempotencyKeyErrorBehavior() throws Exception {
        when(orderService.placeOrder(any()))
                .thenThrow(new DuplicateOrderException());

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleOrderRequest))
                        .requestAttr("authenticatedAccountId", 101L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORD-409"))
                .andExpect(jsonPath("$.message").value("Duplicate order"));
    }

    @Test
    @DisplayName("Characterisation: Insufficient cash balance returns 400 Bad Request with ORD-400 code")
    public void pinInsufficientFundsErrorBehavior() throws Exception {
        when(orderService.placeOrder(any()))
                .thenThrow(new InsufficientFundsException());

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleOrderRequest))
                        .requestAttr("authenticatedAccountId", 101L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ORD-400"))
                .andExpect(jsonPath("$.message").value("Insufficient funds"));
    }

    @Test
    @DisplayName("Characterisation: Inactive account returns 403 Forbidden with ACC-403 code")
    public void pinAccountNotActiveErrorBehavior() throws Exception {
        when(orderService.placeOrder(any()))
                .thenThrow(new AccountNotActiveException());

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleOrderRequest))
                        .requestAttr("authenticatedAccountId", 101L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"))
                .andExpect(jsonPath("$.message").value("Account is not active"));
    }

    @Test
    @DisplayName("Characterisation: Unknown instrument symbol returns 404 Not Found with INS-404 code")
    public void pinUnknownSymbolErrorBehavior() throws Exception {
        when(orderService.placeOrder(any()))
                .thenThrow(new InstrumentNotFoundException());

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleOrderRequest))
                        .requestAttr("authenticatedAccountId", 101L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INS-404"))
                .andExpect(jsonPath("$.message").value("Instrument not found or not tradable"));
    }
}
