package com.trading.tradeapi.notifications.api;

/**
 * The seam the watchlists module calls when a threshold crosses.
 *
 * <p>This is a Java interface and deliberately not an HTTP route, for the same reason
 * {@code preferences.api.AlertChannelResolver} is not one: the caller is a colleague module in
 * the same process, publishing it under {@code /api/v1/} would hand any bearer-token holder the
 * ability to create notifications for any account, and the security review would find it first.
 *
 * <p>Contract with callers — agreed with the watchlists module before either side was built:
 * <ul>
 *   <li>Never throws. This runs on the market-data consumer thread with no HTTP request in
 *       flight; a delivery that cannot be booked must be recorded as a failure, not take the
 *       partition down with it.</li>
 *   <li>Never returns the customer's contact to the caller. Delivery detail stays inside the
 *       notifications module.</li>
 *   <li>Recording is idempotent on {@code PriceAlertCrossed.eventId}: a redelivered market-data
 *       message does not double-book or double-send.</li>
 * </ul>
 */
public interface AlertDelivery {

    void deliverPriceAlert(PriceAlertCrossed crossed);
}