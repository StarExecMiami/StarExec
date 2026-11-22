package org.starexec.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Statistics;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.SessionUtil;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Verifies structured JSON graph responses, error refusals, and route compatibility.
 */
public class JobGraphResponseTest {

	private static final int USER_ID = 7;
	private static final int JOB_ID = 11;
	private static final int JOB_SPACE_ID = 13;
	private static final int STAGE = 1;

	@BeforeAll
	public static void initialize() {
		Validator.initialize();
	}

	/** Runs one request as a user who may see the job, with every chart builder stubbed. */
	private interface GraphRequest {
		String run(MockedStatic<Statistics> statistics);
	}

	private static String answer(GraphRequest request) {
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<JobSecurity> security = Mockito.mockStatic(JobSecurity.class);
				MockedStatic<Statistics> statistics = Mockito.mockStatic(Statistics.class)) {
			session.when(() -> SessionUtil.getUserId(any())).thenReturn(USER_ID);
			security.when(() -> JobSecurity.canUserSeeJobSpace(anyInt(), anyInt()))
					.thenReturn(new ValidatorStatusCode(true));
			security.when(() -> JobSecurity.canUserSeeJob(anyInt(), anyInt()))
					.thenReturn(new ValidatorStatusCode(true));
			security.when(() -> JobSecurity.isAnonymousLinkAssociatedWithJobSpace(anyString(), anyInt()))
					.thenReturn(new ValidatorStatusCode(true));
			return request.run(statistics);
		}
	}

	private static HttpServletRequest overviewRequest(String... selectedIds) {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getParameterValues("selectedIds[]")).thenReturn(selectedIds);
		return request;
	}

	private static String comparison() {
		return new RESTServices().getSolverComparisonGraph(STAGE, JOB_SPACE_ID, 1, 2,
				Mockito.mock(HttpServletRequest.class));
	}

	private static void assertRefusedWith(String expectedMessage, String json) {
		JsonObject response = JsonParser.parseString(json).getAsJsonObject();
		assertFalse(response.get("success").getAsBoolean(),
				"a graph that cannot be drawn must not be reported as drawn: " + json);
		assertEquals(expectedMessage, response.get("message").getAsString(), json);
	}

	private static JsonObject errorData(String code) {
		JsonObject error = new JsonObject();
		error.addProperty("error", code);
		return error;
	}

	@Test
	public void anOverviewWithNoConfigurationSelectedSaysSoWithoutDrawing() {
		String json = answer(statistics -> {
			String response = new RESTServices().getSpaceOverviewGraph(STAGE, JOB_SPACE_ID, overviewRequest((String[]) null));
			statistics.verifyNoInteractions();
			return response;
		});
		assertRefusedWith(RESTHelpers.NO_CONFIGURATION_SELECTED, json);
	}

	@Test
	public void anOverviewThatFailsToDrawAnswersADatabaseError() {
		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeSpaceOverviewChartData(anyInt(), anyBoolean(), anyBoolean(), anyList(),
					anyInt(), any())).thenReturn(null);
			return new RESTServices().getSpaceOverviewGraph(STAGE, JOB_SPACE_ID, overviewRequest("5"));
		});
		assertRefusedWith(RESTServices.ERROR_DATABASE.getMessage(), json);
	}

	@Test
	public void anOversizedOverviewAnswersTooManyJobPairs() {
		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeSpaceOverviewChartData(anyInt(), anyBoolean(), anyBoolean(), anyList(),
					anyInt(), any())).thenReturn(errorData(Statistics.OVERSIZED_GRAPH_ERROR));
			return new RESTServices().getSpaceOverviewGraph(STAGE, JOB_SPACE_ID, overviewRequest("5"));
		});
		assertRefusedWith(RESTServices.ERROR_TOO_MANY_JOB_PAIRS.getMessage(), json);
	}

	@Test
	public void aDrawnOverviewReturnsStructuredSeriesJson() {
		JsonObject payload = new JsonObject();
		payload.addProperty("chartType", "spaceOverview");
		payload.add("series", new JsonArray());

		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeSpaceOverviewChartData(anyInt(), anyBoolean(), anyBoolean(), anyList(),
					anyInt(), any())).thenReturn(payload);
			return new RESTServices().getSpaceOverviewGraph(STAGE, JOB_SPACE_ID, overviewRequest("5", "6"));
		});
		JsonObject response = JsonParser.parseString(json).getAsJsonObject();
		assertEquals("spaceOverview", response.get("chartType").getAsString());
	}

	@Test
	public void aPairTimeGraphThatFailsToDrawAnswersADatabaseError() {
		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeJobTimeGraphData(anyInt())).thenReturn(null);
			return new RESTServices().getPairTimeGraph(JOB_ID, Mockito.mock(HttpServletRequest.class));
		});
		assertRefusedWith(RESTServices.ERROR_DATABASE.getMessage(), json);
	}

	@Test
	public void aDrawnPairTimeGraphReturnsStructuredPointsJson() {
		JsonObject payload = new JsonObject();
		payload.addProperty("chartType", "pairTime");
		payload.addProperty("totalPairs", 4);
		payload.add("points", new JsonArray());

		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeJobTimeGraphData(JOB_ID)).thenReturn(payload);
			return new RESTServices().getPairTimeGraph(JOB_ID, Mockito.mock(HttpServletRequest.class));
		});
		JsonObject response = JsonParser.parseString(json).getAsJsonObject();
		assertEquals("pairTime", response.get("chartType").getAsString());
		assertEquals(4, response.get("totalPairs").getAsInt());
	}

	@Test
	public void aComparisonWithNothingToPlotSaysSoInsteadOfADatabaseError() {
		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeSolverComparisonChartData(anyInt(), anyInt(), anyInt(),
					anyInt(), any())).thenReturn(errorData(Statistics.NO_COMPARABLE_PAIRS_ERROR));
			return comparison();
		});
		assertRefusedWith(RESTHelpers.NO_COMPARABLE_PAIRS, json);
	}

	@Test
	public void aComparisonThatFailsStillAnswersADatabaseError() {
		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeSolverComparisonChartData(anyInt(), anyInt(), anyInt(),
					anyInt(), any())).thenReturn(null);
			return comparison();
		});
		assertRefusedWith(RESTServices.ERROR_DATABASE.getMessage(), json);
	}

	@Test
	public void aComparisonOfAConfigurationWithItselfAsksForTwoDifferentOnes() {
		String json = answer(statistics -> {
			String response = new RESTServices().getSolverComparisonGraph(STAGE, JOB_SPACE_ID, 5, 5,
					Mockito.mock(HttpServletRequest.class));
			statistics.verifyNoInteractions();
			return response;
		});
		assertRefusedWith(RESTHelpers.SAME_CONFIGURATION_TWICE, json);
	}

	@Test
	public void aDrawnComparisonReturnsStructuredPointsOnBothNewAndLegacyRoutes() {
		JsonObject payload = new JsonObject();
		payload.addProperty("chartType", "solverComparison");
		payload.addProperty("xLabel", "S1/c1 time(s)");
		payload.addProperty("yLabel", "S2/c2 time(s)");
		payload.add("points", new JsonArray());

		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeSolverComparisonChartData(anyInt(), anyInt(), anyInt(),
					anyInt(), any())).thenReturn(payload);
			return comparison();
		});
		String legacyJson = answer(statistics -> {
			statistics.when(() -> Statistics.makeSolverComparisonChartData(anyInt(), anyInt(), anyInt(),
					anyInt(), any())).thenReturn(payload);
			return new RESTServices().getSolverComparisonGraph(STAGE, JOB_SPACE_ID, 1, 2, 300, "white",
					Mockito.mock(HttpServletRequest.class));
		});
		String anonJson = answer(statistics -> {
			statistics.when(() -> Statistics.makeSolverComparisonChartData(anyInt(), anyInt(), anyInt(),
					anyInt(), any())).thenReturn(payload);
			return new RESTServices().getAnonymousSolverComparisonGraph("anon-uuid", STAGE, JOB_SPACE_ID,
					1, 2, "all", Mockito.mock(HttpServletRequest.class));
		});
		assertEquals(json, legacyJson);
		assertEquals(json, anonJson);
		JsonObject response = JsonParser.parseString(json).getAsJsonObject();
		assertEquals("solverComparison", response.get("chartType").getAsString(), json);
	}
}
