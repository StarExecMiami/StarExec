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
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.starexec.backend.LocalJobMonitor;

/**
 * Tests for the two ways LocalJobMonitor could strand a job pair.
 *
 * <p>Both are about a transient condition being treated as permanent. status.json is
 * written in place, so a poll can read it mid-write; that must not be recorded as a
 * failed run. And a rerun must be able to clear a pair's processed marker even when the
 * monitor no longer holds a directory entry for it.
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
    private Set<Integer> processedPairIds() throws Exception {
        Field f = LocalJobMonitor.class.getDeclaredField("processedPairIds");
        f.setAccessible(true);
        return (Set<Integer>) f.get(monitor);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> trackedPairs() throws Exception {
        Field f = LocalJobMonitor.class.getDeclaredField("trackedPairs");
        f.setAccessible(true);
        return (Map<String, Integer>) f.get(monitor);
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

    /**
     * clearPairTracking used to remove the processed marker only when it also found a
     * trackedPairs entry. A pair without one stayed marked processed and its rerun was
     * skipped forever.
     */
    @Test
    public void clearPairTrackingClearsProcessedMarkerWithoutADirectoryEntry()
        throws Exception {
        processedPairIds().add(99);
        assertTrue(trackedPairs().isEmpty());

        monitor.clearPairTracking(99);

        assertFalse(
            "the processed marker must be cleared even with no trackedPairs entry,"
                + " otherwise the rerun is skipped forever",
            processedPairIds().contains(99)
        );
    }

    @Test
    public void clearPairTrackingStillClearsBothWhenADirectoryEntryExists()
        throws Exception {
        trackedPairs().put("/tmp/some/logdir", 98);
        processedPairIds().add(98);

        monitor.clearPairTracking(98);

        assertFalse(processedPairIds().contains(98));
        assertFalse(trackedPairs().containsValue(98));
    }
}
