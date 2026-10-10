package com.trading.tradeapi.strategy.service;

import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.marketdata.CandleService;
import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import com.trading.tradeapi.strategy.dto.CreateStrategyRequest;
import com.trading.tradeapi.strategy.dto.StrategyDto;
import com.trading.tradeapi.strategy.dto.StrategyExecutionDto;
import com.trading.tradeapi.strategy.dto.StrategyPreview;
import com.trading.tradeapi.strategy.entity.StrategyRecord;
import com.trading.tradeapi.strategy.mapper.StrategyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A customer's strategies: create, list, pause, resume, delete, and see what each one
 * decided. Every method takes the owning account, and every query is scoped by it.
 */
@Service
public class StrategyService {

    private static final Logger log = LoggerFactory.getLogger(StrategyService.class);

    static final int MAX_STRATEGIES = 10;
    static final int DEFAULT_ORDERS_PER_DAY = 5;
    static final int DEFAULT_SHORT_WINDOW = 20;
    static final int DEFAULT_LONG_WINDOW = 50;
    static final int DEFAULT_BAND_PERIOD = 20;
    static final java.math.BigDecimal DEFAULT_BAND_K = java.math.BigDecimal.valueOf(2);
    /** When no maxNotional is provided, do not impose a strategy-side notional cap. */
    static final java.math.BigDecimal DEFAULT_UNBOUNDED_MAX_NOTIONAL = new java.math.BigDecimal("9999999999999999.99");
    /** Without a stated maximum position, a strategy may build up to this many orders' worth. */
    static final int DEFAULT_POSITION_ORDERS = 5;
    /** Headroom over the target for a price target's default maximum order value. */
    static final java.math.BigDecimal TARGET_HEADROOM = new java.math.BigDecimal("1.2");

    private final StrategyMapper mapper;
    private final InstrumentMapper instruments;
    private final CandleService candles;
    private final MarketDataService marketData;
    private final com.trading.tradeapi.portfolio.api.PortfolioPositions portfolio;
    private final org.springframework.beans.factory.ObjectProvider<StrategyQuoteSweeper> sweeper;

    public StrategyService(StrategyMapper mapper, InstrumentMapper instruments,
                           CandleService candles, MarketDataService marketData,
                           com.trading.tradeapi.portfolio.api.PortfolioPositions portfolio,
                           org.springframework.beans.factory.ObjectProvider<StrategyQuoteSweeper> sweeper) {
        this.portfolio = portfolio;
        this.sweeper = sweeper;
        this.mapper = mapper;
        this.instruments = instruments;
        this.candles = candles;
        this.marketData = marketData;
    }

    public List<StrategyDto> list(long accountId) {
        return mapper.findByAccount(accountId).stream().map(StrategyService::toDto).toList();
    }

    public StrategyDto create(long accountId, CreateStrategyRequest request) {
        String symbol = request.symbol().trim().toUpperCase(Locale.ROOT);
        String type = request.strategyType().trim().toUpperCase(Locale.ROOT);

        if (instruments.findBySymbol(symbol) == null) {
            throw new DomainException("VAL-422", "Instrument '" + symbol + "' is not tradable",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (mapper.countByAccount(accountId) >= MAX_STRATEGIES) {
            throw new DomainException("VAL-422", "An account may have at most " + MAX_STRATEGIES + " strategies",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // Only the instrument, the rule, the quantity and the maximum order value are
        // required; every other setting has a standard default.
        StrategyRecord s = new StrategyRecord();
        s.setAccountId(accountId);
        s.setSymbol(symbol);
        s.setStrategyType(type);
        s.setQuantity(request.quantity());
        s.setOrderType("MARKET");

        if (StrategyRules.PRICE_TARGET.equals(type)) {
            if (request.side() == null || request.targetPrice() == null) {
                throw new IllegalArgumentException("A price target needs a side (BUY or SELL) and a targetPrice");
            }
            s.setSide(request.side().trim().toUpperCase(Locale.ROOT));
            s.setTargetPrice(request.targetPrice());
            // A price target always trades at market: the target is the trigger, the
            // market order is the action.

            // Creating the same target twice (a double click, a retried request) returns
            // the one already working instead of arming a second.
            StrategyRecord existing = mapper.findActiveTarget(accountId, symbol, s.getSide(),
                    request.targetPrice(), request.quantity());
            if (existing != null) {
                log.info("Strategy {} already armed for account {}; create is idempotent", existing.getStrategyId(), accountId);
                return toDto(existing);
            }
        }

        java.math.BigDecimal maxNotional = request.maxNotional();
        if (maxNotional == null) {
            // Limits are optional; without one, keep a practical no-cap value for storage.
            maxNotional = StrategyRules.PRICE_TARGET.equals(type)
                    ? request.targetPrice().multiply(java.math.BigDecimal.valueOf(request.quantity()))
                    .multiply(TARGET_HEADROOM).setScale(2, java.math.RoundingMode.CEILING)
                    : DEFAULT_UNBOUNDED_MAX_NOTIONAL;
        }
        s.setMaxNotional(maxNotional);
        s.setMaxOrdersPerDay(request.maxOrdersPerDay() == null ? DEFAULT_ORDERS_PER_DAY : request.maxOrdersPerDay());
        long maxPosition;
        if (request.maxPosition() != null) {
            maxPosition = request.maxPosition();
        } else if (StrategyRules.PRICE_TARGET.equals(type)) {
            // One order on top of whatever is held now: the target adds exactly its quantity.
            long held = portfolio.pricedPositions(accountId).stream()
                    .filter(p -> p.symbol().equalsIgnoreCase(symbol))
                    .mapToLong(com.trading.tradeapi.portfolio.dto.PricedPositionDto::quantity)
                    .sum();
            maxPosition = held + request.quantity();
        } else {
            maxPosition = request.quantity() * DEFAULT_POSITION_ORDERS;
        }
        if (maxPosition < request.quantity()) {
            throw new IllegalArgumentException("maxPosition must be at least the quantity of one order");
        }
        s.setMaxPosition(maxPosition);

        if (StrategyRules.PRICE_TARGET.equals(type)) {
            // nothing more: the target is the rule
        } else if (StrategyRules.MA_CROSSOVER.equals(type)) {
            int shortWindow = request.shortWindow() != null ? request.shortWindow() : DEFAULT_SHORT_WINDOW;
            int longWindow = request.longWindow() != null ? request.longWindow() : DEFAULT_LONG_WINDOW;
            if (shortWindow >= longWindow) {
                throw new IllegalArgumentException("shortWindow must be smaller than longWindow");
            }
            s.setShortWindow(shortWindow);
            s.setLongWindow(longWindow);
        } else {
            s.setBandPeriod(request.bandPeriod() != null ? request.bandPeriod() : DEFAULT_BAND_PERIOD);
            s.setBandK(request.bandK() != null ? request.bandK() : DEFAULT_BAND_K);
        }
        String name = request.name() == null ? "" : request.name().trim();
        s.setName(!name.isEmpty() ? name : StrategyRules.PRICE_TARGET.equals(type)
                ? (s.getSide().equals("BUY") ? "Buy " : "Sell ") + s.getQuantity() + " " + symbol
                        + (s.getSide().equals("BUY") ? " at or below " : " at or above ")
                        + s.getTargetPrice().stripTrailingZeros().toPlainString()
                : StrategyRules.MA_CROSSOVER.equals(type)
                ? symbol + " MA " + s.getShortWindow() + "/" + s.getLongWindow()
                : symbol + " Bollinger " + s.getBandPeriod() + "/" + s.getBandK().stripTrailingZeros().toPlainString());

        mapper.insert(s);
        // Ask the poller for this symbol's daily history now, so the strategy is ready
        // by the first quote rather than one poll later.
        if (!StrategyRules.PRICE_TARGET.equals(type)) {
            candles.storedDailyCloses(List.of(symbol));
        } else {
            // Ask for the symbol quote now so the immediate check below can use a
            // fresh value rather than waiting for the next scheduled poll.
            try {
                marketData.quote(symbol);
            } catch (RuntimeException e) {
                log.debug("Quote prefetch for strategy {} skipped: {}", s.getStrategyId(), e.getMessage());
            }
            // Check the target against the poller's latest price now, rather than waiting
            // for the next sweep: a target the price has already reached trades at once.
            sweeper.ifAvailable(sw -> {
                try {
                    sw.evaluate(symbol);
                } catch (RuntimeException e) {
                    log.warn("Immediate check of strategy {} failed: {}", s.getStrategyId(), e.getMessage());
                }
            });
        }
        log.info("Strategy {} created for account {}: {} on {}", s.getStrategyId(), accountId, type, symbol);
        return toDto(mapper.findById(s.getStrategyId()));
    }

    public StrategyDto setStatus(long accountId, long strategyId, String status) {
        StrategyRecord current = owned(accountId, strategyId);
        if ("COMPLETED".equals(current.getStatus())) {
            throw new DomainException("VAL-422", "Completed strategies cannot be resumed", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        mapper.updateStatus(strategyId, accountId, status.trim().toUpperCase(Locale.ROOT));
        log.info("Strategy {} set to {} by account {}", strategyId, status, accountId);
        return toDto(mapper.findById(strategyId));
    }

    public void delete(long accountId, long strategyId) {
        StrategyRecord current = owned(accountId, strategyId);
        if ("COMPLETED".equals(current.getStatus())) {
            throw new DomainException("VAL-422", "Completed strategies cannot be deleted", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        mapper.delete(strategyId, accountId);
        log.info("Strategy {} deleted by account {}", strategyId, accountId);
    }

    public List<StrategyExecutionDto> executions(long accountId, long strategyId) {
        owned(accountId, strategyId);
        return mapper.findExecutions(strategyId, 50).stream()
                .map(e -> new StrategyExecutionDto(e.getExecutionId(), e.getSignal(), e.getPrice(),
                        e.getQuantity(), e.getStatus(), e.getOrderId(), e.getReason(), e.getCreatedAt()))
                .toList();
    }

    /** Where the rule stands now, on the stored closes and the latest polled quote. Trades nothing. */
    public StrategyPreview preview(long accountId, long strategyId) {
        StrategyRecord s = owned(accountId, strategyId);
        List<Double> closes = StrategyRules.PRICE_TARGET.equals(s.getStrategyType())
                ? List.of()
                : candles.dailyCloses(List.of(s.getSymbol())).get(s.getSymbol());
        MarketQuote quote = marketData.quotes(List.of(s.getSymbol())).get(s.getSymbol());
        int available = closes == null ? 0 : closes.size();
        int needed = StrategyRules.closesNeeded(s);

        Double price = quote != null ? Double.valueOf(quote.price().doubleValue())
                : available > 0 ? closes.get(available - 1) : null;
        if (price == null) {
            return new StrategyPreview(strategyId, false, null, null, null, null, null, null, null,
                    available, needed, "Waiting for price data for " + s.getSymbol() + ".");
        }
        Optional<StrategyRules.Reading> r = StrategyRules.read(s, closes, price);
        if (r.isEmpty()) {
            return new StrategyPreview(strategyId, false, null, price, null, null, null, null, null,
                    available, needed, "Needs " + needed + " daily closes; " + available + " available.");
        }
        StrategyRules.Reading reading = r.get();
        boolean crossover = StrategyRules.MA_CROSSOVER.equals(s.getStrategyType());
        return new StrategyPreview(strategyId, true, reading.state(), round(price),
                crossover ? round(reading.primary()) : null,
                crossover ? round(reading.secondary()) : null,
                crossover ? null : round(reading.primary()),
                crossover ? null : round(reading.lower()),
                crossover ? null : round(reading.upper()),
                available, needed, explain(s, reading));
    }

    private StrategyRecord owned(long accountId, long strategyId) {
        StrategyRecord s = mapper.findByIdAndAccount(strategyId, accountId);
        if (s == null) {
            throw new DomainException("ACC-404", "Strategy not found", HttpStatus.NOT_FOUND);
        }
        return s;
    }

    private static String explain(StrategyRecord s, StrategyRules.Reading r) {
        if (StrategyRules.PRICE_TARGET.equals(s.getStrategyType())) {
            boolean buy = "BUY".equals(s.getSide());
            double gap = (r.price() / r.primary() - 1) * 100;
            return String.format(Locale.ROOT,
                    "%s %d at %s once the price is %s %.2f. It is %.2f now (%.1f%% %s the target)%s.",
                    buy ? "Buys" : "Sells", s.getQuantity(),
                    "LIMIT".equals(s.getOrderType()) ? "a limit of the target" : "market",
                    buy ? "at or below" : "at or above", r.primary(), r.price(), Math.abs(gap),
                    gap >= 0 ? "above" : "below", StrategyRules.HIT.equals(r.state()) ? ": target reached" : "");
        }
        if (StrategyRules.MA_CROSSOVER.equals(s.getStrategyType())) {
            return String.format(Locale.ROOT,
                    "The %d-day average (%.2f) is %s the %d-day average (%.2f). Buys when it crosses above, sells when it crosses below.",
                    s.getShortWindow(), r.primary(), r.state().equals(StrategyRules.ABOVE) ? "above" : "below",
                    s.getLongWindow(), r.secondary());
        }
        String where = switch (r.state()) {
            case StrategyRules.BELOW -> "below the lower band";
            case StrategyRules.ABOVE -> "above the upper band";
            default -> "inside the bands";
        };
        return String.format(Locale.ROOT,
                "The price (%.2f) is %s (%.2f to %.2f). Buys on a break below the lower band, sells on a break above the upper.",
                r.price(), where, r.lower(), r.upper());
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 100) / 100.0;
    }

    static StrategyDto toDto(StrategyRecord s) {
        return new StrategyDto(s.getStrategyId(), s.getAccountId(), s.getName(), s.getSymbol(), s.getStrategyType(),
                s.getSide(), s.getTargetPrice(), s.getOrderType(),
                s.getShortWindow(), s.getLongWindow(), s.getBandPeriod(), s.getBandK(), s.getQuantity(),
                s.getMaxNotional(), s.getMaxOrdersPerDay(),
                s.getMaxPosition() == null ? 0 : s.getMaxPosition(), s.getStatus(), s.getStopReason(), s.getLastState(),
                s.getLastPrice(), s.getLastEvaluatedAt(),
                s.getConsecutiveFailures() == null ? 0 : s.getConsecutiveFailures(), s.getCreatedAt());
    }
}
