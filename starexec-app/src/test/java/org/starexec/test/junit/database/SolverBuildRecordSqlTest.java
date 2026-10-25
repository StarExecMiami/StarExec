package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.data.database.Common;
import org.starexec.data.database.Solvers;
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
	public void theOutcomeIsOnlyAppliedWhileTheSolverWaitsAtTheSamePath() throws Exception {
		int alreadyBuilt = insertSolver(source, BUILT_BY_STAREXEC);
		assertFalse("a solver built in between",
				Solvers.recordBuildOutcome(alreadyBuilt, source.toString(), "/elsewhere"));
		assertEquals(BUILT_BY_STAREXEC, buildStatus(alreadyBuilt));
		assertEquals(source.toString(), path(alreadyBuilt));

		int moved = insertSolver(built, UNBUILT);
		assertFalse("a solver whose path changed in between",
				Solvers.recordBuildOutcome(moved, source.toString(), null));
		assertEquals(UNBUILT, buildStatus(moved));
		assertEquals(built.toString(), path(moved));

		int rebuilt = insertSolver(source, BUILD_FAILED);
		assertTrue("a failed solver being rebuilt waits for the rebuild",
				Solvers.recordBuildOutcome(rebuilt, source.toString(), built.toString()));
		assertEquals(BUILT_BY_STAREXEC, buildStatus(rebuilt));
		assertEquals(built.toString(), path(rebuilt));
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
