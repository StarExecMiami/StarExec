package org.starexec.test.junit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.RESTServices;
import org.starexec.constants.R;
import org.starexec.data.database.Queues;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.to.Queue;
import org.starexec.jobs.JobManager;
import org.starexec.util.SessionUtil;

/**
 * Tests queue load reporting for active, inactive, missing, and unauthorized queues.
 */
public class RESTServicesClusterLoadsTests {

    private final RESTServices services = new RESTServices();

    private static Queue queue(int id, String name, String status) {
        Queue q = new Queue();
        q.setId(id);
        q.setName(name);
        q.setStatus(status);
        return q;
    }

    @Test
    public void adminGetsInformativeLoadMessageForInactiveQueueWithout404() {
        JobManager.clearLoadBalanceMonitors();
        HttpServletRequest request = mock(HttpServletRequest.class);
        Queue inactive = queue(900_001, "all.q", R.QUEUE_STATUS_INACTIVE);

        try (
            MockedStatic<SessionUtil> sessionMock = Mockito.mockStatic(SessionUtil.class);
            MockedStatic<Queues> queuesMock = Mockito.mockStatic(Queues.class);
            MockedStatic<GeneralSecurity> secMock = Mockito.mockStatic(GeneralSecurity.class)
        ) {
            sessionMock.when(() -> SessionUtil.getUserId(request)).thenReturn(7);
            queuesMock.when(() -> Queues.get(900_001)).thenReturn(inactive);
            secMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(7)).thenReturn(true);

            String result = services.getLoadsForQueue(900_001, request);

            assertNotNull(result);
            assertTrue(result.contains("all.q"));
            assertTrue(result.contains("inactive"));
        }
    }

    @Test
    public void nonAdminGets404ForInactiveQueue() {
        JobManager.clearLoadBalanceMonitors();
        HttpServletRequest request = mock(HttpServletRequest.class);
        Queue inactive = queue(900_002, "dead.q", R.QUEUE_STATUS_INACTIVE);

        try (
            MockedStatic<SessionUtil> sessionMock = Mockito.mockStatic(SessionUtil.class);
            MockedStatic<Queues> queuesMock = Mockito.mockStatic(Queues.class);
            MockedStatic<GeneralSecurity> secMock = Mockito.mockStatic(GeneralSecurity.class)
        ) {
            sessionMock.when(() -> SessionUtil.getUserId(request)).thenReturn(42);
            queuesMock.when(() -> Queues.get(900_002)).thenReturn(inactive);
            secMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(42)).thenReturn(false);

            WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> services.getLoadsForQueue(900_002, request)
            );
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getResponse().getStatus());
        }
    }

    @Test
    public void unauthenticatedOrMissingQueueReturns404() {
        JobManager.clearLoadBalanceMonitors();
        HttpServletRequest request = mock(HttpServletRequest.class);

        try (
            MockedStatic<SessionUtil> sessionMock = Mockito.mockStatic(SessionUtil.class);
            MockedStatic<Queues> queuesMock = Mockito.mockStatic(Queues.class)
        ) {
            sessionMock.when(() -> SessionUtil.getUserId(request)).thenReturn(R.PUBLIC_USER_ID);
            WebApplicationException publicEx = assertThrows(
                WebApplicationException.class,
                () -> services.getLoadsForQueue(14235, request)
            );
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), publicEx.getResponse().getStatus());

            sessionMock.when(() -> SessionUtil.getUserId(request)).thenReturn(42);
            queuesMock.when(() -> Queues.get(999_999)).thenReturn(null);
            WebApplicationException missingEx = assertThrows(
                WebApplicationException.class,
                () -> services.getLoadsForQueue(999_999, request)
            );
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), missingEx.getResponse().getStatus());
            assertNull(JobManager.getLoadRepresentationForQueue(999_999));
        }
    }

    @Test
    public void activeQueueReturnsLoadMonitorTextForAuthenticatedUser() {
        JobManager.clearLoadBalanceMonitors();
        HttpServletRequest request = mock(HttpServletRequest.class);
        Queue active = queue(900_003, "kubernetes.q", R.QUEUE_STATUS_ACTIVE);

        try (
            MockedStatic<SessionUtil> sessionMock = Mockito.mockStatic(SessionUtil.class);
            MockedStatic<Queues> queuesMock = Mockito.mockStatic(Queues.class)
        ) {
            sessionMock.when(() -> SessionUtil.getUserId(request)).thenReturn(42);
            queuesMock.when(() -> Queues.get(900_003)).thenReturn(active);

            String result = services.getLoadsForQueue(900_003, request);

            assertNotNull(result);
            assertTrue(result.contains("minimum ="), "Unexpected load text: " + result);
        }
    }
}
