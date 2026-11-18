package org.starexec.test.junit.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.starexec.backend.Backend;
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

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code JobManager.submitJobs} does with a pair whose job was killed, deleted or paused
 * while the backend was starting it.
 *
 * <p>The pair is claimed (PENDING_SUBMIT to ENQUEUED) and submitted, and only then is its
 * execution id written, conditionally: the write is refused once the job is no longer
 * submit-eligible. The cleanup that followed stopped the execution with the legacy
 * {@code killPair}, which proves nothing, and wrote nothing: the pair stayed ENQUEUED with no
 * execution id. {@code Jobs.kill} cannot prove an execution it cannot name stopped, so deleting
 * the job was refused on every retry until FIND_BROKEN_JOB_PAIRS filed the pair
 * ERROR_SUBMIT_FAIL, up to three hours later (E2E check 14.k1, 2026-10-22).
 *
 * <p>Driven through the real {@code submitJobs}, which writes a real job script into a
 * temporary folder; only the database layer and the backend are stubbed. The settle call is
 * matched by name, so this test compiles against code that lacks it and fails there on
 * behavior.
 */
public class StaleSubmissionTest {

	private static final int PAIR_ID = 101;
	/** The pair's execution id when it was loaded: a rerun pair keeps its previous one. */
	private static final int EXEC_ID_AT_CLAIM = 41;
	/** The execution the backend creates for this submission. */
	private static final int NEW_EXEC_ID = 1043;

	@TempDir
	public File folder;

	private Backend originalBackend;
	private Object originalTemplate;
	private final List<Object[]> settled = new ArrayList<>();
	private final List<String> statusWrites = new ArrayList<>();

	@BeforeEach
	public void saveGlobals() throws Exception {
		originalBackend = R.BACKEND;
		originalTemplate = templateField().get(null);
		templateField().set(null, "#!/bin/bash\n# queue $$QUEUE$$\n");
	}

	@AfterEach
	public void restoreGlobals() throws Exception {
		R.BACKEND = originalBackend;
		templateField().set(null, originalTemplate);
	}

	@Test
	public void aConfirmedStopIsSettledAndRetiredFromTracking() throws Exception {
		Backend backend = backend();
		Mockito.when(backend.killPairConfirmed(Mockito.anyInt())).thenReturn(Backend.KillOutcome.CONFIRMED_SAFE);

		submit(backend);

		Mockito.verify(backend).killPairConfirmed(NEW_EXEC_ID);
		Mockito.verify(backend, Mockito.never()).killPair(Mockito.anyInt());
		assertSettled(true);
		Mockito.verify(backend).clearPairTracking(PAIR_ID);
	}

	@Test
	public void anUnprovenStopIsRecordedAndStaysTracked() throws Exception {
		Backend backend = backend();
		Mockito.when(backend.killPairConfirmed(Mockito.anyInt())).thenReturn(Backend.KillOutcome.UNPROVEN);

		submit(backend);

		Mockito.verify(backend).killPairConfirmed(NEW_EXEC_ID);
		assertSettled(false);
		// Kept, as Jobs.kill keeps it: the execution may still be running, so its monitor and
		// the backend's concurrency accounting must still see it.
		Mockito.verify(backend, Mockito.never()).clearPairTracking(Mockito.anyInt());
	}

	@Test
	public void aStopThatThrowsIsUnproven() throws Exception {
		Backend backend = backend();
		Mockito.when(backend.killPairConfirmed(Mockito.anyInt())).thenThrow(new IllegalStateException("backend unreachable"));

		submit(backend);

		assertSettled(false);
		Mockito.verify(backend, Mockito.never()).clearPairTracking(Mockito.anyInt());
	}

	private void assertSettled(boolean stopConfirmed) {
		assertEquals(1, settled.size(), "the stale pair is settled exactly once");
		assertArrayEquals(new Object[] { PAIR_ID, EXEC_ID_AT_CLAIM, NEW_EXEC_ID, stopConfirmed },
				settled.get(0), "settled against the identity it was claimed with, with the new execution");
		assertTrue(statusWrites.isEmpty(),
				"a stale submission is not a submission failure: no status may be written, was " + statusWrites);
	}

	// ----------------------------------------------------------------- driver

	private static Backend backend() throws Exception {
		Backend backend = Mockito.mock(Backend.class);
		Mockito.when(backend.isQueueDispatchable(Mockito.anyString())).thenReturn(true);
		Mockito.when(backend.submitScript(Mockito.anyInt(), Mockito.anyString(), Mockito.any(), Mockito.anyString(),
				Mockito.anyString())).thenReturn(NEW_EXEC_ID);
		return backend;
	}

	private void submit(Backend backend) throws Exception {
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
		pending.add(pair(PAIR_ID, job.getId()));
		pending.get(0).setBackendExecId(EXEC_ID_AT_CLAIM);

		Queue q = new Queue();
		q.setId(1);
		q.setName("all.q");
		q.setCpuTimeout(60);
		q.setWallTimeout(60);

		String root = folder.getAbsolutePath();
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
	}

	// ----------------------------------------------------------------- stubs

	private Object jobPairs(InvocationOnMock call) throws Throwable {
		switch (call.getMethod().getName()) {
			case "tryMarkPendingPairEnqueued":
				return JobPairs.ConditionalPairUpdateResult.UPDATED;
			case "tryUpdateBackendExecId":
				// The job was killed, deleted or paused while the backend was starting the pair.
				return JobPairs.ConditionalPairUpdateResult.STALE;
			case "getPairStatusLookup":
				// setStatusForExistingPair's first step: any call means a status write was attempted.
				statusWrites.add("pair " + call.getArgument(0));
				return Mockito.RETURNS_DEFAULTS.answer(call);
			case "settleStaleSubmission":
				settled.add(call.getArguments());
				// Any outcome: JobManager only reports it.
				return call.getMethod().getReturnType().getEnumConstants()[0];
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
