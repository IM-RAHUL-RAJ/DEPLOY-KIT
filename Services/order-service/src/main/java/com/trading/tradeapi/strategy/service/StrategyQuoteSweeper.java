package com.trading.tradeapi.strategy.service;

import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import com.trading.tradeapi.marketdata.QuoteEvent;
import com.trading.tradeapi.strategy.mapper.StrategyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * Runs the active strategies against the market poller's latest stored quotes, every few
 * seconds. The {@code market-data} stream delivers the same quotes; this sweep makes the
 * strategies independent of it, so a strategy still trades when Kafka consumers are off or
 * behind. Both paths key a decision by the quote itself (symbol and quote time), so the
 * same polled price can never trade twice, whichever path sees it first. It reads only the
 * database cache the poller fills: no market-data call is made here.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "strategy.sweep.enabled", havingValue = "true", matchIfMissing = true)
public class StrategyQuoteSweeper {

    private static final Logger log = LoggerFactory.getLogger(StrategyQuoteSweeper.class);

    private final StrategyMapper mapper;
    private final MarketDataService marketData;
    private final StrategyEngine engine;

    public StrategyQuoteSweeper(StrategyMapper mapper, MarketDataService marketData, StrategyEngine engine) {
        this.mapper = mapper;
        this.marketData = marketData;
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${strategy.sweep.interval-ms}", initialDelayString = "${strategy.sweep.initial-delay-ms:20000}")
    public void sweep() {
        try {
            for (String symbol : mapper.findActiveSymbols()) {
                evaluate(symbol);
            }
        } catch (RuntimeException e) {
            log.warn("Strategy sweep failed: {}", e.getMessage());
        }
    }

    /** Evaluates one symbol's strategies against its latest stored quote, if there is one. */
    public void evaluate(String symbol) {
        MarketQuote quote = marketData.latest(symbol);
        if (quote == null || quote.price() == null) {
            return;
        }
        engine.onQuote(new QuoteEvent(null, quote.symbol(), quote.price(), quote.currency(),
                quote.stale(), quote.observedAt()));
    }

    /** For tests: the symbols this sweep would visit. */
    List<String> symbols() {
        return mapper.findActiveSymbols();
    }
}
