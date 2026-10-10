package com.trading.tradeapi.dto;

public record InstrumentDto(
        Long instrumentId,
        String symbol,
        String ticker,
        String assetClass,
        String quoteCurrency,
        String status
) {}
