package org.starexec.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.security.SpaceSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.SessionUtil;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copying with an empty selection must answer, not fail.
 *
 * <p>An ABSENT selectedIds[] is already refused at the top of the handler, where a null from
 * getParameterValues gives ERROR_INVALID_PARAMS. What was not refused is a present but empty
 * selection, a zero-length array: canCopySpace iterates the selection so it passes, the copy loop
 * runs zero times, and the handler answered "Space copied successfully" for spaces it never
 * copied, after building a New_ID cookie from an empty list, which used to throw with no try/catch
 * around it.
 */
public class EmptySpaceSelectionTest {

	private static final int SPACE_ID = 3;

	@BeforeAll
	public static void initialize() {
		Validator.initialize();
	}

	@Test
	public void anEmptySelectionIsAnsweredWithAStatusNotAnException() {
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<SpaceSecurity> security = Mockito.mockStatic(SpaceSecurity.class)) {
			session.when(() -> SessionUtil.getUserId(Mockito.any())).thenReturn(7);
			security.when(() -> SpaceSecurity.canCopySpace(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyList()))
					.thenReturn(new ValidatorStatusCode(true));

			String json = new RESTServices().copySubSpaceToSpace(
					SPACE_ID, request(), Mockito.mock(HttpServletResponse.class));

			JsonObject response = JsonParser.parseString(json).getAsJsonObject();
			assertFalse(response.get("success").getAsBoolean(),
					"copying nothing must not be reported as success: " + response);
			assertTrue(response.get("message").getAsString().contains("No spaces were selected"), response.toString());
		}
	}

	/**
	 * Everything the handler needs, with a present but empty selection: null is already refused
	 * as invalid parameters, so a zero-length array is the only way an empty list gets in.
	 */
	private HttpServletRequest request() {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getParameter("copyHierarchy")).thenReturn("false");
		Mockito.when(request.getParameter("copyPrimitives")).thenReturn("COPY");
		Mockito.when(request.getParameter("copy")).thenReturn("true");
		Mockito.when(request.getParameterValues("selectedIds[]")).thenReturn(new String[0]);
		return request;
	}

	/** An absent selection keeps its existing answer: invalid parameters, not a copy report. */
	@Test
	public void anAbsentSelectionIsStillRefusedAsInvalidParameters() {
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class)) {
			session.when(() -> SessionUtil.getUserId(Mockito.any())).thenReturn(7);
			HttpServletRequest request = request();
			Mockito.when(request.getParameterValues("selectedIds[]")).thenReturn(null);

			String json = new RESTServices().copySubSpaceToSpace(
					SPACE_ID, request, Mockito.mock(HttpServletResponse.class));

			JsonObject response = JsonParser.parseString(json).getAsJsonObject();
			assertFalse(response.get("success").getAsBoolean(), response.toString());
		}
	}
}
