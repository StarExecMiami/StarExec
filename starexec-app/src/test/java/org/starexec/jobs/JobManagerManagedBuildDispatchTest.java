package org.starexec.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.backend.Backend;
import org.starexec.constants.R;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Queues;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.database.Users;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Configuration;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Queue;
import org.starexec.data.to.Solver;
import org.starexec.data.to.User;
import org.starexec.data.to.enums.BenchmarkingFramework;
import org.starexec.data.to.pipelines.JoblineStage;
import org.starexec.data.to.pipelines.StageAttributes;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the queued dispatcher, including its real script and log writes. */
class JobManagerManagedBuildDispatchTest {
    @TempDir Path files;
    private Backend originalBackend;
    private Object originalTemplate;
    private boolean managedHistory;
    private boolean historyReadFails;

    @BeforeEach void saveGlobals() throws Exception {
        originalBackend = R.BACKEND;
        originalTemplate = templateField().get(null);
        templateField().set(null, "#!/bin/bash\n# queue $$QUEUE$$\n");
    }

    @AfterEach void restoreGlobals() throws Exception {
        R.BACKEND = originalBackend;
        templateField().set(null, originalTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepared", "bound", "stopped", "staging-only", "engine-only", "output-only", "rejected", "deleted"})
    void registeredBuildCannotFallThroughToLegacySubmission(String kind) throws Exception {
        dispatch(true, Optional.of(artifact(kind)), false, true, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void registryFailureDefersBeforeAnyScriptOrLogWrite(boolean stagingSupported) throws Exception {
        dispatch(true, Optional.empty(), true, true, stagingSupported);
    }

    @Test void registeredBuildIsNotDiscardedByAnUnsupportedBackend() throws Exception {
        dispatch(true, Optional.of(artifact("prepared")), false, true, false);
    }

    @Test void unboundPlannedBuildPreservesLegacyDispatch() throws Exception {
        dispatch(true, Optional.of(artifact("planned")), false, false, false);
    }

    @Test void historicalBuildWithoutCatalogEntryPreservesLegacyDispatch() throws Exception {
        dispatch(true, Optional.empty(), false, false, false);
    }

    @Test void ordinaryJobDoesNotConsultTheBuildRegistry() throws Exception {
        dispatch(false, Optional.empty(), true, false, true);
    }

    @Test void priorManagedAttemptPreventsAnUnrecordableLegacyRerun() throws Exception {
        managedHistory = true;
        dispatch(true, Optional.of(artifact("planned")), false, true, true);
    }

    @Test void unavailableHistoryDoesNotPermitLegacySubmission() throws Exception {
        historyReadFails = true;
        dispatch(true, Optional.of(artifact("planned")), false, true, true);
    }

    private void dispatch(boolean build, Optional<SolverBuildArtifacts.Artifact> artifact,
                          boolean registryFails, boolean deferred, boolean stagingSupported) throws Exception {
        Backend backend = mock(Backend.class);
        R.BACKEND = backend;
        when(backend.isQueueDispatchable(anyString())).thenReturn(true);
        when(backend.supportsBuildStaging()).thenReturn(stagingSupported);
        when(backend.submitScript(anyInt(), anyString(), any(), anyString(), anyString())).thenReturn(500);

        User owner = new User();
        owner.setId(7);
        owner.setDiskQuota(Long.MAX_VALUE);
        Job job = new Job();
        job.setId(3);
        job.setUserId(7);
        job.setUser(owner);
        job.setBuildJob(build);
        job.setWallclockTimeout(60);
        job.setCpuTimeout(60);
        job.setMaxMemory(1L << 30);
        job.setBenchmarkingFramework(BenchmarkingFramework.RUNSOLVER);
        StageAttributes attributes = new StageAttributes();
        attributes.setStageNumber(1);
        attributes.setCpuTimeout(60);
        attributes.setWallclockTimeout(60);
        attributes.setMaxMemory(1L << 30);
        job.addStageAttributes(attributes);

        JobPair pair = pair(101, job.getId());
        pair.setBackendExecId(41);
        Queue queue = new Queue();
        queue.setId(1);
        queue.setName("all.q");
        queue.setCpuTimeout(60);
        queue.setWallTimeout(60);
        Path inbox = Files.createDirectory(files.resolve("inbox"));
        Path log = Files.writeString(files.resolve("previous.log"), "conserved prior output\n");
        String root = inbox.toString();
        String stdout = files.resolve("output/stdout.txt").toString();
        List<Integer> claims = new ArrayList<>();
        List<Integer> returned = new ArrayList<>();

        try (MockedStatic<JobPairs> pairs = mockStatic(JobPairs.class);
             MockedStatic<Jobs> jobs = mockStatic(Jobs.class);
             MockedStatic<Users> users = mockStatic(Users.class);
             MockedStatic<Queues> queues = mockStatic(Queues.class);
             MockedStatic<SolverBuildArtifacts> artifacts = mockStatic(SolverBuildArtifacts.class);
             MockedStatic<R> constants = mockStatic(R.class, CALLS_REAL_METHODS)) {
            pairs.when(() -> JobPairs.tryMarkPendingPairEnqueued(anyInt())).thenAnswer(call -> {
                claims.add(call.getArgument(0));
                return JobPairs.ConditionalPairUpdateResult.UPDATED;
            });
            pairs.when(() -> JobPairs.tryReturnDeferredPairToPending(101, 41)).thenAnswer(call -> {
                returned.add(call.getArgument(0));
                return JobPairs.ConditionalPairUpdateResult.UPDATED;
            });
            pairs.when(() -> JobPairs.tryUpdateBackendExecId(101, 500))
                    .thenReturn(JobPairs.ConditionalPairUpdateResult.UPDATED);
            pairs.when(() -> JobPairs.getLogFilePath(any(JobPair.class))).thenReturn(log.toString());
            pairs.when(() -> JobPairs.getPairStdout(any(JobPair.class))).thenReturn(stdout);
            jobs.when(() -> Jobs.getPendingPairsDetailed(any(Job.class), anyInt()))
                    .thenReturn(deferred ? List.of(pair, pair(102, job.getId())) : List.of(pair));
            jobs.when(() -> Jobs.getSlotsInJobQueue(any(Job.class))).thenReturn("1");
            constants.when(R::getJobInboxDir).thenReturn(root);
            if (registryFails) {
                artifacts.when(() -> SolverBuildArtifacts.findCurrent(101)).thenThrow(new SQLException("unavailable"));
            } else {
                artifacts.when(() -> SolverBuildArtifacts.findCurrent(101)).thenReturn(artifact);
            }
            if (historyReadFails) {
                artifacts.when(() -> SolverBuildArtifacts.hasManagedHistory(101)).thenThrow(new SQLException("unavailable"));
            } else {
                artifacts.when(() -> SolverBuildArtifacts.hasManagedHistory(101)).thenReturn(managedHistory);
            }

            JobManager.submitJobs(List.of(job), queue, 0, 1);

            if (deferred) {
                verify(backend, never()).submitScript(anyInt(), anyString(), any(), anyString(), anyString());
                verify(backend, never()).submitScript(anyInt(), anyString(), any(), anyString(), anyString(), any());
            }
            assertEquals(List.of(101), claims, "only one pair is claimed in this pass");
            if (build) artifacts.verify(() -> SolverBuildArtifacts.findCurrent(101));
            else artifacts.verifyNoInteractions();
            pairs.verify(() -> JobPairs.getPairStatusLookup(anyInt()), never());
            if (deferred) {
                assertEquals(List.of(101), returned, "return the captured pair and execution to pending");
                assertEquals("conserved prior output\n", Files.readString(log));
                try (var entries = Files.list(inbox)) {
                    assertEquals(0, entries.count(), "refusal must precede script writes");
                }
                pairs.verify(() -> JobPairs.getLogFilePath(any(JobPair.class)), never());
            } else {
                verify(backend).submitScript(eq(101), anyString(), any(), eq(log.toString()), eq("all.q"));
                assertTrue(returned.isEmpty());
                assertFalse(Files.exists(log), "legacy log replacement remains unchanged");
                try (var entries = Files.walk(inbox)) {
                    assertTrue(entries.anyMatch(Files::isRegularFile), "the real script writer ran");
                }
            }
        }
    }

    private static SolverBuildArtifacts.Artifact artifact(String kind) {
        boolean planned = kind.equals("planned") || kind.equals("rejected") || kind.equals("deleted");
        boolean partial = kind.endsWith("-only");
        UUID token = planned || partial ? null : UUID.fromString("00000000-0000-0000-0000-000000000001");
        String staging = kind.equals("staging-only") || !planned && !partial ? "/storage/build" : null;
        String engine = kind.equals("bound") || kind.equals("stopped") || kind.equals("engine-only")
                ? "podman:500:owned-container" : null;
        return new SolverBuildArtifacts.Artifact(1, 101, 2, 11, 11, 7, 1,
                "/source_src", staging, kind.equals("output-only") ? "/output" : null,
                null, token, engine, kind.equals("stopped") ? Instant.EPOCH : null,
                kind.equals("rejected") ? "REJECTED" : kind.equals("deleted") ? "DELETED" : planned ? "PLANNED" : "STAGED");
    }

    private static JobPair pair(int id, int jobId) {
        Solver solver = new Solver();
        solver.setId(11);
        Configuration configuration = new Configuration();
        configuration.setId(12);
        JoblineStage stage = new JoblineStage();
        stage.setStageNumber(1);
        stage.setSolver(solver);
        stage.setConfiguration(configuration);
        Benchmark benchmark = new Benchmark();
        benchmark.setId(13);
        benchmark.setPath("/bench/b13.p");
        benchmark.setUsesDependencies(false);
        JobPair pair = new JobPair();
        pair.setId(id);
        pair.setJobId(jobId);
        pair.setPrimaryStageNumber(1);
        pair.addStage(stage);
        pair.setBench(benchmark);
        pair.setPath("root");
        pair.setBenchInputPaths(new ArrayList<>());
        return pair;
    }

    private static Field templateField() throws Exception {
        Field field = JobManager.class.getDeclaredField("mainTemplate");
        field.setAccessible(true);
        return field;
    }
}
