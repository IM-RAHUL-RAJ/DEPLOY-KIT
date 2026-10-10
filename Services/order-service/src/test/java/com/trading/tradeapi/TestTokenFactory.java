package com.trading.tradeapi;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class TestTokenFactory {

    public static String createToken(Long accountId, String secret, long ttlMillis) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        // signWith(key) automatically sets algorithm to HS256 for HMAC-SHA256 keys (JJWT 0.12.x)
        return Jwts.builder()
                .issuer("auth-service")
                .subject("00000000-0000-0000-0000-000000000001")
                .claims(Map.of("accountId", accountId, "roles", List.of("CUSTOMER")))
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }
}