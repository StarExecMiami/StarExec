package org.starexec.test.junit.util.matrixView;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Configuration;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.JobSpace;
import org.starexec.data.to.Solver;
import org.starexec.data.to.pipelines.JoblineStage;
import org.starexec.util.matrixView.Matrix;
import org.starexec.util.matrixView.MatrixElement;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

public class MatrixUnitTests {

	private static JobPair buildPair(int pairId, int jobSpaceId, int benchId, String benchName,
			int solverId, String solverName, int configId, String configName, int stageNumber) {
		Benchmark bench = new Benchmark();
		bench.setId(benchId);
		bench.setName(benchName);

		Solver solver = new Solver();
		solver.setId(solverId);
		solver.setName(solverName);

		Configuration config = new Configuration();
		config.setId(configId);
		config.setName(configName);

		JoblineStage stage = new JoblineStage();
		stage.setStageNumber(stageNumber);
		stage.setSolver(solver);
		stage.setConfiguration(config);
		stage.setCpuUsage(1.5);
		stage.setWallclockTime(2.0);
		stage.setMaxVirtualMemory(128.0);

		JobPair pair = new JobPair();
		pair.setId(pairId);
		pair.setJobSpaceId(jobSpaceId);
		pair.setBench(bench);
		pair.setStages(Collections.singletonList(stage));
		return pair;
	}

	@Test
	void filtersPairsByJobSpaceIdAndPreservesSpecialIdentifierCharacters() throws Exception {
		JobSpace space = new JobSpace();
		space.setId(10);
		space.setName("root");

		JobPair matching = buildPair(101, 10, 20, "SEU961^5.p", 30, "cvc5", 40, "default", 1);
		JobPair otherSpace = buildPair(102, 99, 21, "other.p", 30, "cvc5", 40, "default", 1);

		Job job = new Job();
		job.setId(5);
		job.setJobPairs(Arrays.asList(matching, otherSpace));

		try (MockedStatic<Spaces> spaces = mockStatic(Spaces.class);
			 MockedStatic<Jobs> jobs = mockStatic(Jobs.class)) {
			spaces.when(() -> Spaces.getJobSpace(10)).thenReturn(space);
			jobs.when(() -> Jobs.getStatusFromStage(any(JoblineStage.class))).thenReturn("solved");

			Matrix matrix = Matrix.getMatrixForJobSpaceFromJobAndStageNumber(job, 10, 1);

			assertEquals(1, matrix.getBenchmarksByRow().size());
			assertEquals("SEU961^5.p", matrix.getBenchmarksByRow().get(0).getName());
			assertEquals(1, matrix.getSolverConfigsByColumn().size());
			MatrixElement cell = matrix.getInternalMatrixRepresentation().get(0).get(0);
			assertEquals("SEU961^5.p20-cvc530-default40", cell.getUniqueIdentifier());
		}
	}

	@Test
	void returnsEmptyMatrixWhenNoPairsMatchSpaceOrStage() throws Exception {
		JobSpace space = new JobSpace();
		space.setId(10);
		space.setName("empty");

		JobPair otherSpace = buildPair(102, 99, 21, "other.p", 30, "cvc5", 40, "default", 1);

		Job job = new Job();
		job.setId(5);
		job.setJobPairs(Collections.singletonList(otherSpace));

		try (MockedStatic<Spaces> spaces = mockStatic(Spaces.class)) {
			spaces.when(() -> Spaces.getJobSpace(10)).thenReturn(space);

			Matrix matrix = Matrix.getMatrixForJobSpaceFromJobAndStageNumber(job, 10, 1);

			assertTrue(matrix.getBenchmarksByRow().isEmpty());
			assertTrue(matrix.getSolverConfigsByColumn().isEmpty());
			assertTrue(matrix.getInternalMatrixRepresentation().isEmpty());
		}
	}
}
