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

    /**
     * A drain beginning while a poll is in flight must not process the same completed
     * containers twice. The scheduler is single-threaded, so the shutdown thread is the
     * only other entrant; cancel(false) does not stop a poll that has already started.
     *
     * <p>This measures actual overlap rather than asserting the method carries a
     * modifier: the mocked backend records how many callers are inside it at once.
     * Without mutual exclusion the observed maximum is 2.
     */
    @Test
    public void concurrentPollAndDrainDoNotOverlap() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger inside =
            new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxInside =
            new java.util.concurrent.atomic.AtomicInteger();

        when(backend.getCompletedContainers()).thenAnswer(inv -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            Thread.sleep(120);
            inside.decrementAndGet();
            return java.util.Collections.emptyList();
        });

        Method check = ContainerJobMonitor.class.getDeclaredMethod("checkCompletedJobs");
        check.setAccessible(true);

        Runnable call = () -> {
            try {
                check.invoke(monitor);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        Thread poll = new Thread(call, "test-poll");
        Thread drain = new Thread(call, "test-drain");
        poll.start();
        Thread.sleep(20);   // let the first one get inside
        drain.start();
        poll.join(5000);
        drain.join(5000);

        assertEquals(
            "poll and drain must not be inside checkCompletedJobs at the same time",
            1,
            maxInside.get()
        );
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
                WATCHER_CHILD_STATUS_0 + WATCHER_MAX_RSS_1024
            )
        );

        assertFalse(flag(stats, "wallclockExceeded"));
        assertFalse(flag(stats, "cpuExceeded"));
        assertFalse(flag(stats, "memoryExceeded"));
    }

    // ---------------------------------------------------------------------
    // Fixtures quoted from the producer, not from the parser.
    //
    // These strings are transcribed from runsolver's own source, vendored at
    // org/starexec/config/sge/RunSolverSource/. An earlier version of this file
    // wrote the RSS line with a colon because that is what the parser matched;
    // runsolver actually emits "=", so the parser could never fire and this test
    // suite certified the bug instead of catching it.
    //
    // If you add a fixture here, grep the literal out of RunSolverSource first.
    // ---------------------------------------------------------------------

    /** Watcher.hh:325 — {@code cout << "Child status: " << WEXITSTATUS(...)}. */
    private static final String WATCHER_CHILD_STATUS_0 = "Child status: 0\n";

    /** Watcher.hh:396 — {@code cout << "maximum resident set size= " << r.ru_maxrss}. */
    private static final String WATCHER_MAX_RSS_1024 =
        "maximum resident set size= 1024\n";

    /**
     * The regression test for the fixture bug itself. RSS was never asserted before, so
     * the mismatched separator went unnoticed even though a test read the line.
     */
    @Test
    public void maxResidentSetSizeIsParsedFromRealRunsolverOutput() throws Exception {
        Object stats = parseRunsolverOutput(
            outputDirWith(null, WATCHER_CHILD_STATUS_0 + WATCHER_MAX_RSS_1024)
        );

        java.lang.reflect.Field f =
            stats.getClass().getDeclaredField("maxResidentSetSize");
        f.setAccessible(true);
        assertEquals(
            "runsolver writes 'maximum resident set size= N' with an equals sign"
                + " (RunSolverSource/Watcher.hh:396); a parser expecting ':' silently"
                + " records 0 for every run",
            1024L,
            f.getLong(stats)
        );
    }

    /** Watcher.hh:726. Unreachable without -R today, but must not read as a clean run. */
    @Test
    public void maximumMemoryExceededIsTreatedAsAMemoryLimit() throws Exception {
        Object stats = parseRunsolverOutput(
            outputDirWith(
                null,
                "Maximum memory exceeded: sending SIGTERM then SIGKILL\n"
            )
        );

        assertTrue(
            "runsolver's -R memory kill (Watcher.hh:726) must not be recorded as a"
                + " completed run",
            flag(stats, "memoryExceeded")
        );
    }

    // ---------------------------------------------------------------------
    // M1 -- a run we learned nothing about must not be written as zeros.
    //
    // Every RunsolverStats field starts at 0, so an unparseable run is
    // indistinguishable from a run that took no time. Writing it recorded
    // wallclock=0, cpu=0, max_vmem=0 over real measurements.
    // ---------------------------------------------------------------------

    /** Watcher.hh:454/457 — {@code var << "WCTIME=" ...}, {@code "CPUTIME=" ...}. */
    private static final String VAR_OUT_REAL_RUN = "WCTIME=12.34\nCPUTIME=11.5\n";

    private boolean hasAnyMeasurement(Object stats) throws Exception {
        Method m = ContainerJobMonitor.class.getDeclaredMethod(
            "hasAnyMeasurement", stats.getClass());
        m.setAccessible(true);
        return (Boolean) m.invoke(null, stats);
    }

    @Test
    public void anOutputDirectoryWithNothingInItIsNotAMeasurement() throws Exception {
        java.nio.file.Path empty = java.nio.file.Files.createTempDirectory("cjm-empty");
        empty.toFile().deleteOnExit();

        assertFalse(
            "an unparseable run must not look like a measurement, or its zeros get"
                + " written over the real recorded values",
            hasAnyMeasurement(parseRunsolverOutput(empty))
        );
    }

    @Test
    public void anUnparseableStatsJsonIsNotAMeasurement() throws Exception {
        Object stats = parseRunsolverOutput(outputDirWith("{ this is not json", null));

        assertFalse(
            "a corrupt stats.json yields all-zero fields; that must not be persisted",
            hasAnyMeasurement(stats)
        );
    }

    /**
     * Positive control. Without it the two tests above would pass against a
     * hasAnyMeasurement that always returned false, which would silently stop
     * recording every run -- trading a corrupting write for a total loss.
     */
    @Test
    public void aRealRunIsAMeasurement() throws Exception {
        assertTrue(
            "a run with timings in var.out must still be persisted",
            hasAnyMeasurement(parseRunsolverOutputWithVar(VAR_OUT_REAL_RUN))
        );
    }

    /** Writes only a var.out, the file runsolver always produces. */
    private Object parseRunsolverOutputWithVar(String varOut) throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("cjm-var");
        dir.toFile().deleteOnExit();
        java.nio.file.Files.writeString(dir.resolve("var.out"), varOut);
        return parseRunsolverOutput(dir);
    }
}
