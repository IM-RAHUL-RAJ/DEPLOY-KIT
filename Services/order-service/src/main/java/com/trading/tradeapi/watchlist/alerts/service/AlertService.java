package com.trading.tradeapi.watchlist.alerts.service;

import com.trading.tradeapi.watchlist.alerts.dto.CreatePriceAlertRequest;
import com.trading.tradeapi.watchlist.alerts.dto.PriceAlertDto;
import com.trading.tradeapi.watchlist.alerts.entity.PriceAlertRecord;
import com.trading.tradeapi.watchlist.alerts.mapper.PriceAlertMapper;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.notifications.api.AlertDelivery;
import com.trading.tradeapi.notifications.api.PriceAlertCrossed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The watchlists half of the module: arming, listing and cancelling one-shot price alerts, and
 * the evaluation that hands a crossed threshold to the notifications module.
 *
 * <p>Where the crossing is committed and where the notification is booked are separate. A redelivered
 * market-data message finds the alert already {@code TRIGGERED} — the guarded update returns 0 and
 * nothing is delivered, so the alerts table and the inbox agree even if Kafka redelivers.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final int MAX_ACTIVE_ALERTS = 20;

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final PriceAlertMapper priceAlertMapper;
    private final InstrumentMapper instrumentMapper;
    private final AlertDelivery alertDelivery;

    public AlertService(PriceAlertMapper priceAlertMapper,
                        InstrumentMapper instrumentMapper,
                        AlertDelivery alertDelivery) {
        this.priceAlertMapper = priceAlertMapper;
        this.instrumentMapper = instrumentMapper;
        this.alertDelivery = alertDelivery;
    }

    /** All of the account's alerts, newest first, every status. */
    public List<PriceAlertDto> list(Long accountId) {
        return priceAlertMapper.findByAccount(accountId).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Arms a one-shot alert. The same active alert again is returned unchanged; an account may
     * hold at most {@value #MAX_ACTIVE_ALERTS}.
     */
    public PriceAlertDto create(Long accountId, CreatePriceAlertRequest request) {
        String symbol = request.symbol().trim().toUpperCase();
        String direction = request.direction().trim().toUpperCase();
        BigDecimal threshold = request.threshold();

        if (instrumentMapper.findBySymbol(symbol) == null) {
            log.warn("Alert refused: {} is not a tradable instrument", symbol);
            throw new DomainException("VAL-422", "Instrument '" + symbol + "' is not tradable",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        PriceAlertRecord existing = priceAlertMapper.findActiveByAccountSymbolDirectionThreshold(
                accountId, symbol, direction, threshold);
        if (existing != null) {
            log.info("Alert already active for account {}: {} {} {}", accountId, symbol, direction, threshold);
            return toDto(existing);
        }

        if (priceAlertMapper.countActiveByAccount(accountId) >= MAX_ACTIVE_ALERTS) {
            log.warn("Alert refused for account {}: cap of {} active alerts reached",
                    accountId, MAX_ACTIVE_ALERTS);
            throw new DomainException("VAL-422",
                    "No more than " + MAX_ACTIVE_ALERTS + " active alerts are allowed",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        PriceAlertRecord alert = new PriceAlertRecord(accountId, symbol, direction, threshold);
        priceAlertMapper.insertActive(alert);
        log.info("Armed alert {} for account {}: {} {} {}", alert.getPriceAlertId(), accountId,
                symbol, direction, threshold);
        return toDto(priceAlertMapper.findById(alert.getPriceAlertId()));
    }

    /**
     * Disarms an alert. Idempotent: cancelling an alert that has already fired or been cancelled
     * returns it with its current status. A foreign or unknown id is the same {@code ACC-404}.
     */
    public PriceAlertDto cancel(Long accountId, Long alertId) {
        PriceAlertRecord alert = priceAlertMapper.findById(alertId);
        if (alert == null || !alert.getAccountId().equals(accountId)) {
            throw new DomainException("ACC-404", "Alert not found", HttpStatus.NOT_FOUND);
        }
        if (STATUS_ACTIVE.equals(alert.getStatus())) {
            priceAlertMapper.markCancelled(alertId, accountId);
            alert.setStatus(STATUS_CANCELLED);
        }
        log.info("Cancelled alert {} for account {}", alertId, accountId);
        return toDto(alert);
    }

    /**
     * Evaluates one market-data quote against the active alerts for its symbol. Runs on the
     * market-data consumer thread. A crossing deactivates the alert and, only if that flip
     * actually happened, hands the event to the notifications module.
     *
     * <p>{@code messageTimestamp} disambiguates redelivered messages for the event id; two messages
     * that crossed at the same timestamp are the same delivery.
     */
    @Transactional
    public void evaluate(String symbol, BigDecimal price, Instant messageTimestamp) {
        List<PriceAlertRecord> alerts = priceAlertMapper.findActiveBySymbol(symbol);
        if (alerts.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (PriceAlertRecord alert : alerts) {
            if (!PriceAlertEvaluator.crossed(alert.getDirection(), alert.getThreshold(), price)) {
                continue;
            }
            int flipped = priceAlertMapper.markTriggered(alert.getPriceAlertId(), price, now);
            if (flipped != 1) {
                log.debug("Alert {} already not active; skipping crossed delivery", alert.getPriceAlertId());
                continue;
            }
            String eventId = symbol + "-" + alert.getPriceAlertId() + "@" + messageTimestamp;
            alertDelivery.deliverPriceAlert(new PriceAlertCrossed(
                    eventId,
                    alert.getAccountId(),
                    symbol,
                    alert.getDirection(),
                    alert.getThreshold(),
                    price,
                    now));
            log.info("Alert {} crossed for account {}: last {} {} threshold {}",
                    alert.getPriceAlertId(), alert.getAccountId(), price,
                    alert.getDirection(), alert.getThreshold());
        }
    }

    private PriceAlertDto toDto(PriceAlertRecord alert) {
        return new PriceAlertDto(
                alert.getPriceAlertId(),
                alert.getAccountId(),
                alert.getSymbol(),
                alert.getDirection(),
                alert.getThreshold(),
                alert.getCurrency(),
                alert.getStatus(),
                alert.getTriggeredPrice(),
                alert.getTriggeredAt(),
                alert.getCreatedAt());
    }
}