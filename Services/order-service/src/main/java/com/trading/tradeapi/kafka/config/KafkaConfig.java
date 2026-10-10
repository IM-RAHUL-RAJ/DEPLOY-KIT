package com.trading.tradeapi.kafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Kafka configuration for the Trade API service
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        // Register JSR-310 module for Java 8 time types (Instant, LocalDateTime, etc.)
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }
}
