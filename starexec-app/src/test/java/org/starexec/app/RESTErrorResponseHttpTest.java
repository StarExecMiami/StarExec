package org.starexec.app;

import org.jboss.resteasy.core.Dispatcher;
import org.jboss.resteasy.mock.MockDispatcherFactory;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.mock.MockHttpResponse;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.security.BenchmarkSecurity;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Benchmark;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RESTErrorResponseHttpTest {

	@Test
	public void outsiderBenchmarkContentReturns404() throws Exception {
		try (MockedStatic<BenchmarkSecurity> security = Mockito.mockStatic(BenchmarkSecurity.class);
			 MockedStatic<Benchmarks> benchmarks = Mockito.mockStatic(Benchmarks.class)) {
			security.when(() -> BenchmarkSecurity.canUserSeeBenchmarkContents(14, 4))
					.thenReturn(new ValidatorStatusCode(false));

			MockHttpResponse response = request("/services/benchmarks/14/contents?limit=100", 4);

			assertEquals(404, response.getStatus());
			assertEquals("not available", response.getContentAsString());
			security.verify(() -> BenchmarkSecurity.canUserSeeBenchmarkContents(14, 4));
			benchmarks.verifyNoInteractions();
		}
	}

	@Test
	public void benchmarkContentIoFailureReturns500() throws Exception {
		Benchmark benchmark = new Benchmark();
		benchmark.setId(14);
		try (MockedStatic<BenchmarkSecurity> security = Mockito.mockStatic(BenchmarkSecurity.class);
			 MockedStatic<Benchmarks> benchmarks = Mockito.mockStatic(Benchmarks.class)) {
			security.when(() -> BenchmarkSecurity.canUserSeeBenchmarkContents(14, 3))
					.thenReturn(new ValidatorStatusCode(true));
			benchmarks.when(() -> Benchmarks.get(14)).thenReturn(benchmark);
			benchmarks.when(() -> Benchmarks.getContents(benchmark, 100))
					.thenThrow(new IOException("synthetic storage failure"));

			MockHttpResponse response = request("/services/benchmarks/14/contents?limit=100", 3);

			assertEquals(500, response.getStatus());
			assertEquals("not available", response.getContentAsString());
			security.verify(() -> BenchmarkSecurity.canUserSeeBenchmarkContents(14, 3));
			benchmarks.verify(() -> Benchmarks.getContents(benchmark, 100));
		}
	}

	@Test
	public void missingUploadStatusReturns404() throws Exception {
		try (MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class);
			 MockedStatic<GeneralSecurity> security = Mockito.mockStatic(GeneralSecurity.class)) {
			queue.when(() -> UploadJobQueue.getJob(14L)).thenReturn(Optional.empty());
			security.when(() -> GeneralSecurity.hasAdminReadPrivileges(3)).thenReturn(false);

			MockHttpResponse response = request("/services/uploads/jobs/14", 3);

			assertEquals(404, response.getStatus());
			assertEquals("not available", response.getContentAsString());
			queue.verify(() -> UploadJobQueue.getJob(14L));
		}
	}

	private MockHttpResponse request(String path, int userId) throws Exception {
		HttpServletRequest servletRequest = Mockito.mock(HttpServletRequest.class);
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class)) {
			session.when(() -> SessionUtil.getUserId(Mockito.any(HttpServletRequest.class))).thenReturn(userId);
			Dispatcher dispatcher = MockDispatcherFactory.createDispatcher();
			dispatcher.getRegistry().addSingletonResource(new RESTServices(), "/services");
			MockHttpResponse response = new MockHttpResponse();
			ResteasyProviderFactory.pushContext(HttpServletRequest.class, servletRequest);
			try {
				dispatcher.invoke(MockHttpRequest.get(path), response);
			} finally {
				ResteasyProviderFactory.clearContextData();
			}
			session.verify(() -> SessionUtil.getUserId(Mockito.any(HttpServletRequest.class)));
			return response;
		}
	}
}
