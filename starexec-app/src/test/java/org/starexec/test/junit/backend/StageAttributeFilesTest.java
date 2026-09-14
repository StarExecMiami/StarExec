package org.starexec.test.junit.backend;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.backend.KubernetesNativeBackend;
import org.starexec.backend.LocalBackend;
import org.starexec.backend.StageAttributeFiles;
import org.starexec.backend.exception.RetryableIngestionException;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The rules {@link StageAttributeFiles} applies to what a job left behind (#179), and the rerun
 * cleanup that keeps a previous attempt's files from being read as this one's.
 *
 * <p>The end-to-end behaviour through the real helper and monitors is
 * {@code StageResultAttributionTest}; these pin the cases a well-behaved helper never produces.
 */
public class StageAttributeFilesTest {

	private static final int PAIR = 77;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Path output() throws Exception {
		return folder.newFolder().toPath();
	}

	private static Path declare(Path out) throws Exception {
		return Files.createDirectories(out.resolve("stage-attributes"));
	}

	private static Properties props(String key, String value) {
		Properties p = new Properties();
		p.setProperty(key, value);
		return p;
	}

	// ------------------------------------------------------------------ membership

	/**
	 * A file for a stage the pair does not have is refused, even when a snapshot claims it
	 * finished. Stages 1 and 3 are not 1, 2 and 3.
	 */
	@Test
	public void aStageThePairDoesNotHaveIsNeverSelected() throws Exception {
		Path out = output();
		Path dir = declare(out);
		Files.writeString(dir.resolve("2.txt"), "starexec-result=Forged\n");
		Files.writeString(dir.resolve("3.txt"), "starexec-result=Real\n");

		Map<Integer, Properties> selected = StageAttributeFiles.select(
				out, PAIR, Set.of(2), 3, new Properties(), () -> Set.of(1, 3));

		assertEquals(Set.of(3), selected.keySet());
		assertEquals("Real", selected.get(3).getProperty("starexec-result"));
	}

	/** Not knowing the pair's stages is not a reason to guess them. */
	@Test
	public void unreadableStagesAreRetriedRatherThanGuessed() throws Exception {
		Path out = output();
		Files.writeString(declare(out).resolve("1.txt"), "starexec-result=Theorem\n");
		try {
			StageAttributeFiles.select(out, PAIR, Set.of(), 1, new Properties(), () -> null);
			fail("an unreadable stage list must not be treated as any particular one");
		} catch (RetryableIngestionException expected) {
			// as intended
		}
	}

	/** Nothing finished, nothing to decide: the database is not even asked. */
	@Test
	public void aRunningPairWithNothingFinishedSelectsNothing() throws Exception {
		Path out = output();
		Files.writeString(declare(out).resolve("1.txt"), "starexec-result=Theorem\n");
		Map<Integer, Properties> selected = StageAttributeFiles.select(
				out, PAIR, Set.of(), 0, props("starexec-result", "Legacy"), () -> {
					throw new AssertionError("no stage lookup is needed");
				});
		assertTrue(selected.isEmpty());
	}

	// ------------------------------------------------------------------ file checks

	/** A link where a stage's file belongs is not that stage's file. */
	@Test
	public void aLinkedStageFileIsNotRead() throws Exception {
		Path out = output();
		Path outside = folder.newFile("outside.txt").toPath();
		Files.writeString(outside, "starexec-result=Outside\n");
		Files.createSymbolicLink(declare(out).resolve("1.txt"), outside);

		Map<Integer, Properties> selected = StageAttributeFiles.select(
				out, PAIR, Set.of(), 1, new Properties(), () -> Set.of(1));
		assertTrue("followed a link: " + selected, selected.isEmpty());
	}

	/** Replacing the marker with a link does not steer the pair back to the legacy file. */
	@Test
	public void aLinkedMarkerStillDeclaresTheProtocol() throws Exception {
		Path out = output();
		Files.createSymbolicLink(out.resolve("stage-attributes"), folder.newFolder().toPath());
		assertTrue(StageAttributeFiles.declared(out));

		Map<Integer, Properties> selected = StageAttributeFiles.select(
				out, PAIR, Set.of(), 1, props("starexec-result", "Legacy"), () -> Set.of(1));
		assertTrue("the legacy file was used: " + selected, selected.isEmpty());
	}

	/** A file far larger than any post-processor output is refused instead of read into memory. */
	@Test
	public void anOversizedStageFileIsNotRead() throws Exception {
		Path out = output();
		Files.write(declare(out).resolve("1.txt"), new byte[1024 * 1024 + 1]);

		Map<Integer, Properties> selected = StageAttributeFiles.select(
				out, PAIR, Set.of(), 1, new Properties(), () -> Set.of(1));
		assertTrue(selected.isEmpty());
	}

	/** An empty file is a stage with no attributes, not an error. */
	@Test
	public void anEmptyStageFileSelectsAnEmptySet() throws Exception {
		Path out = output();
		Files.writeString(declare(out).resolve("1.txt"), "");

		Map<Integer, Properties> selected = StageAttributeFiles.select(
				out, PAIR, Set.of(), 1, new Properties(), () -> Set.of(1));
		assertEquals(Set.of(1), selected.keySet());
		assertTrue(selected.get(1).isEmpty());
	}

	/** The same parsing the legacy readers apply, including the empty-result normalisation. */
	@Test
	public void stageFilesParseLikeTheLegacyFile() throws Exception {
		Path out = output();
		Files.writeString(declare(out).resolve("1.txt"),
				"\nstarexec-result=\nnokey\n=novalue\n key = spaced \n");

		Properties p = StageAttributeFiles.select(
				out, PAIR, Set.of(), 1, new Properties(), () -> Set.of(1)).get(1);
		assertEquals("starexec-unknown", p.getProperty("starexec-result"));
		assertEquals("spaced", p.getProperty("key"));
		assertEquals(2, p.size());
	}

	// ------------------------------------------------------------------ legacy

	/** Without the marker, a single-stage pair's legacy file goes to its only stage, whatever it is. */
	@Test
	public void legacyAttributesGoToASingleStagePairsOnlyStage() throws Exception {
		Path out = output();
		Map<Integer, Properties> selected = StageAttributeFiles.select(
				out, PAIR, Set.of(), 2, props("starexec-result", "Theorem"), () -> Set.of(2));
		assertEquals(Set.of(2), selected.keySet());
	}

	/** ... and a multi-stage pair's to none of them. */
	@Test
	public void legacyAttributesAreNotGuessedForAMultiStagePair() throws Exception {
		Path out = output();
		Map<Integer, Properties> selected = StageAttributeFiles.select(
				out, PAIR, Set.of(1), 2, props("starexec-result", "Theorem"), () -> Set.of(1, 2));
		assertTrue(selected.isEmpty());
	}

	/** Legacy attributes are only taken at completion, as the new files are only taken when finished. */
	@Test
	public void legacyAttributesWaitForCompletion() throws Exception {
		Path out = output();
		Map<Integer, Properties> selected = StageAttributeFiles.select(
				out, PAIR, Set.of(), 0, props("starexec-result", "Theorem"), () -> Set.of(1));
		assertTrue(selected.isEmpty());
	}

	// ------------------------------------------------------------------ rerun cleanup

	/**
	 * Fail closed: a per-stage directory the cleanup cannot remove stops the attempt, as a
	 * surviving stage-status directory does. Otherwise attempt 1's files would be read as
	 * attempt 2's.
	 */
	@Test
	public void localRefusesToStartWhenStageAttributesSurvive() throws Exception {
		Path out = output();
		Path dir = declare(out);
		Files.writeString(dir.resolve("2.txt"), "starexec-result=Stale\n");
		Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			Method cleanup = LocalBackend.class.getDeclaredMethod(
					"cleanupPreviousRunArtifacts", File.class);
			cleanup.setAccessible(true);
			assertFalse("the attempt must not start over a surviving stage-attributes directory",
					(boolean) cleanup.invoke(new LocalBackend(), out.toFile()));
		} finally {
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	public void kubernetesRefusesToSubmitWhenStageAttributesSurvive() throws Exception {
		Path out = output();
		Path dir = declare(out);
		Files.writeString(dir.resolve("2.txt"), "starexec-result=Stale\n");
		Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			Method cleanup = KubernetesNativeBackend.class.getDeclaredMethod(
					"clearStaleAttemptArtifacts", Path.class, int.class);
			cleanup.setAccessible(true);
			assertFalse("the attempt must not be submitted over a surviving stage-attributes"
							+ " directory",
					(boolean) cleanup.invoke(new KubernetesNativeBackend(), out, PAIR));
		} finally {
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	/** The controls: a removable directory is removed and the attempt goes ahead. */
	@Test
	public void bothBackendsClearARemovableStageAttributesDirectory() throws Exception {
		Path local = output();
		Files.writeString(declare(local).resolve("2.txt"), "starexec-result=Stale\n");
		Method localCleanup = LocalBackend.class.getDeclaredMethod(
				"cleanupPreviousRunArtifacts", File.class);
		localCleanup.setAccessible(true);
		assertTrue((boolean) localCleanup.invoke(new LocalBackend(), local.toFile()));
		assertFalse(Files.exists(local.resolve("stage-attributes")));

		Path k8s = output();
		Files.writeString(declare(k8s).resolve("2.txt"), "starexec-result=Stale\n");
		Method k8sCleanup = KubernetesNativeBackend.class.getDeclaredMethod(
				"clearStaleAttemptArtifacts", Path.class, int.class);
		k8sCleanup.setAccessible(true);
		assertTrue((boolean) k8sCleanup.invoke(new KubernetesNativeBackend(), k8s, PAIR));
		assertFalse(Files.exists(k8s.resolve("stage-attributes")));
	}
}
