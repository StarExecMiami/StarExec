package org.starexec.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.Analytics;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Communities;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Notifications;
import org.starexec.data.database.Permissions;
import org.starexec.data.database.Processors;
import org.starexec.data.database.Queues;
import org.starexec.data.database.RunscriptErrors;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Spaces;
import org.starexec.data.database.Users;
import org.starexec.data.security.BenchmarkSecurity;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.ProcessorSecurity;
import org.starexec.data.security.SpaceSecurity;
import org.starexec.data.security.UserSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.AnalyticsResults;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Configuration;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.JobStatus;
import org.starexec.data.to.Permission;
import org.starexec.data.to.Solver;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RESTServicesTest {

	private static final Gson gson = new GsonBuilder()
			.setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
			.create();

	@Test
	public void editProcessorRejectsANonNumericTimeLimitBeforeWriting() {
		final int processorId = 42;
		final int userId = 7;
		Processor processor = new Processor();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getParameter("name")).thenReturn(processor.getName());
		Mockito.when(request.getParameter("desc")).thenReturn(processor.getDescription());
		Mockito.when(request.getParameter("timelimit")).thenReturn("not-a-number");

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<ProcessorSecurity> processorSecurity = Mockito.mockStatic(ProcessorSecurity.class);
			 MockedStatic<Processors> processors = Mockito.mockStatic(Processors.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			processors.when(() -> Processors.get(processorId)).thenReturn(processor);
			processorSecurity.when(() -> ProcessorSecurity.canUserEditProcessor(
					processorId, userId, processor.getName(), processor.getDescription()))
					.thenReturn(new ValidatorStatusCode(true));

			String response = new RESTServices().editProcessor(processorId, request);
			ValidatorStatusCode status = gson.fromJson(response, ValidatorStatusCode.class);

			assertFalse(status.isSuccess());
			processors.verify(() -> Processors.updateName(Mockito.anyInt(), Mockito.anyString()), Mockito.never());
			processors.verify(() -> Processors.updateDescription(Mockito.anyInt(), Mockito.anyString()), Mockito.never());
			processors.verify(() -> Processors.updateTimeLimit(Mockito.anyInt(), Mockito.anyInt()), Mockito.never());
		}
	}

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
			assertTrue(msg.contains("alice_smith"), "the blocking space names must survive");
			assertTrue(msg.contains("id=85"), "the blocking space ids must survive");
			assertTrue(msg.contains("id=91"), "the blocking space ids must survive");
			assertFalse("User not found.".equals(msg),
					"a typed blocker must not be reported as a missing user");
			assertFalse(msg.startsWith("Unable to delete user."),
					"a typed blocker must not fall through to the generic category");
		}
	}

	@Test
	public void deleteUserExplainsWhenAnOwnedExecutionCannotBeRetired() throws Exception {
		final int userToDelete = 99;
		final int adminId = 1;
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		UserDeletionBlockedException blocked = new UserDeletionBlockedException(
				UserDeletionBlockedException.ACTIVE_JOB_NOT_RETIRED,
				"backend-private-detail-must-not-reach-the-client",
				java.util.Collections.emptyList(),
				java.util.Collections.emptyList());

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
			assertTrue(status.getMessage().contains("active jobs could not be safely stopped and retired"));
			assertTrue(status.getMessage().contains("retry the deletion"));
			assertFalse(status.getMessage().contains("backend-private-detail"));
		}
	}

	@Test
	public void deleteUserExplainsRetainedSolverStorageWithoutExposingItsCause() throws Exception {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		UserDeletionBlockedException blocked = new UserDeletionBlockedException(
				UserDeletionBlockedException.SOLVER_STORAGE_IN_USE,
				"private-storage-path-and-sql-detail", null, null,
				new java.sql.SQLException("private-driver-detail", "55006"));
		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<UserSecurity> userSecurity = Mockito.mockStatic(UserSecurity.class);
				MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(1);
			userSecurity.when(() -> UserSecurity.canDeleteUser(99, 1)).thenReturn(new ValidatorStatusCode(true));
			users.when(() -> Users.deleteUser(99)).thenThrow(blocked);
			ValidatorStatusCode status = gson.fromJson(new RESTServices().deleteUser(99, request), ValidatorStatusCode.class);
			assertFalse(status.isSuccess());
			assertTrue(status.getMessage().contains("solver files or build records are still retained or in use"));
			assertTrue(status.getMessage().contains("retry"));
			assertFalse(status.getMessage().contains("private"));
			assertFalse(status.getMessage().contains("55006"));
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
			assertEquals("Unable to delete user. The failure has been logged for an administrator.",
					msg, "unexpected failures must map to the stable generic category");
			assertFalse(msg.contains("DELETE FROM"), "SQL text must not reach the client");
			assertFalse(msg.contains("secret_table"), "table names must not reach the client");
			assertFalse(msg.contains("zzz"), "constraint names must not reach the client");
			assertFalse(msg.contains("23502"), "SQLSTATE must not reach the client");
			assertFalse("User not found.".equals(msg),
					"must not be classified as a missing user");
		}
	}

	@Test
	public void getUserSpacePermissionsChecksCallerPrivilegesOnCommunityAndDeniesOutsiders() {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Space community = new Space();
		community.setId(5);
		Permission adminPerm = new Permission(true);
		adminPerm.setLeader(true);
		List<Space> communities = java.util.Collections.singletonList(community);

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<Communities> communitiesDb = Mockito.mockStatic(Communities.class);
			 MockedStatic<GeneralSecurity> generalSecurity = Mockito.mockStatic(GeneralSecurity.class);
			 MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class)) {
			communitiesDb.when(Communities::getAll).thenReturn(communities);
			permissions.when(() -> Permissions.get(77, 5)).thenReturn(adminPerm);
			generalSecurity.when(() -> GeneralSecurity.hasAdminWritePrivileges(1)).thenReturn(true);
			generalSecurity.when(() -> GeneralSecurity.hasAdminWritePrivileges(42)).thenReturn(false);

			// Non-admin caller querying an admin's community permissions must be denied
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(42);
			ValidatorStatusCode denied = gson.fromJson(
					new RESTServices().getUserSpacePermissions(5, 1, request),
					ValidatorStatusCode.class);
			assertFalse(denied.isSuccess());

			// Public/unauthenticated caller querying public user permissions must be denied
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(R.PUBLIC_USER_ID);
			ValidatorStatusCode publicDenied = gson.fromJson(
					new RESTServices().getUserSpacePermissions(9, R.PUBLIC_USER_ID, request),
					ValidatorStatusCode.class);
			assertFalse(publicDenied.isSuccess());

			// Non-leader querying another user's non-community space permissions must receive JSON error, not null
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(42);
			sessionUtil.when(() -> SessionUtil.getPermission(request, 9)).thenReturn(new Permission(false));
			ValidatorStatusCode spaceDenied = gson.fromJson(
					new RESTServices().getUserSpacePermissions(9, 77, request),
					ValidatorStatusCode.class);
			assertFalse(spaceDenied.isSuccess());

			permissions.verify(() -> Permissions.get(Mockito.anyInt(), Mockito.anyInt()), Mockito.never());

			// Admin caller can inspect another user's community permissions
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(1);
			Permission fetched = gson.fromJson(
					new RESTServices().getUserSpacePermissions(5, 77, request),
					Permission.class);
			assertTrue(fetched.isLeader());
		}
	}

	@Test
	public void queueJobsFiltersPrivateJobsForNonAdminUsers() {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Job visibleJob = new Job();
		visibleJob.setId(10);
		visibleJob.setName("visible-job");
		visibleJob.setUserId(42);
		visibleJob.setCreateTime(new Timestamp(1000L));
		java.util.HashMap<String, Integer> stats = new java.util.HashMap<>();
		stats.put("totalPairs", 5);
		stats.put("pendingPairs", 2);
		visibleJob.setLiteJobPairStats(stats);

		Job hiddenJob = new Job();
		hiddenJob.setId(11);
		hiddenJob.setName("secret-job");
		hiddenJob.setUserId(99);
		hiddenJob.setCreateTime(new Timestamp(1000L));

		User owner = new User();
		owner.setId(42);
		owner.setFirstName("Alice");
		owner.setLastName("Smith");

		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class);
			 MockedStatic<GeneralSecurity> generalSecurity = Mockito.mockStatic(GeneralSecurity.class);
			 MockedStatic<JobSecurity> jobSecurity = Mockito.mockStatic(JobSecurity.class);
			 MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(42);
			generalSecurity.when(() -> GeneralSecurity.hasAdminReadPrivileges(42)).thenReturn(false);
			jobs.when(() -> Jobs.getByQueueId(1)).thenReturn(java.util.Arrays.asList(visibleJob, hiddenJob));
			jobs.when(() -> Jobs.getJobStatus(Mockito.anyInt())).thenReturn(JobStatus.RUNNING);
			jobSecurity.when(() -> JobSecurity.canUserSeeJob(10, 42)).thenReturn(new ValidatorStatusCode(true));
			jobSecurity.when(() -> JobSecurity.canUserSeeJob(11, 42)).thenReturn(new ValidatorStatusCode(false));
			users.when(() -> Users.get(42)).thenReturn(owner);

			JsonObject json = JsonParser.parseString(new RESTServices().getQueueJobs(1, request)).getAsJsonObject();
			JsonArray data = json.getAsJsonArray("data");
			assertEquals(1, data.size());
			assertEquals(10, data.get(0).getAsJsonObject().get("id").getAsInt());
		}

		JobPair visiblePair = new JobPair();
		visiblePair.setId(1);
		visiblePair.setJobId(10);
		JobPair hiddenPair = new JobPair();
		hiddenPair.setId(2);
		hiddenPair.setJobId(11);
		try (MockedStatic<GeneralSecurity> generalSecurity = Mockito.mockStatic(GeneralSecurity.class);
			 MockedStatic<JobSecurity> jobSecurity = Mockito.mockStatic(JobSecurity.class)) {
			generalSecurity.when(() -> GeneralSecurity.hasAdminReadPrivileges(42)).thenReturn(false);
			jobSecurity.when(() -> JobSecurity.canUserSeeJob(10, 42)).thenReturn(new ValidatorStatusCode(true));
			jobSecurity.when(() -> JobSecurity.canUserSeeJob(11, 42)).thenReturn(new ValidatorStatusCode(false));

			List<JobPair> filtered = RESTHelpers.filterVisibleClusterPairs(
					java.util.Arrays.asList(visiblePair, hiddenPair), 42);
			assertEquals(1, filtered.size());
			assertEquals(1, filtered.get(0).getId());
		}
	}

	@Test
	public void jobNotificationsRequireJobVisibilityAndAuthenticatedUser() {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<JobSecurity> jobSecurity = Mockito.mockStatic(JobSecurity.class);
			 MockedStatic<Notifications> notifications = Mockito.mockStatic(Notifications.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(42);
			jobSecurity.when(() -> JobSecurity.canUserSeeJob(99, 42)).thenReturn(new ValidatorStatusCode(false));

			assertThrows(RESTException.class, () -> new RESTServices().subscribeUserToJob(99, request));
			assertThrows(RESTException.class, () -> new RESTServices().unsubscribeUserToJob(99, request));
			notifications.verifyNoInteractions();
		}
	}

	@Test
	public void addJobPairsConfirmationRejectsUnauthorizedAndSkipsMissingConfigs() {
		HttpServletRequest unauthorizedReq = Mockito.mock(HttpServletRequest.class);
		Mockito.when(unauthorizedReq.getParameter("jobId")).thenReturn("77");
		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<JobSecurity> jobSecurity = Mockito.mockStatic(JobSecurity.class);
			 MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(unauthorizedReq)).thenReturn(42);
			jobSecurity.when(() -> JobSecurity.canUserAddJobPairs(77, 42))
					.thenReturn(new ValidatorStatusCode(false));

			JsonObject res = JsonParser.parseString(
					new RESTServices().getNumberOfPairsToBeAddedAndDeleted(unauthorizedReq)).getAsJsonObject();
			assertFalse(res.get("success").getAsBoolean());
			jobs.verifyNoInteractions();
		}

		HttpServletRequest authorizedReq = Mockito.mock(HttpServletRequest.class);
		Mockito.when(authorizedReq.getParameter("jobId")).thenReturn("77");
		Mockito.when(authorizedReq.getParameterValues("configs")).thenReturn(new String[]{"500"});
		Mockito.when(authorizedReq.getParameterValues("addToAll")).thenReturn(new String[]{"8"});
		Mockito.when(authorizedReq.getParameterValues("addToPaired")).thenReturn(null);
		User u = new User();
		u.setId(42);
		u.setPairQuota(100);
		try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<JobSecurity> jobSecurity = Mockito.mockStatic(JobSecurity.class);
			 MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
			 MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class);
			 MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			sessionUtil.when(() -> SessionUtil.getUserId(authorizedReq)).thenReturn(42);
			jobSecurity.when(() -> JobSecurity.canUserAddJobPairs(77, 42))
					.thenReturn(new ValidatorStatusCode(true));
			solvers.when(() -> Solvers.getConfigIdSetByJob(77)).thenReturn(new java.util.HashSet<>());
			solvers.when(() -> Solvers.getConfiguration(500)).thenReturn(null);
			jobs.when(() -> Jobs.getJobPairsToBeDeletedFromConfigIds(Mockito.eq(77), Mockito.anySet()))
					.thenReturn(java.util.Collections.emptyList());
			jobs.when(() -> Jobs.countJobPairsToBeAddedFromConfigIdsForPairedBenchmarks(
					Mockito.eq(77), Mockito.anySet(), Mockito.anySet())).thenReturn(0);
			jobs.when(() -> Jobs.countJobPairsToBeAddedFromConfigIdsForAllBenchmarks(
					Mockito.eq(77), Mockito.anySet(), Mockito.anySet())).thenReturn(0);
			users.when(() -> Users.get(42)).thenReturn(u);
			jobs.when(() -> Jobs.countPairsByUser(42)).thenReturn(10);

			JsonObject res = JsonParser.parseString(
					new RESTServices().getNumberOfPairsToBeAddedAndDeleted(authorizedReq)).getAsJsonObject();
			assertTrue(res.get("success").getAsBoolean());
			assertEquals(90, res.get("remainingQuota").getAsInt());
		}
	}
}
