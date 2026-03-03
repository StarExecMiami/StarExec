package org.starexec.app;

import org.starexec.logger.StarLogger;
import org.starexec.util.CsrfUtil;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Servlet filter that enforces CSRF token validation on all state-modifying
 * POST requests to /secure/* paths and the public registration endpoint.
 *
 * <p>The StarExecCommand CLI client is exempted because it sends the custom
 * {@code StarExecCommand: StarExecCommand} header on every request.  Browsers
 * cannot include arbitrary custom headers in cross-site requests without a
 * CORS preflight, so this header is a reliable, non-spoofable signal from a
 * same-process HTTP client (not a browser).
 *
 * <p>The CSRF token is expected either as:
 * <ul>
 *   <li>The {@code X-CSRF-Token} request header (AJAX / multipart uploads), or
 *   <li>The {@code csrfToken} form parameter (regular URL-encoded forms).
 * </ul>
 *
 * @see CsrfUtil
 */
public class CsrfFilter implements Filter {

    private static final StarLogger log = StarLogger.getLogger(CsrfFilter.class);

    /** Header name used by the JavaScript AJAX setup in master.js */
    public static final String CSRF_HEADER = "X-CSRF-Token";

    /** Header sent by StarExecCommand CLI on every request (Connection.setHeaders). */
    private static final String STAREXEC_COMMAND_HEADER = "StarExecCommand";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // no-op
    }

    @Override
    public void destroy() {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest servletRequest,
                         ServletResponse servletResponse,
                         FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String uri = request.getRequestURI();
            String ctx = request.getContextPath();

            boolean isSecure      = uri.startsWith(ctx + "/secure/");
            boolean isRegistration = uri.startsWith(ctx + "/public/registration/");

            if (isSecure || isRegistration) {
                if (!isApiClient(request) && !isTokenValid(request)) {
                    log.warn("doFilter",
                            "CSRF token validation failed for " + uri
                                    + " [UA=" + request.getHeader("User-Agent") + "]");
                    response.sendError(HttpServletResponse.SC_FORBIDDEN,
                            "Invalid or missing CSRF token");
                    return;
                }
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * Returns true if the request carries a valid CSRF token.
     * Accepts the token from the {@code X-CSRF-Token} header (set by the
     * global jQuery AJAX setup in master.js) or from a form parameter named
     * {@code csrfToken}.
     */
    private boolean isTokenValid(HttpServletRequest request) {
        String sessionToken = getSessionToken(request);
        if (sessionToken == null) {
            log.warn("doFilter", "CSRF validation: no token in session");
            return false;
        }

        String submitted = request.getHeader(CSRF_HEADER);
        if (submitted == null) {
            submitted = request.getParameter(CsrfUtil.CSRF_TOKEN_PARAM);
        }

        if (submitted == null) {
            log.warn("doFilter", "CSRF validation: no token submitted");
            return false;
        }

        boolean valid = constantTimeEquals(sessionToken, submitted);
        if (!valid) {
            log.warn("doFilter", "CSRF validation: token mismatch");
        }
        return valid;
    }

    private String getSessionToken(HttpServletRequest request) {
        javax.servlet.http.HttpSession session = request.getSession(false);
        if (session == null) return null;
        return (String) session.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR);
    }

    /** Constant-time string comparison to prevent timing attacks. */
    private boolean constantTimeEquals(String a, String b) {
        try {
            return MessageDigest.isEqual(
                    a.getBytes(StandardCharsets.UTF_8),
                    b.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isApiClient(HttpServletRequest request) {
        return request.getHeader(STAREXEC_COMMAND_HEADER) != null;
    }
}
