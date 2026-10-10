package com.trading.tradeapi.admin;

import com.trading.tradeapi.exception.AdminAccessDeniedException;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Access control for the admin API. The authenticated roles are placed on the
 * request by {@link JwtAuthenticationFilter} after the JWT signature has been
 * verified, so a caller can never spoof the role in the request body.
 */
@Component
public class AdminAuthorizer {

    private static final String ADMIN_ROLE = "ADMIN";

    /**
     * Refuses the caller unless the verified token carries the ADMIN role.
     *
     * @return the authenticated admin's uuid, so the caller can attribute a status
     *         change to a real row rather than trusting anything in the body
     * @throws AdminAccessDeniedException when the token is not an admin's
     */
    public String requireAdmin(HttpServletRequest request) {
        Object roles = request.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_ROLES_ATTR);
        boolean isAdmin = roles instanceof Collection<?> collection && collection.contains(ADMIN_ROLE);
        if (!isAdmin) {
            throw new AdminAccessDeniedException();
        }
        Object uuid = request.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_UUID_ATTR);
        return uuid instanceof String value ? value : null;
    }
}
