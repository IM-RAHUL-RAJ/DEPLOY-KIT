package com.trading.tradeapi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Verifies the bearer token on every /api/v1/** request and puts the verified
 * claims on the request for the controllers to authorise against.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_ACCOUNT_ID_ATTR = "authenticatedAccountId";
    public static final String AUTHENTICATED_ROLES_ATTR = "authenticatedRoles";
    public static final String AUTHENTICATED_UUID_ATTR = "authenticatedUuid";

    private final JwtService jwtService;
    private final Set<String> allowedOrigins;

    public JwtAuthenticationFilter(JwtService jwtService, CorsProperties cors) {
        this.jwtService = jwtService;
        this.allowedOrigins = Set.copyOf(cors.allowedOrigins());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CORS preflight carries no token; the browser needs it answered to send the real request.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendUnauthorized(request, response);
            return;
        }

        // Only token verification is inside the try: an exception thrown further
        // down the chain is that request's own failure, not an authentication one,
        // and reporting it as a 401 would sign the user out for a server bug.
        Long accountId;
        List<String> roles;
        String subject;
        try {
            Claims claims = jwtService.validateAndParseToken(authHeader.substring(7).trim());
            accountId = jwtService.extractAccountId(claims);
            roles = jwtService.extractRoles(claims);
            subject = claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            sendUnauthorized(request, response);
            return;
        }

        // A client always carries an account claim. An administrator may legitimately
        // have no trading account, so the admin role is sufficient on its own.
        if (accountId == null && !roles.contains("ADMIN")) {
            sendUnauthorized(request, response);
            return;
        }

        request.setAttribute(AUTHENTICATED_ACCOUNT_ID_ATTR, accountId);
        request.setAttribute(AUTHENTICATED_ROLES_ATTR, roles);
        request.setAttribute(AUTHENTICATED_UUID_ATTR, subject);
        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        // This response is written before Spring MVC's CORS handling runs, so echo
        // the caller's origin here, but only when it is on the configured list.
        String origin = request.getHeader("Origin");
        if (origin != null && allowedOrigins.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Vary", "Origin");
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"errorCode\":\"AUTH-401\",\"message\":\"Unauthorised\"}");
    }

    /** The verified account of the current request, or null for an administrator. */
    public static Long getAccountIdFromContext() {
        HttpServletRequest request = currentRequest();
        Object accountId = request == null ? null : request.getAttribute(AUTHENTICATED_ACCOUNT_ID_ATTR);
        return accountId instanceof Long value ? value : null;
    }

    /** The verified user UUID (the JWT subject) of the current request. */
    public static String getUserUuidFromContext() {
        HttpServletRequest request = currentRequest();
        Object uuid = request == null ? null : request.getAttribute(AUTHENTICATED_UUID_ATTR);
        return uuid instanceof String value ? value : null;
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest()
                : null;
    }
}
