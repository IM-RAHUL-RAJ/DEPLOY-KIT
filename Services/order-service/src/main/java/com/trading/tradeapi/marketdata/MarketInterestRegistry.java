package com.trading.tradeapi.marketdata;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records that a symbol is wanted, in a transaction of its own.
 *
 * <p>The request has to be visible to the market-data poller at once. Written inside
 * a caller's transaction (order placement, say) it would stay invisible until that
 * transaction committed, and the caller would wait for a quote nobody had been asked
 * to fetch.
 */
@Component
public class MarketInterestRegistry {

    private final MarketDataMapper mapper;

    public MarketInterestRegistry(MarketDataMapper mapper) {
        this.mapper = mapper;
    }

    /** Creates the interest, or extends it if it exists; a concurrent insert just extends. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void register(String symbol, String reason, int ttlMinutes) {
        if (mapper.extendInterest(symbol, reason, ttlMinutes) > 0) {
            return;
        }
        try {
            mapper.insertInterest(symbol, reason, ttlMinutes);
        } catch (DuplicateKeyException raced) {
            mapper.extendInterest(symbol, reason, ttlMinutes);
        }
    }
}
