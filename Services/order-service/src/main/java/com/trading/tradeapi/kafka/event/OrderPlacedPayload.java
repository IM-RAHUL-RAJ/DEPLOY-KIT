package com.trading.tradeapi.kafka.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Payload for ORDER_PLACED events published to the 'orders' topic
 */
public class OrderPlacedPayload {
    @JsonProperty("orderId")
    private Long orderId;

    @JsonProperty("accountId")
    private Long accountId;

    @JsonProperty("symbol")
    private String symbol;

    @JsonProperty("side")
    private String side;

    @JsonProperty("quantity")
    private Long quantity;

    @JsonProperty("price")
    private BigDecimal price;

    @JsonProperty("idempotencyKey")
    private String idempotencyKey;

    @JsonProperty("createdOn")
    private String createdOn;

    public OrderPlacedPayload() {}

    public OrderPlacedPayload(Long orderId, Long accountId, String symbol, String side,
                              Long quantity, BigDecimal price, String idempotencyKey, String createdOn) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.idempotencyKey = idempotencyKey;
        this.createdOn = createdOn;
    }

    public String getOrderId() {
        return orderId.toString();
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public Long getQuantity() {
        return quantity;
    }

    public void setQuantity(Long quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(String createdOn) {
        this.createdOn = createdOn;
    }
}
