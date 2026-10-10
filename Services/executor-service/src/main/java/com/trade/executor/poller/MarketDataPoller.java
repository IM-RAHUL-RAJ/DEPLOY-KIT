package com.trade.executor.poller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.client.FauxnanceClient;
import com.trade.executor.domain.CandleSeries;
import com.trade.executor.domain.Quote;
import com.trade.executor.service.OrderResolver;
import com.trade.executor.service.QuoteCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one component that asks Fauxnance for market data, and the thing that works
 * the book.
 *
 * <p>Fauxnance refreshes a quote about every six minutes and allows 2000 requests a
 * day, with up to 25 symbols per batched request. So:
 * <ul>
 *   <li><b>Scheduled poll</b> (every {@code fauxnance.poll-interval-seconds}, one
 *       minutes by default): quotes the symbols somebody depends on, in batches of
 *       25: working orders, watchlists, active price alerts, holdings, and symbols
 *       viewed recently. A market whose last quote said {@code closed} is refreshed
 *       only hourly, because its price will not move.</li>
 *   <li><b>Fast path</b> (every couple of seconds): fetches what somebody has just
 *       asked for and nobody has data for yet, such as a new symbol typed on the order
 *       ticket or a chart's candles. The order service records the request in
 *       {@code market_interest}; this picks it up, so a new symbol shows a price
 *       within seconds while only this component spends quota.</li>
 * </ul>
 *
 * <p>Every quote is written to {@code market_quote}, which the order service reads
 * for the ticket, the watchlist, market-order pricing and the portfolio; kept for
 * the watchlist page in {@code instrument_quote}; published on {@code market-data};
 * and checked against the working orders in its symbol.
 *
 * <p><b>Execution never uses a batch quote.</b> When a polled quote shows that a
 * working order can now execute, the order is priced from a fresh
 * {@code GET /quotes/{symbol}} of its own, exactly as a newly placed order is by the
 * consumer. An order whose price has not been reached costs nothing.
 */
@Component
public class MarketDataPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketDataPoller.class);
    private static final int MAX_BATCH = 25;

    @Value("${topics.market-data}")
    private String marketDataTopic;

    /** How long a closed market's last quote is trusted before it is fetched again. */
    @Value("${market.closed-refresh-minutes:60}")
    private int closedRefreshMinutes;

    /** How long an on-demand symbol that failed to fetch waits before the next try. */
    @Value("${market.on-demand.retry-seconds:60}")
    private long onDemandRetrySeconds;

    /** How long a symbol Fauxnance does not know is remembered as unknown. */
    @Value("${market.not-found-minutes:30}")
    private int notFoundMinutes;

    private final FauxnanceClient fauxnanceClient;
    private final QuoteCache quoteCache;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OrderResolver orderResolver;
    private final com.trade.executor.service.CurrencyConverter currencyConverter;

    /** Symbols an on-demand fetch failed for, and when they may be tried again. */
    private final Map<String, Instant> retryAfter = new ConcurrentHashMap<>();

    public MarketDataPoller(FauxnanceClient fauxnanceClient,
                            QuoteCache quoteCache,
                            JdbcTemplate jdbc,
                            KafkaTemplate<String, String> kafkaTemplate,
                            ObjectMapper objectMapper,
                            OrderResolver orderResolver,
                            com.trade.executor.service.CurrencyConverter currencyConverter) {
        this.fauxnanceClient = fauxnanceClient;
        this.quoteCache = quoteCache;
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.orderResolver = orderResolver;
        this.currencyConverter = currencyConverter;
    }

    // ------------------------------------------------------------------
    // Scheduled poll
    // ------------------------------------------------------------------

    @Scheduled(fixedDelayString = "#{${fauxnance.poll-interval-seconds} * 1000}",
               initialDelayString = "${market.initial-delay-ms:5000}")
    public void poll() {
        try {
            List<String> symbols = trackedSymbols();
            if (symbols.isEmpty()) {
                return;
            }
            FauxnanceClient.BatchResult batch = fauxnanceClient.fetchQuoteBatch(symbols);
            recordFeed(batch);
            int resolved = process(batch.quotes());
            log.info("Poll: {} tracked, {} quoted in {} call(s), {} working order(s) resolved",
                    symbols.size(), batch.quotes().size(), batchCount(symbols.size()), resolved);
        } catch (Exception e) {
            log.error("Market data poll failed: {}", e.getMessage(), e);
        }
    }

    /**
     * What the scheduled poll refreshes: every symbol somebody depends on, less any
     * whose market was closed at its last fetch less than an hour ago.
     */
    List<String> trackedSymbols() {
        return jdbc.queryForList(
                "SELECT s.symbol FROM ("
                        + " SELECT ticker AS symbol FROM orders WHERE status = 'NEW'"
                        + " UNION SELECT i.symbol FROM instrument i WHERE EXISTS"
                        + "   (SELECT 1 FROM watchlist_instrument wi WHERE wi.instrument_id = i.instrument_id)"
                        // An armed alert needs market-data for its symbol even when no
                        // watchlist holds it, or the alert could never fire.
                        + " UNION SELECT symbol FROM price_alert WHERE status = 'ACTIVE'"
                        // Likewise an active automated strategy trades on this stream.
                        + " UNION SELECT symbol FROM trading_strategy WHERE status = 'ACTIVE'"
                        + " UNION SELECT ticker FROM holding WHERE quantity > 0"
                        + " UNION SELECT symbol FROM market_interest"
                        + "   WHERE reason = 'VIEWED' AND expires_at > now() AND not_found_at IS NULL"
                        + ") s"
                        + " WHERE NOT EXISTS (SELECT 1 FROM market_quote q WHERE q.symbol = s.symbol"
                        + "   AND q.market_state = 'closed' AND q.fetched_at > now() - make_interval(mins => ?))"
                        + " ORDER BY 1",
                String.class, closedRefreshMinutes);
    }

    // ------------------------------------------------------------------
    // Fast path: what somebody has just asked for
    // ------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${market.on-demand.check-ms:2000}",
               initialDelayString = "${market.initial-delay-ms:5000}")
    public void fetchRequested() {
        try {
            fetchRequestedQuotes();
            fetchRequestedCandles();
        } catch (Exception e) {
            log.error("On-demand market data fetch failed: {}", e.getMessage(), e);
        }
    }

    /** New symbols with no quote yet, in one batched call of up to 25. */
    void fetchRequestedQuotes() {
        List<String> wanted = jdbc.queryForList(
                "SELECT i.symbol FROM market_interest i"
                        + " WHERE i.reason = 'VIEWED' AND i.expires_at > now()"
                        + " AND (i.not_found_at IS NULL OR i.not_found_at < now() - make_interval(mins => ?))"
                        + " AND NOT EXISTS (SELECT 1 FROM market_quote q WHERE q.symbol = i.symbol)"
                        + " ORDER BY i.requested_at",
                String.class, notFoundMinutes)
                .stream().filter(this::mayTryNow).limit(MAX_BATCH).toList();
        if (wanted.isEmpty()) {
            return;
        }
        FauxnanceClient.BatchResult batch = fauxnanceClient.fetchQuoteBatch(wanted);
        recordFeed(batch);
        process(batch.quotes());

        Set<String> returned = new HashSet<>();
        batch.quotes().forEach(q -> returned.add(q.symbol().toUpperCase()));
        for (String symbol : wanted) {
            if (returned.contains(symbol.toUpperCase())) {
                retryAfter.remove("Q:" + symbol);
            } else if (batch.failed()) {
                // The call itself failed: back off rather than spend a request every tick.
                retryAfter.put("Q:" + symbol, Instant.now().plusSeconds(onDemandRetrySeconds));
            } else {
                // Fauxnance answered and does not know this symbol.
                markNotFound(symbol, "VIEWED");
            }
        }
        log.info("On demand: {} new symbol(s) requested, {} quoted", wanted.size(), returned.size());
    }

    /** Candles somebody's chart needs and that have not been fetched today. */
    void fetchRequestedCandles() {
        List<String> wanted = jdbc.queryForList(
                "SELECT i.symbol FROM market_interest i"
                        + " WHERE i.reason = 'CANDLES' AND i.expires_at > now()"
                        + " AND (i.not_found_at IS NULL OR i.not_found_at < now() - make_interval(mins => ?))"
                        + " AND NOT EXISTS (SELECT 1 FROM market_candles c"
                        + "   WHERE c.symbol = i.symbol AND c.fetched_on = current_date)"
                        + " ORDER BY i.requested_at",
                String.class, notFoundMinutes)
                .stream().filter(s -> mayTryNow("C:" + s)).limit(5).toList();
        for (String symbol : wanted) {
            FauxnanceClient.CandleResult result = fauxnanceClient.fetchCandles(symbol);
            switch (result.outcome()) {
                case FOUND -> {
                    storeCandles(result.series());
                    retryAfter.remove("C:" + symbol);
                }
                case NOT_FOUND -> markNotFound(symbol, "CANDLES");
                case UNAVAILABLE -> retryAfter.put("C:" + symbol,
                        Instant.now().plusSeconds(onDemandRetrySeconds));
            }
        }
    }

    // ------------------------------------------------------------------
    // Feed status, for health reports that must not spend quota themselves
    // ------------------------------------------------------------------

    /**
     * The key's quota, checked at most hourly ({@code GET /usage} costs a unit) and
     * stored for the order service's health report.
     */
    @Scheduled(fixedDelayString = "#{${market.usage-check-minutes:60} * 60000}",
               initialDelayString = "${market.initial-delay-ms:5000}")
    public void checkUsage() {
        try {
            FauxnanceClient.Usage usage = fauxnanceClient.fetchUsage();
            if (usage == null) {
                return;
            }
            jdbc.update("UPDATE market_feed_status SET daily_quota = ?, used_today = ?,"
                    + " quota_checked_at = now() WHERE id = 1", usage.dailyQuota(), usage.usedToday());
            log.info("Fauxnance quota: {} of {} used today", usage.usedToday(), usage.dailyQuota());
        } catch (Exception e) {
            log.warn("Could not record Fauxnance usage: {}", e.getMessage());
        }
    }

    private void recordFeed(FauxnanceClient.BatchResult batch) {
        try {
            if (batch.failed()) {
                jdbc.update("UPDATE market_feed_status SET last_failure_at = now(),"
                        + " last_error = 'batch quote request failed' WHERE id = 1");
            } else {
                jdbc.update("UPDATE market_feed_status SET last_success_at = now() WHERE id = 1");
            }
        } catch (Exception e) {
            log.debug("Could not record feed status: {}", e.getMessage());
        }
    }

    private boolean mayTryNow(String symbolOrKey) {
        String key = symbolOrKey.startsWith("C:") ? symbolOrKey : "Q:" + symbolOrKey;
        Instant after = retryAfter.get(key);
        return after == null || Instant.now().isAfter(after);
    }

    // ------------------------------------------------------------------
    // Each quote: cache, store, publish, work the book
    // ------------------------------------------------------------------

    int process(List<Quote> quotes) {
        int resolved = 0;
        for (Quote quote : quotes) {
            quoteCache.put(quote.symbol(), quote);
            storeMarketQuote(quote);
            storeWatchlistQuote(quote);
            publishQuote(quote);
            resolved += workOrders(quote);
        }
        return resolved;
    }

    /**
     * Executes the working orders in this symbol that the quote shows can now
     * execute, each priced from a fresh quote of its own. Orders whose price has not
     * been reached are left alone and cost no request.
     */
    private int workOrders(Quote polled) {
        List<Map<String, Object>> orders = jdbc.queryForList(
                "SELECT o.order_id, o.account_id, o.ticker, o.side, o.quantity, o.price, o.order_type, "
                        + "a.currency AS account_currency "
                        + "FROM orders o JOIN account a ON a.account_id = o.account_id "
                        + "WHERE o.ticker = ? AND o.status = 'NEW' ORDER BY o.order_id",
                polled.symbol());
        int resolved = 0;
        for (Map<String, Object> row : orders) {
            String orderType = OrderResolver.orderType(row);
            // The limit is in the account's currency; compare it with the quote in that currency.
            Object accountCurrency = row.get("account_currency");
            Quote inAccount = accountCurrency == null ? polled
                    : currencyConverter.toAccountCurrency(polled, accountCurrency.toString());
            if (!mayExecute(orderType, (String) row.get("side"), (BigDecimal) row.get("price"), inAccount)) {
                continue;
            }
            try {
                Quote fresh = fauxnanceClient.fetchSingleQuote(polled.symbol());
                if (fresh == null) {
                    log.warn("Order {} left working: no fresh quote for {}", row.get("order_id"), polled.symbol());
                    continue;
                }
                quoteCache.put(fresh.symbol(), fresh);
                storeMarketQuote(fresh);
                OrderResolver.Outcome outcome = orderResolver.resolve(OrderResolver.facts(row), orderType, fresh);
                if (outcome == OrderResolver.Outcome.FILLED || outcome == OrderResolver.Outcome.REJECTED) {
                    resolved++;
                    log.info("Order {} {} on poll", row.get("order_id"), outcome);
                }
            } catch (Exception e) {
                // One order's failure (for example an optimistic-lock clash with the
                // consumer) must not stop the rest; it is still NEW for the next tick.
                log.warn("Order {} not resolved this poll: {}", row.get("order_id"), e.getMessage());
            }
        }
        return resolved;
    }

    /**
     * Whether a polled quote shows that a working order can execute now, which is
     * when it is worth a fresh quote. A market order always can. A limit buy can when
     * the ask has come down to its limit; a limit sell when the bid has risen to it.
     * A stale quote never fills a limit order, so it is not worth a request.
     */
    static boolean mayExecute(String orderType, String side, BigDecimal limit, Quote polled) {
        if (!"LIMIT".equalsIgnoreCase(orderType)) {
            return true;
        }
        if (polled == null || polled.stale() || limit == null) {
            return false;
        }
        if ("BUY".equalsIgnoreCase(side)) {
            BigDecimal ask = polled.ask() != null ? polled.ask() : polled.price();
            return ask != null && ask.compareTo(limit) <= 0;
        }
        if ("SELL".equalsIgnoreCase(side)) {
            BigDecimal bid = polled.bid() != null ? polled.bid() : polled.price();
            return bid != null && bid.compareTo(limit) >= 0;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Storage and publishing
    // ------------------------------------------------------------------

    /** The shared latest quote that the order service reads instead of calling Fauxnance. */
    private void storeMarketQuote(Quote quote) {
        try {
            jdbc.update(
                    "INSERT INTO market_quote (symbol, price, bid, ask, currency, change, change_percent,"
                            + " previous_close, market_state, stale, quote_as_of, fetched_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), now())"
                            + " ON CONFLICT (symbol) DO UPDATE SET price = EXCLUDED.price, bid = EXCLUDED.bid,"
                            + " ask = EXCLUDED.ask, currency = EXCLUDED.currency, change = EXCLUDED.change,"
                            + " change_percent = EXCLUDED.change_percent, previous_close = EXCLUDED.previous_close,"
                            + " market_state = EXCLUDED.market_state, stale = EXCLUDED.stale,"
                            + " quote_as_of = EXCLUDED.quote_as_of, fetched_at = EXCLUDED.fetched_at",
                    quote.symbol().toUpperCase(), quote.price(), quote.bid(), quote.ask(),
                    quote.currency() != null ? quote.currency() : "USD",
                    quote.change(), quote.changePercent(), quote.previousClose(),
                    quote.marketState(), quote.stale(), quote.quoteAsOf());
            // A symbol Fauxnance knows again is no longer remembered as unknown.
            jdbc.update("UPDATE market_interest SET not_found_at = NULL"
                    + " WHERE symbol = ? AND not_found_at IS NOT NULL", quote.symbol().toUpperCase());
        } catch (Exception e) {
            log.warn("Could not store market quote for {}: {}", quote.symbol(), e.getMessage());
        }
    }

    private void storeCandles(CandleSeries series) {
        try {
            jdbc.update(
                    "INSERT INTO market_candles (symbol, currency, candles, fetched_on, fetched_at)"
                            + " VALUES (?, ?, ?, current_date, now())"
                            + " ON CONFLICT (symbol) DO UPDATE SET currency = EXCLUDED.currency,"
                            + " candles = EXCLUDED.candles, fetched_on = EXCLUDED.fetched_on,"
                            + " fetched_at = EXCLUDED.fetched_at",
                    series.symbol(), series.currency(), series.candlesJson());
            log.info("Candles stored for {}", series.symbol());
        } catch (Exception e) {
            log.warn("Could not store candles for {}: {}", series.symbol(), e.getMessage());
        }
    }

    private void markNotFound(String symbol, String reason) {
        jdbc.update("UPDATE market_interest SET not_found_at = now() WHERE symbol = ? AND reason = ?",
                symbol, reason);
        log.info("Fauxnance has no data for {} ({})", symbol, reason);
    }

    /**
     * Keeps the latest quote of a watched instrument in instrument_quote, which the
     * watchlist page reads, so the browser never calls the market-data API. One
     * statement: it writes only when the symbol is on someone's watchlist.
     */
    private void storeWatchlistQuote(Quote quote) {
        try {
            jdbc.update(
                    "INSERT INTO instrument_quote "
                            + "(instrument_id, symbol, current_price, currency, bid, ask, change, change_percent, last_updated, stale) "
                            + "SELECT i.instrument_id, i.symbol, ?, ?, ?, ?, ?, ?, now(), ? FROM instrument i "
                            + "WHERE i.symbol = ? "
                            + "AND EXISTS (SELECT 1 FROM watchlist_instrument wi WHERE wi.instrument_id = i.instrument_id) "
                            + "ON CONFLICT (instrument_id) DO UPDATE SET "
                            + "current_price = EXCLUDED.current_price, currency = EXCLUDED.currency, "
                            + "bid = EXCLUDED.bid, ask = EXCLUDED.ask, change = EXCLUDED.change, "
                            + "change_percent = EXCLUDED.change_percent, last_updated = EXCLUDED.last_updated, "
                            + "stale = EXCLUDED.stale",
                    quote.price(), quote.currency() != null ? quote.currency() : "USD",
                    quote.bid(), quote.ask(), quote.change(), quote.changePercent(), quote.stale(),
                    quote.symbol());
        } catch (Exception e) {
            log.warn("Could not store watchlist quote for {}: {}", quote.symbol(), e.getMessage());
        }
    }

    /** One QUOTE per symbol on market-data, keyed by symbol (contracts/kafka-topics.md). */
    private void publishQuote(Quote quote) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("symbol", quote.symbol());
            payload.put("price", quote.price());
            payload.put("bid", quote.bid());
            payload.put("ask", quote.ask());
            payload.put("currency", quote.currency() != null ? quote.currency() : "USD");
            payload.put("change", quote.change());
            payload.put("changePercent", quote.changePercent());
            payload.put("previousClose", quote.previousClose());
            payload.put("marketState", quote.marketState() != null ? quote.marketState() : "unknown");
            payload.put("stale", quote.stale());
            payload.put("quoteAsOf", quote.quoteAsOf());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", UUID.randomUUID().toString());
            envelope.put("eventType", "QUOTE");
            envelope.put("eventTime", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            envelope.put("source", "market-poller");
            envelope.put("schemaVersion", 1);
            envelope.put("payload", payload);

            kafkaTemplate.send(marketDataTopic, quote.symbol(), objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.error("Could not publish QUOTE for {}: {}", quote.symbol(), e.getMessage());
        }
    }

    private static int batchCount(int symbols) {
        return (symbols + MAX_BATCH - 1) / MAX_BATCH;
    }
}
