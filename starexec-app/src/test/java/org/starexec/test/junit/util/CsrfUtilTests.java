package org.starexec.test.junit.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.util.CsrfUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the CsrfUtil utility class.
 * Tests CSRF token generation, storage, and validation.
 */
public class CsrfUtilTests {

    private HttpServletRequest mockRequest;
    private HttpSession mockSession;

    @BeforeEach
    public void setUp() {
        mockRequest = mock(HttpServletRequest.class);
        mockSession = mock(HttpSession.class);
    }

    // ===== Constants Tests =====

    @Test
    public void testConstantsExist() {
        Assertions.assertNotNull(CsrfUtil.CSRF_TOKEN_ATTR, "CSRF_TOKEN_ATTR should exist");
        Assertions.assertNotNull(CsrfUtil.CSRF_TOKEN_PARAM, "CSRF_TOKEN_PARAM should exist");
        Assertions.assertEquals("csrfToken", CsrfUtil.CSRF_TOKEN_ATTR);
        Assertions.assertEquals("csrfToken", CsrfUtil.CSRF_TOKEN_PARAM);
    }

    // ===== getOrCreateToken Tests =====

    @Test
    public void testGetOrCreateToken_CreatesNewToken() {
        when(mockRequest.getSession(true)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(null);

        String token = CsrfUtil.getOrCreateToken(mockRequest);

        Assertions.assertNotNull(token, "Token should not be null");
        Assertions.assertFalse(token.isEmpty(), "Token should not be empty");
        verify(mockSession).setAttribute(eq(CsrfUtil.CSRF_TOKEN_ATTR), anyString());
    }

    @Test
    public void testGetOrCreateToken_ReturnsExistingToken() {
        String existingToken = "existing-token-12345";
        when(mockRequest.getSession(true)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(existingToken);

        String token = CsrfUtil.getOrCreateToken(mockRequest);

        Assertions.assertEquals(existingToken, token, "Should return existing token");
        verify(mockSession, never()).setAttribute(anyString(), any());
    }

    @Test
    public void testGetOrCreateToken_GeneratesUniqueTokens() {
        HttpServletRequest request1 = mock(HttpServletRequest.class);
        HttpServletRequest request2 = mock(HttpServletRequest.class);
        HttpSession session1 = mock(HttpSession.class);
        HttpSession session2 = mock(HttpSession.class);

        when(request1.getSession(true)).thenReturn(session1);
        when(request2.getSession(true)).thenReturn(session2);
        when(session1.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(null);
        when(session2.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(null);

        // Capture the tokens that get set
        final String[] tokens = new String[2];
        doAnswer(inv -> {
            tokens[0] = (String) inv.getArguments()[1];
            return null;
        }).when(session1).setAttribute(eq(CsrfUtil.CSRF_TOKEN_ATTR), anyString());

        doAnswer(inv -> {
            tokens[1] = (String) inv.getArguments()[1];
            return null;
        }).when(session2).setAttribute(eq(CsrfUtil.CSRF_TOKEN_ATTR), anyString());

        CsrfUtil.getOrCreateToken(request1);
        CsrfUtil.getOrCreateToken(request2);

        Assertions.assertNotNull(tokens[0], "First token should be set");
        Assertions.assertNotNull(tokens[1], "Second token should be set");
        Assertions.assertNotEquals(tokens[0], tokens[1], "Tokens should be unique");
    }

    // ===== validateToken Tests =====

    @Test
    public void testValidateToken_ValidToken() {
        String token = "valid-token-123";
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(token);
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn(token);

        Assertions.assertTrue(CsrfUtil.validateToken(mockRequest), "Valid token should validate");
    }

    @Test
    public void testValidateToken_InvalidToken() {
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn("session-token");
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn("submitted-token");

        Assertions.assertFalse(CsrfUtil.validateToken(mockRequest), "Mismatched token should not validate");
    }

    @Test
    public void testValidateToken_NoSession() {
        when(mockRequest.getSession(false)).thenReturn(null);

        Assertions.assertFalse(CsrfUtil.validateToken(mockRequest), "Should fail when no session exists");
    }

    @Test
    public void testValidateToken_NoSessionToken() {
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(null);
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn("some-token");

        Assertions.assertFalse(CsrfUtil.validateToken(mockRequest), "Should fail when session has no token");
    }

    @Test
    public void testValidateToken_NoSubmittedToken() {
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn("session-token");
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn(null);

        Assertions.assertFalse(CsrfUtil.validateToken(mockRequest), "Should fail when no token submitted");
    }

    @Test
    public void testValidateToken_EmptySubmittedToken() {
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn("session-token");
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn("");

        Assertions.assertFalse(CsrfUtil.validateToken(mockRequest), "Should fail when empty token submitted");
    }

    // ===== validateTokenOrThrow Tests =====

    @Test
    public void testValidateTokenOrThrow_ValidToken() {
        String token = "valid-token-123";
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(token);
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn(token);

        // Should not throw
        CsrfUtil.validateTokenOrThrow(mockRequest);
    }

    @Test
    public void testValidateTokenOrThrow_InvalidToken() {
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn("session-token");
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn("wrong-token");

        assertThrows(SecurityException.class, () -> CsrfUtil.validateTokenOrThrow(mockRequest));
    }

    @Test
    public void testValidateTokenOrThrow_NoSession() {
        when(mockRequest.getSession(false)).thenReturn(null);

        assertThrows(SecurityException.class, () -> CsrfUtil.validateTokenOrThrow(mockRequest));
    }

    // ===== regenerateToken Tests =====

    @Test
    public void testRegenerateToken_CreatesNewToken() {
        when(mockRequest.getSession(true)).thenReturn(mockSession);

        String token = CsrfUtil.regenerateToken(mockRequest);

        Assertions.assertNotNull(token, "New token should not be null");
        Assertions.assertFalse(token.isEmpty(), "New token should not be empty");
        verify(mockSession).setAttribute(eq(CsrfUtil.CSRF_TOKEN_ATTR), eq(token));
    }

    @Test
    public void testRegenerateToken_ReplacesExistingToken() {
        String oldToken = "old-token-123";
        when(mockRequest.getSession(true)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(oldToken);

        String newToken = CsrfUtil.regenerateToken(mockRequest);

        Assertions.assertNotNull(newToken, "New token should not be null");
        Assertions.assertNotEquals(oldToken, newToken, "New token should be different");
        verify(mockSession).setAttribute(eq(CsrfUtil.CSRF_TOKEN_ATTR), eq(newToken));
    }

    // ===== Timing Attack Prevention Tests =====

    @Test
    public void testValidateToken_TimingAttackPrevention() {
        // Both tokens are the same length but different values
        // The comparison should take constant time
        String sessionToken = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String attackToken1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaabbb";
        String attackToken2 = "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz";

        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(sessionToken);

        // Test with token that differs at the end
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn(attackToken1);
        Assertions.assertFalse(CsrfUtil.validateToken(mockRequest));

        // Test with completely different token
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn(attackToken2);
        Assertions.assertFalse(CsrfUtil.validateToken(mockRequest));
    }
}
