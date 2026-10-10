package com.trade.executor.exception;

/**
 * Thrown when a Kafka message can NEVER be successfully processed,
 * regardless of how many times it is retried.
 *
 * Examples:
 *   - Malformed JSON (the bytes won't fix themselves)
 *   - Missing orderId in the payload
 *   - Order ID not found in Postgres (referential integrity failure upstream)
 *   - Account not found
 *   - Unrecognised eventType we don't know how to handle
 *
 * The Kafka error handler is configured to NOT retry this exception class —
 * the message is dead-lettered on the very first failure.
 */
public class NonRetryableOrderException extends RuntimeException {

    public NonRetryableOrderException(String message) {
        super(message);
    }

    public NonRetryableOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
