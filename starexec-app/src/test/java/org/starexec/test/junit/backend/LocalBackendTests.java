package org.starexec.test.junit.backend;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.Backend;
import org.starexec.backend.LocalBackend;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.backend.exception.SubmissionDeferredException;
import org.starexec.constants.R;
import org.starexec.data.database.JobPairs;
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
        return localJob(1, 1, Path.of("script.sh"), tempDir, logPath);
    }

    private Object localJob(
        int execId,
        int pairId,
        Path scriptPath,
        Path workDir,
        Path logPath
    ) throws Exception {
        Class<?> type = Class.forName("org.starexec.backend.LocalBackend$LocalJob");
        var ctor = type.getDeclaredConstructor(
            int.class, int.class, String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
            execId,
            pairId,
            scriptPath.toString(),
            workDir.toString(),
            logPath.toString()
        );
    }

    private void executeJob(Object job) throws Exception {
        Method method = LocalBackend.class.getDeclaredMethod(
            "executeJob", Class.forName("org.starexec.backend.LocalBackend$LocalJob")
        );
        method.setAccessible(true);
        method.invoke(backend, job);
    }

    private LocalJobMonitor jobMonitor() throws Exception {
        Field field = LocalBackend.class.getDeclaredField("jobMonitor");
        field.setAccessible(true);
        return (LocalJobMonitor) field.get(backend);
    }

    private void waitForFixtureJob() {
        await()
            .atMost(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
            .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .until(() -> !backend.getRunningJobsStatus().contains("fake_job.sh"));
    }

    private Path executableScript(String name, String body) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/bash\n" + body + "\n");
        script.toFile().setExecutable(true);
        return script;
    }

    private ThreadPoolExecutor replaceWithSingleWorkerExecutor(boolean callerRuns)
        throws Exception {
        Field executorField = LocalBackend.class.getDeclaredField("executorService");
        executorField.setAccessible(true);
        ThreadPoolExecutor configuredExecutor =
            (ThreadPoolExecutor) executorField.get(backend);
        configuredExecutor.shutdownNow();
        Assert.assertTrue(configuredExecutor.awaitTermination(2, TimeUnit.SECONDS));

        var rejectionHandler = callerRuns
            ? new ThreadPoolExecutor.CallerRunsPolicy()
            : configuredExecutor.getRejectedExecutionHandler();
        ThreadPoolExecutor replacement = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1),
            rejectionHandler
        );
        executorField.set(backend, replacement);
        return replacement;
    }

    private void occupyWorkerAndQueue(
        ThreadPoolExecutor executor,
        CountDownLatch releaseWorker
    ) throws InterruptedException {
        CountDownLatch workerStarted = new CountDownLatch(1);
        executor.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Assert.assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
        executor.execute(() -> { });
        Assert.assertEquals(executor.getQueue().size(), 1,
            "the single worker and bounded queue must both be occupied");
    }

    private void verifyNoScientificStatusWrites(MockedStatic<JobPairs> jobPairs) {
        jobPairs.verify(() -> JobPairs.setStatusForPairAndStages(
                Mockito.anyInt(), Mockito.anyInt()),
            Mockito.never());
        jobPairs.verify(() -> JobPairs.setPairStatusPrecise(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()),
            Mockito.never());
        jobPairs.verify(() -> JobPairs.setPairStatusPrecise(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyBoolean()),
            Mockito.never());
        jobPairs.verify(() -> JobPairs.setPairStatusPreciseResult(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyBoolean()),
            Mockito.never());
        jobPairs.verify(() -> JobPairs.setEarlierStageStatuses(
                Mockito.anyInt(), Mockito.anyMap()),
            Mockito.never());
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

    @Test
    public void zeroExitWithoutTerminalEvidenceDoesNotInventScientificFailure()
        throws Exception {
        waitForFixtureJob();
        Path output = Files.createDirectory(tempDir.resolve("zero-without-status"));
        Object job = localJob(
            101,
            4101,
            executableScript("exit-zero.sh", "exit 0"),
            tempDir,
            output.resolve("job.log")
        );

        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            executeJob(job);
            verifyNoScientificStatusWrites(jobPairs);
        }
        Assert.assertEquals(jobMonitor().getTrackedPairCount(), 1,
            "the unresolved pair must remain tracked for late evidence");
        Assert.assertFalse(Files.exists(output.resolve("status.json")));
    }

    @Test
    public void nonzeroProcessExitWithoutTerminalEvidenceDoesNotInventScientificFailure()
        throws Exception {
        waitForFixtureJob();
        Path output = Files.createDirectory(tempDir.resolve("nonzero-without-status"));
        Object job = localJob(
            102,
            4102,
            executableScript("exit-nine.sh", "exit 9"),
            tempDir,
            output.resolve("job.log")
        );

        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            executeJob(job);
            verifyNoScientificStatusWrites(jobPairs);
        }
        Assert.assertEquals(jobMonitor().getTrackedPairCount(), 1,
            "process lifecycle failure alone must leave the pair unresolved");
    }

    @Test
    public void unexpectedExecutionFailureDoesNotInventScientificFailure()
        throws Exception {
        waitForFixtureJob();
        Path output = Files.createDirectory(tempDir.resolve("unexpected-without-status"));
        Object job = localJob(
            103,
            4103,
            executableScript("not-started.sh", "exit 0"),
            tempDir,
            output.resolve("job.log")
        );

        Field cores = LocalBackend.class.getDeclaredField("availableCores");
        cores.setAccessible(true);
        cores.set(backend, new LinkedBlockingQueue<Integer>() {
            @Override
            public Integer poll(long timeout, TimeUnit unit) {
                throw new IllegalStateException("synthetic scheduler failure");
            }
        });

        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            executeJob(job);
            verifyNoScientificStatusWrites(jobPairs);
        }
        Assert.assertEquals(jobMonitor().getTrackedPairCount(), 1,
            "an implementation failure must leave the scientific result unresolved");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void cleanupFailureDoesNotInventResultOrLeakActiveExecution() throws Exception {
        waitForFixtureJob();
        Path outputEntry = tempDir.resolve("output-is-not-a-directory");
        Files.writeString(outputEntry, "not a directory");
        Object job = localJob(
            104,
            4104,
            executableScript("cleanup-never-starts.sh", "exit 0"),
            tempDir,
            outputEntry.resolve("job.log")
        );

        Field activeField = LocalBackend.class.getDeclaredField("activeJobs");
        activeField.setAccessible(true);
        Map active = (Map) activeField.get(backend);
        active.put(104, job);

        try (MockedStatic<JobPairs> jobPairs = Mockito.mockStatic(JobPairs.class)) {
            executeJob(job);
            verifyNoScientificStatusWrites(jobPairs);
        }

        Assert.assertFalse(active.containsKey(104),
            "a refused attempt must not remain forever in activeJobs");
        Field completedAt = job.getClass().getDeclaredField("completedAt");
        completedAt.setAccessible(true);
        Assert.assertTrue(completedAt.getLong(job) > 0,
            "a refused attempt must still record operational completion");
        Assert.assertEquals(jobMonitor().getTrackedPairCount(), 0,
            "cleanup failed before monitor registration");
    }

    @Test
    public void saturatedQueueDefersWithoutRunningSolverOrBlockingControls() throws Exception {
        waitForFixtureJob();

        CountDownLatch releaseWorker = new CountDownLatch(1);
        ThreadPoolExecutor saturatedExecutor = replaceWithSingleWorkerExecutor(false);

        Path solverStarted = tempDir.resolve("saturated-solver-started");
        Path releaseSolver = tempDir.resolve("release-saturated-solver");
        Path script = executableScript(
            "saturated-job.sh",
            "touch '" + solverStarted + "'\n" +
                "while [ ! -e '" + releaseSolver + "' ]; do sleep 0.01; done"
        );
        Path workDir = Files.createDirectory(tempDir.resolve("saturated-work"));
        Path logPath = tempDir.resolve("saturated-output/job.log");
        ExecutorService callers = Executors.newFixedThreadPool(2);
        Future<Integer> submission = null;
        Future<Boolean> control = null;

        try {
            occupyWorkerAndQueue(saturatedExecutor, releaseWorker);

            CountDownLatch submitStarted = new CountDownLatch(1);
            submission = callers.submit(() -> {
                submitStarted.countDown();
                // submitScript reads the pair's attempt (#185); mocking is thread-local, so
                // the stub belongs on the submitting thread. No database is involved.
                try (MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
                    pairs.when(() -> JobPairs.getCurrentAttemptNo(42)).thenReturn(1);
                    return backend.submitScript(
                        42, script.toString(), workDir.toString(), logPath.toString());
                }
            });
            Assert.assertTrue(submitStarted.await(2, TimeUnit.SECONDS));

            Future<Integer> submittedJob = submission;
            await()
                .atMost(2, TimeUnit.SECONDS)
                .pollInterval(10, TimeUnit.MILLISECONDS)
                .until(() -> submittedJob.isDone() || Files.exists(solverStarted));

            CountDownLatch controlStarted = new CountDownLatch(1);
            control = callers.submit(() -> {
                controlStarted.countDown();
                // Both monitor-holding control paths must stay responsive.
                return !backend.killPair(Integer.MIN_VALUE) && backend.killAll();
            });
            Assert.assertTrue(controlStarted.await(2, TimeUnit.SECONDS));
            boolean controlCompleted = false;
            try {
                Assert.assertTrue(control.get(500, TimeUnit.MILLISECONDS));
                controlCompleted = true;
            } catch (TimeoutException expectedWhenCallerRuns) {
                // Released below so the baseline failure does not strand a solver process.
            }

            Files.writeString(releaseSolver, "release\n");
            boolean deferred = false;
            try {
                submission.get(2, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Assert.assertTrue(e.getCause() instanceof SubmissionDeferredException,
                    "saturation must use the scheduler's non-terminal deferral signal");
                deferred = true;
            }
            if (control != null) {
                control.get(2, TimeUnit.SECONDS);
            }

            Assert.assertTrue(controlCompleted,
                "a saturated submission must not hold the backend monitor while a solver runs");
            Assert.assertTrue(deferred,
                "the explicit saturation contract is to defer the submission");
            Assert.assertFalse(Files.exists(solverStarted),
                "a saturated solver must not run on its submitting thread");
            Assert.assertTrue(backend.getActiveExecutionIds().isEmpty(),
                "a rejected or completed submission must not leave a tracked execution ID");
            Assert.assertEquals(((LocalBackend) backend).getQueuedJobCount(), 0);
            Assert.assertEquals(((LocalBackend) backend).getRunningJobCount(), 0);

            saturatedExecutor.getQueue().clear();
            int queuedId;
            try (MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
                pairs.when(() -> JobPairs.getCurrentAttemptNo(42)).thenReturn(1);
                queuedId = backend.submitScript(
                    42, script.toString(), workDir.toString(), logPath.toString());
            }
            Assert.assertTrue(queuedId > 0, "the freed queue slot must accept a submission");
            Assert.assertTrue(backend.killPair(queuedId),
                "a job must be cancellable immediately after submitScript returns");
            releaseWorker.countDown();
            await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> saturatedExecutor.getQueue().isEmpty());
            Assert.assertFalse(Files.exists(solverStarted),
                "an immediately cancelled queued job must never execute");
            Assert.assertTrue(backend.getActiveExecutionIds().isEmpty());

            saturatedExecutor.shutdown();
            try {
                backend.submitScript(
                    42, script.toString(), workDir.toString(), logPath.toString());
                Assert.fail("a job-pair submission during shutdown must be deferred");
            } catch (SubmissionDeferredException expected) {
                Assert.assertTrue(backend.getActiveExecutionIds().isEmpty(),
                    "shutdown rejection must not leave a tracked execution ID");
            }
        } finally {
            Files.writeString(releaseSolver, "release\n");
            releaseWorker.countDown();
            if (submission != null) {
                submission.cancel(true);
            }
            if (control != null) {
                control.cancel(true);
            }
            callers.shutdownNow();
            saturatedExecutor.shutdownNow();
            callers.awaitTermination(2, TimeUnit.SECONDS);
            saturatedExecutor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void saturatedNonPairSubmissionReturnsErrorAndLeavesNothingTracked() throws Exception {
        waitForFixtureJob();

        CountDownLatch releaseWorker = new CountDownLatch(1);
        ThreadPoolExecutor saturatedExecutor = replaceWithSingleWorkerExecutor(false);
        Path solverStarted = tempDir.resolve("non-pair-solver-started");
        Path script = executableScript(
            "non-pair-job.sh", "touch '" + solverStarted + "'");
        Path workDir = Files.createDirectory(tempDir.resolve("non-pair-work"));
        Path logPath = tempDir.resolve("non-pair-output/job.log");

        try {
            occupyWorkerAndQueue(saturatedExecutor, releaseWorker);

            int execId = backend.submitScript(
                -1, script.toString(), workDir.toString(), logPath.toString());

            Assert.assertEquals(execId, -1,
                "a saturated maintenance submission reports an error instead of running inline");
            Assert.assertFalse(Files.exists(solverStarted));
            Assert.assertTrue(backend.getActiveExecutionIds().isEmpty());
        } finally {
            releaseWorker.countDown();
            saturatedExecutor.shutdownNow();
            saturatedExecutor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void unexpectedHandOffFailureLeavesNothingTracked() throws Exception {
        waitForFixtureJob();

        Field executorField = LocalBackend.class.getDeclaredField("executorService");
        executorField.setAccessible(true);
        ThreadPoolExecutor configuredExecutor =
            (ThreadPoolExecutor) executorField.get(backend);
        configuredExecutor.shutdownNow();
        Assert.assertTrue(configuredExecutor.awaitTermination(2, TimeUnit.SECONDS));
        ThreadPoolExecutor failing = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1)) {
            @Override
            public void execute(Runnable command) {
                throw new IllegalStateException("simulated hand-off failure");
            }
        };
        executorField.set(backend, failing);
        Path script = executableScript("hand-off-failure.sh", "true");
        Path workDir = Files.createDirectory(tempDir.resolve("hand-off-work"));
        Path logPath = tempDir.resolve("hand-off-output/job.log");

        try {
            int execId = backend.submitScript(
                42, script.toString(), workDir.toString(), logPath.toString());

            Assert.assertEquals(execId, -1);
            Assert.assertTrue(backend.getActiveExecutionIds().isEmpty(),
                "a failed hand-off must not leave a tracked execution ID");
        } finally {
            failing.shutdownNow();
        }
    }

    @Test
    public void callerRunCompletionIsNotReinsertedIntoActiveJobs() throws Exception {
        waitForFixtureJob();

        CountDownLatch releaseWorker = new CountDownLatch(1);
        ThreadPoolExecutor callerRunsExecutor = replaceWithSingleWorkerExecutor(true);

        Path solverStarted = tempDir.resolve("caller-run-solver-started");
        Path script = executableScript(
            "caller-run-job.sh", "touch '" + solverStarted + "'");
        Path workDir = Files.createDirectory(tempDir.resolve("caller-run-work"));
        Path logPath = tempDir.resolve("caller-run-output/job.log");

        try {
            occupyWorkerAndQueue(callerRunsExecutor, releaseWorker);

            int execId = backend.submitScript(
                -1, script.toString(), workDir.toString(), logPath.toString());

            Assert.assertTrue(execId > 0);
            Assert.assertTrue(Files.exists(solverStarted),
                "the fixture must prove CallerRunsPolicy executed the saturated task");
            Assert.assertFalse(backend.getActiveExecutionIds().contains(execId),
                "a task completed inside execute() must not be registered afterward");
        } finally {
            releaseWorker.countDown();
            callerRunsExecutor.shutdownNow();
            callerRunsExecutor.awaitTermination(2, TimeUnit.SECONDS);
        }
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
        // The case that discriminates: the status field is complete, so a regex matches it and
        // reads 7 as a finished run -- but the document is not, so the write is still in
        // progress. The old implementation returned true here and killed a live process.
        Assert.assertFalse(
            reportedComplete("{\"pairId\":1,\"status\":7,\"stageNum"),
            "a complete status field in an incomplete document is not a result");
        Assert.assertFalse(
            reportedComplete("{\"pairId\":1,\"status\":14,\"stageNumber\":1,\"timesta"),
            "truncated in a later field is still an incomplete document");

        // These were already refused before the change; kept so the whole shape is covered.
        Assert.assertFalse(reportedComplete("{\"pairId\":1,\"stat"), "truncated before the field");
        Assert.assertFalse(reportedComplete("{\"pairId\":1,\"status\":1"), "truncated mid-number");
        Assert.assertFalse(reportedComplete("{ this is not json"), "malformed");
        Assert.assertFalse(reportedComplete("{\"pairId\":1}"), "no status field");
        Assert.assertFalse(reportedComplete(null), "no status.json at all");
    }
}
