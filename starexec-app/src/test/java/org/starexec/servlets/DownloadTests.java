package org.starexec.servlets;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Permissions;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.User;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.mockito.ArgumentMatchers.anyInt;

public class DownloadTests {

	@Test
	public void doGetSendsHttp500WhenJobDownloadFailsBeforeResponseCommit() throws Exception {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

		User user = new User();
		user.setId(7);

		Mockito.when(request.getParameter("type")).thenReturn(R.JOB);
		Mockito.when(request.getParameter("id")).thenReturn("1");
		Mockito.when(request.getParameter("getcompleted")).thenReturn("false");
		Mockito.when(request.getParameter("returnids")).thenReturn("false");
		Mockito.when(response.isCommitted()).thenReturn(false);

		try (MockedStatic<SessionUtil> sessionUtilMock = Mockito.mockStatic(SessionUtil.class);
				 MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
				 MockedStatic<Jobs> jobsMock = Mockito.mockStatic(Jobs.class)) {
			sessionUtilMock.when(() -> SessionUtil.getUser(request)).thenReturn(user);
			sessionUtilMock.when(() -> SessionUtil.getUserId(request)).thenReturn(7);
			permissionsMock.when(() -> Permissions.canUserSeeJob(anyInt(), anyInt()))
					.thenReturn(new ValidatorStatusCode(true));
			jobsMock.when(() -> Jobs.get(1)).thenReturn(null);

			new Download().doGet(request, response);

			Mockito.verify(response).reset();
			Mockito.verify(response).sendError(
					HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"Failed to generate download"
			);
		}
	}
}
