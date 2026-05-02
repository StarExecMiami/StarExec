package org.starexec.util;

import org.starexec.logger.StarLogger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Utility class for CSRF (Cross-Site Request Forgery) token management.
 * Provides methods to generate, store, and validate CSRF tokens.
 *
 * @author StarExec Team
 */
public class CsrfUtil {
    private static final StarLogger log = StarLogger.getLogger(CsrfUtil.class);

    /** Session attribute name for storing the CSRF token */
    public static final String CSRF_TOKEN_ATTR = "csrfToken";

    /** Form parameter name for CSRF token */
    public static final String CSRF_TOKEN_PARAM = "csrfToken";

    /**
     * Generates a new CSRF token and stores it in the session.
     * If a token already exists in the session, it is returned instead
     * of generating a new one.
     *
     * @param request The HTTP request
     * @return The CSRF token
     */
    public static String getOrCreateToken(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        String token = (String) session.getAttribute(CSRF_TOKEN_ATTR);
        
        if (token == null) {
            token = generateToken();
            session.setAttribute(CSRF_TOKEN_ATTR, token);
            log.trace("Generated new CSRF token for session");
        }
        
        return token;
    }

    /**
     * Validates the CSRF token from the request against the one stored in session.
     * Uses constant-time comparison to prevent timing attacks.
     *
     * @param request The HTTP request containing the CSRF token parameter
     * @return true if the token is valid, false otherwise
     */
    public static boolean validateToken(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            log.warn("CSRF validation failed: no session exists");
            return false;
        }

        String sessionToken = (String) session.getAttribute(CSRF_TOKEN_ATTR);
        String submittedToken = request.getParameter(CSRF_TOKEN_PARAM);

        if (sessionToken == null || submittedToken == null) {
            log.warn("CSRF validation failed: missing token (session=" + 
                    (sessionToken != null) + ", submitted=" + (submittedToken != null) + ")");
            return false;
        }

        boolean valid = constantTimeEquals(sessionToken, submittedToken);
        if (!valid) {
            log.warn("CSRF validation failed: token mismatch");
        }
        
        return valid;
    }

    /**
     * Validates the CSRF token and throws a SecurityException if invalid.
     *
     * @param request The HTTP request
     * @throws SecurityException if the CSRF token is invalid
     */
    public static void validateTokenOrThrow(HttpServletRequest request) throws SecurityException {
        if (!validateToken(request)) {
            throw new SecurityException("Invalid CSRF token");
        }
    }

    /**
     * Regenerates the CSRF token. Call this after successful form submission
     * to prevent token reuse attacks.
     *
     * @param request The HTTP request
     * @return The new CSRF token
     */
    public static String regenerateToken(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        String token = generateToken();
        session.setAttribute(CSRF_TOKEN_ATTR, token);
        log.trace("Regenerated CSRF token for session");
        return token;
    }

    /**
     * Generates a cryptographically strong random token.
     *
     * @return A new random token
     */
    private static String generateToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * Compares two strings in constant time to prevent timing attacks.
     *
     * @param a First string
     * @param b Second string
     * @return true if strings are equal, false otherwise
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            byte[] aBytes = a.getBytes("UTF-8");
            byte[] bBytes = b.getBytes("UTF-8");
            return MessageDigest.isEqual(aBytes, bBytes);
        } catch (Exception e) {
            log.error("Error comparing CSRF tokens", e);
            return false;
        }
    }
}
