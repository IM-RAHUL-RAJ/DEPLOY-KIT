package com.trading.tradeapi.preferences.api;

/**
 * How the platform reaches a customer. Stored by the preferences module, resolved by it,
 * and never held as a constant anywhere else.
 *
 * <p>{@link #NONE} is a stored answer rather than an absence: the customer was asked and
 * declined. It is deliberately distinct from a customer who has never been asked, which the
 * resolver reports the same way because a delivery path has nowhere to send either.
 */
public enum AlertChannel {
    /** The stored answer to "did the customer decline?" - and, on a per-category row, the answer
     * a row gives when there is nowhere delivery should go. */
    NONE,
    EMAIL,
    /** In-app: a toast on the terminal and a row in the inbox. Needs no destination. */
    PUSH,
    /** Email and in-app together. Needs an email address. */
    BOTH,
    /**
     * Per-category rows only: "do what the account's global channel says". A resolver turns this
     * into the global channel and never reports it, so a delivery path never sees it.
     */
    INHERIT;

    /** Never throws: an unreadable stored value is treated as no channel rather than as a server fault. */
    public static AlertChannel fromStorage(String value) {
        if (value == null) {
            return NONE;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
