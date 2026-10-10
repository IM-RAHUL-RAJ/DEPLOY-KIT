package com.trading.tradeapi.notifications.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decode half of the trade-events consumer: both producers' message shapes have to land on
 * the same canonical categories and the parser must survive the fields each side omits.
 */
@DisplayName("Trade-events payload decoding")
class TradeEventPayloadTest {

    @Test
    @DisplayName("Executor and Trade API event names map to the three canonical categories")
    void eventNamesNormalise() {
        assertThat(TradeEventPayload.canonicalCategory("TRADE_EXECUTED")).isEqualTo("ORDER_FILLED");
        assertThat(TradeEventPayload.canonicalCategory("ORDER_FILLED")).isEqualTo("ORDER_FILLED");
        assertThat(TradeEventPayload.canonicalCategory("TRADE_REJECTED")).isEqualTo("ORDER_REJECTED");
        assertThat(TradeEventPayload.canonicalCategory("ORDER_REJECTED")).isEqualTo("ORDER_REJECTED");
        assertThat(TradeEventPayload.canonicalCategory("ORDER_CANCELLED")).isEqualTo("ORDER_CANCELLED");
        assertThat(TradeEventPayload.canonicalCategory("MARKET_DATA_UPDATE")).isNull();
        assertThat(TradeEventPayload.canonicalCategory(null)).isNull();
    }

    @Test
    @DisplayName("The executor's sparse fill payload decodes to a full outcome")
    void sparseExecutorPayloadDecodes() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", 7);
        payload.put("accountId", 1);
        payload.put("symbol", "AAPL");
        payload.put("side", "BUY");
        payload.put("quantity", 40L);
        payload.put("executedPrice", BigDecimal.valueOf(232.71));
        payload.put("status", "FILLED");

        NotificationService.OrderOutcome outcome =
                TradeEventPayload.asOutcome("evt-1", "ORDER_FILLED", payload);

        assertThat(outcome.category()).isEqualTo("ORDER_FILLED");
        assertThat(outcome.accountId()).isEqualTo(1L);
        assertThat(outcome.symbol()).isEqualTo("AAPL");
        assertThat(outcome.quantity()).isEqualTo(40L);
        assertThat(outcome.executedPrice()).isEqualByComparingTo("232.71");
        assertThat(outcome.reason()).isNull();
    }

    @Test
    @DisplayName("A rejection without accountId or reason still decodes without throwing")
    void sparseRejectionDecodes() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", 8);

        NotificationService.OrderOutcome outcome =
                TradeEventPayload.asOutcome("evt-2", "ORDER_REJECTED", payload);

        assertThat(outcome.accountId()).isNull();
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.quantity()).isNull();
        assertThat(outcome.executedPrice()).isNull();
    }
}