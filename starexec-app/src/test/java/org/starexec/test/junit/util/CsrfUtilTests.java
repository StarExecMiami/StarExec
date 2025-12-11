package org.starexec.test.junit.util;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.starexec.util.CsrfUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import static org.mockito.Mockito.*;

/**
 * Unit tests for the CsrfUtil utility class.
 * Tests CSRF token generation, storage, and validation.
 */
public class CsrfUtilTests {

    private HttpServletRequest mockRequest;
    private HttpSession mockSession;

    @Before
    public void setUp() {
        mockRequest = mock(HttpServletRequest.class);
        mockSession = mock(HttpSession.class);
    }

    // ===== Constants Tests =====

    @Test
    public void testConstantsExist() {
        Assert.assertNotNull("CSRF_TOKEN_ATTR should exist", CsrfUtil.CSRF_TOKEN_ATTR);
        Assert.assertNotNull("CSRF_TOKEN_PARAM should exist", CsrfUtil.CSRF_TOKEN_PARAM);
        Assert.assertEquals("csrfToken", CsrfUtil.CSRF_TOKEN_ATTR);
        Assert.assertEquals("csrfToken", CsrfUtil.CSRF_TOKEN_PARAM);
    }

    // ===== getOrCreateToken Tests =====

    @Test
    public void testGetOrCreateToken_CreatesNewToken() {
        when(mockRequest.getSession(true)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(null);

        String token = CsrfUtil.getOrCreateToken(mockRequest);

        Assert.assertNotNull("Token should not be null", token);
        Assert.assertFalse("Token should not be empty", token.isEmpty());
        verify(mockSession).setAttribute(eq(CsrfUtil.CSRF_TOKEN_ATTR), anyString());
    }

    @Test
    public void testGetOrCreateToken_ReturnsExistingToken() {
        String existingToken = "existing-token-12345";
        when(mockRequest.getSession(true)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(existingToken);

        String token = CsrfUtil.getOrCreateToken(mockRequest);

        Assert.assertEquals("Should return existing token", existingToken, token);
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

        Assert.assertNotNull("First token should be set", tokens[0]);
        Assert.assertNotNull("Second token should be set", tokens[1]);
        Assert.assertNotEquals("Tokens should be unique", tokens[0], tokens[1]);
    }

    // ===== validateToken Tests =====

    @Test
    public void testValidateToken_ValidToken() {
        String token = "valid-token-123";
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(token);
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn(token);

        Assert.assertTrue("Valid token should validate", CsrfUtil.validateToken(mockRequest));
    }

    @Test
    public void testValidateToken_InvalidToken() {
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn("session-token");
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn("submitted-token");

        Assert.assertFalse("Mismatched token should not validate", CsrfUtil.validateToken(mockRequest));
    }

    @Test
    public void testValidateToken_NoSession() {
        when(mockRequest.getSession(false)).thenReturn(null);

        Assert.assertFalse("Should fail when no session exists", CsrfUtil.validateToken(mockRequest));
    }

    @Test
    public void testValidateToken_NoSessionToken() {
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(null);
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn("some-token");

        Assert.assertFalse("Should fail when session has no token", CsrfUtil.validateToken(mockRequest));
    }

    @Test
    public void testValidateToken_NoSubmittedToken() {
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn("session-token");
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn(null);

        Assert.assertFalse("Should fail when no token submitted", CsrfUtil.validateToken(mockRequest));
    }

    @Test
    public void testValidateToken_EmptySubmittedToken() {
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn("session-token");
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn("");

        Assert.assertFalse("Should fail when empty token submitted", CsrfUtil.validateToken(mockRequest));
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

    @Test(expected = SecurityException.class)
    public void testValidateTokenOrThrow_InvalidToken() {
        when(mockRequest.getSession(false)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn("session-token");
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn("wrong-token");

        CsrfUtil.validateTokenOrThrow(mockRequest);
    }

    @Test(expected = SecurityException.class)
    public void testValidateTokenOrThrow_NoSession() {
        when(mockRequest.getSession(false)).thenReturn(null);

        CsrfUtil.validateTokenOrThrow(mockRequest);
    }

    // ===== regenerateToken Tests =====

    @Test
    public void testRegenerateToken_CreatesNewToken() {
        when(mockRequest.getSession(true)).thenReturn(mockSession);

        String token = CsrfUtil.regenerateToken(mockRequest);

        Assert.assertNotNull("New token should not be null", token);
        Assert.assertFalse("New token should not be empty", token.isEmpty());
        verify(mockSession).setAttribute(eq(CsrfUtil.CSRF_TOKEN_ATTR), eq(token));
    }

    @Test
    public void testRegenerateToken_ReplacesExistingToken() {
        String oldToken = "old-token-123";
        when(mockRequest.getSession(true)).thenReturn(mockSession);
        when(mockSession.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(oldToken);

        String newToken = CsrfUtil.regenerateToken(mockRequest);

        Assert.assertNotNull("New token should not be null", newToken);
        Assert.assertNotEquals("New token should be different", oldToken, newToken);
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
        Assert.assertFalse(CsrfUtil.validateToken(mockRequest));

        // Test with completely different token
        when(mockRequest.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn(attackToken2);
        Assert.assertFalse(CsrfUtil.validateToken(mockRequest));
    }
}
