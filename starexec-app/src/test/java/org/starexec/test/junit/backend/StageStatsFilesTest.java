package org.starexec.test.junit.backend;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.backend.LocalBackend;
import org.starexec.backend.StageStatsFiles;
import org.starexec.backend.exception.RetryableIngestionException;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The rules {@link StageStatsFiles} applies to what a job left behind (#180), and the rerun
 * cleanup that keeps a previous attempt's measurements from being read as this one's.
 *
 * <p>The end-to-end behaviour through the real helper and monitors is in
 * {@code StageResultAttributionTest}; these pin the cases a well-behaved helper never produces.
 */
public class StageStatsFilesTest {

	private static final int PAIR = 77;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Path output() throws Exception {
		return folder.newFolder().toPath();
	}

	private static Path declare(Path out) throws Exception {
		return Files.createDirectories(out.resolve("stage-stats"));
	}

	/** What containerWriteStats writes, with each value overridable. */
	private static String stats(int pair, Integer stage, String wall, String maxvm) {
		return "{\n"
				+ (pair < 0 ? "" : "  \"pairId\": " + pair + ",\n")
				+ (stage == null ? "" : "  \"stageNumber\": " + stage + ",\n")
				+ "  \"wallclockTime\": " + wall + ",\n"
				+ "  \"cpuTime\": 1.25,\n"
				+ "  \"userTime\": 1,\n"
				+ "  \"systemTime\": 0,\n"
				+ "  \"maxVirtualMemory\": " + maxvm + ",\n"
				+ "  \"maxResidentSetSize\": 10,\n"
				+ "  \"diskSize\": 100,\n"
				+ "  \"hostname\": \"node-7\"\n"
				+ "}\n";
	}

	private static String stats(int stage) {
		return stats(PAIR, stage, "1.5", "1000");
	}

	private static Map<Integer, StageStatsFiles.Stats> select(
			Path out, Set<Integer> earlier, int terminal, Set<Integer> stages) throws Exception {
		return StageStatsFiles.select(out, PAIR, earlier, terminal, () -> stages);
	}

	// ---------------------------------------------------------------------- per-stage files

	@Test
	public void aValidFileIsReadExactly() throws Exception {
		Path out = output();
		Files.writeString(declare(out).resolve("1.json"), stats(1));

		StageStatsFiles.Stats s = select(out, Set.of(), 1, Set.of(1)).get(1);
		assertEquals(1.5, s.wallclockTime, 0.0);
		assertEquals(1.25, s.cpuTime, 0.0);
		assertEquals(1.0, s.userTime, 0.0);
		assertEquals(0.0, s.systemTime, 0.0);
		assertEquals(1000.0, s.maxVirtualMemory, 0.0);
		assertEquals(10L, s.maxResidentSetSize);
		assertEquals(100L, s.diskSize);
		assertEquals("node-7", s.hostname);
	}

	/** Zero is a measurement (C2): runsolver reports MAXVM=0 for a run under 0.1 s. */
	@Test
	public void zeroValuesAreMeasurements() throws Exception {
		Path out = output();
		Files.writeString(declare(out).resolve("1.json"), stats(PAIR, 1, "0", "0"));

		StageStatsFiles.Stats s = select(out, Set.of(), 1, Set.of(1)).get(1);
		assertEquals(0.0, s.wallclockTime, 0.0);
		assertEquals(0.0, s.maxVirtualMemory, 0.0);
	}

	@Test
	public void aStageThePairDoesNotHaveIsNeverSelected() throws Exception {
		Path out = output();
		Path dir = declare(out);
		Files.writeString(dir.resolve("2.json"), stats(2));
		Files.writeString(dir.resolve("3.json"), stats(3));

		assertEquals(Set.of(3), select(out, Set.of(2), 3, Set.of(1, 3)).keySet());
	}

	@Test
	public void unreadableStagesAreRetriedRatherThanGuessed() throws Exception {
		Path out = output();
		Files.writeString(declare(out).resolve("1.json"), stats(1));
		try {
			select(out, Set.of(), 1, null);
			fail("an unreadable stage list must not be treated as any particular one");
		} catch (RetryableIngestionException expected) {
			// as intended
		}
	}

	/** A file published for one stage that says it is another's is not believed. */
	@Test
	public void aFileNamingAnotherStageIsRefused() throws Exception {
		Path out = output();
		Files.writeString(declare(out).resolve("2.json"), stats(1));
		assertTrue(select(out, Set.of(), 2, Set.of(1, 2)).isEmpty());
	}

	@Test
	public void aFileNamingAnotherPairIsRefused() throws Exception {
		Path out = output();
		Files.writeString(declare(out).resolve("1.json"), stats(PAIR + 1, 1, "1.5", "1000"));
		assertTrue(select(out, Set.of(), 1, Set.of(1)).isEmpty());
	}

	/** Present and numeric, every one: a missing, quoted, negative or unparsable value refuses. */
	@Test
	public void aFileWithoutEveryMeasurementAsANonNegativeNumberIsRefused() throws Exception {
		for (String[] bad : new String[][] {
				{"\"1.5\"", "1000"}, {"-1", "1000"}, {"1.5", "null"}, {"1.5", "true"},
				{"1e999", "1000"}, {"1.5", "\"\""}}) {
			Path out = output();
			Files.writeString(declare(out).resolve("1.json"), stats(PAIR, 1, bad[0], bad[1]));
			assertTrue("wall=" + bad[0] + " maxvm=" + bad[1],
					select(out, Set.of(), 1, Set.of(1)).isEmpty());
		}
		Path out = output();
		Files.writeString(declare(out).resolve("1.json"),
				stats(1).replace("  \"diskSize\": 100,\n", ""));
		assertTrue("no diskSize", select(out, Set.of(), 1, Set.of(1)).isEmpty());
	}

	@Test
	public void aFileThatIsNotAJsonObjectIsRefused() throws Exception {
		for (String bad : new String[] {"", "{ not json", "[1, 2]", "42"}) {
			Path out = output();
			Files.writeString(declare(out).resolve("1.json"), bad);
			assertTrue("'" + bad + "'", select(out, Set.of(), 1, Set.of(1)).isEmpty());
		}
	}

	@Test
	public void aLinkedStageFileIsNotRead() throws Exception {
		Path out = output();
		Path outside = folder.newFile("outside.json").toPath();
		Files.writeString(outside, stats(1));
		Files.createSymbolicLink(declare(out).resolve("1.json"), outside);
		assertTrue(select(out, Set.of(), 1, Set.of(1)).isEmpty());
	}

	@Test
	public void anOversizedStageFileIsNotRead() throws Exception {
		Path out = output();
		Files.write(declare(out).resolve("1.json"), new byte[1024 * 1024 + 1]);
		assertTrue(select(out, Set.of(), 1, Set.of(1)).isEmpty());
	}

	/** The marker alone decides: the legacy stats.json beside it is never used. */
	@Test
	public void theLegacyFileIsNeverReadUnderTheProtocol() throws Exception {
		Path out = output();
		declare(out);
		Files.writeString(out.resolve("stats.json"), stats(1));
		assertTrue(select(out, Set.of(), 1, Set.of(1)).isEmpty());
	}

	@Test
	public void aRunningPairWithNothingFinishedSelectsNothing() throws Exception {
		Path out = output();
		Files.writeString(declare(out).resolve("1.json"), stats(1));
		assertTrue(StageStatsFiles.select(out, PAIR, Set.of(), 0, () -> {
			throw new AssertionError("no stage lookup is needed");
		}).isEmpty());
	}

	@Test
	public void anEmptyHostnameIsNoHostname() throws Exception {
		Path out = output();
		Files.writeString(declare(out).resolve("1.json"),
				stats(1).replace("\"node-7\"", "\"  \""));
		assertNull(select(out, Set.of(), 1, Set.of(1)).get(1).hostname);
	}

	// ------------------------------------------------------------------------------- legacy

	@Test
	public void legacyStatsGoToTheFinishedStageTheyName() throws Exception {
		Path out = output();
		Files.writeString(out.resolve("stats.json"), stats(1));
		assertEquals(Set.of(1), select(out, Set.of(1), 2, Set.of(1, 2)).keySet());
	}

	/** C1: a missing stageNumber is not a parser default of 1. */
	@Test
	public void legacyStatsWithoutAStageNumberRecordNothing() throws Exception {
		Path out = output();
		Files.writeString(out.resolve("stats.json"), stats(PAIR, null, "1.5", "1000"));
		assertTrue(select(out, Set.of(1), 2, Set.of(1, 2)).isEmpty());
	}

	@Test
	public void legacyStatsWithoutAPairIdRecordNothing() throws Exception {
		Path out = output();
		Files.writeString(out.resolve("stats.json"), stats(-1, 1, "1.5", "1000"));
		assertTrue(select(out, Set.of(1), 2, Set.of(1, 2)).isEmpty());
	}

	@Test
	public void legacyStatsForAnotherPairRecordNothing() throws Exception {
		Path out = output();
		Files.writeString(out.resolve("stats.json"), stats(PAIR + 1, 1, "1.5", "1000"));
		assertTrue(select(out, Set.of(1), 2, Set.of(1, 2)).isEmpty());
	}

	/** A Local poll while the stage the file names is still running: not yet, and no lookup. */
	@Test
	public void legacyStatsForAStageNotYetFinishedWait() throws Exception {
		Path out = output();
		Files.writeString(out.resolve("stats.json"), stats(2));
		assertTrue(StageStatsFiles.select(out, PAIR, Set.of(1), 0, () -> {
			throw new AssertionError("no stage lookup is needed");
		}).isEmpty());
	}

	@Test
	public void legacyStatsForAStageThePairDoesNotHaveRecordNothing() throws Exception {
		Path out = output();
		Files.writeString(out.resolve("stats.json"), stats(2));
		assertTrue(select(out, Set.of(), 2, Set.of(1, 3)).isEmpty());
	}

	@Test
	public void legacyStatsThroughALinkAreNotRead() throws Exception {
		Path out = output();
		Path outside = folder.newFile("outside-stats.json").toPath();
		Files.writeString(outside, stats(1));
		Files.createSymbolicLink(out.resolve("stats.json"), outside);
		assertTrue(select(out, Set.of(), 1, Set.of(1)).isEmpty());
	}

	// ------------------------------------------------------------------------- rerun cleanup

	@Test
	public void localRefusesToStartWhenStageStatsSurvive() throws Exception {
		Path out = output();
		Path dir = declare(out);
		Files.writeString(dir.resolve("2.json"), stats(2));
		Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			Method cleanup = LocalBackend.class.getDeclaredMethod(
					"cleanupPreviousRunArtifacts", File.class);
			cleanup.setAccessible(true);
			assertFalse("the attempt must not start over a surviving stage-stats directory",
					(boolean) cleanup.invoke(new LocalBackend(), out.toFile()));
		} finally {
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	public void kubernetesRefusesToSubmitWhenStageStatsSurvive() throws Exception {
		Path out = output();
		Path dir = declare(out);
		Files.writeString(dir.resolve("2.json"), stats(2));
		Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			Method cleanup = KubernetesNativeBackend.class.getDeclaredMethod(
					"clearStaleAttemptArtifacts", Path.class, int.class);
			cleanup.setAccessible(true);
			assertFalse("the attempt must not be submitted over a surviving stage-stats directory",
					(boolean) cleanup.invoke(new KubernetesNativeBackend(), out, PAIR));
		} finally {
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	public void bothBackendsClearARemovableStageStatsDirectory() throws Exception {
		Path local = output();
		Files.writeString(declare(local).resolve("2.json"), stats(2));
		Method localCleanup = LocalBackend.class.getDeclaredMethod(
				"cleanupPreviousRunArtifacts", File.class);
		localCleanup.setAccessible(true);
		assertTrue((boolean) localCleanup.invoke(new LocalBackend(), local.toFile()));
		assertFalse(Files.exists(local.resolve("stage-stats")));

		Path k8s = output();
		Files.writeString(declare(k8s).resolve("2.json"), stats(2));
		Method k8sCleanup = KubernetesNativeBackend.class.getDeclaredMethod(
				"clearStaleAttemptArtifacts", Path.class, int.class);
		k8sCleanup.setAccessible(true);
		assertTrue((boolean) k8sCleanup.invoke(new KubernetesNativeBackend(), k8s, PAIR));
		assertFalse(Files.exists(k8s.resolve("stage-stats")));
	}
}
