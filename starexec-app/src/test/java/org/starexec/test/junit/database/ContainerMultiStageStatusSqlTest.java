package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import org.starexec.backend.ContainerJobMonitor;
import org.starexec.backend.PodmanBackend;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.backend.exception.RetryableIngestionException;
import org.starexec.data.database.StageStatusBatchResult;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Does the real monitor, against the real status routines, record every stage of a
 * multi-stage container pair?
 *
 * <p>The producer half is covered by {@code ContainerStageStatusTest}, which drives the
 * shipped {@code functions.bash}. This is the consumer half, and it is deliberately not a
 * mock: a test that counted calls on a mocked {@code JobPairs} would have passed against
 * the defect, because the defect is that the one call the monitor makes cannot express more
 * than one stage. So this runs {@code ContainerJobMonitor.processCompletedJob} over a real
 * output directory laid out exactly as the job script leaves it, and asserts the rows.
 *
 * <p>Live failure jobs are not used to reach the failure cases. Container mode maps a
 * solver's non-zero exit to {@code STATUS_COMPLETE} -- {@code determineStatus} only reports
 * {@code ERROR_RUNSCRIPT} when {@code var.out} is missing, and runsolver always writes it --
 * and the failure paths that do fire pass through defects outside this change
 * ({@code markRunscriptError} subtracts one from the stage number; the EXIT trap replaces
 * specific codes with {@code ERROR_BENCHMARK}). Injecting the failure through the real
 * runsolver artifacts instead exercises this contract without testing it through those.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class ContainerMultiStageStatusSqlTest extends Common {

	private static final String TAG = "stagestatus-probe";

	private static final int USER_ID = 90701;
	private static final int SPACE_ID = 90701;
	private static final int QUEUE_ID = 90701;
	private static final int SOLVER_ID = 90701;
	private static final int CONFIG_ID = 90701;
	private static final int BENCH_ID = 90701;
	private static final int JOB_ID = 90701;
	private static final int PAIR_ID = 90701;
	/** A second pair, so "belongs to another pair" can be tested against a row that exists. */
	private static final int OTHER_PAIR_ID = 90702;

	private static final int ENQUEUED = 2;
	private static final int RUNNING = 4;
	private static final int COMPLETE = 7;
	private static final int EXCEED_CPU = 15;
	private static final int NOT_REACHED = 23;

	private ContainerJobMonitor monitor;
	/** Held so the lifecycle tests can stub the poll and verify what the monitor did with it. */
	private PodmanBackend backend;
	private Path work;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("ContainerMultiStageStatusSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws Exception {
		cleanUp();
		backend = Mockito.mock(PodmanBackend.class);
		monitor = new ContainerJobMonitor(backend);
		work = Files.createTempDirectory("stage-status-probe");
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
			// Both pairs start where a real pair starts: enqueued, with every stage enqueued.
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
			quietly(s, "DROP TRIGGER IF EXISTS probe_block_pair_status ON starexec.job_pairs");
			quietly(s, "DROP FUNCTION IF EXISTS starexec.probe_block_pair_status()");
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

	@AfterClass
	public static void noProbeTriggersSurvive() throws SQLException {
		if (!DatabaseTestSupport.isDatabaseConfigured()) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			assertEquals("a fault-injection trigger must never outlive its test", 0,
					scalar(con, "SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'probe_%'"));
		}
	}

	// ------------------------------------------------------------------ the defect itself

	/**
	 * The three-stage failure. Every row is required, and pair completion is required to
	 * fire exactly once even though three stages were written.
	 */
	@Test
	public void aFailureAtStageTwoRecordsAllThreeStages() throws Exception {
		Path out = outputDir("fail");
		writeLegacyStatus(out, PAIR_ID, EXCEED_CPU, 2);
		writeSnapshot(out, PAIR_ID, COMPLETE, 1);
		writeSnapshot(out, PAIR_ID, EXCEED_CPU, 2);
		writeCpuLimitBreach(out);

		process(out);

		assertEquals("stage 1 ran to completion and must say so", COMPLETE, stageStatus(1));
		assertEquals("stage 2 must carry the exact failure", EXCEED_CPU, stageStatus(2));
		assertEquals("stage 3 was never reached", NOT_REACHED, stageStatus(3));
		assertEquals("the pair carries the exact failure", EXCEED_CPU, pairStatus());
		assertEquals("pair completion fires once, not once per stage", 1, completions());
	}

	/** The two-stage success, which is what the local end-to-end run reproduces. */
	@Test
	public void aTwoStageSuccessCompletesBothStages() throws Exception {
		Path out = outputDir("ok");
		writeLegacyStatus(out, PAIR_ID, COMPLETE, 2);
		writeSnapshot(out, PAIR_ID, COMPLETE, 1);
		writeSnapshot(out, PAIR_ID, COMPLETE, 2);
		writeCleanRun(out);

		process(out);

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(COMPLETE, stageStatus(2));
		assertEquals(COMPLETE, pairStatus());
		assertEquals(1, completions());
	}

	// ------------------------------------------------------------------ idempotency

	/** Processing the same directory twice must land on the same rows. */
	@Test
	public void reprocessingTheSameOutputChangesNothing() throws Exception {
		Path out = outputDir("replay");
		writeLegacyStatus(out, PAIR_ID, EXCEED_CPU, 2);
		writeSnapshot(out, PAIR_ID, COMPLETE, 1);
		writeSnapshot(out, PAIR_ID, EXCEED_CPU, 2);
		writeCpuLimitBreach(out);

		process(out);
		// job_pair_completion has UNIQUE(pair_id) and the insert is ON CONFLICT DO NOTHING,
		// so counting rows proves nothing -- it would read 1 even if the terminal routine ran
		// once per stage. Capture the identity and the timestamps instead: those DO move if
		// the pair-terminal path executes a second time.
		int completionIdBefore = completionId();
		String endTimeBefore = pairEndTime();

		process(out);

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(EXCEED_CPU, stageStatus(2));
		assertEquals(NOT_REACHED, stageStatus(3));
		assertEquals(EXCEED_CPU, pairStatus());
		assertEquals("a replay must not complete the pair twice", 1, completions());
		assertEquals("the completion row must be the same one",
				completionIdBefore, completionId());
		assertEquals("a replay must not move the pair's end_time",
				endTimeBefore, pairEndTime());
	}

	/**
	 * The crash window the monitor is exposed to: earlier stages commit on their own
	 * connection, the pair-level write then fails, and the container survives for a retry.
	 * The retry must converge rather than find a half-written pair it cannot fix.
	 */
	@Test
	public void aFailureBetweenTheStageWritesAndThePairWriteConvergesOnRetry() throws Exception {
		Path out = outputDir("partial");
		writeLegacyStatus(out, PAIR_ID, EXCEED_CPU, 2);
		writeSnapshot(out, PAIR_ID, COMPLETE, 1);
		writeSnapshot(out, PAIR_ID, EXCEED_CPU, 2);
		writeCpuLimitBreach(out);

		installPairStatusBlock();
		try {
			process(out);
			fail("the pair write was blocked, so processing must not report success");
		} catch (Exception expected) {
			// The monitor throws so its caller keeps the container for the next poll.
		}

		assertEquals("the earlier stage committed on its own connection",
				COMPLETE, stageStatus(1));
		assertEquals("the pair itself did not move", ENQUEUED, pairStatus());
		assertEquals("nothing completed the pair", 0, completions());

		removePairStatusBlock();
		process(out);

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(EXCEED_CPU, stageStatus(2));
		assertEquals(NOT_REACHED, stageStatus(3));
		assertEquals(EXCEED_CPU, pairStatus());
		assertEquals(1, completions());
	}

	// ------------------------------------------------------------------ compatibility

	/**
	 * A container whose job script predates the snapshots. The monitor must behave exactly
	 * as it did -- terminal stage and later stages only -- rather than failing.
	 */
	@Test
	public void outputWithoutSnapshotsBehavesAsItAlwaysDid() throws Exception {
		Path out = outputDir("legacy");
		writeLegacyStatus(out, PAIR_ID, COMPLETE, 2);
		writeCleanRun(out);

		process(out);

		assertEquals("without a snapshot the monitor must not invent one",
				ENQUEUED, stageStatus(1));
		assertEquals(COMPLETE, stageStatus(2));
		assertEquals(NOT_REACHED, stageStatus(3));
		assertEquals(COMPLETE, pairStatus());
	}

	// ------------------------------------------------------------------ ownership

	/** A snapshot naming another pair must be refused, and must write nothing at all. */
	@Test
	public void aSnapshotClaimingAnotherPairWritesNothing() throws Exception {
		Path out = outputDir("foreign");
		writeLegacyStatus(out, PAIR_ID, COMPLETE, 2);
		writeSnapshot(out, OTHER_PAIR_ID, COMPLETE, 1);
		writeCleanRun(out);

		assertRejectedWithoutWriting(out, "claims pair " + OTHER_PAIR_ID);
		assertEquals("the pair it tried to reach must be untouched",
				ENQUEUED, stageStatus(OTHER_PAIR_ID, 1));
	}

	/** status.json naming another pair must be refused too: identity comes from the label. */
	@Test
	public void aStatusFileClaimingAnotherPairWritesNothing() throws Exception {
		Path out = outputDir("foreign-legacy");
		writeLegacyStatus(out, OTHER_PAIR_ID, COMPLETE, 2);
		writeCleanRun(out);

		assertRejectedWithoutWriting(out, "but the container is labelled pair");
		assertEquals(ENQUEUED, stageStatus(OTHER_PAIR_ID, 2));
	}

	/** A snapshot whose file name disagrees with the record it holds is not trusted. */
	@Test
	public void aSnapshotWhoseNameDisagreesWithItsRecordWritesNothing() throws Exception {
		Path out = outputDir("mismatch");
		writeLegacyStatus(out, PAIR_ID, COMPLETE, 2);
		Files.createDirectories(out.resolve("stage-status"));
		Files.writeString(out.resolve("stage-status/1.json"),
				record(PAIR_ID, COMPLETE, 2));
		writeCleanRun(out);

		assertRejectedWithoutWriting(out, "names stage");
	}

	/**
	 * The no-op shape, and the reason this patch preserves per-stage state instead of
	 * deriving it. A {@code <noop>} pipeline stage takes a stage number
	 * ({@code JobUtil.java}) but is given no {@code jobpair_stage_data} row
	 * ({@code JobPairs.java}), so a pair's stage numbers are not contiguous: this pair has
	 * stages 1 and 3. Reaching stage 3 therefore says nothing at all about stage 2, and a
	 * write derived for it would raise {@code P0002} against a row that does not exist.
	 *
	 * <p>So a snapshot naming stage 2 here is not a stage to record. It must roll the whole
	 * batch back, taking stage 1 with it, rather than writing what it can.
	 */
	@Test
	public void aSnapshotForAStageThePairDoesNotHaveWritesNothing() throws Exception {
		removeStage(PAIR_ID, 2);

		Path out = outputDir("nostage");
		writeLegacyStatus(out, PAIR_ID, COMPLETE, 3);
		writeSnapshot(out, PAIR_ID, COMPLETE, 1);
		writeSnapshot(out, PAIR_ID, COMPLETE, 2);
		writeCleanRun(out);

		try {
			process(out);
			fail("untrusted output must not be processed");
		} catch (Exception expected) {
			assertNotNull(expected.getMessage());
		}
		assertEquals("the stage that would have succeeded must have rolled back",
				ENQUEUED, stageStatus(1));
		assertEquals("stage 3 must be untouched", ENQUEUED, stageStatus(3));
		assertEquals("the pair must be untouched", ENQUEUED, pairStatus());
		assertEquals("nothing may have completed the pair", 0, completions());
	}

	/**
	 * And the same shape when the snapshots are honest: stages 1 and 3, nothing claimed for
	 * the no-op in between. Non-contiguous stage numbers must simply work.
	 */
	@Test
	public void aPairWithANoOpStageRecordsTheStagesItHas() throws Exception {
		removeStage(PAIR_ID, 2);

		Path out = outputDir("noop");
		writeLegacyStatus(out, PAIR_ID, COMPLETE, 3);
		writeSnapshot(out, PAIR_ID, COMPLETE, 1);
		writeSnapshot(out, PAIR_ID, COMPLETE, 3);
		writeCleanRun(out);

		process(out);

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(COMPLETE, stageStatus(3));
		assertEquals(COMPLETE, pairStatus());
		assertEquals(1, completions());
	}

	/**
	 * A snapshot file whose name is digits but far too long for an {@code int}.
	 *
	 * <p>It has to be ignored rather than rejected. Parsing it would throw
	 * {@link NumberFormatException} out of the whole completion, and because the container
	 * is kept for retry, every later poll would read the same file again -- the pair would
	 * never finish. It is not a stage of anything, so it is not a snapshot, and the pair
	 * completes on the records that are real.
	 */
	@Test
	public void aStageNumberTooLargeForAnIntIsIgnoredRatherThanWedgingThePair()
			throws Exception {
		Path out = outputDir("huge");
		writeLegacyStatus(out, PAIR_ID, COMPLETE, 2);
		writeSnapshot(out, PAIR_ID, COMPLETE, 1);
		writeSnapshot(out, PAIR_ID, COMPLETE, 2);
		Files.createDirectories(out.resolve("stage-status"));
		Files.writeString(out.resolve("stage-status/99999999999999999999.json"),
				record(PAIR_ID, COMPLETE, 1));
		writeCleanRun(out);

		process(out);

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(COMPLETE, stageStatus(2));
		assertEquals(COMPLETE, pairStatus());
		assertEquals(1, completions());
	}

	// ------------------------------------------------------------------ consistency

	/**
	 * The distinction between preserved history and convenient inference. Stage 2 finished,
	 * but stage 1's own record says it never got past RUNNING. Sequential completion is not
	 * a safe inference -- a no-op pipeline stage consumes a stage number without owning a
	 * row, so the numbers are not even contiguous -- so this is a lost record, and the pair
	 * must be held rather than silently completed.
	 */
	@Test
	public void anEarlierStageStillRunningIsRefusedRatherThanCompleted() throws Exception {
		Path out = outputDir("inconsistent");
		writeLegacyStatus(out, PAIR_ID, COMPLETE, 2);
		writeSnapshot(out, PAIR_ID, RUNNING, 1);
		writeSnapshot(out, PAIR_ID, COMPLETE, 2);
		writeCleanRun(out);

		assertRejectedWithoutWriting(out, "carries non-terminal status");
	}

	/**
	 * The laundering path, refused. A stage parked at {@code STATUS_PROCESSING(22)} is
	 * selected by the periodic post-processing task, which sets the whole PAIR to
	 * {@code STATUS_COMPLETE} -- so accepting 22 here would let a container convert its own
	 * timeout into a clean completion. The pair must be left exactly as it was, with its
	 * evidence intact for diagnosis.
	 */
	@Test
	public void aSnapshotClaimingProcessingIsRefusedAndCannotReachComplete() throws Exception {
		Path out = outputDir("launder");
		writeLegacyStatus(out, PAIR_ID, COMPLETE, 2);
		writeSnapshot(out, PAIR_ID, 22, 1);
		writeSnapshot(out, PAIR_ID, COMPLETE, 2);
		writeCleanRun(out);

		assertRejectedWithoutWriting(out, "carries non-terminal status 22");

		// The post-processing task selects on exactly this, so nothing may be left holding it.
		try (Connection con = Common.getConnection()) {
			assertEquals("no stage may be left at STATUS_PROCESSING", 0,
					scalar(con, "SELECT count(*) FROM starexec.jobpair_stage_data"
							+ " WHERE jobpair_id=" + PAIR_ID + " AND status_code=22"));
		}
	}

	// --------------------------------------------- the real outer monitor lifecycle

	/**
	 * The property this PR actually claims, proven through the caller that decides it.
	 *
	 * <p>{@code processCompletedJob} throwing is not evidence of anything on its own: what
	 * matters is what {@code checkCompletedJobs} does with the throw. It used to catch
	 * everything, stamp the pair {@code ERROR_RUNSCRIPT} and delete the container -- so a
	 * transient database failure during ingestion overwrote a stage that had just been
	 * recorded {@code COMPLETE} and destroyed the only copy of a good result.
	 *
	 * <p>Sequence: stage 1 persists, the pair-level write is blocked, and then the assertions
	 * are about the outer lifecycle -- the pair is untouched, the container is NOT removed,
	 * and the execution slot IS handed back so retrying costs no capacity. The block is then
	 * lifted and a second poll must converge, removing the container only once it has
	 * succeeded.
	 */
	@Test
	public void aTransientFailureIsRetriedByTheRealMonitorWithoutLosingAnything()
			throws Exception {
		Path out = outputDir("lifecycle");
		writeLegacyStatus(out, PAIR_ID, EXCEED_CPU, 2);
		writeSnapshot(out, PAIR_ID, COMPLETE, 1);
		writeSnapshot(out, PAIR_ID, EXCEED_CPU, 2);
		writeCpuLimitBreach(out);

		PodmanBackend.CompletedContainerInfo info = new PodmanBackend.CompletedContainerInfo(
				"lifecycle-container", PAIR_ID, out.toString(), 0, 0);
		Mockito.when(backend.getCompletedContainers())
				.thenReturn(Collections.singletonList(info));

		// --- B: the pair-level write fails transiently ---
		installPairStatusBlock();
		pollOnce();

		// --- C: nothing about the result was altered ---
		assertEquals("the earlier stage stands", COMPLETE, stageStatus(1));
		assertEquals("the pair must NOT have been force-failed", ENQUEUED, pairStatus());
		assertEquals("nothing completed the pair", 0, completions());

		// --- D/F: evidence kept, capacity returned ---
		Mockito.verify(backend, Mockito.never())
				.removeCompletedContainer("lifecycle-container");
		Mockito.verify(backend, Mockito.times(1))
				.releaseSlotForCompletedContainer("lifecycle-container");
		assertTrue("the output directory is the only copy and must survive",
				Files.isDirectory(out.resolve("stage-status")));

		// --- E: a later poll retries and converges ---
		removePairStatusBlock();
		resetBackoff();
		pollOnce();

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(EXCEED_CPU, stageStatus(2));
		assertEquals(NOT_REACHED, stageStatus(3));
		assertEquals(EXCEED_CPU, pairStatus());
		assertEquals(1, completions());

		// --- F/G: cleanup only after success, slot released exactly once ---
		Mockito.verify(backend, Mockito.times(1))
				.removeCompletedContainer("lifecycle-container");
		Mockito.verify(backend, Mockito.times(1))
				.releaseSlotForCompletedContainer("lifecycle-container");
	}

	/**
	 * Content that will never be valid still resolves, so a genuinely broken container cannot
	 * occupy the queue forever. This is the other half of the classification: the pair is
	 * failed and the container released, which is correct here and wrong for the case above.
	 */
	@Test
	public void unusableContentIsStillResolvedAndReleased() throws Exception {
		Path out = outputDir("unusable");
		writeLegacyStatus(out, PAIR_ID, COMPLETE, 2);
		writeSnapshot(out, OTHER_PAIR_ID, COMPLETE, 1);
		writeCleanRun(out);

		PodmanBackend.CompletedContainerInfo info = new PodmanBackend.CompletedContainerInfo(
				"unusable-container", PAIR_ID, out.toString(), 0, 0);
		Mockito.when(backend.getCompletedContainers())
				.thenReturn(Collections.singletonList(info));

		pollOnce();

		Mockito.verify(backend, Mockito.times(1))
				.removeCompletedContainer("unusable-container");
		assertEquals("the pair is resolved rather than left hanging",
				11, pairStatus());
	}

	/**
	 * New application against a database whose migrations have not been applied.
	 *
	 * <p>Reachable: {@code SKIP_MIGRATIONS=true} exists, and migrations are routinely applied
	 * out of band. Calling a stored routine that is not there raises SQLSTATE 42883, and the
	 * old catch-all turned that into {@code ERROR_RUNSCRIPT} plus container deletion -- so
	 * deploying the application before the migration silently destroyed every successful
	 * multi-stage pair. It has to fail closed instead: nothing recorded, nothing deleted,
	 * retry once the routine exists.
	 *
	 * <p>The routine is renamed rather than dropped so the fixture can put it back whatever
	 * the test does.
	 */
	@Test
	public void anUnmigratedDatabaseFailsClosedAndKeepsTheResults() throws Exception {
		Path out = outputDir("unmigrated");
		writeLegacyStatus(out, PAIR_ID, EXCEED_CPU, 2);
		writeSnapshot(out, PAIR_ID, COMPLETE, 1);
		writeSnapshot(out, PAIR_ID, EXCEED_CPU, 2);
		writeCpuLimitBreach(out);

		PodmanBackend.CompletedContainerInfo info = new PodmanBackend.CompletedContainerInfo(
				"unmigrated-container", PAIR_ID, out.toString(), 0, 0);
		Mockito.when(backend.getCompletedContainers())
				.thenReturn(Collections.singletonList(info));

		renameRoutine("UpdatePairStageStatusIfUnresolved", "UpdatePairStageStatusIfUnresolved_hidden");
		try {
			pollOnce();

			assertEquals("no stage may be written without the routine", ENQUEUED, stageStatus(1));
			assertEquals("the pair must not be force-failed", ENQUEUED, pairStatus());
			assertEquals("nothing may complete the pair", 0, completions());
			Mockito.verify(backend, Mockito.never())
					.removeCompletedContainer("unmigrated-container");
			assertTrue("the results must survive for the retry",
					Files.isDirectory(out.resolve("stage-status")));
		} finally {
			renameRoutine("UpdatePairStageStatusIfUnresolved_hidden", "UpdatePairStageStatusIfUnresolved");
		}

		// And once the migration has been applied, the same output ingests cleanly.
		resetBackoff();
		pollOnce();

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(EXCEED_CPU, stageStatus(2));
		assertEquals(NOT_REACHED, stageStatus(3));
		assertEquals(EXCEED_CPU, pairStatus());
		Mockito.verify(backend, Mockito.times(1))
				.removeCompletedContainer("unmigrated-container");
	}

	private void renameRoutine(String from, String to) throws SQLException {
		try (Connection con = Common.getConnection(); Statement st = con.createStatement()) {
			st.execute("ALTER FUNCTION starexec." + from + "(INT, INT, INT) RENAME TO " + to);
		}
	}

	/** One poll of the real loop. */
	private void pollOnce() throws Exception {
		Method check = ContainerJobMonitor.class.getDeclaredMethod("checkCompletedJobs");
		check.setAccessible(true);
		try {
			check.invoke(monitor);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			throw cause instanceof Exception ? (Exception) cause : new Exception(cause);
		}
	}

	/** Clears the retry backoff so the second poll is not skipped by the wait. */
	private void resetBackoff() throws Exception {
		Field f = ContainerJobMonitor.class.getDeclaredField("ingestionAttempts");
		f.setAccessible(true);
		((Map<?, ?>) f.get(monitor)).clear();
	}

	// ------------------------------------------------- the guard, exercised directly

	/** Applying the same terminal status twice is a no-op, not a second write. */
	@Test
	public void applyingTheSameStatusTwiceIsIdempotent() throws Exception {
		assertEquals(StageStatusBatchResult.APPLIED,
				JobPairs.setEarlierStageStatuses(PAIR_ID, one(1, COMPLETE)));
		assertEquals(StageStatusBatchResult.APPLIED,
				JobPairs.setEarlierStageStatuses(PAIR_ID, one(1, COMPLETE)));
		assertEquals(COMPLETE, stageStatus(1));

		assertEquals(StageStatusBatchResult.APPLIED,
				JobPairs.setEarlierStageStatuses(PAIR_ID, one(2, EXCEED_CPU)));
		assertEquals(StageStatusBatchResult.APPLIED,
				JobPairs.setEarlierStageStatuses(PAIR_ID, one(2, EXCEED_CPU)));
		assertEquals(EXCEED_CPU, stageStatus(2));
	}

	/** A stale record must never move a finished stage backwards. */
	@Test
	public void aStaleRunningRecordCannotUndoACompletedStage() throws Exception {
		assertEquals(StageStatusBatchResult.APPLIED,
				JobPairs.setEarlierStageStatuses(PAIR_ID, one(1, COMPLETE)));

		// RUNNING is not a result at all, so the routine refuses it outright now.
		assertEquals(StageStatusBatchResult.FAILED,
				JobPairs.setEarlierStageStatuses(PAIR_ID, one(1, RUNNING)));

		assertEquals("the completed stage stands", COMPLETE, stageStatus(1));
	}

	/** Nor may one terminal result be quietly replaced by a different one. */
	@Test
	public void oneTerminalResultDoesNotReplaceAnother() throws Exception {
		assertEquals(StageStatusBatchResult.APPLIED,
				JobPairs.setEarlierStageStatuses(PAIR_ID, one(1, COMPLETE)));
		assertEquals(StageStatusBatchResult.APPLIED,
				JobPairs.setEarlierStageStatuses(PAIR_ID, one(1, EXCEED_CPU)));
		assertEquals(COMPLETE, stageStatus(1));
	}

	/** A batch naming a stage that does not exist writes none of its stages. */
	@Test
	public void aBatchWithAnUnknownStageWritesNoneOfIt() throws Exception {
		Map<Integer, Integer> batch = new LinkedHashMap<>();
		batch.put(1, COMPLETE);
		batch.put(9, COMPLETE);

		assertEquals("an unknown stage is bad data, not an outage",
				StageStatusBatchResult.REJECTED_UNKNOWN_STAGE,
				JobPairs.setEarlierStageStatuses(PAIR_ID, batch));
		assertEquals("the stage that would have succeeded must have rolled back",
				ENQUEUED, stageStatus(1));
	}

	/**
	 * The database itself refuses every non-terminal status, for every code the model has.
	 *
	 * <p>Enumerated rather than probing the three known mismatches, so a status added later
	 * cannot quietly become ingestible. The expected answer is taken from
	 * {@code isTerminalExecutionResult}, and {@code TerminalStatusContractSqlTest} separately
	 * proves that predicate equals the database's own -- so this asserts the mutation boundary
	 * and that one asserts the definition.
	 */
	@Test
	public void theRoutineAcceptsExactlyTheTerminalStatuses() throws Exception {
		for (StatusCode code : StatusCode.values()) {
			int value = code.getVal();
			// Reset the stage so each code is judged from the same starting point.
			try (Connection con = Common.getConnection(); Statement st = con.createStatement()) {
				st.execute("UPDATE starexec.jobpair_stage_data SET status_code=" + ENQUEUED
						+ " WHERE jobpair_id=" + PAIR_ID + " AND stage_number=1");
			}
			StageStatusBatchResult result =
					JobPairs.setEarlierStageStatuses(PAIR_ID, one(1, value));

			if (code.isTerminalExecutionResult()) {
				assertEquals(code + "(" + value + ") is a result and must be accepted",
						StageStatusBatchResult.APPLIED, result);
				assertEquals(code + " must have been written", value, stageStatus(1));
			} else {
				assertEquals(code + "(" + value + ") is not a result and must be refused",
						StageStatusBatchResult.FAILED, result);
				assertEquals(code + " must not have been written",
						ENQUEUED, stageStatus(1));
			}
		}
	}

	// ------------------------------------------------------------------------- harness

	private void assertRejectedWithoutWriting(Path out, String expectedReason) throws Exception {
		try {
			process(out);
			fail("untrusted output must not be processed");
		} catch (RetryableIngestionException wrongKind) {
			fail("bad content must be permanent, not retryable: " + wrongKind.getMessage());
		} catch (Exception expected) {
			// Pinned to the specific refusal. Accepting any exception would let this pass
			// for an unrelated reason -- a Gson failure, a refactor moving the throw --
			// and the test would keep reporting success while proving nothing.
			assertNotNull(expected.getMessage());
			assertTrue("wrong refusal: " + expected.getMessage(),
					expected.getMessage().contains(expectedReason));
		}
		assertEquals("stage 1 must be untouched", ENQUEUED, stageStatus(1));
		assertEquals("stage 2 must be untouched", ENQUEUED, stageStatus(2));
		assertEquals("stage 3 must be untouched", ENQUEUED, stageStatus(3));
		assertEquals("the pair must be untouched", ENQUEUED, pairStatus());
		assertEquals("nothing may have completed the pair", 0, completions());
	}

	private void process(Path out) throws Exception {
		Method m = ContainerJobMonitor.class.getDeclaredMethod(
				"processCompletedJob", PodmanBackend.CompletedContainerInfo.class);
		m.setAccessible(true);
		try {
			m.invoke(monitor, new PodmanBackend.CompletedContainerInfo(
					"probe-container-id", PAIR_ID, out.toString(), 0, 0));
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			throw cause instanceof Exception ? (Exception) cause : new Exception(cause);
		}
	}

	private Path outputDir(String name) throws IOException {
		Path out = work.resolve(name);
		Files.createDirectories(out);
		return out;
	}

	private static String record(int pairId, int status, int stage) {
		return "{\"pairId\":" + pairId + ",\"status\":" + status
				+ ",\"stageNumber\":" + stage + ",\"timestamp\":1788792411}\n";
	}

	private void writeLegacyStatus(Path out, int pairId, int status, int stage) throws IOException {
		Files.writeString(out.resolve("status.json"), record(pairId, status, stage));
	}

	private void writeSnapshot(Path out, int pairId, int status, int stage) throws IOException {
		Files.createDirectories(out.resolve("stage-status"));
		Files.writeString(out.resolve("stage-status/" + stage + ".json"),
				record(pairId, status, stage));
	}

	/** runsolver's own artifacts for a clean run, so determineStatus sees COMPLETE. */
	private void writeCleanRun(Path out) throws IOException {
		Files.writeString(out.resolve("var.out"),
				"WCTIME=0.10\nCPUTIME=0.09\nUSERTIME=0.08\nSYSTEMTIME=0.01\n"
						+ "MAXVM=1000\nTIMEOUT=false\nMEMOUT=false\n");
		Files.writeString(out.resolve("watcher.out"), "Child status: 0\n");
	}

	/** And for a CPU-limit kill, so determineStatus sees EXCEED_CPU. */
	private void writeCpuLimitBreach(Path out) throws IOException {
		Files.writeString(out.resolve("var.out"),
				"WCTIME=10.5\nCPUTIME=10.4\nUSERTIME=10.3\nSYSTEMTIME=0.1\n"
						+ "MAXVM=1000\nTIMEOUT=true\nMEMOUT=false\n");
		Files.writeString(out.resolve("watcher.out"),
				"Maximum CPU time exceeded: sending SIGTERM then SIGKILL\n");
	}

	/** Drops one stage row, reproducing what a {@code <noop>} pipeline stage leaves behind. */
	private void removeStage(int pairId, int stage) throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("DELETE FROM starexec.jobpair_stage_data WHERE jobpair_id=" + pairId
					+ " AND stage_number=" + stage);
		}
	}

	private static Map<Integer, Integer> one(int stage, int status) {
		Map<Integer, Integer> m = new LinkedHashMap<>();
		m.put(stage, status);
		return m;
	}

	// ------------------------------------------------------------------ fault injection

	/**
	 * Blocks the pair-level status write only, leaving the stage-only routine alone, so the
	 * failure lands exactly in the window between the two.
	 */
	private void installPairStatusBlock() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("CREATE OR REPLACE FUNCTION starexec.probe_block_pair_status() "
					+ "RETURNS trigger AS $probe$ BEGIN "
					+ "IF NEW.id = " + PAIR_ID + " AND NEW.status_code IS DISTINCT FROM OLD.status_code THEN "
					+ "RAISE EXCEPTION 'probe: pair status write blocked'; END IF; "
					+ "RETURN NEW; END; $probe$ LANGUAGE plpgsql");
			s.execute("CREATE TRIGGER probe_block_pair_status BEFORE UPDATE ON starexec.job_pairs "
					+ "FOR EACH ROW EXECUTE FUNCTION starexec.probe_block_pair_status()");
		}
	}

	private void removePairStatusBlock() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("DROP TRIGGER IF EXISTS probe_block_pair_status ON starexec.job_pairs");
			s.execute("DROP FUNCTION IF EXISTS starexec.probe_block_pair_status()");
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

	/** Teardown must not fail on a table this deployment does not have. */
	private static void quietly(Statement s, String sql) {
		try {
			s.execute(sql);
		} catch (SQLException ignored) {
			// A missing table in teardown is not a test result.
		}
	}
}
