package com.trading.tradeapi.chatbot.service;

import com.trading.tradeapi.chatbot.client.GroqLlmClient;
import com.trading.tradeapi.chatbot.dto.*;
import com.trading.tradeapi.dto.OrderHistoryEntryDto;
import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderType;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.HoldingMapper;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.mapper.OrderMapper;
import com.trading.tradeapi.service.AccountService;
import com.trading.tradeapi.service.CurrencyConverter;
import com.trading.tradeapi.service.HoldingService;
import com.trading.tradeapi.service.InstrumentQuoteService;
import com.trading.tradeapi.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates chatbot interactions with security guardrails.
 * Handles intent detection, data retrieval, and order confirmation flows.
 */
@Service
public class ChatbotService {

    private static final Logger logger = LoggerFactory.getLogger(ChatbotService.class);

    // Store pending order confirmations: key = confirmation token
    private final Map<String, PendingOrderConfirmation> pendingConfirmations = new ConcurrentHashMap<>();

    private final GroqLlmClient groqClient;
    private final AccountService accountService;
    private final AccountMapper accountMapper;
    private final HoldingMapper holdingMapper;
    private final InstrumentMapper instrumentMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final HoldingService holdingService;
    private final InstrumentQuoteService instrumentQuoteService;
    private final CurrencyConverter currencyConverter;

    public ChatbotService(
            GroqLlmClient groqClient,
            AccountService accountService,
            AccountMapper accountMapper,
            HoldingMapper holdingMapper,
            InstrumentMapper instrumentMapper,
            OrderMapper orderMapper,
            OrderService orderService,
            HoldingService holdingService,
            InstrumentQuoteService instrumentQuoteService,
            CurrencyConverter currencyConverter) {
        this.groqClient = groqClient;
        this.accountService = accountService;
        this.accountMapper = accountMapper;
        this.holdingMapper = holdingMapper;
        this.instrumentMapper = instrumentMapper;
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.holdingService = holdingService;
        this.instrumentQuoteService = instrumentQuoteService;
        this.currencyConverter = currencyConverter;
    }

    /**
     * Main chat endpoint handler
     */
    @Transactional
    public ChatResponseDto chat(Long authenticatedAccountId, ChatRequestDto request) {
        if (authenticatedAccountId == null) {
            return errorResponse("Authentication required");
        }

        // Validate account exists and is active
        AccountRecord account = accountMapper.findById(authenticatedAccountId);
        if (account == null) {
            return errorResponse("Account not found");
        }

        try {
            accountService.validateAccountActive(account);
        } catch (AccountNotActiveException e) {
            return errorResponse("Your account is not active");
        }

        String userMessage = request.message().trim();
        if (userMessage.isEmpty()) {
            return errorResponse("Please enter a message");
        }

        // If this is a confirmation token (for pending order), handle confirmation
        if (request.confirmationToken() != null && !request.confirmationToken().isEmpty()) {
            return handleOrderConfirmation(authenticatedAccountId, request.confirmationToken(), userMessage);
        }

        // Analyze intent and safety
        LlmIntentAnalysisDto intentAnalysis = groqClient.analyzeIntent(userMessage);
        if (intentAnalysis == null) {
            return errorResponse("I could not analyse that request right now. Please try again.");
        }

        // Check for dangerous/risky requests
        if (intentAnalysis.riskFlags() != null && !intentAnalysis.riskFlags().equals("none")) {
            return handleRiskyRequest(intentAnalysis.riskFlags());
        }

        // Route to appropriate handler based on intent
        return switch (intentAnalysis.intent()) {
            case "ACCOUNT_QUERY" -> handleAccountQuery(authenticatedAccountId, userMessage, intentAnalysis);
            case "PORTFOLIO_QUERY" -> handlePortfolioQuery(authenticatedAccountId, userMessage, intentAnalysis);
            case "INSTRUMENT_QUERY" -> handleInstrumentQuery(authenticatedAccountId, userMessage, intentAnalysis);
            case "ORDER_REQUEST" -> handleOrderRequest(authenticatedAccountId, userMessage, intentAnalysis);
            case "ORDER_HISTORY" -> handleOrderHistory(authenticatedAccountId, userMessage, intentAnalysis);
            default -> errorResponse("I'm not sure how to help with that. Try asking about your account, portfolio, or placing an order.");
        };
    }

    /**
     * Handle account-related queries (cash balance, account info)
     */
    private ChatResponseDto handleAccountQuery(Long accountId, String userMessage, LlmIntentAnalysisDto intent) {
        try {
            AccountRecord account = accountMapper.findById(accountId);
            if (account == null) {
                return errorResponse("Account not found");
            }

            String response = String.format(
                    "Your cash balance is $%,.2f %s. Your account is %s.",
                    account.getCashBalance(),
                    account.getCurrency(),
                    account.getStatus().toString().toLowerCase()
            );

            return successResponse(response);
        } catch (Exception e) {
            logger.error("Error handling account query: {}", e.getMessage(), e);
            return errorResponse("Unable to retrieve account information");
        }
    }

    /**
     * Handle portfolio-related queries (holdings, positions)
     */
    private ChatResponseDto handlePortfolioQuery(Long accountId, String userMessage, LlmIntentAnalysisDto intent) {
        try {
            List<PositionResponseDto> holdings = holdingService.getHoldingsByAccountId(accountId);

            if (holdings.isEmpty()) {
                return successResponse("You currently have no holdings.");
            }

            // Check for specific instrument queries in the message
            List<String> requestedSymbols = intent.entities().stream()
                    .map(String::toUpperCase)
                    .collect(Collectors.toList());

            List<PositionResponseDto> filtered = holdings;
            if (!requestedSymbols.isEmpty()) {
                filtered = holdings.stream()
                        .filter(h -> requestedSymbols.contains(h.symbol()))
                        .collect(Collectors.toList());
            }

            if (filtered.isEmpty() && !requestedSymbols.isEmpty()) {
                return successResponse(String.format("You do not own any %s.", String.join(", ", requestedSymbols)));
            }

            // Format holdings response
            StringBuilder response = new StringBuilder("Your current holdings:\n");
            BigDecimal totalValue = BigDecimal.ZERO;

            for (PositionResponseDto holding : filtered) {
                BigDecimal positionValue = BigDecimal.valueOf(holding.quantity()).multiply(holding.averageCost());
                totalValue = totalValue.add(positionValue);
                response.append(String.format("• %s: %d shares @ $%,.2f each (value: $%,.2f)\n",
                        holding.symbol(),
                        holding.quantity(),
                        holding.averageCost(),
                        positionValue));
            }

            response.append(String.format("\nTotal portfolio value: $%,.2f", totalValue));
            return successResponse(response.toString());
        } catch (Exception e) {
            logger.error("Error handling portfolio query: {}", e.getMessage(), e);
            return errorResponse("Unable to retrieve portfolio information");
        }
    }

    /**
     * Handle instrument price queries
     */
    private ChatResponseDto handleInstrumentQuery(Long accountId, String userMessage, LlmIntentAnalysisDto intent) {
        try {
            if (intent.entities().isEmpty()) {
                return errorResponse("Please specify which instrument you'd like to know about.");
            }

            // Get account to retrieve account currency
            AccountRecord account = accountMapper.findById(accountId);
            if (account == null) {
                return errorResponse("Account not found");
            }

            String symbol = intent.entities().get(0).toUpperCase();
            var quote = instrumentQuoteService.getQuote(symbol);

            String instrumentCurrency = quote.quoteCurrency() != null ? quote.quoteCurrency() : "USD";
            String accountCurrency = account.getCurrency();
            BigDecimal instrumentPrice = quote.price() != null ? quote.price() : BigDecimal.ZERO;

            // If currencies differ, show price in both currencies
            if (!instrumentCurrency.equals(accountCurrency)) {
                BigDecimal accountCurrencyPrice = currencyConverter.toLedgerPrice(instrumentPrice, instrumentCurrency, accountCurrency);
                String response = String.format(
                        "The current price of %s is %,.2f %s (%.2f %s).",
                        symbol,
                        instrumentPrice,
                        instrumentCurrency,
                        accountCurrencyPrice,
                        accountCurrency
                );
                return successResponse(response);
            } else {
                // Same currency, show single price
                String response = String.format(
                        "The current price of %s is %,.2f %s.",
                        symbol,
                        instrumentPrice,
                        instrumentCurrency
                );
                return successResponse(response);
            }
        } catch (InstrumentNotFoundException e) {
            return errorResponse("Instrument not found. Please check the symbol and try again.");
        } catch (Exception e) {
            logger.error("Error handling instrument query: {}", e.getMessage(), e);
            return errorResponse("Unable to retrieve instrument information. The market data service may be unavailable.");
        }
    }

    /**
     * Handle order history queries
     */
    private ChatResponseDto handleOrderHistory(Long accountId, String userMessage, LlmIntentAnalysisDto intent) {
        try {
            // Get all orders for the account (no date/status filter)
            List<OrderHistoryEntryDto> orders = orderMapper.findByAccountAndFilters(accountId, null, null, null);

            if (orders == null || orders.isEmpty()) {
                return successResponse("You haven't placed any orders yet.");
            }

            // Get summary of orders
            int totalOrders = orders.size();
            int executedOrders = (int) orders.stream().filter(o -> "EXECUTED".equals(o.status())).count();
            int cancelledOrders = (int) orders.stream().filter(o -> "CANCELLED".equals(o.status())).count();
            int pendingOrders = (int) orders.stream().filter(o -> "PENDING".equals(o.status())).count();

            String response = String.format(
                    "You have placed %d order(s) in total:\n• Executed: %d\n• Pending: %d\n• Cancelled: %d",
                    totalOrders,
                    executedOrders,
                    pendingOrders,
                    cancelledOrders
            );

            return successResponse(response);
        } catch (Exception e) {
            logger.error("Error handling order history query: {}", e.getMessage(), e);
            return errorResponse("Unable to retrieve order history information");
        }
    }

    /**
     * Handle order placement requests (with confirmation flow)
     */
    private ChatResponseDto handleOrderRequest(Long accountId, String userMessage, LlmIntentAnalysisDto intent) {
        try {
            // Extract order details using LLM
            LlmOrderExtractionDto orderExtraction = groqClient.extractOrderDetails(userMessage);

            if (orderExtraction == null || orderExtraction.symbol() == null || orderExtraction.side() == null) {
                return errorResponse("I couldn't understand the order details. Please specify: instrument, BUY/SELL, quantity, and order type (MARKET/LIMIT).");
            }

            // Validate instrument exists
            String symbol = orderExtraction.symbol().toUpperCase();
            InstrumentRecord instrument = instrumentMapper.findBySymbol(symbol);
            if (instrument == null) {
                return errorResponse(String.format("Instrument %s not found. Please check the symbol.", symbol));
            }

            // Get account for currency conversion
            AccountRecord account = accountMapper.findById(accountId);
            if (account == null) {
                return errorResponse("Account not found");
            }

            // Get current price for confirmation
            BigDecimal price = orderExtraction.limitPrice();
            String instrumentCurrency = instrument.getQuoteCurrency();
            String accountCurrency = account.getCurrency();
            BigDecimal displayPrice = price;  // Price shown to user (in instrument's native currency)

            if ("MARKET".equals(orderExtraction.orderType())) {
                try {
                    var quote = instrumentQuoteService.getQuote(symbol);
                    price = quote.price();
                    displayPrice = price;
                } catch (Exception e) {
                    logger.warn("Could not get market price for {}: {}", symbol, e.getMessage());
                }
            } else if ("LIMIT".equals(orderExtraction.orderType()) && price != null) {
                // User provides limit price in the instrument's native currency (e.g., INR for Indian stocks)
                // Use price as-is without conversion
                displayPrice = price;
            }

            // Validate order parameters
            BigDecimal quantity = orderExtraction.quantity();
            if (orderExtraction.sellAll()) {
                quantity = getHoldingQuantity(accountId, symbol);
            }

            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                return errorResponse("Invalid quantity. Please specify a valid number of shares.");
            }

            // Check user has sufficient funds/holdings
            String validationError = validateOrder(accountId, symbol, orderExtraction.side(), quantity, price);
            if (validationError != null) {
                return errorResponse(validationError);
            }

            // Create pending confirmation
            String confirmationToken = generateConfirmationToken();
            BigDecimal estimatedCost = quantity.multiply(displayPrice != null ? displayPrice : BigDecimal.ZERO);

            PendingOrderConfirmation pending = new PendingOrderConfirmation(
                    accountId,
                    symbol,
                    orderExtraction.side(),
                    quantity,
                    orderExtraction.orderType(),
                    price,  // Store converted price (instrument currency)
                    estimatedCost,
                    Instant.now()
            );
            pendingConfirmations.put(confirmationToken, pending);

            // Return confirmation request
            OrderConfirmationData confirmation = new OrderConfirmationData(
                    instrument.getTicker(),
                    symbol,
                    orderExtraction.side(),
                    quantity,
                    orderExtraction.orderType(),
                    displayPrice,  // Show display price in instrument's native currency
                    estimatedCost,
                    String.format("Ready to place a %s order for %,.2f %s shares at %s? Reply 'yes' to confirm.",
                            orderExtraction.side(),
                            quantity,
                            symbol,
                            "MARKET".equals(orderExtraction.orderType()) ? "market price" : displayPrice + " " + instrumentCurrency)
            );

            return new ChatResponseDto(
                    confirmation.userConfirmationQuestion(),
                    "order_confirmation",
                    Instant.now(),
                    confirmation,
                    confirmationToken,
                    true
            );
        } catch (Exception e) {
            logger.error("Error handling order request: {}", e.getMessage(), e);
            return errorResponse("Unable to process order request. Please try again.");
        }
    }

    /**
     * Handle order confirmation (yes/no response)
     */
    private ChatResponseDto handleOrderConfirmation(Long accountId, String confirmationToken, String response) {
        PendingOrderConfirmation pending = pendingConfirmations.get(confirmationToken);
        if (pending == null) {
            return errorResponse("Confirmation expired. Please place the order again.");
        }

        // Check if confirmation is still valid (not older than 5 minutes)
        if (Instant.now().isAfter(pending.createdAt().plusSeconds(300))) {
            pendingConfirmations.remove(confirmationToken);
            return errorResponse("Confirmation expired. Please place the order again.");
        }

        // Check if account ID matches (security)
        if (!pending.accountId().equals(accountId)) {
            logger.warn("Confirmation token account mismatch: {} vs {}", pending.accountId(), accountId);
            pendingConfirmations.remove(confirmationToken);
            return errorResponse("Authorization failed");
        }

        String lowerResponse = response.trim().toLowerCase();
        // Only accept explicit "yes" confirmations
        if (!("yes".equals(lowerResponse) || "y".equals(lowerResponse) || "confirm".equals(lowerResponse) || "ok".equals(lowerResponse))) {
            pendingConfirmations.remove(confirmationToken);
            return successResponse("Order cancelled.");
        }

        // Execute the order through the existing OrderService
        try {
            // Convert BigDecimal quantity to Long for the order request
            Long quantityLong = pending.quantity().longValue();
            
            // Determine OrderType from the pending order
            OrderType orderType = "MARKET".equals(pending.orderType()) ? OrderType.MARKET : OrderType.LIMIT;
            
            PlaceOrderRequestDto orderRequest = new PlaceOrderRequestDto(
                    accountId,
                    pending.symbol(),
                    OrderSide.valueOf(pending.side()),
                    quantityLong,
                    pending.price(),
                    orderType,
                    "CNF-" + UUID.randomUUID() // Generate unique idempotency key
            );

            var orderResponse = orderService.placeOrder(orderRequest);
            pendingConfirmations.remove(confirmationToken);

            return new ChatResponseDto(
                    String.format("Your %s order for %,.2f %s shares has been submitted (Order ID: %s).",
                            pending.side(),
                            pending.quantity(),
                            pending.symbol(),
                            orderResponse.orderId()),
                    "order_success",
                    Instant.now(),
                    null,
                    null,
                    true
            );
        } catch (Exception e) {
            logger.error("Error executing order: {}", e.getMessage(), e);
            pendingConfirmations.remove(confirmationToken);
            return errorResponse("Failed to place order: " + e.getMessage());
        }
    }

    /**
     * Security guardrail: reject dangerous requests
     */
    private ChatResponseDto handleRiskyRequest(String riskFlags) {
        if (riskFlags.contains("dangerous")) {
            logger.warn("Dangerous request detected: {}", riskFlags);
            return errorResponse("I cannot process that request as it may compromise security or data integrity.");
        }
        if (riskFlags.contains("admin_access")) {
            return errorResponse("You don't have permission to access that information.");
        }
        if (riskFlags.contains("other_account")) {
            return errorResponse("You can only access your own account information.");
        }
        return errorResponse("I cannot process that request.");
    }

    /**
     * Validate order parameters
     */
    private String validateOrder(Long accountId, String symbol, String side, BigDecimal quantity, BigDecimal price) {
        AccountRecord account = accountMapper.findById(accountId);
        if (account == null) {
            return "Account not found";
        }

        // Let the order service handle validation and rejection
        // Orders with insufficient funds or holdings will be rejected by the backend
        // This matches the behavior of orders placed through the regular order ticket
        return null;
    }

    /**
     * Get holding quantity for an account and symbol
     */
    private BigDecimal getHoldingQuantity(Long accountId, String symbol) {
        try {
            List<PositionResponseDto> holdings = holdingService.getHoldingsByAccountId(accountId);
            return holdings.stream()
                    .filter(h -> symbol.equalsIgnoreCase(h.symbol()))
                    .map(h -> BigDecimal.valueOf(h.quantity()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            logger.error("Error getting holding quantity: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generate a unique confirmation token
     */
    private String generateConfirmationToken() {
        return "CNF-" + UUID.randomUUID().toString();
    }

    private ChatResponseDto successResponse(String message) {
        return new ChatResponseDto(message, "text", Instant.now(), null, null, true);
    }

    private ChatResponseDto errorResponse(String message) {
        return new ChatResponseDto(message, "error", Instant.now(), null, null, false);
    }

    /**
     * Internal record for pending order confirmations
     */
    private record PendingOrderConfirmation(
            Long accountId,
            String symbol,
            String side,
            BigDecimal quantity,
            String orderType,
            BigDecimal price,
            BigDecimal estimatedCost,
            Instant createdAt
    ) {}
}
