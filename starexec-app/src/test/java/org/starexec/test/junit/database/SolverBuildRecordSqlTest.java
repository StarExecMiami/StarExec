package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.data.database.Common;
import org.starexec.data.database.Solvers;
import org.starexec.data.to.FinishedSolverBuild;
import org.starexec.data.to.Solver;
import org.starexec.data.to.SolverBuildStatus.SolverBuildStatusCode;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.jobs.SolverBuildRecorder;
import org.starexec.test.util.DatabaseTestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

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

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private final List<Integer> jobs = new ArrayList<>();
	private final List<Integer> benchmarks = new ArrayList<>();
	private final List<Integer> solvers = new ArrayList<>();
	private int userId;
	private Path source;
	private Path built;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("SolverBuildRecordSqlTest");
		Common.initialize();
	}

	@Before
	public void createSolverFiles() throws Exception {
		try (Connection con = Common.getConnection();
				PreparedStatement ps = con.prepareStatement("SELECT min(id) FROM starexec.users");
				ResultSet rs = ps.executeQuery()) {
			assertTrue(rs.next());
			userId = rs.getInt(1);
			assertTrue("the database must have a user", userId > 0);
		}
		Path solverDir = Files.createDirectories(folder.getRoot().toPath().resolve("Solvers/3/probe"));
		source = Files.createDirectory(solverDir.resolve("20261025-09.15.48.756_src"));
		Files.writeString(source.resolve("starexec_build"), "BUILD SCRIPT");
		// The upload creates the built path empty; a build that worked copied the solver into it.
		built = Files.createDirectory(solverDir.resolve("20261025-09.15.48.756"));
	}

	@After
	public void dropFixture() throws SQLException {
		try (Connection con = Common.getConnection()) {
			for (int job : jobs) {
				delete(con, "DELETE FROM starexec.job_pairs WHERE job_id = ?", job);
				delete(con, "DELETE FROM starexec.jobs WHERE id = ?", job);
			}
			for (int bench : benchmarks) {
				delete(con, "DELETE FROM starexec.benchmarks WHERE id = ?", bench);
			}
			for (int solver : solvers) {
				delete(con, "DELETE FROM starexec.solvers WHERE id = ?", solver);
			}
		}
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
		assertTrue("the helper benchmark is deleted", deleted(bench));
		assertFalse("with its file", Files.exists(benchFile));
	}

	@Test
	public void aFailedBuildMakesTheSolverFailedAndKeepsItsSource() throws Exception {
		int solver = insertSolver(source, UNBUILT);
		int bench = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, bench, StatusCode.ERROR_RUNSCRIPT);

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals(BUILD_FAILED, buildStatus(solver));
		assertEquals("a rebuild starts from the uploaded source", source.toString(), path(solver));
		assertTrue(deleted(bench));
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
		assertTrue("the older job is retired", deleted(older));
		assertFalse("the newer one is still running", deleted(newer));
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
		assertEquals("as RebuildSolver leaves it", UNBUILT, buildStatus(solver));
		assertEquals(source.toString(), path(solver));

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals("the applied build is not applied again", UNBUILT, buildStatus(solver));
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
			assumeTrue("needs a user that file permissions apply to", cannotRead(built));
			SolverBuildRecorder.recordFinishedBuilds();
		} finally {
			Files.setPosixFilePermissions(solverDir, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
		assertEquals(UNBUILT, buildStatus(solver));
		assertEquals(source.toString(), path(solver));
		assertFalse("not recorded, so not retired", deleted(bench));

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals("recorded once it can be read", BUILT_BY_STAREXEC, buildStatus(solver));
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
		assertFalse("a solver built in between",
				Solvers.recordBuildOutcome(asRead(alreadyBuilt, source, UNBUILT), "/elsewhere"));
		assertEquals(BUILT_BY_STAREXEC, buildStatus(alreadyBuilt));
		assertEquals(source.toString(), path(alreadyBuilt));

		int moved = insertSolver(built, UNBUILT);
		assertFalse("a solver whose path changed in between",
				Solvers.recordBuildOutcome(asRead(moved, source, UNBUILT), null));
		assertEquals(UNBUILT, buildStatus(moved));
		assertEquals(built.toString(), path(moved));

		int rebuilt = insertSolver(source, BUILD_FAILED);
		assertTrue("a failed solver being rebuilt waits for the rebuild",
				Solvers.recordBuildOutcome(asRead(rebuilt, source, BUILD_FAILED), built.toString()));
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

		assertFalse("the status read is not the solver's any more",
				Solvers.recordBuildOutcome(read, read.getBuiltPath()));
		assertEquals(BUILD_FAILED, buildStatus(solver));
		assertEquals(source.toString(), path(solver));

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals("decided again, from the solver as it is now", BUILT_BY_STAREXEC, buildStatus(solver));
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
		assertTrue("read as the newest", read.isNewestBuildOfSolver());
		int newer = insertBenchmark("starexec_build", benchmarkFile());
		buildJob(solver, newer, StatusCode.STATUS_PENDING_SUBMIT);

		assertFalse("a newer build job exists now", Solvers.recordBuildOutcome(read, read.getBuiltPath()));
		assertEquals(BUILD_FAILED, buildStatus(solver));
		assertEquals(source.toString(), path(solver));

		SolverBuildRecorder.recordFinishedBuilds();

		assertEquals("still waiting for the newer job", BUILD_FAILED, buildStatus(solver));
		assertEquals(source.toString(), path(solver));
		assertTrue("the older job is retired", deleted(older));
		assertFalse("the newer one has not finished", deleted(newer));
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
		assertEquals("the other solver still waits for its own build", UNBUILT, buildStatus(other));
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
		assertFalse("the ordinary job's benchmark is not a helper", deleted(problem));
	}

	// ---------------------------------------------------------------------------- fixture

	/** A helper benchmark file where addBenchmarkFromText puts one; a kept sibling bounds the cleanup. */
	private Path benchmarkFile() throws Exception {
		Path userDir = Files.createDirectories(folder.getRoot().toPath().resolve("Benchmarks/" + userId));
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
	private static FinishedSolverBuild asRead(int solver, Path path, int buildStatus) {
		return new FinishedSolverBuild(0, 0, StatusCode.STATUS_COMPLETE, 0, solver, path.toString(),
				SolverBuildStatusCode.toStatusCode(buildStatus), true);
	}

	/** The one finished, unapplied build job of the solver, as a pass reads it. */
	private static FinishedSolverBuild finishedBuildOf(int solver) throws SQLException {
		List<FinishedSolverBuild> builds = Solvers.getFinishedBuildsToRecord().stream()
				.filter(b -> b.getSolverId() == solver)
				.collect(Collectors.toList());
		assertEquals("finished build jobs of solver " + solver, 1, builds.size());
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
				assertTrue("benchmark " + bench + " must exist", rs.next());
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
				assertTrue("row " + id + " must exist", rs.next());
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
