package com.trading.tradeapi.service;

import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.MarketDataUnavailableException;
import com.trading.tradeapi.mapper.AccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Server-side authority for the price of a market order.
 *
 * <p>A market order arrives from the client with no reference price. The price is
 * taken from the market-data poller's latest quote here — the ask for a buy, the bid for a sell —
 * and then re-denominated into the account's ledger currency, because the cash
 * balance it is checked against is held in that currency. The same conversion is
 * applied again by the executor at fill time, so admission and settlement agree.
 */
@Service
public class MarketOrderPricer {

    private static final Logger logger = LoggerFactory.getLogger(MarketOrderPricer.class);

    private static final String DEFAULT_CURRENCY = "USD";

    private final MarketDataService marketData;
    private final AccountMapper accountMapper;
    private final CurrencyConverter currencyConverter;

    public MarketOrderPricer(MarketDataService marketData,
                             AccountMapper accountMapper,
                             CurrencyConverter currencyConverter) {
        this.marketData = marketData;
        this.accountMapper = accountMapper;
        this.currencyConverter = currencyConverter;
    }

    /**
     * Returns the effective request for a market order, carrying the live price in
     * the account currency. Limit orders are returned untouched so their client
     * price stays authoritative.
     */
    public PlaceOrderRequestDto price(PlaceOrderRequestDto request) {
        if (request == null || request.orderType() == null
                || request.orderType() != com.trading.tradeapi.enums.OrderType.MARKET) {
            return request;
        }
        ResolvedPrice resolved = resolve(request);
        return new PlaceOrderRequestDto(
                request.accountId(),
                request.ticker(),
                request.side(),
                request.quantity(),
                resolved.price(),
                request.orderType(),
                request.idempotencyKey());
    }

    /** Full resolution result, including the native price and quote currency. */
    public record ResolvedPrice(
            BigDecimal price,
            BigDecimal nativePrice,
            String nativeCurrency,
            String accountCurrency,
            boolean stale
    ) {}

    public ResolvedPrice resolve(PlaceOrderRequestDto request) {
        String symbol = request.ticker() == null ? null : request.ticker().trim().toUpperCase();
        AccountRecord account = accountMapper.findById(request.accountId());
        if (account == null) {
            throw new AccountNotFoundException();
        }
        String accountCurrency = account.getCurrency() == null || account.getCurrency().isBlank()
                ? DEFAULT_CURRENCY
                : account.getCurrency();

        MarketQuote quote = marketData.quote(symbol).quote();
        if (quote == null) {
            logger.warn("No market-data quote available to price market order for {}", symbol);
            throw new MarketDataUnavailableException(symbol);
        }

        boolean isBuy = request.side() == OrderSide.BUY;
        BigDecimal nativePrice = quote.nativePriceFor(isBuy);
        if (nativePrice == null || nativePrice.signum() <= 0) {
            logger.warn("Quote for {} carries no usable {} price", symbol, isBuy ? "ask" : "bid");
            throw new MarketDataUnavailableException(symbol);
        }

        String nativeCurrency = quote.currency() == null || quote.currency().isBlank()
                ? DEFAULT_CURRENCY
                : quote.currency();
        BigDecimal ledgerPrice = currencyConverter.toLedgerPrice(nativePrice, nativeCurrency, accountCurrency);
        if (ledgerPrice == null || ledgerPrice.signum() <= 0) {
            throw new MarketDataUnavailableException(symbol);
        }

        return new ResolvedPrice(ledgerPrice, nativePrice, nativeCurrency, accountCurrency, quote.stale());
    }
}