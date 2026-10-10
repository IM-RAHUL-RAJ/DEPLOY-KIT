package com.trading.tradeapi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Verifies the access tokens the auth service issues (auth-api.yaml, JWT claims).
 *
 * <p>The signing secret has no fallback. A service that quietly started with a
 * default would either accept tokens anyone reading the source could mint, or
 * reject every real token with a secret that disagrees with the auth service's.
 * Refusing to start says which of the two configurations is missing.
 */
@Service
public class JwtService {

    /** HS256 needs a 256-bit key; a shorter secret is brute-forceable offline. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey secretKey;
    private final String issuer;

    public JwtService(@Value("${jwt.secret:}") String secret,
                      @Value("${jwt.issuer}") String issuer) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to at least " + MIN_SECRET_BYTES
                            + " bytes, and must match the auth service's");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
    }

    /**
     * Verifies signature, algorithm, expiry and issuer, and that the mandatory
     * claims (sub and a non-empty roles list) are present.
     */
    public Claims validateAndParseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("Token is missing");
        }

        // Verifies the signature and the expiry.
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token);

        String alg = jws.getHeader().getAlgorithm();
        if (!"HS256".equalsIgnoreCase(alg)) {
            throw new JwtException("Unsupported algorithm: " + alg);
        }

        Claims claims = jws.getPayload();
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new JwtException("Token subject is missing");
        }
        Object roles = claims.get("roles");
        if (!(roles instanceof List<?> roleList) || roleList.isEmpty()) {
            throw new JwtException("Token roles are missing");
        }
        return claims;
    }

    public Long extractAccountId(Claims claims) {
        Object accountIdObj = claims.get("accountId");
        if (accountIdObj == null) {
            return null;
        }
        if (accountIdObj instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(accountIdObj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public List<String> extractRoles(Claims claims) {
        Object roles = claims.get("roles");
        if (roles instanceof List<?> roleList) {
            return roleList.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
