package org.starexec.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Solvers;
import org.starexec.data.security.SpaceSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Solver;
import org.starexec.util.SessionUtil;
import org.starexec.util.Validator;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the copy handler tells the user when some solvers could not be copied.
 *
 * <p>It used to answer "Solver(s) copied successfully" whatever happened, so a user whose solver
 * failed to copy was told it had. The copies that did succeed are kept, so the report names the
 * failures rather than failing the whole request.
 */
public class SolverCopyStatusTest {

	private static final int SPACE_ID = 3;
	private static final int USER_ID = 7;

	@BeforeClass
	public static void initialize() {
		Validator.initialize();
	}

	@Test
	public void aCleanCopyIsReportedAsSuccess() {
		JsonObject response = copyTwoSolvers(List.of(101, 102));

		assertTrue(response.toString(), response.get("success").getAsBoolean());
		assertEquals("Solver(s) copied successfully", response.get("message").getAsString());
	}

	/** The handler must report the failure, and must still keep the solver that did copy. */
	@Test
	public void aFailedCopyIsReportedByTheHandler() {
		JsonObject response = copyTwoSolvers(List.of(101, -1));

		assertFalse("a failed copy must not be reported as success: " + response,
				response.get("success").getAsBoolean());
		String message = response.get("message").getAsString();
		assertTrue("the message must say how many failed: " + message, message.contains("1 of 2"));
		assertTrue("the message must say the rest were copied: " + message, message.contains("1 was copied"));
	}

	/**
	 * Every copy failing is reachable: associate on an empty list succeeds, so the handler still
	 * takes the reporting branch. It must not then claim that 0 solvers were copied successfully.
	 */
	@Test
	public void everyCopyFailingIsReportedWithoutClaimingSuccesses() {
		JsonObject response = copyTwoSolvers(List.of(-1, -1));

		assertFalse(response.toString(), response.get("success").getAsBoolean());
		String message = response.get("message").getAsString();
		assertEquals("None of the 2 solver(s) could be copied", message);
	}

	/** One surviving copy reads as one, not as "1 were copied". */
	@Test
	public void aSingleSurvivingCopyIsWordedInTheSingular() {
		String message = RESTServices.solverCopyStatus(1, 1, true).getMessage();

		assertTrue(message, message.contains("the remaining 1 was copied successfully"));
	}

	/**
	 * A link copies nothing, so failedCopies is never incremented on that path: a link must keep
	 * its own message and never report a partial copy.
	 */
	@Test
	public void aLinkNeverReportsAPartialCopy() {
		JsonObject response = linkTwoSolvers();

		assertTrue(response.toString(), response.get("success").getAsBoolean());
		assertEquals("Solver(s) linked successfully", response.get("message").getAsString());
	}

	/**
	 * The New_ID cookie tells the client which solvers now exist. A failed copy's id in there
	 * names a solver that was never created, so the cookie must carry the real ids only.
	 */
	@Test
	public void theCookieCarriesOnlyTheIdsThatCopied() {
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

		copyTwoSolvers(List.of(101, -1), response);

		ArgumentCaptor<Cookie> cookie = ArgumentCaptor.forClass(Cookie.class);
		Mockito.verify(response).addCookie(cookie.capture());
		assertEquals("New_ID", cookie.getValue().getName());
		assertEquals("101", cookie.getValue().getValue());
	}

	/** With nothing copied there is no id to name, and the cookie must not be set at all. */
	@Test
	public void noCookieIsSetWhenNothingCopied() {
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

		copyTwoSolvers(List.of(-1, -1), response);

		Mockito.verify(response, Mockito.never()).addCookie(Mockito.any());
	}

	/** Only the ids of solvers that exist may be associated with the space. */
	@Test
	public void onlyTheSolversThatCopiedAreAssociated() {
		try (MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
				MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<SpaceSecurity> security = Mockito.mockStatic(SpaceSecurity.class)) {
			stub(solvers, session, security, List.of(101, -1));

			new RESTServices().copySolversToSpace(SPACE_ID, request(), Mockito.mock(HttpServletResponse.class));

			solvers.verify(() -> Solvers.associate(List.of(101), SPACE_ID, false, USER_ID, false));
		}
	}

	private JsonObject linkTwoSolvers() {
		try (MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
				MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<SpaceSecurity> security = Mockito.mockStatic(SpaceSecurity.class)) {
			stub(solvers, session, security, List.of());

			HttpServletRequest request = request();
			Mockito.when(request.getParameter("copy")).thenReturn("false");
			String json = new RESTServices()
					.copySolversToSpace(SPACE_ID, request, Mockito.mock(HttpServletResponse.class));

			solvers.verify(() -> Solvers.copySolvers(Mockito.anyList(), Mockito.anyInt(), Mockito.anyInt()),
					Mockito.never());
			return JsonParser.parseString(json).getAsJsonObject();
		}
	}

	private JsonObject copyTwoSolvers(List<Integer> copyResults) {
		return copyTwoSolvers(copyResults, Mockito.mock(HttpServletResponse.class));
	}

	private JsonObject copyTwoSolvers(List<Integer> copyResults, HttpServletResponse response) {
		try (MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
				MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<SpaceSecurity> security = Mockito.mockStatic(SpaceSecurity.class)) {
			stub(solvers, session, security, copyResults);

			String json = new RESTServices().copySolversToSpace(SPACE_ID, request(), response);

			return JsonParser.parseString(json).getAsJsonObject();
		}
	}

	private void stub(MockedStatic<Solvers> solvers, MockedStatic<SessionUtil> session,
			MockedStatic<SpaceSecurity> security, List<Integer> copyResults) {
		session.when(() -> SessionUtil.getUserId(Mockito.any())).thenReturn(USER_ID);
		security.when(() -> SpaceSecurity.canCopyOrLinkSolverBetweenSpaces(
						Mockito.any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyList(),
						Mockito.anyBoolean(), Mockito.anyBoolean()))
				.thenReturn(new ValidatorStatusCode(true));
		solvers.when(() -> Solvers.get(Mockito.anyList())).thenReturn(List.of(new Solver(), new Solver()));
		solvers.when(() -> Solvers.copySolvers(Mockito.anyList(), Mockito.anyInt(), Mockito.anyInt()))
				.thenReturn(copyResults);
		solvers.when(() -> Solvers.associate(Mockito.anyList(), Mockito.anyInt(),
				Mockito.anyBoolean(), Mockito.anyInt(), Mockito.anyBoolean())).thenReturn(true);
	}

	private HttpServletRequest request() {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getParameterValues("selectedIds[]")).thenReturn(new String[] {"11", "12"});
		Mockito.when(request.getParameter("copyToSubspaces")).thenReturn("false");
		Mockito.when(request.getParameter("copy")).thenReturn("true");
		return request;
	}
}
