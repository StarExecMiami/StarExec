package org.starexec.data.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.AnonymousLinks.PrimitivesToAnonymize;
import org.starexec.data.to.Configuration;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Solver;
import org.starexec.data.to.Status;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.data.to.pipelines.JoblineStage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * Unit tests for structured pair-time and space-overview chart data builders in {@link Statistics}.
 */
public class JobChartDataTest {

	private static JobPair pairWithTime(int solverId, String solverName, int configId, String configName,
			double wallclockSeconds, StatusCode statusCode) {
		Solver solver = new Solver();
		solver.setId(solverId);
		solver.setName(solverName);

		Configuration config = new Configuration();
		config.setId(configId);
		config.setName(configName);

		JoblineStage stage = new JoblineStage();
		stage.setStageNumber(1);
		stage.setSolver(solver);
		stage.setConfiguration(config);
		stage.setWallclockTime(wallclockSeconds);

		Status status = new Status();
		status.setCode(statusCode);

		JobPair pair = new JobPair();
		pair.setStatus(status);
		pair.addStage(stage);
		return pair;
	}

	@Test
	public void jobTimeGraphSortsTimestampsAndKeepsTotalPairCount() {
		List<Date> unordered = new ArrayList<>(Arrays.asList(
				new Date(2000L),
				null,
				new Date(1000L)));
		try (MockedStatic<Statistics> stats = Mockito.mockStatic(Statistics.class, Mockito.CALLS_REAL_METHODS)) {
			stats.when(() -> Statistics.getPairTimes(42)).thenReturn(unordered);
			JsonObject data = Statistics.makeJobTimeGraphData(42);
			assertEquals("pairTime", data.get("chartType").getAsString());
			assertEquals(3, data.get("totalPairs").getAsInt());
			JsonArray points = data.getAsJsonArray("points");
			assertEquals(2, points.size());
			assertEquals(1000L, points.get(0).getAsJsonObject().get("x").getAsLong());
			assertEquals(1, points.get(0).getAsJsonObject().get("y").getAsInt());
			assertEquals(2000L, points.get(1).getAsJsonObject().get("x").getAsLong());
			assertEquals(2, points.get(1).getAsJsonObject().get("y").getAsInt());
		}
	}

	@Test
	public void jobTimeGraphReturnsNullWhenQueryFails() {
		try (MockedStatic<Statistics> stats = Mockito.mockStatic(Statistics.class, Mockito.CALLS_REAL_METHODS)) {
			stats.when(() -> Statistics.getPairTimes(42)).thenReturn(null);
			assertNull(Statistics.makeJobTimeGraphData(42));
		}
	}

	@Test
	public void spaceOverviewSortsTimesBuildsCumulativeCountsAndHonorsSolverAnonymization() {
		List<JobPair> pairs = List.of(
				pairWithTime(5, "CVC5", 10, "default", 4.0, StatusCode.STATUS_COMPLETE),
				pairWithTime(5, "CVC5", 10, "default", 1.0, StatusCode.STATUS_COMPLETE),
				pairWithTime(5, "CVC5", 10, "default", 9.0, StatusCode.STATUS_PENDING_SUBMIT));

		JsonObject normal = Statistics.makeSpaceOverviewChartData(pairs, false, true, 1, PrimitivesToAnonymize.NONE);
		assertEquals("spaceOverview", normal.get("chartType").getAsString());
		assertTrue(normal.get("logY").getAsBoolean());
		JsonObject series = normal.getAsJsonArray("series").get(0).getAsJsonObject();
		assertEquals("CVC5(5) config = default", series.get("label").getAsString());
		JsonArray points = series.getAsJsonArray("points");
		assertEquals(2, points.size());
		assertEquals(1, points.get(0).getAsJsonObject().get("x").getAsInt());
		assertEquals(1.0, points.get(0).getAsJsonObject().get("y").getAsDouble());
		assertEquals(2, points.get(1).getAsJsonObject().get("x").getAsInt());
		assertEquals(4.0, points.get(1).getAsJsonObject().get("y").getAsDouble());

		JsonObject anonymized = Statistics.makeSpaceOverviewChartData(pairs, false, false, 1,
				PrimitivesToAnonymize.ALL);
		String anonLabel = anonymized.getAsJsonArray("series").get(0).getAsJsonObject().get("label").getAsString();
		assertEquals("CVC5 config = default", anonLabel);
	}

	@Test
	public void spaceOverviewEnforcesMaximumDataPointsLimit() {
		List<JobPair> tooMany = new ArrayList<>(R.MAXIMUM_DATA_POINTS + 1);
		JobPair stub = Mockito.mock(JobPair.class);
		for (int i = 0; i <= R.MAXIMUM_DATA_POINTS; i++) {
			tooMany.add(stub);
		}
		try (MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {
			jobs.when(() -> Jobs.getJobPairsForSolverComparisonGraph(anyInt(), anyList(), anyInt(), any()))
					.thenReturn(List.of(tooMany));
			JsonObject result = Statistics.makeSpaceOverviewChartData(7, false, false, List.of(10), 1,
					PrimitivesToAnonymize.NONE);
			assertEquals(Statistics.OVERSIZED_GRAPH_ERROR, result.get("error").getAsString());
		}
	}
}
