package com.trading.tradeapi.portfolio.service;

import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.HoldingMapper;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.portfolio.dto.PnlResponseDto;
import com.trading.tradeapi.portfolio.dto.PortfolioSummaryDto;
import com.trading.tradeapi.portfolio.dto.PricedPositionDto;
import com.trading.tradeapi.portfolio.dto.SymbolPnlDto;
import com.trading.tradeapi.portfolio.entity.FilledOrderRow;
import com.trading.tradeapi.portfolio.mapper.PortfolioMapper;
import com.trading.tradeapi.service.CurrencyConverter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Prices a whole account's portfolio and works out its P&amp;L.
 *
 * <p>Realised P&amp;L is a chronological walk over every filled order, keeping a running
 * average cost per symbol: a buy re-averages, a sell books {@code (executed - average) *
 * sold}. That average is the number an accounting system would accept because the walk
 * spans the whole history, and the {@code from}/{@code to} window only bounds <em>which</em>
 * sells are reported, never the average itself. Unrealised P&amp;L is market-to-market in one
 * read of the market-data poller's latest quotes.
 *
 * <p><b>Currencies.</b> Fills settle in the account's ledger currency: the executor converts
 * the quote before it fills, so every executed price and every holding's average cost is in
 * the ledger currency. Positions are therefore valued in the ledger currency too: the live
 * quote, which is in the instrument's own currency, is converted before it is compared with
 * the cost. Mixing the two is what made a rupee-quoted holding in a dollar account show a
 * cost of 12 against a price of 992.
 *
 * <p>A priced holding whose currency has no configured rate is excluded from the base-currency
 * sums and flags {@code partial}; a holding with no live quote is {@code stale}, its columns
 * null. An account with positions but no usable quote for any of them is answered MKT-503
 * rather than a page of nulls.
 */
@Service
public class PortfolioService implements com.trading.tradeapi.portfolio.api.PortfolioPositions {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int AVG_COST_SCALE = 8;

    private final PortfolioMapper portfolioMapper;
    private final HoldingMapper holdingMapper;
    private final AccountMapper accountMapper;
    private final InstrumentMapper instrumentMapper;
    private final MarketDataService marketData;
    private final CurrencyConverter currencyConverter;

    public PortfolioService(PortfolioMapper portfolioMapper,
                            HoldingMapper holdingMapper,
                            AccountMapper accountMapper,
                            InstrumentMapper instrumentMapper,
                            MarketDataService marketData,
                            CurrencyConverter currencyConverter) {
        this.portfolioMapper = portfolioMapper;
        this.holdingMapper = holdingMapper;
        this.accountMapper = accountMapper;
        this.instrumentMapper = instrumentMapper;
        this.marketData = marketData;
        this.currencyConverter = currencyConverter;
    }

    /** The account exists or ACC-404. */
    public AccountRecord requireAccount(long accountId) {
        AccountRecord account = accountMapper.findById(accountId);
        if (account == null) {
            throw new AccountNotFoundException();
        }
        return account;
    }

    public PortfolioSummaryDto summary(long accountId) {
        AccountRecord account = requireAccount(accountId);
        List<LocalPosition> positions = pricePositions(accountId, null, account.getCurrency());
        requireSomePriced(positions);

        String base = account.getCurrency();
        BigDecimal cash = account.getCashBalance();
        BigDecimal baseCost = BigDecimal.ZERO;
        BigDecimal baseValue = BigDecimal.ZERO;
        int excludedFromTotals = 0;
        for (LocalPosition p : positions) {
            BigDecimal convertedCost = convertOrNull(p.costBasis(), p.currency(), base);
            BigDecimal convertedValue = convertOrNull(p.marketValue(), p.currency(), base);
            if (convertedCost == null || convertedValue == null) {
                excludedFromTotals++;
                continue;
            }
            baseCost = baseCost.add(convertedCost);
            baseValue = baseValue.add(convertedValue);
        }

        BigDecimal unrealisedBase = baseValue.subtract(baseCost);
        Realised realised = realisedTotals(accountId, base, null, null);
        boolean partial = excludedFromTotals > 0
                || positions.stream().anyMatch(p -> !p.priced())
                || realised.partial();

        return new PortfolioSummaryDto(
                accountId,
                base,
                cash,
                baseValue,
                baseCost,
                unrealisedBase,
                percentile(unrealisedBase, baseCost),
                realised.realisedTotal(),
                cash.add(baseValue),
                positions.size(),
                partial,
                Instant.now());
    }

    @Override
    public List<PricedPositionDto> pricedPositions(long accountId) {
        return positions(accountId, null);
    }

    public List<PricedPositionDto> positions(long accountId, String symbolFilter) {
        AccountRecord account = requireAccount(accountId);
        List<LocalPosition> positions = pricePositions(accountId, symbolFilter, account.getCurrency());
        requireSomePriced(positions);
        return positions.stream().map(LocalPosition::toDto).toList();
    }

    public PnlResponseDto pnl(long accountId, LocalDate from, LocalDate to) {
        AccountRecord account = requireAccount(accountId);
        String base = account.getCurrency();

        Map<String, BigDecimal> unrealisedBySymbol = new LinkedHashMap<>();
        BigDecimal unrealisedBase = BigDecimal.ZERO;
        boolean partial = false;
        for (LocalPosition p : pricePositions(accountId, null, base)) {
            BigDecimal converted = convertOrNull(p.unrealisedPnl(), p.currency(), base);
            if (converted == null) {
                partial = true;
                continue;
            }
            unrealisedBase = unrealisedBase.add(converted);
            unrealisedBySymbol.put(p.symbol(), converted);
        }

        Realised realised = realisedTotals(accountId, base, from, to);
        partial = partial || realised.partial();

        List<SymbolPnlDto> bySymbol = new ArrayList<>();
        List<String> symbols = new ArrayList<>();
        for (FilledOrderRow row : portfolioMapper.findFilledOrdersByAccountId(accountId)) {
            String symbol = row.symbol();
            if (!symbols.contains(symbol)) {
                symbols.add(symbol);
            }
        }
        // Order by symbol for a stable listing with realised and unrealised both present.
        for (String symbol : realised.realised().keySet()) {
            if (!symbols.contains(symbol)) {
                symbols.add(symbol);
            }
        }
        for (String symbol : unrealisedBySymbol.keySet()) {
            if (!symbols.contains(symbol)) {
                symbols.add(symbol);
            }
        }
        for (String symbol : symbols) {
            BigDecimal r = realised.realised().getOrDefault(symbol, BigDecimal.ZERO);
            BigDecimal u = unrealisedBySymbol.get(symbol);
            if (u == null) {
                u = BigDecimal.ZERO;
                partial = true;
            }
            bySymbol.add(new SymbolPnlDto(symbol, r, u, r.add(u)));
        }

        BigDecimal realisedBase = realised.realisedTotal();
        return new PnlResponseDto(
                accountId,
                base,
                from,
                to,
                realisedBase,
                unrealisedBase,
                realisedBase.add(unrealisedBase),
                bySymbol,
                Instant.now());
    }

    // ------------------------------------------------------------------ internals

    /** MKT-503 when there is at least one position and none of them has a usable quote. */
    private void requireSomePriced(List<LocalPosition> positions) {
        if (positions.stream().anyMatch(LocalPosition::priced)) {
            return;
        }
        if (positions.isEmpty()) {
            return;
        }
        throw new DomainException("MKT-503",
                "No live market quote is available for any holding; pricing cannot proceed",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    private BigDecimal percentile(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.multiply(HUNDRED).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    /**
     * Converts native into the base currency, or returns {@code null} when no rate is
     * configured. Identity (instrument currency equal to the base, or a blank instrument
     * currency, which the ledger treats as base) never needs a rate.
     */
    private BigDecimal convertOrNull(BigDecimal nativeAmount, String from, String base) {
        if (nativeAmount == null) {
            return null;
        }
        try {
            return currencyConverter.convert(nativeAmount, from, base);
        } catch (RuntimeException e) {
            log.debug("No conversion path for {} -> {}: {}", from, base, e.getMessage());
            return null;
        }
    }

    private record LocalPosition(
            long accountId,
            String symbol,
            BigDecimal quantity,
            BigDecimal averageCost,
            BigDecimal costBasis,
            BigDecimal lastPrice,
            BigDecimal marketValue,
            BigDecimal unrealisedPnl,
            String currency,
            Instant priceAsOf,
            boolean stale) {

        boolean priced() {
            return lastPrice != null;
        }

        PricedPositionDto toDto() {
            BigDecimal pct = null;
            if (lastPrice != null && costBasis.signum() != 0) {
                pct = unrealisedPnl.multiply(HUNDRED).divide(costBasis, 2, RoundingMode.HALF_UP);
            }
            return new PricedPositionDto(
                    accountId, symbol, quantity.longValue(), averageCost, costBasis,
                    lastPrice, marketValue, unrealisedPnl, pct, currency, priceAsOf, stale);
        }
    }

    private List<LocalPosition> pricePositions(long accountId, String symbolFilter, String ledger) {
        String base = ledger == null || ledger.isBlank() ? currencyConverter.getBase() : ledger.toUpperCase();
        List<PositionResponseDto> holdings = holdingMapper.findHoldingsByAccountId(accountId);
        List<String> wanted = new ArrayList<>();
        for (PositionResponseDto h : holdings) {
            if (symbolFilter == null || symbolFilter.equalsIgnoreCase(h.symbol())) {
                wanted.add(h.symbol());
            }
        }
        Map<String, MarketQuote> quotes = marketData.quotes(wanted);
        Instant now = Instant.now();

        List<LocalPosition> positions = new ArrayList<>();
        for (PositionResponseDto h : holdings) {
            if (symbolFilter != null && !symbolFilter.equalsIgnoreCase(h.symbol())) {
                continue;
            }
            BigDecimal quantity = BigDecimal.valueOf(h.quantity());
            BigDecimal averageCost = h.averageCost();
            BigDecimal costBasis = averageCost.multiply(quantity);
            MarketQuote quote = quotes.get(h.symbol().toUpperCase());
            // The quote is in the instrument's currency; the cost is in the ledger's.
            String instrumentCurrency = quote != null && quote.currency() != null && !quote.currency().isBlank()
                    ? quote.currency().toUpperCase()
                    : instrumentCurrency(h.symbol());
            BigDecimal lastPrice = quote == null ? null : convertOrNull(quote.price(), instrumentCurrency, base);
            if (lastPrice != null) {
                lastPrice = lastPrice.setScale(AVG_COST_SCALE, RoundingMode.HALF_UP);
            }
            BigDecimal marketValue = lastPrice == null ? null : lastPrice.multiply(quantity);
            BigDecimal unrealised = lastPrice == null ? null : marketValue.subtract(costBasis);

            positions.add(new LocalPosition(
                    accountId,
                    h.symbol(),
                    quantity,
                    averageCost,
                    costBasis,
                    lastPrice,
                    marketValue,
                    unrealised,
                    base,
                    lastPrice == null ? null : quote.observedAt(),
                    quote == null || quote.stale()));
        }
        return positions;
    }

    private String instrumentCurrency(String symbol) {
        InstrumentRecord instrument = instrumentMapper.findBySymbol(symbol);
        if (instrument != null && instrument.getQuoteCurrency() != null
                && !instrument.getQuoteCurrency().isBlank()) {
            return instrument.getQuoteCurrency().toUpperCase();
        }
        return currencyConverter.getBase();
    }

    /**
     * Walk the whole fill history once, keeping a running average cost. Sells whose
     * executed date falls inside [{@code from}, {@code to}] (either side may be null)
     * are reported; the average itself always spans the whole history.
     */
    private record Realised(Map<String, BigDecimal> realised, BigDecimal realisedTotal, boolean partial) {
    }

    private Realised realisedTotals(long accountId, String base, LocalDate from, LocalDate to) {
        Map<String, BigDecimal> quantity = new HashMap<>();
        Map<String, BigDecimal> average = new HashMap<>();
        Map<String, BigDecimal> realisedBySymbol = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean partial = false;

        for (FilledOrderRow row : portfolioMapper.findFilledOrdersByAccountId(accountId)) {
            String symbol = row.symbol();
            BigDecimal beforeQty = quantity.getOrDefault(symbol, BigDecimal.ZERO);
            BigDecimal beforeAvg = average.getOrDefault(symbol, BigDecimal.ZERO);
            if ("BUY".equalsIgnoreCase(row.side())) {
                BigDecimal afterQty = beforeQty.add(row.quantity());
                BigDecimal newCost = beforeQty.multiply(beforeAvg)
                        .add(row.quantity().multiply(row.executedPrice()));
                average.put(symbol, afterQty.signum() == 0
                        ? BigDecimal.ZERO : newCost.divide(afterQty, AVG_COST_SCALE, RoundingMode.HALF_UP));
                quantity.put(symbol, afterQty);
                continue;
            }
            if ("SELL".equalsIgnoreCase(row.side()) && beforeQty.signum() > 0) {
                BigDecimal sold = row.quantity().min(beforeQty);
                if (sold.signum() > 0) {
                    BigDecimal pnlNative = row.executedPrice().subtract(beforeAvg).multiply(sold);
                    boolean inWindow = inWindow(row.executedOn(), from, to);
                    if (inWindow) {
                        // Executed prices settle in the ledger currency already.
                        BigDecimal converted = pnlNative;
                        if (converted == null) {
                            partial = true;
                        } else {
                            realisedBySymbol.merge(symbol, converted, BigDecimal::add);
                            total = total.add(converted);
                        }
                    }
                    quantity.put(symbol, beforeQty.subtract(sold));
                }
            }
        }
        return new Realised(realisedBySymbol, total, partial);
    }

    private boolean inWindow(Instant executedOn, LocalDate from, LocalDate to) {
        if (executedOn == null) {
            return true;
        }
        LocalDate day = executedOn.atOffset(ZoneOffset.UTC).toLocalDate();
        if (from != null && day.isBefore(from)) {
            return false;
        }
        return to == null || !day.isAfter(to);
    }
}