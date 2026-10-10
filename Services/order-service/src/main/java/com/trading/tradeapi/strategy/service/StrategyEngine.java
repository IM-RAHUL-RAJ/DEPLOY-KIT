package com.trading.tradeapi.strategy.service;

import com.trading.tradeapi.marketdata.CandleService;
import com.trading.tradeapi.marketdata.QuoteEvent;
import com.trading.tradeapi.portfolio.api.PortfolioPositions;
import com.trading.tradeapi.portfolio.dto.PricedPositionDto;
import com.trading.tradeapi.strategy.entity.StrategyExecutionRecord;
import com.trading.tradeapi.strategy.entity.StrategyRecord;
import com.trading.tradeapi.strategy.mapper.StrategyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Runs every active strategy on a symbol against one quote from the price stream.
 *
 * <p>For each strategy: read the rule's state on the daily closes plus this price, store
 * it, and if the state changed in the way the rule trades on, act. Acting is guarded,
 * in this order, at the moment of decision and never only when the strategy was saved:
 * <ol>
 *   <li>the strategy is still ACTIVE (re-read, so a pause a moment ago wins);</li>
 *   <li>a SELL needs the shares: no holding means nothing to sell, skipped;</li>
 *   <li>one decision per strategy and market-data event, by a unique row, so a
 *       redelivered quote cannot trade twice. The row is written only while the
 *       strategy is ACTIVE at that instant, so a pause takes effect before the order,
 *       not after the cycle.</li>
 * </ol>
 * Only then is the order placed, through the Trade REST API. A price target is one-shot:
 * once its order is placed it is COMPLETED, and if a limit refuses it, it stops. Three failures in a row
 * stop the strategy with the reason; the customer resumes it once the cause is fixed.
 */
@Service
public class StrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final StrategyMapper mapper;
    private final CandleService candles;
    private final PortfolioPositions portfolio;
    private final StrategyOrderPlacer placer;
    private final Clock clock;

    @Autowired
    public StrategyEngine(StrategyMapper mapper, CandleService candles, PortfolioPositions portfolio,
                          StrategyOrderPlacer placer) {
        this(mapper, candles, portfolio, placer, Clock.systemUTC());
    }

    StrategyEngine(StrategyMapper mapper, CandleService candles, PortfolioPositions portfolio,
                   StrategyOrderPlacer placer, Clock clock) {
        this.mapper = mapper;
        this.candles = candles;
        this.portfolio = portfolio;
        this.placer = placer;
        this.clock = clock;
    }

    /** Evaluates one quote; returns the decisions taken, for logging and tests. */
    public List<StrategyExecutionRecord> onQuote(QuoteEvent quote) {
        List<StrategyRecord> strategies = mapper.findActiveBySymbol(quote.symbol());
        if (quote.stale()) {
            // An indicator rule never acts on a price the feed calls old: its signal is the
            // price itself. A price target may: it only decides that a market order is due,
            // and the executor prices that order with its own fresh quote. Skipping stale
            // quotes left targets waiting for as long as the feed lagged.
            strategies = strategies.stream()
                    .filter(s -> StrategyRules.PRICE_TARGET.equals(s.getStrategyType()))
                    .toList();
        }
        if (strategies.isEmpty()) {
            return List.of();
        }
        // Only the indicator rules need history; a symbol watched by price targets alone
        // never asks the poller for candles it would not use.
        boolean needsHistory = strategies.stream()
                .anyMatch(s -> !StrategyRules.PRICE_TARGET.equals(s.getStrategyType()));
        List<Double> closes = needsHistory
                ? candles.storedDailyCloses(List.of(quote.symbol())).get(quote.symbol())
                : List.of();
        // A decision is keyed by the quote itself, not by how it arrived: the stream and the
        // sweep of the poller's cache can both deliver the same polled price, and it must
        // trade once.
        String quoteId = quote.symbol() + "@" + quote.observedAt();

        List<StrategyExecutionRecord> decisions = new java.util.ArrayList<>();
        for (StrategyRecord strategy : strategies) {
            try {
                evaluate(strategy, closes, quote, eventIdFor(strategy, quoteId)).ifPresent(decisions::add);
            } catch (RuntimeException e) {
                // One broken strategy must not stop the others on the same quote.
                log.error("Strategy {} failed on {}: {}", strategy.getStrategyId(), quoteId, e.getMessage());
            }
        }
        return decisions;
    }

    /**
     * The decision key for this strategy on this quote. After a failed order the key gains the
     * attempt number, so the next sweep may try again on the same quote: a closed market's
     * quote never changes, and without this a target whose one order failed would wait for
     * ever. The order lookup before each attempt keeps a retry from trading twice.
     */
    static String eventIdFor(StrategyRecord strategy, String quoteId) {
        int failures = strategy.getConsecutiveFailures() == null ? 0 : strategy.getConsecutiveFailures();
        return failures == 0 ? quoteId : quoteId + "#retry" + failures;
    }

    private Optional<StrategyExecutionRecord> evaluate(StrategyRecord strategy, List<Double> closes,
                                                       QuoteEvent quote, String eventId) {
        double price = quote.price().doubleValue();
        Optional<StrategyRules.Reading> reading = StrategyRules.read(strategy, closes, price);
        String state = reading.map(StrategyRules.Reading::state).orElse(null);
        mapper.recordEvaluation(strategy.getStrategyId(), state, quote.price(), quote.observedAt());
        if (state == null) {
            log.debug("Strategy {}: not enough history for {}", strategy.getStrategyId(), quote.symbol());
            return Optional.empty();
        }

        Optional<String> signal = StrategyRules.triggerFor(strategy, strategy.getLastState(), state);
        if (signal.isEmpty()) {
            return Optional.empty();
        }
        log.info("Strategy {} ({} {}) fired {} at {}: {} -> {}", strategy.getStrategyId(),
                strategy.getStrategyType(), strategy.getSymbol(), signal.get(), price,
                strategy.getLastState(), state);
        return Optional.ofNullable(act(strategy.getStrategyId(), signal.get(), quote.price(), eventId));
    }

    private StrategyExecutionRecord act(long strategyId, String signal, BigDecimal price, String eventId) {
        StrategyRecord s = mapper.findById(strategyId);
        if (s == null || !"ACTIVE".equals(s.getStatus())) {
            return null;
        }

        String refusal = refusal(s, signal, price);
        StrategyExecutionRecord execution = new StrategyExecutionRecord(strategyId, eventId, signal, price,
                s.getQuantity(), refusal == null ? "PENDING" : "SKIPPED", refusal);
        try {
            if (mapper.insertExecution(execution) == 0) {
                log.info("Strategy {} was paused or stopped before acting on {}; no order", strategyId, eventId);
                return null;
            }
        } catch (DuplicateKeyException e) {
            log.info("Strategy {} already decided on {}; redelivery ignored", strategyId, eventId);
            return null;
        }
        boolean oneShot = StrategyRules.PRICE_TARGET.equals(s.getStrategyType());
        String idempotencyKey = "strategy-" + strategyId + "-" + eventId;
        if (oneShot && refusal == null) {
            // A target trades once. If an earlier attempt's order exists after all (its call
            // timed out but the order went in), finish with that order instead of a second one.
            String earlier = mapper.findLatestOrderIdForStrategy(strategyId);
            if (earlier != null) {
                return placed(execution, s, signal, earlier, true);
            }
        }
        if (refusal != null) {
            log.info("Strategy {} {} skipped: {}", strategyId, signal, refusal);
            if (oneShot) {
                // A target stays hit on every following quote: stop rather than refuse forever.
                mapper.stop(strategyId, truncate("Target reached but not traded: " + refusal, 200));
            }
            return execution;
        }

        try {
            boolean limit = oneShot && "LIMIT".equals(s.getOrderType());
            StrategyOrderPlacer.PlacedOrder order = placer.place(strategyId, s.getAccountId(), s.getSymbol(),
                    signal, s.getQuantity(), limit ? "LIMIT" : "MARKET", limit ? s.getTargetPrice() : null,
                    idempotencyKey);
            return placed(execution, s, signal, order.orderId(), oneShot);
        } catch (StrategyOrderPlacer.OrderPlacementException e) {
            // A timeout or a lost response does not mean no order: the order API may have
            // accepted it. The orders table answers; only a missing order is a failure.
            String landed = mapper.findOrderIdByIdempotencyKey(idempotencyKey);
            if (landed != null) {
                log.info("Strategy {}: the call failed ({}) but order {} was placed", strategyId,
                        e.getMessage(), landed);
                return placed(execution, s, signal, landed, oneShot);
            }
            String reason = truncate(e.getMessage(), 300);
            mapper.completeExecution(execution.getExecutionId(), "FAILED", null, reason);
            mapper.incrementFailures(strategyId);
            execution.setStatus("FAILED");
            execution.setReason(reason);
            int failures = (s.getConsecutiveFailures() == null ? 0 : s.getConsecutiveFailures()) + 1;
            log.warn("Strategy {} order failed ({} in a row): {}", strategyId, failures, reason);
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                mapper.stop(strategyId, truncate("Stopped after " + failures + " failed orders. Last: " + reason, 200));
                log.warn("Strategy {} stopped after {} consecutive failures", strategyId, failures);
            }
        }
        return execution;
    }

    private StrategyExecutionRecord placed(StrategyExecutionRecord execution, StrategyRecord s, String signal,
                                           String orderId, boolean oneShot) {
        long strategyId = s.getStrategyId();
        mapper.completeExecution(execution.getExecutionId(), "PLACED", orderId, null);
        mapper.resetFailures(strategyId);
        execution.setStatus("PLACED");
        execution.setOrderId(orderId);
        log.info("Strategy {} placed {} {} {}: order {}", strategyId, signal, s.getQuantity(), s.getSymbol(), orderId);
        if (oneShot) {
            mapper.complete(strategyId, truncate("Target reached: order " + orderId + " placed", 200));
        }
        return execution;
    }

    /** Why this trade must not happen, or null when it may. */
    private String refusal(StrategyRecord s, String signal, BigDecimal price) {
        long held = portfolio.pricedPositions(s.getAccountId()).stream()
                .filter(p -> p.symbol().equalsIgnoreCase(s.getSymbol()))
                .mapToLong(PricedPositionDto::quantity)
                .sum();
        if (StrategyRules.SELL.equals(signal) && held < s.getQuantity()) {
            return "Sell signal, but the account holds " + held + " of the " + s.getQuantity() + " to sell";
        }
        return null;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
