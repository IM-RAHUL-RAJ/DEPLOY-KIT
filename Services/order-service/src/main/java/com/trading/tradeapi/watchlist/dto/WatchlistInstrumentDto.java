package com.trading.tradeapi.watchlist.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;

public record WatchlistInstrumentDto(
        Long watchlistInstrumentId,
        Long instrumentId,
        String symbol,
        String ticker,
        String assetClass,
        String quoteCurrency,
        BigDecimal currentPrice,
        String priceCurrency,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal change,
        BigDecimal changePercent,
        Boolean stale,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant addedAt
) {}
