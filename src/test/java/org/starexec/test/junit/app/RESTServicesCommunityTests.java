package org.starexec.test.junit.app;

import com.google.gson.Gson;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.RESTServices;
import org.starexec.data.database.Spaces;
import org.starexec.data.security.SpaceSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;

import javax.servlet.http.HttpServletRequest;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RESTServicesCommunityTests {
	private final RESTServices services = new RESTServices();
	private final Gson gson = new Gson();

	@Test
	public void editCommunityDetailsNormalizesDescAttribute() {
		final int spaceId = 99;
		final int userId = 42;
		final String newDescription = "Updated description";

		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getParameter("val")).thenReturn(newDescription);

		try (MockedStatic<SessionUtil> sessionUtilMock = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<SpaceSecurity> spaceSecurityMock = Mockito.mockStatic(SpaceSecurity.class);
			 MockedStatic<Util> utilMock = Mockito.mockStatic(Util.class);
			 MockedStatic<Spaces> spacesMock = Mockito.mockStatic(Spaces.class)) {

			sessionUtilMock.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			spaceSecurityMock.when(() -> SpaceSecurity.canUpdateSettings(Mockito.anyInt(), Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
					.thenReturn(new ValidatorStatusCode(true));
			utilMock.when(() -> Util.isNullOrEmpty(newDescription)).thenReturn(false);
			spacesMock.when(() -> Spaces.updateDescription(spaceId, newDescription)).thenReturn(true);

			String resultJson = services.editCommunityDetails("desc", spaceId, request);
			ValidatorStatusCode result = gson.fromJson(resultJson, ValidatorStatusCode.class);

			assertTrue("Expected desc attribute to succeed after normalization", result.isSuccess());

			spaceSecurityMock.verify(() -> SpaceSecurity.canUpdateSettings(spaceId, "description", newDescription, userId));
			spacesMock.verify(() -> Spaces.updateDescription(spaceId, newDescription));
		}
	}
}
