package com.trading.tradeapi.service;

import com.trading.tradeapi.dto.OrderHistoryEntryDto;
import com.trading.tradeapi.dto.OrderResponseDto;
import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.entity.OrderRecord;
import com.trading.tradeapi.enums.OrderStatus;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.exception.DuplicateOrderException;
import com.trading.tradeapi.exception.OrderNotFoundException;
import com.trading.tradeapi.kafka.producer.OrderProducer;
import com.trading.tradeapi.kafka.producer.TradeEventProducer;
import com.trading.tradeapi.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class OrderService {
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    /** "ORD-123" as the API returns it, or the bare numeric key. */
    private static final Pattern ORDER_ID = Pattern.compile("^(ORD-)?\\d{1,18}$");

    private final OrderMapper orderMapper;
    private final OrderValidator orderValidator;
    private final OrderProducer orderProducer;
    private final TradeEventProducer tradeEventProducer;
    private final MarketOrderPricer marketOrderPricer;

    public OrderService(OrderMapper orderMapper,
                        OrderValidator orderValidator,
                        OrderProducer orderProducer,
                        TradeEventProducer tradeEventProducer,
                        MarketOrderPricer marketOrderPricer) {
        this.orderMapper = orderMapper;
        this.orderValidator = orderValidator;
        this.orderProducer = orderProducer;
        this.tradeEventProducer = tradeEventProducer;
        this.marketOrderPricer = marketOrderPricer;
    }

    /**
     * Validates, records the order as NEW, and publishes ORDER_PLACED once the row
     * has committed. Publishing inside the transaction could hand the executor an
     * order it cannot see yet, or announce one that then rolls back; publishing
     * after the commit risks only an unannounced order, which the executor's
     * poller recovers from the table. The recoverable failure is the one chosen.
     */
    @Transactional
    public OrderResponseDto placeOrder(PlaceOrderRequestDto request) {
        // A market order carries no price from the client: it is priced here from the
        // live book and re-denominated into the account currency, so the funds check
        // and the eventual fill are compared in the same units.
        PlaceOrderRequestDto effectiveRequest = normalise(marketOrderPricer.price(normalise(request)));
        orderValidator.validate(effectiveRequest);

        Instant receivedAt = Instant.now();
        Long orderId = insertOrder(effectiveRequest, receivedAt);

        afterCommit(() -> orderProducer.publishOrderPlaced(
                orderId,
                effectiveRequest.accountId(),
                effectiveRequest.ticker(),
                effectiveRequest.side().toString(),
                effectiveRequest.quantity(),
                effectiveRequest.price(),
                effectiveRequest.idempotencyKey(),
                receivedAt));

        // NEW is the working state: the executor resolves it asynchronously.
        return new OrderResponseDto(
                canonicalOrderId(orderId.toString()),
                OrderStatus.NEW,
                "Order placed successfully",
                effectiveRequest.ticker(),
                effectiveRequest.side(),
                effectiveRequest.quantity(),
                effectiveRequest.price(),
                effectiveRequest.orderType(),
                null
        );
    }

    private Long insertOrder(PlaceOrderRequestDto request, Instant receivedAt) {
        OrderRecord orderRecord = new OrderRecord(
                null,
                request.accountId(),
                request.ticker(),
                request.side(),
                BigDecimal.valueOf(request.quantity()),
                request.price(),
                OrderStatus.NEW,
                request.orderType(),
                receivedAt,
                request.idempotencyKey()
        );
        try {
            orderMapper.insertOrder(orderRecord);
        } catch (DuplicateKeyException e) {
            // Two requests with the same key raced past the lookup; the unique index
            // decided. Same answer as the lookup would have given.
            throw new DuplicateOrderException("ORD-409", "Order with this idempotency key already exists");
        }
        logger.info("Order {} recorded as NEW for account {}", orderRecord.getOrderId(), request.accountId());
        return orderRecord.getOrderId();
    }

    /**
     * Cancels a working order. Only the account that placed it may cancel it; an
     * administrator token, which carries no account, may cancel any.
     *
     * @param callerAccountId the account in the caller's verified token, or null for an administrator
     */
    @Transactional
    public OrderResponseDto cancelOrder(String id, Long callerAccountId) {
        if (id == null || !ORDER_ID.matcher(id).matches()) {
            throw new OrderNotFoundException();
        }
        OrderRecord order = orderMapper.findById(id);
        if (order == null) {
            throw new OrderNotFoundException();
        }
        if (callerAccountId != null && !callerAccountId.equals(order.getAccountId())) {
            throw new AccountNotActiveException();
        }
        if (order.getStatus() != OrderStatus.NEW || orderMapper.cancelOrder(id) == 0) {
            // Already filled, rejected or cancelled, possibly by the executor a moment ago.
            throw new DuplicateOrderException("ORD-409", "Order is not cancellable");
        }

        afterCommit(() -> tradeEventProducer.publishCancelled(order));

        return new OrderResponseDto(
                canonicalOrderId(id),
                OrderStatus.CANCELLED,
                "Order cancelled",
                order.getTicker(),
                order.getSide(),
                order.getQuantity().longValue(),
                order.getPrice(),
                order.getOrderType(),
                order.getExecutedPrice()
        );
    }

    @Transactional(readOnly = true)
    public List<OrderHistoryEntryDto> getOrders(Long accountId, String status, Instant from, Instant to) {
        return orderMapper.findByAccountAndFilters(accountId, status, from, to);
    }

    /** Symbols are stored upper-case whatever the client typed, so "aapl" and "AAPL" are one instrument. */
    private static PlaceOrderRequestDto normalise(PlaceOrderRequestDto request) {
        if (request == null || request.ticker() == null) {
            return request;
        }
        return new PlaceOrderRequestDto(
                request.accountId(),
                request.ticker().trim().toUpperCase(Locale.ROOT),
                request.side(),
                request.quantity(),
                request.price(),
                request.orderType(),
                request.idempotencyKey());
    }

    /**
     * Runs the action once the current transaction commits, or now when there is
     * none. A failure is logged, never thrown: by then the order is committed, and
     * turning a publishing problem into an error response would tell the trader an
     * order failed that the executor will still fill.
     */
    private static void afterCommit(Runnable action) {
        Runnable guarded = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                logger.error("Post-commit publish failed; the order is committed and stays recoverable", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }

    /** Canonical, stable, non-opaque order id derived from the numeric primary key. */
    static String canonicalOrderId(String rawId) {
        if (rawId == null) return null;
        return rawId.startsWith("ORD-") ? rawId : "ORD-" + rawId;
    }
}
