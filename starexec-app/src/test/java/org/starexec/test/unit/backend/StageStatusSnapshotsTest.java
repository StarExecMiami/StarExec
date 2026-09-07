package org.starexec.test.unit.backend;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.backend.StageStatusSnapshots;
import org.starexec.data.to.Status.StatusCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The backend-independent half of the stage-status protocol, on its own terms.
 *
 * <p>Deliberately not written by reading the container monitor's copy of these rules. The
 * protocol's authority is the producer -- {@code functions.bash} -- and the database's own
 * terminal-status predicate; a test derived from a second consumer would only prove the two
 * consumers agree with each other, which is the drift that produced the status-laundering
 * defect this class exists to prevent.
 */
public class StageStatusSnapshotsTest {

	private static final int PAIR = 4242;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// ------------------------------------------------------------------ the happy path

	@Test
	public void everyStageIsReturnedInStageOrder() throws Exception {
		Path out = folder.newFolder("ok").toPath();
		write(out, 2, PAIR, 2, StatusCode.EXCEED_CPU.getVal());
		write(out, 1, PAIR, 1, StatusCode.STATUS_COMPLETE.getVal());

		Map<Integer, Integer> read = StageStatusSnapshots.read(out, PAIR);

		assertEquals(2, read.size());
		assertEquals("[1, 2]", read.keySet().toString());
		assertEquals(Integer.valueOf(StatusCode.STATUS_COMPLETE.getVal()), read.get(1));
		assertEquals(Integer.valueOf(StatusCode.EXCEED_CPU.getVal()), read.get(2));
	}

	/** Output from a job script that predates the protocol reads as "nothing to add". */
	@Test
	public void anAbsentDirectoryIsEmptyRatherThanAnError() throws Exception {
		assertTrue(StageStatusSnapshots.read(folder.newFolder("legacy").toPath(), PAIR).isEmpty());
	}

	@Test
	public void anEmptyDirectoryIsEmpty() throws Exception {
		Path out = folder.newFolder("empty").toPath();
		Files.createDirectories(out.resolve("stage-status"));
		assertTrue(StageStatusSnapshots.read(out, PAIR).isEmpty());
	}

	@Test
	public void aNullOutputDirectoryIsEmpty() throws Exception {
		assertTrue(StageStatusSnapshots.read(null, PAIR).isEmpty());
	}

	/** The producer's own temporary file must not be mistaken for a record. */
	@Test
	public void namesThatAreNotSnapshotsAreIgnored() throws Exception {
		Path out = folder.newFolder("names").toPath();
		write(out, 1, PAIR, 1, StatusCode.STATUS_COMPLETE.getVal());
		Path dir = out.resolve("stage-status");
		for (String junk : new String[]{"1.json.tmp", "0.json", "01.json", "-1.json",
				"abc.json", "1000000000.json", "99999999999999999999.json", "notes.txt"}) {
			Files.writeString(dir.resolve(junk), "{}\n");
		}

		Map<Integer, Integer> read = StageStatusSnapshots.read(out, PAIR);

		assertEquals("only the one real snapshot", 1, read.size());
		assertTrue(read.containsKey(1));
	}

	/** The widest name the producer can emit must still be accepted. */
	@Test
	public void theLargestNameTheProducerCanWriteIsAccepted() throws Exception {
		Path out = folder.newFolder("wide").toPath();
		write(out, 999999999, PAIR, 999999999, StatusCode.STATUS_COMPLETE.getVal());
		assertTrue(StageStatusSnapshots.read(out, PAIR).containsKey(999999999));
	}

	// ------------------------------------------------------------------ refusals

	@Test
	public void aRecordNamingAnotherPairIsRefused() throws Exception {
		Path out = folder.newFolder("foreign").toPath();
		write(out, 1, PAIR + 1, 1, StatusCode.STATUS_COMPLETE.getVal());
		assertRefused(out, "claims pair");
	}

	@Test
	public void aFileNameDisagreeingWithItsRecordIsRefused() throws Exception {
		Path out = folder.newFolder("mismatch").toPath();
		write(out, 1, PAIR, 2, StatusCode.STATUS_COMPLETE.getVal());
		assertRefused(out, "is named for stage");
	}

	@Test
	public void malformedJsonIsRefused() throws Exception {
		Path out = folder.newFolder("malformed").toPath();
		Files.createDirectories(out.resolve("stage-status"));
		Files.writeString(out.resolve("stage-status/1.json"), "{not json");
		assertRefused(out, "malformed stage snapshot");
	}

	@Test
	public void aMissingFieldIsRefused() throws Exception {
		Path out = folder.newFolder("missing").toPath();
		Files.createDirectories(out.resolve("stage-status"));
		Files.writeString(out.resolve("stage-status/1.json"),
				"{\"pairId\":" + PAIR + ",\"stageNumber\":1}\n");
		assertRefused(out, "has no status");
	}

	@Test
	public void aNonIntegerFieldIsRefused() throws Exception {
		Path out = folder.newFolder("nonint").toPath();
		Files.createDirectories(out.resolve("stage-status"));
		Files.writeString(out.resolve("stage-status/1.json"),
				"{\"pairId\":" + PAIR + ",\"stageNumber\":1,\"status\":{}}\n");
		assertRefused(out, "non-integer status");
	}

	@Test
	public void anOversizedRecordIsRefusedWithoutBeingRead() throws Exception {
		Path out = folder.newFolder("huge").toPath();
		Files.createDirectories(out.resolve("stage-status"));
		Files.writeString(out.resolve("stage-status/1.json"), "x".repeat(9 * 1024));
		assertRefused(out, "bytes");
	}

	// --------------------------------------------------- the terminal-status contract

	/**
	 * Every status the model has, judged against the one authority.
	 *
	 * <p>Enumerated rather than probing the known-bad three, so a status added later cannot
	 * quietly become ingestible.
	 */
	@Test
	public void exactlyTheTerminalExecutionResultsAreAccepted() throws Exception {
		for (StatusCode code : StatusCode.values()) {
			Path out = folder.newFolder("st-" + code.name()).toPath();
			write(out, 1, PAIR, 1, code.getVal());

			if (code.isTerminalExecutionResult()) {
				Map<Integer, Integer> read = StageStatusSnapshots.read(out, PAIR);
				assertEquals(code + " is a result and must be accepted",
						Integer.valueOf(code.getVal()), read.get(1));
			} else {
				assertRefused(out, "not a terminal execution result");
			}
		}
	}

	/** Named individually, so a regression says which state leaked rather than only that one did. */
	@Test
	public void theThreeStatesThatMeanWorkIsStillOwedAreRefused() throws Exception {
		for (StatusCode code : new StatusCode[]{StatusCode.STATUS_PROCESSING_RESULTS,
				StatusCode.STATUS_PAUSED, StatusCode.STATUS_PROCESSING}) {
			Path out = folder.newFolder("owed-" + code.getVal()).toPath();
			write(out, 1, PAIR, 1, code.getVal());
			assertRefused(out, "not a terminal execution result");
		}
	}

	/**
	 * 22 is the one with teeth: a stage parked at STATUS_PROCESSING is selected by the periodic
	 * post-processing task, which then sets the whole pair to STATUS_COMPLETE.
	 */
	@Test
	public void statusProcessingCannotBeIngested() throws Exception {
		assertEquals(22, StatusCode.STATUS_PROCESSING.getVal());
		Path out = folder.newFolder("launder").toPath();
		write(out, 1, PAIR, 1, 22);
		assertRefused(out, "carries status 22");
	}

	/** One bad record poisons the read; a partially believed history is worse than none. */
	@Test
	public void oneBadRecordRefusesTheWholeRead() throws Exception {
		Path out = folder.newFolder("partial").toPath();
		write(out, 1, PAIR, 1, StatusCode.STATUS_COMPLETE.getVal());
		write(out, 2, PAIR + 99, 2, StatusCode.STATUS_COMPLETE.getVal());
		assertRefused(out, "claims pair");
	}

	// ------------------------------------------------------------------------- harness

	private static void write(Path outputDir, int fileStage, int pairId, int recordStage, int status)
			throws Exception {
		Path dir = outputDir.resolve("stage-status");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(fileStage + ".json"),
				"{\"pairId\":" + pairId + ",\"status\":" + status
						+ ",\"stageNumber\":" + recordStage + ",\"timestamp\":1788818872}\n");
	}

	private static void assertRefused(Path outputDir, String expectedReason) throws Exception {
		try {
			StageStatusSnapshots.read(outputDir, PAIR);
			fail("expected refusal mentioning: " + expectedReason);
		} catch (StageStatusSnapshots.InvalidSnapshotException expected) {
			assertTrue("wrong refusal: " + expected.getMessage(),
					expected.getMessage().contains(expectedReason));
		}
	}
}
