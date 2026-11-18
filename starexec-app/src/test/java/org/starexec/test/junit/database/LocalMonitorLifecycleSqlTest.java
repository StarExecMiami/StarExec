package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.data.database.Common;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What does {@code LocalJobMonitor} actually do when completion processing fails?
 *
 * <p>This is the proof obligation that has to be settled before #130 wires stage-status
 * ingestion into this backend, because ingestion adds new ways for processing to fail.
 *
 * <p>The code comments say one thing and the code says another. {@code processCompletedJob}
 * carries "Throwing leaves the pair tracked so a later poll retries it; returning normally
 * would mark it processed and the result would be lost" -- but the outer catch in
 * {@code checkCompletedJobs} calls
 * {@code JobPairs.setStatusForPairAndStages(pairId, ERROR_RUNSCRIPT)} and then {@code retire}.
 * That is not a retry. It stamps the pair <em>and every one of its stages</em> as a runscript
 * error and removes it from tracking, so no later poll ever looks at it again.
 *
 * <p>The blast radius is wider than the equivalent defect was on the container path, which
 * wrote only stage 1. A comment is not evidence, so this establishes the behaviour against a
 * real database and the real poll loop.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class LocalMonitorLifecycleSqlTest extends Common {

	private static final String TAG = "local-lifecycle-probe";

	private static final int USER_ID = 90901;
	private static final int SPACE_ID = 90901;
	private static final int QUEUE_ID = 90901;
	private static final int SOLVER_ID = 90901;
	private static final int CONFIG_ID = 90901;
	private static final int BENCH_ID = 90901;
	private static final int JOB_ID = 90901;
	private static final int PAIR_ID = 90901;

	private static final int ENQUEUED = StatusCode.STATUS_ENQUEUED.getVal();
	private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
	private static final int ERROR_RUNSCRIPT = StatusCode.ERROR_RUNSCRIPT.getVal();
	private static final int EXCEED_CPU = StatusCode.EXCEED_CPU.getVal();
	private static final int NOT_REACHED = StatusCode.STATUS_NOT_REACHED.getVal();

	private Path logDir;

	@BeforeAll
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("LocalMonitorLifecycleSqlTest");
		Common.initialize();
	}

	@BeforeEach
	public void seed() throws Exception {
		cleanUp();
		logDir = Files.createTempDirectory("local-lifecycle-probe");
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

	@AfterEach
	public void cleanUp() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			quietly(s, "DROP TRIGGER IF EXISTS probe_block_pair ON starexec.job_pairs");
			quietly(s, "DROP FUNCTION IF EXISTS starexec.probe_block_pair()");
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
	 * A transient database failure during completion processing must not be recorded as a
	 * solver failure, and must not end the pair's chance of being processed correctly.
	 *
	 * <p>This is the property #130 has to be able to rely on before stage ingestion is added,
	 * since ingestion introduces new failure paths. Asserted as the requirement, not as the
	 * current behaviour, so that it states the obligation rather than blessing whatever is
	 * there now.
	 */
	@Test
	public void aTransientProcessingFailureIsNotRecordedAsASolverFailure() throws Exception {
		writeStatus(COMPLETE, 1);
		writeCleanRun();

		LocalJobMonitor monitor = new LocalJobMonitor();
		monitor.registerJob(logDir.toString(), PAIR_ID);

		blockPairWrites();
		try {
			poll(monitor);
		} finally {
			unblockPairWrites();
		}

		assertEquals(ENQUEUED,
				pairStatus(), "a database outage is not a runscript error");
		assertEquals(ENQUEUED,
				stageStatus(1), "and it must not be stamped across every stage");
		assertEquals(ENQUEUED, stageStatus(2));
		assertEquals(ENQUEUED, stageStatus(3));
		assertTrue(isTracked(monitor),
				"the pair must remain tracked so a later poll can retry it");
		assertTrue(Files.exists(logDir.resolve("status.json")),
				"the solver's output must survive for that retry");
	}

	/** And having survived, the retry must actually converge. */
	@Test
	public void aLaterPollConvergesAfterTheFailureClears() throws Exception {
		writeStatus(COMPLETE, 1);
		writeCleanRun();

		LocalJobMonitor monitor = new LocalJobMonitor();
		monitor.registerJob(logDir.toString(), PAIR_ID);

		FakeClock clock = installClock(monitor);

		blockPairWrites();
		try {
			poll(monitor);
		} finally {
			unblockPairWrites();
		}

		// Still inside the backoff window: the poll must decline to reprocess.
		poll(monitor);
		assertEquals(ENQUEUED, pairStatus(), "a retry must not fire before its deadline");

		clock.advanceMillis(60_000);
		poll(monitor);

		assertEquals(COMPLETE, pairStatus());
		assertEquals(COMPLETE, stageStatus(1));
	}

	/** Backoff grows and then stops growing, measured on the injected monotonic clock. */
	@Test
	public void retryDelayGrowsExponentiallyAndIsCapped() throws Exception {
		writeStatus(COMPLETE, 1);
		writeCleanRun();

		LocalJobMonitor monitor = new LocalJobMonitor();
		monitor.registerJob(logDir.toString(), PAIR_ID);
		FakeClock clock = installClock(monitor);

		long previous = 0;
		long capped = 0;
		int cappedRuns = 0;
		blockPairWrites();
		try {
			for (int attempt = 1; attempt <= 12; attempt++) {
				poll(monitor);
				long delay = nextRetryDelayMillis(monitor, clock);
				assertTrue(delay > 0, "attempt " + attempt + " must schedule a future retry");
				if (delay == previous) {
					capped = delay;
					cappedRuns++;
				} else {
					assertTrue(delay > previous,
							"delay must grow until it caps: " + previous + " -> " + delay);
				}
				previous = delay;
				clock.advanceMillis(delay);
			}
		} finally {
			unblockPairWrites();
		}

		assertTrue(cappedRuns > 0, "the delay must stop growing rather than run away");
		assertTrue(capped <= 600_000L, "and the cap must be a sane bound, was " + capped);
		assertEquals(ENQUEUED, pairStatus(), "no attempt count may fabricate a solver status");
		assertEquals(ENQUEUED, stageStatus(1));
	}

	/**
	 * A missing stored routine is retried rather than blocked, and this documents why.
	 *
	 * <p>The requirement is that a schema incompatibility should be held for intervention
	 * rather than polled forever. It cannot be met for this particular call:
	 * {@code JobPairs.setPairStatusPreciseResult} catches its {@code SQLException}, logs it and
	 * returns {@code FAILED}, so by the time the monitor sees the failure the SQLState -- the
	 * only thing that distinguishes "routine missing" from "connection blip" -- is gone.
	 * Recovering it means changing a method three backends share, which is outside this
	 * change.
	 *
	 * <p>What is guaranteed instead, and asserted here: the loop is bounded by the backoff cap
	 * rather than hot, every attempt logs an actionable error, the evidence is retained, and no
	 * solver status is ever fabricated. That is a slow poll against a condition an operator can
	 * see, not silent corruption.
	 *
	 * <p>The stage-history path added later does not share this limitation:
	 * {@code StageStatusBatchResult} already separates a missing routine from a transient
	 * failure at the point where the SQLState is still visible.
	 */
	@Test
	public void aMissingStoredRoutineIsRetriedBoundedlyAndNeverFabricatesAStatus()
			throws Exception {
		writeStatus(COMPLETE, 1);
		writeCleanRun();

		LocalJobMonitor monitor = new LocalJobMonitor();
		monitor.registerJob(logDir.toString(), PAIR_ID);
		FakeClock clock = installClock(monitor);

		renameRoutine("UpdatePairStatusPrecise", "UpdatePairStatusPrecise_hidden",
				"INT, INT, INT, INT, BOOLEAN, INT");
		try {
			poll(monitor);
			assertEquals(ENQUEUED, pairStatus(), "no solver status may be invented");
			assertEquals(ENQUEUED, stageStatus(1));
			assertTrue(isTracked(monitor), "the pair must stay tracked with its evidence");

			// Bounded, not hot: within the backoff window nothing is reprocessed at all.
			int before = ingestionFailures(monitor);
			poll(monitor);
			assertEquals(before,
					ingestionFailures(monitor), "a retry must not fire before its deadline");

			clock.advanceMillis(3_600_000);
			poll(monitor);
			assertEquals(ENQUEUED,
					pairStatus(), "still no fabricated status after further attempts");
		} finally {
			renameRoutine("UpdatePairStatusPrecise_hidden", "UpdatePairStatusPrecise",
					"INT, INT, INT, INT, BOOLEAN, INT");
		}
	}

	/**
	 * Retry state belongs to a generation, not to a pair id.
	 *
	 * <p>A pair that entered backoff and is then rerun must not hand its deadline, its failure
	 * count or a blocked flag to the new run, and the superseded run must not write anything.
	 */
	@Test
	public void aRerunStartsCleanAndTheSupersededRunCannotMutateIt() throws Exception {
		writeStatus(COMPLETE, 1);
		writeCleanRun();

		LocalJobMonitor monitor = new LocalJobMonitor();
		monitor.registerJob(logDir.toString(), PAIR_ID);
		installClock(monitor);

		blockPairWrites();
		try {
			poll(monitor);
		} finally {
			unblockPairWrites();
		}
		assertTrue(ingestionFailures(monitor) > 0, "generation A must be in backoff");

		// Generation B: the same pair, rerun.
		monitor.registerJob(logDir.toString(), PAIR_ID);

		assertEquals(0, ingestionFailures(monitor), "B must not inherit A's failure count");
		assertTrue(!isBlocked(monitor), "B must not inherit A's blocked state");

		// B is immediately eligible, with no inherited deadline to wait out.
		poll(monitor);
		assertEquals(COMPLETE, pairStatus());
		assertEquals(COMPLETE, stageStatus(1));
	}

	/**
	 * Retaining a pair for ingestion retry must not hold execution capacity.
	 *
	 * <p>Structural, because the coupling would be structural: capacity lives entirely in
	 * {@code LocalBackend} -- {@code availableCores.offer(job.coreId)} and
	 * {@code activeJobs.remove(job.execId)}, both in the execution path's {@code finally} --
	 * while {@code LocalJobMonitor.retire} only removes an entry from its own {@code pairs}
	 * map. If a future change gave the monitor a handle on either, keeping a pair tracked for
	 * retry could quietly deadlock local throughput, and no row-level test would show it.
	 */
	@Test
	public void retryPendingTrackingCannotHoldExecutionCapacity() throws Exception {
		for (java.lang.reflect.Field f : LocalJobMonitor.class.getDeclaredFields()) {
			String type = f.getType().getName();
			assertTrue(!type.contains("LocalBackend") && !type.contains("Semaphore"),
					"LocalJobMonitor must not hold execution capacity state: "
							+ f.getName() + " is " + type);
			assertTrue(!f.getName().toLowerCase().contains("core")
							&& !f.getName().toLowerCase().contains("slot")
							&& !f.getName().toLowerCase().contains("activejobs"),
					"suspicious capacity-shaped field: " + f.getName());
		}

		// And behaviourally: a pair held for retry is still tracked, which is the state that
		// would have to leak capacity if the two were coupled.
		writeStatus(COMPLETE, 1);
		writeCleanRun();
		LocalJobMonitor monitor = freshMonitor();
		blockPairWrites();
		try {
			poll(monitor);
		} finally {
			unblockPairWrites();
		}
		assertTrue(isTracked(monitor), "retry-pending means tracked");
		assertEquals(ENQUEUED, pairStatus(), "and unresolved, not failed");
	}

	// ------------------------------------------------------- cleanup is link-safe

	/**
	 * Cleanup must not delete through a symbolic link a previous solver left behind.
	 *
	 * <p>The stage-status tree is solver-writable, so {@code stage-status/link -> /anywhere} is
	 * something a hostile or merely buggy solver can create. A traversal built on
	 * {@code File.listFiles} follows it, and the next attempt's cleanup then deletes somebody
	 * else's files.
	 */
	@Test
	public void cleanupDeletesTheLinkAndNotItsTarget() throws Exception {
		Path external = Files.createTempDirectory("outside-the-pair");
		Path sentinel = external.resolve("sentinel.txt");
		Files.writeString(sentinel, "must survive");

		Path snapshots = logDir.resolve("stage-status");
		Files.createDirectories(snapshots);
		Files.writeString(snapshots.resolve("1.json"), "{}");
		assumeSymlinks(() -> Files.createSymbolicLink(snapshots.resolve("link"), external));

		assertTrue(clearPreviousAttempt(), "cleanup must succeed");

		assertFalse(Files.exists(snapshots), "the tree must be gone");
		assertTrue(Files.exists(external), "the link's TARGET must be untouched");
		assertTrue(Files.exists(sentinel), "and everything inside it");
	}

	/** The same when the tree root itself is a link: unlink it, never follow it. */
	@Test
	public void cleanupUnlinksASymlinkedTreeRootWithoutFollowingIt() throws Exception {
		Path external = Files.createTempDirectory("outside-root");
		Path sentinel = external.resolve("sentinel.txt");
		Files.writeString(sentinel, "must survive");
		assumeSymlinks(() -> Files.createSymbolicLink(logDir.resolve("stage-status"), external));

		assertTrue(clearPreviousAttempt());

		assertFalse(Files.exists(logDir.resolve("stage-status"), java.nio.file.LinkOption.NOFOLLOW_LINKS),
				"the link must be gone");
		assertTrue(Files.exists(sentinel), "its target must survive");
	}

	/** A dangling link is still an entry that must be removed, not skipped as "absent". */
	@Test
	public void cleanupRemovesABrokenSymlink() throws Exception {
		Path snapshots = logDir.resolve("stage-status");
		Files.createDirectories(snapshots);
		assumeSymlinks(() -> Files.createSymbolicLink(
				snapshots.resolve("dangling"), logDir.resolve("nothing-here")));

		assertTrue(clearPreviousAttempt());
		assertFalse(Files.exists(snapshots, java.nio.file.LinkOption.NOFOLLOW_LINKS));
	}

	/** Ordinary nesting still works. */
	@Test
	public void cleanupRemovesNestedDirectories() throws Exception {
		Path deep = logDir.resolve("stage-status/a/b/c");
		Files.createDirectories(deep);
		Files.writeString(deep.resolve("1.json"), "{}");

		assertTrue(clearPreviousAttempt());
		assertFalse(Files.exists(logDir.resolve("stage-status")));
	}

	/**
	 * Symbolic links need privilege on some platforms. A test that quietly passes when it
	 * could not create the link would assert nothing, so the limitation is made explicit.
	 */
	private static void assumeSymlinks(SymlinkAction action) throws Exception {
		try {
			action.run();
		} catch (UnsupportedOperationException | java.io.IOException e) {
			Assumptions.abort(
					"this platform will not create symbolic links; the link-boundary"
							+ " behaviour is NOT covered here: " + e);
		}
	}

	private interface SymlinkAction {
		void run() throws Exception;
	}

	// ------------------------------------------ stale authoritative artifacts

	/**
	 * A surviving {@code status.json} must stop the attempt, not merely warn.
	 *
	 * <p>The monitor is registered immediately after cleanup returns, so a stale status file is
	 * read as this generation's result before the new process writes anything.
	 */
	@Test
	public void anUndeletableStatusFileRefusesTheAttempt() throws Exception {
		writeStatus(COMPLETE, 1);
		// Make the parent unwritable so the entry cannot be unlinked.
		java.io.File readOnly = logDir.toFile();
		Assumptions.assumeTrue(readOnly.setWritable(false, false),
				"needs a filesystem where chmod bites");
		try {
			assertFalse(clearPreviousAttempt(),
					"a stale authoritative artifact must refuse the attempt");
		} finally {
			assertTrue(readOnly.setWritable(true, false), "restore write permission");
		}
	}

	// ----------------------------------------------- parse failures are not results

	/**
	 * A status file that never parses must block, not become {@code ERROR_RUNSCRIPT}.
	 *
	 * <p>Polls past the parse-failure threshold, which is where the old code synthesised a
	 * runscript error and persisted it.
	 */
	@Test
	public void aPermanentlyMalformedStatusBlocksRatherThanFabricating() throws Exception {
		Files.writeString(logDir.resolve("status.json"), "{ this is not json");
		writeCleanRun();

		LocalJobMonitor monitor = freshMonitor();
		for (int poll = 0; poll < 5; poll++) {
			poll(monitor);
		}

		assertEquals(ENQUEUED, pairStatus(), "no solver status may be invented");
		assertEquals(ENQUEUED, stageStatus(1));
		assertEquals(0, completions(), "nothing may complete the pair");
		assertTrue(Files.exists(logDir.resolve("status.json")),
				"the unreadable evidence must be retained");
		assertTrue(isBlocked(monitor), "and the pair held for intervention");
	}

	/** A truncated file that is later repaired must converge, not block permanently. */
	@Test
	public void aTruncatedStatusThatIsRepairedConverges() throws Exception {
		Files.writeString(logDir.resolve("status.json"), "{\"pairId\":");
		writeCleanRun();

		LocalJobMonitor monitor = freshMonitor();
		poll(monitor);
		assertEquals(ENQUEUED, pairStatus(), "a truncated read must not mutate anything");

		writeStatus(COMPLETE, 1);
		poll(monitor);

		assertEquals(COMPLETE, pairStatus());
		assertEquals(COMPLETE, stageStatus(1));
	}

	/** A file that vanishes between discovery and read is a race, not a solver failure. */
	@Test
	public void aStatusFileVanishingBetweenChecksFabricatesNothing() throws Exception {
		writeStatus(COMPLETE, 1);
		writeCleanRun();
		LocalJobMonitor monitor = freshMonitor();

		Files.delete(logDir.resolve("status.json"));
		poll(monitor);

		assertEquals(ENQUEUED, pairStatus(), "a vanished file is not a runscript error");
		assertEquals(ENQUEUED, stageStatus(1));
		assertEquals(0, completions());
	}

	// --------------------------------------------- the authoritative terminal predicate

	/**
	 * Retirement must follow the same terminal contract the database enforces.
	 *
	 * <p>Compared against {@code isTerminalExecutionResult()} for every status rather than a
	 * second hand-maintained list; {@code TerminalStatusContractSqlTest} separately pins that
	 * predicate to {@code starexec.IsTerminalPairStatus}.
	 */
	@Test
	public void onlyTerminalExecutionResultsRetireTracking() throws Exception {
		for (StatusCode code : StatusCode.values()) {
			cleanUp();
			seed();
			writeStatus(code.getVal(), 1);
			writeCleanRun();

			LocalJobMonitor monitor = freshMonitor();
			poll(monitor);

			if (code.isTerminalExecutionResult()) {
				assertFalse(isTracked(monitor), code + " is a result and must retire tracking");
			} else {
				assertTrue(isTracked(monitor),
						code + " is not a result and must NOT retire tracking");
			}
		}
	}

	/** Named individually, because these three are why the predicate had to change. */
	@Test
	public void processingPausedAndProcessingResultsDoNotRetireTracking() throws Exception {
		for (StatusCode code : new StatusCode[]{StatusCode.STATUS_PROCESSING_RESULTS,
				StatusCode.STATUS_PAUSED, StatusCode.STATUS_PROCESSING}) {
			cleanUp();
			seed();
			writeStatus(code.getVal(), 1);
			writeCleanRun();

			LocalJobMonitor monitor = freshMonitor();
			poll(monitor);

			assertTrue(isTracked(monitor),
					code + "(" + code.getVal() + ") means work is still owed");
		}
	}

	// --------------------------------------------------- stale attempt contamination

	/**
	 * A rerun must not inherit the previous attempt's stage history.
	 *
	 * <p>LocalBackend output directories are keyed by pair, not by attempt --
	 * {@code new File(job.logPath).getParentFile()} over
	 * {@code JobPairs.getStdout(pairId)} -- so attempt B writes into the tree attempt A left
	 * behind. {@code cleanupPreviousRunArtifacts} already cleared seven fixed names at the
	 * right moment, before the pair is registered with the monitor, but not
	 * {@code stage-status/}, which nothing read until stage-history ingestion existed.
	 */
	@Test
	public void aRerunDoesNotInheritThePreviousAttemptsStages() throws Exception {
		// Attempt A: two stages.
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeSnapshot(2, PAIR_ID, 2, COMPLETE);
		Files.writeString(logDir.resolve("stage-status/2.json.tmp"), "partial");

		assertTrue(clearPreviousAttempt(), "the attempt boundary must clear it");
		assertFalse(Files.exists(logDir.resolve("stage-status")),
				"the whole tree must be gone");

		// Attempt B: one stage only.
		writeStatus(COMPLETE, 1);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeCleanRun();

		poll(freshMonitor());

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(NOT_REACHED, stageStatus(2), "attempt A's stage 2 must not be observed");
		assertEquals(COMPLETE, pairStatus());
	}

	/** Missing and empty trees are both success; a leftover temp file goes with the tree. */
	@Test
	public void clearingToleratesMissingEmptyAndPartialTrees() throws Exception {
		assertTrue(clearPreviousAttempt(), "missing directory");

		Files.createDirectories(logDir.resolve("stage-status"));
		assertTrue(clearPreviousAttempt(), "empty directory");
		assertFalse(Files.exists(logDir.resolve("stage-status")));

		Files.createDirectories(logDir.resolve("stage-status"));
		Files.writeString(logDir.resolve("stage-status/1.json.tmp"), "partial");
		assertTrue(clearPreviousAttempt(), "leftover temp file");
		assertFalse(Files.exists(logDir.resolve("stage-status")));
	}

	/** An unclearable tree must be reported so the attempt does not start. */
	@Test
	public void clearingFailureIsReportedSoTheAttemptCanBeRefused() throws Exception {
		Path dir = logDir.resolve("stage-status");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("1.json"), "{}");
		java.io.File readOnly = dir.toFile();
		Assumptions.assumeTrue(readOnly.setWritable(false, false),
				"needs a filesystem where chmod bites");
		try {
			assertFalse(clearPreviousAttempt(),
					"an unclearable tree must be reported, not ignored");
		} finally {
			assertTrue(readOnly.setWritable(true, false),
					"restore write permission on " + readOnly);
		}
	}

	private boolean clearPreviousAttempt() throws Exception {
		org.starexec.backend.LocalBackend backend = new org.starexec.backend.LocalBackend();
		java.lang.reflect.Method m =
				org.starexec.backend.LocalBackend.class.getDeclaredMethod(
						"cleanupPreviousRunArtifacts", java.io.File.class);
		m.setAccessible(true);
		return (Boolean) m.invoke(backend, logDir.toFile());
	}

	// ------------------------------------------------------ stage-history ingestion

	@Test
	public void aTwoStageSuccessRecordsBothStages() throws Exception {
		writeStatus(COMPLETE, 2);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeSnapshot(2, PAIR_ID, 2, COMPLETE);
		writeCleanRun();

		poll(freshMonitor());

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(COMPLETE, stageStatus(2));
		assertEquals(COMPLETE, pairStatus());
		assertEquals(1, completions(), "pair completion fires once");
	}

	@Test
	public void aFailureAtStageTwoRecordsAllThreeStages() throws Exception {
		writeStatus(EXCEED_CPU, 2);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeSnapshot(2, PAIR_ID, 2, EXCEED_CPU);
		writeCpuLimitBreach();

		poll(freshMonitor());

		assertEquals(COMPLETE, stageStatus(1));
		assertEquals(EXCEED_CPU, stageStatus(2));
		assertEquals(NOT_REACHED, stageStatus(3));
		assertEquals(EXCEED_CPU, pairStatus());
	}

	/** Legacy output invents no history: an absent snapshot is not a stage that completed. */
	@Test
	public void legacyOutputWithoutSnapshotsIsUnchanged() throws Exception {
		writeStatus(COMPLETE, 2);
		writeCleanRun();

		poll(freshMonitor());

		assertEquals(ENQUEUED, stageStatus(1), "no earlier stage may be invented");
		assertEquals(COMPLETE, stageStatus(2));
		assertEquals(NOT_REACHED, stageStatus(3));
		assertEquals(COMPLETE, pairStatus());
	}

	/** A stage that never got past RUNNING is lost history, not history to reconstruct. */
	@Test
	public void anEarlierStageStillRunningIsRefusedRatherThanCompleted() throws Exception {
		writeStatus(COMPLETE, 2);
		writeSnapshot(1, PAIR_ID, 1, StatusCode.STATUS_RUNNING.getVal());
		writeCleanRun();

		assertBlockedWithoutWriting();
	}

	@Test
	public void aSnapshotNamingAnotherPairWritesNothing() throws Exception {
		writeStatus(COMPLETE, 2);
		writeSnapshot(1, PAIR_ID + 500, 1, COMPLETE);
		writeCleanRun();

		assertBlockedWithoutWriting();
	}

	@Test
	public void aStageThePairDoesNotHaveWritesNothing() throws Exception {
		writeStatus(COMPLETE, 9);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeSnapshot(8, PAIR_ID, 8, COMPLETE);
		writeCleanRun();

		assertBlockedWithoutWriting();
	}

	@Test
	public void malformedJsonIsBlockedWithEvidenceRetained() throws Exception {
		writeStatus(COMPLETE, 2);
		Files.createDirectories(logDir.resolve("stage-status"));
		Files.writeString(logDir.resolve("stage-status/1.json"), "{not json");
		writeCleanRun();

		assertBlockedWithoutWriting();
		assertTrue(Files.exists(logDir.resolve("stage-status/1.json")),
				"evidence must be kept for diagnosis");
	}

	/**
	 * Processing the same completed result twice must not move anything.
	 *
	 * <p>Asserted on the completion id and end_time rather than a completion row count: that
	 * count is 1 by schema construction -- UNIQUE(pair_id) plus ON CONFLICT DO NOTHING -- so it
	 * would read 1 even if the terminal path ran once per stage.
	 */
	@Test
	public void reprocessingTheSameResultChangesNothing() throws Exception {
		writeStatus(EXCEED_CPU, 2);
		writeSnapshot(1, PAIR_ID, 1, COMPLETE);
		writeSnapshot(2, PAIR_ID, 2, EXCEED_CPU);
		writeCpuLimitBreach();

		LocalJobMonitor first = freshMonitor();
		poll(first);
		assertTrue(!isTracked(first), "tracking retires after success");
		int completionBefore = completionId();
		String endTimeBefore = pairEndTime();
		int stage1Before = stageStatus(1);

		// A second monitor re-registers the same completed output and processes it again.
		poll(freshMonitor());

		assertEquals(stage1Before, stageStatus(1));
		assertEquals(EXCEED_CPU, stageStatus(2));
		assertEquals(EXCEED_CPU, pairStatus());
		assertEquals(1, completions());
		assertEquals(completionBefore, completionId(), "the completion row must be the same one");
		assertEquals(endTimeBefore, pairEndTime(), "end_time must not drift");
	}

	private LocalJobMonitor freshMonitor() throws Exception {
		LocalJobMonitor monitor = new LocalJobMonitor();
		monitor.registerJob(logDir.toString(), PAIR_ID);
		installClock(monitor);
		return monitor;
	}

	/** Blocked: held for intervention, nothing written, nothing fabricated. */
	private void assertBlockedWithoutWriting() throws Exception {
		LocalJobMonitor monitor = freshMonitor();
		poll(monitor);

		assertTrue(isBlocked(monitor), "an invalid artifact must block, not retry forever");
		assertEquals(ENQUEUED, stageStatus(1), "stage 1 untouched");
		assertEquals(ENQUEUED, stageStatus(2), "stage 2 untouched");
		assertEquals(ENQUEUED, pairStatus(), "no solver status may be fabricated");
		assertEquals(0, completions(), "nothing may complete the pair");
		assertTrue(isTracked(monitor), "the pair stays tracked with its evidence");
	}

	private void writeSnapshot(int fileStage, int pairId, int recordStage, int status)
			throws Exception {
		Files.createDirectories(logDir.resolve("stage-status"));
		Files.writeString(logDir.resolve("stage-status/" + fileStage + ".json"),
				"{\"pairId\":" + pairId + ",\"status\":" + status
						+ ",\"stageNumber\":" + recordStage + ",\"timestamp\":1788818872}\n");
	}

	private void writeCpuLimitBreach() throws Exception {
		Files.writeString(logDir.resolve("var.out"),
				"WCTIME=10.5\nCPUTIME=10.4\nMAXVM=1000\nTIMEOUT=true\nMEMOUT=false\n");
		Files.writeString(logDir.resolve("watcher.out"),
				"Maximum CPU time exceeded: sending SIGTERM then SIGKILL\n");
	}

	private int completionId() throws SQLException {
		try (Connection con = Common.getConnection()) {
			return scalar(con, "SELECT coalesce(max(completion_id),-1) FROM"
					+ " starexec.job_pair_completion WHERE pair_id=" + PAIR_ID);
		}
	}

	private int completions() throws SQLException {
		try (Connection con = Common.getConnection()) {
			return scalar(con, "SELECT count(*) FROM starexec.job_pair_completion"
					+ " WHERE pair_id=" + PAIR_ID);
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

	// ------------------------------------------------------------------ clock + state

	/** A monotonic source the test advances explicitly; no sleeps, no wall-clock coupling. */
	private static final class FakeClock implements java.util.function.LongSupplier {
		private long nanos = 1_000_000_000L;

		@Override
		public long getAsLong() {
			return nanos;
		}

		void advanceMillis(long millis) {
			nanos += millis * 1_000_000L;
		}
	}

	private static FakeClock installClock(LocalJobMonitor monitor) throws Exception {
		FakeClock clock = new FakeClock();
		java.lang.reflect.Field f = LocalJobMonitor.class.getDeclaredField("nanoTime");
		f.setAccessible(true);
		f.set(monitor, clock);
		return clock;
	}

	private static Object stateOf(LocalJobMonitor monitor) throws Exception {
		java.lang.reflect.Field f = LocalJobMonitor.class.getDeclaredField("pairs");
		f.setAccessible(true);
		return ((java.util.Map<?, ?>) f.get(monitor)).get(PAIR_ID);
	}

	private static Object field(Object state, String name) throws Exception {
		java.lang.reflect.Field f = state.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(state);
	}

	private static int ingestionFailures(LocalJobMonitor monitor) throws Exception {
		Object state = stateOf(monitor);
		return state == null ? -1 : (Integer) field(state, "ingestionFailures");
	}

	private static boolean isBlocked(LocalJobMonitor monitor) throws Exception {
		Object state = stateOf(monitor);
		return state != null && (Boolean) field(state, "ingestionBlocked");
	}

	private static long nextRetryDelayMillis(LocalJobMonitor monitor, FakeClock clock)
			throws Exception {
		Object state = stateOf(monitor);
		long due = (Long) field(state, "nextRetryAtNanos");
		return (due - clock.getAsLong()) / 1_000_000L;
	}

	private void renameRoutine(String from, String to, String signature) throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("ALTER FUNCTION starexec." + from + "(" + signature + ") RENAME TO " + to);
		}
	}

	// ------------------------------------------------------------------------- harness

	@SuppressWarnings("unchecked")
	private static boolean isTracked(LocalJobMonitor monitor) throws Exception {
		java.lang.reflect.Field f = LocalJobMonitor.class.getDeclaredField("pairs");
		f.setAccessible(true);
		return ((java.util.Map<Integer, ?>) f.get(monitor)).containsKey(PAIR_ID);
	}

	private static void poll(LocalJobMonitor monitor) throws Exception {
		Method m = LocalJobMonitor.class.getDeclaredMethod("checkCompletedJobs");
		m.setAccessible(true);
		m.invoke(monitor);
	}

	private void writeStatus(int status, int stage) throws Exception {
		Files.writeString(logDir.resolve("status.json"),
				"{\"pairId\":" + PAIR_ID + ",\"status\":" + status
						+ ",\"stageNumber\":" + stage + ",\"timestamp\":1788818872}\n");
	}

	private void writeCleanRun() throws Exception {
		Files.writeString(logDir.resolve("var.out"),
				"WCTIME=0.10\nCPUTIME=0.09\nMAXVM=1000\nTIMEOUT=false\nMEMOUT=false\n");
		Files.writeString(logDir.resolve("watcher.out"), "Child status: 0\n");
	}

	private void blockPairWrites() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("CREATE OR REPLACE FUNCTION starexec.probe_block_pair() RETURNS trigger AS"
					+ " $probe$ BEGIN IF NEW.id = " + PAIR_ID
					+ " AND NEW.status_code IS DISTINCT FROM OLD.status_code"
					+ " THEN RAISE EXCEPTION 'probe: pair write blocked'; END IF;"
					+ " RETURN NEW; END; $probe$ LANGUAGE plpgsql");
			s.execute("CREATE TRIGGER probe_block_pair BEFORE UPDATE ON starexec.job_pairs"
					+ " FOR EACH ROW EXECUTE FUNCTION starexec.probe_block_pair()");
		}
	}

	private void unblockPairWrites() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("DROP TRIGGER IF EXISTS probe_block_pair ON starexec.job_pairs");
			s.execute("DROP FUNCTION IF EXISTS starexec.probe_block_pair()");
		}
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

	private static int scalar(Connection con, String sql) throws SQLException {
		try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			assertTrue(rs.next(), "query returned no row: " + sql);
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
