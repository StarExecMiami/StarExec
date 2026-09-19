package org.starexec.test.junit.jobs;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.starexec.backend.Backend;
import org.starexec.backend.exception.SubmissionDeferredException;
import org.starexec.constants.R;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Queues;
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
import org.starexec.jobs.JobManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What {@code JobManager.submitJobs} does with a pair whose submission the backend deferred.
 *
 * <p>{@code KubernetesNativeBackend.submitScript} throws {@link SubmissionDeferredException}
 * when it is at its concurrency cap, and when {@code create()} failed with nothing left in
 * the cluster. Both mean "not now, try again", but by then the pair has already been claimed
 * from PENDING_SUBMIT to ENQUEUED. Leaving it there stranded it: never selected again, yet
 * counted toward the queue size, until the queue stopped dispatching altogether (reproduced
 * on microk8s: 16 stranded pairs, zero Kubernetes Jobs, "Not adding more job pairs" forever).
 *
 * <p>Driven through the real {@code submitJobs}, which writes a real job script into a temporary
 * folder; only the database layer and the backend are stubbed, so what is asserted is the wiring
 * at the {@code JobManager} boundary.
 */
public class DeferredSubmissionTest {

	private static final String AT_CAP = "at concurrency cap (1/1); pair 101 stays queued";
	private static final String CREATE_FAILED = "create() failed for pair 101 with no cluster side effect";

	/** Exec id the first pair carries from an earlier attempt, as a rerun pair does. */
	private static final int STALE_EXEC_ID = 41;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Backend originalBackend;
	private Object originalTemplate;
	private final List<Object[]> returnedToPending = new ArrayList<>();
	private final List<String> statusWrites = new ArrayList<>();

	@Before
	public void saveGlobals() throws Exception {
		originalBackend = R.BACKEND;
		originalTemplate = templateField().get(null);
		templateField().set(null, "#!/bin/bash\n# queue $$QUEUE$$\n");
	}

	@After
	public void restoreGlobals() throws Exception {
		R.BACKEND = originalBackend;
		templateField().set(null, originalTemplate);
	}

	@Test
	public void aPairDeferredAtTheCapGoesBackToPendingAndEndsThePass() throws Exception {
		assertDeferredPairReturned(AT_CAP);
	}

	@Test
	public void aPairDeferredAfterAFailedCreateGoesBackToPendingAndEndsThePass() throws Exception {
		assertDeferredPairReturned(CREATE_FAILED);
	}

	private void assertDeferredPairReturned(String deferral) throws Exception {
		Backend backend = Mockito.mock(Backend.class);
		Mockito.when(backend.isQueueDispatchable(Mockito.anyString())).thenReturn(true);
		Mockito.when(backend.submitScript(Mockito.anyInt(), Mockito.anyString(), Mockito.any(), Mockito.anyString(),
				Mockito.anyString())).thenThrow(new SubmissionDeferredException(deferral));
		R.BACKEND = backend;

		User owner = new User();
		owner.setId(7);
		owner.setDiskQuota(Long.MAX_VALUE);
		Job job = new Job();
		job.setId(3);
		job.setUserId(7);
		job.setUser(owner);
		job.setWallclockTimeout(60);
		job.setCpuTimeout(60);
		job.setMaxMemory(1L << 30);
		job.setBenchmarkingFramework(BenchmarkingFramework.RUNSOLVER);
		StageAttributes attrs = new StageAttributes();
		attrs.setStageNumber(1);
		attrs.setCpuTimeout(60);
		attrs.setWallclockTimeout(60);
		attrs.setMaxMemory(1L << 30);
		job.addStageAttributes(attrs);

		List<JobPair> pending = new ArrayList<>();
		for (int id = 101; id <= 103; id++) {
			pending.add(pair(id, job.getId()));
		}
		pending.get(0).setBackendExecId(STALE_EXEC_ID);

		Queue q = new Queue();
		q.setId(1);
		q.setName("all.q");
		q.setCpuTimeout(60);
		q.setWallTimeout(60);

		String root = folder.getRoot().getAbsolutePath();
		String logPath = root + "/logs/pair.log";
		String stdoutPath = root + "/out/pair/stdout.txt";

		try (MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class, this::jobPairs);
				MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class, call -> jobs(call, pending));
				MockedStatic<Users> users = Mockito.mockStatic(Users.class);
				MockedStatic<Queues> queues = Mockito.mockStatic(Queues.class);
				MockedStatic<R> r = Mockito.mockStatic(R.class, Mockito.CALLS_REAL_METHODS)) {
			pairs.when(() -> JobPairs.getLogFilePath(Mockito.any())).thenReturn(logPath);
			pairs.when(() -> JobPairs.getPairStdout(Mockito.any())).thenReturn(stdoutPath);
			r.when(R::getJobInboxDir).thenReturn(root + "/jobin");

			JobManager.submitJobs(Collections.singletonList(job), q, 0, 1);
		}

		Mockito.verify(backend, Mockito.times(1)).submitScript(Mockito.anyInt(), Mockito.anyString(), Mockito.any(),
				Mockito.anyString(), Mockito.anyString());
		assertEquals("the deferred pair, and only it, goes back to PENDING_SUBMIT", 1, returnedToPending.size());
		assertEquals(101, returnedToPending.get(0)[0]);
		assertEquals("fenced on the execution id it carried when claimed", STALE_EXEC_ID, returnedToPending.get(0)[1]);
		assertTrue("a deferral is not a failure: no status may be written, was " + statusWrites,
				statusWrites.isEmpty());
	}

	// ----------------------------------------------------------------- stubs

	private Object jobPairs(InvocationOnMock call) throws Throwable {
		switch (call.getMethod().getName()) {
			case "tryMarkPendingPairEnqueued":
				return JobPairs.ConditionalPairUpdateResult.UPDATED;
			case "getPairStatusLookup":
				// setStatusForExistingPair's first step: any call means a status write was attempted.
				statusWrites.add("pair " + call.getArgument(0));
				return Mockito.RETURNS_DEFAULTS.answer(call);
			case "tryReturnDeferredPairToPending":
				returnedToPending.add(new Object[] { call.getArgument(0), call.getArgument(1) });
				return JobPairs.ConditionalPairUpdateResult.UPDATED;
			default:
				return Mockito.RETURNS_DEFAULTS.answer(call);
		}
	}

	private static Object jobs(InvocationOnMock call, List<JobPair> pending) throws Throwable {
		switch (call.getMethod().getName()) {
			case "getPendingPairsDetailed":
				return new ArrayList<>(pending);
			case "getSlotsInJobQueue":
				return "1";
			default:
				return Mockito.RETURNS_DEFAULTS.answer(call);
		}
	}

	private static JobPair pair(int id, int jobId) {
		Solver solver = new Solver();
		solver.setId(11);
		Configuration config = new Configuration();
		config.setId(12);
		JoblineStage stage = new JoblineStage();
		stage.setStageNumber(1);
		stage.setSolver(solver);
		stage.setConfiguration(config);
		Benchmark bench = new Benchmark();
		bench.setId(13);
		bench.setPath("/bench/b13.p");
		bench.setUsesDependencies(false);

		JobPair p = new JobPair();
		p.setId(id);
		p.setJobId(jobId);
		p.setPrimaryStageNumber(1);
		p.addStage(stage);
		p.setBench(bench);
		p.setPath("root");
		p.setBenchInputPaths(new ArrayList<>());
		return p;
	}

	private static Field templateField() throws Exception {
		Field f = JobManager.class.getDeclaredField("mainTemplate");
		f.setAccessible(true);
		return f;
	}
}
