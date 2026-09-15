package org.starexec.test.unit.jobs;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.ContainerJobMonitor;
import org.starexec.backend.ExecutionRef;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.backend.LocalBackend;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.backend.PodmanBackend;
import org.starexec.backend.StageStatusSnapshots;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.JobPairs.PairStatusLookupState;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;
import org.starexec.data.to.Status.StatusCode;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which stage a multi-stage pair's post-processor attributes (#179) and runsolver measurements
 * (#180) are recorded against, from the real {@code functions.bash} through each container-mode
 * monitor's real ingestion.
 *
 * <p>Reproduces the qualification finding on integrated jobs 2-4 (two E stages): stage 1's output
 * said Theorem, yet the database held the final stage's SZS UNK under stage 1 and nothing under
 * stage 2.
 *
 * <h2>How faithful the producer is</h2>
 *
 * The shipped helper, not a model of it. The pair starts through {@code sendNode}, which is where
 * the helper declares the per-stage protocol; each stage reuses the one {@code $WORKING_DIR/output}
 * the real job script uses and runs the real {@code copyOutput} with a real post-processor, and
 * the real {@code cleanForNextStage} empties that directory between stages. Stage outcomes are
 * sent with the calls the jobscript makes. Only {@code copyOutputNoStats} is stubbed: it saves
 * solver output into the pair's output tree, which is not under test.
 *
 * <h2>How faithful the database is</h2>
 *
 * {@code JobPairs} is mocked, and the mock refuses what the database refuses: a precise status
 * for a stage the pair does not have ({@code REJECTED_INVALID_STAGE}, which covers stage 0), and
 * an earlier-stage batch naming such a stage ({@code REJECTED_UNKNOWN_STAGE}). Every attribute
 * write is recorded as a separate call, so a double or partial write is visible, and each stage's
 * attributes are compared as an exact map.
 *
 * <p>Every test runs once per backend. Local polls while a pair runs, so the mid-run tests are
 * Local's alone; Podman and Kubernetes ingest once, after the job has exited.
 */
@RunWith(Parameterized.class)
public class StageResultAttributionTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	private static final int PAIR = 4242;

	/** Two keys per stage, distinct values, so a leaked or stale key cannot hide. */
	private static final String THEOREM = "starexec-result=Theorem\nSZSStatus=THM\n";
	private static final String UNKNOWN = "starexec-result=Unknown\nSZSStatus=UNK\nSZSOutput=None\n";
	private static final String SATISFIABLE = "starexec-result=Satisfiable\nSZSStatus=SAT\n";

	enum Backend { LOCAL, CONTAINER, KUBERNETES }

	@Parameterized.Parameters(name = "{0}")
	public static Object[] backends() {
		return Backend.values();
	}

	@Parameterized.Parameter
	public Backend backend;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// ------------------------------------------------------------------ placement (W1, W2)

	/** The finding itself: each stage's own set, on its own stage, written once. */
	@Test
	public void eachStageKeepsItsOwnAttributes() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		assertRecorded(ingest(pair, 1, 2), Map.of(1, THEOREM, 2, UNKNOWN));
	}

	/**
	 * Stage numbers are identities, not positions. No-op pipeline stages own no row, so a pair
	 * can have stages 1 and 3; a writer keyed on the loop index would file stage 3 as stage 2.
	 */
	@Test
	public void nonContiguousStagesKeepTheirOwnNumbers() throws Exception {
		Pair pair = new Pair(1, 3);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(3, SATISFIABLE).complete(3);
		pair.run(0);

		assertRecorded(ingest(pair, 1, 3), Map.of(1, THEOREM, 3, SATISFIABLE));
	}

	/**
	 * A final stage with no post-processor leaves the previous stage's attributes.txt behind.
	 * Recording that file against the terminal stage is exactly what Kubernetes used to do.
	 */
	@Test
	public void aStageWithoutAPostProcessorGetsNoAttributes() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, null).complete(2);
		pair.run(0);

		assertRecorded(ingest(pair, 1, 2), Map.of(1, THEOREM));
	}

	/** A post-processor that prints nothing gives its stage no attributes, and is not an error. */
	@Test
	public void emptyPostProcessorOutputGivesItsStageNoAttributes() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, "").complete(2);
		pair.run(0);

		Ingested result = ingest(pair, 1, 2);
		assertTrue("an empty result is not a failure: " + result.failure, result.failure == null);
		assertRecorded(result, Map.of(1, THEOREM));
	}

	// ------------------------------------------------------------- legacy disabled (W6)

	/**
	 * With the protocol declared, attributes.txt is never read -- not even when it disagrees with
	 * every per-stage file. Bogus must not reach the database on any backend.
	 */
	@Test
	public void theLegacyFileIsNeverReadUnderTheProtocol() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		Files.writeString(pair.out.resolve("attributes.txt"), "starexec-result=Bogus\nBogus=1\n");

		Ingested result = ingest(pair, 1, 2);
		assertNoValue(result, "Bogus");
		assertRecorded(result, Map.of(1, THEOREM, 2, UNKNOWN));
	}

	/** Declared, but no stage published anything: nothing is recorded, not the legacy file. */
	@Test
	public void theMarkerWithNoFilesRecordsNothing() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		deleteTree(pair.out.resolve("stage-attributes"));
		Files.createDirectories(pair.out.resolve("stage-attributes"));

		assertRecorded(ingest(pair, 1, 2), Map.of());
	}

	// ------------------------------------------------------------- single stage (W7)

	/** Single-stage pairs record exactly what they always did, with the protocol ... */
	@Test
	public void aSingleStagePairIsUnchangedWithTheProtocol() throws Exception {
		Pair pair = new Pair(1);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.run(0);

		assertTrue("the helper declares the protocol",
				Files.isDirectory(pair.out.resolve("stage-attributes")));
		assertRecorded(ingest(pair, 1), Map.of(1, THEOREM));
	}

	/** ... and from a helper that predates it, whose only output is attributes.txt. */
	@Test
	public void aSingleStagePairIsUnchangedFromAnOlderHelper() throws Exception {
		Pair pair = new Pair(1);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.run(0);
		pair.asOlderHelper();

		assertRecorded(ingest(pair, 1), Map.of(1, THEOREM));
	}

	/**
	 * An older helper's multi-stage attributes.txt names no stage, and neither stage 1 nor the
	 * terminal stage is a safe guess, so it is not recorded at all.
	 */
	@Test
	public void aMultiStagePairFromAnOlderHelperRecordsNothing() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		pair.asOlderHelper();

		assertRecorded(ingest(pair, 1, 2), Map.of());
	}

	// ------------------------------------------------------------------ gate (W5)

	/** A stage whose snapshot is missing has not been shown to finish, so it is not consumed. */
	@Test
	public void aStageWithoutASnapshotIsNotConsumed() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		Files.delete(pair.out.resolve("stage-status").resolve("1.json"));

		assertRecorded(ingest(pair, 1, 2), Map.of(2, UNKNOWN));
	}

	/** Local polls mid-stage-1: stage 1's snapshot still says RUNNING, so nothing is consumed. */
	@Test
	public void aLocalPollDuringStageOneRecordsNothing() throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL);
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM);
		pair.run(0);

		Ingested poll = ingestLocal(pair, false, 1, 2);
		assertIngested(poll);
		assertFalse("the pair is still running", poll.terminal);
		assertRecorded(poll, Map.of());
	}

	/**
	 * Local polls mid-stage-2, with stage 1 finished and attributes.txt holding stage 1's set:
	 * status.json still names stage 1 as running, so nothing is consumed yet -- and once the pair
	 * completes, both stages are recorded from their own files.
	 */
	@Test
	public void aLocalPollDuringStageTwoRecordsNothingUntilThePairCompletes() throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL);
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN);
		pair.run(0);

		Local local = new Local(pair);
		try {
			Ingested midStage = local.poll(1, 2);
			assertIngested(midStage);
			assertFalse("the pair is still running", midStage.terminal);
			assertRecorded(midStage, Map.of());

			pair.append("sendStageStatus \"$STATUS_COMPLETE\" 2\n");
			pair.append("sendStatus \"$STATUS_COMPLETE\" 2\n");
			pair.run(0);
			Ingested done = local.poll(1, 2);
			assertIngested(done);
			assertTrue("the pair is complete", done.terminal);
			assertRecorded(done, Map.of(1, THEOREM, 2, UNKNOWN));
		} finally {
			local.close();
		}
	}

	/**
	 * A rerun registered after the poll wrote the pair's status, but before it wrote the
	 * attributes, has started over: the files this poll already read belong to the attempt the
	 * rerun replaced, so none of them may be written against the pair.
	 */
	@Test
	public void aLocalPollSupersededAfterTheStatusWriteRecordsNoAttributes() throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL);
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		Local local = new Local(pair);
		try {
			boolean[] rerunLanded = { false };
			Ingested result = local.poll(() -> {
				local.monitor.registerJob(pair.out.toString(), PAIR);
				rerunLanded[0] = true;
			}, 1, 2);
			assertTrue("the rerun must land at the status write", rerunLanded[0]);
			assertRecorded(result, Map.of());
		} finally {
			local.close();
		}
	}

	// ------------------------------------------------------------------ edge cases

	/**
	 * A stage that errors after its post-processor ran keeps its attributes, as a single-stage
	 * pair's attributes.txt always was recorded whatever the pair's status. Here runsolver's
	 * watch file is missing: copyOutput runs, then markRunscriptError (jobscript:537-540).
	 */
	@Test
	public void aStageThatErrorsAfterPostProcessingKeepsItsAttributes() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN);
		pair.append("markRunscriptError 2\n");
		pair.run(0);

		assertRecorded(ingest(pair, 1, 2), Map.of(1, THEOREM, 2, UNKNOWN));
	}

	/**
	 * The exitJobscript catch-all reports on the pair-level channel, stage 0, and writes no
	 * snapshot. No stage is named, so nothing may be recorded for any of them (#145's shape).
	 */
	@Test
	public void aPairLevelFailureRecordsNothing() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN);
		pair.append("sendStatus \"$ERROR_BENCHMARK\"\n");
		pair.run(0);

		// Local and Podman refuse the stage-0 result outright; Kubernetes reports it handled.
		// Either way no attribute may be written.
		Ingested result = ingest(pair, 1, 2);
		assertTrue("a refusal, if any, is the invalid-stage one: " + result.failure,
				result.failure == null
						|| result.failure instanceof StageStatusSnapshots.InvalidSnapshotException);
		assertWritten(result, Map.of());
	}

	/**
	 * A post-processor that fails part-way leaves partial output in the sandbox, and none of it
	 * may be published: its stage gets nothing, and stage 1 keeps its own set.
	 */
	@Test
	public void aFailedPostProcessorPublishesNothingForItsStage() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.failingStage(2, "starexec-result=Partial\n");
		pair.run(1);

		assertFalse("the partial output must not be published",
				Files.exists(pair.out.resolve("stage-attributes").resolve("2.txt")));
		Ingested result = ingest(pair, 1, 2);
		assertNoValue(result, "Partial");
		assertRecorded(result, Map.of(1, THEOREM));
	}

	/**
	 * A rerun that dies in stage 1 must not pick up the previous attempt's stage-2 file. Local
	 * and Kubernetes clear the directory before the attempt starts, as they clear stage-status;
	 * the gate alone already keeps stage 2 out, because the new attempt never reached it.
	 */
	@Test
	public void aRerunThatDiesInStageOneIgnoresThePreviousAttempt() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		assertTrue(Files.exists(pair.out.resolve("stage-attributes").resolve("2.txt")));

		clearForRerun(pair.out);

		Pair rerun = pair.rerun();
		rerun.start();
		rerun.stage(1, SATISFIABLE);
		rerun.append("markRunscriptError 1\n");
		rerun.run(0);

		assertRecorded(ingest(rerun, 1, 2), Map.of(1, SATISFIABLE));
	}

	/**
	 * A rerun in the same output directory with no backend cleanup, as on Podman, whose stage 2
	 * post-processor fails: stage 2 is terminal but published nothing, so the previous attempt's
	 * 2.txt must be gone rather than filed against the stage that failed. The helper clears it
	 * when the pair starts.
	 */
	@Test
	public void aRerunWithoutCleanupGetsNothingFromThePreviousAttempt() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		assertTrue(Files.exists(pair.out.resolve("stage-attributes").resolve("2.txt")));

		Pair rerun = pair.rerun();
		rerun.start();
		rerun.stage(1, SATISFIABLE).complete(1);
		rerun.failingStage(2, "starexec-result=Partial\n");
		rerun.run(1);

		assertTrue("the directory stays: it is the marker",
				Files.isDirectory(pair.out.resolve("stage-attributes")));
		Ingested result = ingest(rerun, 1, 2);
		assertNoValue(result, "Unknown");
		assertRecorded(result, Map.of(1, SATISFIABLE));
	}

	/**
	 * Fail closed: a previous attempt's file the helper cannot remove stops the pair before any
	 * stage runs, reported at the pair level so no stage's surviving file is read.
	 */
	@Test
	public void aPreviousAttemptThatCannotBeClearedFailsThePairBeforeAnyStage() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		Path marker = pair.out.resolve("stage-attributes");
		Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("r-xr-xr-x"));
		Ingested result;
		try {
			Pair rerun = pair.rerun();
			rerun.start();
			rerun.stage(1, SATISFIABLE).complete(1);
			rerun.stage(2, SATISFIABLE).complete(2);
			rerun.run(0);

			assertEquals("no stage may have run", THEOREM,
					Files.readString(marker.resolve("1.txt")));
			String status = Files.readString(pair.out.resolve("status.json"));
			assertTrue("a pair-level runscript error: " + status,
					status.contains("\"status\":11,") && status.contains("\"stageNumber\":0,"));
			result = ingest(rerun, 1, 2);
		} finally {
			Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
		assertTrue("a refusal, if any, is the invalid-stage one: " + result.failure,
				result.failure == null
						|| result.failure instanceof StageStatusSnapshots.InvalidSnapshotException);
		assertWritten(result, Map.of());
	}

	/** Clearing never follows a link planted where the marker belongs. */
	@Test
	public void aLinkedMarkerIsReplacedRatherThanClearedThrough() throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL); // a property of the helper alone
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.run(0);

		Path outside = folder.newFolder("outside").toPath();
		Files.writeString(outside.resolve("2.txt"), "keep\n");
		Path marker = pair.out.resolve("stage-attributes");
		deleteTree(marker);
		Files.createSymbolicLink(marker, outside);

		Pair rerun = pair.rerun();
		rerun.start();
		rerun.run(0);

		assertTrue("a file outside the output was deleted", Files.exists(outside.resolve("2.txt")));
		assertTrue(Files.isDirectory(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS));
	}

	/** Rollback: an older monitor reads attributes.txt, which the new helper still publishes. */
	@Test
	public void theLegacyFileIsStillPublishedForAnOlderMonitor() throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL); // a property of the helper alone
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		assertEquals(UNKNOWN, Files.readString(pair.out.resolve("attributes.txt")));
	}

	// ------------------------------------------------------------- measurements (#180)

	/**
	 * Each stage's cpu, wallclock, memory and disk are recorded against that stage, exactly. The
	 * qualification finding: only the final stage's measurements survived (#180).
	 */
	@Test
	public void everyStageKeepsItsOwnMeasurements() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		assertMeasured(ingest(pair, 1, 2), Map.of(1, measured(0, 1), 2, measured(0, 2)));
	}

	@Test
	public void nonContiguousStagesKeepTheirOwnMeasurements() throws Exception {
		Pair pair = new Pair(1, 3);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(3, UNKNOWN).complete(3);
		pair.run(0);

		assertMeasured(ingest(pair, 1, 3), Map.of(1, measured(0, 1), 3, measured(0, 3)));
	}

	/**
	 * A final stage whose var file was missing goes through copyOutputNoStats and
	 * markRunscriptError (jobscript:530-534), so it publishes no measurements -- and stats.json
	 * still holds stage 1's. Stage 2 must get nothing rather than stage 1's numbers.
	 */
	@Test
	public void aStageThatNeverReachedCopyOutputIsNotGivenThePreviousStagesMeasurements()
			throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stageWithoutCopyOutput(2);
		pair.run(0);

		assertMeasured(ingest(pair, 1, 2), Map.of(1, measured(0, 1)));
	}

	/**
	 * Zero is a measurement. runsolver samples virtual memory only after 0.1 s, so a short stage
	 * reports MAXVM=0, and it is recorded as exactly that.
	 */
	@Test
	public void aZeroMeasurementIsRecordedExactly() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.varFile(1, "WCTIME=0.05\nCPUTIME=0.04\nUSERTIME=0.03\nSYSTEMTIME=0\nMAXVM=0\n"
				+ "TIMEOUT=false\nMEMOUT=false\n");
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		assertMeasured(ingest(pair, 1, 2), Map.of(
				1, List.of("node-7", 0.05, 0.04, 0.03, 0.0, 0.0, 10L, 100L),
				2, measured(0, 2)));
	}

	/**
	 * Every stage is charged its own disk, as the grid-engine path always charged it: stage 1's
	 * saved output against stage 1, stage 2's against stage 2. Before, only the final stage's
	 * output was charged at all.
	 */
	@Test
	public void everyStageIsChargedItsOwnDisk() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		assertIngested(ingest(pair, 1, 2));
		assertEquals("each stage's recorded disk", Map.of(1, 100L, 2, 200L), ledger.stageDisk);
		assertEquals("the user's charge is the sum of the stages'", 300L, ledger.charged);
	}

	/**
	 * Local polls a finished stage again and again. Each poll records the same measurements, and
	 * the user is still charged each stage's disk once.
	 */
	@Test
	public void repeatedLocalPollsChargeEachStageOnce() throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL);
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		Local local = new Local(pair);
		try {
			assertMeasured(local.poll(1, 2), Map.of(1, measured(0, 1), 2, measured(0, 2)));
			assertMeasured(local.poll(1, 2), Map.of(1, measured(0, 1), 2, measured(0, 2)));
		} finally {
			local.close();
		}
		assertEquals(Map.of(1, 100L, 2, 200L), ledger.stageDisk);
		assertEquals("a second poll must not charge again", 300L, ledger.charged);
	}

	/**
	 * Local polls mid-stage-2, after stage 2's copyOutput published but before the stage
	 * finished: nothing is recorded yet, not stage 2's numbers under whatever stats.json names.
	 */
	@Test
	public void aLocalPollDuringStageTwoRecordsNoMeasurementsUntilThePairCompletes()
			throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL);
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN);
		pair.run(0);

		Local local = new Local(pair);
		try {
			Ingested midStage = local.poll(1, 2);
			assertFalse("the pair is still running", midStage.terminal);
			assertMeasured(midStage, Map.of());

			pair.append("sendStageStatus \"$STATUS_COMPLETE\" 2\n");
			pair.append("sendStatus \"$STATUS_COMPLETE\" 2\n");
			pair.run(0);
			assertMeasured(local.poll(1, 2), Map.of(1, measured(0, 1), 2, measured(0, 2)));
		} finally {
			local.close();
		}
	}

	/** As for attributes: a rerun registered at the status write has started over. */
	@Test
	public void aLocalPollSupersededAfterTheStatusWriteRecordsNoMeasurements() throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL);
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		Local local = new Local(pair);
		try {
			boolean[] rerunLanded = { false };
			Ingested result = local.poll(() -> {
				local.monitor.registerJob(pair.out.toString(), PAIR);
				rerunLanded[0] = true;
			}, 1, 2);
			assertTrue("the rerun must land at the status write", rerunLanded[0]);
			assertMeasured(result, Map.of());
		} finally {
			local.close();
		}
	}

	/**
	 * A rerun in the same output directory with no backend cleanup, as on Podman, whose stage 2
	 * never reaches copyOutput: stage 2 gets neither the previous attempt's stage-2 file nor this
	 * attempt's stage 1.
	 */
	@Test
	public void aRerunWithoutCleanupGetsNoMeasurementsFromThePreviousAttempt() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		assertTrue(Files.exists(pair.out.resolve("stage-stats").resolve("2.json")));

		Pair rerun = pair.rerun();
		rerun.start();
		rerun.stage(1, SATISFIABLE).complete(1);
		rerun.stageWithoutCopyOutput(2);
		rerun.run(0);

		assertMeasured(ingest(rerun, 1, 2), Map.of(1, measured(1, 1)));
	}

	/** Fail closed, as for attributes: a stale stats file that survives stops the pair. */
	@Test
	public void aPreviousAttemptsStatsThatCannotBeClearedFailThePairBeforeAnyStage()
			throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);

		Path marker = pair.out.resolve("stage-stats");
		String before = Files.readString(marker.resolve("1.json"));
		Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("r-xr-xr-x"));
		Ingested result;
		try {
			Pair rerun = pair.rerun();
			rerun.start();
			rerun.stage(1, SATISFIABLE).complete(1);
			rerun.run(0);

			assertEquals("no stage may have run", before, Files.readString(marker.resolve("1.json")));
			String status = Files.readString(pair.out.resolve("status.json"));
			assertTrue("a pair-level runscript error: " + status,
					status.contains("\"status\":11,") && status.contains("\"stageNumber\":0,"));
			result = ingest(rerun, 1, 2);
		} finally {
			Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
		assertTrue("a refusal, if any, is the invalid-stage one: " + result.failure,
				result.failure == null
						|| result.failure instanceof StageStatusSnapshots.InvalidSnapshotException);
		assertEquals("no measurement may be written", List.of(), result.measured);
	}

	/**
	 * A stage whose measurements cannot be published fails at that stage: with the marker present
	 * nothing else is read, so carrying on would lose them silently.
	 */
	@Test
	public void aStageWhoseMeasurementsCannotBePublishedFails() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.append("chmod a-w \"$STAREXEC_OUTPUT_DIR/stage-stats\"\n");
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		Path marker = pair.out.resolve("stage-stats");
		Ingested result;
		try {
			pair.run(1);
			String status = Files.readString(pair.out.resolve("status.json"));
			assertTrue("a general error at stage 1: " + status,
					status.contains("\"status\":18,") && status.contains("\"stageNumber\":1,"));
			assertTrue("the legacy file is still written first",
					Files.exists(pair.out.resolve("stats.json")));
			result = ingest(pair, 1, 2);
		} finally {
			Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
		assertMeasured(result, Map.of());
	}

	/** The helper declares the protocol when the pair starts, before any stage publishes. */
	@Test
	public void theMeasurementsMarkerIsCreatedWhenThePairStarts() throws Exception {
		Assume.assumeTrue(backend == Backend.LOCAL); // a property of the helper alone
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.run(0);

		Path marker = pair.out.resolve("stage-stats");
		assertTrue(Files.isDirectory(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS));
		try (var entries = Files.list(marker)) {
			assertEquals(0, entries.count());
		}
	}

	// ---------------------------------------------------- measurements from an older helper

	/**
	 * Without the marker, stats.json is recorded under the stage it names -- here the final one,
	 * which is the only one it still holds.
	 */
	@Test
	public void anOlderHelpersStatsAreRecordedUnderTheStageTheyName() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		pair.asOlderStatsHelper();

		assertMeasured(ingest(pair, 1, 2), Map.of(2, measured(0, 2)));
	}

	/** ... not under the terminal stage when stats.json still holds the stage before it. */
	@Test
	public void anOlderHelpersStatsFromAnEarlierStageStayWithThatStage() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stageWithoutCopyOutput(2);
		pair.run(0);
		pair.asOlderStatsHelper();

		assertMeasured(ingest(pair, 1, 2), Map.of(1, measured(0, 1)));
	}

	/** A stats.json that does not say which stage it is from is not given one (C1). */
	@Test
	public void anOlderHelpersStatsWithoutAStageNumberRecordNothing() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		pair.asOlderStatsHelper();
		pair.dropStatsField("stageNumber");

		assertMeasured(ingest(pair, 1, 2), Map.of());
	}

	/** ... nor one that does not say which pair it is from. */
	@Test
	public void anOlderHelpersStatsWithoutAPairIdRecordNothing() throws Exception {
		Pair pair = new Pair(1, 2);
		pair.start();
		pair.stage(1, THEOREM).complete(1);
		pair.stage(2, UNKNOWN).complete(2);
		pair.run(0);
		pair.asOlderStatsHelper();
		pair.dropStatsField("pairId");

		assertMeasured(ingest(pair, 1, 2), Map.of());
	}

	// --------------------------------------------------------------------- assertions

	private static void assertRecorded(Ingested result, Map<Integer, String> expected) {
		assertIngested(result);
		assertWritten(result, expected);
	}

	private static void assertWritten(Ingested result, Map<Integer, String> expected) {
		Map<Integer, Map<String, String>> want = new TreeMap<>();
		for (Map.Entry<Integer, String> e : expected.entrySet()) {
			want.put(e.getKey(), keyValues(e.getValue()));
		}
		Map<Integer, Map<String, String>> got = new TreeMap<>();
		for (Write w : result.writes) {
			assertFalse("stage " + w.stage + " was written more than once: " + result.writes,
					got.containsKey(w.stage));
			assertFalse("stage " + w.stage + " was written with no attributes: " + result.writes,
					w.attributes.isEmpty());
			got.put(w.stage, w.attributes);
		}
		assertEquals("attributes by stage (ingestion outcome: "
				+ (result.failure == null ? "ok" : result.failure) + ")", want, got);
	}

	/**
	 * The measurements written in one ingestion, by stage, exactly: node, wallclock, cpu, user,
	 * system, max virtual memory, max resident set size, disk.
	 */
	private static void assertMeasured(Ingested result, Map<Integer, List<Object>> expected) {
		assertIngested(result);
		Map<Integer, List<Object>> got = new TreeMap<>();
		for (Measured m : result.measured) {
			assertFalse("stage " + m.stage + " was measured more than once: " + result.measured,
					got.containsKey(m.stage));
			got.put(m.stage, m.values);
		}
		assertEquals("measurements by stage", new TreeMap<>(expected), got);
	}

	/** What the harness's default var file, watch file and stdout give a stage. */
	private static List<Object> measured(int generation, int stage) {
		int v = stage + 10 * generation;
		return List.of("node-7", v + 0.5, v + 0.25, (double) v, 0.0, v * 1000.0,
				(long) v * 10, (long) v * 100);
	}

	/** "Nothing was recorded" means something only if the ingestion itself went through. */
	private static void assertIngested(Ingested result) {
		if (result.failure != null) {
			throw new AssertionError("the ingestion failed rather than completing", result.failure);
		}
	}

	private static void assertNoValue(Ingested result, String value) {
		for (Write w : result.writes) {
			assertFalse("'" + value + "' reached the database: " + w,
					w.attributes.containsValue(value) || w.attributes.containsKey(value));
		}
	}

	private static Map<String, String> keyValues(String lines) {
		Map<String, String> map = new TreeMap<>();
		for (String line : lines.split("\n")) {
			if (!line.isEmpty()) {
				int eq = line.indexOf('=');
				map.put(line.substring(0, eq), line.substring(eq + 1));
			}
		}
		return map;
	}

	// ----------------------------------------------------------------------- database

	private static final class Write {
		final int stage;
		final Map<String, String> attributes;

		Write(int stage, Map<String, String> attributes) {
			this.stage = stage;
			this.attributes = attributes;
		}

		@Override
		public String toString() {
			return stage + "=" + attributes;
		}
	}

	private static final class Measured {
		final int stage;
		final List<Object> values;

		Measured(int stage, List<Object> values) {
			this.stage = stage;
			this.values = values;
		}

		@Override
		public String toString() {
			return stage + "=" + values;
		}
	}

	/**
	 * The disk accounting of UpdatePairRunSolverStats: a stage's row takes the new size, and the
	 * user is charged the difference from what the row held.
	 */
	private static final class DiskLedger {
		final Map<Integer, Long> stageDisk = new TreeMap<>();
		long charged;
	}

	/** One per test, shared by every ingestion in it, as the database would be. */
	private final DiskLedger ledger = new DiskLedger();

	private static final class Ingested {
		final List<Write> writes = new ArrayList<>();
		final List<Measured> measured = new ArrayList<>();
		boolean terminal;
		Exception failure;
	}

	/** A database for one pair that has exactly {@code stages}, refusing what the real one does. */
	private Ingested stubDatabase(MockedStatic<JobPairs> db, Set<Integer> stages)
			throws Exception {
		Ingested result = new Ingested();
		db.when(() -> JobPairs.getStageNumbers(PAIR)).thenReturn(new TreeSet<>(stages));
		db.when(() -> JobPairs.setEarlierStageStatuses(Mockito.anyInt(), Mockito.anyMap()))
				.thenAnswer(inv -> {
					Map<?, ?> batch = inv.getArgument(1);
					return stages.containsAll(batch.keySet())
							? StageStatusBatchResult.APPLIED
							: StageStatusBatchResult.REJECTED_UNKNOWN_STAGE;
				});
		db.when(() -> JobPairs.setPairStatusPreciseResult(Mockito.anyInt(), Mockito.anyInt(),
						Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
				.thenAnswer(inv -> stages.contains((Integer) inv.getArgument(1))
						? PairStatusResult.APPLIED
						: PairStatusResult.REJECTED_INVALID_STAGE);
		db.when(() -> JobPairs.addJobPairAttributes(Mockito.anyInt(), Mockito.anyInt(),
						Mockito.any(Properties.class)))
				.thenAnswer(inv -> {
					Properties p = inv.getArgument(2);
					Map<String, String> copy = new TreeMap<>();
					for (String key : p.stringPropertyNames()) {
						copy.put(key, p.getProperty(key));
					}
					result.writes.add(new Write(inv.getArgument(1), copy));
					return true;
				});
		db.when(() -> JobPairs.updateRunSolverStats(Mockito.anyInt(), Mockito.anyString(),
						Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyDouble(),
						Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyLong(),
						Mockito.anyInt(), Mockito.anyLong()))
				.thenAnswer(inv -> {
					int stage = inv.getArgument(8);
					if (!stages.contains(stage)) {
						return false; // P0002, logged and reported as false by JobPairs
					}
					long disk = inv.getArgument(9);
					ledger.charged += disk - ledger.stageDisk.getOrDefault(stage, 0L);
					ledger.stageDisk.put(stage, disk);
					result.measured.add(new Measured(stage, List.of(
							inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
							inv.getArgument(4), inv.getArgument(5), inv.getArgument(6),
							inv.getArgument(7), inv.getArgument(9))));
					return true;
				});
		Constructor<JobPairs.PairStatusLookupResult> lookup =
				JobPairs.PairStatusLookupResult.class.getDeclaredConstructor(
						PairStatusLookupState.class, int.class);
		lookup.setAccessible(true);
		JobPairs.PairStatusLookupResult running =
				lookup.newInstance(PairStatusLookupState.FOUND, StatusCode.STATUS_RUNNING.getVal());
		db.when(() -> JobPairs.getPairStatusLookup(PAIR)).thenReturn(running);
		return result;
	}

	// ---------------------------------------------------------------------- ingestion

	/** Ingests the finished pair once, through this test's backend. */
	private Ingested ingest(Pair pair, Integer... stages) throws Exception {
		switch (backend) {
			case LOCAL:
				return ingestLocal(pair, true, stages);
			case CONTAINER:
				return ingestContainer(pair, stages);
			default:
				return ingestKubernetes(pair, stages);
		}
	}

	private Ingested ingestLocal(Pair pair, boolean expectTerminal, Integer... stages)
			throws Exception {
		Local local = new Local(pair);
		try {
			Ingested result = local.poll(stages);
			if (expectTerminal && result.failure == null) {
				assertTrue("the finished pair must be ingested as terminal", result.terminal);
			}
			return result;
		} finally {
			local.close();
		}
	}

	/** One registered Local monitor, polled as many times as a test needs. */
	private final class Local {
		final LocalJobMonitor monitor = new LocalJobMonitor();
		final Object state;
		final Method process;

		Local(Pair pair) throws Exception {
			// Not started, so registering schedules no poll: the polls are the test's own.
			monitor.registerJob(pair.out.toString(), PAIR);
			Field pairs = LocalJobMonitor.class.getDeclaredField("pairs");
			pairs.setAccessible(true);
			state = ((Map<?, ?>) pairs.get(monitor)).get(PAIR);
			process = LocalJobMonitor.class.getDeclaredMethod(
					"processCompletedJob", int.class, state.getClass());
			process.setAccessible(true);
		}

		Ingested poll(Integer... stages) throws Exception {
			return poll(() -> { }, stages);
		}

		/** A poll in which {@code afterStatusWrite} runs once the database accepts the status. */
		Ingested poll(Runnable afterStatusWrite, Integer... stages) throws Exception {
			try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
				Ingested result = stubDatabase(db, Set.of(stages));
				db.when(() -> JobPairs.setPairStatusPreciseResult(Mockito.anyInt(),
								Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
								Mockito.anyBoolean()))
						.thenAnswer(inv -> {
							if (!Set.of(stages).contains((Integer) inv.getArgument(1))) {
								return PairStatusResult.REJECTED_INVALID_STAGE;
							}
							afterStatusWrite.run();
							return PairStatusResult.APPLIED;
						});
				try {
					result.terminal = (boolean) process.invoke(monitor, PAIR, state);
				} catch (InvocationTargetException e) {
					result.failure = (Exception) e.getCause();
				}
				return result;
			}
		}

		void close() {
			monitor.stop();
		}
	}

	private Ingested ingestContainer(Pair pair, Integer... stages) throws Exception {
		ContainerJobMonitor monitor = new ContainerJobMonitor(null);
		PodmanBackend.CompletedContainerInfo info = new PodmanBackend.CompletedContainerInfo(
				"c-attribution", PAIR, pair.out.toString(), 0);
		Method process = ContainerJobMonitor.class.getDeclaredMethod(
				"processCompletedJob", PodmanBackend.CompletedContainerInfo.class);
		process.setAccessible(true);
		try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
			Ingested result = stubDatabase(db, Set.of(stages));
			try {
				process.invoke(monitor, info);
				result.terminal = true;
			} catch (InvocationTargetException e) {
				result.failure = (Exception) e.getCause();
			}
			return result;
		}
	}

	@SuppressWarnings("unchecked")
	private Ingested ingestKubernetes(Pair pair, Integer... stages) throws Exception {
		KubernetesNativeBackend k8s = new KubernetesNativeBackend();
		int execId = 17;
		String jobName = "job-attribution";
		for (String name : new String[]{"execIdToJobName", "execIdToPairId", "execIdToOutputDir"}) {
			Field f = KubernetesNativeBackend.class.getDeclaredField(name);
			f.setAccessible(true);
			Map<Integer, Object> map = (Map<Integer, Object>) f.get(k8s);
			map.put(execId, name.endsWith("JobName") ? jobName
					: name.endsWith("PairId") ? (Object) PAIR : pair.out);
		}
		Class<?> callbackClass = Class.forName(
				"org.starexec.backend.KubernetesNativeBackend$KubernetesJobCompletionCallback");
		Constructor<?> ctor = callbackClass.getDeclaredConstructor(KubernetesNativeBackend.class);
		ctor.setAccessible(true);
		Object callback = ctor.newInstance(k8s);
		Method onComplete = callbackClass.getDeclaredMethod("onJobComplete", ExecutionRef.class);
		onComplete.setAccessible(true);

		try (MockedStatic<JobPairs> db = Mockito.mockStatic(JobPairs.class)) {
			Ingested result = stubDatabase(db, Set.of(stages));
			try {
				result.terminal = (boolean) onComplete.invoke(
						callback, new ExecutionRef(execId, jobName, "uid-" + jobName));
			} catch (InvocationTargetException e) {
				result.failure = (Exception) e.getCause();
			}
			return result;
		}
	}

	/** What this backend does to a pair's output before a rerun, through its real method. */
	private void clearForRerun(Path out) throws Exception {
		switch (backend) {
			case LOCAL: {
				Method m = LocalBackend.class.getDeclaredMethod(
						"cleanupPreviousRunArtifacts", File.class);
				m.setAccessible(true);
				assertTrue("cleanup must succeed",
						(boolean) m.invoke(new LocalBackend(), out.toFile()));
				break;
			}
			case KUBERNETES: {
				Method m = KubernetesNativeBackend.class.getDeclaredMethod(
						"clearStaleAttemptArtifacts", Path.class, int.class);
				m.setAccessible(true);
				assertTrue("cleanup must succeed",
						(boolean) m.invoke(new KubernetesNativeBackend(), out, PAIR));
				break;
			}
			default:
				// PodmanBackend clears nothing before a rerun -- not status.json, not
				// stage-status. The helper clears this directory's files when the pair starts.
				return;
		}
		assertFalse("the per-stage attributes must be cleared before the attempt",
				Files.exists(out.resolve("stage-attributes")));
	}

	// ------------------------------------------------------------------------ producer

	/** A pair run through the shipped helper, built up one jobscript step at a time. */
	private final class Pair {
		final Path dir;
		final Path out;
		final int[] stageNumbers;
		private final StringBuilder body = new StringBuilder();
		private final Map<Integer, String> varFiles = new java.util.HashMap<>();
		private int generation;

		Pair(int... stageNumbers) throws Exception {
			this(folder.newFolder("pair-" + System.nanoTime()).toPath(), stageNumbers);
		}

		private Pair(Path dir, int... stageNumbers) throws Exception {
			this.dir = dir;
			this.out = dir.resolve("out");
			this.stageNumbers = stageNumbers;
			Files.createDirectories(out);
			Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}

		/** The next attempt, in the same output directory. */
		Pair rerun() throws Exception {
			Pair next = new Pair(dir, stageNumbers);
			next.generation = generation + 1;
			return next;
		}

		/** The helper's preamble and the pair start the jobscript performs (initSandbox). */
		void start() {
			StringBuilder b = body;
			b.append("export SCRIPT_DIR='").append(dir).append("'\n");
			b.append("export STAREXEC_OUTPUT_DIR='").append(out).append("'\n");
			b.append("export CONTAINER_MODE=true\n");
			b.append("export PAIR_ID=").append(PAIR).append('\n');
			b.append("export STAREXEC_NODE_NAME=node-7\n");
			b.append("export SHARED_DIR='").append(dir).append("/shared'\n");
			b.append("export WORKING_DIR_BASE='").append(dir).append("/work'\n");
			b.append("export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n");
			b.append("export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n");
			b.append("SOLVER_PATHS=()\n");
			b.append(". \"$SCRIPT_DIR/functions.bash\"\n");
			// jobscript:26-27 export these before sourcing; set here after, since only read.
			b.append("RUNSOLVER=RUNSOLVER\nBENCHEXEC=BENCHEXEC\n");
			b.append("STAREXEC_WALLCLOCK_LIMIT=100\nSTAREXEC_CPU_LIMIT=100\nDISK_QUOTA_EXCEEDED=0\n");
			b.append("REPORT_HOST=localhost\n");
			b.append("NUM_STAGES=").append(stageNumbers.length).append('\n');
			b.append("STAGE_NUMBERS=(");
			for (int n : stageNumbers) {
				b.append(n).append(' ');
			}
			b.append(")\n");
			// The one workspace the real job script reuses for every stage.
			b.append("WORKING_DIR='").append(dir).append("/work/attempt").append(generation)
					.append("'\n");
			b.append("OUT_DIR=\"$WORKING_DIR/output\"\n");
			b.append("LOCAL_SOLVER_DIR=\"$WORKING_DIR/solver\"\n");
			b.append("LOCAL_BENCH_PATH=\"$WORKING_DIR/benchmark/theBenchmark.p\"\n");
			b.append("VARFILE=\"$OUT_DIR/var.out\"\nWATCHFILE=\"$OUT_DIR/watcher.out\"\n");
			b.append("STDOUT_FILE=\"$OUT_DIR/stdout.txt\"\n");
			b.append("mkdir -p \"$OUT_DIR\" \"$LOCAL_SOLVER_DIR\"\n");
			// Saving solver output into the pair's output tree is not under test.
			b.append("function copyOutputNoStats { :; }\n");
			b.append("STAGE_INDEX=0\n");
			// initSandbox's call: where the helper declares the per-stage protocol.
			b.append("sendNode \"$HOSTNAME\" 1\n");
		}

		/** A stage whose solver ran, then copyOutput with this post-processor output. */
		Pair stage(int number, String postProcessorOutput) throws Exception {
			beginStage(number);
			body.append("POST_PROCESSOR_PATH='")
					.append(postProcessorOutput == null ? "" : postProcessor(number, postProcessorOutput, 0))
					.append("'\n");
			// jobscript:557. Stdout is saved (2), so it is what the stage is charged for.
			body.append("copyOutput ").append(number).append(" 2 1 \"$RUNSOLVER\"\n");
			body.append("cd \"$WORKING_DIR\"\n");
			return this;
		}

		/** A stage whose var file came back empty: jobscript:530-534. */
		void stageWithoutCopyOutput(int number) throws Exception {
			beginStage(number);
			body.append(": > \"$VARFILE\"\n");
			body.append("copyOutputNoStats ").append(number).append(" 2 1 \"$RUNSOLVER\"\n");
			body.append("markRunscriptError ").append(number).append('\n');
			body.append("exit 0\n");
		}

		/** Replaces the default var file runsolver leaves for {@code stage}. */
		void varFile(int stage, String content) {
			varFiles.put(stage, content);
		}

		/** A stage whose post-processor prints {@code partial} and then fails. */
		void failingStage(int number, String partial) throws Exception {
			beginStage(number);
			body.append("POST_PROCESSOR_PATH='").append(postProcessor(number, partial, 3)).append("'\n");
			body.append("copyOutput ").append(number).append(" 2 1 \"$RUNSOLVER\"\n");
		}

		/** The success branch at jobscript:620-626, then the cleanForNextStage at :647. */
		Pair complete(int number) {
			body.append("sendStageStatus \"$STATUS_COMPLETE\" ").append(number).append('\n');
			if (number == stageNumbers[stageNumbers.length - 1]) {
				body.append("sendStatus \"$STATUS_COMPLETE\" ").append(number).append('\n');
			} else {
				body.append("cleanForNextStage\n");
			}
			return this;
		}

		void append(String line) {
			body.append(line);
		}

		/** Leaves only what a helper predating the protocol would have written. */
		void asOlderHelper() throws Exception {
			deleteTree(out.resolve("stage-attributes"));
		}

		/** Leaves only what a helper predating per-stage measurements would have written. */
		void asOlderStatsHelper() throws Exception {
			deleteTree(out.resolve("stage-stats"));
		}

		/** Removes one field from stats.json, as a helper that never wrote it would leave it. */
		void dropStatsField(String field) throws Exception {
			Path stats = out.resolve("stats.json");
			StringBuilder kept = new StringBuilder();
			for (String line : Files.readAllLines(stats)) {
				if (!line.contains("\"" + field + "\"")) {
					kept.append(line).append('\n');
				}
			}
			Files.writeString(stats, kept.toString());
		}

		private void beginStage(int number) throws Exception {
			int index = Arrays.stream(stageNumbers).boxed().toList().indexOf(number);
			body.append("STAGE_INDEX=").append(index).append('\n');
			body.append("CURRENT_STAGE_NUMBER=").append(number).append('\n');
			body.append("mkdir -p \"$OUT_DIR/output_files\" \"$(dirname \"$LOCAL_BENCH_PATH\")\"\n");
			body.append("printf 'fof(a, conjecture, $true).\\n' > \"$LOCAL_BENCH_PATH\"\n");
			// Distinct per stage and per attempt, so a measurement filed under the wrong stage
			// cannot pass for the right one; see measured(). Stdout is v*100 bytes: the disk.
			int v = number + 10 * generation;
			body.append("printf '%0*d' ").append(v * 100).append(" 0 > \"$STDOUT_FILE\"\n");
			String var = varFiles.getOrDefault(number, "WCTIME=" + v + ".5\nCPUTIME=" + v + ".25\n"
					+ "USERTIME=" + v + "\nSYSTEMTIME=0\nMAXVM=" + (v * 1000) + "\n"
					+ "TIMEOUT=false\nMEMOUT=false\n");
			body.append("cat > \"$VARFILE\" <<'VAR'\n").append(var).append("VAR\n");
			body.append("printf 'Child status: 0\\nmaximum resident set size= ").append(v * 10)
					.append("\\n' > \"$WATCHFILE\"\n");
			body.append("POST_PROCESSOR_TIME_LIMIT=1\n");
		}

		/** A post-processor directory whose process script prints {@code output}. */
		private String postProcessor(int stage, String output, int exit) throws Exception {
			Path pp = dir.resolve("pp-" + generation + "-" + stage);
			Files.createDirectories(pp);
			Files.writeString(pp.resolve("result.txt"), output);
			Files.writeString(pp.resolve("process"),
					"#!/bin/bash\ncat result.txt\nexit " + exit + "\n");
			return pp.toString();
		}

		/**
		 * Runs everything appended so far, from the top: each call replays the whole script,
		 * which is deterministic, so a later call simply continues the same pair further.
		 */
		void run(int expectedExit) throws Exception {
			File script = dir.resolve("generated-" + generation + ".sh").toFile();
			Files.writeString(script.toPath(), body.toString());
			Result parse = exec(Bash.PATH, "-n", script.getAbsolutePath());
			assertEquals("generated script must parse:\n" + parse.out, 0, parse.exit);
			resetOutput();
			Result result = exec(Bash.PATH, script.getAbsolutePath());
			assertEquals("the helper exit status:\n" + result.out, expectedExit, result.exit);
		}

		/** A replay starts from the output this attempt found, not from its own earlier run. */
		private void resetOutput() throws Exception {
			if (generation == 0) {
				deleteTree(out);
				Files.createDirectories(out);
			}
			deleteTree(dir.resolve("work").resolve("attempt" + generation));
		}
	}

	private static void deleteTree(Path root) throws Exception {
		if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var walk = Files.walk(root)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	private static Result exec(String... command) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(command);
		// updateStats formats runsolver's decimals with printf "%.0f", which rejects "1.5" under
		// a comma-decimal LC_NUMERIC -- reported separately, and not this test's subject. Job
		// containers run in the C locale.
		pb.environment().put("LC_ALL", "C");
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue("command must not hang", p.waitFor(60, TimeUnit.SECONDS));
		return new Result(p.exitValue(), output);
	}

	private static final class Result {
		final int exit;
		final String out;

		Result(int exit, String out) {
			this.exit = exit;
			this.out = out;
		}
	}
}
