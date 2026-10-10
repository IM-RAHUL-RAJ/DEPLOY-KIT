package com.trade.executor.service;

import com.trade.executor.domain.Quote;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory thread-safe cache for the latest market quotes.
 * Updated by MarketDataPoller via Kafka market-data topic consumer.
 * Used by OrderEventConsumer to evaluate fill decisions without hitting external APIs.
 */
@Component
public class QuoteCache {

    private final ConcurrentHashMap<String, Quote> cache = new ConcurrentHashMap<>();

    /**
     * Stores or replaces the quote for a given symbol.
     */
    public void put(String symbol, Quote quote) {
        cache.put(symbol.toUpperCase(), quote);
    }

    /**
     * Returns the latest quote for the symbol, or null if not cached.
     */
    public Quote get(String symbol) {
        return symbol != null ? cache.get(symbol.toUpperCase()) : null;
    }

    public int size() {
        return cache.size();
    }
}
