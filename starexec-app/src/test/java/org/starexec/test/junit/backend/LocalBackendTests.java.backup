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
        existingJobId = backend.submitScript(
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

        int newId = backend.submitScript(
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

        backend.submitScript(
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
}
