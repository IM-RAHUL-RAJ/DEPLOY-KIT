package com.trading.tradeapi.preferences.api;

/**
 * The seam between this module and everything that needs to reach a customer.
 *
 * <p>This is a Java interface and deliberately not an HTTP route. It exists so that another
 * module in the same process can ask "where does this customer want to be reached?" before it
 * sends. Publishing it under {@code /api/v1/} would make it reachable by anyone holding any
 * customer's bearer token, and it would be the first thing this sprint's security review
 * looked for: a route that exists only so a colleague can call it, in the application that
 * holds the order book.
 *
 * <p>One implementation, behind this interface. Nothing outside {@code preferences} may reach
 * the preference table or the module's mapper: this is the only way in.
 *
 * <p>Contract with callers — agreed with the notifications module before either side was
 * built:
 * <ul>
 *   <li>Never returns {@code null}. No preference, an unknown account and an explicit
 *       {@code NONE} all answer {@link ResolvedChannel#none()}.</li>
 *   <li>Never throws. This runs on a consumer thread with no HTTP request in flight; a
 *       delivery path that cannot ask must be able to decide what to do, not take the
 *       partition down with it.</li>
 *   <li>Cheap enough to call on every send. The channel is read fresh each time rather than
 *       cached at start-up, so a change a customer makes takes effect on the next message.</li>
 * </ul>
 */
public interface AlertChannelResolver {

    /** The account-wide channel, for callers that have not classified the message. */
    ResolvedChannel resolveChannelForAccount(long accountId);

    /**
     * The channel for a classified message. A category named by the customer (price alerts, order
     * outcomes) can override the account-wide channel; {@code category == null} behaves exactly
     * like {@link #resolveChannelForAccount(long)}. Unknown categories fall back to the
     * account-wide channel, so a future category can never silently disappear.
     */
    ResolvedChannel resolveChannelForAccount(long accountId, String category);
}
