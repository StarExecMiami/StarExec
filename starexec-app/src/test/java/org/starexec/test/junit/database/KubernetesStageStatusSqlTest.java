package org.starexec.test.junit.database;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import org.starexec.backend.ExecutionRef;
import org.starexec.backend.KubernetesJobMonitor;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.data.database.Common;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Does the real Kubernetes completion callback record every stage, against a real database?
 *
 * <p>The gap being closed (issue #131): a K8s-native multi-stage pair writes per-stage
 * snapshots and the backend read only the pair-level {@code status.json}, so every stage before
 * the last kept whatever it was enqueued with.
 *
 * <p>This drives {@code KubernetesJobCompletionCallback.onJobComplete} itself, not the snapshot
 * reader underneath it. A test of the reader alone cannot see the things that actually decide
 * correctness here: which pair the backend believes it is processing, whether the terminal
 * update still fires exactly once, and what the callback's boolean return does to the retry
 * contract. Those live in the callback.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class KubernetesStageStatusSqlTest extends Common {

	private static final String TAG = "k8s-stage-probe";

	private static final int USER_ID = 90801;
	private static final int SPACE_ID = 90801;
	private static final int QUEUE_ID = 90801;
	private static final int SOLVER_ID = 90801;
	private static final int CONFIG_ID = 90801;
	private static final int BENCH_ID = 90801;
	private static final int JOB_ID = 90801;
	private static final int PAIR_ID = 90801;
	private static final int OTHER_PAIR_ID = 90802;

	private static final int EXEC_ID = 8081;
	private static final String JOB_NAME = "job-" + EXEC_ID;

	private static final int ENQUEUED = StatusCode.STATUS_ENQUEUED.getVal();
	private static final int RUNNING = StatusCode.STATUS_RUNNING.getVal();
	private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
	private static final int EXCEED_CPU = StatusCode.EXCEED_CPU.getVal();
	private static final int NOT_REACHED = StatusCode.STATUS_NOT_REACHED.getVal();

	private Path outputDir;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("KubernetesStageStatusSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws Exception {
		cleanUp();
		outputDir = Files.createTempDirectory("k8s-stage-probe");
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
					+ "'," + QUEUE_ID + ",NOW(),'d',2,0)");
			for (int pair : new int[]{PAIR_ID, OTHER_PAIR_ID}) {
				s.execute("INSERT INTO starexec.job_pairs (id,job_id,bench_id,status_code)"
						+ " VALUES (" + pair + "," + JOB_ID + "," + BENCH_ID + "," + ENQUEUED + ")");
				for (int stage = 1; stage <= 3; stage++) {
					s.execute("INSERT INTO starexec.jobpair_stage_data (jobpair_id,stage_number,"
							+ "status_code,solver_id,config_id,disk_size) VALUES (" + pair + ","
							+ stage + "," + ENQUEUED + "," + SOLVER_ID + "," + CONFIG_ID + ",0)");
				}
			}
		}
	}

	@After
	public void cleanUp() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			quietly(s, "DROP TRIGGER IF EXISTS probe_block_stage ON starexec.jobpair_stage_data");
			quietly(s, "DROP FUNCTION IF EXISTS starexec.probe_block_stage()");
			for (int pair : new int[]{PAIR_ID, OTHER_PAIR_ID}) {
				quietly(s, "DELETE FROM starexec.job_pair_repro_manifests WHERE pair_id=" + pair);
				quietly(s, "DELETE FROM starexec.job_pair_completion WHERE pair_id=" + pair);
				quietly(s, "DELETE FROM starexec.jobpair_stage_data WHERE jobpair_id=" + pair);
				quietly(s, "DELETE FROM starexec.job_pairs WHERE id=" + pair);
			}
			quietly(s, "DELETE FROM starexec.jobs WHERE id=" + JOB_ID);
			quietly(s, "DELETE FROM starexec.configurations WHERE id=" + CONFIG_ID);
			quietly(s, "DELETE FROM starexec.solvers WHERE id=" + SOLVER_ID);
			quietly(s, "DELETE FROM starexec.benchmarks WHERE id=" + BENCH_ID);
			quietly(s, "DELETE FROM starexec.queues WHERE id=" + QUEUE_ID);
			quietly(s, "DELETE FROM starexec.spaces WHERE id=" + SPACE_ID);
			quietly(s, "DELETE FROM starexec.users WHERE id=" + USER_ID);
		}
	}

	// ------------------------------------------------------------------ the defect

	/** Two stages, both complete. Stage 1 is what used to be lost. */
	@Test
	public void aTwoStageSuccessRecordsBothStages() throws Exception {
		writeStatus(PAIR_ID, COMPLETE, 2);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeSnapshot(2, PAIR_ID, 2, COMPLETE);
		writeCleanRun();

		assertTrue("completion must be acknowledged", complete());

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(COMPLETE, stageStatus(2));
		assertEquals(COMPLETE, pairStatus());
		assertEquals("pair completion fires once", 1, completions());
	}

	/** Three stages, failing at the second. */
	@Test
	public void aFailureAtStageTwoRecordsAllThreeStages() throws Exception {
		writeStatus(PAIR_ID, EXCEED_CPU, 2);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeSnapshot(2, PAIR_ID, 2, EXCEED_CPU);
		writeCpuLimitBreach();

		assertTrue(complete());

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(EXCEED_CPU, stageStatus(2));
		assertEquals(NOT_REACHED, stageStatus(3));
		assertEquals(EXCEED_CPU, pairStatus());
		assertEquals(1, completions());
	}

	/** A duplicate callback must converge, not double-account. */
	@Test
	public void aDuplicateCallbackIsIdempotent() throws Exception {
		writeStatus(PAIR_ID, EXCEED_CPU, 2);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeSnapshot(2, PAIR_ID, 2, EXCEED_CPU);
		writeCpuLimitBreach();

		assertTrue(complete());
		int completionBefore = completionId();
		String endTimeBefore = pairEndTime();

		assertTrue("a replay must still be acknowledged", complete());

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(EXCEED_CPU, stageStatus(2));
		assertEquals(NOT_REACHED, stageStatus(3));
		assertEquals(EXCEED_CPU, pairStatus());
		assertEquals(1, completions());
		assertEquals("the same completion row", completionBefore, completionId());
		assertEquals("end_time must not drift on a replay", endTimeBefore, pairEndTime());
	}

	// ------------------------------------------------------------------ retry contract

	/**
	 * A transient stage-write failure must report retry, keep the evidence, and converge.
	 *
	 * <p>Asserted against the mechanism that actually exists: returning false leaves the
	 * execution out of {@code completedExecutions}, so the Job stays in the labelled listing and
	 * the next poll calls back again. There is no cleanup-pending record on this path.
	 */
	@Test
	public void aTransientStageWriteFailureRetriesAndConverges() throws Exception {
		writeStatus(PAIR_ID, EXCEED_CPU, 2);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeSnapshot(2, PAIR_ID, 2, EXCEED_CPU);
		writeCpuLimitBreach();

		blockStageWrites();
		assertFalse("a blocked stage write must report retry", complete());

		assertEquals("nothing may be recorded", ENQUEUED, stageStatus(1));
		assertEquals("the pair must not be force-failed", ENQUEUED, pairStatus());
		assertEquals("nothing may complete the pair", 0, completions());
		assertTrue("the evidence must survive", Files.isDirectory(outputDir.resolve("stage-status")));

		unblockStageWrites();
		assertTrue(complete());

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(EXCEED_CPU, stageStatus(2));
		assertEquals(EXCEED_CPU, pairStatus());
		assertEquals(1, completions());
	}

	// ------------------------------------------------------------------ refusals

	@Test
	public void aSnapshotNamingAnotherPairWritesNothing() throws Exception {
		writeStatus(PAIR_ID, COMPLETE, 2);
		writeSnapshot(1, OTHER_PAIR_ID, 1, COMPLETE);
		writeCleanRun();

		assertRefused();
		assertEquals("the pair it named must be untouched", ENQUEUED,
				stageStatus(OTHER_PAIR_ID, 1));
	}

	@Test
	public void aNameDisagreeingWithItsRecordWritesNothing() throws Exception {
		writeStatus(PAIR_ID, COMPLETE, 2);
		writeSnapshot(1, PAIR_ID, 2, COMPLETE);
		writeCleanRun();
		assertRefused();
	}

	@Test
	public void aStageThePairDoesNotHaveWritesNothing() throws Exception {
		writeStatus(PAIR_ID, COMPLETE, 9);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeSnapshot(8, PAIR_ID, 8, COMPLETE);
		writeCleanRun();
		assertRefused();
	}

	@Test
	public void aNonTerminalSnapshotWritesNothing() throws Exception {
		writeStatus(PAIR_ID, COMPLETE, 2);
		writeSnapshot(1, PAIR_ID, 1, RUNNING);
		writeCleanRun();
		assertRefused();
	}

	/** The laundering path: 22 would be turned into COMPLETE by post-processing. */
	@Test
	public void aProcessingSnapshotWritesNothingAndCannotReachComplete() throws Exception {
		writeStatus(PAIR_ID, COMPLETE, 2);
		writeSnapshot(1, PAIR_ID, 1, StatusCode.STATUS_PROCESSING.getVal());
		writeCleanRun();

		assertRefused();
		try (Connection con = Common.getConnection()) {
			assertEquals("no stage may be left at STATUS_PROCESSING", 0,
					scalar(con, "SELECT count(*) FROM starexec.jobpair_stage_data"
							+ " WHERE jobpair_id=" + PAIR_ID + " AND status_code=22"));
		}
	}

	@Test
	public void malformedJsonWritesNothing() throws Exception {
		writeStatus(PAIR_ID, COMPLETE, 2);
		Files.createDirectories(outputDir.resolve("stage-status"));
		Files.writeString(outputDir.resolve("stage-status/1.json"), "{not json");
		writeCleanRun();
		assertRefused();
	}

	// ------------------------------------------------------------------ compatibility

	/** Output from a job script predating the protocol must behave exactly as before. */
	@Test
	public void legacyOutputWithoutSnapshotsIsUnchanged() throws Exception {
		writeStatus(PAIR_ID, COMPLETE, 2);
		writeCleanRun();

		assertTrue(complete());

		assertEquals("no earlier stage may be invented", ENQUEUED, stageStatus(1));
		assertEquals(COMPLETE, stageStatus(2));
		assertEquals(NOT_REACHED, stageStatus(3));
		assertEquals(COMPLETE, pairStatus());
	}

	/** Single-stage pairs are not affected by any of this. */
	@Test
	public void aSingleStagePairIsUnchanged() throws Exception {
		writeStatus(PAIR_ID, COMPLETE, 1);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeCleanRun();

		assertTrue(complete());

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(NOT_REACHED, stageStatus(2));
		assertEquals(COMPLETE, pairStatus());
		assertEquals(1, completions());
	}

	// ------------------------------------------------------------------ ownership

	/**
	 * A stale Job pointed at a valid-looking directory does not apply it.
	 *
	 * <p>The output directory is keyed by pair, not by attempt, so a previous attempt's
	 * {@code ExecutionRef} can still name a directory full of well-formed snapshots for the
	 * right pair. Only ownership distinguishes them.
	 *
	 * <p>What this establishes, precisely: the refusal happens in the <em>pre-existing</em>
	 * fence, not in the new ingestion. {@code resolvePairId} requires {@code ownsTracking} or a
	 * UID-matched Job label, and returns null for a stale reference, so {@code onJobComplete}
	 * returns false before reaching stage ingestion at all. Verified by removing the new
	 * ownership checks: this test still passes, which is why it is named for the outcome rather
	 * than for the mechanism.
	 *
	 * <p>The ownership checks inside {@code ingestEarlierStageStatuses} are therefore
	 * defence-in-depth behind a fence that already holds — deliberately kept, because the
	 * re-check at the mutation boundary covers a rerun landing *during* the file reads, which
	 * the entry fence cannot see. That narrower window has no test here; the database's refusal
	 * to overwrite a recorded stage result is its backstop.
	 */
	@Test
	public void aStaleExecutionIsRefusedBeforeAnyStageIsWritten() throws Exception {
		writeStatus(PAIR_ID, COMPLETE, 2);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeSnapshot(2, PAIR_ID, 2, COMPLETE);
		writeCleanRun();

		KubernetesNativeBackend backend = backendOwning(EXEC_ID, PAIR_ID, outputDir);
		// A later attempt took the exec id: same id and name, different Kubernetes object.
		ExecutionRef stale = new ExecutionRef(EXEC_ID, JOB_NAME, "uid-of-a-previous-attempt");

		callbackFor(backend).onJobComplete(stale);

		assertEquals("a stale execution must not record stages", ENQUEUED, stageStatus(1));
	}

	// ------------------------------------------------------------------------- harness

	/** Runs the real completion callback for a well-owned execution. */
	private boolean complete() throws Exception {
		KubernetesNativeBackend backend = backendOwning(EXEC_ID, PAIR_ID, outputDir);
		return callbackFor(backend).onJobComplete(execution());
	}

	private void assertRefused() throws Exception {
		assertFalse("untrusted output must report retry, not success", complete());
		assertEquals("stage 1 untouched", ENQUEUED, stageStatus(1));
		assertEquals("stage 2 untouched", ENQUEUED, stageStatus(2));
		assertEquals("stage 3 untouched", ENQUEUED, stageStatus(3));
		assertEquals("the pair must not be force-failed", ENQUEUED, pairStatus());
		assertEquals("nothing may complete the pair", 0, completions());
		assertTrue("evidence must be preserved", Files.exists(outputDir.resolve("status.json")));
	}

	private static ExecutionRef execution() {
		return new ExecutionRef(EXEC_ID, JOB_NAME, "uid-" + JOB_NAME);
	}

	@SuppressWarnings("unchecked")
	private KubernetesNativeBackend backendOwning(int execId, int pairId, Path dir)
			throws Exception {
		KubernetesNativeBackend backend = new KubernetesNativeBackend();
		ExecutionRef owner = execution();
		putMap(backend, "execIdToOutputDir", execId, dir);
		putMap(backend, "execIdToJobName", execId, owner.jobName());
		putMap(backend, "execIdToJobUid", execId, owner.jobUid());
		putMap(backend, "execIdToPairId", execId, pairId);
		setField(backend, "kubernetesClient", Mockito.mock(KubernetesClient.class));
		return backend;
	}

	@SuppressWarnings("unchecked")
	private static void putMap(Object target, String field, Object key, Object value)
			throws Exception {
		Field f = KubernetesNativeBackend.class.getDeclaredField(field);
		f.setAccessible(true);
		((Map<Object, Object>) f.get(target)).put(key, value);
	}

	private static void setField(Object target, String field, Object value) throws Exception {
		Field f = KubernetesNativeBackend.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private KubernetesJobMonitor.JobCompletionCallback callbackFor(KubernetesNativeBackend backend)
			throws Exception {
		for (Class<?> inner : KubernetesNativeBackend.class.getDeclaredClasses()) {
			if (KubernetesJobMonitor.JobCompletionCallback.class.isAssignableFrom(inner)) {
				Constructor<?> ctor = inner.getDeclaredConstructors()[0];
				ctor.setAccessible(true);
				return (KubernetesJobMonitor.JobCompletionCallback) ctor.newInstance(backend);
			}
		}
		throw new IllegalStateException("no JobCompletionCallback implementation found");
	}

	// ------------------------------------------------------------------ artifacts

	private void writeStatus(int pairId, int status, int stage) throws Exception {
		Files.writeString(outputDir.resolve("status.json"), record(pairId, status, stage));
	}

	private void writeSnapshot(int fileStage, int pairId, int recordStage, int status)
			throws Exception {
		Files.createDirectories(outputDir.resolve("stage-status"));
		Files.writeString(outputDir.resolve("stage-status/" + fileStage + ".json"),
				record(pairId, status, recordStage));
	}

	private static String record(int pairId, int status, int stage) {
		return "{\"pairId\":" + pairId + ",\"status\":" + status
				+ ",\"stageNumber\":" + stage + ",\"timestamp\":1788818872}\n";
	}

	private void writeCleanRun() throws Exception {
		Files.writeString(outputDir.resolve("var.out"),
				"WCTIME=0.10\nCPUTIME=0.09\nMAXVM=1000\nTIMEOUT=false\nMEMOUT=false\n");
		Files.writeString(outputDir.resolve("watcher.out"), "Child status: 0\n");
	}

	private void writeCpuLimitBreach() throws Exception {
		Files.writeString(outputDir.resolve("var.out"),
				"WCTIME=10.5\nCPUTIME=10.4\nMAXVM=1000\nTIMEOUT=true\nMEMOUT=false\n");
		Files.writeString(outputDir.resolve("watcher.out"),
				"Maximum CPU time exceeded: sending SIGTERM then SIGKILL\n");
	}

	// ------------------------------------------------------------------ fault injection

	private void blockStageWrites() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("CREATE OR REPLACE FUNCTION starexec.probe_block_stage() RETURNS trigger AS"
					+ " $probe$ BEGIN IF NEW.jobpair_id = " + PAIR_ID
					+ " THEN RAISE EXCEPTION 'probe: stage write blocked'; END IF;"
					+ " RETURN NEW; END; $probe$ LANGUAGE plpgsql");
			s.execute("CREATE TRIGGER probe_block_stage BEFORE UPDATE ON"
					+ " starexec.jobpair_stage_data FOR EACH ROW"
					+ " EXECUTE FUNCTION starexec.probe_block_stage()");
		}
	}

	private void unblockStageWrites() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("DROP TRIGGER IF EXISTS probe_block_stage ON starexec.jobpair_stage_data");
			s.execute("DROP FUNCTION IF EXISTS starexec.probe_block_stage()");
		}
	}

	// ------------------------------------------------------------------------- queries

	private int stageStatus(int stage) throws SQLException {
		return stageStatus(PAIR_ID, stage);
	}

	private int stageStatus(int pairId, int stage) throws SQLException {
		try (Connection con = Common.getConnection()) {
			return scalar(con, "SELECT status_code FROM starexec.jobpair_stage_data"
					+ " WHERE jobpair_id=" + pairId + " AND stage_number=" + stage);
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

	private int completionId() throws SQLException {
		try (Connection con = Common.getConnection()) {
			return scalar(con, "SELECT coalesce(max(completion_id),-1) FROM"
					+ " starexec.job_pair_completion WHERE pair_id=" + PAIR_ID);
		}
	}

	private String pairEndTime() throws SQLException {
		try (Connection con = Common.getConnection();
				Statement st = con.createStatement();
				ResultSet rs = st.executeQuery("SELECT coalesce(end_time::text,'null')"
						+ " FROM starexec.job_pairs WHERE id=" + PAIR_ID)) {
			assertTrue(rs.next());
			return rs.getString(1);
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
