package org.starexec.servlets;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Processors;
import org.starexec.data.database.Queues;
import org.starexec.data.database.Settings;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.DefaultSettings;
import org.starexec.data.to.Job;
import org.starexec.data.to.Processor;
import org.starexec.data.to.Queue;
import org.starexec.data.to.Solver;
import org.starexec.data.to.pipelines.StageAttributes;
import org.starexec.jobs.JobManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The jobs StarExec creates on a user's behalf -- the test job for a newly uploaded solver, and the
 * job that builds one -- are placed on a queue that is scheduled, or not created at all (#204).
 *
 * <p>Both used {@code system_flags.test_queue}, defaulting to {@code all.q}, whether or not that
 * queue was ACTIVE. {@code JobManager.checkPendingJobs} schedules only ACTIVE queues, so on a
 * backend where {@code all.q} is inactive -- the PodmanBackend stack, whose own queue
 * ({@code container.q}) {@code PodmanBackend} makes global and sets ACTIVE -- the job's pairs stayed
 * pending forever.
 *
 * <p>The queue chosen is the configured test queue if the job's owner can use it (an ACTIVE queue
 * in {@code Queues.getUserQueues}), otherwise the lowest-numbered queue the owner can use; with
 * none, the job is not created. The database is replaced at its static boundaries; the job
 * asserted is the one handed to {@code Jobs.add}, built by the real {@code JobManager.setupJob}.
 */
public class SystemJobQueueTests {

	private static final int UPLOADER = 33;
	private static final int SOLVER_OWNER = 77;
	private static final int SOLVER_ID = 11;
	private static final int SPACE_ID = 22;
	private static final int SETTINGS_ID = 44;
	private static final int ALL_Q = 1;

	// ------------------------------------------------------------------ the solver test job

	/** The Podman stack: all.q is the test queue and inactive, container.q is active. */
	@Test
	public void theTestJobUsesAnActiveQueueWhenTheTestQueueIsInactive() throws Exception {
		Job job = buildTestJob(ALL_Q, Collections.singletonList(queue(2, 600, 900)));
		assertEquals(2, job.getQueue().getId());
	}

	@Test
	public void theTestJobKeepsATestQueueTheUploaderCanUse() throws Exception {
		Job job = buildTestJob(3, Arrays.asList(queue(2, 600, 900), queue(3, 600, 900)));
		assertEquals(3, job.getQueue().getId());
	}

	@Test
	public void theTestJobTakesTheLowestNumberedUsableQueue() throws Exception {
		Job job = buildTestJob(ALL_Q, Arrays.asList(queue(5, 600, 900), queue(2, 600, 900)));
		assertEquals(2, job.getQueue().getId());
	}

	@Test
	public void noTestJobIsCreatedWhenNoQueueIsUsable() throws Exception {
		try (Mocks mocks = new Mocks(ALL_Q, UPLOADER, Collections.emptyList())) {
			int jobId = CreateJob.buildSolverTestJob(SOLVER_ID, SPACE_ID, UPLOADER, SETTINGS_ID);

			assertEquals(-1, jobId);
			mocks.jobs.verify(() -> Jobs.add(Mockito.any(Job.class), Mockito.anyInt()),
					Mockito.never());
		}
	}

	// ------------------------------------------------------------------------ the build job

	/** Owned by the solver's owner, on a queue that owner can use, with that queue's limits. */
	@Test
	public void theBuildJobUsesAnActiveQueueTheSolverOwnerCanUse() throws Exception {
		try (Mocks mocks = new Mocks(ALL_Q, SOLVER_OWNER,
				Collections.singletonList(queue(2, 111, 222)))) {
			JobManager.addBuildJob(SOLVER_ID, SPACE_ID);

			Job job = mocks.added();
			assertEquals(2, job.getQueue().getId());
			StageAttributes stage = job.getStageAttributesByStageNumber(1);
			assertEquals("the build job's cpu limit is its queue's", 111, stage.getCpuTimeout());
			assertEquals("and so is its wallclock limit", 222, stage.getWallclockTimeout());
		}
	}

	@Test
	public void noBuildJobIsCreatedWhenNoQueueIsUsable() throws Exception {
		try (Mocks mocks = new Mocks(ALL_Q, SOLVER_OWNER, Collections.emptyList())) {
			int jobId = JobManager.addBuildJob(SOLVER_ID, SPACE_ID);

			assertEquals(-1, jobId);
			mocks.jobs.verify(() -> Jobs.add(Mockito.any(Job.class), Mockito.anyInt()),
					Mockito.never());
			mocks.uploads.verify(() -> UploadBenchmark.addBenchmarkFromText(
					Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(),
					Mockito.anyBoolean()), Mockito.never());
		}
	}

	// ------------------------------------------------------------------------------ harness

	private static Job buildTestJob(int testQueue, List<Queue> usable) throws Exception {
		try (Mocks mocks = new Mocks(testQueue, UPLOADER, usable)) {
			CreateJob.buildSolverTestJob(SOLVER_ID, SPACE_ID, UPLOADER, SETTINGS_ID);
			return mocks.added();
		}
	}

	private static Queue queue(int id, int cpuTimeout, int wallTimeout) {
		Queue q = new Queue();
		q.setId(id);
		q.setName("queue-" + id);
		q.setStatus("ACTIVE");
		q.setCpuTimeout(cpuTimeout);
		q.setWallTimeout(wallTimeout);
		return q;
	}

	/** The static boundaries both job builders reach, with the owner's usable queues given. */
	private static final class Mocks implements AutoCloseable {
		final MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
		final MockedStatic<Settings> settings = Mockito.mockStatic(Settings.class);
		final MockedStatic<Queues> queues = Mockito.mockStatic(Queues.class, Mockito.CALLS_REAL_METHODS);
		final MockedStatic<Processors> processors = Mockito.mockStatic(Processors.class);
		final MockedStatic<JobManager> jobManager =
				Mockito.mockStatic(JobManager.class, Mockito.CALLS_REAL_METHODS);
		final MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class);
		final MockedStatic<UploadBenchmark> uploads = Mockito.mockStatic(UploadBenchmark.class);
		final MockedStatic<Benchmarks> benchmarks = Mockito.mockStatic(Benchmarks.class);
		final MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class);
		private final ArgumentCaptor<Job> added = ArgumentCaptor.forClass(Job.class);

		Mocks(int testQueue, int owner, List<Queue> usable) throws Exception {
			processors.when(Processors::getNoTypeProcessor).thenReturn(new Processor());
			processors.when(() -> Processors.get(Mockito.anyInt())).thenAnswer(call -> {
				Processor p = new Processor();
				p.setId(call.getArgument(0));
				return p;
			});

			Solver solver = new Solver();
			solver.setId(SOLVER_ID);
			solver.setName("uploaded-solver");
			solver.setUserId(SOLVER_OWNER);
			solvers.when(() -> Solvers.get(SOLVER_ID)).thenReturn(solver);
			solvers.when(() -> Solvers.getConfigsForSolver(SOLVER_ID))
					.thenReturn(Collections.emptyList());

			DefaultSettings profile = new DefaultSettings();
			profile.setBenchIds(Collections.singletonList(7));
			settings.when(() -> Settings.getProfileById(SETTINGS_ID)).thenReturn(profile);

			queues.when(Queues::getTestQueue).thenReturn(testQueue);
			queues.when(() -> Queues.getUserQueues(owner)).thenReturn(usable);
			queues.when(() -> Queues.get(Mockito.anyInt())).thenAnswer(call -> {
				int id = call.getArgument(0);
				for (Queue q : usable) {
					if (q.getId() == id) {
						return q;
					}
				}
				Queue inactive = queue(id, 10, 10);
				inactive.setStatus("INACTIVE");
				return inactive;
			});

			jobManager.when(() -> JobManager.buildJob(
					Mockito.any(Job.class), Mockito.anyList(), Mockito.anyList(), Mockito.any()))
					.thenAnswer(call -> null);
			uploads.when(() -> UploadBenchmark.addBenchmarkFromText(
					Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(),
					Mockito.anyBoolean())).thenReturn(8);
			jobs.when(() -> Jobs.add(added.capture(), Mockito.eq(SPACE_ID))).thenReturn(true);
		}

		Job added() {
			List<Job> all = added.getAllValues();
			assertEquals("exactly one job is added", 1, all.size());
			return all.get(0);
		}

		@Override
		public void close() {
			spaces.close();
			benchmarks.close();
			uploads.close();
			jobs.close();
			jobManager.close();
			processors.close();
			queues.close();
			settings.close();
			solvers.close();
		}
	}
}
