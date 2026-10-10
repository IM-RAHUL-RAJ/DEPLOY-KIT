package com.trading.tradeapi.service;

import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the instrument an order refers to.
 *
 * Known instruments are read from the local catalogue and must be ACTIVE.
 * A symbol that is not in the catalogue is looked up through the market-data
 * poller; when it has a live quote the instrument is registered and becomes
 * tradable, so users are not restricted to the instruments seeded ahead of time.
 */
@Service
public class InstrumentDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(InstrumentDiscoveryService.class);
    private static final String ACTIVE = "ACTIVE";
    private static final String DEFAULT_ASSET_CLASS = "EQUITY";

    private final InstrumentMapper instrumentMapper;
    private final MarketDataService marketData;

    public InstrumentDiscoveryService(InstrumentMapper instrumentMapper,
                                      MarketDataService marketData) {
        this.instrumentMapper = instrumentMapper;
        this.marketData = marketData;
    }

    @Transactional
    public InstrumentRecord resolveTradeableInstrument(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new InstrumentNotFoundException();
        }

        String normalized = symbol.trim().toUpperCase();
        InstrumentRecord existing = instrumentMapper.findBySymbol(normalized);
        if (existing != null) {
            if (isActive(existing)) {
                return existing;
            }
            // The catalogue knows this symbol but it is suspended/delisted; do not revive it.
            throw new InstrumentNotFoundException();
        }

        MarketQuote quote = marketData.quote(normalized).quote();
        if (quote == null || quote.price() == null) {
            throw new InstrumentNotFoundException();
        }

        instrumentMapper.upsertDiscoveredInstrument(
                normalized, normalized, quote.currency(), ACTIVE);

        InstrumentRecord discovered = instrumentMapper.findBySymbol(normalized);
        if (discovered == null) {
            throw new InstrumentNotFoundException();
        }

        logger.info("Registered instrument {} ({}) discovered through the market-data poller",
                normalized, quote.currency() != null ? quote.currency() : DEFAULT_ASSET_CLASS);
        return discovered;
    }

    private boolean isActive(InstrumentRecord instrument) {
        return instrument.getStatus() != null && ACTIVE.equalsIgnoreCase(instrument.getStatus());
    }
}
