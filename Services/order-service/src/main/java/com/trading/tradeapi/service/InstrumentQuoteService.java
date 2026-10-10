package com.trading.tradeapi.service;

import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import com.trading.tradeapi.exception.MarketDataUnavailableException;

import com.trading.tradeapi.watchlist.alerts.service.AlertService;
import com.trading.tradeapi.dto.InstrumentQuoteDto;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Serves the order ticket the latest quote for a symbol, so the UI can show a
 * foreign instrument's native price next to its account-currency equivalent.
 *
 * <p>The quote comes from the market-data poller through {@link MarketDataService};
 * this service never calls Fauxnance. Asking for a symbol registers it as viewed, so
 * the poller keeps it current while the customer is looking at it, and a symbol
 * nobody had asked about before is fetched by the poller within seconds.
 *
 * <p>The quote is also handed to the alerts module, so an armed price alert is
 * checked against the price the customer can actually see.
 */
@Service
public class InstrumentQuoteService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentQuoteService.class);

    private final InstrumentMapper instrumentMapper;
    private final MarketDataService marketData;
    private final AlertService alertService;

    public InstrumentQuoteService(InstrumentMapper instrumentMapper,
                                  MarketDataService marketData,
                                  AlertService alertService) {
        this.instrumentMapper = instrumentMapper;
        this.marketData = marketData;
        this.alertService = alertService;
    }

    public InstrumentQuoteDto getQuote(String symbol) {
        String normalized = symbol == null ? null : symbol.trim().toUpperCase();
        if (normalized == null || normalized.isEmpty()) {
            throw new InstrumentNotFoundException();
        }

        InstrumentRecord instrument = instrumentMapper.findBySymbol(normalized);
        MarketDataService.QuoteLookup lookup = marketData.quote(normalized);
        MarketQuote quote = lookup.quote();

        if (quote == null && instrument == null) {
            if (lookup.notFound()) {
                throw new InstrumentNotFoundException();
            }
            // The poller has the symbol queued but has not answered yet.
            throw new MarketDataUnavailableException(normalized);
        }

        String currency = (quote != null && quote.currency() != null)
                ? quote.currency()
                : (instrument != null ? instrument.getQuoteCurrency() : null);

        if (quote != null && quote.price() != null) {
            try {
                alertService.evaluate(normalized, quote.price(), quote.observedAt());
            } catch (RuntimeException e) {
                // A quote is still useful when alert evaluation misbehaves; the
                // failure must not take the order ticket down with it.
                log.warn("Alert evaluation skipped for {} ({})", normalized, e.getMessage());
            }
        }

        return new InstrumentQuoteDto(
                quote != null ? quote.symbol() : normalized,
                currency,
                quote != null ? quote.price() : null,
                quote != null ? quote.bid() : null,
                quote != null ? quote.ask() : null,
                quote != null && quote.stale());
    }
}
