package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.starexec.backend.LocalJobMonitor;

/**
 * Tests for the ways LocalJobMonitor could strand or misreport a job pair.
 *
 * <p>The first group is about a transient condition being treated as permanent:
 * status.json is written in place, so a poll can read it mid-write, and that must not be
 * recorded as a failed run.
 *
 * <p>The second group is about telling one run of a pair from the next. A rerun arriving
 * while a poll is in flight used to let the poll write its stale conclusion over the new
 * run, leaving it untracked and flagged as processed -- stuck forever. The generation
 * token is what makes that distinguishable, so these tests assert on it directly.
 *
 * <p>Note that {@code aSupersededPollCannotRetireTheRerun} and
 * {@code anUncontestedPollRetiresThePair} are a matched pair: the first alone would pass
 * against a {@code retire} that never removed anything.
 */
public class LocalJobMonitorTests {

    private LocalJobMonitor monitor;

    @Before
    public void setUp() {
        monitor = new LocalJobMonitor();
    }

    /**
     * The constructor allocates a scheduler thread whether or not the monitor is ever
     * started, so a test that just drops the instance leaks one per test method.
     */
    @After
    public void tearDown() {
        if (monitor != null) {
            monitor.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, Object> pairs() throws Exception {
        Field f = LocalJobMonitor.class.getDeclaredField("pairs");
        f.setAccessible(true);
        return (Map<Integer, Object>) f.get(monitor);
    }

    /** PairExecutionState is private, so reach its fields reflectively. */
    private Object field(Object state, String name) throws Exception {
        Field f = state.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(state);
    }

    private Object stateFor(int pairId) throws Exception {
        return pairs().get(pairId);
    }

    private long generationOf(int pairId) throws Exception {
        Object state = stateFor(pairId);
        assertNotNull("no tracking entry for pairId=" + pairId, state);
        return (Long) field(state, "generation");
    }

    private String logDirOf(int pairId) throws Exception {
        Object state = stateFor(pairId);
        assertNotNull("no tracking entry for pairId=" + pairId, state);
        return (String) field(state, "logDir");
    }

    /** Looks a private method up by name so the test need not name the private type. */
    private Method declared(String name) {
        for (Method m : LocalJobMonitor.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new AssertionError("no such method: " + name);
    }

    private boolean retire(int pairId, Object state) throws Exception {
        return (Boolean) declared("retire").invoke(monitor, pairId, state);
    }

    private boolean isCurrent(int pairId, Object state) throws Exception {
        return (Boolean) declared("isCurrent").invoke(monitor, pairId, state);
    }

    private int recordParseFailure(int pairId, Object state) throws Exception {
        return (Integer) declared("recordParseFailure").invoke(monitor, pairId, state);
    }

    private Object readStatusFile(Path dir, int pairId) throws Exception {
        Method m = LocalJobMonitor.class.getDeclaredMethod(
            "readStatusFile", Path.class, int.class);
        m.setAccessible(true);
        return m.invoke(monitor, dir, pairId);
    }

    private Path dirContaining(String statusJson) throws Exception {
        Path dir = Files.createTempDirectory("ljm-test");
        dir.toFile().deleteOnExit();
        Files.writeString(dir.resolve("status.json"), statusJson);
        return dir;
    }

    // ------------------------------------------------------------------
    // status.json read mid-write
    // ------------------------------------------------------------------

    /**
     * A truncated file must report "not readable yet", not a status. Returning the
     * ERROR_RUNSCRIPT sentinel here -- which is what catching the parse exception and
     * carrying on would produce -- permanently records a healthy run as failed.
     */
    @Test
    public void truncatedStatusFileIsNotReadableRatherThanAnError() throws Exception {
        assertNull(
            "a half-written status.json must not resolve to a status",
            readStatusFile(dirContaining("{\"status\": 7, \"stageNum"), 1)
        );
    }

    @Test
    public void statusFileWithoutTheStatusFieldIsNotReadable() throws Exception {
        assertNull(
            "a status.json lacking its status field must not resolve to a status",
            readStatusFile(dirContaining("{\"stageNumber\": 1}"), 2)
        );
    }

    @Test
    public void nonObjectStatusFileIsNotReadable() throws Exception {
        assertNull(
            "content that is not a JSON object must not resolve to a status",
            readStatusFile(dirContaining("not json at all"), 3)
        );
    }

    @Test
    public void wellFormedStatusFileStillParses() throws Exception {
        // The inverse error matters as much: this must not start returning null for
        // files that are perfectly good.
        Object ss = readStatusFile(dirContaining("{\"status\": 7, \"stageNumber\": 2}"), 4);
        assertNotNull("a complete status.json must still parse", ss);

        Field stage = ss.getClass().getDeclaredField("stageNumber");
        stage.setAccessible(true);
        assertEquals(2, stage.getInt(ss));
    }

    // ------------------------------------------------------------------
    // rerun clearing
    // ------------------------------------------------------------------

    @Test
    public void clearPairTrackingRemovesTheWholeEntry() throws Exception {
        monitor.registerJob("/tmp/some/logdir", 98);
        assertNotNull(stateFor(98));

        monitor.clearPairTracking(98);

        assertFalse(
            "one removal must clear the directory, the processed status and the"
                + " parse-failure count together -- they used to be three facts that"
                + " could be left half-cleared",
            pairs().containsKey(98)
        );
    }

    /**
     * The old clearPairTracking removed a pair's processed marker only when it also
     * found a directory entry, so a pair without one stayed marked processed and its
     * rerun was skipped forever. There is no longer a marker to leave behind, but
     * calling it for an unknown pair must still be harmless.
     */
    @Test
    public void clearPairTrackingOnAnUnknownPairIsHarmless() throws Exception {
        monitor.clearPairTracking(99);
        assertFalse(pairs().containsKey(99));
    }

    // ------------------------------------------------------------------
    // generation guard: run N must not clobber run N+1
    // ------------------------------------------------------------------

    @Test
    public void everyRegistrationGetsAFreshGeneration() throws Exception {
        monitor.registerJob("/tmp/run-1", 42);
        long first = generationOf(42);

        monitor.registerJob("/tmp/run-2", 42);
        long second = generationOf(42);

        assertTrue(
            "a rerun must be distinguishable from the run it replaces; without a"
                + " strictly increasing token nothing tells run N from run N+1",
            second > first
        );
        assertEquals("/tmp/run-2", logDirOf(42));
    }

    /**
     * The stranding bug, encoded. A poll captures run N's state, a rerun lands, and the
     * poll then finishes and tries to retire the pair. Previously that removed the
     * directory entry and set the processed marker, leaving run N+1 both untracked and
     * flagged as done -- stuck forever.
     */
    @Test
    public void aSupersededPollCannotRetireTheRerun() throws Exception {
        monitor.registerJob("/tmp/run-n", 42);
        Object runN = stateFor(42);

        monitor.registerJob("/tmp/run-n-plus-1", 42);

        assertFalse(
            "a poll belonging to the superseded run must not retire the new run",
            retire(42, runN)
        );
        assertTrue("the rerun must still be tracked", pairs().containsKey(42));
        assertEquals("/tmp/run-n-plus-1", logDirOf(42));
    }

    /**
     * Positive control. Without this, the test above could pass simply because retire
     * never removes anything.
     */
    @Test
    public void anUncontestedPollRetiresThePair() throws Exception {
        monitor.registerJob("/tmp/run-n", 43);
        Object runN = stateFor(43);

        assertTrue("an uncontested terminal result must retire the pair", retire(43, runN));
        assertFalse(pairs().containsKey(43));
    }

    /**
     * Guards the database write. Recording run N's outcome after run N+1 has started is
     * a wrong recorded result, which is worse than a stranded pair.
     */
    @Test
    public void isCurrentGoesFalseOnceARerunLands() throws Exception {
        monitor.registerJob("/tmp/run-n", 44);
        Object runN = stateFor(44);
        assertTrue("the run's own generation must be current before any rerun",
            isCurrent(44, runN));

        monitor.registerJob("/tmp/run-n-plus-1", 44);

        assertFalse("run N must not be allowed to write after run N+1 has started",
            isCurrent(44, runN));
    }

    @Test
    public void parseFailuresBelongToOneRunAndResetOnRerun() throws Exception {
        monitor.registerJob("/tmp/run-n", 45);
        Object runN = stateFor(45);

        assertEquals(1, recordParseFailure(45, runN));
        assertEquals(2, recordParseFailure(45, runN));

        monitor.registerJob("/tmp/run-n-plus-1", 45);
        Object runNext = stateFor(45);

        assertEquals(
            "a rerun starts its own unreadable-file budget; a count carried over could"
                + " fail the new run on its first bad read",
            0,
            ((Integer) field(runNext, "parseFailures")).intValue()
        );
        assertEquals(
            "a failure reported by the superseded run must not be counted against"
                + " the new one",
            -1,
            recordParseFailure(45, runN)
        );
    }
}
