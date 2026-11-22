package org.starexec.data.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.AnonymousLinks.PrimitivesToAnonymize;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Configuration;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.JobSpace;
import org.starexec.data.to.Solver;
import org.starexec.data.to.Status;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.data.to.pipelines.JoblineStage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * Verifies solver-comparison and space-overview chart data generation, limit enforcement, and anonymization.
 *
 * <p>getJobPairsForSolverComparisonGraph keeps only the pairs the graph plots: completed pairs whose
 * result matches the benchmark's expected result. When one configuration had none, the chart builder
 * returned null, the same answer as a failed query, and the job page reported an internal database
 * error. A failed query must still be answered with null.
 */
public class SolverComparisonChartTest {

	private static JsonObject compare(List<JobPair> first, List<JobPair> second) {
		List<List<JobPair>> lists = new ArrayList<>();
		lists.add(first);
		lists.add(second);
		return compareLists(lists);
	}

	private static JsonObject compareLists(List<List<JobPair>> lists) {
		try (MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {
			jobs.when(() -> Jobs.getJobPairsForSolverComparisonGraph(anyInt(), anyList(), anyInt(), any()))
					.thenReturn(lists);
			return Statistics.makeSolverComparisonChartData(1, 2, 3, 1, PrimitivesToAnonymize.NONE);
		}
	}

	private static List<JobPair> onePair() {
		List<JobPair> pairs = new ArrayList<>();
		pairs.add(Mockito.mock(JobPair.class));
		return pairs;
	}

	private static JobPair completedPair(int benchId, String benchName, int solverId, String solverName,
			String configName, double wallclockSeconds) {
		Benchmark bench = new Benchmark();
		bench.setId(benchId);
		bench.setName(benchName);

		Solver solver = new Solver();
		solver.setId(solverId);
		solver.setName(solverName);

		Configuration config = new Configuration();
		config.setId(solverId * 10);
		config.setName(configName);

		JoblineStage stage = new JoblineStage();
		stage.setStageNumber(1);
		stage.setSolver(solver);
		stage.setConfiguration(config);
		stage.setWallclockTime(wallclockSeconds);

		Status status = new Status();
		status.setCode(StatusCode.STATUS_COMPLETE);

		JobPair pair = new JobPair();
		pair.setBench(bench);
		pair.setStatus(status);
		pair.addStage(stage);
		return pair;
	}

	@Test
	public void aFirstConfigurationWithNothingToPlotIsNothingToCompare() {
		JsonObject result = compare(new ArrayList<>(), onePair());
		assertEquals(Statistics.NO_COMPARABLE_PAIRS_ERROR, result.get("error").getAsString());
	}

	@Test
	public void aSecondConfigurationWithNothingToPlotIsNothingToCompare() {
		JsonObject result = compare(onePair(), new ArrayList<>());
		assertEquals(Statistics.NO_COMPARABLE_PAIRS_ERROR, result.get("error").getAsString());
	}

	@Test
	public void aFailedQueryIsStillAFailure() {
		assertNull(compareLists(null));
	}

	@Test
	public void oversizedComparisonReturnsBigError() {
		List<JobPair> tooMany = new ArrayList<>(R.MAXIMUM_DATA_POINTS + 1);
		JobPair stub = Mockito.mock(JobPair.class);
		for (int i = 0; i <= R.MAXIMUM_DATA_POINTS; i++) {
			tooMany.add(stub);
		}
		JsonObject result = compare(tooMany, onePair());
		assertEquals(Statistics.OVERSIZED_GRAPH_ERROR, result.get("error").getAsString());
	}

	@Test
	public void nonAnonymizedComparisonIncludesBenchmarkIdsAndSolverLabels() {
		List<JobPair> p1 = List.of(completedPair(701, "sat-1.smt2", 11, "CVC5", "default", 1.5));
		List<JobPair> p2 = List.of(completedPair(701, "sat-1.smt2", 12, "Z3", "fast", 2.5));
		JsonObject data = compare(p1, p2);

		assertEquals("solverComparison", data.get("chartType").getAsString());
		assertEquals("CVC5/default time(s)", data.get("xLabel").getAsString());
		assertEquals("Z3/fast time(s)", data.get("yLabel").getAsString());
		assertFalse(data.get("benchmarksAnonymized").getAsBoolean());

		JsonArray points = data.getAsJsonArray("points");
		assertEquals(1, points.size());
		JsonObject point = points.get(0).getAsJsonObject();
		assertEquals(1.5, point.get("x").getAsDouble());
		assertEquals(2.5, point.get("y").getAsDouble());
		assertEquals("sat-1.smt2", point.get("name").getAsString());
		assertEquals(701, point.get("benchmarkId").getAsInt());
	}

	@Test
	public void anonymizedComparisonOmitsBenchmarkIdsAndUsesAnonymizedNames() {
		List<JobPair> p1 = List.of(completedPair(701, "secret-bench.smt2", 11, "RealSolverA", "cfgA", 1.2));
		List<JobPair> p2 = List.of(completedPair(701, "secret-bench.smt2", 12, "RealSolverB", "cfgB", 3.4));
		JobSpace jobSpace = new JobSpace();
		jobSpace.setId(99);
		jobSpace.setJobId(500);

		try (MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class);
				MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class);
				MockedStatic<AnonymousLinks> anon = Mockito.mockStatic(AnonymousLinks.class, Mockito.CALLS_REAL_METHODS)) {
			jobs.when(() -> Jobs.getJobPairsForSolverComparisonGraph(anyInt(), anyList(), anyInt(), any()))
					.thenReturn(List.of(p1, p2));
			spaces.when(() -> Spaces.getJobSpace(99)).thenReturn(jobSpace);
			anon.when(() -> AnonymousLinks.getAnonymizedSolverNames(500, 1))
					.thenReturn(Map.of(11, "Solver 1", 12, "Solver 2"));
			anon.when(() -> AnonymousLinks.getAnonymizedBenchmarkNames(anyList()))
					.thenReturn(Map.of(701, "Benchmark 1"));

			JsonObject data = Statistics.makeSolverComparisonChartData(1, 2, 99, 1, PrimitivesToAnonymize.ALL);
			assertEquals("Solver 1 time(s)", data.get("xLabel").getAsString());
			assertEquals("Solver 2 time(s)", data.get("yLabel").getAsString());
			assertTrue(data.get("benchmarksAnonymized").getAsBoolean());

			JsonObject point = data.getAsJsonArray("points").get(0).getAsJsonObject();
			assertEquals("Benchmark 1", point.get("name").getAsString());
			assertFalse(point.has("benchmarkId"), "benchmarkId must be omitted when benchmarks are anonymized");
		}
	}
}
