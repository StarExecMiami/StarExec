package org.starexec.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.util.CsrfUtil;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class CsrfFilterCoverageTest {

    private static final String SESSION_CSRF = "synthetic-test-csrf-value";
    private HttpServletRequest request;
    private HttpServletResponse response;
    private HttpSession session;
    private FilterChain chain;

    @BeforeEach
    public void setUp() {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        chain = mock(FilterChain.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContextPath()).thenReturn("/starexec");
        when(request.getRequestURI()).thenReturn("/starexec/services/jobs/notifications/subscribe");
        when(request.getServletPath()).thenReturn("/services");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn(SESSION_CSRF);
    }

    private void refused() throws Exception {
        new CsrfFilter().doFilter(request, response, chain);
        verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
        verifyNoInteractions(chain);
        verify(request, never()).getSession(true);
    }

    private void allowed() throws Exception {
        new CsrfFilter().doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(response);
    }

    @Test
    public void formAndJsonServicePostsRequireCsrf() throws Exception {
        for (String type : new String[]{"application/x-www-form-urlencoded", "application/json"}) {
            setUp();
            when(request.getContentType()).thenReturn(type);
            refused();
        }
    }

    @Test
    public void everyStateChangingMethodRequiresCsrf() throws Exception {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            setUp();
            when(request.getMethod()).thenReturn(method);
            refused();
        }
    }

    @Test
    public void formParameterAllowsAnAuthenticatedBrowserWrite() throws Exception {
        when(request.getParameter(CsrfUtil.CSRF_TOKEN_PARAM)).thenReturn(SESSION_CSRF);
        allowed();
    }

    @Test
    public void ajaxHeaderAllowsJsonWrites() throws Exception {
        when(request.getContentType()).thenReturn("application/json");
        when(request.getHeader(CsrfFilter.CSRF_HEADER)).thenReturn(SESSION_CSRF);
        allowed();
    }

    @Test
    public void aMismatchedValueIsRefused() throws Exception {
        when(request.getHeader(CsrfFilter.CSRF_HEADER)).thenReturn("a-different-test-value");
        refused();
    }

    @Test
    public void aMissingSessionIsRefusedWithoutCreatingOne() throws Exception {
        when(request.getSession(false)).thenReturn(null);
        when(request.getHeader(CsrfFilter.CSRF_HEADER)).thenReturn(SESSION_CSRF);
        refused();
    }

    @Test
    public void safeMethodsRemainAvailableWithoutCsrf() throws Exception {
        for (String method : new String[]{"GET", "HEAD", "OPTIONS"}) {
            setUp();
            when(request.getMethod()).thenReturn(method);
            allowed();
        }
    }

    @Test
    public void theExistingCommandHeaderContractIsPreserved() throws Exception {
        when(request.getHeader("StarExecCommand")).thenReturn("StarExecCommand");
        allowed();
    }

    @Test
    public void aCommandUserAgentDoesNotBypassCsrf() throws Exception {
        when(request.getHeader("User-Agent")).thenReturn("StarExecCommand");
        refused();
    }

    @Test
    public void servletMappingDeterminesProtectionAfterContainerNormalization() throws Exception {
        for (String uri : new String[]{"/starexec/services;segment/jobs/notifications/subscribe",
                "/starexec/%73ervices/jobs/notifications/subscribe"}) {
            setUp();
            when(request.getRequestURI()).thenReturn(uri);
            refused();
        }
    }

    @Test
    public void wildcardServletMappingUsesDecodedPathInfo() throws Exception {
        when(request.getServletPath()).thenReturn("");
        when(request.getPathInfo()).thenReturn("/services/jobs/notifications/subscribe");
        refused();
    }

    @Test
    public void aSiblingPathDoesNotMatchTheProtectedPrefix() throws Exception {
        when(request.getServletPath()).thenReturn("/services-extra");
        allowed();
    }

    @Test
    public void secureServletsAndRegistrationRemainProtected() throws Exception {
        for (String path : new String[]{"/secure/add/job", "/public/registration/manager"}) {
            setUp();
            when(request.getServletPath()).thenReturn(path);
            when(request.getRequestURI()).thenReturn("/starexec" + path);
            refused();
        }
    }

    @Test
    public void unrelatedPublicAndContainerLoginEndpointsAreUnchanged() throws Exception {
        for (String path : new String[]{"/public/health/live", "/j_security_check"}) {
            setUp();
            when(request.getServletPath()).thenReturn(path);
            when(request.getRequestURI()).thenReturn("/starexec" + path);
            allowed();
        }
    }
}
