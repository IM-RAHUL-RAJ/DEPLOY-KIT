package com.trading.tradeapi.advice.dto;

import java.util.List;

/**
 * How every advice number is computed, stated once: what the UI renders under
 * "How this is calculated", so a signal can always be traced back to its formula.
 */
public record Methodology(String summary, List<Formula> factors, String scoring, String disclaimer) {

    /** One factor: what it is, its formula, and how it is scored. */
    public record Formula(String key, String name, String formula, String rule) {}
}
