package com.trading.tradeapi.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Browser origins allowed to call the Trade REST API: the frontend's address from
 * Config/application.yml, or CORS_ALLOWED_ORIGINS (comma-separated). One list
 * feeds both the MVC CORS mapping and the JWT filter's own 401 responses, so the
 * two cannot disagree.
 */
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(
        List<String> allowedOrigins
) {}
