package org.starexec.test.junit.database;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.data.database.Common;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.constants.R;
import org.starexec.data.to.FinishedSolverBuild;
import org.starexec.data.to.SolverBuildStopReceipt;
import org.starexec.data.to.Solver;
import org.starexec.data.to.SolverBuildStatus.SolverBuildStatusCode;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.jobs.SolverBuildRecorder;
import org.starexec.jobs.SolverBuildStaging;
import org.starexec.jobs.SolverBuildHelperCleanup;
import org.starexec.test.util.DatabaseTestSupport;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/**
 * {@link SolverBuildRecorder#recordFinishedBuilds} against the real tables: a finished build job sets its
 * solver's build status and, when it built, the solver's path; its helper benchmark is then deleted, which
 * is what keeps the next pass from applying it again.
 *
 * <p>In container mode the job script cannot reach the database, and it was the only thing that recorded
 * a build, so a solver with a {@code starexec_build} script stayed "being built" for good: rebuilding it
 * was refused, and no job using it was ever dispatched.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*} variables at a
 * <strong>disposable</strong> database: these tests insert and delete rows.
 */
public class SolverBuildRecordSqlTest extends Common {

	private static final int UNBUILT = 0;
	private static final int BUILT_BY_STAREXEC = 2;
	private static final int BUILD_FAILED = 3;

	@TempDir
	public File folder;

	private final List<Integer> jobs = new ArrayList<>();
	private final List<Integer> benchmarks = new ArrayList<>();
	private final List<Integer> solvers = new ArrayList<>();
	private int userId;
	private Path source;
	private Path built;

	@BeforeAll
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("SolverBuildRecordSqlTest");
		Common.initialize();
	}

	@BeforeEach
	public void createSolverFiles() throws Exception {
		userId = insertReturningId("INSERT INTO starexec.users (email, first_name, last_name, institution,"
				+ " created, password, disk_quota, disk_size)"
				+ " VALUES (?, 'Build', 'Record', 'test', now(), 'x', 1000000, 0) RETURNING id",
				ps -> ps.setString(1, "build-" + UUID.randomUUID() + "@example.invalid"));
		Path solverDir = Files.createDirectories(folder.toPath().resolve("Solvers/" + userId + "/probe"));
		source = Files.createDirectory(solverDir.resolve("20261025-09.15.48.756_src"));
		Files.writeString(source.resolve("starexec_build"), "BUILD SCRIPT");
		// The upload creates the built path empty; a build that worked copied the solver into it.
		built = Files.createDirectory(solverDir.resolve("20261025-09.15.48.756"));
	}

	@AfterEach
	public void dropFixture() throws SQLException {
		try (Connection con = Common.getConnection()) {
			for (int job : jobs) {
				delete(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", job);
				delete(con, "DELETE FROM starexec.jobs WHERE id = ?", job);
			}
			for (int bench : benchmarks) {
				retireManagedFixtureHelper(con, bench);
				delete(con, "DELETE FROM starexec.benchmarks WHERE id = ?", bench);
			}
			for (int solver : solvers) {
				delete(con, "DELETE FROM starexec.solvers WHERE id = ?", solver);
			}
			if (userId > 0) {
				assertEquals("0", read("SELECT disk_size FROM starexec.users WHERE id=?", userId),
						"the fixture must settle its own disk usage");
				delete(con, "DELETE FROM starexec.users WHERE id = ?", userId);
			}
		}
	}

	private void retireManagedFixtureHelper(Connection con, int benchmark) throws SQLException {
		var helper = SolverBuildArtifacts.findHelper(con, benchmark);
		if (helper.isEmpty() || helper.get().finishedAt() != null) return;
		List<int[]> readers = new ArrayList<>();
		try (PreparedStatement ps = con.prepareStatement("SELECT origin_pair_id,attempt_no FROM starexec.solver_build_artifacts"
				+ " WHERE helper_benchmark_id=? AND execution_token IS NOT NULL")) {
			ps.setInt(1, benchmark);
			try (ResultSet rs = ps.executeQuery()) { while (rs.next()) readers.add(new int[]{rs.getInt(1),rs.getInt(2)}); }
		}
		if (readers.isEmpty()) return;
		// These fixtures prepare metadata but never launch a provider execution.
		for (int[] reader : readers) {
			var artifact = SolverBuildArtifacts.find(con, reader[0], reader[1]).orElseThrow();
			if (SolverBuildArtifacts.findStopReceipt(con, reader[0], reader[1]).isPresent()) continue;
			String engine = "kubernetes:7:fixture-never-started-" + artifact.id();
			JsonObject facts = new JsonObject(); facts.addProperty("status", "absent"); facts.addProperty("disposition", "never_started");
			var receipt = SolverBuildStopReceipt.observed("kubernetes", artifact.id(), reader[0], reader[1], engine,
					"fixture-resource-" + artifact.id(), Instant.now(), facts);
			assertTrue(SolverBuildArtifacts.recordUnstarted(con, reader[0], reader[1], artifact.executionToken(), engine, receipt));
		}
		assertTrue(SolverBuildHelperCleanup.purge(con, benchmark, folder.toPath().resolve("Benchmarks")));
	}

	@Test
	public void aCompletedBuildMakesTheSolverBuiltAtItsBuiltPath() throws Exception {
		Files.writeString(built.resolve("probe-solver"), "BUILT");
		int solver = insertSolver(source, UNBUILT);
		Path benchFile = benchmarkFile();
		int bench = insertBenchmark("starexec_build", benchFile);
		buildJob(solver, bench, StatusCode.STATUS_COMPLETE);

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(BUILT_BY_STAREXEC, buildStatus(solver));
		assertEquals(built.toString(), path(solver));
		assertTrue(deleted(bench), "the helper benchmark is deleted");
		assertFalse(Files.exists(benchFile), "with its file");
	}

	@Test
	public void aFailedBuildMakesTheSolverFailedAndKeepsItsSource() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, bench, StatusCode.ERROR_RUNSCRIPT);

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(BUILD_FAILED, buildStatus(solver));
		assertEquals(source.toString(), path(solver), "a rebuild starts from the uploaded source");
		assertTrue(deleted(bench));
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	public void aLegacyLogRemainsReadableAfterHistoricalManagedAttempts(boolean failed) throws Exception {
		Files.writeString(built.resolve("probe-solver"), "BUILT");
		int solver = insertSolver(source, UNBUILT);
		int historical = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, historical, StatusCode.ERROR_RUNSCRIPT);
		try (Connection con = Common.getConnection(); PreparedStatement ps = con.prepareStatement(
				"UPDATE starexec.solver_build_artifacts SET state='REJECTED',execution_token=?,staging_path=?"
				+ " WHERE pair_id IN (SELECT id FROM starexec.job_pairs WHERE job_id=?)")) {
			ps.setObject(1, UUID.randomUUID()); ps.setString(2, folder.toPath().resolve("historical-staging").toString());
			ps.setInt(3, jobs.get(jobs.size()-1)); assertEquals(1, ps.executeUpdate());
		}
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, bench, failed ? StatusCode.ERROR_RUNSCRIPT : StatusCode.STATUS_COMPLETE);
		SolverBuildRecorder.recordFinishedBuilds();
		assertEquals(failed ? BUILD_FAILED : BUILT_BY_STAREXEC, buildStatus(solver));
		try (Connection con = Common.getConnection(); PreparedStatement ps = con.prepareStatement(
				"SELECT a.execution_token IS NULL AND a.state='REJECTED' AND a.origin_pair_id IN"
				+ " (SELECT id FROM starexec.job_pairs WHERE job_id=?) FROM starexec.solvers s"
				+ " JOIN starexec.solver_build_artifacts a ON a.id=s.build_output_artifact_id WHERE s.id=?")) {
			ps.setInt(1, jobs.get(jobs.size()-1)); ps.setInt(2, solver);
			try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); assertTrue(rs.getBoolean(1)); }
		}
		Path legacy = folder.toPath().resolve("legacy-log"); Files.writeString(legacy, "completed legacy log");
		try (var solvers = mockStatic(Solvers.class, CALLS_REAL_METHODS)) {
			solvers.when(() -> Solvers.getSolverBuildOutput(solver)).thenReturn(legacy.toFile());
			assertEquals("completed legacy log", Solvers.readSolverBuildLog(solver, userId).orElseThrow());
		}
	}

	/** Recorded before the copy-back moved ahead of COMPLETE, or a copy of nothing: not a usable solver. */
	@Test
	public void aCompletedBuildWithNothingCopiedBackFailed() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, bench, StatusCode.STATUS_COMPLETE);

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(BUILD_FAILED, buildStatus(solver));
		assertEquals(source.toString(), path(solver));
		assertTrue(deleted(bench));
	}

	@Test
	public void aBuildThatHasNotFinishedIsLeftForALaterPass() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, bench, StatusCode.STATUS_RUNNING);

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(UNBUILT, buildStatus(solver));
		assertEquals(source.toString(), path(solver));
		assertFalse(deleted(bench));
	}

	/** A rebuild's job is the newer one; the older job's result says nothing about it. */
	@Test
	public void anOlderBuildJobIsRetiredWithoutDecidingForTheNewerOne() throws Exception {
		Files.writeString(built.resolve("probe-solver"), "BUILT");
		int solver = insertSolver(source, UNBUILT);
		int older = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, older, StatusCode.STATUS_COMPLETE);
		int newer = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, newer, StatusCode.STATUS_RUNNING);

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(UNBUILT, buildStatus(solver));
		assertEquals(source.toString(), path(solver));
		assertTrue(deleted(older), "the older job is retired");
		assertFalse(deleted(newer), "the newer one is still running");
	}

	/**
	 * A rebuild makes the solver wait again, at its source path, before its build job exists. A pass in
	 * between must not take the last build, already applied, for the rebuild's.
	 */
	@Test
	public void aRecordedBuildIsNotAppliedAgainWhenTheSolverIsRebuilt() throws Exception {
		Files.writeString(built.resolve("probe-solver"), "BUILT");
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, bench, StatusCode.STATUS_COMPLETE);
		SolverBuildRecorder.recordFinishedBuilds();
		assertEquals(BUILT_BY_STAREXEC, buildStatus(solver));
		rebuild(solver);
		assertEquals(UNBUILT, buildStatus(solver), "as RebuildSolver leaves it");
		assertEquals(source.toString(), path(solver));

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(UNBUILT, buildStatus(solver), "the applied build is not applied again");
		assertEquals(source.toString(), path(solver));
	}

	/** A rebuild leaves the last build's files at the built path, so files there do not make a failed one built. */
	@Test
	public void aFailedRebuildIsFailedThoughTheLastBuildsFilesAreStillThere() throws Exception {
		Files.writeString(built.resolve("probe-solver"), "LAST BUILD");
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, bench, StatusCode.ERROR_RUNSCRIPT);

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(BUILD_FAILED, buildStatus(solver));
		assertEquals(source.toString(), path(solver));
		assertTrue(deleted(bench));
	}

	/** A shared file system that cannot be read now says nothing about the build: it waits for a later pass. */
	@Test
	public void aBuildWhoseBuiltPathCannotBeReadIsLeftForALaterPass() throws Exception {
		Files.writeString(built.resolve("probe-solver"), "BUILT");
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, bench, StatusCode.STATUS_COMPLETE);
		Path solverDir = built.getParent();
		Files.setPosixFilePermissions(solverDir, PosixFilePermissions.fromString("---------"));
		try {
			assumeTrue(cannotRead(built), "needs a user that file permissions apply to");
			SolverBuildRecorder.recordFinishedBuilds();
		} finally {
			Files.setPosixFilePermissions(solverDir, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
		assertEquals(UNBUILT, buildStatus(solver));
		assertEquals(source.toString(), path(solver));
		assertFalse(deleted(bench), "not recorded, so not retired");

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(BUILT_BY_STAREXEC, buildStatus(solver), "recorded once it can be read");
		assertEquals(built.toString(), path(solver));
		assertTrue(deleted(bench));
	}

	/** Only a benchmark named as addBuildJob names it is a build job's helper, so only that one is deleted. */
	@Test
	public void aBuildJobWhoseBenchmarkIsNotItsHelperIsLeftAlone() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("real-benchmark.p", benchmarkFile());
		buildJob(solver, bench, StatusCode.ERROR_RUNSCRIPT);

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(UNBUILT, buildStatus(solver));
		assertFalse(deleted(bench));
	}

	@Test
	public void anOrdinaryJobIsNotABuild() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		job(false, solver, bench, StatusCode.ERROR_RUNSCRIPT);

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(UNBUILT, buildStatus(solver));
		assertFalse(deleted(bench));
	}

	/** Read and applied in two steps: a solver that changed in between must keep what it changed to. */
	@Test
	public void theOutcomeIsOnlyAppliedWhileTheSolverIsAsItWasRead() throws Exception {
		int alreadyBuilt = insertSolver(source, BUILT_BY_STAREXEC);
		assertFalse(Solvers.recordBuildOutcome(asRead(alreadyBuilt, source, UNBUILT), "/elsewhere"),
				"a solver built in between");
		assertEquals(BUILT_BY_STAREXEC, buildStatus(alreadyBuilt));
		assertEquals(source.toString(), path(alreadyBuilt));

		int moved = insertSolver(built, UNBUILT);
		assertFalse(Solvers.recordBuildOutcome(asRead(moved, source, UNBUILT), null),
				"a solver whose path changed in between");
		assertEquals(UNBUILT, buildStatus(moved));
		assertEquals(built.toString(), path(moved));

		int rebuilt = insertSolver(source, BUILD_FAILED);
		assertTrue(Solvers.recordBuildOutcome(asRead(rebuilt, source, BUILD_FAILED), built.toString()),
				"a failed solver being rebuilt waits for the rebuild");
		assertEquals(BUILT_BY_STAREXEC, buildStatus(rebuilt));
		assertEquals(built.toString(), path(rebuilt));
	}

	/** The three-hourly sweep can mark a build failed between the read and the update. The next pass decides. */
	@Test
	public void aStatusThatChangedAfterTheReadIsDecidedAgainOnTheNextPass() throws Exception {
		Files.writeString(built.resolve("probe-solver"), "BUILT");
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, bench, StatusCode.STATUS_COMPLETE);
		FinishedSolverBuild read = finishedBuildOf(solver);
		setBuildStatus(solver, BUILD_FAILED);

		assertFalse(Solvers.recordBuildOutcome(read, read.getBuiltPath()),
				"the status read is not the solver's any more");
		assertEquals(BUILD_FAILED, buildStatus(solver));
		assertEquals(source.toString(), path(solver));

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(BUILT_BY_STAREXEC, buildStatus(solver), "decided again, from the solver as it is now");
		assertEquals(built.toString(), path(solver));
		assertTrue(deleted(bench));
	}

	/**
	 * A rebuild of a failed solver leaves the solver as it is and adds its build job afterwards. A pass that read
	 * the older job before that must not apply it: the newer job decides.
	 */
	@Test
	public void aBuildJobAddedAfterTheReadDecidesInsteadOfTheOlderOne() throws Exception {
		Files.writeString(built.resolve("probe-solver"), "BUILT");
		int solver = insertSolver(source, BUILD_FAILED);
		int older = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, older, StatusCode.STATUS_COMPLETE);
		FinishedSolverBuild read = finishedBuildOf(solver);
		assertTrue(read.isNewestBuildOfSolver(), "read as the newest");
		int newer = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, newer, StatusCode.STATUS_PENDING_SUBMIT);

		assertFalse(Solvers.recordBuildOutcome(read, read.getBuiltPath()), "a newer build job exists now");
		assertEquals(BUILD_FAILED, buildStatus(solver));
		assertEquals(source.toString(), path(solver));

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(BUILD_FAILED, buildStatus(solver), "still waiting for the newer job");
		assertEquals(source.toString(), path(solver));
		assertTrue(deleted(older), "the older job is retired");
		assertFalse(deleted(newer), "the newer one has not finished");
	}

	/** Only the solver's own build jobs can be newer: a later build of another solver holds nothing up. */
	@Test
	public void aLaterBuildJobOfAnotherSolverDoesNotHoldUpThisOne() throws Exception {
		Files.writeString(built.resolve("probe-solver"), "BUILT");
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, bench, StatusCode.STATUS_COMPLETE);
		int other = insertSolver(source, UNBUILT);
		int otherBench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(other, otherBench, StatusCode.STATUS_PENDING_SUBMIT);

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(BUILT_BY_STAREXEC, buildStatus(solver));
		assertEquals(built.toString(), path(solver));
		assertTrue(deleted(bench));
		assertEquals(UNBUILT, buildStatus(other), "the other solver still waits for its own build");
		assertFalse(deleted(otherBench));
	}

	/**
	 * A job can be created for a solver that is still being built: its pairs wait for the build. Such a job is
	 * not a newer build, or the build it waits for would never be applied.
	 */
	@Test
	public void aLaterOrdinaryJobOfTheSolverDoesNotHoldUpItsBuild() throws Exception {
		Files.writeString(built.resolve("probe-solver"), "BUILT");
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, bench, StatusCode.STATUS_COMPLETE);
		int problem = insertBenchmark("real-benchmark.p", benchmarkFile());
		job(false, solver, problem, StatusCode.STATUS_PENDING_SUBMIT);

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(BUILT_BY_STAREXEC, buildStatus(solver));
		assertEquals(built.toString(), path(solver));
		assertTrue(deleted(bench));
		assertFalse(deleted(problem), "the ordinary job's benchmark is not a helper");
	}

	private void prepareExecution(FinishedSolverBuild build) throws Exception {
		Path parent = Files.createDirectory(folder.toPath().resolve("execution-staging"),
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		try (Connection connection = Common.getConnection()) {
			SolverBuildStaging.prepare(connection, build.getPairId(), 1, parent);
		}
	}

	@ParameterizedTest @ValueSource(ints = {7, 11})
	public void executionBoundBuildsAreNotConsumedByLegacyRecorder(int status) throws Exception {
		int solver = insertSolver(source, UNBUILT);
		Path input = benchmarkFile();
		int benchmark = insertBenchmark("starexec_build", input);
		buildJob(solver, benchmark, StatusCode.toStatusCode(status));
		prepareExecution(finishedBuildOf(solver));
		SolverBuildRecorder.recordFinishedBuilds();
		assertEquals(UNBUILT, buildStatus(solver));
		assertEquals(source.toString(), path(solver));
		assertFalse(deleted(benchmark));
		assertTrue(Files.exists(input));
	}

	@Test
	public void anExecutionPreparedAfterTheReadCannotUseLegacyCAS() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		int benchmark = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, benchmark, StatusCode.STATUS_COMPLETE);
		FinishedSolverBuild snapshot = finishedBuildOf(solver);
		prepareExecution(snapshot);
		assertFalse(Solvers.recordBuildOutcome(snapshot, snapshot.getBuiltPath()));
		assertEquals(UNBUILT, buildStatus(solver));
		assertEquals(source.toString(), path(solver));
		assertFalse(deleted(benchmark));
	}

	@Test
	public void aSupersededExecutionBuildKeepsItsHelper() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		Path input = benchmarkFile();
		int older = insertBenchmark("starexec_build", input);
		buildJob(solver, older, StatusCode.STATUS_COMPLETE);
		prepareExecution(finishedBuildOf(solver));
		int newer = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, newer, StatusCode.STATUS_PENDING_SUBMIT);
		SolverBuildRecorder.recordFinishedBuilds();
		assertEquals(UNBUILT, buildStatus(solver));
		assertFalse(deleted(older));
		assertFalse(deleted(newer));
		assertTrue(Files.exists(input));
	}

	@Test
	public void anExecutionPreparedAfterTheReadCannotRetireItsHelper() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		Path input = benchmarkFile();
		int benchmark = insertBenchmark("starexec_build", input);
		buildJob(solver, benchmark, StatusCode.STATUS_COMPLETE);
		FinishedSolverBuild snapshot = finishedBuildOf(solver);
		prepareExecution(snapshot);
		assertTrue(Solvers.claimLegacyBuildRetirement(snapshot).isEmpty());
		assertFalse(deleted(benchmark)); assertTrue(Files.exists(input));
	}

	private boolean prepareMetadata(Connection connection, FinishedSolverBuild snapshot, int attempt) throws SQLException {
		return SolverBuildArtifacts.prepare(connection, snapshot.getPairId(), attempt, UUID.randomUUID(),
				folder.toPath().resolve("reserved-execution").toAbsolutePath());
	}

	private String finishLegacy(Connection connection, FinishedSolverBuild snapshot, boolean apply) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT starexec.finish_legacy_solver_build(?,?,?,?,?,?,?,?,?,?)")) {
			statement.setInt(1, snapshot.getJobId()); statement.setInt(2, snapshot.getPairId());
			statement.setInt(3, snapshot.getBenchmarkId()); statement.setInt(4, snapshot.getSolverId());
			statement.setString(5, snapshot.getSolverPath()); statement.setInt(6, snapshot.getSolverBuildStatus().getVal());
			statement.setInt(7, snapshot.getPairStatus().getVal()); statement.setString(8, snapshot.getBuiltPath());
			statement.setString(9, R.SOLVER_BUILD_SCRIPT); statement.setBoolean(10, apply);
			try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
		}
	}

	private Connection directConnection() throws SQLException {
		return java.sql.DriverManager.getConnection(R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD);
	}

	private static int backendPid(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()"); ResultSet result = statement.executeQuery()) {
			assertTrue(result.next()); return result.getInt(1);
		}
	}

	private void awaitLock(int pid) {
		await().atMost(5, TimeUnit.SECONDS).until(() -> "t".equals(read(
				"SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')", pid)));
	}

	@Test
	public void aCommittedLegacyOutcomePreventsPreparationEvenAfterARerun() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		int benchmark = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, benchmark, StatusCode.STATUS_COMPLETE);
		FinishedSolverBuild snapshot = finishedBuildOf(solver);
		assertTrue(Solvers.recordBuildOutcome(snapshot, snapshot.getBuiltPath()));
		rebuild(solver);
		try (Connection connection = directConnection()) {
			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE starexec.job_pair_attempts SET current_attempt_no=current_attempt_no+1 WHERE pair_id=?")) {
				statement.setInt(1, snapshot.getPairId()); assertEquals(1, statement.executeUpdate());
			}
			assertFalse(prepareMetadata(connection, snapshot, 2));
		}
		assertEquals(source.toString(), path(solver)); assertFalse(deleted(benchmark));
	}

	@Test
	public void aRetiredInputCannotBePreparedByAnotherPairBeforeFileDeletion() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		Path input = benchmarkFile(); int benchmark = insertBenchmark("starexec_build", input);
		buildJob(solver, benchmark, StatusCode.STATUS_COMPLETE);
		FinishedSolverBuild snapshot = finishedBuildOf(solver);
		assertTrue(Solvers.recordBuildOutcome(snapshot, snapshot.getBuiltPath()));
		int other = insertSolver(source, UNBUILT); buildJob(other, benchmark, StatusCode.STATUS_COMPLETE);
		try (Connection connection = directConnection()) { assertFalse(prepareMetadata(connection, finishedBuildOf(other), 1)); }
		assertTrue(Files.exists(input)); assertFalse(deleted(benchmark));
	}

	@Test
	public void sharedHelpersAreNeverClaimedOrDeletedByTheLegacyRecorder() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		Path input = benchmarkFile(); int benchmark = insertBenchmark("starexec_build", input);
		buildJob(solver, benchmark, StatusCode.STATUS_COMPLETE);
		FinishedSolverBuild snapshot = finishedBuildOf(solver);
		int other = insertSolver(source, UNBUILT); buildJob(other, benchmark, StatusCode.STATUS_PENDING_SUBMIT);
		assertFalse(Solvers.recordBuildOutcome(snapshot, snapshot.getBuiltPath()));
		assertTrue(Solvers.claimLegacyBuildRetirement(snapshot).isEmpty());
		SolverBuildRecorder.recordFinishedBuilds();
		assertEquals(UNBUILT, buildStatus(solver)); assertFalse(deleted(benchmark)); assertTrue(Files.exists(input));
	}

	@Test
	public void preparedExecutionWinsTheRaceAgainstLegacyMetadata() throws Exception {
		int solver = insertSolver(source, UNBUILT); int benchmark = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, benchmark, StatusCode.STATUS_COMPLETE); FinishedSolverBuild snapshot = finishedBuildOf(solver);
		var executor = Executors.newSingleThreadExecutor();
		try (Connection preparing = directConnection(); Connection recording = directConnection()) {
			preparing.setAutoCommit(false); assertTrue(prepareMetadata(preparing, snapshot, 1));
			int pid = backendPid(recording);
			var result = executor.submit(() -> finishLegacy(recording, snapshot, true));
			awaitLock(pid); preparing.commit();
			assertNull(result.get(5, TimeUnit.SECONDS)); assertEquals(UNBUILT, buildStatus(solver));
			assertFalse(deleted(benchmark));
		} finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
	}

	@Test
	public void claimedRetirementWinsTheRaceAgainstExecutionPreparation() throws Exception {
		int solver = insertSolver(source, UNBUILT); Path input = benchmarkFile();
		int benchmark = insertBenchmark("starexec_build", input); buildJob(solver, benchmark, StatusCode.STATUS_COMPLETE);
		FinishedSolverBuild snapshot = finishedBuildOf(solver); var executor = Executors.newSingleThreadExecutor();
		try (Connection retiring = directConnection(); Connection preparing = directConnection()) {
			retiring.setAutoCommit(false); assertEquals(input.toString(), finishLegacy(retiring, snapshot, false));
			int pid = backendPid(preparing); var result = executor.submit(() -> prepareMetadata(preparing, snapshot, 1));
			awaitLock(pid); retiring.commit();
			assertFalse(result.get(5, TimeUnit.SECONDS)); assertTrue(deleted(benchmark));
			assertTrue(Files.exists(input), "the committed claim preserves its path until filesystem cleanup");
		} finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
	}

	@Test
	public void helperRetirementAndSolverDeletionDoNotDeadlock() throws Exception {
		int solver = insertSolver(source, UNBUILT); Path input = benchmarkFile();
		int benchmark = insertBenchmark("starexec_build", input); buildJob(solver, benchmark, StatusCode.STATUS_COMPLETE);
		FinishedSolverBuild snapshot = finishedBuildOf(solver); var executor = Executors.newFixedThreadPool(2);
		try (Connection holder = directConnection(); Connection retiring = directConnection(); Connection deleting = directConnection()) {
			holder.setAutoCommit(false);
			try (PreparedStatement ps = holder.prepareStatement("SELECT id FROM starexec.solvers WHERE id=? FOR UPDATE")) {
				ps.setInt(1, solver); try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); }
			}
			int retirementPid = backendPid(retiring); int deletionPid = backendPid(deleting);
			var retirement = executor.submit(() -> finishLegacy(retiring, snapshot, false));
			awaitLock(retirementPid);
			var deletion = executor.submit(() -> {
				try (PreparedStatement ps = deleting.prepareStatement("SELECT count(*) FROM starexec.request_solver_storage_deletion(?)")) {
					ps.setInt(1, solver); try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getInt(1); }
				}
			});
			awaitLock(deletionPid); holder.commit();
			assertEquals(input.toString(), retirement.get(10, TimeUnit.SECONDS));
			assertEquals(1, deletion.get(10, TimeUnit.SECONDS));
			assertTrue(deleted(benchmark)); assertTrue(Files.exists(input));
		} finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
	}

	@Test
	public void helperAccountingIsSettledExactlyOnceBeforeFileCleanup() throws Exception {
		int solver = insertSolver(source, UNBUILT); Path input = benchmarkFile();
		int benchmark = insertBenchmark("starexec_build", input); buildJob(solver, benchmark, StatusCode.STATUS_COMPLETE);
		FinishedSolverBuild snapshot = finishedBuildOf(solver);
		long original = Long.parseLong(read("SELECT disk_size FROM starexec.users WHERE id=?", userId));
		try (Connection connection = directConnection()) {
			try {
				try (PreparedStatement statement = connection.prepareStatement("UPDATE starexec.users SET disk_size=disk_size+17 WHERE id=?")) {
					statement.setInt(1, userId); assertEquals(1, statement.executeUpdate());
				}
				try (PreparedStatement statement = connection.prepareStatement("UPDATE starexec.benchmarks SET disk_size=17 WHERE id=?")) {
					statement.setInt(1, benchmark); assertEquals(1, statement.executeUpdate());
				}
				assertEquals(input.toString(), Solvers.claimLegacyBuildRetirement(snapshot).orElseThrow());
				assertEquals(original, Long.parseLong(read("SELECT disk_size FROM starexec.users WHERE id=?", userId)));
				assertTrue(Solvers.claimLegacyBuildRetirement(snapshot).isEmpty());
				assertEquals(original, Long.parseLong(read("SELECT disk_size FROM starexec.users WHERE id=?", userId)));
				assertEquals("0", read("SELECT disk_size FROM starexec.benchmarks WHERE id=?", benchmark));
				assertTrue(Files.exists(input));
			} finally {
				try (PreparedStatement statement = connection.prepareStatement("UPDATE starexec.users SET disk_size=? WHERE id=?")) {
					statement.setLong(1, original); statement.setInt(2, userId); statement.executeUpdate();
				}
			}
		}
	}

	@ParameterizedTest @ValueSource(strings = {"deleted-helper", "unknown-status"})
	public void unavailableBuildInputsOrStatusCannotAcquireExecutionStorage(String condition) throws Exception {
		int solver = insertSolver(source, UNBUILT); int benchmark = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, benchmark, StatusCode.STATUS_COMPLETE); FinishedSolverBuild snapshot = finishedBuildOf(solver);
		try (Connection connection = directConnection()) {
			String sql = condition.equals("deleted-helper") ? "UPDATE starexec.benchmarks SET deleted=true WHERE id=?"
					: "UPDATE starexec.solvers SET build_status=NULL WHERE id=?";
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				statement.setInt(1, condition.equals("deleted-helper") ? benchmark : solver); statement.executeUpdate();
			}
			assertFalse(prepareMetadata(connection, snapshot, 1));
			assertNull(SolverBuildArtifacts.find(connection, snapshot.getPairId(), 1).orElseThrow().executionToken());
		}
	}

	// ---------------------------------------------------------------------------- fixture

	/** A helper benchmark file where addBenchmarkFromText puts one; a kept sibling bounds the cleanup. */
	private Path benchmarkFile() throws Exception {
		Path userDir = Files.createDirectories(folder.toPath().resolve("Benchmarks/" + userId));
		Files.writeString(userDir.resolve("keep"), "KEEP");
		Path dir = Files.createDirectory(userDir.resolve(UUID.randomUUID().toString()));
		return Files.writeString(dir.resolve("starexec_build"), "dummy benchmark");
	}

	private int insertSolver(Path path, int buildStatus) throws SQLException {
		int id = insertReturningId("INSERT INTO starexec.solvers (user_id, name, uploaded, path, disk_size,"
				+ " build_status) VALUES (?, ?, now(), ?, 0, ?) RETURNING id", ps -> {
			ps.setInt(1, userId);
			ps.setString(2, "build-record-" + UUID.randomUUID().toString().substring(0, 8));
			ps.setString(3, path.toString());
			ps.setInt(4, buildStatus);
		});
		solvers.add(id);
		return id;
	}

	private int insertBenchmark(String name, Path file) throws SQLException {
		int id = insertReturningId("INSERT INTO starexec.benchmarks (user_id, name, uploaded, path, disk_size)"
				+ " VALUES (?, ?, now(), ?, 0) RETURNING id", ps -> {
			ps.setInt(1, userId);
			ps.setString(2, name);
			ps.setString(3, file.toString());
		});
		benchmarks.add(id);
		return id;
	}

	private void buildJob(int solver, int bench, StatusCode status) throws SQLException {
		job(true, solver, bench, status);
	}

	/** A one-pair job, laid out as addBuildJob lays out a build job: one stage, with no configuration row. */
	private void job(boolean buildJob, int solver, int bench, StatusCode status) throws SQLException {
		int job = insertReturningId("INSERT INTO starexec.jobs (user_id, name, total_pairs, disk_size, buildjob)"
				+ " VALUES (?, 'build-record-test', 1, 0, ?) RETURNING id", ps -> {
			ps.setInt(1, userId);
			ps.setBoolean(2, buildJob);
		});
		jobs.add(job);
		int pair = insertReturningId("INSERT INTO starexec.job_pairs (job_id, bench_id, bench_name, status_code,"
				+ " primary_jobpair_data) VALUES (?, ?, 'starexec_build', ?, 1) RETURNING id", ps -> {
			ps.setInt(1, job);
			ps.setInt(2, bench);
			ps.setInt(3, status.getVal());
		});
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement("INSERT INTO starexec.jobpair_stage_data"
						+ " (jobpair_id, stage_number, status_code, solver_id, config_id, solver_name, config_name,"
						+ " disk_size) VALUES (?, 1, ?, ?, -1, 'probe', 'starexec_build', 0)")) {
			ps.setInt(1, pair);
			ps.setInt(2, status.getVal());
			ps.setInt(3, solver);
			ps.executeUpdate();
		}
	}

	/** The solver of a build job as getFinishedBuildsToRecord reads it, for the update to compare with. */
	private FinishedSolverBuild asRead(int solver, Path path, int buildStatus) throws Exception {
		int benchmark = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, benchmark, StatusCode.STATUS_COMPLETE);
		FinishedSolverBuild actual = finishedBuildOf(solver);
		return new FinishedSolverBuild(actual.getJobId(), actual.getPairId(), StatusCode.STATUS_COMPLETE, benchmark, solver, path.toString(),
				SolverBuildStatusCode.toStatusCode(buildStatus), true);
	}

	/** The one finished, unapplied build job of the solver, as a pass reads it. */
	private static FinishedSolverBuild finishedBuildOf(int solver) throws SQLException {
		List<FinishedSolverBuild> builds = Solvers.getFinishedBuildsToRecord().stream()
				.filter(b -> b.getSolverId() == solver)
				.collect(Collectors.toList());
		assertEquals(1, builds.size(), "finished build jobs of solver " + solver);
		return builds.get(0);
	}

	/** What the three-hourly sweep does to the solver of a build pair it finds broken. */
	private static void setBuildStatus(int solver, int buildStatus) {
		Solver s = new Solver();
		s.setId(solver);
		Solvers.setSolverBuildStatus(s, buildStatus);
	}

	private int buildStatus(int solver) throws SQLException {
		return Integer.parseInt(read("SELECT build_status FROM starexec.solvers WHERE id = ?", solver));
	}

	private String path(int solver) throws SQLException {
		return read("SELECT path FROM starexec.solvers WHERE id = ?", solver);
	}

	private boolean deleted(int bench) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement("SELECT deleted FROM starexec.benchmarks WHERE id = ?")) {
			ps.setInt(1, bench);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next(), "benchmark " + bench + " must exist");
				return rs.getBoolean(1);
			}
		}
	}

	/** What a rebuild does to the solver before it adds the rebuild's build job. */
	private static void rebuild(int solver) throws SQLException {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement("SELECT starexec.RebuildSolver(?)")) {
			ps.setInt(1, solver);
			ps.executeQuery().close();
		}
	}

	private interface Binder {
		void bind(PreparedStatement ps) throws SQLException;
	}

	private static int insertReturningId(String sql, Binder binder) throws SQLException {
		try (Connection con = Common.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
			binder.bind(ps);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				return rs.getInt(1);
			}
		}
	}

	private static boolean cannotRead(Path path) {
		try {
			Files.readAttributes(path, BasicFileAttributes.class);
			return false;
		} catch (IOException e) {
			return true;
		}
	}

	private static String read(String sql, int id) throws SQLException {
		try (Connection con = Common.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next(), "row " + id + " must exist");
				return rs.getString(1);
			}
		}
	}

	private static void delete(Connection con, String sql, int id) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setInt(1, id);
			ps.executeUpdate();
		}
	}
}
