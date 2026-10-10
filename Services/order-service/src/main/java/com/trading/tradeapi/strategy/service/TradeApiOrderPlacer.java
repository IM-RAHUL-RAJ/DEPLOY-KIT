package com.trading.tradeapi.strategy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Places a strategy's order through the Trade REST API, exactly as the frontend does.
 *
 * <p>The call carries a short-lived token minted for this one order: subject
 * {@code strategy:<id>}, the strategy owner's {@code accountId}, role {@code STRATEGY},
 * sixty seconds. The API's own filter verifies it and the order controller's
 * account check then holds: a strategy can only trade the account that owns it.
 *
 * <p>A MARKET order, priced by the executor's own fresh quote at execution, or for a
 * price target set to LIMIT, a limit order at the target price.
 * The idempotency key is derived from the strategy and the market-data event, so a
 * retry of the same decision cannot place a second order.
 */
@Component
public class TradeApiOrderPlacer implements StrategyOrderPlacer {

    private static final long TOKEN_SECONDS = 60;

    private final RestTemplate http;
    private final ObjectMapper objectMapper;
    private final SecretKey key;
    private final String issuer;
    private final Environment env;

    public TradeApiOrderPlacer(ObjectMapper objectMapper, Environment env,
                               @Value("${jwt.secret:}") String secret,
                               @Value("${jwt.issuer}") String issuer,
                               @Value("${strategy.order-timeout}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(timeoutMs, 5000));
        factory.setReadTimeout(timeoutMs);
        this.http = new RestTemplate(factory);
        this.objectMapper = objectMapper;
        // JwtService refuses to start on a short secret, so by here it is long enough.
        this.key = secret == null || secret.isEmpty() ? null : Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.env = env;
    }

    /**
     * {@code strategy.trade-api-url} when set (the API behind a gateway, say), else this
     * service itself: {@code services.order.url} from the shared Config/application.yml on
     * the port it actually bound. Resolved per call: the bound port is only known once the
     * server has started, after this bean was built.
     */
    String ordersUrl() {
        String base = env.getProperty("strategy.trade-api-url");
        if (base == null || base.isBlank()) {
            String host = env.getRequiredProperty("services.order.url");
            String port = env.getProperty("local.server.port", env.getRequiredProperty("server.port"));
            base = host.replaceAll("/+$", "") + ":" + port;
        }
        return base.replaceAll("/+$", "") + "/api/v1/orders";
    }

    @Override
    public PlacedOrder place(long strategyId, long accountId, String symbol, String side, long quantity,
                             String orderType, java.math.BigDecimal limitPrice, String idempotencyKey) {
        if (key == null) {
            throw new OrderPlacementException("No JWT secret configured; cannot call the Trade API");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(strategyId, accountId));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", accountId);
        body.put("symbol", symbol);
        body.put("side", side);
        body.put("quantity", quantity);
        boolean limit = "LIMIT".equals(orderType) && limitPrice != null;
        body.put("orderType", limit ? "LIMIT" : "MARKET");
        if (limit) {
            // The order API takes prices to the cent.
            body.put("price", limitPrice.setScale(2, java.math.RoundingMode.HALF_UP));
        }
        body.put("idempotencyKey", idempotencyKey);

        try {
            String response = http.postForObject(ordersUrl(), new HttpEntity<>(body, headers), String.class);
            JsonNode node = objectMapper.readTree(response == null ? "{}" : response);
            String orderId = node.path("orderId").asText(null);
            String status = node.path("status").asText(null);
            if (orderId == null || "REJECTED".equals(status)) {
                throw new OrderPlacementException("Order rejected: " + node.path("message").asText("no reason given"));
            }
            return new PlacedOrder(orderId, status);
        } catch (RestClientResponseException e) {
            throw new OrderPlacementException("Trade API " + e.getStatusCode().value() + ": " + reason(e));
        } catch (RestClientException e) {
            throw new OrderPlacementException("Trade API unreachable: " + e.getMessage());
        } catch (OrderPlacementException e) {
            throw e;
        } catch (Exception e) {
            throw new OrderPlacementException("Unreadable Trade API response: " + e.getMessage());
        }
    }

    private String token(long strategyId, long accountId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("strategy:" + strategyId)
                .issuer(issuer)
                .claim("accountId", accountId)
                .claim("roles", List.of("STRATEGY"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(TOKEN_SECONDS)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private String reason(RestClientResponseException e) {
        try {
            JsonNode node = objectMapper.readTree(e.getResponseBodyAsString());
            String message = node.path("message").asText(null);
            return message != null ? message : e.getStatusText();
        } catch (Exception ignored) {
            return e.getStatusText();
        }
    }
}
