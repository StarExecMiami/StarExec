package org.starexec.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Statistics;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.SessionUtil;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * A job graph that cannot be drawn must be answered with a status the job page can show.
 *
 * <p>With no configuration selected, the space overview graph answered HTTP 500: makeSpaceOverviewChart
 * returns null for an empty selection, and the helper dereferenced the result before checking it. The
 * pairs-over-time graph did the same whenever drawing failed. The solver comparison graph reported an
 * internal database error when one configuration had no pair to plot, which is not a failure, and said
 * that a configuration compared with itself had none.
 */
public class JobGraphResponseTest {

	private static final int USER_ID = 7;
	private static final int JOB_ID = 11;
	private static final int JOB_SPACE_ID = 13;
	private static final int STAGE = 1;

	@BeforeClass
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
			return request.run(statistics);
		}
	}

	private static HttpServletRequest overviewRequest(String... selectedIds) {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getParameterValues("selectedIds[]")).thenReturn(selectedIds);
		return request;
	}

	private static String comparison() {
		return new RESTServices().getSolverComparisonGraph(STAGE, JOB_SPACE_ID, 1, 2, 300, "white",
				Mockito.mock(HttpServletRequest.class));
	}

	private static void assertRefusedWith(String expectedMessage, String json) {
		JsonObject response = JsonParser.parseString(json).getAsJsonObject();
		assertFalse("a graph that cannot be drawn must not be reported as drawn: " + json,
				response.get("success").getAsBoolean());
		assertEquals(json, expectedMessage, response.get("message").getAsString());
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
		// makeSpaceOverviewChart returns null when its query fails.
		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeSpaceOverviewChart(anyInt(), anyBoolean(), anyBoolean(), anyList(),
					anyInt(), any())).thenReturn(null);
			return new RESTServices().getSpaceOverviewGraph(STAGE, JOB_SPACE_ID, overviewRequest("5"));
		});
		assertRefusedWith(RESTServices.ERROR_DATABASE.getMessage(), json);
	}

	/** The job page reads a drawn overview as the chart's path in plain text; that answer is unchanged. */
	@Test
	public void aDrawnOverviewIsStillAnsweredWithItsPath() {
		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeSpaceOverviewChart(anyInt(), anyBoolean(), anyBoolean(), anyList(),
					anyInt(), any())).thenReturn("/starexec/jobgraphs/overview");
			return new RESTServices().getSpaceOverviewGraph(STAGE, JOB_SPACE_ID, overviewRequest("5", "6"));
		});
		assertEquals("/starexec/jobgraphs/overview", json);
	}

	@Test
	public void aPairTimeGraphThatFailsToDrawAnswersADatabaseError() {
		// makeJobTimeGraph returns null whenever drawing fails.
		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeJobTimeGraph(anyInt())).thenReturn(null);
			return new RESTServices().getPairTimeGraph(JOB_ID, Mockito.mock(HttpServletRequest.class));
		});
		assertRefusedWith(RESTServices.ERROR_DATABASE.getMessage(), json);
	}

	@Test
	public void aComparisonWithNothingToPlotSaysSoInsteadOfADatabaseError() {
		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeSolverComparisonChart(anyInt(), anyInt(), anyInt(), anyInt(), any(),
					anyInt(), any())).thenReturn(List.of(Statistics.NO_COMPARABLE_PAIRS_ERROR));
			return comparison();
		});
		assertRefusedWith(RESTHelpers.NO_COMPARABLE_PAIRS, json);
	}

	@Test
	public void aComparisonThatFailsStillAnswersADatabaseError() {
		String json = answer(statistics -> {
			// Stubbed explicitly: an unstubbed static mock answers a List method with an empty list, not null.
			statistics.when(() -> Statistics.makeSolverComparisonChart(anyInt(), anyInt(), anyInt(), anyInt(), any(),
					anyInt(), any())).thenReturn(null);
			return comparison();
		});
		assertRefusedWith(RESTServices.ERROR_DATABASE.getMessage(), json);
	}

	/**
	 * The pairs are split by configuration, so one configuration chosen twice fills only one side of the
	 * comparison, and the answer said that configuration had nothing to plot.
	 */
	@Test
	public void aComparisonOfAConfigurationWithItselfAsksForTwoDifferentOnes() {
		String json = answer(statistics -> {
			String response = new RESTServices().getSolverComparisonGraph(STAGE, JOB_SPACE_ID, 5, 5, 300, "white",
					Mockito.mock(HttpServletRequest.class));
			statistics.verifyNoInteractions();
			return response;
		});
		assertRefusedWith(RESTHelpers.SAME_CONFIGURATION_TWICE, json);
	}

	/** The job page reads a drawn comparison as JSON holding the chart's path and its image map. */
	@Test
	public void aDrawnComparisonIsStillAnsweredWithItsPathAndMap() {
		String json = answer(statistics -> {
			statistics.when(() -> Statistics.makeSolverComparisonChart(anyInt(), anyInt(), anyInt(), anyInt(), any(),
					anyInt(), any())).thenReturn(List.of("/starexec/jobgraphs/comparison", "<map></map>"));
			return comparison();
		});
		JsonObject response = JsonParser.parseString(json).getAsJsonObject();
		assertEquals(json, "/starexec/jobgraphs/comparison", response.get("src").getAsString());
		assertEquals(json, "<map></map>", response.get("map").getAsString());
	}
}
