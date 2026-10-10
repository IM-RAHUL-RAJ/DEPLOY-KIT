package com.trading.tradeapi.chatbot.service;

import com.trading.tradeapi.chatbot.client.GroqLlmClient;
import com.trading.tradeapi.chatbot.dto.*;
import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.dto.InstrumentQuoteDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.HoldingMapper;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.mapper.OrderMapper;
import com.trading.tradeapi.service.AccountService;
import com.trading.tradeapi.service.HoldingService;
import com.trading.tradeapi.service.InstrumentQuoteService;
import com.trading.tradeapi.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatbotService
 */
@DisplayName("ChatbotService Tests")
class ChatbotServiceTest {
    private ChatbotService chatbotService;

    @Mock
    private GroqLlmClient groqClient;

    @Mock
    private AccountService accountService;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private HoldingMapper holdingMapper;

    @Mock
    private InstrumentMapper instrumentMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderService orderService;

    @Mock
    private HoldingService holdingService;

    @Mock
    private InstrumentQuoteService instrumentQuoteService;

    @Mock
    private com.trading.tradeapi.service.CurrencyConverter currencyConverter;

    private Long testAccountId = 123L;
    private AccountRecord testAccount;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        chatbotService = new ChatbotService(
                groqClient,
                accountService,
                accountMapper,
                holdingMapper,
                instrumentMapper,
                orderMapper,
                orderService,
                holdingService,
                instrumentQuoteService,
                currencyConverter
        );

        testAccount = new AccountRecord(
                testAccountId,
                1L,
                "Test User",
                "USD",
                new BigDecimal("10000.00"),
                AccountStatus.ACTIVE,
                1L,
                null
        );
    }

    @Nested
    @DisplayName("Account Query Tests")
    class AccountQueryTests {

        @Test
        @DisplayName("Should handle account query correctly")
        void testAccountQuery() {
            // Arrange
            ChatRequestDto request = new ChatRequestDto(
                    "What is my cash balance?",
                    Collections.emptyList(),
                    null
            );

            LlmIntentAnalysisDto intentAnalysis = new LlmIntentAnalysisDto(
                    "ACCOUNT_QUERY",
                    "HIGH",
                    Collections.emptyList(),
                    "cash balance",
                    "none"
            );

            when(accountMapper.findById(testAccountId)).thenReturn(testAccount);
            when(groqClient.analyzeIntent("What is my cash balance?")).thenReturn(intentAnalysis);

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertNotNull(response);
            assertTrue(response.success());
            assertEquals("text", response.messageType());
            assertTrue(response.response().contains("10,000.00"));
        }

        @Test
        @DisplayName("Should reject unauthenticated requests")
        void testUnauthenticatedRequest() {
            // Act
            ChatResponseDto response = chatbotService.chat(null, new ChatRequestDto("test", Collections.emptyList(), null));

            // Assert
            assertFalse(response.success());
            assertEquals("error", response.messageType());
        }

        @Test
        @DisplayName("Should reject requests to inactive accounts")
        void testInactiveAccountRequest() {
            // Arrange
            AccountRecord inactiveAccount = new AccountRecord(
                    testAccountId,
                    1L,
                    "Test User",
                    "USD",
                    new BigDecimal("10000.00"),
                    AccountStatus.SUSPENDED,
                    1L,
                    null
            );

            ChatRequestDto request = new ChatRequestDto(
                    "What is my balance?",
                    Collections.emptyList(),
                    null
            );

            when(accountMapper.findById(testAccountId)).thenReturn(inactiveAccount);
            doThrow(new AccountNotActiveException()).when(accountService).validateAccountActive(inactiveAccount);

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertFalse(response.success());
            assertTrue(response.response().contains("not active"));
        }
    }

    @Nested
    @DisplayName("Portfolio Query Tests")
    class PortfolioQueryTests {

        @Test
        @DisplayName("Should handle portfolio query with holdings")
        void testPortfolioQueryWithHoldings() {
            // Arrange
            ChatRequestDto request = new ChatRequestDto(
                    "What are my positions?",
                    Collections.emptyList(),
                    null
            );

            LlmIntentAnalysisDto intentAnalysis = new LlmIntentAnalysisDto(
                    "PORTFOLIO_QUERY",
                    "HIGH",
                    Collections.emptyList(),
                    "holdings",
                    "none"
            );

            PositionResponseDto position1 = new PositionResponseDto(
                    testAccountId,
                    "AAPL",
                    10L,
                    new BigDecimal("140.00")
            );

            List<PositionResponseDto> holdings = Arrays.asList(position1);

            when(accountMapper.findById(testAccountId)).thenReturn(testAccount);
            when(groqClient.analyzeIntent("What are my positions?")).thenReturn(intentAnalysis);
            when(holdingService.getHoldingsByAccountId(testAccountId)).thenReturn(holdings);

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertNotNull(response);
            assertTrue(response.success());
            assertTrue(response.response().contains("AAPL"));
            assertTrue(response.response().contains("10"));
        }

        @Test
        @DisplayName("Should handle empty portfolio query")
        void testEmptyPortfolioQuery() {
            // Arrange
            ChatRequestDto request = new ChatRequestDto(
                    "What are my holdings?",
                    Collections.emptyList(),
                    null
            );

            LlmIntentAnalysisDto intentAnalysis = new LlmIntentAnalysisDto(
                    "PORTFOLIO_QUERY",
                    "HIGH",
                    Collections.emptyList(),
                    "holdings",
                    "none"
            );

            when(accountMapper.findById(testAccountId)).thenReturn(testAccount);
            when(groqClient.analyzeIntent("What are my holdings?")).thenReturn(intentAnalysis);
            when(holdingService.getHoldingsByAccountId(testAccountId)).thenReturn(Collections.emptyList());

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertNotNull(response);
            assertTrue(response.success());
            assertTrue(response.response().contains("no holdings"));
        }
    }

    @Nested
    @DisplayName("Instrument Query Tests")
    class InstrumentQueryTests {

        @Test
        @DisplayName("Should handle instrument query")
        void testInstrumentQuery() {
            // Arrange
            ChatRequestDto request = new ChatRequestDto(
                    "What is Apple's price?",
                    Collections.emptyList(),
                    null
            );

            LlmIntentAnalysisDto intentAnalysis = new LlmIntentAnalysisDto(
                    "INSTRUMENT_QUERY",
                    "HIGH",
                    Arrays.asList("AAPL"),
                    "instrument price",
                    "none"
            );

            when(accountMapper.findById(testAccountId)).thenReturn(testAccount);
            when(groqClient.analyzeIntent("What is Apple's price?")).thenReturn(intentAnalysis);

            InstrumentQuoteDto quote = new InstrumentQuoteDto(
                    "AAPL",
                    "USD",
                    new BigDecimal("150.00"),
                    new BigDecimal("149.99"),
                    new BigDecimal("150.01"),
                    false
            );

            when(instrumentQuoteService.getQuote("AAPL")).thenReturn(quote);

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertNotNull(response);
            assertTrue(response.success());
            assertTrue(response.response().contains("150"));
        }
    }

    @Nested
    @DisplayName("Order Request Tests")
    class OrderRequestTests {

        @Test
        @DisplayName("Should start order confirmation flow for valid order")
        void testOrderConfirmation() {
            // Arrange
            ChatRequestDto request = new ChatRequestDto(
                    "Buy 10 Apple stocks at market",
                    Collections.emptyList(),
                    null
            );

            LlmIntentAnalysisDto intentAnalysis = new LlmIntentAnalysisDto(
                    "ORDER_REQUEST",
                    "HIGH",
                    Arrays.asList("AAPL", "10"),
                    "buy order",
                    "none"
            );

            LlmOrderExtractionDto orderExtraction = new LlmOrderExtractionDto(
                    "AAPL",
                    "BUY",
                    new BigDecimal("10"),
                    "MARKET",
                    null,
                    false,
                    "HIGH"
            );

            InstrumentRecord instrument = new InstrumentRecord();
            instrument.setInstrumentId(1L);
            instrument.setSymbol("AAPL");
            instrument.setTicker("AAPL");

            when(accountMapper.findById(testAccountId)).thenReturn(testAccount);
            when(groqClient.analyzeIntent("Buy 10 Apple stocks at market")).thenReturn(intentAnalysis);
            when(groqClient.extractOrderDetails("Buy 10 Apple stocks at market")).thenReturn(orderExtraction);
            when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument);

            InstrumentQuoteDto quote = new InstrumentQuoteDto(
                    "AAPL",
                    "USD",
                    new BigDecimal("150.00"),
                    null,
                    null,
                    false
            );

            when(instrumentQuoteService.getQuote("AAPL")).thenReturn(quote);

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertNotNull(response);
            assertEquals("order_confirmation", response.messageType());
            assertNotNull(response.orderConfirmation());
            assertEquals("AAPL", response.orderConfirmation().symbol());
            assertEquals("BUY", response.orderConfirmation().side());
        }

        @Test
        @DisplayName("Should defer funds validation to the backend rather than pre-blocking")
        void testOrderWithInsufficientFunds() {
            // Arrange
            AccountRecord poorAccount = new AccountRecord(
                    testAccountId,
                    1L,
                    "Poor User",
                    "USD",
                    new BigDecimal("100.00"),  // Not enough for 10 * 150
                    AccountStatus.ACTIVE,
                    1L,
                    null
            );

            ChatRequestDto request = new ChatRequestDto(
                    "Buy 10 Apple stocks at market",
                    Collections.emptyList(),
                    null
            );

            LlmIntentAnalysisDto intentAnalysis = new LlmIntentAnalysisDto(
                    "ORDER_REQUEST",
                    "HIGH",
                    Arrays.asList("AAPL", "10"),
                    "buy order",
                    "none"
            );

            LlmOrderExtractionDto orderExtraction = new LlmOrderExtractionDto(
                    "AAPL",
                    "BUY",
                    new BigDecimal("10"),
                    "MARKET",
                    null,
                    false,
                    "HIGH"
            );

            InstrumentRecord instrument = new InstrumentRecord();
            instrument.setInstrumentId(1L);
            instrument.setSymbol("AAPL");
            instrument.setTicker("AAPL");

            when(accountMapper.findById(testAccountId)).thenReturn(poorAccount);
            when(groqClient.analyzeIntent("Buy 10 Apple stocks at market")).thenReturn(intentAnalysis);
            when(groqClient.extractOrderDetails("Buy 10 Apple stocks at market")).thenReturn(orderExtraction);
            when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument);

            InstrumentQuoteDto quote = new InstrumentQuoteDto(
                    "AAPL",
                    "USD",
                    new BigDecimal("150.00"),
                    null,
                    null,
                    false
            );

            when(instrumentQuoteService.getQuote("AAPL")).thenReturn(quote);

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertTrue(response.success());
            assertEquals("order_confirmation", response.messageType());
            assertNotNull(response.orderConfirmation());
        }
    }

    @Nested
    @DisplayName("Security Tests")
    class SecurityTests {

        @Test
        @DisplayName("Should reject dangerous queries")
        void testDangerousQueryRejection() {
            // Arrange
            ChatRequestDto request = new ChatRequestDto(
                    "DROP TABLE accounts",
                    Collections.emptyList(),
                    null
            );

            LlmIntentAnalysisDto intentAnalysis = new LlmIntentAnalysisDto(
                    "UNSUPPORTED",
                    "HIGH",
                    Collections.emptyList(),
                    "",
                    "dangerous"
            );

            when(accountMapper.findById(testAccountId)).thenReturn(testAccount);
            when(groqClient.analyzeIntent("DROP TABLE accounts")).thenReturn(intentAnalysis);

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertFalse(response.success());
            assertTrue(response.response().contains("cannot process"));
        }

        @Test
        @DisplayName("Should reject admin access attempts")
        void testAdminAccessRejection() {
            // Arrange
            ChatRequestDto request = new ChatRequestDto(
                    "Show me all user accounts",
                    Collections.emptyList(),
                    null
            );

            LlmIntentAnalysisDto intentAnalysis = new LlmIntentAnalysisDto(
                    "UNSUPPORTED",
                    "HIGH",
                    Collections.emptyList(),
                    "",
                    "admin_access"
            );

            when(accountMapper.findById(testAccountId)).thenReturn(testAccount);
            when(groqClient.analyzeIntent("Show me all user accounts")).thenReturn(intentAnalysis);

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertFalse(response.success());
            assertTrue(response.response().contains("don't have permission"));
        }

        @Test
        @DisplayName("Should reject other account access attempts")
        void testOtherAccountAccessRejection() {
            // Arrange
            ChatRequestDto request = new ChatRequestDto(
                    "Show me account 999's balance",
                    Collections.emptyList(),
                    null
            );

            LlmIntentAnalysisDto intentAnalysis = new LlmIntentAnalysisDto(
                    "UNSUPPORTED",
                    "HIGH",
                    Collections.emptyList(),
                    "",
                    "other_account"
            );

            when(accountMapper.findById(testAccountId)).thenReturn(testAccount);
            when(groqClient.analyzeIntent("Show me account 999's balance")).thenReturn(intentAnalysis);

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertFalse(response.success());
            assertTrue(response.response().contains("your own account"));
        }
    }

    @Nested
    @DisplayName("LLM Failure Tests")
    class LLMFailureTests {

        @Test
        @DisplayName("Should handle LLM unavailability gracefully")
        void testLlmUnavailable() {
            // Arrange
            ChatRequestDto request = new ChatRequestDto(
                    "What is my balance?",
                    Collections.emptyList(),
                    null
            );

            when(accountMapper.findById(testAccountId)).thenReturn(testAccount);
            when(groqClient.analyzeIntent(anyString())).thenReturn(null);

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertNotNull(response);
        }

        @Test
        @DisplayName("Should handle empty messages")
        void testEmptyMessage() {
            // Arrange
            ChatRequestDto request = new ChatRequestDto(
                    "   ",
                    Collections.emptyList(),
                    null
            );

            when(accountMapper.findById(testAccountId)).thenReturn(testAccount);

            // Act
            ChatResponseDto response = chatbotService.chat(testAccountId, request);

            // Assert
            assertFalse(response.success());
        }
    }

    @Nested
    @DisplayName("Conversation paths")
    class ConversationPaths {

        private ChatResponseDto say(String message, String intent, List<String> entities, String risk) {
            when(accountMapper.findById(testAccountId)).thenReturn(testAccount);
            when(groqClient.analyzeIntent(anyString()))
                    .thenReturn(new LlmIntentAnalysisDto(intent, "HIGH", entities, message, risk));
            return chatbotService.chat(testAccountId, new ChatRequestDto(message, List.of(), null));
        }

        private InstrumentRecord instrument(String symbol, String currency) {
            return new InstrumentRecord(1L, symbol, symbol, "EQUITY", currency, "ACTIVE", null);
        }

        @Test
        @DisplayName("Unknown accounts, unknown intents and every kind of risky request are refused")
        void refusals() {
            when(accountMapper.findById(testAccountId)).thenReturn(null);
            assertFalse(chatbotService.chat(testAccountId, new ChatRequestDto("hi", List.of(), null)).success());

            assertTrue(say("tell me a joke", "UNSUPPORTED", List.of(), "none").response().contains("not sure"));
            assertTrue(say("drop tables", "ACCOUNT_QUERY", List.of(), "dangerous").response().contains("security"));
            assertTrue(say("admin settings", "ACCOUNT_QUERY", List.of(), "admin_access").response().contains("permission"));
            assertTrue(say("account 5", "ACCOUNT_QUERY", List.of(), "other_account").response().contains("own account"));
            assertTrue(say("odd", "ACCOUNT_QUERY", List.of(), "something_else").response().contains("cannot process"));
        }

        @Test
        @DisplayName("Portfolio: filters by the asked symbol, and says so when it is not held")
        void portfolio() {
            when(holdingService.getHoldingsByAccountId(testAccountId)).thenReturn(List.of(
                    new PositionResponseDto(testAccountId, "AAPL", 3L, new BigDecimal("100")),
                    new PositionResponseDto(testAccountId, "MSFT", 1L, new BigDecimal("300"))));
            assertTrue(say("my aapl", "PORTFOLIO_QUERY", List.of("aapl"), "none").response().contains("AAPL: 3 shares"));
            assertTrue(say("my tsla", "PORTFOLIO_QUERY", List.of("TSLA"), "none").response().contains("do not own any TSLA"));

            when(holdingService.getHoldingsByAccountId(testAccountId)).thenThrow(new IllegalStateException("db"));
            assertFalse(say("holdings", "PORTFOLIO_QUERY", List.of(), "none").success());
        }

        @Test
        @DisplayName("Instrument prices: shown in both currencies when they differ; unknown symbols and outages explained")
        void instrumentPrices() {
            when(instrumentQuoteService.getQuote("INFY")).thenReturn(new InstrumentQuoteDto("INFY", "INR", new BigDecimal("1500"), false));
            when(currencyConverter.toLedgerPrice(any(), eq("INR"), eq("USD"))).thenReturn(new BigDecimal("18"));
            assertTrue(say("infy price", "INSTRUMENT_QUERY", List.of("infy"), "none").response().contains("18.00 USD"));

            when(instrumentQuoteService.getQuote("NULLS")).thenReturn(new InstrumentQuoteDto("NULLS", null, null, false));
            assertTrue(say("nulls", "INSTRUMENT_QUERY", List.of("NULLS"), "none").response().contains("0.00 USD"));

            assertFalse(say("price?", "INSTRUMENT_QUERY", List.of(), "none").success());
            when(instrumentQuoteService.getQuote("ZZZ")).thenThrow(new com.trading.tradeapi.exception.InstrumentNotFoundException());
            assertTrue(say("zzz", "INSTRUMENT_QUERY", List.of("ZZZ"), "none").response().contains("Instrument not found"));
            when(instrumentQuoteService.getQuote("DOWN")).thenThrow(new IllegalStateException("feed"));
            assertTrue(say("down", "INSTRUMENT_QUERY", List.of("DOWN"), "none").response().contains("unavailable"));
        }

        @Test
        @DisplayName("Order history: counts, none yet, and a failure")
        void orderHistory() {
            when(orderMapper.findByAccountAndFilters(testAccountId, null, null, null))
                    .thenReturn(List.of(mock(com.trading.tradeapi.dto.OrderHistoryEntryDto.class)), List.of())
                    .thenThrow(new IllegalStateException("db"));
            assertTrue(say("orders", "ORDER_HISTORY", List.of(), "none").response().contains("1"));
            assertTrue(say("orders", "ORDER_HISTORY", List.of(), "none").response().contains("haven't placed"));
            assertFalse(say("orders", "ORDER_HISTORY", List.of(), "none").success());
        }

        @Test
        @DisplayName("An order is confirmed with yes and placed, or cancelled with anything else")
        void orderConfirmationFlow() {
            when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument("AAPL", "USD"));
            when(groqClient.extractOrderDetails(anyString())).thenReturn(
                    new LlmOrderExtractionDto("aapl", "BUY", new BigDecimal("2"), "LIMIT", new BigDecimal("190"), false, "HIGH"));
            ChatResponseDto ask = say("buy 2 aapl at 190", "ORDER_REQUEST", List.of("AAPL"), "none");
            assertEquals("order_confirmation", ask.messageType());

            com.trading.tradeapi.dto.OrderResponseDto placed = mock(com.trading.tradeapi.dto.OrderResponseDto.class);
            when(placed.orderId()).thenReturn("ORD-9");
            when(orderService.placeOrder(any())).thenReturn(placed);
            ChatResponseDto done = chatbotService.chat(testAccountId, new ChatRequestDto("yes", List.of(), ask.confirmationToken()));
            assertTrue(done.response().contains("ORD-9"));

            // The token is single use.
            assertTrue(chatbotService.chat(testAccountId, new ChatRequestDto("yes", List.of(), ask.confirmationToken()))
                    .response().contains("expired"));

            ChatResponseDto again = say("buy 2 aapl at 190", "ORDER_REQUEST", List.of("AAPL"), "none");
            assertEquals("Order cancelled.",
                    chatbotService.chat(testAccountId, new ChatRequestDto("no", List.of(), again.confirmationToken())).response());
        }

        @Test
        @DisplayName("A confirmation from another account, or a failing placement, places nothing")
        void confirmationGuards() {
            when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument("AAPL", "USD"));
            when(groqClient.extractOrderDetails(anyString())).thenReturn(
                    new LlmOrderExtractionDto("AAPL", "BUY", new BigDecimal("1"), "MARKET", null, false, "HIGH"));
            when(instrumentQuoteService.getQuote("AAPL")).thenThrow(new IllegalStateException("no quote"));
            ChatResponseDto ask = say("buy 1 aapl", "ORDER_REQUEST", List.of("AAPL"), "none");
            assertTrue(ask.response().contains("market price"));

            AccountRecord other = new AccountRecord(999L, 2L, "Other", "USD", BigDecimal.TEN, AccountStatus.ACTIVE, 1L, null);
            when(accountMapper.findById(999L)).thenReturn(other);
            assertTrue(chatbotService.chat(999L, new ChatRequestDto("yes", List.of(), ask.confirmationToken()))
                    .response().contains("Authorization failed"));

            ChatResponseDto again = say("buy 1 aapl", "ORDER_REQUEST", List.of("AAPL"), "none");
            when(orderService.placeOrder(any())).thenThrow(new IllegalStateException("closed"));
            assertTrue(chatbotService.chat(testAccountId, new ChatRequestDto("ok", List.of(), again.confirmationToken()))
                    .response().contains("Failed to place order"));
        }

        @Test
        @DisplayName("Sell all uses the holding; unclear orders, unknown symbols and no holding are explained")
        void orderRequestGuards() {
            when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument("AAPL", "USD"));
            when(holdingService.getHoldingsByAccountId(testAccountId))
                    .thenReturn(List.of(new PositionResponseDto(testAccountId, "AAPL", 4L, BigDecimal.ONE)));
            when(groqClient.extractOrderDetails(anyString())).thenReturn(
                    new LlmOrderExtractionDto("AAPL", "SELL", null, "LIMIT", new BigDecimal("200"), true, "HIGH"));
            ChatResponseDto sellAll = say("sell all aapl at 200", "ORDER_REQUEST", List.of("AAPL"), "none");
            assertEquals(0, new BigDecimal("4").compareTo(sellAll.orderConfirmation().quantity()));

            when(groqClient.extractOrderDetails(anyString())).thenReturn(null);
            assertTrue(say("buy something", "ORDER_REQUEST", List.of(), "none").response().contains("couldn't understand"));

            when(groqClient.extractOrderDetails(anyString())).thenReturn(
                    new LlmOrderExtractionDto("ZZZ", "BUY", BigDecimal.ONE, "MARKET", null, false, "HIGH"));
            assertTrue(say("buy zzz", "ORDER_REQUEST", List.of("ZZZ"), "none").response().contains("ZZZ not found"));

            when(groqClient.extractOrderDetails(anyString())).thenReturn(
                    new LlmOrderExtractionDto("AAPL", "SELL", null, "MARKET", null, true, "HIGH"));
            when(holdingService.getHoldingsByAccountId(testAccountId)).thenThrow(new IllegalStateException("db"));
            assertTrue(say("sell all aapl", "ORDER_REQUEST", List.of("AAPL"), "none").response().contains("Invalid quantity"));
        }
    }
}
