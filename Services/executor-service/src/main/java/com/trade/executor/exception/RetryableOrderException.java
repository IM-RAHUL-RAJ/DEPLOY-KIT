package com.trade.executor.exception;

/**
 * Thrown when a Kafka message failed due to a TRANSIENT condition that
 * may succeed if retried after a backoff delay.
 *
 * Examples:
 *   - Database connection temporarily lost
 *   - Optimistic lock budget exhausted (concurrent modification by another node)
 *   - Kafka producer failure publishing to trade-events
 *
 * The Kafka error handler retries this with an exponential backoff
 * (1s → 2s → 4s → 8s → 16s) before finally dead-lettering the message.
 */
public class RetryableOrderException extends RuntimeException {

    public RetryableOrderException(String message) {
        super(message);
    }

    public RetryableOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
