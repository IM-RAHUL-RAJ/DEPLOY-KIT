package com.trading.tradeapi.portfolio.service;

import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import static com.trading.tradeapi.marketdata.TestQuotes.*;
import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.HoldingMapper;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.portfolio.dto.PnlResponseDto;
import com.trading.tradeapi.portfolio.dto.PortfolioSummaryDto;
import com.trading.tradeapi.portfolio.dto.PricedPositionDto;
import com.trading.tradeapi.portfolio.entity.FilledOrderRow;
import com.trading.tradeapi.portfolio.mapper.PortfolioMapper;
import com.trading.tradeapi.service.CurrencyConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// Lenient: the instrument lookup is only a fallback now that the quote carries its currency.
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PortfolioServiceUnitTest {

    private static final String PERU_DAY = "2026-01-05T10:00:00Z";
    private static final String MID_JAN = "2026-01-15T10:00:00Z";

    @Mock
    private PortfolioMapper portfolioMapper;
    @Mock
    private HoldingMapper holdingMapper;
    @Mock
    private AccountMapper accountMapper;
    @Mock
    private InstrumentMapper instrumentMapper;
    @Mock
    private MarketDataService fauxnanceClient;

    private static AccountRecord account(long id, String currency, String cash) {
        return new AccountRecord(id, 1L, "Holder", currency, new BigDecimal(cash), AccountStatus.ACTIVE, 0L, null);
    }

    private static InstrumentRecord instrument(String symbol, String currency) {
        return new InstrumentRecord(1L, symbol, symbol, "EQUITY", currency, "ACTIVE", null);
    }

    private static PositionResponseDto holding(long accountId, String symbol, long qty, String avgCost) {
        return new PositionResponseDto(accountId, symbol, qty, new BigDecimal(avgCost));
    }

    private static MarketQuote quote(String symbol, String price, String currency, boolean stale) {
        return q(symbol, new BigDecimal(price), currency, stale);
    }

    private static FilledOrderRow fill(String symbol, String side, String qty, String price, String executedOn) {
        return new FilledOrderRow(symbol, side, new BigDecimal(qty), new BigDecimal(price),
                Instant.parse(executedOn));
    }

    private PortfolioService service(String base, Map<String, BigDecimal> rates) {
        CurrencyConverter converter = new CurrencyConverter();
        converter.setBase(base);
        if (rates != null) {
            converter.setRates(rates);
        }
        return new PortfolioService(portfolioMapper, holdingMapper, accountMapper, instrumentMapper,
                fauxnanceClient, converter);
    }

    @Test
    void summaryPricesPositionsAndTotalsInTheBaseCurrency() {
        when(accountMapper.findById(1L)).thenReturn(account(1, "USD", "10000.00"));
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(List.of(holding(1, "AAPL", 100, "150.00")));
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument("AAPL", "USD"));
        when(fauxnanceClient.quotes(anyCollection()))
                .thenReturn(Map.of("AAPL", quote("AAPL", "160.00", "USD", false)));
        PortfolioService service = service("USD", null);

        PortfolioSummaryDto summary = service.summary(1L);

        assertThat(summary.baseCurrency()).isEqualTo("USD");
        assertThat(summary.cashBalance()).isEqualByComparingTo("10000.00");
        assertThat(summary.costBasis()).isEqualByComparingTo("15000.00");
        assertThat(summary.marketValue()).isEqualByComparingTo("16000.00");
        assertThat(summary.unrealisedPnl()).isEqualByComparingTo("1000.00");
        assertThat(summary.unrealisedPnlPercent()).isEqualByComparingTo("6.67");
        assertThat(summary.realisedPnl()).isEqualByComparingTo("0");
        assertThat(summary.totalValue()).isEqualByComparingTo("26000.00");
        assertThat(summary.positionCount()).isEqualTo(1);
        assertThat(summary.partial()).isFalse();
        assertThat(summary.asOf()).isNotNull();
    }

    @Test
    void positionsReturnPricedNativeRowsAndHonourTheSymbolFilter() {
        when(accountMapper.findById(1L)).thenReturn(account(1, "USD", "10000.00"));
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(List.of(
                holding(1, "AAPL", 100, "150.00"),
                holding(1, "GOOGL", 40, "160.00")));
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument("AAPL", "USD"));
        when(instrumentMapper.findBySymbol("GOOGL")).thenReturn(instrument("GOOGL", "USD"));
        when(fauxnanceClient.quotes(anyCollection())).thenReturn(Map.of(
                "AAPL", quote("AAPL", "160.00", "USD", false),
                "GOOGL", quote("GOOGL", "170.00", "USD", false)));
        PortfolioService service = service("USD", null);

        List<PricedPositionDto> all = service.positions(1L, null);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).symbol()).isEqualTo("AAPL");
        assertThat(all.get(0).quantity()).isEqualTo(100);
        assertThat(all.get(0).lastPrice()).isEqualByComparingTo("160.00");
        assertThat(all.get(0).marketValue()).isEqualByComparingTo("16000.00");
        assertThat(all.get(0).unrealisedPnl()).isEqualByComparingTo("1000.00");
        assertThat(all.get(0).currency()).isEqualTo("USD");
        assertThat(all.get(0).priceAsOf()).isNotNull();
        assertThat(all.get(0).stale()).isFalse();

        List<PricedPositionDto> filtered = service.positions(1L, "aapl");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).symbol()).isEqualTo("AAPL");
    }

    @Test
    void aHoldingWithNoQuoteIsStaleWithNullColumns() {
        when(accountMapper.findById(1L)).thenReturn(account(1, "USD", "10000.00"));
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(List.of(
                holding(1, "AAPL", 100, "150.00"),
                holding(1, "GOOGL", 40, "160.00")));
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument("AAPL", "USD"));
        when(instrumentMapper.findBySymbol("GOOGL")).thenReturn(instrument("GOOGL", "USD"));
        when(fauxnanceClient.quotes(anyCollection()))
                .thenReturn(Map.of("AAPL", quote("AAPL", "160.00", "USD", false)));
        PortfolioService service = service("USD", null);

        List<PricedPositionDto> positions = service.positions(1L, null);

        PricedPositionDto googl = positions.stream().filter(p -> p.symbol().equals("GOOGL")).findFirst().orElseThrow();
        assertThat(googl.priced()).isFalse();
        assertThat(googl.lastPrice()).isNull();
        assertThat(googl.marketValue()).isNull();
        assertThat(googl.unrealisedPnl()).isNull();
        assertThat(googl.priceAsOf()).isNull();
        assertThat(googl.stale()).isTrue();
    }

    @Test
    void anAccountWithPositionsButNoQuoteForAnyOfThemIsMkt503() {
        when(accountMapper.findById(1L)).thenReturn(account(1, "USD", "10000.00"));
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(List.of(holding(1, "AAPL", 100, "150.00")));
        when(fauxnanceClient.quotes(anyCollection())).thenReturn(Map.of());
        PortfolioService service = service("USD", null);

        assertThatThrownBy(() -> service.summary(1L))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MKT-503");
                    assertThat(e.getHttpStatus().value()).isEqualTo(503);
                });
    }

    @Test
    void anAccountWithNoPositionsIsNotAnError() {
        when(accountMapper.findById(1L)).thenReturn(account(1, "USD", "10000.00"));
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(List.of());
        when(fauxnanceClient.quotes(anyCollection())).thenReturn(Map.of());
        PortfolioService service = service("USD", null);

        assertThat(service.positions(1L, null)).isEmpty();
    }

    @Test
    void anAccountThatDoesNotExistIsAcc404() {
        when(accountMapper.findById(9L)).thenReturn(null);
        PortfolioService service = service("USD", null);

        assertThatThrownBy(() -> service.summary(9L))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("ACC-404");
                    assertThat(e.getHttpStatus().value()).isEqualTo(404);
                });
    }

    @Test
    void realisedWalkBooksWinsAndLossesAgainstTheRunningAverage() {
        when(accountMapper.findById(1L)).thenReturn(account(1, "USD", "10000.00"));
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(List.of(holding(1, "AAPL", 15, "120.00")));
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument("AAPL", "USD"));
        when(fauxnanceClient.quotes(anyCollection()))
                .thenReturn(Map.of("AAPL", quote("AAPL", "150.00", "USD", false)));
        when(portfolioMapper.findFilledOrdersByAccountId(1L)).thenReturn(List.of(
                fill("AAPL", "BUY", "10", "100.00", "2026-01-01T09:30:00Z"),
                fill("AAPL", "BUY", "5", "160.00", "2026-01-02T09:30:00Z"),
                fill("AAPL", "SELL", "6", "180.00", "2026-01-03T09:30:00Z"),
                fill("AAPL", "SELL", "9", "90.00", "2026-01-04T09:30:00Z")));
        PortfolioService service = service("USD", null);

        PortfolioSummaryDto summary = service.summary(1L);

        // BUY 10@100 then BUY 5@160 -> avg 120; SELL 6@180 profit 360; SELL 9@90 loss 270.
        assertThat(summary.realisedPnl()).isEqualByComparingTo("90");
    }

    @Test
    void realisedWindowBindsWhichSellsAreReportedButNeverTheAverage() {
        when(accountMapper.findById(1L)).thenReturn(account(1, "USD", "10000.00"));
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(List.of(holding(1, "AAPL", 4, "100.00")));
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument("AAPL", "USD"));
        when(fauxnanceClient.quotes(anyCollection()))
                .thenReturn(Map.of("AAPL", quote("AAPL", "150.00", "USD", false)));
        when(portfolioMapper.findFilledOrdersByAccountId(1L)).thenReturn(List.of(
                fill("AAPL", "BUY", "10", "100.00", "2026-01-01T09:30:00Z"),
                fill("AAPL", "SELL", "6", "110.00", PERU_DAY),
                fill("AAPL", "SELL", "4", "90.00", MID_JAN)));
        PortfolioService service = service("USD", null);

        PnlResponseDto windowed = service.pnl(1L, LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-20"));
        assertThat(windowed.from()).isEqualTo(LocalDate.parse("2026-01-10"));
        assertThat(windowed.realisedPnl()).isEqualByComparingTo("-40");

        PnlResponseDto unbounded = service.pnl(1L, null, null);
        assertThat(unbounded.realisedPnl()).isEqualByComparingTo("20");
    }

    @Test
    void anUnconvertiblePositionIsExcludedFromBaseTotalsAndFlagsPartial() {
        when(accountMapper.findById(1L)).thenReturn(account(1, "EUR", "1000.00"));
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(List.of(
                holding(1, "AAPL", 5, "100.00"),
                holding(1, "GOOGL", 2, "100.00")));
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument("AAPL", "EUR"));
        when(instrumentMapper.findBySymbol("GOOGL")).thenReturn(instrument("GOOGL", "XXX"));
        when(fauxnanceClient.quotes(anyCollection())).thenReturn(Map.of(
                "AAPL", quote("AAPL", "110.00", "EUR", false),
                "GOOGL", quote("GOOGL", "105.00", "XXX", false)));
        PortfolioService service = service("EUR", Map.of("EUR", new BigDecimal("1")));

        PortfolioSummaryDto summary = service.summary(1L);

        assertThat(summary.partial()).isTrue();
        assertThat(summary.positionCount()).isEqualTo(2);
        // Only AAPL contributes; GOOGL's 210 of XXX has no rate into EUR.
        assertThat(summary.marketValue()).isEqualByComparingTo("550.00");
        assertThat(summary.costBasis()).isEqualByComparingTo("500.00");
        assertThat(summary.unrealisedPnl()).isEqualByComparingTo("50.00");
        assertThat(summary.totalValue()).isEqualByComparingTo("1550.00");
    }

    @Test
    void pnlCombinesRealisedAndUnrealisedPerSymbol() {
        when(accountMapper.findById(1L)).thenReturn(account(1, "USD", "10000.00"));
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(List.of(holding(1, "AAPL", 10, "100.00")));
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(instrument("AAPL", "USD"));
        when(fauxnanceClient.quotes(anyCollection()))
                .thenReturn(Map.of("AAPL", quote("AAPL", "120.00", "USD", false)));
        when(portfolioMapper.findFilledOrdersByAccountId(1L)).thenReturn(List.of(
                fill("AAPL", "BUY", "5", "100.00", "2026-01-01T09:30:00Z"),
                fill("AAPL", "SELL", "5", "110.00", "2026-01-02T09:30:00Z")));
        PortfolioService service = service("USD", null);

        PnlResponseDto pnl = service.pnl(1L, null, null);

        assertThat(pnl.realisedPnl()).isEqualByComparingTo("50");
        assertThat(pnl.unrealisedPnl()).isEqualByComparingTo("200");
        assertThat(pnl.totalPnl()).isEqualByComparingTo("250");
        assertThat(pnl.bySymbol()).hasSize(1);
        assertThat(pnl.bySymbol().get(0).symbol()).isEqualTo("AAPL");
        assertThat(pnl.bySymbol().get(0).realisedPnl()).isEqualByComparingTo("50");
        assertThat(pnl.bySymbol().get(0).unrealisedPnl()).isEqualByComparingTo("200");
        assertThat(pnl.bySymbol().get(0).totalPnl()).isEqualByComparingTo("250");
    }

    @Test
    void aForeignQuoteIsConvertedToTheLedgerCurrencyBeforeItMeetsTheCost() {
        // Bought in a USD account at $12.00 a share (the executor converts before it fills);
        // the quote arrives in rupees. Valued in rupees against a dollar cost, this showed
        // a profit of 98,000%; converted, it is the few cents it really is.
        when(accountMapper.findById(1L)).thenReturn(account(1, "USD", "1000.00"));
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(List.of(holding(1, "INFY.NS", 10, "12.00")));
        when(fauxnanceClient.quotes(anyCollection()))
                .thenReturn(Map.of("INFY.NS", quote("INFY.NS", "1000.00", "INR", false)));
        PortfolioService service = service("USD", Map.of("USD", BigDecimal.ONE, "INR", new BigDecimal("80")));

        PricedPositionDto p = service.positions(1L, null).get(0);
        assertThat(p.currency()).isEqualTo("USD");
        assertThat(p.lastPrice()).isEqualByComparingTo("12.50");
        assertThat(p.marketValue()).isEqualByComparingTo("125.00");
        assertThat(p.costBasis()).isEqualByComparingTo("120.00");
        assertThat(p.unrealisedPnl()).isEqualByComparingTo("5.00");
        assertThat(p.unrealisedPnlPercent()).isEqualByComparingTo("4.17");
    }
}
