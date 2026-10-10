package com.trading.tradeapi.advice.service;

import com.trading.tradeapi.advice.dto.AdviceResponse;
import com.trading.tradeapi.advice.dto.TradeSignal;
import com.trading.tradeapi.watchlist.dto.WatchlistDto;
import com.trading.tradeapi.watchlist.dto.WatchlistInstrumentDto;
import com.trading.tradeapi.marketdata.CandleService;
import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import com.trading.tradeapi.portfolio.api.PortfolioPositions;
import com.trading.tradeapi.portfolio.dto.PricedPositionDto;
import com.trading.tradeapi.watchlist.service.WatchlistService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trade advice: a BUY, SELL or HOLD view on each instrument a customer holds or
 * watches, with the reasoning behind it.
 *
 * <p>Inputs, all read through the layer that owns them: holdings from the portfolio
 * module's published {@link PortfolioPositions}, watched symbols from the watchlist
 * service, daily candles and the latest quote from the market-data poller. Nothing
 * here calls Fauxnance and nothing is written: a signal informs a customer, it never
 * trades.
 *
 * <p>Daily candles change once a day and the poller stores them once a day, so a
 * signal costs no quota beyond that first fetch per symbol per day.
 */
@Service
public class AdviceService {

    public static final String DISCLAIMER =
            "Educational signal computed from delayed public price data. Not investment advice.";

    private final PortfolioPositions portfolio;
    private final WatchlistService watchlists;
    private final CandleService candles;
    private final MarketDataService marketData;

    public AdviceService(PortfolioPositions portfolio,
                         WatchlistService watchlists,
                         CandleService candles,
                         MarketDataService marketData) {
        this.portfolio = portfolio;
        this.watchlists = watchlists;
        this.candles = candles;
        this.marketData = marketData;
    }

    /** Signals for every held and watched instrument, holdings first. */
    public AdviceResponse forAccount(long accountId, String userUuid) {
        Map<String, List<String>> sources = new LinkedHashMap<>();
        Map<String, TradeSignal.Holding> held = new LinkedHashMap<>();
        Map<String, PricedPositionDto> priced = new LinkedHashMap<>();

        for (PricedPositionDto p : portfolio.pricedPositions(accountId)) {
            String s = p.symbol().toUpperCase();
            sources.computeIfAbsent(s, k -> new ArrayList<>()).add("HOLDING");
            held.put(s, new TradeSignal.Holding(p.quantity(), toDouble(p.averageCost()), toDouble(p.unrealisedPnlPercent())));
            priced.put(s, p);
        }
        for (WatchlistDto list : watchlists.listWatchlists(accountId, userUuid)) {
            if (list.instruments() == null) {
                continue;
            }
            for (WatchlistInstrumentDto i : list.instruments()) {
                List<String> why = sources.computeIfAbsent(i.symbol().toUpperCase(), k -> new ArrayList<>());
                if (!why.contains("WATCHLIST")) {
                    why.add("WATCHLIST");
                }
            }
        }

        Map<String, List<Double>> closes = candles.dailyCloses(sources.keySet());
        Map<String, MarketQuote> quotes = marketData.quotes(sources.keySet());

        List<TradeSignal> signals = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : sources.entrySet()) {
            String symbol = e.getKey();
            MarketQuote quote = quotes.get(symbol);
            signals.add(SignalEngine.evaluate(symbol, closes.get(symbol),
                    quote == null ? null : quote.price().doubleValue(),
                    quote == null ? null : quote.observedAt(),
                    held.get(symbol), e.getValue()));
        }
        return new AdviceResponse(accountId, SignalEngine.METHODOLOGY, DISCLAIMER, Instant.now(), signals);
    }

    /** A signal for any one symbol, held, watched or neither. */
    public TradeSignal forSymbol(long accountId, String symbol) {
        String s = symbol.trim().toUpperCase();
        TradeSignal.Holding holding = null;
        for (PricedPositionDto p : portfolio.pricedPositions(accountId)) {
            if (p.symbol().equalsIgnoreCase(s)) {
                holding = new TradeSignal.Holding(p.quantity(), toDouble(p.averageCost()), toDouble(p.unrealisedPnlPercent()));
            }
        }
        List<Double> series = candles.dailyCloses(List.of(s)).get(s);
        MarketQuote quote = marketData.quotes(List.of(s)).get(s);
        return SignalEngine.evaluate(s, series,
                quote == null ? null : quote.price().doubleValue(),
                quote == null ? null : quote.observedAt(),
                holding, List.of(holding != null ? "HOLDING" : "REQUESTED"));
    }

    private static Double toDouble(java.math.BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }
}
