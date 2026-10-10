package com.trading.tradeapi.advice.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

/** Signals for every instrument a customer holds or watches, with the methodology stated. */
public record AdviceResponse(
        long accountId,
        String methodology,
        String disclaimer,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant asOf,
        List<TradeSignal> signals
) {}
