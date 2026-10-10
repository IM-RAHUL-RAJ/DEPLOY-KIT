package com.trading.tradeapi.kafka.event;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kafka event envelope containing common fields for all events
 */
public class KafkaEventEnvelope<T> {
    @JsonProperty("eventId")
    private String eventId;

    @JsonProperty("eventType")
    private String eventType;

    @JsonProperty("eventTime")
    private String eventTime;

    @JsonProperty("source")
    private String source;

    @JsonProperty("schemaVersion")
    private Integer schemaVersion;

    @JsonProperty("payload")
    private T payload;

    public KafkaEventEnvelope() {}

    public KafkaEventEnvelope(String eventId, String eventType, String eventTime, 
                              String source, Integer schemaVersion, T payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.eventTime = eventTime;
        this.source = source;
        this.schemaVersion = schemaVersion;
        this.payload = payload;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventTime() {
        return eventTime;
    }

    public void setEventTime(String eventTime) {
        this.eventTime = eventTime;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public T getPayload() {
        return payload;
    }

    public void setPayload(T payload) {
        this.payload = payload;
    }
}
