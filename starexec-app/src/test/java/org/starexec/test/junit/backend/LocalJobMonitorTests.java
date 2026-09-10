package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.backend.StageStatusSnapshots;
import org.starexec.data.to.Status.StatusCode;

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
    // parseRunSolverStats reads all three sources
    //
    // It used to return as soon as stats.json parsed, leaving var.out and
    // watcher.out unread -- the same defect fixed in ContainerJobMonitor by
    // 316668fd2. The sharpest consequence was silent: extractDouble returns 0.0
    // on no match, so a stats.json that parsed but lacked a field produced a
    // zero while runsolver's own var.out sat unread beside it.
    //
    // Fixtures below are transcribed from the producers:
    //   RunSolverSource/Watcher.hh:454 "WCTIME="   :457 "CPUTIME="
    //   RunSolverSource/Watcher.hh:396 "maximum resident set size= "
    // ------------------------------------------------------------------

    private Object parseRunSolverStats(Path dir) throws Exception {
        Method m = LocalJobMonitor.class.getDeclaredMethod(
            "parseRunSolverStats", Path.class);
        m.setAccessible(true);
        return m.invoke(monitor, dir);
    }

    private double statDouble(Object stats, String name) throws Exception {
        Field f = stats.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getDouble(stats);
    }

    private long statLong(Object stats, String name) throws Exception {
        Field f = stats.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(stats);
    }

    private String statString(Object stats, String name) throws Exception {
        Field f = stats.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(stats);
    }

    private Path outputDir(String varOut, String watcherOut, String statsJson)
        throws Exception {
        Path dir = Files.createTempDirectory("ljm-stats");
        dir.toFile().deleteOnExit();
        if (varOut != null) Files.writeString(dir.resolve("var.out"), varOut);
        if (watcherOut != null) Files.writeString(dir.resolve("watcher.out"), watcherOut);
        if (statsJson != null) Files.writeString(dir.resolve("stats.json"), statsJson);
        return dir;
    }

    /** The regression: a stats.json missing a field must not zero what var.out had. */
    @Test
    public void aPartialStatsJsonDoesNotZeroValuesReadFromVarOut() throws Exception {
        Object stats = parseRunSolverStats(outputDir(
            "WCTIME=42.5\nCPUTIME=41.25\n",
            null,
            "{\"wallclockTime\": 42.5}"   // cpuTime absent
        ));

        assertEquals(42.5, statDouble(stats, "wallclockTime"), 0.0001);
        assertEquals(
            "cpuTime is absent from stats.json, so var.out's value must survive rather"
                + " than being overwritten with 0",
            41.25,
            statDouble(stats, "cpuTime"),
            0.0001
        );
    }

    @Test
    public void varOutIsReadEvenWhenStatsJsonExists() throws Exception {
        Object stats = parseRunSolverStats(outputDir(
            "WCTIME=10.0\nCPUTIME=9.0\nUSERTIME=8.0\nSYSTEMTIME=1.0\nMAXVM=2048\n",
            null,
            "{\"wallclockTime\": 10.0}"
        ));

        assertEquals(8.0, statDouble(stats, "userTime"), 0.0001);
        assertEquals(2048.0, statDouble(stats, "maxVirtualMemory"), 0.0001);
    }

    @Test
    public void watcherOutRssIsReadWithTheProducersEqualsSeparator() throws Exception {
        Object stats = parseRunSolverStats(outputDir(
            null,
            "maximum resident set size= 4096\n",
            null
        ));

        assertEquals(
            "Watcher.hh:396 writes this line with '='; a parser expecting ':' records 0",
            4096L,
            statLong(stats, "maxResidentSetSize")
        );
    }

    /** stats.json still wins for the fields it does carry. */
    @Test
    public void statsJsonRemainsAuthoritativeWhereItHasAValue() throws Exception {
        Object stats = parseRunSolverStats(outputDir(
            "WCTIME=1.0\nCPUTIME=1.0\n",
            null,
            "{\"wallclockTime\": 99.0, \"cpuTime\": 98.0}"
        ));

        assertEquals(99.0, statDouble(stats, "wallclockTime"), 0.0001);
        assertEquals(98.0, statDouble(stats, "cpuTime"), 0.0001);
    }

    // ------------------------------------------------------------------
    // A malformed number in stats.json must not escape parseRunSolverStats
    //
    // extractDoubleOr matches [0-9.]+ and extractLongOr/extractIntOr match
    // [0-9]+, so "1.2.3", ".", "1..2" and digit strings past Integer/Long range
    // all match the regex and then throw out of the parse. The enclosing catch
    // took IOException only, so the NumberFormatException left the monitor.
    //
    // Both tests below turn on one witness: hostname is applied last, at the very
    // end of the stats.json block. A malformed value that merely failed to match
    // would leave the block running and hostname set; hostname being null instead
    // proves the parse was actually reached and actually threw. That is what makes
    // these non-vacuous, so the control assertion establishing that hostname is
    // set at all comes first.
    // ------------------------------------------------------------------

    /**
     * The sharpest form: the malformed value is followed by a good one, and the good
     * one must NOT be applied. If the regex had simply failed to match, userTime
     * would carry stats.json's 77.0; it carrying var.out's 8.0 is the proof that
     * Double.parseDouble was reached and threw.
     */
    @Test
    public void aMalformedNumberInStatsJsonDegradesToVarOutInsteadOfEscaping()
        throws Exception {
        Object stats = parseRunSolverStats(outputDir(
            "WCTIME=42.5\nCPUTIME=41.25\nUSERTIME=8.0\n",
            null,
            // wallclockTime is applied first and is good; cpuTime throws; userTime
            // and hostname sit after it in the source and so are never reached.
            "{\"wallclockTime\": 43.75, \"cpuTime\": 1.2.3,"
                + " \"userTime\": 77.0, \"hostname\": \"node7\"}"
        ));

        assertEquals(
            "the fields applied before the malformed one must still come from"
                + " stats.json -- the block does run, it just cannot finish",
            43.75,
            statDouble(stats, "wallclockTime"),
            0.0001
        );
        assertEquals(
            "cpuTime must fall back to var.out's measurement, not to 0",
            41.25,
            statDouble(stats, "cpuTime"),
            0.0001
        );
        assertEquals(
            "userTime proves the parse threw rather than failing to match: a"
                + " non-matching value would have left the block running and"
                + " applied stats.json's 77.0 here",
            8.0,
            statDouble(stats, "userTime"),
            0.0001
        );
        assertNull(
            "hostname is applied last, so it is only null if the block aborted",
            statString(stats, "hostname")
        );
    }

    /**
     * One case per flagged conversion: extractDoubleOr, extractLongOr, extractIntOr.
     * They share a single enclosing catch, so this asserts the same invariants across
     * all of them rather than repeating three near-identical helper tests.
     */
    @Test
    public void everyMalformedNumericShapeInStatsJsonStaysContained() throws Exception {
        String varOut = "WCTIME=42.5\nCPUTIME=41.25\n";
        String watcherOut = "maximum resident set size= 4096\n";

        // Control. Without this, the assertNull below would pass against a build
        // that never parsed hostname at all.
        Object good = parseRunSolverStats(outputDir(
            varOut, watcherOut, "{\"cpuTime\": 9.5, \"hostname\": \"node7\"}"));
        assertEquals("node7", statString(good, "hostname"));
        assertEquals(9.5, statDouble(good, "cpuTime"), 0.0001);

        String[][] malformed = {
            {"a bare dot reaches Double.parseDouble",
                "{\"cpuTime\": ., \"hostname\": \"node7\"}"},
            {"a doubled dot reaches Double.parseDouble",
                "{\"cpuTime\": 1..2, \"hostname\": \"node7\"}"},
            {"a value past Long range reaches Long.parseLong",
                "{\"maxResidentSetSize\": 99999999999999999999,"
                    + " \"hostname\": \"node7\"}"},
            {"a value past Integer range reaches Integer.parseInt",
                "{\"stageNumber\": 99999999999, \"hostname\": \"node7\"}"},
        };

        for (String[] c : malformed) {
            String why = c[0];
            // Escaping here surfaces as InvocationTargetException from the reflective
            // call, so simply completing the loop is part of the assertion.
            Object stats = parseRunSolverStats(outputDir(varOut, watcherOut, c[1]));

            assertNull(
                why + ": the block must abort, leaving hostname unapplied",
                statString(stats, "hostname")
            );
            assertEquals(
                why + ": cpuTime must keep var.out's measurement rather than 0",
                41.25,
                statDouble(stats, "cpuTime"),
                0.0001
            );
            assertEquals(
                why + ": maxResidentSetSize must keep watcher.out's measurement"
                    + " rather than 0",
                4096L,
                statLong(stats, "maxResidentSetSize")
            );
        }
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

    // ------------------------------------------------------------------
    // What a pair-level status is allowed to be.
    //
    // status.json is written by the job script into a directory the job can write. The protocol
    // has it carry STATUS_RUNNING while a stage is in flight and a terminal execution result
    // once one finishes -- which is exactly the job script's emission set. Anything else did not
    // come from the protocol.
    //
    // STATUS_PROCESSING(22) is the one with teeth: a pair left at 22 is selected by the periodic
    // post-processing task, which sets it to STATUS_COMPLETE. ContainerJobMonitor already
    // guarded its own pair-level write against this; this path did not.
    //
    // The guard runs before any database call, so a refusal is an InvalidSnapshotException and
    // anything that gets past it fails later for want of a database. That difference is what
    // these assert on -- no database is needed to tell "refused" from "accepted".
    // ------------------------------------------------------------------

    private void updateDatabase(int pairId, StatusCode status, int stageNumber) throws Throwable {
        for (Method m : LocalJobMonitor.class.getDeclaredMethods()) {
            if (m.getName().equals("updateDatabase")) {
                m.setAccessible(true);
                try {
                    m.invoke(monitor, pairId, status, stageNumber,
                             newRunSolverStats(), new java.util.Properties());
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
                return;
            }
        }
        throw new AssertionError("no such method: updateDatabase");
    }

    /** RunSolverStats is private; build one with all-zero fields. */
    private Object newRunSolverStats() throws Exception {
        Class<?> type = Class.forName("org.starexec.backend.LocalJobMonitor$RunSolverStats");
        var ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            if (types[i] == double.class) args[i] = 0.0d;
            else if (types[i] == long.class) args[i] = 0L;
            else if (types[i] == int.class) args[i] = 0;
            else args[i] = null;
        }
        return ctor.newInstance(args);
    }

    private boolean isRefused(StatusCode status) throws Throwable {
        try {
            updateDatabase(50, status, 1);
            return false;
        } catch (StageStatusSnapshots.InvalidSnapshotException refused) {
            assertTrue("the refusal must name the status: " + refused.getMessage(),
                    refused.getMessage().contains(status.toString()));
            return true;
        } catch (Throwable anythingElse) {
            // Got past the guard and failed further in, for want of a database.
            return false;
        }
    }

    /**
     * The whole enum, so a status added later cannot quietly become recordable. Enumerated
     * against the authoritative predicate rather than against a list written out here, which
     * would only prove this test and the guard were written by the same hand.
     */
    @Test
    public void onlyRunningOrATerminalResultCanBeRecordedAsAPairStatus() throws Throwable {
        for (StatusCode code : StatusCode.values()) {
            boolean allowed = code == StatusCode.STATUS_RUNNING || code.isTerminalExecutionResult();
            assertEquals(code + " (" + code.getVal() + ")", !allowed, isRefused(code));
        }
    }

    /** Named individually, so a regression says which state leaked rather than only that one did. */
    @Test
    public void theThreeStatesThatMeanWorkIsStillOwedAreRefusedAsAPairStatus() throws Throwable {
        assertTrue("19 STATUS_PROCESSING_RESULTS",
                isRefused(StatusCode.STATUS_PROCESSING_RESULTS));
        assertTrue("20 STATUS_PAUSED", isRefused(StatusCode.STATUS_PAUSED));
        assertTrue("22 STATUS_PROCESSING -- the one post-processing promotes to COMPLETE",
                isRefused(StatusCode.STATUS_PROCESSING));
    }

    /** The terminal error statuses 23-26 are results a pair may legitimately be left holding. */
    @Test
    public void theTerminalErrorStatusesAreStillRecordable() throws Throwable {
        for (StatusCode code : new StatusCode[]{
                StatusCode.STATUS_NOT_REACHED,              // 23
                StatusCode.ERROR_BENCH_DEPENDENCY_MISSING,  // 24
                StatusCode.ERROR_PRE_PROCESSOR,             // 25
                StatusCode.ERROR_POST_PROCESSOR}) {         // 26
            assertFalse(code + " is a result and must remain recordable", isRefused(code));
        }
    }

    /** And the progress status, which is how a pair is marked running at all. */
    @Test
    public void runningIsStillRecordableBecauseThatIsHowAPairIsMarkedRunning() throws Throwable {
        assertFalse(isRefused(StatusCode.STATUS_RUNNING));
    }

    /** A refusal must happen before anything is written, including stage history. */
    @Test
    public void aRefusedStatusReachesNoDatabaseCallAtAll() throws Throwable {
        try {
            updateDatabase(50, StatusCode.STATUS_PROCESSING, 1);
            fail("STATUS_PROCESSING must be refused");
        } catch (StageStatusSnapshots.InvalidSnapshotException expected) {
            for (StackTraceElement f : expected.getStackTrace()) {
                assertFalse("the refusal must precede any JobPairs call, but the stack shows "
                                + f, f.getClassName().contains("JobPairs"));
            }
        }
    }

    // ------------------------------------------------------------------
    // A stage that is still running must not strand the pair.
    //
    // status.json exists from the first moment of a run -- sendNode writes STATUS_RUNNING into
    // it -- so the poll loop calls into ingestion against a pair that has not finished. The
    // stage's own snapshot legitimately reads STATUS_RUNNING at that point. Validating it threw
    // InvalidSnapshotException, which IngestionOutcome classifies as BLOCKED, and BLOCKED is
    // permanent: the pair was never re-read, even though it went on to finish cleanly a second
    // later and its evidence became consistent.
    //
    // These assert on the throw rather than on a flag because the throw IS the mechanism: it is
    // the only thing that reaches recordIngestionFailure and sets ingestionBlocked.
    // ------------------------------------------------------------------

    private void ingestEarlierStageStatuses(
        int pairId, Object state, Path outputDir, int terminalStage) throws Exception {
        for (Method m : LocalJobMonitor.class.getDeclaredMethods()) {
            if (m.getName().equals("ingestEarlierStageStatuses")) {
                m.setAccessible(true);
                try {
                    m.invoke(monitor, pairId, state, outputDir, terminalStage);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw (Exception) e.getCause();
                }
                return;
            }
        }
        throw new AssertionError("no such method: ingestEarlierStageStatuses");
    }

    private Path dirWithStageSnapshot(int pairId, int stage, int status) throws Exception {
        Path dir = Files.createTempDirectory("ljm-stage");
        dir.toFile().deleteOnExit();
        Path snapshots = dir.resolve("stage-status");
        Files.createDirectories(snapshots);
        Files.writeString(
            snapshots.resolve(stage + ".json"),
            "{\"pairId\":" + pairId + ",\"status\":" + status
                + ",\"stageNumber\":" + stage + ",\"timestamp\":1788988692}\n");
        return dir;
    }

    @Test
    public void aStageStillRunningDoesNotBlockIngestion() throws Exception {
        // 4 is STATUS_RUNNING, which the stage's snapshot holds from the moment it starts.
        // status.json names that same stage: sendNode's sendStatus defaults to stage 0, but the
        // sendStageStatus on the next line rewrites status.json with the real stage number.
        Path dir = dirWithStageSnapshot(46, 1, 4);
        monitor.registerJob(dir.toString(), 46);

        // Single-stage pair, currently in stage 1. Nothing is behind it, and its own snapshot
        // must not be judged. Before the bound this threw, and the throw was permanent.
        ingestEarlierStageStatuses(46, stateFor(46), dir, 1);
    }

    /**
     * A stage number below 1 names no stage. It arrives here routinely -- every pair-level error
     * path takes {@code sendStatus}'s default of 0 -- and must not be read as "no stage is in
     * flight", which would skip the whole directory and let the caller record a result for a
     * pair whose history it had just declined to read.
     *
     * <p>The pre-bound behaviour is the conservative one, so 0 keeps it: validate everything, and
     * refuse a stage that is not holding a result.
     */
    @Test
    public void aStageNumberBelowOneIsNotTreatedAsABound() throws Exception {
        Path dir = dirWithStageSnapshot(49, 1, 4);
        monitor.registerJob(dir.toString(), 49);

        try {
            ingestEarlierStageStatuses(49, stateFor(49), dir, 0);
            fail("stageNumber 0 must not silently skip every snapshot");
        } catch (StageStatusSnapshots.InvalidSnapshotException expected) {
            assertTrue(
                "wrong refusal: " + expected.getMessage(),
                expected.getMessage().contains("carries status 4"));
        }
    }

    /**
     * The control. Once the pair has moved past a stage, that stage's status is final, and a
     * non-terminal one is still a refusal -- otherwise the fix would have opened the
     * status-laundering path the snapshot rules exist to close.
     */
    @Test
    public void aStageThePairMovedPastIsStillValidated() throws Exception {
        // 22 is STATUS_PROCESSING, the value the periodic post-processor promotes to COMPLETE.
        Path dir = dirWithStageSnapshot(48, 1, 22);
        monitor.registerJob(dir.toString(), 48);

        try {
            ingestEarlierStageStatuses(48, stateFor(48), dir, 2);
            fail("a non-terminal status on a stage the pair has passed must be refused");
        } catch (StageStatusSnapshots.InvalidSnapshotException expected) {
            assertTrue(
                "wrong refusal: " + expected.getMessage(),
                expected.getMessage().contains("carries status 22"));
        }
    }
}
