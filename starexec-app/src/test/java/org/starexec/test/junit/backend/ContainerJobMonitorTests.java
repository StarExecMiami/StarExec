package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.starexec.backend.AdaptivePollInterval;
import org.starexec.backend.ContainerJobMonitor;
import org.starexec.backend.PodmanBackend;
import org.starexec.backend.exception.BackendTransientException;
import org.starexec.data.database.JobPairs;
import org.starexec.data.to.Status.StatusCode;

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

    /** Writes only a var.out, the file runsolver always produces. */
    private Object parseRunsolverOutputWithVar(String varOut) throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("cjm-var");
        dir.toFile().deleteOnExit();
        java.nio.file.Files.writeString(dir.resolve("var.out"), varOut);
        return parseRunsolverOutput(dir);
    }

    // ---------------------------------------------------------------------
    // M4 -- runsolver's own TIMEOUT=/MEMOUT= verdicts.
    //
    // Transcribed from RunSolverSource/Watcher.hh:471-475, which writes them
    // with boolalpha, so the values are the lowercase words true/false. Note
    // each is preceded by an explanatory comment line ("# TIMEOUT: did the
    // solver exceed the time limit?") that a looser match would hit.
    // ---------------------------------------------------------------------

    private static final String VAR_OUT_TIMED_OUT =
        "# WCTIME: wall clock time in seconds\n"
            + "WCTIME=600.1\n"
            + "# CPUTIME: CPU time in seconds (USERTIME+SYSTEMTIME)\n"
            + "CPUTIME=599.8\n"
            + "# TIMEOUT: did the solver exceed the time limit?\n"
            + "TIMEOUT=true\n"
            + "# MEMOUT: did the solver exceed the memory limit?\n"
            + "MEMOUT=false\n";

    private static final String VAR_OUT_CLEAN =
        "WCTIME=1.5\nCPUTIME=1.4\nTIMEOUT=false\nMEMOUT=false\n";

    private StatusCode determineStatus(Object stats, java.nio.file.Path dir)
        throws Exception {
        Method m = ContainerJobMonitor.class.getDeclaredMethod(
            "determineStatus", stats.getClass(), java.nio.file.Path.class);
        m.setAccessible(true);
        return (StatusCode) m.invoke(monitor, stats, dir);
    }

    @Test
    public void timeoutFlagIsParsedFromVarOut() throws Exception {
        Object stats = parseRunsolverOutputWithVar(VAR_OUT_TIMED_OUT);

        assertTrue("TIMEOUT=true must be read", flag(stats, "timeout"));
        assertFalse("MEMOUT=false must be read as false", flag(stats, "memout"));
    }

    @Test
    public void theCommentLineIsNotMistakenForTheValue() throws Exception {
        // "# TIMEOUT: did the solver exceed the time limit?" precedes TIMEOUT=false here.
        // A match on the bare word rather than the "TIMEOUT=" prefix would misread it.
        Object stats = parseRunsolverOutputWithVar(VAR_OUT_CLEAN);
        assertFalse(flag(stats, "timeout"));
        assertFalse(flag(stats, "memout"));
    }

    /**
     * The end-to-end regression. A SIGKILLed solver leaves no "Child status:" line
     * (Watcher.hh:326-331), so exitCode stays 0; if the prose sentence is absent too, the
     * old determineStatus returned STATUS_COMPLETE for a run that timed out.
     */
    @Test
    public void aTimedOutRunIsNotRecordedAsComplete() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("cjm-m4");
        dir.toFile().deleteOnExit();
        java.nio.file.Files.writeString(dir.resolve("var.out"), VAR_OUT_TIMED_OUT);

        Object stats = parseRunsolverOutput(dir);

        assertEquals(
            "runsolver reported TIMEOUT=true, so this run must not be recorded as a"
                + " clean completion just because no prose line matched",
            StatusCode.EXCEED_CPU,
            determineStatus(stats, dir)
        );
    }

    @Test
    public void aCleanRunIsStillComplete() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("cjm-m4-ok");
        dir.toFile().deleteOnExit();
        java.nio.file.Files.writeString(dir.resolve("var.out"), VAR_OUT_CLEAN);

        Object stats = parseRunsolverOutput(dir);

        assertEquals(
            "the inverse error matters as much: a clean run must not be reported as"
                + " having breached a limit",
            StatusCode.STATUS_COMPLETE,
            determineStatus(stats, dir)
        );
    }

    // ------------------------------------ per-stage snapshots, refused through the poll loop
    //
    // #200. What a refusal does is decided by the catch it lands in, so these drive the real
    // checkCompletedJobs rather than the reader. Podman read snapshots through a private copy
    // that threw plain Exceptions; the poll loop's catch-all recorded ERROR_RUNSCRIPT against an
    // invented stage 1 and removed the container. Local and Kubernetes, reading the same bytes
    // through StageStatusSnapshots, hold the pair and write nothing. Each case here requires the
    // Local outcome: the slot handed back once, the container kept and held on the next poll,
    // no status written, and the output still on disk.

    private static final int HELD_PAIR = 4242;

    @Test
    public void aMalformedSnapshotIsHeldWithItsContainer() throws Exception {
        java.nio.file.Path out = finishedAtStageTwo("malformed");
        writeSnapshotFile(out, "1.json", "{not json");
        assertHeld(out, "malformed");
    }

    @Test
    public void aSnapshotMissingAFieldIsHeldWithItsContainer() throws Exception {
        java.nio.file.Path out = finishedAtStageTwo("missing");
        writeSnapshotFile(out, "1.json", "{\"pairId\":" + HELD_PAIR + ",\"stageNumber\":1}\n");
        assertHeld(out, "missing");
    }

    @Test
    public void aSnapshotClaimingAnotherPairIsHeldWithItsContainer() throws Exception {
        java.nio.file.Path out = finishedAtStageTwo("foreign");
        writeSnapshotFile(out, "1.json", snapshotRecord(HELD_PAIR + 1, 1, complete()));
        assertHeld(out, "foreign");
    }

    @Test
    public void aSnapshotNamedForAnotherStageIsHeldWithItsContainer() throws Exception {
        java.nio.file.Path out = finishedAtStageTwo("mismatch");
        writeSnapshotFile(out, "1.json", snapshotRecord(HELD_PAIR, 2, complete()));
        assertHeld(out, "mismatch");
    }

    @Test
    public void anEarlierStageStillRunningIsHeldWithItsContainer() throws Exception {
        java.nio.file.Path out = finishedAtStageTwo("running");
        writeSnapshotFile(out, "1.json",
            snapshotRecord(HELD_PAIR, 1, StatusCode.STATUS_RUNNING.getVal()));
        assertHeld(out, "running");
    }

    @Test
    public void anEarlierStageClaimingProcessingIsHeldWithItsContainer() throws Exception {
        java.nio.file.Path out = finishedAtStageTwo("processing");
        writeSnapshotFile(out, "1.json",
            snapshotRecord(HELD_PAIR, 1, StatusCode.STATUS_PROCESSING.getVal()));
        assertHeld(out, "processing");
    }

    /** More entries than any real pair has stages, whatever their names. */
    @Test
    public void aSnapshotDirectoryOverTheEntryCapIsHeldWithItsContainer() throws Exception {
        java.nio.file.Path out = finishedAtStageTwo("crowded");
        writeSnapshotFile(out, "1.json", snapshotRecord(HELD_PAIR, 1, complete()));
        for (int i = 0; i < 1024; i++) {
            writeSnapshotFile(out, "extra-" + i + ".tmp", "");
        }
        assertHeld(out, "crowded");
    }

    /**
     * A directory that cannot be read is the filesystem, not the contents. It is retried with
     * backoff, as Local and Kubernetes classify an IOException, rather than recorded as a
     * solver failure or held as a bad artifact.
     */
    @Test
    public void anUnreadableSnapshotDirectoryIsRetriedWithItsContainer() throws Exception {
        java.nio.file.Path out = finishedAtStageTwo("unreadable");
        writeSnapshotFile(out, "1.json", snapshotRecord(HELD_PAIR, 1, complete()));
        java.io.File dir = out.resolve("stage-status").toFile();
        org.junit.Assume.assumeTrue("needs a filesystem that honours permissions",
            dir.setReadable(false, false) && !dir.canRead());
        String container = "container-unreadable";
        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            pollTwice(out, container);

            verify(backend, never()).removeCompletedContainer(container);
            verify(backend, times(1)).releaseSlotForCompletedContainer(container);
            jobPairs.verifyNoInteractions();
        } finally {
            dir.setReadable(true, false);
        }
        assertFalse("an unreadable directory is not a bad artifact",
            quarantine().contains(container));
        assertTrue("it is due another attempt", attempts().containsKey(container));
        assertTrue(java.nio.file.Files.exists(out.resolve("stage-status/1.json")));
    }

    private void assertHeld(java.nio.file.Path out, String label) throws Exception {
        String container = "container-" + label;
        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            pollTwice(out, container);

            verify(backend, never()).removeCompletedContainer(container);
            verify(backend, times(1)).releaseSlotForCompletedContainer(container);
            jobPairs.verifyNoInteractions();
        }
        assertTrue("held on the first refusal, and skipped by the next poll",
            quarantine().contains(container));
        assertTrue("the output survives the refusal",
            java.nio.file.Files.exists(out.resolve("status.json")));
    }

    /** Two polls over the same exited container, as the scheduler would make them. */
    private void pollTwice(java.nio.file.Path out, String container) throws Exception {
        PodmanBackend.CompletedContainerInfo info =
            new PodmanBackend.CompletedContainerInfo(container, HELD_PAIR, out.toString(), 0);
        when(backend.getCompletedContainers())
            .thenReturn(java.util.Collections.singletonList(info));
        invokeCheckCompletedJobs();
        invokeCheckCompletedJobs();
    }

    /** A labelled pair whose status.json says stage 2 finished cleanly. */
    private static java.nio.file.Path finishedAtStageTwo(String label) throws IOException {
        java.nio.file.Path out = java.nio.file.Files.createTempDirectory("cjm-held-" + label);
        out.toFile().deleteOnExit();
        java.nio.file.Files.writeString(out.resolve("status.json"),
            snapshotRecord(HELD_PAIR, 2, complete()));
        java.nio.file.Files.writeString(out.resolve("var.out"),
            "WCTIME=0.10\nCPUTIME=0.09\nTIMEOUT=false\nMEMOUT=false\n");
        java.nio.file.Files.writeString(out.resolve("watcher.out"), "Child status: 0\n");
        return out;
    }

    private static void writeSnapshotFile(java.nio.file.Path out, String name, String content)
        throws IOException {
        java.nio.file.Path dir = java.nio.file.Files.createDirectories(out.resolve("stage-status"));
        java.nio.file.Files.writeString(dir.resolve(name), content);
    }

    private static String snapshotRecord(int pairId, int stage, int status) {
        return "{\"pairId\":" + pairId + ",\"status\":" + status + ",\"stageNumber\":" + stage
            + ",\"timestamp\":1788818872}\n";
    }

    private static int complete() {
        return StatusCode.STATUS_COMPLETE.getVal();
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<String> quarantine() throws Exception {
        Field f = ContainerJobMonitor.class.getDeclaredField("ingestionQuarantine");
        f.setAccessible(true);
        return (java.util.Set<String>) f.get(monitor);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, ?> attempts() throws Exception {
        Field f = ContainerJobMonitor.class.getDeclaredField("ingestionAttempts");
        f.setAccessible(true);
        return (java.util.Map<String, ?>) f.get(monitor);
    }
}
