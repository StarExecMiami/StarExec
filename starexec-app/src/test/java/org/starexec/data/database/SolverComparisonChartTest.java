package org.starexec.data.database;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.AnonymousLinks.PrimitivesToAnonymize;
import org.starexec.data.to.JobPair;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * A solver comparison with nothing to plot is not a failure.
 *
 * <p>getJobPairsForSolverComparisonGraph keeps only the pairs the graph plots: completed pairs whose
 * result matches the benchmark's expected result. When one configuration had none, the chart builder
 * returned null, the same answer as a failed query, and the job page reported an internal database
 * error. A failed query must still be answered with null.
 */
public class SolverComparisonChartTest {

	private static List<String> compare(List<JobPair> first, List<JobPair> second) {
		List<List<JobPair>> lists = new ArrayList<>();
		lists.add(first);
		lists.add(second);
		return compareLists(lists);
	}

	private static List<String> compareLists(List<List<JobPair>> lists) {
		try (MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {
			jobs.when(() -> Jobs.getJobPairsForSolverComparisonGraph(anyInt(), anyList(), anyInt(), any()))
					.thenReturn(lists);
			return Statistics.makeSolverComparisonChart(1, 2, 3, 300, Color.BLACK, 1, PrimitivesToAnonymize.NONE);
		}
	}

	private static List<JobPair> onePair() {
		List<JobPair> pairs = new ArrayList<>();
		pairs.add(Mockito.mock(JobPair.class));
		return pairs;
	}

	@Test
	public void aFirstConfigurationWithNothingToPlotIsNothingToCompare() {
		assertEquals(List.of(Statistics.NO_COMPARABLE_PAIRS_ERROR), compare(new ArrayList<>(), onePair()));
	}

	@Test
	public void aSecondConfigurationWithNothingToPlotIsNothingToCompare() {
		assertEquals(List.of(Statistics.NO_COMPARABLE_PAIRS_ERROR), compare(onePair(), new ArrayList<>()));
	}

	@Test
	public void aFailedQueryIsStillAFailure() {
		assertNull(compareLists(null));
	}
}
