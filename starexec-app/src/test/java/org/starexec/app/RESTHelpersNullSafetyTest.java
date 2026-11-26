package org.starexec.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.AnonymousLinks.PrimitivesToAnonymize;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Spaces;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.JobSpace;
import org.starexec.data.to.Solver;
import org.starexec.data.to.SolverStats;
import org.starexec.data.to.pipelines.JoblineStage;
import org.starexec.exceptions.RESTException;
import org.starexec.util.DataTablesQuery;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;

public class RESTHelpersNullSafetyTest {

	@Test
	public void convertSolversToJsonObjectHandlesNullSolverType() {
		Solver solver = new Solver();
		solver.setId(15);
		solver.setName("legacy-solver");
		solver.setType(null);

		JsonObject obj = RESTHelpers.convertSolversToJsonObject(List.of(solver), new DataTablesQuery(), -1);
		JsonArray row = obj.getAsJsonArray("aaData").get(0).getAsJsonArray();
		assertEquals("unknown", row.get(2).getAsString());
	}

	@Test
	public void convertJobPairsToJsonObjectHandlesMissingStageSolverAndStatus() {
		JobPair pairMissingStage = new JobPair();
		pairMissingStage.setId(101);
		pairMissingStage.setBench(null);
		pairMissingStage.setStages(List.of());

		JoblineStage emptyStage = new JoblineStage();
		emptyStage.setStageNumber(1);
		emptyStage.setSolver(null);
		emptyStage.setStatus(null);
		JobPair pairEmptyStage = new JobPair();
		pairEmptyStage.setId(102);
		pairEmptyStage.setBench(null);
		pairEmptyStage.setStages(List.of(emptyStage));

		JsonObject obj = assertDoesNotThrow(() -> RESTHelpers.convertJobPairsToJsonObject(
				List.of(pairMissingStage, pairEmptyStage),
				new DataTablesQuery(),
				true,
				1,
				PrimitivesToAnonymize.NONE));
		JsonArray rows = obj.getAsJsonArray("aaData");
		assertEquals(2, rows.size());
		assertEquals("N/A", rows.get(0).getAsJsonArray().get(0).getAsString());
		assertEquals("N/A", rows.get(0).getAsJsonArray().get(1).getAsString());
		assertEquals("N/A", rows.get(0).getAsJsonArray().get(2).getAsString());
		assertEquals("N/A", rows.get(1).getAsJsonArray().get(1).getAsString());
	}

	@Test
	public void convertJobPairsToJsonObjectClusterHandlesNullOwningJobUserAndPrimarySolver() {
		JobPair pair = new JobPair();
		pair.setId(201);
		pair.setJobId(9);
		pair.setQueueSubmitTime(new Timestamp(0L));
		pair.setOwningJob(null);
		pair.setOwningUser(null);
		pair.setBench(null);
		pair.setStages(List.of());

		JsonObject obj = assertDoesNotThrow(() -> RESTHelpers.convertJobPairsToJsonObjectCluster(
				List.of(pair), new DataTablesQuery(), 1));
		JsonArray row = obj.getAsJsonArray("aaData").get(0).getAsJsonArray();
		assertEquals("N/A", row.get(2).getAsString());
		assertEquals("N/A", row.get(3).getAsString());
		assertEquals("N/A", row.get(4).getAsString());
		assertEquals("N/A", row.get(5).getAsString());
	}

	@Test
	public void convertSolverStatsToJsonObjectHandlesNullSolverAndConfiguration() {
		SolverStats stats = new SolverStats();
		stats.setSolver(null);
		stats.setConfiguration(null);
		JsonObject obj = assertDoesNotThrow(() -> RESTHelpers.convertSolverStatsToJsonObject(
				List.of(stats), new DataTablesQuery(), 1, 2, true, true, PrimitivesToAnonymize.NONE));
		JsonArray row = obj.getAsJsonArray("aaData").get(0).getAsJsonArray();
		assertEquals(-1, row.get(0).getAsInt());
		assertEquals(-1, row.get(1).getAsInt());
		assertEquals("", row.get(2).getAsString());
		assertEquals("", row.get(3).getAsString());
	}

	@Test
	public void getJobSpacesJsonHandlesMissingRootJobSpaceWithoutNpe() {
		try (MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class);
			 MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class)) {
			Job job = new Job();
			job.setId(55);
			job.setPrimarySpace(77);
			jobs.when(() -> Jobs.get(55)).thenReturn(job);
			spaces.when(() -> Spaces.getJobSpace(77)).thenReturn(null);

			String json = assertDoesNotThrow(() -> RESTHelpers.getJobSpacesJson(0, 55, false));
			assertEquals("[]", json);
		}
	}

	@Test
	public void getFinishedJobPairsForMatrixHandlesNullPrimitivesAndMissingJobSpace() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<JobSecurity> jobSecurity = Mockito.mockStatic(JobSecurity.class);
			 MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class);
			 MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(1);
			jobSecurity.when(() -> JobSecurity.canUserSeeJobSpace(10, 1)).thenReturn(new ValidatorStatusCode(true));
			spaces.when(() -> Spaces.getJobSpace(10)).thenReturn(null);

			RESTException missing = assertThrows(RESTException.class,
					() -> new RESTServices().getFinishedJobPairsForMatrix(10, 1, request));
			assertSame(RESTException.NOT_FOUND, missing);

			JobSpace js = new JobSpace();
			js.setId(10);
			js.setJobId(77);
			spaces.when(() -> Spaces.getJobSpace(10)).thenReturn(js);

			JoblineStage stage = new JoblineStage();
			stage.setStageNumber(1);
			stage.setSolver(null);
			stage.setConfiguration(null);
			JobPair pair = new JobPair();
			pair.setId(88);
			pair.setBench(null);
			pair.setStages(List.of(stage));

			jobs.when(() -> Jobs.getNewCompletedPairsDetailed(77, 0)).thenReturn(List.of(pair));
			jobs.when(() -> Jobs.getStatusFromStage(stage)).thenReturn("complete");
			jobs.when(() -> Jobs.isJobComplete(77)).thenReturn(true);

			String response = assertDoesNotThrow(() -> new RESTServices().getFinishedJobPairsForMatrix(10, 1, request));
			JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
			assertTrue(obj.get("done").getAsBoolean());
			assertEquals(1, obj.getAsJsonObject("benchSolverConfigElementMap").size());
		}
	}

	@Test
	public void getSlotsInJobQueueHandlesNullQueueWithoutNpe() {
		Job job = new Job();
		job.setQueue(null);
		assertEquals(R.DEFAULT_QUEUE_SLOTS, Jobs.getSlotsInJobQueue(job));
	}
}
