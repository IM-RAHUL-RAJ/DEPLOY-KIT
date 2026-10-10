package com.trading.tradeapi.controller;

import com.trading.tradeapi.marketdata.CandleService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.LocalDate;

import com.trading.tradeapi.dto.InstrumentQuoteDto;
import com.trading.tradeapi.service.InstrumentQuoteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

    private final InstrumentQuoteService instrumentQuoteService;
    private final CandleService candleService;

    public InstrumentController(InstrumentQuoteService instrumentQuoteService,
                                CandleService candleService) {
        this.instrumentQuoteService = instrumentQuoteService;
        this.candleService = candleService;
    }

    @GetMapping("/{symbol}/quote")
    public ResponseEntity<InstrumentQuoteDto> getQuote(@PathVariable("symbol") String symbol) {
        return ResponseEntity.ok(instrumentQuoteService.getQuote(symbol));
    }

    /**
     * Daily candles for a chart, from the market-data poller's daily fetch. Behind
     * the same token check as every /api/v1 route; candles are market data and
     * belong to no account.
     */
    @GetMapping("/{symbol}/candles")
    public ResponseEntity<CandleService.CandlesResponse> getCandles(
            @PathVariable("symbol") String symbol,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(candleService.candles(symbol, from, to));
    }
}
