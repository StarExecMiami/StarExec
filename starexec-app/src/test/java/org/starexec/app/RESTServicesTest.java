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
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Spaces;
import org.starexec.data.database.Users;
import org.starexec.data.security.BenchmarkSecurity;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.SpaceSecurity;
import org.starexec.data.security.UserSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Processor;
import org.starexec.data.to.Space;
import org.starexec.data.to.User;
import org.starexec.exceptions.RESTException;
import org.starexec.exceptions.StarExecDatabaseException;
import org.starexec.exceptions.UserDeletionBlockedException;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Path;
import java.io.IOException;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RESTServicesTest {

	private static final Gson gson = new GsonBuilder()
			.setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
			.create();

	@Test
	public void editJobDescriptionEndpointKeepsDescriptionOutOfThePath() throws Exception {
		Method endpoint = RESTServices.class.getMethod(
			"editJobDescription",
			int.class,
			HttpServletRequest.class
		);

		assertEquals("/job/edit/description/{jobId}", endpoint.getAnnotation(Path.class).value());
	}

	@Test
	public void editJobDescriptionRejectsInvalidTextBeforeWriting() throws Exception {
		final int jobId = 42;
		final int userId = 7;
		final String invalidDescription = "<script>stored-xss</script>";
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Validator.initialize();

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<JobSecurity> jobSecurity = Mockito.mockStatic(JobSecurity.class);
			 MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			jobSecurity.when(() -> JobSecurity.userOwnsJobOrIsAdmin(jobId, userId)).thenReturn(true);

			String response = new RESTServices().editJobDescription(jobId, invalidDescription, request);
			ValidatorStatusCode status = gson.fromJson(response, ValidatorStatusCode.class);

			assertFalse(status.isSuccess());
			jobs.verify(() -> Jobs.setJobDescription(jobId, invalidDescription), Mockito.never());
		}
	}

	@Test
	public void editJobDescriptionAcceptsScientificPunctuation() throws Exception {
		final int jobId = 42;
		final int userId = 7;
		final String description = "C++ solver - https://my-tool.example.com/2017-05-22";
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getParameter("description")).thenReturn(description);
		Validator.initialize();

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<JobSecurity> jobSecurity = Mockito.mockStatic(JobSecurity.class);
			 MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			jobSecurity.when(() -> JobSecurity.userOwnsJobOrIsAdmin(jobId, userId)).thenReturn(true);

			String response = new RESTServices().editJobDescription(jobId, request);
			ValidatorStatusCode status = gson.fromJson(response, ValidatorStatusCode.class);

			assertTrue(status.isSuccess());
			jobs.verify(() -> Jobs.setJobDescription(jobId, description));
		}
	}

	@Test
	public void editSpaceRejectsInvalidDescriptionBeforeWriting() throws Exception {
		final int spaceId = 99;
		final int userId = 7;
		final String name = "Space name";
		final String invalidDescription = "<script>stored-xss</script>";
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getParameter("name")).thenReturn(name);
		Mockito.when(request.getParameter("description")).thenReturn(invalidDescription);
		Mockito.when(request.getParameter("locked")).thenReturn("false");
		Mockito.when(request.getParameter("sticky")).thenReturn("false");
		Validator.initialize();

		try (MockedStatic<Util> util = Mockito.mockStatic(Util.class);
			 MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<SpaceSecurity> spaceSecurity = Mockito.mockStatic(SpaceSecurity.class);
			 MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class)) {
			for (String parameter : new String[]{"name", "description", "locked", "sticky"}) {
				util.when(() -> Util.paramExists(parameter, request)).thenReturn(true);
			}
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			spaceSecurity.when(() -> SpaceSecurity.canUpdateProperties(spaceId, userId, name, false))
				.thenReturn(new ValidatorStatusCode(true));
			spaces.when(() -> Spaces.updateDetails(Mockito.eq(userId), Mockito.any(Space.class))).thenReturn(true);

			String response = new RESTServices().editSpace(spaceId, request);
			ValidatorStatusCode status = gson.fromJson(response, ValidatorStatusCode.class);

			assertFalse(status.isSuccess());
			spaces.verify(() -> Spaces.updateDetails(Mockito.eq(userId), Mockito.any(Space.class)), Mockito.never());
		}
	}

	@Test
	public void editSpaceAcceptsScientificPunctuation() throws Exception {
		final int spaceId = 99;
		final int userId = 7;
		final String name = "Scientific space";
		final String description = "C++ solver - https://my-tool.example.com/2017-05-22";
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getParameter("name")).thenReturn(name);
		Mockito.when(request.getParameter("description")).thenReturn(description);
		Mockito.when(request.getParameter("locked")).thenReturn("false");
		Mockito.when(request.getParameter("sticky")).thenReturn("false");
		Validator.initialize();

		try (MockedStatic<Util> util = Mockito.mockStatic(Util.class);
			 MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<SpaceSecurity> spaceSecurity = Mockito.mockStatic(SpaceSecurity.class);
			 MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class)) {
			for (String parameter : new String[]{"name", "description", "locked", "sticky"}) {
				util.when(() -> Util.paramExists(parameter, request)).thenReturn(true);
			}
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			spaceSecurity.when(() -> SpaceSecurity.canUpdateProperties(spaceId, userId, name, false))
				.thenReturn(new ValidatorStatusCode(true));
			spaces.when(() -> Spaces.updateDetails(Mockito.eq(userId), Mockito.any(Space.class))).thenReturn(true);

			String response = new RESTServices().editSpace(spaceId, request);
			ValidatorStatusCode status = gson.fromJson(response, ValidatorStatusCode.class);

			assertTrue(status.isSuccess());
			spaces.verify(() -> Spaces.updateDetails(
				Mockito.eq(userId),
				Mockito.argThat(space -> description.equals(space.getDescription()))
			));
		}
	}

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

	/**
	 * A genuinely missing user must still report "User not found." This is the
	 * pre-existing contract and the only case that could previously occur, so it
	 * has to survive unchanged.
	 */
	@Test
	public void deleteUserReportsNotFoundOnlyForTheP0002Cause() throws Exception {
		final int userToDelete = 99;
		final int adminId = 1;
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

		java.sql.SQLException notFound = new java.sql.SQLException("User 99 not found", "P0002");
		StarExecDatabaseException wrapped =
				new StarExecDatabaseException("User not found: " + userToDelete, notFound);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UserSecurity> userSecurity = Mockito.mockStatic(UserSecurity.class);
			 MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(adminId);
			userSecurity.when(() -> UserSecurity.canDeleteUser(userToDelete, adminId))
					.thenReturn(new ValidatorStatusCode(true));
			users.when(() -> Users.deleteUser(userToDelete)).thenThrow(wrapped);

			String response = new RESTServices().deleteUser(userToDelete, request);
			ValidatorStatusCode status = gson.fromJson(response, ValidatorStatusCode.class);

			assertFalse(status.isSuccess());
			assertEquals("User not found.", status.getMessage());
		}
	}

	/**
	 * A typed, administrator-actionable blocker must keep its useful detail, built
	 * from the exception's structured fields.
	 */
	@Test
	public void deleteUserRendersTypedBlockerFromStructuredFields() throws Exception {
		final int userToDelete = 99;
		final int adminId = 1;
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

		UserDeletionBlockedException blocked = new UserDeletionBlockedException(
				UserDeletionBlockedException.AMBIGUOUS_PERSONAL_SPACE,
				"Cannot identify a single personal space for user 99",
				java.util.Arrays.asList(85, 91),
				java.util.Arrays.asList("alice_smith", "alice_smith"));

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UserSecurity> userSecurity = Mockito.mockStatic(UserSecurity.class);
			 MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(adminId);
			userSecurity.when(() -> UserSecurity.canDeleteUser(userToDelete, adminId))
					.thenReturn(new ValidatorStatusCode(true));
			users.when(() -> Users.deleteUser(userToDelete)).thenThrow(blocked);

			String response = new RESTServices().deleteUser(userToDelete, request);
			ValidatorStatusCode status = gson.fromJson(response, ValidatorStatusCode.class);

			assertFalse(status.isSuccess());
			String msg = status.getMessage();
			assertTrue("the blocking space names must survive", msg.contains("alice_smith"));
			assertTrue("the blocking space ids must survive", msg.contains("id=85"));
			assertTrue("the blocking space ids must survive", msg.contains("id=91"));
			assertFalse("a typed blocker must not be reported as a missing user",
					"User not found.".equals(msg));
			assertFalse("a typed blocker must not fall through to the generic category",
					msg.startsWith("Unable to delete user."));
		}
	}

	/**
	 * An unexpected database failure must yield the stable generic category, with
	 * no internal text reaching the client. This is what makes a future return to
	 * echoing e.getMessage() fail.
	 */
	@Test
	public void deleteUserHidesInternalDetailOnUnexpectedDatabaseFailure() throws Exception {
		final int userToDelete = 99;
		final int adminId = 1;
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

		java.sql.SQLException internal = new java.sql.SQLException(
				"ERROR: null value in column \"zzz\" violates not-null constraint"
						+ " ; SQL: DELETE FROM starexec.secret_table WHERE id = 1", "23502");
		StarExecDatabaseException unexpected =
				new StarExecDatabaseException("constraint zzz_not_null on starexec.secret_table", internal);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<UserSecurity> userSecurity = Mockito.mockStatic(UserSecurity.class);
			 MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(adminId);
			userSecurity.when(() -> UserSecurity.canDeleteUser(userToDelete, adminId))
					.thenReturn(new ValidatorStatusCode(true));
			users.when(() -> Users.deleteUser(userToDelete)).thenThrow(unexpected);

			String response = new RESTServices().deleteUser(userToDelete, request);
			ValidatorStatusCode status = gson.fromJson(response, ValidatorStatusCode.class);

			assertFalse(status.isSuccess());
			String msg = status.getMessage();
			assertEquals("unexpected failures must map to the stable generic category",
					"Unable to delete user. The failure has been logged for an administrator.", msg);
			assertFalse("SQL text must not reach the client", msg.contains("DELETE FROM"));
			assertFalse("table names must not reach the client", msg.contains("secret_table"));
			assertFalse("constraint names must not reach the client", msg.contains("zzz"));
			assertFalse("SQLSTATE must not reach the client", msg.contains("23502"));
			assertFalse("must not be classified as a missing user",
					"User not found.".equals(msg));
		}
	}
}
