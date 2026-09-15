package org.starexec.servlets;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Processors;
import org.starexec.data.database.Queues;
import org.starexec.data.database.Settings;
import org.starexec.data.database.Solvers;
import org.starexec.data.to.DefaultSettings;
import org.starexec.data.to.Job;
import org.starexec.data.to.Processor;
import org.starexec.data.to.Queue;
import org.starexec.data.to.Solver;
import org.starexec.data.to.pipelines.StageAttributes;
import org.starexec.jobs.JobManager;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The job created to test a newly uploaded solver takes its processors from the settings profile
 * the upload names (#203).
 *
 * <p>{@code CreateJob.buildSolverTestJob} read the post-processor from
 * {@code getPreProcessorId()} whenever the profile had one. A profile with only a post-processor
 * threw a {@code NullPointerException} unboxing the absent pre-processor, so the upload failed with
 * HTTP 500 after the solver had been stored; a profile with both ran the pre-processor again in
 * place of the post-processor, so every result was post-processed by the wrong program.
 *
 * <p>The database is replaced at its static boundaries; what is asserted is the job handed to
 * {@code Jobs.add}, built by the real {@code JobManager.setupJob}.
 */
public class CreateJobSolverTestJobTests {

	private static final int SOLVER_ID = 11;
	private static final int SPACE_ID = 22;
	private static final int USER_ID = 33;
	private static final int SETTINGS_ID = 44;
	private static final int QUEUE_ID = 55;
	private static final int PRE_PROCESSOR_ID = 101;
	private static final int POST_PROCESSOR_ID = 202;

	@Test
	public void aProfileWithOnlyAPostProcessorTestsWithThatPostProcessor() throws Exception {
		StageAttributes stage = buildTestJobStage(null, POST_PROCESSOR_ID);

		assertNull("no pre-processor was configured", stage.getPreProcessor());
		assertNotNull("the configured post-processor is used", stage.getPostProcessor());
		assertEquals(POST_PROCESSOR_ID, stage.getPostProcessor().getId());
	}

	@Test
	public void aProfileWithBothProcessorsTestsWithEachInItsPlace() throws Exception {
		StageAttributes stage = buildTestJobStage(PRE_PROCESSOR_ID, POST_PROCESSOR_ID);

		assertEquals(PRE_PROCESSOR_ID, stage.getPreProcessor().getId());
		assertEquals("the post-processor is not the pre-processor run twice",
				POST_PROCESSOR_ID, stage.getPostProcessor().getId());
	}

	/** The control: a pre-processor alone was never affected. */
	@Test
	public void aProfileWithOnlyAPreProcessorTestsWithNoPostProcessor() throws Exception {
		StageAttributes stage = buildTestJobStage(PRE_PROCESSOR_ID, null);

		assertEquals(PRE_PROCESSOR_ID, stage.getPreProcessor().getId());
		assertNull(stage.getPostProcessor());
	}

	/**
	 * Runs buildSolverTestJob against a profile with the given processors and returns stage 1 of
	 * the job it tried to add.
	 */
	private static StageAttributes buildTestJobStage(Integer preProcessorId, Integer postProcessorId)
			throws Exception {
		Solver solver = new Solver();
		solver.setId(SOLVER_ID);
		solver.setName("uploaded-solver");

		try (MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
				MockedStatic<Settings> settings = Mockito.mockStatic(Settings.class);
				MockedStatic<Queues> queues = Mockito.mockStatic(Queues.class);
				MockedStatic<Processors> processors = Mockito.mockStatic(Processors.class);
				MockedStatic<JobManager> jobManager =
						Mockito.mockStatic(JobManager.class, Mockito.CALLS_REAL_METHODS);
				MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {

			// DefaultSettings' constructor asks for the no-type processor.
			processors.when(Processors::getNoTypeProcessor).thenReturn(new Processor());
			DefaultSettings profile = new DefaultSettings();
			profile.setPreProcessorId(preProcessorId);
			profile.setPostProcessorId(postProcessorId);
			profile.setCpuTimeout(10);
			profile.setWallclockTimeout(20);
			profile.setMaxMemory(1024L * 1024L);
			profile.setBenchIds(Collections.singletonList(7));

			solvers.when(() -> Solvers.get(SOLVER_ID)).thenReturn(solver);
			solvers.when(() -> Solvers.getConfigsForSolver(SOLVER_ID))
					.thenReturn(Collections.emptyList());
			settings.when(() -> Settings.getProfileById(SETTINGS_ID)).thenReturn(profile);
			queues.when(Queues::getTestQueue).thenReturn(QUEUE_ID);
			queues.when(() -> Queues.get(QUEUE_ID)).thenReturn(new Queue());
			processors.when(() -> Processors.get(Mockito.anyInt())).thenAnswer(call -> {
				Processor p = new Processor();
				p.setId(call.getArgument(0));
				return p;
			});
			jobManager.when(() -> JobManager.buildJob(
					Mockito.any(Job.class), Mockito.anyList(), Mockito.anyList(), Mockito.any()))
					.thenAnswer(call -> null);
			ArgumentCaptor<Job> added = ArgumentCaptor.forClass(Job.class);
			jobs.when(() -> Jobs.add(added.capture(), Mockito.eq(SPACE_ID))).thenReturn(true);

			CreateJob.buildSolverTestJob(SOLVER_ID, SPACE_ID, USER_ID, SETTINGS_ID);

			List<Job> jobsAdded = added.getAllValues();
			assertEquals("exactly one test job is added", 1, jobsAdded.size());
			StageAttributes stage = jobsAdded.get(0).getStageAttributesByStageNumber(1);
			assertNotNull("the test job has a stage 1", stage);
			return stage;
		}
	}
}
