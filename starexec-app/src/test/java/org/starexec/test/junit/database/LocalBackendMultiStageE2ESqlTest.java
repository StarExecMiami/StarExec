package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.backend.LocalBackend;
import org.starexec.backend.StageStatusSnapshots;
import org.starexec.data.database.Common;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A real LocalBackend execution, end to end.
 *
 * <p>This is the axis the rest of the #130 suite does not cover. Those tests drive
 * {@code LocalJobMonitor}'s poll loop directly over hand-written artifacts, which proves the
 * ingestion contract but assumes the process half. Here {@code LocalBackend} actually forks a
 * script, that script actually writes the artifacts, {@code LocalBackend} registers the pair
 * with the monitor it created and started itself, and the monitor ingests into a real
 * database. Nothing about the execution path is simulated.
 *
 * <p>No cluster or container is required, which is the point:
 * {@code STAREXEC_BACKEND_TYPE} defaults to {@code local}, and this backend runs jobs as
 * ordinary local processes. {@code LocalBackendTests} already forks real scripts this way; it
 * passes {@code pairId = -1} so nothing reaches the database. Passing a real pair is the whole
 * difference.
 *
 * <p>The script stands in for the shipped job script rather than being it -- running
 * {@code functions.bash} for real would drag in runsolver, a solver and a benchmark. What it
 * reproduces exactly is the artifact protocol: one {@code status.json} truncated per stage,
 * plus a {@code stage-status/<n>.json} per stage, which is what ingestion consumes.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class LocalBackendMultiStageE2ESqlTest extends Common {

	private static final String TAG = "local-e2e-probe";

	private static final int USER_ID = 91001;
	private static final int SPACE_ID = 91001;
	private static final int QUEUE_ID = 91001;
	private static final int SOLVER_ID = 91001;
	private static final int CONFIG_ID = 91001;
	private static final int BENCH_ID = 91001;
	private static final int JOB_ID = 91001;
	private static final int PAIR_ID = 91001;

	private static final int ENQUEUED = StatusCode.STATUS_ENQUEUED.getVal();
	private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
	private static final int NOT_REACHED = StatusCode.STATUS_NOT_REACHED.getVal();

	/** Generous: this waits on a real fork plus a real poll cycle, not on a sleep. */
	private static final long TIMEOUT_MS = 60_000L;

	private LocalBackend backend;
	private Path work;
	private Path outputDir;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("LocalBackendMultiStageE2ESqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws Exception {
		cleanUp();
		work = Files.createTempDirectory("local-e2e");
		outputDir = work.resolve("logs");
		Files.createDirectories(outputDir);
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("INSERT INTO starexec.users (id,email,first_name,last_name,institution,"
					+ "created,password,disk_quota,disk_size) VALUES (" + USER_ID + ",'"
					+ TAG + "@test','A','P','I',NOW(),'x',1000000000,0)");
			s.execute("INSERT INTO starexec.spaces (id,name,created,locked) VALUES ("
					+ SPACE_ID + ",'" + TAG + "',NOW(),false)");
			s.execute("INSERT INTO starexec.queues (id,name,status) VALUES ("
					+ QUEUE_ID + ",'" + TAG + "','ACTIVE') ON CONFLICT DO NOTHING");
			s.execute("INSERT INTO starexec.solvers (id,user_id,name,uploaded,path,description,"
					+ "downloadable,disk_size) VALUES (" + SOLVER_ID + "," + USER_ID + ",'"
					+ TAG + "',NOW(),'/tmp/" + TAG + "','d',false,0)");
			s.execute("INSERT INTO starexec.configurations (id,solver_id,name,description,updated)"
					+ " VALUES (" + CONFIG_ID + "," + SOLVER_ID + ",'" + TAG + "','d',NOW())");
			s.execute("INSERT INTO starexec.benchmarks (id,user_id,name,uploaded,path,description,"
					+ "downloadable,disk_size) VALUES (" + BENCH_ID + "," + USER_ID + ",'"
					+ TAG + "',NOW(),'/tmp/" + TAG + "','d',false,0)");
			s.execute("INSERT INTO starexec.jobs (id,user_id,name,queue_id,created,description,"
					+ "total_pairs,disk_size) VALUES (" + JOB_ID + "," + USER_ID + ",'" + TAG
					+ "'," + QUEUE_ID + ",NOW(),'d',1,0)");
			s.execute("INSERT INTO starexec.job_pairs (id,job_id,bench_id,status_code) VALUES ("
					+ PAIR_ID + "," + JOB_ID + "," + BENCH_ID + "," + ENQUEUED + ")");
			for (int stage = 1; stage <= 3; stage++) {
				s.execute("INSERT INTO starexec.jobpair_stage_data (jobpair_id,stage_number,"
						+ "status_code,solver_id,config_id,disk_size) VALUES (" + PAIR_ID + ","
						+ stage + "," + ENQUEUED + "," + SOLVER_ID + "," + CONFIG_ID + ",0)");
			}
		}
	}

	@After
	public void cleanUp() throws SQLException {
		if (backend != null) {
			try {
				backend.destroyIf();
			} catch (Exception ignored) {
				// Teardown of a backend that may never have started is not a test result.
			}
			backend = null;
		}
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			quietly(s, "DELETE FROM starexec.job_pair_repro_manifests WHERE pair_id=" + PAIR_ID);
			quietly(s, "DELETE FROM starexec.job_pair_completion WHERE pair_id=" + PAIR_ID);
			quietly(s, "DELETE FROM starexec.jobpair_stage_data WHERE jobpair_id=" + PAIR_ID);
			quietly(s, "DELETE FROM starexec.job_pairs WHERE id=" + PAIR_ID);
			quietly(s, "DELETE FROM starexec.jobs WHERE id=" + JOB_ID);
			quietly(s, "DELETE FROM starexec.configurations WHERE id=" + CONFIG_ID);
			quietly(s, "DELETE FROM starexec.solvers WHERE id=" + SOLVER_ID);
			quietly(s, "DELETE FROM starexec.benchmarks WHERE id=" + BENCH_ID);
			quietly(s, "DELETE FROM starexec.queues WHERE id=" + QUEUE_ID);
			quietly(s, "DELETE FROM starexec.spaces WHERE id=" + SPACE_ID);
			quietly(s, "DELETE FROM starexec.users WHERE id=" + USER_ID);
		}
	}

	/**
	 * Two stages, executed by a real forked process, ingested by the real monitor.
	 *
	 * <p>Stage 1's row is the discriminator: before this work it stayed at whatever it was
	 * enqueued with, because only the final {@code status.json} was ever read.
	 */
	@Test
	public void aRealTwoStageExecutionRecordsBothStages() throws Exception {
		submit(twoStageScript());

		awaitTerminal();

		assertEquals("stage 1 ran and must say so", COMPLETE, stageStatus(1));
		assertEquals("stage 2 ran and must say so", COMPLETE, stageStatus(2));
		assertEquals("stage 3 was never reached", NOT_REACHED, stageStatus(3));
		assertEquals(COMPLETE, pairStatus());
		assertEquals("pair completion fires once", 1, completions());

		// The process really did write the protocol, rather than the test writing it.
		assertTrue(Files.exists(outputDir.resolve("stage-status/1.json")));
		assertTrue(Files.exists(outputDir.resolve("stage-status/2.json")));

		// And what it wrote is the protocol the shared validator accepts -- read back through
		// StageStatusSnapshots itself, so this fixture cannot drift into a private dialect
		// that only this test understands. If the production contract changes, this fails.
		Map<Integer, Integer> parsed = StageStatusSnapshots.read(outputDir, PAIR_ID);
		assertEquals("the shared validator must see both stages", 2, parsed.size());
		assertEquals(Integer.valueOf(COMPLETE), parsed.get(1));
		assertEquals(Integer.valueOf(COMPLETE), parsed.get(2));
	}

	/**
	 * The regression: a pair that stops after its first stage.
	 *
	 * <p>The fixture seeds three {@code jobpair_stage_data} rows, as the two-stage case does --
	 * the pair is configured for three stages and only the first one runs. So stage 2 reading
	 * {@code STATUS_NOT_REACHED} is the correct outcome, written by
	 * {@code UpdatePairStatusPrecise} for every {@code stage_number > terminal}, not evidence of
	 * a one-stage pair. Named for the execution rather than the configuration, because the
	 * earlier name ("single stage execution ... stage2 NOT_REACHED") read as a contradiction.
	 *
	 * <p>What it guards is that stage-history ingestion changes nothing when there is no
	 * earlier history to ingest.
	 */
	@Test
	public void aRealExecutionThatStopsAfterStageOneIsUnchanged() throws Exception {
		submit(firstStageOnlyScript());

		awaitTerminal();

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals("configured but never reached", NOT_REACHED, stageStatus(2));
		assertEquals("configured but never reached", NOT_REACHED, stageStatus(3));
		assertEquals(COMPLETE, pairStatus());
		assertEquals(1, completions());
	}

	// ------------------------------------------------------------------------- harness

	private void submit(String script) throws Exception {
		Path scriptPath = work.resolve("job.sh");
		Files.writeString(scriptPath, script);
		Files.setPosixFilePermissions(scriptPath, PosixFilePermissions.fromString("rwxr-xr-x"));

		backend = new LocalBackend();
		backend.initialize("");

		// logPath is a FILE inside the output directory; LocalBackend takes its parent as the
		// output dir, exactly as production does via JobPairs.getStdout(pairId).
		int execId = backend.submitScript(
				PAIR_ID,
				scriptPath.toString(),
				work.toString(),
				outputDir.resolve(PAIR_ID + ".txt").toString());
		assertTrue("submission must be accepted, got execId " + execId, execId > 0);
	}

	/** Polls the database, not a sleep: the real monitor decides when this becomes true. */
	private void awaitTerminal() throws Exception {
		long deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
		while (System.nanoTime() - deadline < 0) {
			if (pairStatus() != ENQUEUED && pairStatus() != StatusCode.STATUS_RUNNING.getVal()) {
				return;
			}
			Thread.sleep(200);
		}
		throw new AssertionError("pair " + PAIR_ID + " never reached a terminal status; it is "
				+ pairStatus() + " and its output is in " + outputDir);
	}

	/**
	 * A two-stage run, in the artifact protocol the shipped helper uses: one status.json that
	 * each stage truncates, and one durable record per stage beside it.
	 */
	private String twoStageScript() {
		return "#!/bin/bash\nset -eu\n"
				+ "OUT=" + outputDir + "\n"
				+ "mkdir -p \"$OUT/stage-status\"\n"
				+ record(1, COMPLETE)
				+ record(2, COMPLETE)
				+ runsolverArtifacts();
	}

	private String firstStageOnlyScript() {
		return "#!/bin/bash\nset -eu\n"
				+ "OUT=" + outputDir + "\n"
				+ "mkdir -p \"$OUT/stage-status\"\n"
				+ record(1, COMPLETE)
				+ runsolverArtifacts();
	}

	/** One stage: the per-stage record, then status.json truncated to point at this stage. */
	private String record(int stage, int status) {
		String json = "{\\\"pairId\\\":" + PAIR_ID + ",\\\"status\\\":" + status
				+ ",\\\"stageNumber\\\":" + stage + ",\\\"timestamp\\\":1788818872}";
		return "printf '%s\\n' \"" + json + "\" > \"$OUT/stage-status/" + stage + ".json\"\n"
				+ "printf '%s\\n' \"" + json + "\" > \"$OUT/status.json\"\n";
	}

	private String runsolverArtifacts() {
		return "printf 'WCTIME=0.10\\nCPUTIME=0.09\\nMAXVM=1000\\nTIMEOUT=false\\nMEMOUT=false\\n'"
				+ " > \"$OUT/var.out\"\n"
				+ "printf 'Child status: 0\\n' > \"$OUT/watcher.out\"\n";
	}

	private int stageStatus(int stage) throws SQLException {
		try (Connection con = Common.getConnection()) {
			return scalar(con, "SELECT status_code FROM starexec.jobpair_stage_data"
					+ " WHERE jobpair_id=" + PAIR_ID + " AND stage_number=" + stage);
		}
	}

	private int pairStatus() throws SQLException {
		try (Connection con = Common.getConnection()) {
			return scalar(con, "SELECT status_code FROM starexec.job_pairs WHERE id=" + PAIR_ID);
		}
	}

	private int completions() throws SQLException {
		try (Connection con = Common.getConnection()) {
			return scalar(con, "SELECT count(*) FROM starexec.job_pair_completion"
					+ " WHERE pair_id=" + PAIR_ID);
		}
	}

	private static int scalar(Connection con, String sql) throws SQLException {
		try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			assertTrue("query returned no row: " + sql, rs.next());
			return rs.getInt(1);
		}
	}

	private static void quietly(Statement s, String sql) {
		try {
			s.execute(sql);
		} catch (SQLException ignored) {
			// A missing table in teardown is not a test result.
		}
	}
}
