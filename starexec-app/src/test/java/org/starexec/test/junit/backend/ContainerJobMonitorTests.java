package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.starexec.backend.AdaptivePollInterval;
import org.starexec.backend.ContainerJobMonitor;
import org.starexec.backend.PodmanBackend;
import org.starexec.backend.exception.BackendTransientException;

public class ContainerJobMonitorTests {

    @Mock
    private PodmanBackend backend;

    private ContainerJobMonitor monitor;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        monitor = new ContainerJobMonitor(backend);
    }

    @Test
    public void testCheckCompletedJobs_TransientPollFailure_RetriesAtBaseInterval()
        throws Exception {
        AdaptivePollInterval pollInterval = getPollInterval();
        while (!pollInterval.isBackedOff()) {
            pollInterval.recordIdle();
        }

        when(backend.getCompletedContainers())
            .thenThrow(
                new BackendTransientException(
                    "Broken pipe while listing completed containers",
                    new IOException("Broken pipe"),
                    "podman"
                )
            );

        invokeCheckCompletedJobs();

        verify(backend).getCompletedContainers();
        assertEquals(
            "Transient polling failures should reset the monitor to the base interval",
            pollInterval.getBaseInterval(),
            pollInterval.getCurrentInterval()
        );
        assertFalse(
            "Transient polling failures should clear backed-off state for a fast retry",
            pollInterval.isBackedOff()
        );
    }

    private AdaptivePollInterval getPollInterval() throws Exception {
        Field field = ContainerJobMonitor.class.getDeclaredField("pollInterval");
        field.setAccessible(true);
        return (AdaptivePollInterval) field.get(monitor);
    }

    private void invokeCheckCompletedJobs() throws Exception {
        Method method = ContainerJobMonitor.class.getDeclaredMethod(
            "checkCompletedJobs"
        );
        method.setAccessible(true);
        method.invoke(monitor);
    }
}
