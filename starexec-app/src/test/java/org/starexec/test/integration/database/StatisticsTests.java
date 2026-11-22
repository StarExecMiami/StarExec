package org.starexec.test.integration.database;

import com.google.gson.JsonObject;
import org.starexec.constants.R;
import org.starexec.data.database.AnonymousLinks.PrimitivesToAnonymize;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Statistics;
import org.starexec.data.to.*;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.junit.jupiter.api.Assertions;

import java.util.HashMap;
import java.util.List;

/**
 * Tests for Statistics.java
 * @author Eric
 *
 */
public class StatisticsTests extends TestSequence {
	
	List<JobPair> spaceOverviewPairs = null;
	
	User owner = null;
	User admin = null;
	Space space = null;
	Solver solver = null;
	List<Integer> benchmarkIds = null;
	Job job = null;
	
	@StarexecTest
	private void makeSpaceOverviewTest() {
		JsonObject data = Statistics.makeSpaceOverviewChartData(spaceOverviewPairs, false, false, 1, PrimitivesToAnonymize.NONE);
		Assertions.assertNotNull(data);
		Assertions.assertEquals("spaceOverview", data.get("chartType").getAsString());
	}
	@StarexecTest
	private void makeSolverComparisonTest() {
		JsonObject data = Statistics.makeSolverComparisonChartData(spaceOverviewPairs, spaceOverviewPairs, job.getPrimarySpace(), 
				1, PrimitivesToAnonymize.NONE);
		Assertions.assertNotNull(data);
		Assertions.assertEquals("solverComparison", data.get("chartType").getAsString());
	}
	
	@StarexecTest
	private void getJobPairOverviewTest() {
		HashMap<String, String> stats = Statistics.getJobPairOverview(job.getId());
		Assertions.assertEquals(String.valueOf(job.getJobPairs().size()), stats.get("totalPairs"));
		Assertions.assertEquals(String.valueOf(job.getJobPairs().size()), stats.get("completePairs"));
		Assertions.assertEquals("0", stats.get("pendingPairs"));
		Assertions.assertEquals("0", stats.get("errorPairs"));

	}

	@StarexecTest
	private void getJobPairOverviewPausedJobCountsAsIncompleteTest() throws Exception {
		Job pausedJob = loader.loadJobIntoDatabase(space.getId(), owner.getId(), solver.getId(), benchmarkIds);
		try {
			for (JobPair pair : pausedJob.getJobPairs()) {
				Assertions.assertTrue(
						JobPairs.setStatusForPairAndStages(pair.getId(), StatusCode.STATUS_PENDING_SUBMIT.getVal())
				);
			}
			Assertions.assertTrue(Jobs.pause(pausedJob.getId()));

			HashMap<String, String> stats = Statistics.getJobPairOverview(pausedJob.getId());
			Assertions.assertEquals(String.valueOf(pausedJob.getJobPairs().size()), stats.get("totalPairs"));
			Assertions.assertEquals("0", stats.get("completePairs"));
			Assertions.assertEquals(String.valueOf(pausedJob.getJobPairs().size()), stats.get("pendingPairs"));
			Assertions.assertEquals("0", stats.get("errorPairs"));
		} finally {
			Jobs.deleteAndRemove(pausedJob.getId());
		}
	}
	
	@Override
	protected String getTestName() {
		return "StatisticsTests";
	}

	@Override
	protected void setup() throws Exception {
		spaceOverviewPairs = TestUtil.getFakeJobPairs(2000);
		owner = loader.loadUserIntoDatabase();
		admin = loader.loadUserIntoDatabase(TestUtil.getRandomAlphaString(10),TestUtil.getRandomAlphaString(10),TestUtil.getRandomPassword(),TestUtil.getRandomPassword(),"The University of Miami",R.ADMIN_ROLE_NAME);
		space = loader.loadSpaceIntoDatabase(owner.getId(), 1);
		solver = loader.loadSolverIntoDatabase(space.getId(), owner.getId());
		benchmarkIds = loader.loadBenchmarksIntoDatabase(space.getId(), owner.getId());
		job = loader.loadJobIntoDatabase(space.getId(), owner.getId(), solver.getId(), benchmarkIds);
	}

	@Override
	protected void teardown() throws Exception {
		loader.deleteAllPrimitives();
	}

}
