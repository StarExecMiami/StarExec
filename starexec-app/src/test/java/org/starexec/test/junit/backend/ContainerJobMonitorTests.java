package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anySet;
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

    @Test
    public void testCheckCompletedJobs_PerformsStaleExitedContainerSweep()
        throws Exception {
        when(backend.getCompletedContainers()).thenReturn(java.util.Collections.emptyList());
        when(backend.cleanupStaleExitedContainers(anySet())).thenReturn(0);

        invokeCheckCompletedJobs();

        verify(backend).cleanupStaleExitedContainers(anySet());
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

    // ---------------------------------------------------------------------
    // Resource-limit detection in container mode.
    //
    // stats.json carries timings and nothing else; the wallclockExceeded,
    // cpuExceeded and memoryExceeded flags come only from runsolver's
    // watcher.out. The monitor used to return as soon as it found stats.json,
    // so in container mode those flags stayed false and a solver that blew its
    // limit was recorded as STATUS_COMPLETE. These tests pin the two halves of
    // that: the flags must survive, and stats.json must still win on timings.
    // ---------------------------------------------------------------------

    /** Writes the pair of files a real container run leaves behind. */
    private java.nio.file.Path outputDirWith(String statsJson, String watcherOut)
        throws IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("cjm-test");
        dir.toFile().deleteOnExit();
        if (statsJson != null) {
            java.nio.file.Files.writeString(dir.resolve("stats.json"), statsJson);
        }
        if (watcherOut != null) {
            java.nio.file.Files.writeString(dir.resolve("watcher.out"), watcherOut);
        }
        return dir;
    }

    private Object parseRunsolverOutput(java.nio.file.Path dir) throws Exception {
        Method m = ContainerJobMonitor.class.getDeclaredMethod(
            "parseRunsolverOutput",
            java.nio.file.Path.class
        );
        m.setAccessible(true);
        return m.invoke(monitor, dir);
    }

    private boolean flag(Object stats, String name) throws Exception {
        Field f = stats.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(stats);
    }

    private double number(Object stats, String name) throws Exception {
        Field f = stats.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getDouble(stats);
    }

    @Test
    public void statsJsonDoesNotSuppressWallclockLimitDetection() throws Exception {
        java.nio.file.Path dir = outputDirWith(
            "{\"wallclockTime\": 10.0, \"cpuTime\": 9.0}",
            "Maximum wall clock time exceeded: sending SIGTERM then SIGKILL\n"
                + "Child status: 0\n"
        );

        Object stats = parseRunsolverOutput(dir);

        assertTrue(
            "wallclockExceeded must survive even though stats.json exists",
            flag(stats, "wallclockExceeded")
        );
    }

    @Test
    public void statsJsonDoesNotSuppressCpuOrMemoryLimitDetection() throws Exception {
        Object cpu = parseRunsolverOutput(
            outputDirWith("{\"cpuTime\": 5.0}", "Maximum CPU time exceeded: sending SIGTERM\n")
        );
        assertTrue("cpuExceeded must survive stats.json", flag(cpu, "cpuExceeded"));

        Object mem = parseRunsolverOutput(
            outputDirWith("{\"cpuTime\": 5.0}", "Maximum VSize exceeded: sending SIGTERM\n")
        );
        assertTrue("memoryExceeded must survive stats.json", flag(mem, "memoryExceeded"));
    }

    @Test
    public void statsJsonRemainsAuthoritativeForTimings() throws Exception {
        // watcher.out carries no timings, so this only pins that reading it did not
        // clobber what stats.json provided -- the fix must be additive.
        Object stats = parseRunsolverOutput(
            outputDirWith(
                "{\"wallclockTime\": 42.5, \"cpuTime\": 41.25}",
                "Maximum CPU time exceeded\nChild status: 0\n"
            )
        );

        assertEquals(42.5, number(stats, "wallclockTime"), 0.0001);
        assertEquals(41.25, number(stats, "cpuTime"), 0.0001);
        assertTrue(flag(stats, "cpuExceeded"));
    }

    @Test
    public void noLimitLinesLeavesFlagsClear() throws Exception {
        // The inverse error matters as much: a clean run must not be reported as
        // having exceeded anything.
        Object stats = parseRunsolverOutput(
            outputDirWith(
                "{\"wallclockTime\": 1.0, \"cpuTime\": 1.0}",
                "Child status: 0\nmaximum resident set size: 1024\n"
            )
        );

        assertFalse(flag(stats, "wallclockExceeded"));
        assertFalse(flag(stats, "cpuExceeded"));
        assertFalse(flag(stats, "memoryExceeded"));
    }
}
