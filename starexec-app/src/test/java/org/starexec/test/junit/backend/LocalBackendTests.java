package org.starexec.test.junit.backend;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.starexec.backend.Backend;
import org.starexec.backend.LocalBackend;
import org.starexec.constants.R;
import org.testng.Assert;

/**
 * Unit tests for LocalBackend job execution.
 *
 * <p>These tests verify the LocalBackend's ability to submit, track, and kill jobs.
 * Uses Awaitility for deterministic async assertions instead of Thread.sleep.
 *
 * @see org.starexec.backend.LocalBackend
 */
public class LocalBackendTests {

    private Backend backend = null;
    int existingJobId = 0;
    Path tempDir;

    // Maximum time to wait for async operations
    private static final int MAX_WAIT_SECONDS = 5;

    // Poll interval for checking conditions
    private static final int POLL_INTERVAL_MS = 100;

    @Before
    public void initialize() throws IOException {
        tempDir = Files.createTempDirectory("localbackend_test");
        Path scriptPath = tempDir.resolve("fake_job.sh");
        Files.writeString(
            scriptPath,
            "#!/bin/bash\nsleep 1\necho 'fake job'\n"
        );
        scriptPath.toFile().setExecutable(true);

        Path workDir = tempDir.resolve("work");
        Files.createDirectory(workDir);
        Path logPath = tempDir.resolve("log.txt");

        backend = new LocalBackend();
        backend.initialize(""); // Initialize the backend
        existingJobId = backend.submitScript(-1, 
            scriptPath.toString(),
            workDir.toString(),
            logPath.toString()
        );
    }

    @After
    public void cleanup() {
        // Ensure backend is properly shut down to release resources
        if (backend != null) {
            backend.destroyIf();
        }
    }

    @Test
    public void testSubmitJob() throws IOException {
        Path newScript = tempDir.resolve("new_job.sh");
        Files.writeString(newScript, "#!/bin/bash\nsleep 1\necho 'new job'\n");
        newScript.toFile().setExecutable(true);
        Path newWork = tempDir.resolve("work2");
        Files.createDirectory(newWork);
        Path newLog = tempDir.resolve("log2.txt");

        int newId = backend.submitScript(-1, 
            newScript.toString(),
            newWork.toString(),
            newLog.toString()
        );
        String status = backend.getRunningJobsStatus();
        Assert.assertTrue(status.contains("new_job.sh"));
    }

    @Test
    public void killJobTest() {
        Assert.assertTrue(backend.killPair(existingJobId));

        // Use Awaitility to wait for job removal with deterministic polling
        // This replaces the flaky Thread.sleep(6000) pattern
        await()
            .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
            .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .until(() ->
                !backend.getRunningJobsStatus().contains("fake_job.sh")
            );

        String status = backend.getRunningJobsStatus();
        Assert.assertFalse(status.contains("fake_job.sh"));
    }

    @Test
    public void killNonExistantJobTest() {
        Assert.assertFalse(backend.killPair(-1)); // Should return false for non-existent
        String status = backend.getRunningJobsStatus();
        Assert.assertTrue(status.contains(existingJobId + "")); // Existing job still there
    }

    @Test
    public void killAllJobsTest() throws IOException {
        Path newScript = tempDir.resolve("another_job.sh");
        Files.writeString(
            newScript,
            "#!/bin/bash\nsleep 1\necho 'another job'\n"
        );
        newScript.toFile().setExecutable(true);
        Path newWork = tempDir.resolve("work3");
        Files.createDirectory(newWork);
        Path newLog = tempDir.resolve("log3.txt");

        backend.submitScript(-1, 
            newScript.toString(),
            newWork.toString(),
            newLog.toString()
        );
        Assert.assertTrue(backend.killAll());

        // Use Awaitility to wait for all jobs to be removed
        // This replaces the flaky Thread.sleep(6000) pattern
        await()
            .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
            .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .until(() -> {
                String currentStatus = backend.getRunningJobsStatus();
                return (
                    !currentStatus.contains("fake_job.sh") &&
                    !currentStatus.contains("another_job.sh")
                );
            });

        String status = backend.getRunningJobsStatus();
        Assert.assertFalse(status.contains("fake_job.sh"));
        Assert.assertFalse(status.contains("another_job.sh"));
    }

    @Test
    public void getRunningJobsStatusTest() {
        String status = backend.getRunningJobsStatus();
        Assert.assertTrue(status.contains("LocalBackend Status"));
        Assert.assertTrue(status.contains("fake_job.sh"));
    }

    @Test
    public void getQueuesTest() {
        String[] queues = backend.getQueues();
        Assert.assertEquals(queues.length, 1);
        Assert.assertEquals(queues[0], R.DEFAULT_QUEUE_NAME);
    }

    // ------------------------------------------------------------------
    // isJobReportedComplete decides whether a still-running process is a zombie, and a true
    // answer kills it on the caller's next statement. So the question it answers must be the
    // shared one -- StatusCode.isTerminalExecutionResult(), which the database enforces too --
    // and not a numeric >= 7, which is also true of the three states that mean work is still
    // owed.
    // ------------------------------------------------------------------

    /** The private nested LocalJob, built through its declared constructor. */
    private Object localJob(Path logPath) throws Exception {
        Class<?> type = Class.forName("org.starexec.backend.LocalBackend$LocalJob");
        var ctor = type.getDeclaredConstructor(
            int.class, int.class, String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(1, 1, "script.sh", tempDir.toString(), logPath.toString());
    }

    /** Writes status.json beside a log file and asks the predicate about it. */
    private boolean reportedComplete(String statusJson) throws Exception {
        Path dir = Files.createTempDirectory("zombie_test");
        dir.toFile().deleteOnExit();
        if (statusJson != null) {
            Files.writeString(dir.resolve("status.json"), statusJson);
        }
        var method = LocalBackend.class.getDeclaredMethod(
            "isJobReportedComplete", Class.forName("org.starexec.backend.LocalBackend$LocalJob"));
        method.setAccessible(true);
        return (Boolean) method.invoke(backend, localJob(dir.resolve("job.log")));
    }

    private static String status(int code) {
        return "{\"pairId\":1,\"status\":" + code + ",\"stageNumber\":1,\"timestamp\":1788988692}";
    }

    /** Everything the job script can actually emit, both directions. */
    @Test
    public void everyStatusTheJobScriptEmitsIsClassifiedCorrectly() throws Exception {
        // STATUS_RUNNING: the pair is alive and must not be killed.
        Assert.assertFalse(reportedComplete(status(4)), "STATUS_RUNNING is not a result");

        // The results a pair may be left holding.
        for (int terminal : new int[]{7, 11, 12, 13, 14, 15, 16, 17, 18, 24, 25, 26}) {
            Assert.assertTrue(reportedComplete(status(terminal)), terminal + " is a result");
        }
    }

    /**
     * The boundary. A numeric {@code >= 7} calls all three of these complete, and each means
     * work is still owed -- so the process would be killed while it was still doing that work.
     */
    @Test
    public void theStatesThatMeanWorkIsStillOwedAreNotResults() throws Exception {
        Assert.assertFalse(reportedComplete(status(19)), "STATUS_PROCESSING_RESULTS");
        Assert.assertFalse(reportedComplete(status(20)), "STATUS_PAUSED");
        Assert.assertFalse(reportedComplete(status(22)), "STATUS_PROCESSING");
    }

    /** An unrecognised code resolves to STATUS_UNKNOWN, which is not a result. */
    @Test
    public void anUnrecognisedStatusIsNotAResult() throws Exception {
        Assert.assertFalse(reportedComplete(status(99)), "99 is not a defined status");
        Assert.assertFalse(reportedComplete(status(27)), "27 is past the highest defined status");
    }

    /**
     * status.json is written in place, so a read can land mid-write. A partial document must
     * read as "not finished yet" rather than matching whatever prefix is present.
     */
    @Test
    public void aPartiallyWrittenStatusFileIsNotAResult() throws Exception {
        Assert.assertFalse(reportedComplete("{\"pairId\":1,\"stat"), "truncated before the field");
        Assert.assertFalse(reportedComplete("{\"pairId\":1,\"status\":1"), "truncated mid-number");
        Assert.assertFalse(reportedComplete("{ this is not json"), "malformed");
        Assert.assertFalse(reportedComplete("{\"pairId\":1}"), "no status field");
        Assert.assertFalse(reportedComplete(null), "no status.json at all");
    }
}
