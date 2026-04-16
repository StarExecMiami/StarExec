package org.starexec.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Communities;
import org.starexec.data.database.Users;
import org.starexec.data.security.BenchmarkSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Processor;
import org.starexec.data.to.Space;
import org.starexec.data.to.User;
import org.starexec.exceptions.RESTException;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RESTServicesTest {

	private static final Gson gson = new GsonBuilder()
			.setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
			.create();

	@Test
	public void getBenchmarkMetadataReturnsJsonForValidBenchmark() throws Exception {
		final int benchId = 42;
		final int userId = 7;
		final int ownerId = 3;
		final int communityId = 99;
		final int limit = 0;

		Processor type = new Processor();
		type.setCommunityId(communityId);

		Benchmark benchmark = new Benchmark();
		benchmark.setId(benchId);
		benchmark.setName("test-benchmark");
		benchmark.setDescription("a test benchmark");
		benchmark.setUploadDate(Timestamp.valueOf("2025-01-15 10:00:00"));
		benchmark.setDownloadable(true);
		benchmark.setDiskSize(2048L);
		benchmark.setUserId(ownerId);
		benchmark.setType(type);
		benchmark.setPath("/tmp/test-benchmark");

		User owner = new User();
		owner.setId(ownerId);
		owner.setFirstName("Alice");
		owner.setLastName("Smith");

		Space community = new Space();
		community.setId(communityId);
		community.setName("test-community");

		TreeMap<String, String> attrs = new TreeMap<>();
		attrs.put("status", "sat");

		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<BenchmarkSecurity> benchmarkSecurity = Mockito.mockStatic(BenchmarkSecurity.class);
			 MockedStatic<Benchmarks> benchmarks = Mockito.mockStatic(Benchmarks.class);
			 MockedStatic<Users> users = Mockito.mockStatic(Users.class);
			 MockedStatic<Communities> communities = Mockito.mockStatic(Communities.class)) {

			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			benchmarkSecurity.when(() -> BenchmarkSecurity.canUserSeeBenchmarkContents(benchId, userId))
					.thenReturn(new ValidatorStatusCode(true));
			benchmarks.when(() -> Benchmarks.get(benchId)).thenReturn(benchmark);
			benchmarks.when(() -> Benchmarks.getSortedAttributes(benchId)).thenReturn(attrs);
			benchmarks.when(() -> Benchmarks.getBenchDependencies(benchId)).thenReturn(new ArrayList<>());
			benchmarks.when(() -> Benchmarks.getContents(benchmark, limit)).thenReturn(Optional.of("benchmark content"));
			users.when(() -> Users.get(ownerId)).thenReturn(owner);
			communities.when(() -> Communities.getDetails(communityId)).thenReturn(community);

			RESTServices service = new RESTServices();
			String json = service.getBenchmarkMetadata(benchId, limit, request);

			JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
			assertEquals(benchId, obj.get("id").getAsInt());
			assertEquals("test-benchmark", obj.get("name").getAsString());
			assertEquals("a test benchmark", obj.get("description").getAsString());
			assertTrue(obj.get("downloadable").getAsBoolean());
			assertEquals(2048L, obj.get("diskSize").getAsLong());
			assertEquals("benchmark content", obj.get("content").getAsString());
			assertTrue(obj.has("attributes"));
			assertTrue(obj.has("dependencies"));
			assertTrue(obj.has("owner"));
			assertTrue(obj.has("community"));
		}
	}

	@Test
	public void getBenchmarkMetadataThrowsForbiddenWhenPermissionDenied() {
		final int benchId = 10;
		final int userId = 5;
		final int limit = 0;

		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<BenchmarkSecurity> benchmarkSecurity = Mockito.mockStatic(BenchmarkSecurity.class)) {

			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			benchmarkSecurity.when(() -> BenchmarkSecurity.canUserSeeBenchmarkContents(benchId, userId))
					.thenReturn(new ValidatorStatusCode(false));

			RESTServices service = new RESTServices();
			try {
				service.getBenchmarkMetadata(benchId, limit, request);
				throw new AssertionError("Expected RESTException.FORBIDDEN to be thrown");
			} catch (RESTException e) {
				assertSame(RESTException.FORBIDDEN, e);
			}
		}
	}

	@Test
	public void getBenchmarkMetadataThrowsNotFoundWhenBenchmarkMissing() {
		final int benchId = 99;
		final int userId = 5;
		final int limit = 0;

		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<BenchmarkSecurity> benchmarkSecurity = Mockito.mockStatic(BenchmarkSecurity.class);
			 MockedStatic<Benchmarks> benchmarks = Mockito.mockStatic(Benchmarks.class)) {

			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			benchmarkSecurity.when(() -> BenchmarkSecurity.canUserSeeBenchmarkContents(benchId, userId))
					.thenReturn(new ValidatorStatusCode(true));
			benchmarks.when(() -> Benchmarks.get(benchId)).thenReturn(null);

			RESTServices service = new RESTServices();
			try {
				service.getBenchmarkMetadata(benchId, limit, request);
				throw new AssertionError("Expected RESTException.NOT_FOUND to be thrown");
			} catch (RESTException e) {
				assertSame(RESTException.NOT_FOUND, e);
			}
		}
	}

	@Test
	public void getBenchmarkMetadataThrowsInternalServerErrorOnIOException() throws Exception {
		final int benchId = 55;
		final int userId = 7;
		final int ownerId = 3;
		final int communityId = 99;
		final int limit = 0;

		Processor type = new Processor();
		type.setCommunityId(communityId);

		Benchmark benchmark = new Benchmark();
		benchmark.setId(benchId);
		benchmark.setName("failing-benchmark");
		benchmark.setUserId(ownerId);
		benchmark.setType(type);

		User owner = new User();
		owner.setId(ownerId);

		Space community = new Space();
		community.setId(communityId);

		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<BenchmarkSecurity> benchmarkSecurity = Mockito.mockStatic(BenchmarkSecurity.class);
			 MockedStatic<Benchmarks> benchmarks = Mockito.mockStatic(Benchmarks.class);
			 MockedStatic<Users> users = Mockito.mockStatic(Users.class);
			 MockedStatic<Communities> communities = Mockito.mockStatic(Communities.class)) {

			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			benchmarkSecurity.when(() -> BenchmarkSecurity.canUserSeeBenchmarkContents(benchId, userId))
					.thenReturn(new ValidatorStatusCode(true));
			benchmarks.when(() -> Benchmarks.get(benchId)).thenReturn(benchmark);
			benchmarks.when(() -> Benchmarks.getSortedAttributes(benchId)).thenReturn(new TreeMap<>());
			benchmarks.when(() -> Benchmarks.getBenchDependencies(benchId)).thenReturn(new ArrayList<>());
			benchmarks.when(() -> Benchmarks.getContents(benchmark, limit)).thenThrow(new IOException("disk read failed"));
			users.when(() -> Users.get(ownerId)).thenReturn(owner);
			communities.when(() -> Communities.getDetails(communityId)).thenReturn(community);

			RESTServices service = new RESTServices();
			try {
				service.getBenchmarkMetadata(benchId, limit, request);
				throw new AssertionError("Expected RESTException.INTERNAL_SERVER_ERROR to be thrown");
			} catch (RESTException e) {
				assertSame(RESTException.INTERNAL_SERVER_ERROR, e);
			}
		}
	}
}
