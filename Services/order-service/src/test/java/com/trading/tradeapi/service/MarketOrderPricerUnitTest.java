package com.trading.tradeapi.service;

import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import static com.trading.tradeapi.marketdata.TestQuotes.*;
import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderType;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.MarketDataUnavailableException;
import com.trading.tradeapi.mapper.AccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MarketOrderPricerUnitTest {

    @Mock
    private MarketDataService fauxnanceClient;
    @Mock
    private AccountMapper accountMapper;

    private CurrencyConverter currencyConverter;
    private MarketOrderPricer pricer;

    @BeforeEach
    public void setup() {
        currencyConverter = new CurrencyConverter();
        currencyConverter.setBase("USD");
        currencyConverter.setRates(java.util.Map.of("USD", new BigDecimal("1"), "INR", new BigDecimal("83.3")));
        pricer = new MarketOrderPricer(fauxnanceClient, accountMapper, currencyConverter);
    }

    private PlaceOrderRequestDto request(OrderType type, OrderSide side) {
        return new PlaceOrderRequestDto(101L, "RELIANCE.NS", side, 5L, null, type, "key-12345678");
    }

    private void accountIn(String currency) {
        AccountRecord account = new AccountRecord();
        account.setAccountId(101L);
        account.setCurrency(currency);
        account.setCashBalance(new BigDecimal("100000"));
        account.setStatus(AccountStatus.ACTIVE);
        when(accountMapper.findById(101L)).thenReturn(account);
    }

    @Test
    @DisplayName("A buy market order is priced at the native ask and converted into the account currency")
    public void buyUsesAskConvertedToAccountCurrency() {
        accountIn("USD");
        when(fauxnanceClient.quote("RELIANCE.NS"))
                .thenReturn(lookup(q("RELIANCE.NS", new BigDecimal("1400.00"),
                        new BigDecimal("1399.50"), new BigDecimal("1401.00"), "INR", false)));

        MarketOrderPricer.ResolvedPrice resolved = pricer.resolve(request(OrderType.MARKET, OrderSide.BUY));

        assertEquals(new BigDecimal("1401.00"), resolved.nativePrice());
        assertEquals("INR", resolved.nativeCurrency());
        assertEquals("USD", resolved.accountCurrency());
        // 1401 / 83.3 = 16.8199... -> 16.82 on the two-decimal ledger
        assertEquals(new BigDecimal("16.82"), resolved.price());
    }

    @Test
    @DisplayName("A sell market order is priced at the native bid")
    public void sellUsesBid() {
        accountIn("INR");
        when(fauxnanceClient.quote("RELIANCE.NS"))
                .thenReturn(lookup(q("RELIANCE.NS", new BigDecimal("1400.00"),
                        new BigDecimal("1399.50"), new BigDecimal("1401.00"), "INR", false)));

        MarketOrderPricer.ResolvedPrice resolved = pricer.resolve(request(OrderType.MARKET, OrderSide.SELL));

        assertEquals(new BigDecimal("1399.50"), resolved.nativePrice());
        assertEquals("INR", resolved.accountCurrency());
        // Same currency on both sides: no conversion, mid-book leg kept.
        assertEquals(new BigDecimal("1399.50"), resolved.price());
    }

    @Test
    @DisplayName("A missing bid/ask degrades to the mid price rather than failing")
    public void fallsBackToMidPrice() {
        accountIn("USD");
        when(fauxnanceClient.quote("RELIANCE.NS"))
                .thenReturn(lookup(q("RELIANCE.NS", new BigDecimal("150.00"), null, null, "USD", false)));

        MarketOrderPricer.ResolvedPrice resolved = pricer.resolve(request(OrderType.MARKET, OrderSide.BUY));

        assertEquals(new BigDecimal("150.00"), resolved.price());
    }

    @Test
    @DisplayName("No live quote is a market-data failure, not an insufficient-funds failure")
    public void missingQuoteRaisesMarketDataUnavailable() {
        accountIn("USD");
        when(fauxnanceClient.quote("RELIANCE.NS")).thenReturn(missing());

        MarketDataUnavailableException thrown = assertThrows(MarketDataUnavailableException.class,
                () -> pricer.resolve(request(OrderType.MARKET, OrderSide.BUY)));

        assertEquals("MKT-503", thrown.getCode());
    }

    @Test
    @DisplayName("A zero quote is treated as unusable market data")
    public void zeroQuoteRaisesMarketDataUnavailable() {
        accountIn("USD");
        when(fauxnanceClient.quote("RELIANCE.NS"))
                .thenReturn(lookup(q("RELIANCE.NS", BigDecimal.ZERO,
                        null, BigDecimal.ZERO, "USD", false)));

        assertThrows(MarketDataUnavailableException.class,
                () -> pricer.resolve(request(OrderType.MARKET, OrderSide.BUY)));
    }

    @Test
    @DisplayName("An unknown account is refused before any market data is requested")
    public void unknownAccountThrows() {
        when(accountMapper.findById(101L)).thenReturn(null);

        assertThrows(AccountNotFoundException.class,
                () -> pricer.resolve(request(OrderType.MARKET, OrderSide.BUY)));
    }

    @Test
    @DisplayName("A limit order is returned untouched so its client price stays authoritative")
    public void limitOrderIsNotRepriced() {
        PlaceOrderRequestDto limit = new PlaceOrderRequestDto(
                101L, "AAPL", OrderSide.BUY, 5L, new BigDecimal("150.00"), OrderType.LIMIT, "key-12345678");

        assertSame(limit, pricer.price(limit));
    }

    @Test
    @DisplayName("price() returns the effective request carrying the resolved price")
    public void priceReturnsEffectiveRequest() {
        accountIn("USD");
        when(fauxnanceClient.quote("RELIANCE.NS"))
                .thenReturn(lookup(q("RELIANCE.NS", new BigDecimal("150.00"),
                        new BigDecimal("149.90"), new BigDecimal("150.10"), "USD", false)));

        PlaceOrderRequestDto effective = pricer.price(request(OrderType.MARKET, OrderSide.BUY));

        assertEquals(new BigDecimal("150.10"), effective.price());
        assertEquals(OrderType.MARKET, effective.orderType());
        assertEquals("key-12345678", effective.idempotencyKey());
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Edge inputs")
    class EdgeInputs {

        @Test
        @DisplayName("Nothing to price passes through untouched")
        void passThrough() {
            org.assertj.core.api.Assertions.assertThat(pricer.price(null)).isNull();
            PlaceOrderRequestDto untyped = new PlaceOrderRequestDto(101L, "AAPL", OrderSide.BUY, 5L, null, null, "key-12345678");
            org.assertj.core.api.Assertions.assertThat(pricer.price(untyped)).isSameAs(untyped);
        }

        @Test
        @DisplayName("A blank account currency and a quote without one both read as USD")
        void defaultsToUsd() {
            com.trading.tradeapi.entity.AccountRecord account = new com.trading.tradeapi.entity.AccountRecord();
            account.setAccountId(101L);
            account.setCurrency(" ");
            org.mockito.Mockito.when(accountMapper.findById(101L)).thenReturn(account);
            org.mockito.Mockito.when(fauxnanceClient.quote("AAPL")).thenReturn(com.trading.tradeapi.marketdata.TestQuotes.lookup(
                    com.trading.tradeapi.marketdata.TestQuotes.q("AAPL", new BigDecimal("190"), null, false)));

            MarketOrderPricer.ResolvedPrice resolved = pricer.resolve(
                    new PlaceOrderRequestDto(101L, " aapl ", OrderSide.SELL, 5L, null, OrderType.MARKET, "key-12345678"));
            org.assertj.core.api.Assertions.assertThat(resolved.accountCurrency()).isEqualTo("USD");
            org.assertj.core.api.Assertions.assertThat(resolved.nativeCurrency()).isEqualTo("USD");
            org.assertj.core.api.Assertions.assertThat(resolved.price()).isEqualByComparingTo("190");
        }

        @Test
        @DisplayName("No symbol finds no quote")
        void noSymbol() {
            com.trading.tradeapi.entity.AccountRecord account = new com.trading.tradeapi.entity.AccountRecord();
            account.setCurrency(null);
            org.mockito.Mockito.when(accountMapper.findById(101L)).thenReturn(account);
            org.mockito.Mockito.when(fauxnanceClient.quote(null)).thenReturn(com.trading.tradeapi.marketdata.TestQuotes.missing());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> pricer.resolve(
                    new PlaceOrderRequestDto(101L, null, OrderSide.BUY, 5L, null, OrderType.MARKET, "key-12345678")))
                    .isInstanceOf(com.trading.tradeapi.exception.MarketDataUnavailableException.class);
        }
    }
}
