package org.starexec.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jboss.resteasy.core.Dispatcher;
import org.jboss.resteasy.mock.MockDispatcherFactory;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.mock.MockHttpResponse;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.to.UploadJob;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UploadJobStatusHttpTest {

	@Test
	public void unauthorizedUploadStatusReturns404() throws Exception {
		MockHttpResponse response = requestAs(4);

		assertEquals(404, response.getStatus());
		assertEquals("not available", response.getContentAsString());
	}

	@Test
	public void ownerUploadStatusReturns200() throws Exception {
		MockHttpResponse response = requestAs(3);

		assertEquals(200, response.getStatus());
		JsonObject body = JsonParser.parseString(response.getContentAsString()).getAsJsonObject();
		assertEquals(14L, body.get("id").getAsLong());
		assertEquals("COMPLETED", body.get("status").getAsString());
	}

	private MockHttpResponse requestAs(int userId) throws Exception {
		UploadJob job = new UploadJob();
		job.setId(14L);
		job.setUserId(3);
		job.setStatus("COMPLETED");
		HttpServletRequest servletRequest = Mockito.mock(HttpServletRequest.class);
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class);
			 MockedStatic<GeneralSecurity> security = Mockito.mockStatic(GeneralSecurity.class)) {
			session.when(() -> SessionUtil.getUserId(Mockito.any(HttpServletRequest.class))).thenReturn(userId);
			queue.when(() -> UploadJobQueue.getJob(14L)).thenReturn(Optional.of(job));
			security.when(() -> GeneralSecurity.hasAdminReadPrivileges(userId)).thenReturn(false);
			Dispatcher dispatcher = MockDispatcherFactory.createDispatcher();
			dispatcher.getRegistry().addSingletonResource(new RESTServices(), "/services");
			MockHttpResponse response = new MockHttpResponse();
			ResteasyProviderFactory.pushContext(HttpServletRequest.class, servletRequest);
			try {
				dispatcher.invoke(MockHttpRequest.get("/services/uploads/jobs/14"), response);
			} finally {
				ResteasyProviderFactory.clearContextData();
			}
			session.verify(() -> SessionUtil.getUserId(Mockito.any(HttpServletRequest.class)));
			queue.verify(() -> UploadJobQueue.getJob(14L), Mockito.times(userId == 3 ? 2 : 1));
			return response;
		}
	}
}
