package org.starexec.servlets;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.RESTHelpers;
import org.starexec.data.database.Communities;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Permissions;
import org.starexec.data.database.Processors;
import org.starexec.data.database.Queues;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Spaces;
import org.starexec.data.database.Users;
import org.starexec.data.to.DefaultSettings;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Permission;
import org.starexec.data.to.Processor;
import org.starexec.data.to.Queue;
import org.starexec.data.to.Solver;
import org.starexec.data.to.User;
import org.starexec.jobs.JobManager;
import org.starexec.util.SessionUtil;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The create-job servlet must answer truthfully about what it created, for every combination of the
 * optional {@code traversal} and {@code pause} parameters a client may send.
 *
 * <p>Two defects were observed at runtime against a live stack (probe c45, 2026-10-17):
 * <ul>
 *   <li>{@code keepHierarchy} / {@code runAllBenchInHierarchy} without a {@code traversal} parameter
 *       dereferenced a null traversal method and died with HTTP 500 and no job.</li>
 *   <li>any mode without a {@code pause} parameter dereferenced a null value with
 *       {@code start_paused.equals("yes")} <em>after</em> {@code Jobs.add} had already persisted the
 *       job: the job existed and ran, yet the client received HTTP 500, so a retry made a duplicate.</li>
 * </ul>
 *
 * <p>The database and session are replaced at their static boundaries; {@code JobManager.setupJob}
 * and the depth-first / round-robin builders run for real. A present-but-invalid value must be
 * rejected with HTTP 400 before any job is created; an absent value must take the documented
 * default (depth-first, not paused).
 */
public class CreateJobTraversalPauseTests {

	private static final int USER_ID = 33;
	private static final int SPACE_ID = 22;
	private static final int QUEUE_ID = 55;
	private static final int BENCH_ID = 7;
	private static final int CONFIG_ID = 9;
	private static final int SOLVER_ID = 11;

	/** The request validator's patterns are compiled at application start, not statically. */
	@BeforeClass
	public static void initializeValidator() {
		Validator.initialize();
	}

	@Test
	public void invalidTraversalIsRejectedBeforeAnyJobIsCreated() throws Exception {
		Map<String, String> params = baseParams();
		params.put("runChoice", "keepHierarchy");
		params.put("traversal", "sideways");

		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
		try (MockedStatic<RESTHelpers> rest = Mockito.mockStatic(RESTHelpers.class);
				MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<Users> users = Mockito.mockStatic(Users.class);
				MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class);
				MockedStatic<Communities> communities = Mockito.mockStatic(Communities.class);
				MockedStatic<Processors> processors = Mockito.mockStatic(Processors.class);
				MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {

			stubValidationGates(rest, session, users, permissions, communities, processors);

			new CreateJob().doPost(request(params), response);

			verify(response).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), contains("traversal"));
			jobs.verify(() -> Jobs.add(Mockito.any(Job.class), anyInt()), never());
		}
	}

	@Test
	public void invalidPauseIsRejectedBeforeAnyJobIsCreated() throws Exception {
		Map<String, String> params = baseParams();
		params.put("runChoice", "keepHierarchy");
		params.put("pause", "maybe");

		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
		try (MockedStatic<RESTHelpers> rest = Mockito.mockStatic(RESTHelpers.class);
				MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<Users> users = Mockito.mockStatic(Users.class);
				MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class);
				MockedStatic<Communities> communities = Mockito.mockStatic(Communities.class);
				MockedStatic<Processors> processors = Mockito.mockStatic(Processors.class);
				MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {

			stubValidationGates(rest, session, users, permissions, communities, processors);

			new CreateJob().doPost(request(params), response);

			verify(response).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), contains("pause"));
			jobs.verify(() -> Jobs.add(Mockito.any(Job.class), anyInt()), never());
		}
	}

	/**
	 * The regression that mattered most: with no {@code pause} parameter the job must still be created,
	 * must not be paused, and the client must get a redirect -- never the old HTTP 500 that left a job
	 * behind. Uses "choose a benchmark from this space", which reaches {@code Jobs.add} without walking
	 * a space hierarchy; that path never reads {@code traversal}, so the traversal default is pinned by
	 * the hierarchy tests below instead.
	 */
	@Test
	public void missingPauseCreatesTheJobWithoutPausingItOrFailing() throws Exception {
		Map<String, String> params = choiceParams("runChosenFromSpace");
		HttpServletRequest req = request(params);
		Mockito.when(req.getParameterValues("bench")).thenReturn(new String[]{String.valueOf(BENCH_ID)});

		createJob(req, (response, jobs, jobManager) -> {
			verify(response, never()).sendError(anyInt(), anyString());
			verify(response).sendRedirect(contains("job.jsp"));
			jobs.verify(() -> Jobs.add(Mockito.any(Job.class), eq(SPACE_ID)));
			jobs.verify(() -> Jobs.pause(anyInt()), never());
		});
	}

	/**
	 * Without {@code traversal}, a hierarchy job is built depth-first, as the web form and the manual
	 * default to. This path reads the traversal method, so it fails with the old NullPointerException
	 * if the default is removed.
	 */
	@Test
	public void missingTraversalBuildsTheHierarchyDepthFirst() throws Exception {
		Map<String, String> params = choiceParams("runAllBenchInHierarchy");
		params.put("pause", "no");

		createJob(request(params), (response, jobs, jobManager) -> {
			verify(response, never()).sendError(anyInt(), anyString());
			verify(response).sendRedirect(contains("job.jsp"));
			jobManager.verify(() -> JobManager.addJobPairsDepthFirst(Mockito.any(Job.class), Mockito.anyMap()));
			jobManager.verify(() -> JobManager.addJobPairsRoundRobin(Mockito.any(Job.class), Mockito.anyMap()),
					never());
			jobs.verify(() -> Jobs.add(Mockito.any(Job.class), eq(SPACE_ID)));
		});
	}

	/** The control for the test above: an explicit {@code robin} is still honored. */
	@Test
	public void robinTraversalBuildsTheHierarchyRoundRobin() throws Exception {
		Map<String, String> params = choiceParams("runAllBenchInHierarchy");
		params.put("pause", "no");
		params.put("traversal", "robin");

		createJob(request(params), (response, jobs, jobManager) -> {
			verify(response, never()).sendError(anyInt(), anyString());
			jobManager.verify(() -> JobManager.addJobPairsRoundRobin(Mockito.any(Job.class), Mockito.anyMap()));
			jobManager.verify(() -> JobManager.addJobPairsDepthFirst(Mockito.any(Job.class), Mockito.anyMap()),
					never());
		});
	}

	/** Assertions run while the static mocks of a successful creation are still open. */
	@FunctionalInterface
	private interface CreationChecks {
		void check(HttpServletResponse response, MockedStatic<Jobs> jobs, MockedStatic<JobManager> jobManager)
				throws Exception;
	}

	/**
	 * Runs {@code doPost} with every static boundary a successful "choose" creation reaches stubbed to
	 * succeed, then hands the response and the job mocks to {@code checks}. Each pair builder yields one
	 * pair, so the job passes the "no pairs" and quota guards and reaches {@code Jobs.add}.
	 */
	private static void createJob(HttpServletRequest req, CreationChecks checks) throws Exception {
		Mockito.when(req.getParameterValues("configs")).thenReturn(new String[]{String.valueOf(CONFIG_ID)});
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
		try (MockedStatic<RESTHelpers> rest = Mockito.mockStatic(RESTHelpers.class);
				MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<Users> users = Mockito.mockStatic(Users.class);
				MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class);
				MockedStatic<Communities> communities = Mockito.mockStatic(Communities.class);
				MockedStatic<Processors> processors = Mockito.mockStatic(Processors.class);
				MockedStatic<Queues> queues = Mockito.mockStatic(Queues.class);
				MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
				// Default answers: an empty hierarchy and an empty path map for the choose path.
				MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class);
				MockedStatic<JobManager> jobManager =
						Mockito.mockStatic(JobManager.class, Mockito.CALLS_REAL_METHODS);
				MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class)) {

			stubValidationGates(rest, session, users, permissions, communities, processors);

			Queue queue = new Queue();
			queue.setId(QUEUE_ID);
			queue.setCpuTimeout(0);
			queue.setWallTimeout(0);
			queues.when(() -> Queues.getUserQueues(USER_ID)).thenReturn(Collections.singletonList(queue));
			queues.when(() -> Queues.get(QUEUE_ID)).thenReturn(queue);

			Solver solver = new Solver();
			solver.setId(SOLVER_ID);
			solvers.when(() -> Solvers.getSolverByConfig(CONFIG_ID, false)).thenReturn(solver);

			// The backend pair builders: one pair each, so the job is not empty.
			jobManager.when(() -> JobManager.buildJob(
					Mockito.any(Job.class), Mockito.anyList(), Mockito.anyList(), Mockito.any()))
					.thenAnswer(call -> {
						call.<Job>getArgument(0).addJobPair(new JobPair());
						return null;
					});
			jobManager.when(() -> JobManager.addBenchmarksFromHierarchy(
					anyInt(), anyInt(), Mockito.anyList(), Mockito.any()))
					.thenAnswer(call -> {
						// Mutable: the round-robin builder removes spaces from the map as it drains them.
						List<JobPair> pairs = new ArrayList<>();
						pairs.add(new JobPair());
						Map<Integer, List<JobPair>> spaceToPairs = new HashMap<>();
						spaceToPairs.put(SPACE_ID, pairs);
						return spaceToPairs;
					});

			User user = new User();
			user.setPairQuota(1000);
			users.when(() -> Users.get(USER_ID)).thenReturn(user);
			jobs.when(() -> Jobs.countPairsByUser(USER_ID)).thenReturn(0);
			jobs.when(() -> Jobs.add(Mockito.any(Job.class), eq(SPACE_ID))).thenReturn(true);

			new CreateJob().doPost(req, response);

			checks.check(response, jobs, jobManager);
		}
	}

	/** Parameters that satisfy every validation gate reached before the run-choice-specific logic. */
	private static Map<String, String> baseParams() {
		Map<String, String> params = new HashMap<>();
		params.put("sid", String.valueOf(SPACE_ID));
		params.put("benchmarkingFramework", "RUNSOLVER");
		params.put("queue", String.valueOf(QUEUE_ID));
		params.put("desc", "regression test");
		params.put("seed", "0");
		return params;
	}

	/** A "choose" request with the given benchmark choice and no traversal or pause. */
	private static Map<String, String> choiceParams(String benchChoice) {
		Map<String, String> params = baseParams();
		params.put("runChoice", "choose");
		params.put("benchChoice", benchChoice);
		params.put("preProcess", "-1");
		params.put("postProcess", "-1");
		return params;
	}

	private static HttpServletRequest request(Map<String, String> params) {
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		for (Map.Entry<String, String> e : params.entrySet()) {
			Mockito.when(req.getParameter(e.getKey())).thenReturn(e.getValue());
		}
		return req;
	}

	/** Stubs the static boundaries every test needs to pass validation up to the run-choice logic. */
	private static void stubValidationGates(
			MockedStatic<RESTHelpers> rest,
			MockedStatic<SessionUtil> session,
			MockedStatic<Users> users,
			MockedStatic<Permissions> permissions,
			MockedStatic<Communities> communities,
			MockedStatic<Processors> processors) {
		rest.when(RESTHelpers::getReadOnly).thenReturn(false);
		session.when(() -> SessionUtil.getUserId(Mockito.any())).thenReturn(USER_ID);
		users.when(() -> Users.isDiskQuotaExceeded(USER_ID)).thenReturn(false);

		Permission perm = new Permission(true);
		perm.setAddJob(true);
		permissions.when(() -> Permissions.get(USER_ID, SPACE_ID)).thenReturn(perm);
		permissions.when(() -> Permissions.canUserSeeBenchs(Mockito.anyList(), eq(USER_ID))).thenReturn(true);
		permissions.when(() -> Permissions.canUserSeeSolvers(anyCollection(), eq(USER_ID))).thenReturn(true);

		// DefaultSettings' constructor asks for the no-type processor.
		processors.when(Processors::getNoTypeProcessor).thenReturn(new Processor());
		DefaultSettings settings = new DefaultSettings();
		settings.setCpuTimeout(10);
		settings.setWallclockTimeout(20);
		settings.setMaxMemory(1024L * 1024L);
		communities.when(() -> Communities.getDefaultSettings(SPACE_ID)).thenReturn(settings);
	}
}
