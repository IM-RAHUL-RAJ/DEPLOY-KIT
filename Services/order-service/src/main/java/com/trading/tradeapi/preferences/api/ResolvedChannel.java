package com.trading.tradeapi.preferences.api;

/**
 * The answer a delivery path needs: where to send, and what to send it to.
 *
 * <p>{@code contact} is personal data. It must not be written to a log and must not be
 * copied into a Kafka payload; pass it straight to the channel it belongs to.
 */
public record ResolvedChannel(AlertChannel channel, String contact) {

    private static final ResolvedChannel NONE = new ResolvedChannel(AlertChannel.NONE, null);

    /**
     * What a caller gets when there is nowhere to send: never {@code null}, so a caller has
     * to handle the no-channel case as a value rather than as a possible NPE.
     */
    public static ResolvedChannel none() {
        return NONE;
    }

    /** In-app delivery needs no destination; every other channel does. */
    public static ResolvedChannel of(AlertChannel channel, String contact) {
        if (channel == null || channel == AlertChannel.NONE || channel == AlertChannel.INHERIT) {
            return none();
        }
        if (channel == AlertChannel.PUSH) {
            return new ResolvedChannel(channel, contact);
        }
        if (contact == null || contact.isBlank()) {
            return none();
        }
        return new ResolvedChannel(channel, contact);
    }

    public boolean deliverable() {
        if (channel == AlertChannel.PUSH) {
            return true;
        }
        return channel != AlertChannel.NONE && contact != null && !contact.isBlank();
    }
}
