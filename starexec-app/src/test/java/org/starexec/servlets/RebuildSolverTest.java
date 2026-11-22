package org.starexec.servlets;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.SystemFunctions;
import org.starexec.data.to.Solver;
import org.starexec.data.to.SolverBuildStatus;
import org.starexec.data.to.SolverBuildStatus.SolverBuildStatusCode;
import org.starexec.jobs.JobManager;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class RebuildSolverTest {

	@Test
	public void doPostReturns404WhenAssociatedSpacesIsEmpty() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		StringWriter body = new StringWriter();
		when(response.getWriter()).thenReturn(new PrintWriter(body));
		when(request.getParameter("id")).thenReturn("77");

		Solver solver = mock(Solver.class);
		SolverBuildStatus buildStatus = mock(SolverBuildStatus.class);
		when(buildStatus.getCode()).thenReturn(SolverBuildStatusCode.BUILD_FAILED);
		when(solver.getUserId()).thenReturn(9);
		when(solver.buildStatus()).thenReturn(buildStatus);

		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
			 MockedStatic<SystemFunctions> sys = Mockito.mockStatic(SystemFunctions.class);
			 MockedStatic<JobManager> jobs = Mockito.mockStatic(JobManager.class)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(9);
			solvers.when(() -> Solvers.get(77)).thenReturn(solver);
			solvers.when(() -> Solvers.getAssociatedSpaceIds(77)).thenReturn(Collections.emptyList());

			Method doPost = RebuildSolver.class.getDeclaredMethod(
					"doPost", HttpServletRequest.class, HttpServletResponse.class);
			doPost.setAccessible(true);
			doPost.invoke(new RebuildSolver(), request, response);

			verify(response).setStatus(404);
			JsonObject json = JsonParser.parseString(body.toString()).getAsJsonObject();
			assertFalse(json.get("success").getAsBoolean());
			assertEquals("Cannot find solver's parent space", json.get("message").getAsString());
			sys.verifyNoInteractions();
			jobs.verifyNoInteractions();
		}
	}
}
