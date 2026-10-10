package com.trading.tradeapi.watchlist.controller;

import com.trading.tradeapi.watchlist.dto.AddWatchlistInstrumentRequest;
import com.trading.tradeapi.watchlist.dto.CreateWatchlistRequest;
import com.trading.tradeapi.watchlist.dto.MoveWatchlistInstrumentRequest;
import com.trading.tradeapi.watchlist.dto.RenameWatchlistRequest;
import com.trading.tradeapi.watchlist.dto.WatchlistDto;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.exception.GlobalExceptionHandler;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.watchlist.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer integration for the named watchlist routes: routing, the account claim, service
 * delegation and the error envelope.
 */
@WebMvcTest(value = WatchlistsController.class,
        excludeAutoConfiguration = {
                MybatisAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class
        })
@Import({GlobalExceptionHandler.class})
class WatchlistsControllerSliceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WatchlistService watchlistService;
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

    private static MockHttpServletRequestBuilder asClient(MockHttpServletRequestBuilder request) {
        return request
                .requestAttr(JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR, 7L)
                .requestAttr(JwtAuthenticationFilter.AUTHENTICATED_ROLES_ATTR, List.of("CUSTOMER"))
                .requestAttr(JwtAuthenticationFilter.AUTHENTICATED_UUID_ATTR, "uuid-7");
    }

    private static MockHttpServletRequestBuilder asNoAccount(MockHttpServletRequestBuilder request) {
        return request
                .requestAttr(JwtAuthenticationFilter.AUTHENTICATED_ROLES_ATTR, List.of("ADMIN"))
                .requestAttr(JwtAuthenticationFilter.AUTHENTICATED_UUID_ATTR, "uuid-admin");
    }

    private static WatchlistDto list(long id, String name) {
        return new WatchlistDto(id, 7L, name, List.of(), NOW, NOW);
    }

    @Test
    @DisplayName("GET lists every watchlist, oldest first, with instruments")
    void listRoutes() throws Exception {
        when(watchlistService.listWatchlists(7L, "uuid-7"))
                .thenReturn(List.of(list(1L, "My Watchlist"), list(3L, "Tech leaders")));

        mockMvc.perform(asClient(get("/api/v1/watchlists")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("My Watchlist"))
                .andExpect(jsonPath("$[1].name").value("Tech leaders"));
    }

    @Test
    @DisplayName("POST creates a named watchlist and returns it")
    void createRoutes() throws Exception {
        when(watchlistService.createWatchlist(eq(7L), eq("uuid-7"), any()))
                .thenReturn(list(5L, "Earnings watch"));

        mockMvc.perform(asClient(post("/api/v1/watchlists"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Earnings watch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchlistId").value(5L))
                .andExpect(jsonPath("$.name").value("Earnings watch"));
    }

    @Test
    @DisplayName("PATCH renames one of the account's own lists")
    void renameRoutes() throws Exception {
        when(watchlistService.renameWatchlist(eq(7L), eq("uuid-7"), eq(3L), any(RenameWatchlistRequest.class)))
                .thenReturn(list(3L, "Dividends"));

        mockMvc.perform(asClient(patch("/api/v1/watchlists/3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dividends\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dividends"));
    }

    @Test
    @DisplayName("DELETE removes a list and answers 204")
    void deleteRoutes() throws Exception {
        mockMvc.perform(asClient(delete("/api/v1/watchlists/3")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST adds an instrument to a specific list")
    void addInstrumentRoutes() throws Exception {
        when(watchlistService.addInstrumentToList(eq(7L), eq("uuid-7"), eq(1L), any()))
                .thenReturn(list(1L, "Tech leaders"));

        mockMvc.perform(asClient(post("/api/v1/watchlists/1/instruments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"MSFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchlistId").value(1L));
    }

    @Test
    @DisplayName("DELETE removes an instrument from a specific list")
    void removeInstrumentRoutes() throws Exception {
        when(watchlistService.removeInstrumentFromList(eq(7L), eq("uuid-7"), eq(1L), eq(9L)))
                .thenReturn(list(1L, "Tech leaders"));

        mockMvc.perform(asClient(delete("/api/v1/watchlists/1/instruments/9")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchlistId").value(1L));
    }

    @Test
    @DisplayName("POST moves an instrument to another of the account's lists")
    void moveRoutes() throws Exception {
        when(watchlistService.moveInstrument(eq(7L), eq("uuid-7"), eq(9L), eq(3L)))
                .thenReturn(list(3L, "Tech leaders"));

        mockMvc.perform(asClient(post("/api/v1/watchlists/instruments/9/move"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetWatchlistId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchlistId").value(3L));
    }

    @Test
    @DisplayName("a request without an account claim is ACC-403, in the envelope")
    void noAccountIsForbidden() throws Exception {
        mockMvc.perform(asNoAccount(get("/api/v1/watchlists")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }

    @Test
    @DisplayName("a service rejection reaches the client as the platform envelope")
    void domainErrorIsMapped() throws Exception {
        when(watchlistService.createWatchlist(eq(7L), eq("uuid-7"), any()))
                .thenThrow(new DomainException("VAL-422", "Invalid input", HttpStatus.UNPROCESSABLE_ENTITY));

        mockMvc.perform(asClient(post("/api/v1/watchlists"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
    }
}