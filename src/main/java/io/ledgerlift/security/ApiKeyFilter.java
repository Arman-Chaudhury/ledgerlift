package io.ledgerlift.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Shared-secret gate for /api/** and SOAP operations (POST /ws). The WSDL,
 * OpenAPI docs, Swagger UI and actuator stay open so integrators can discover
 * the contract before they have a key.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final String apiKey;

    public ApiKeyFilter(@Value("${ledgerlift.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        boolean soapOp = p.startsWith("/ws") && "POST".equalsIgnoreCase(req.getMethod());
        return !(p.startsWith("/api/") || soapOp);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader(HEADER);
        if (given == null || !constantTimeEquals(given, apiKey)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"missing or invalid " + HEADER + " header\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8), y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }
}
