package com.trading.tradeapi.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prices for the order service, from the market-data poller.
 *
 * <p>The poller in the Trade Executor is the one component that calls Fauxnance for
 * quotes and candles. This service reads what it stored. A symbol nobody has asked
 * about before is registered as wanted; the poller's fast path fetches it within a
 * couple of seconds, and this waits briefly for it, so a customer typing a new
 * symbol still sees a price at once while no request is spent here.
 *
 * <p>Viewing a symbol keeps the poller interested in it for a while, so its price
 * stays current while the customer is looking at it, and the poller forgets it once
 * nobody is.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    static final String VIEWED = "VIEWED";
    static final String CANDLES = "CANDLES";

    private final MarketDataMapper mapper;
    private final MarketInterestRegistry interest;

    /** How long to wait for the poller to fetch a symbol nobody had asked about. */
    @Value("${market.await-ms:6000}")
    long awaitMs = 6000;

    @Value("${market.await-step-ms:250}")
    long stepMs = 250;

    /** How long viewing a symbol keeps the scheduled poll refreshing it. */
    @Value("${market.view-interest-minutes:30}")
    int viewInterestMinutes = 30;

    /** How long an unknown symbol is answered as unknown without asking again. */
    @Value("${market.not-found-minutes:30}")
    int notFoundMinutes = 30;

    public MarketDataService(MarketDataMapper mapper, MarketInterestRegistry interest) {
        this.mapper = mapper;
        this.interest = interest;
    }

    /** The outcome of asking for one quote. */
    public record QuoteLookup(MarketQuote quote, boolean notFound) {
        public boolean found() {
            return quote != null && quote.price() != null;
        }
    }

    /** The outcome of asking for a symbol's daily candles. */
    public record CandlesLookup(MarketCandles candles, boolean notFound) {}

    /**
     * The latest quote for a symbol, registering it as viewed. If the poller has
     * never fetched it, waits up to {@code market.await-ms} for the fast path.
     */
    public QuoteLookup quote(String symbol) {
        String s = normalise(symbol);
        if (s == null) {
            return new QuoteLookup(null, true);
        }
        interest.register(s, VIEWED, viewInterestMinutes);
        MarketQuote known = mapper.findQuote(s);
        if (known != null) {
            return new QuoteLookup(known, false);
        }
        long deadline = System.currentTimeMillis() + awaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (mapper.isNotFound(s, VIEWED, notFoundMinutes)) {
                return new QuoteLookup(null, true);
            }
            if (!sleep()) {
                break;
            }
            MarketQuote fetched = mapper.findQuote(s);
            if (fetched != null) {
                return new QuoteLookup(fetched, false);
            }
        }
        log.info("No quote for {} within {} ms; the poller has it queued", s, awaitMs);
        return new QuoteLookup(null, mapper.isNotFound(s, VIEWED, notFoundMinutes));
    }

    /** The stored quote for a symbol, without registering interest or waiting. */
    public MarketQuote latest(String symbol) {
        String s = normalise(symbol);
        return s == null ? null : mapper.findQuote(s);
    }

    /**
     * Quotes for several symbols at once, keyed by upper-case symbol. Symbols with
     * no stored quote are registered and waited for together, once. A symbol that
     * still has none is simply absent from the result.
     */
    public Map<String, MarketQuote> quotes(Collection<String> symbols) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String symbol : symbols) {
            String s = normalise(symbol);
            if (s != null) {
                wanted.add(s);
            }
        }
        Map<String, MarketQuote> result = new HashMap<>();
        if (wanted.isEmpty()) {
            return result;
        }
        collect(mapper.findQuotes(wanted), result);
        Set<String> missing = new LinkedHashSet<>(wanted);
        missing.removeAll(result.keySet());
        if (missing.isEmpty()) {
            return result;
        }
        missing.forEach(s -> interest.register(s, VIEWED, viewInterestMinutes));
        long deadline = System.currentTimeMillis() + awaitMs;
        while (!missing.isEmpty() && System.currentTimeMillis() < deadline && sleep()) {
            collect(mapper.findQuotes(missing), result);
            missing.removeAll(result.keySet());
            missing.removeIf(s -> mapper.isNotFound(s, VIEWED, notFoundMinutes));
        }
        return result;
    }

    /**
     * Today's daily candles for a symbol. Registers the request, waits briefly for
     * the poller, and falls back to an older series rather than nothing.
     */
    public CandlesLookup candles(String symbol) {
        String s = normalise(symbol);
        if (s == null) {
            return new CandlesLookup(null, true);
        }
        interest.register(s, CANDLES, 24 * 60);
        MarketCandles stored = mapper.findCandles(s);
        if (isCurrent(stored)) {
            return new CandlesLookup(stored, false);
        }
        long deadline = System.currentTimeMillis() + awaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (mapper.isNotFound(s, CANDLES, notFoundMinutes)) {
                return new CandlesLookup(stored, stored == null);
            }
            if (!sleep()) {
                break;
            }
            MarketCandles fetched = mapper.findCandles(s);
            if (isCurrent(fetched)) {
                return new CandlesLookup(fetched, false);
            }
        }
        return new CandlesLookup(stored, false);
    }

    /**
     * Daily candles for several symbols, requested together and waited for once.
     * A symbol keeps its older series when today's has not arrived in time, and is
     * absent when it has none at all.
     */
    public Map<String, MarketCandles> candles(Collection<String> symbols) {
        return candles(symbols, awaitMs);
    }

    /**
     * As {@link #candles(Collection)}, waiting at most {@code waitMs} for today's series;
     * 0 returns what is stored now. A stream consumer passes 0: it must never hold up the
     * partition for a fetch, and yesterday's closes are fine for a daily indicator.
     */
    public Map<String, MarketCandles> candles(Collection<String> symbols, long waitMs) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String symbol : symbols) {
            String s = normalise(symbol);
            if (s != null) {
                wanted.add(s);
            }
        }
        Map<String, MarketCandles> result = new HashMap<>();
        Set<String> pending = new LinkedHashSet<>();
        for (String s : wanted) {
            interest.register(s, CANDLES, 24 * 60);
            MarketCandles stored = mapper.findCandles(s);
            if (stored != null) {
                result.put(s, stored);
            }
            if (!isCurrent(stored)) {
                pending.add(s);
            }
        }
        long deadline = System.currentTimeMillis() + waitMs;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline && sleep()) {
            for (java.util.Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                String s = it.next();
                MarketCandles fetched = mapper.findCandles(s);
                if (isCurrent(fetched)) {
                    result.put(s, fetched);
                    it.remove();
                } else if (mapper.isNotFound(s, CANDLES, notFoundMinutes)) {
                    it.remove();
                }
            }
        }
        return result;
    }

    /** Daily candles fetched today are as current as daily candles get. */
    private static boolean isCurrent(MarketCandles candles) {
        return candles != null && candles.fetchedOn() != null
                && !candles.fetchedOn().isBefore(LocalDate.now());
    }

    private static void collect(List<MarketQuote> quotes, Map<String, MarketQuote> into) {
        for (MarketQuote q : quotes) {
            if (q != null && q.symbol() != null && q.price() != null) {
                into.put(q.symbol().toUpperCase(), q);
            }
        }
    }

    private static String normalise(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase();
    }

    private boolean sleep() {
        try {
            Thread.sleep(stepMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
