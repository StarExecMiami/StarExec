package org.starexec.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Queues;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Job;
import org.starexec.data.to.Queue;
import org.starexec.data.to.Solver;
import org.starexec.data.to.SolverBuildStatus.SolverBuildStatusCode;
import org.starexec.data.to.Space;
import org.starexec.servlets.UploadBenchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class JobManagerBuildInputFailureTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    public void missingBuildInputFailsBeforeJobCreation(Integer benchmarkId) throws Exception {
        checkBuildInput(benchmarkId, false);
    }

    @Test
    public void validBuildInputPreservesJobCreation() throws Exception {
        checkBuildInput(42, true);
    }

    private void checkBuildInput(Integer benchmarkId, boolean success) throws Exception {
        Solver solver = new Solver();
        solver.setId(7);
        solver.setUserId(3);
        solver.setName("synthetic solver");
        Queue queue = new Queue();
        queue.setId(2);
        Benchmark benchmark = new Benchmark();
        benchmark.setId(42);
        try (MockedStatic<Solvers> solvers = mockStatic(Solvers.class);
             MockedStatic<Queues> queues = mockStatic(Queues.class);
             MockedStatic<Spaces> spaces = mockStatic(Spaces.class);
             MockedStatic<Benchmarks> benchmarks = mockStatic(Benchmarks.class);
             MockedStatic<Jobs> jobs = mockStatic(Jobs.class);
             MockedStatic<UploadBenchmark> uploads = mockStatic(UploadBenchmark.class);
             MockedStatic<JobManager> manager = mockStatic(JobManager.class, invocation -> {
                 if (invocation.getMethod().getName().equals("setupJob")) {
                     return new Job();
                 }
                 return invocation.callRealMethod();
             })) {
            solvers.when(() -> Solvers.get(7)).thenReturn(solver);
            queues.when(() -> Queues.getQueueForSystemJob(3)).thenReturn(2);
            queues.when(() -> Queues.get(2)).thenReturn(queue);
            spaces.when(() -> Spaces.getName(5)).thenReturn("synthetic space");
            spaces.when(() -> Spaces.get(5)).thenReturn(new Space());
            uploads.when(() -> UploadBenchmark.addBenchmarkFromText(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(benchmarkId);
            benchmarks.when(() -> Benchmarks.get(42)).thenReturn(benchmark);
            jobs.when(() -> Jobs.add(any(Job.class), eq(5))).thenAnswer(invocation -> {
                invocation.<Job>getArgument(0).setId(99);
                return true;
            });

            assertEquals(success ? 99 : -1, JobManager.addBuildJob(7, 5));
            if (success) {
                benchmarks.verify(() -> Benchmarks.get(42));
                jobs.verify(() -> Jobs.add(any(Job.class), eq(5)));
                solvers.verify(() -> Solvers.setSolverBuildStatus(any(), anyInt()), never());
            } else {
                benchmarks.verifyNoInteractions();
                jobs.verifyNoInteractions();
                solvers.verify(() -> Solvers.setSolverBuildStatus(solver, SolverBuildStatusCode.BUILD_FAILED.getVal()));
            }
        }
    }
}
