package org.starexec.test.junit.backend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.backend.LocalJobMonitor;
import org.starexec.backend.StageStatusSnapshots;
import org.starexec.data.to.Status.StatusCode;

import java.lang.reflect.Method;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A pair that dies while a stage is still running.
 *
 * <p>Such a pair reports on the pair-level channel -- {@code status.json} carries stage 0, because
 * the failure belongs to no stage -- and leaves that stage's snapshot reading
 * {@code STATUS_RUNNING} for good, since nothing will ever finish it. The monitors read those
 * snapshots with no bound, because a stage that DID finish must keep its own result (#165), and
 * the unfinished one was then judged by terminality and refused. An {@code InvalidSnapshotException}
 * is classified as a deterministic artifact defect, so the pair was held for intervention and never
 * looked at again: a pair that ran, failed, and reported honestly was never resolved.
 *
 * <p>It is not a corner case. {@code jobscript:217} reports a missing benchmark dependency at the
 * pair level, and it runs after {@code initSandbox} (jobscript:201) has already published stage 1
 * as RUNNING through {@code sendNode}, so every such pair took this path.
 *
 * <p>The rule now is that an unfinished stage is skipped rather than refused. It is still not
 * returned, so nothing non-terminal can be ingested; every other check on the bytes still applies.
 */
public class PairLevelSnapshotIngestionTest {

	private static final int PAIR = 4242;
	private static final int PAIR_LEVEL = 0;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// ----------------------------------------------------------------------- the reader

	/** The defect itself, at the level that decides it. */
	@Test
	public void aStageThatNeverFinishedIsSkippedRatherThanRefused() throws Exception {
		Path out = folder.newFolder("mid-stage").toPath();
		write(out, 1, PAIR, 1, StatusCode.STATUS_RUNNING.getVal());

		Map<Integer, Integer> read = StageStatusSnapshots.readForPairLevelResult(out, PAIR);

		assertTrue("an unfinished stage must not be returned", read.isEmpty());
	}

	/** And the stages that did finish are still returned, which is what the no-bound read is for. */
	@Test
	public void theStagesThatFinishedAreStillReturned() throws Exception {
		Path out = folder.newFolder("one-of-each").toPath();
		write(out, 1, PAIR, 1, StatusCode.STATUS_COMPLETE.getVal());
		write(out, 2, PAIR, 2, StatusCode.STATUS_RUNNING.getVal());

		Map<Integer, Integer> read = StageStatusSnapshots.readForPairLevelResult(out, PAIR);

		assertEquals("only the finished stage", "[1]", read.keySet().toString());
		assertEquals(Integer.valueOf(StatusCode.STATUS_COMPLETE.getVal()), read.get(1));
	}

	/**
	 * The one shape the producer cannot legitimately have written: an unfinished stage BELOW one
	 * that finished. Stage 2 runs only because stage 1 completed, so such a pair's records
	 * contradict each other. Skipping is still the right handling -- refusing the pair is the
	 * defect this change removes, and nothing non-terminal is ingested either way -- but the
	 * finished stage must still be returned, and the contradiction is logged rather than lost.
	 */
	@Test
	public void anUnfinishedStageBeneathAFinishedOneIsSkippedAndTheFinishedOneKept() throws Exception {
		Path out = folder.newFolder("inconsistent").toPath();
		write(out, 1, PAIR, 1, StatusCode.STATUS_RUNNING.getVal());
		write(out, 2, PAIR, 2, StatusCode.STATUS_COMPLETE.getVal());

		Map<Integer, Integer> read = StageStatusSnapshots.readForPairLevelResult(out, PAIR);

		assertEquals("the finished stage is still ingested", "[2]", read.keySet().toString());
		assertEquals(Integer.valueOf(StatusCode.STATUS_COMPLETE.getVal()), read.get(2));
	}

	/**
	 * Skipping is not returning-unchecked. STATUS_PROCESSING(22) means work is still owed, and a
	 * pair left at 22 is completed by the post-processing task, so it must never reach a caller.
	 */
	@Test
	public void anUnfinishedStageCannotLaunderItsOwnStatus() throws Exception {
		Path out = folder.newFolder("launder").toPath();
		write(out, 1, PAIR, 1, StatusCode.STATUS_PROCESSING.getVal());

		Map<Integer, Integer> read = StageStatusSnapshots.readForPairLevelResult(out, PAIR);

		assertTrue("STATUS_PROCESSING must never reach a caller", read.isEmpty());
	}

	/**
	 * Only terminality becomes non-fatal. A record naming another pair is cross-pair contamination
	 * whether or not its stage finished, and that does not depend on when the file is read.
	 */
	@Test
	public void aRecordNamingAnotherPairIsStillRefused() throws Exception {
		Path out = folder.newFolder("wrong-pair").toPath();
		write(out, 1, PAIR + 99, 1, StatusCode.STATUS_RUNNING.getVal());

		try {
			StageStatusSnapshots.readForPairLevelResult(out, PAIR);
			fail("a snapshot naming another pair must still be refused");
		} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("claims pair"));
		}
	}

	/** Same for a name that disagrees with the record it holds. */
	@Test
	public void aNameDisagreeingWithItsRecordIsStillRefused() throws Exception {
		Path out = folder.newFolder("wrong-name").toPath();
		write(out, 1, PAIR, 2, StatusCode.STATUS_RUNNING.getVal());

		try {
			StageStatusSnapshots.readForPairLevelResult(out, PAIR);
			fail("a name disagreeing with its record must still be refused");
		} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("is named for stage"));
		}
	}

	/** The control: under a result that NAMES a stage, an unfinished earlier stage is still refused. */
	@Test
	public void aStageLevelResultStillRefusesAnUnfinishedEarlierStage() throws Exception {
		Path out = folder.newFolder("stage-level").toPath();
		write(out, 1, PAIR, 1, StatusCode.STATUS_RUNNING.getVal());

		try {
			StageStatusSnapshots.read(out, PAIR, 2);
			fail("an unfinished stage before the reported one is still a defect");
		} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("not a terminal execution result"));
		}
	}

	// ------------------------------------------------------------------ the warning itself

	/**
	 * The visibility is the deliverable, so it is asserted rather than assumed.
	 *
	 * <p>Without this, the whole detection could be deleted and every other case here would stay
	 * green: they pin what is INGESTED, and the ingestion is identical whether or not anyone is
	 * told that the pair's own records contradict each other.
	 */
	@Test
	public void theContradictionIsReported() throws Exception {
		Path out = folder.newFolder("warns").toPath();
		write(out, 1, PAIR, 1, StatusCode.STATUS_RUNNING.getVal());
		write(out, 2, PAIR, 2, StatusCode.STATUS_COMPLETE.getVal());

		List<ILoggingEvent> logged = captureWhileReading(out);

		assertEquals("exactly one warning, naming the pair and both stages: " + logged, 1, logged.size());
		String message = logged.get(0).getFormattedMessage();
		assertTrue(message, message.contains("Pair " + PAIR));
		assertTrue("it must name the unfinished stage: " + message, message.contains("stage 1"));
		assertTrue("and its status: " + message, message.contains("status 4"));
	}

	/**
	 * And the restraint, which is equally untested otherwise: the ORDINARY case under this mode is
	 * a pair dying while its last stage ran. Warning on that would fire on every such pair until
	 * nobody reads warnings at all, which would cost the signal this exists to add.
	 */
	@Test
	public void theOrdinaryMidStageDeathIsNotReported() throws Exception {
		Path out = folder.newFolder("quiet").toPath();
		write(out, 1, PAIR, 1, StatusCode.STATUS_COMPLETE.getVal());
		write(out, 2, PAIR, 2, StatusCode.STATUS_RUNNING.getVal());

		List<ILoggingEvent> logged = captureWhileReading(out);

		assertEquals("a pair dying in its last stage is ordinary and must not warn: " + logged,
				0, logged.size());
	}

	/** Reads for a pair-level result with an appender attached, and returns the warnings. */
	private static List<ILoggingEvent> captureWhileReading(Path out) throws Exception {
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
				org.slf4j.LoggerFactory.getLogger(StageStatusSnapshots.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			StageStatusSnapshots.readForPairLevelResult(out, PAIR);
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
	}

	// ---------------------------------------------------------------- through the monitor

	/**
	 * The Local backend's own path, which is where this was observed: pair 76 was held with
	 * "INGESTION REQUIRES INTERVENTION ... carries status 4" after its script correctly reported
	 * a missing benchmark dependency as {@code {"status":24,"stageNumber":0}}. Pairs 27 and 107
	 * on the same stack wore the same error message but had a different cause -- a wallclock
	 * timeout reported at STAGE level -- and belong to the producer fix, not to this one.
	 *
	 * <p>The state is passed as null deliberately: an untracked pair short-circuits the generation
	 * check before it is dereferenced, so the read is what this exercises, and a refusal would
	 * still come out as the exception it used to throw.
	 */
	@Test
	public void theLocalMonitorNoLongerRefusesAPairThatDiedMidStage() throws Throwable {
		Path out = folder.newFolder("local-mid-stage").toPath();
		write(out, 1, PAIR, 1, StatusCode.STATUS_RUNNING.getVal());

		ingestEarlierStageStatuses(out, PAIR_LEVEL);
	}

	/** And the control through the same path: a stage-level result still refuses it. */
	@Test
	public void theLocalMonitorStillRefusesAnUnfinishedStageUnderAStageLevelResult()
			throws Throwable {
		Path out = folder.newFolder("local-stage-level").toPath();
		write(out, 1, PAIR, 1, StatusCode.STATUS_RUNNING.getVal());

		try {
			ingestEarlierStageStatuses(out, 2);
			fail("an unfinished stage before the reported one is still a defect");
		} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("not a terminal execution result"));
		}
	}

	// ----------------------------------------------------------------------------- harness

	private static void ingestEarlierStageStatuses(Path outputDir, int reportedStage)
			throws Throwable {
		LocalJobMonitor monitor = new LocalJobMonitor();
		try {
			Class<?> state = Class.forName(
					"org.starexec.backend.LocalJobMonitor$PairExecutionState");
			Method m = LocalJobMonitor.class.getDeclaredMethod(
					"ingestEarlierStageStatuses", int.class, state, Path.class, int.class);
			m.setAccessible(true);
			try {
				m.invoke(monitor, PAIR, null, outputDir, reportedStage);
			} catch (java.lang.reflect.InvocationTargetException e) {
				throw e.getCause();
			}
		} finally {
			monitor.stop();
		}
	}

	private static void write(Path outputDir, int name, int pairId, int stage, int status)
			throws Exception {
		Path dir = Files.createDirectories(outputDir.resolve("stage-status"));
		Files.writeString(dir.resolve(name + ".json"),
				"{\"pairId\":" + pairId + ",\"status\":" + status + ",\"stageNumber\":" + stage
						+ ",\"timestamp\":1788818872}\n");
	}
}
