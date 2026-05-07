package org.starexec.test.integration.database;

import org.junit.Assert;
import org.mockito.Mockito;
import org.starexec.backend.GridEngineBackend;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.database.Communities;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.to.*;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.data.to.enums.ProcessorType;
import org.starexec.data.to.pipelines.JoblineStage;
import org.starexec.data.to.pipelines.PairStageProcessorTriple;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.starexec.util.Util;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Tests for org.starexec.data.database.JobPairs.java
 * @author Eric
 */
public class JobPairTests extends TestSequence {


	private Space space=null; //space to put the test job
	private Solver solver=null; //solver to use for the job
	private Job job=null;
	private Processor postProc=null; //post processor to use for the job
	private List<Integer> benchmarkIds=null; // benchmarks to use for the job
	private User user=null;                  //owner of all the test primitives
	private User nonOwner=null;
	private User admin=null;
	private final int wallclockTimeout=100;
	private final int cpuTimeout=100;
	private final int gbMemory=1;

	private User user2=null;
	private Job job2=null;
	private final Random rand = new Random();

	@Override
	protected String getTestName() {
		return "JobPairTests";
	}

	@StarexecTest
	private void addAndRetrieveAttributesTest() {
		JobPair jp=job.getJobPairs().get(0);
		Properties p=new Properties();
		String prop=TestUtil.getRandomAlphaString(14);
		p.put(prop, prop);
		Assert.assertTrue(JobPairs.addJobPairAttributes(jp.getId(),jp.getPrimaryStage().getStageNumber(), p));
		Properties test=JobPairs.getAttributes(jp.getId()).get(jp.getPrimaryStage().getStageNumber());
		Assert.assertTrue(test.contains(prop));
	}

	@StarexecTest
	private void updateBackendIdTest() {
		JobPair jp = job.getJobPairs().get(0);
		int backendId = rand.nextInt();
		Assert.assertTrue(JobPairs.updateBackendExecId(jp.getId(), backendId));
		Assert.assertEquals(backendId, JobPairs.getPair(jp.getId()).getBackendExecId());
		jp.setBackendExecId(backendId);

	}

	@StarexecTest
	private void getJobPairLogTest() {
		String path=JobPairs.getLogFilePath(job.getJobPairs().get(0));
		Assert.assertNotNull(path);
	}

	@StarexecTest
	private void getJobPairLogByIdTest() {
		String path=JobPairs.getStdout(job.getJobPairs().get(0).getId());
		Assert.assertNotNull(path);
	}

	@StarexecTest
	private void getJobPairPathTest() {
		String path=JobPairs.getPairStdout(job.getJobPairs().get(0));
		Assert.assertNotNull(path);
	}

	@StarexecTest
	private void getJobPairPathByIdTest() {
		String path=JobPairs.getStdout(job.getJobPairs().get(0).getId());
		Assert.assertNotNull(path);
	}

	@StarexecTest
	private void getJobPairTest() {
		JobPair test=JobPairs.getPair(job.getJobPairs().get(0).getId());
		Assert.assertNotNull(test);
		Assert.assertEquals(test.getJobId(),job.getId());
	}

	@StarexecTest
	private void getJobPairDetailedTest() {
		JobPair test=JobPairs.getPairDetailed(job.getJobPairs().get(0).getId());
		Assert.assertNotNull(test);
		Assert.assertEquals(test.getJobId(),job.getId());

		Assert.assertEquals(test.getPrimarySolver().getName(),solver.getName());
	}

	@StarexecTest
	private void  getNodesThatMayHavePairsEnqueuedLongerThanExceptionTest() {
		try {
			int testTimeThreshold = 100000;
			JobPairs.getNodesThatMayHavePairsEnqueuedLongerThan(testTimeThreshold);
		} catch (SQLException e) {
			Assert.fail("Caught an SQLException.");
		}
	}

	// Basic test to make sure that the procedure will actually work.
	@StarexecTest
	private void getPairsEnqueuedLongerThanExceptionTest() {
		try {
			int testTimeThreshold = 100000;
			JobPairs.getPairsEnqueuedLongerThan(testTimeThreshold);
		} catch (SQLException e) {
			Assert.fail("Caught an SQLException.");
		}
	}

	@StarexecTest
	private void setPairStatusTest() {
		JobPair jp=JobPairs.getPair(job.getJobPairs().get(0).getId());
		Assert.assertTrue(JobPairs.setPairStatus(jp.getId(), StatusCode.STATUS_UNKNOWN.getVal()));
		Assert.assertEquals(StatusCode.STATUS_UNKNOWN.getVal(),JobPairs.getPair(jp.getId()).getStatus().getCode().getVal());
	}

	@StarexecTest
	private void setPairStatusRejectsTerminalDowngradeTest() {
		JobPair jp = JobPairs.getPair(job.getJobPairs().get(0).getId());

		Assert.assertTrue(JobPairs.setPairStatus(jp.getId(), StatusCode.STATUS_COMPLETE.getVal()));
		Assert.assertFalse(
			"Terminal pair status must not downgrade back to running.",
			JobPairs.setPairStatus(jp.getId(), StatusCode.STATUS_RUNNING.getVal())
		);
		Assert.assertEquals(
			StatusCode.STATUS_COMPLETE.getVal(),
			JobPairs.getPair(jp.getId()).getStatus().getCode().getVal()
		);

		Assert.assertFalse(
			"Precise status updates must also reject terminal downgrades.",
			JobPairs.setPairStatusPrecise(
				jp.getId(),
				1,
				StatusCode.STATUS_RUNNING.getVal(),
				StatusCode.STATUS_NOT_REACHED.getVal()
			)
		);
		Assert.assertEquals(
			StatusCode.STATUS_COMPLETE.getVal(),
			JobPairs.getPair(jp.getId()).getStatus().getCode().getVal()
		);
	}

	@StarexecTest
	private void setBrokenPairsToErrorStatusTest() throws IOException {
		JobPair jp=JobPairs.getPair(job.getJobPairs().get(0).getId());
		JobPairs.setPairStatus(jp.getId(), StatusCode.STATUS_ENQUEUED.getVal());
		Jobs.setBrokenPairsToErrorStatus(R.BACKEND);
		Assert.assertEquals(Status.StatusCode.ERROR_SUBMIT_FAIL.getVal(),JobPairs.getPair(jp.getId()).getStatus().getCode().getVal());
	}

	@StarexecTest
	private void setBrokenPairStatusCreatesCompletionSideEffectsTest() throws SQLException {
		int pairId = job.getJobPairs().get(0).getId();
		JobPairs.removePairFromCompletedTable(pairId);
		resetJobCompletionTime(job.getId());
		JobPairs.setPairStatus(pairId, StatusCode.STATUS_ENQUEUED.getVal());

		JobPairs.setBrokenPairStatus(JobPairs.getPair(pairId));

		Assert.assertEquals(StatusCode.ERROR_SUBMIT_FAIL.getVal(), JobPairs.getPair(pairId).getStatus().getCode().getVal());
		Assert.assertTrue("Broken pair should be inserted into job_pair_completion.", pairHasCompletionRecord(pairId));
		Assert.assertNotNull("Job should be marked complete when no active pairs remain.", Jobs.get(job.getId()).getCompleteTime());
	}

	@StarexecTest
	private void setBrokenPairStatusStaleStatusIsNoOpTest() throws SQLException {
		int pairId = job.getJobPairs().get(0).getId();
		JobPair stalePair = JobPairs.getPair(pairId);
		JobPairs.removePairFromCompletedTable(pairId);
		resetJobCompletionTime(job.getId());
		JobPairs.setPairStatus(pairId, StatusCode.STATUS_RUNNING.getVal());

		JobPairs.setBrokenPairStatus(stalePair);

		Assert.assertEquals(StatusCode.STATUS_RUNNING.getVal(), JobPairs.getPair(pairId).getStatus().getCode().getVal());
		Assert.assertFalse("Stale broken-pair update should not insert completion side effects.", pairHasCompletionRecord(pairId));
		Assert.assertNull("Stale broken-pair update should not complete the job.", Jobs.get(job.getId()).getCompleteTime());
	}

	@StarexecTest
	private void getPairsByStatusReturnsAllMatchingPairsTest() {
		JobPair firstPair = JobPairs.getPair(job.getJobPairs().get(0).getId());
		JobPair secondPair = JobPairs.getPair(job2.getJobPairs().get(0).getId());
		JobPairs.setPairStatus(firstPair.getId(), StatusCode.STATUS_ENQUEUED.getVal());
		JobPairs.setPairStatus(secondPair.getId(), StatusCode.STATUS_ENQUEUED.getVal());

		List<JobPair> enqueuedPairs = JobPairs.getPairsByStatus(StatusCode.STATUS_ENQUEUED.getVal());
		Set<Integer> enqueuedPairIds = new HashSet<>();
		for (JobPair pair : enqueuedPairs) {
			enqueuedPairIds.add(pair.getId());
		}

		Assert.assertTrue("Expected first enqueued pair to be returned.", enqueuedPairIds.contains(firstPair.getId()));
		Assert.assertTrue("Expected second enqueued pair to be returned.", enqueuedPairIds.contains(secondPair.getId()));
	}

	@StarexecTest
	private void getStdOutTest() {
		JobPair jp = job.getJobPairs().get(0);
		try {
			Optional<String> output = JobPairs.getStdOut(jp.getId(), 1, 1000);
			Assert.assertTrue("stdout for job pairs was not available.",output.isPresent());
		} catch (IOException e) {
			Assert.fail("IOException: " + Util.getStackTrace(e));
		}
	}
	@StarexecTest
	private void getStdOutNoStagesTest() {
		JobPair jp = job.getJobPairs().get(0);

		String output = JobPairs.getStdout(jp.getId());
		Assert.assertNotNull(output);
	}

	@StarexecTest
	private void getPairsToBeProcessedTest() {
		JobPair jp = job.getJobPairs().get(0);
		JobPairs.setStatusForPairAndStages(jp.getId(), StatusCode.STATUS_PROCESSING.getVal());
		List<PairStageProcessorTriple> pairs = JobPairs.getAllPairsForProcessing();
		boolean found = false;
		for (PairStageProcessorTriple triple : pairs) {
			found = found || triple.getPairId()==jp.getId();
		}
		Assert.assertTrue(found);
		JobPairs.setStatusForPairAndStages(jp.getId(), StatusCode.STATUS_COMPLETE.getVal());
	}

	@StarexecTest
	private void setBrokenPairsToErrorStatusNoChange() throws IOException {
		JobPair jp=JobPairs.getPair(job.getJobPairs().get(0).getId());
		HashSet<Integer> set = new HashSet<>();
		set.add(jp.getBackendExecId());
		JobPairs.setPairStatus(jp.getId(), StatusCode.STATUS_ENQUEUED.getVal());
		GridEngineBackend backend = Mockito.mock(GridEngineBackend.class);
		Mockito.when(backend.getActiveExecutionIds()).thenReturn(set);
		Jobs.setBrokenPairsToErrorStatus(backend);
		Assert.assertTrue(JobPairs.getPair(job.getJobPairs().get(0).getId()).getStatus().getCode()==Status.StatusCode.STATUS_ENQUEUED);
	}

	@StarexecTest
	private void isPairCorrectIncompleteTest() {
		JoblineStage stage = new JoblineStage();
		stage.getStatus().setCode(StatusCode.STATUS_PENDING_SUBMIT.getVal());
		Assert.assertEquals(-1, JobPairs.isPairCorrect(stage));
	}

	@StarexecTest
	private void isPairCorrectCompleteTest() {
		JoblineStage stage = new JoblineStage();
		Properties attrs = new Properties();
		attrs.setProperty(R.EXPECTED_RESULT, "result");
		attrs.setProperty(R.STAREXEC_RESULT, "result");
		stage.setAttributes(attrs);
		stage.getStatus().setCode(StatusCode.STATUS_COMPLETE.getVal());
		Assert.assertEquals(0, JobPairs.isPairCorrect(stage));
	}

	@StarexecTest
	private void isPairCorrectWrongTest() {
		JoblineStage stage = new JoblineStage();
		Properties attrs = new Properties();
		attrs.setProperty(R.EXPECTED_RESULT, "result");
		attrs.setProperty(R.STAREXEC_RESULT, "wrong");
		stage.setAttributes(attrs);
		stage.getStatus().setCode(StatusCode.STATUS_COMPLETE.getVal());
		Assert.assertEquals(1, JobPairs.isPairCorrect(stage));
	}

	@StarexecTest
	private void isPairCorrectUnknownTest() {
		JoblineStage stage = new JoblineStage();
		Properties attrs = new Properties();
		attrs.setProperty(R.EXPECTED_RESULT, "result");
		attrs.setProperty(R.STAREXEC_RESULT,R.STAREXEC_UNKNOWN);
		stage.setAttributes(attrs);
		stage.getStatus().setCode(StatusCode.STATUS_COMPLETE.getVal());
		Assert.assertEquals(2, JobPairs.isPairCorrect(stage));
	}

	@StarexecTest
	private void isPairCorrectNoExpectedTest() {
		JoblineStage stage = new JoblineStage();
		Properties attrs = new Properties();
		attrs.setProperty(R.STAREXEC_RESULT,"result");
		stage.setAttributes(attrs);
		stage.getStatus().setCode(StatusCode.STATUS_COMPLETE.getVal());
		Assert.assertEquals(2, JobPairs.isPairCorrect(stage));
	}

	@StarexecTest
	private void isPairCorrectExpectedUnknownTest() {
		JoblineStage stage = new JoblineStage();
		Properties attrs = new Properties();
		attrs.setProperty(R.EXPECTED_RESULT, R.STAREXEC_UNKNOWN);
		attrs.setProperty(R.STAREXEC_RESULT, "sat");
		stage.setAttributes(attrs);
		stage.getStatus().setCode(StatusCode.STATUS_COMPLETE.getVal());
		Assert.assertEquals(2, JobPairs.isPairCorrect(stage));
	}

	@StarexecTest
	private void isPairCorrectMissingActualWithConcreteExpectedTest() {
		JoblineStage stage = new JoblineStage();
		Properties attrs = new Properties();
		attrs.setProperty(R.EXPECTED_RESULT, "sat");
		stage.setAttributes(attrs);
		stage.getStatus().setCode(StatusCode.STATUS_COMPLETE.getVal());
		Assert.assertEquals(1, JobPairs.isPairCorrect(stage));
	}

	@StarexecTest
	private void setPairStageStatusTest() {
		JobPair jp = job.getJobPairs().get(0);
		JoblineStage stage = jp.getStages().get(0);
		Assert.assertTrue(JobPairs.setPairStatus(jp.getId(), stage.getStageNumber(), StatusCode.STATUS_UNKNOWN.getVal()));
		Assert.assertEquals(StatusCode.STATUS_UNKNOWN.getVal(), JobPairs.getPairDetailed(jp.getId()).getStages().get(0).getStatus().getCode().getVal());
		JobPairs.setPairStatus(jp.getId(), stage.getStageNumber(), StatusCode.STATUS_COMPLETE.getVal());
	}

	@StarexecTest
	private void setAllPairStageStatusTest() {
		JobPair jp = job.getJobPairs().get(0);
		Assert.assertTrue(JobPairs.setAllPairStageStatus(jp.getId(), StatusCode.STATUS_UNKNOWN.getVal()));
		Assert.assertEquals(StatusCode.STATUS_UNKNOWN.getVal(), JobPairs.getPairDetailed(jp.getId()).getStages().get(0).getStatus().getCode().getVal());
		JobPairs.setAllPairStageStatus(jp.getId(),StatusCode.STATUS_COMPLETE.getVal());
	}

	@StarexecTest
	private void setStatusForPairAndStagesTest() {
		JobPair jp = job.getJobPairs().get(0);
		Assert.assertTrue(JobPairs.setStatusForPairAndStages(jp.getId(), StatusCode.STATUS_KILLED.getVal()));
		JobPair updatedPair = JobPairs.getPairDetailed(jp.getId());
		Assert.assertEquals(StatusCode.STATUS_KILLED.getVal(), updatedPair.getStages().get(0).getStatus().getCode().getVal());
		Assert.assertEquals(StatusCode.STATUS_KILLED.getVal(), updatedPair.getStatus().getCode().getVal());
		JobPairs.setStatusForPairAndStages(jp.getId(),StatusCode.STATUS_COMPLETE.getVal());
	}

	@StarexecTest
	private void getPairsInJobContainingBenchmarkTest() {
		int benchId = job.getJobPairs().get(0).getBench().getId();
		int jobId = job.getId();
		try {
			List<JobPair> jobPairsContainingBench = JobPairs.getPairsInJobContainingBenchmark(jobId, benchId);
			for (JobPair pair : jobPairsContainingBench) {
				Assert.assertEquals("Job pair did not contain benchmark.", pair.getBench().getId(), benchId);
				Assert.assertEquals("Job pair was not in job.", pair.getJobId(), jobId);
			}
		} catch (SQLException e) {
			Assert.fail("SQL Exception: " + Util.getStackTrace(e));
		}
	}

	@StarexecTest
	private void getPairsInJobContainingSolverTest() {
		int solverId = job.getJobPairs().get(0).getPrimaryStage().getSolver().getId();
		int jobId = job.getId();
		try {
			List<JobPair> jobPairsContainingSolver = JobPairs.getPairsInJobContainingSolver(jobId, solverId);
			for (JobPair pair : jobPairsContainingSolver) {
				boolean atLeastOneHadSolver = false;
				for (JoblineStage stage : pair.getStages()) {
					atLeastOneHadSolver = atLeastOneHadSolver || stage.getSolver().getId() == solverId;
				}
				Assert.assertTrue("Pair with id : " + pair.getId() + " did not contain solver with id: " + solverId, atLeastOneHadSolver);
				Assert.assertEquals("Pair was not in job with id: " + jobId, pair.getJobId(), jobId);
			}
		} catch (SQLException e) {
			Assert.fail("SQL Exception: " + Util.getStackTrace(e));
		}
	}

	@Override
	protected void setup() throws Exception {
		user=loader.loadUserIntoDatabase();
		user2=loader.loadUserIntoDatabase();
		nonOwner=loader.loadUserIntoDatabase();
		admin=loader.loadUserIntoDatabase(TestUtil.getRandomAlphaString(10),TestUtil.getRandomAlphaString(10),TestUtil.getRandomPassword(),TestUtil.getRandomPassword(),"The University of Miami",R.ADMIN_ROLE_NAME);
		space=loader.loadSpaceIntoDatabase(user.getId(), Communities.getTestCommunity().getId());
		solver=loader.loadSolverIntoDatabase("CVC4.zip", space.getId(), user.getId());
		postProc=loader.loadProcessorIntoDatabase("postproc.zip", ProcessorType.POST, Communities.getTestCommunity().getId());
		benchmarkIds=loader.loadBenchmarksIntoDatabase("benchmarks.zip",space.getId(),user.getId());

		List<Integer> solverIds= new ArrayList<>();
		solverIds.add(solver.getId());
		job=loader.loadJobIntoDatabase(space.getId(), user.getId(), -1, postProc.getId(), solverIds, benchmarkIds,cpuTimeout,wallclockTimeout,gbMemory);
		job2=loader.loadJobIntoDatabase(space.getId(), user2.getId(), -1, postProc.getId(), solverIds, benchmarkIds, cpuTimeout, wallclockTimeout, gbMemory);
		Assert.assertNotNull(Jobs.get(job.getId()));
	}

	private void resetJobCompletionTime(int jobId) throws SQLException {
		try (Connection con = DatabaseTestAccess.getConnectionForTest();
			 PreparedStatement ps = con.prepareStatement("UPDATE starexec.jobs SET completed = NULL WHERE id = ?")) {
			ps.setInt(1, jobId);
			ps.executeUpdate();
		}
	}

	private boolean pairHasCompletionRecord(int pairId) throws SQLException {
		try (Connection con = DatabaseTestAccess.getConnectionForTest();
			 PreparedStatement ps = con.prepareStatement("SELECT 1 FROM starexec.job_pair_completion WHERE pair_id = ?")) {
			ps.setInt(1, pairId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	private static class DatabaseTestAccess extends Common {
		private static Connection getConnectionForTest() throws SQLException {
			return getConnection();
		}
	}

	@Override
	protected void teardown() throws Exception {
		loader.deleteAllPrimitives();
	}

}
