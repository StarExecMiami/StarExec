package org.starexec.test.unit.jobs;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The container start makes the job helpers on the data volume the image's own (#324).
 *
 * <p>The generated job script comes from the image's template and sources functions.bash from
 * the data volume. The entrypoint used to copy the helpers only when none existed, so an upgrade
 * paired a new script with the previous release's helper: on Quokka every job script died after
 * its solver finished and 72,443 pairs were filed as ERROR_BENCHMARK. These tests run the shipped
 * {@code docker/sync-sge-scripts.sh} against temporary directories.
 */
public class SyncSgeScriptsTest {

	private static final Path SCRIPT = Path.of("../docker/sync-sge-scripts.sh");

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private Path image;
	private Path volume;

	@Before
	public void layout() throws IOException {
		image = tmp.newFolder("config-sge").toPath();
		volume = tmp.getRoot().toPath().resolve("data").resolve("sge_scripts");
		write(image.resolve("functions.bash"), "new helper\n");
		write(image.resolve("jobscript"), "new template\n");
		write(image.resolve("RunSolverSource/Makefile"), "all:\n");
	}

	/** A first start installs every file of the image, executable. */
	@Test
	public void aFreshVolumeReceivesEveryHelper() throws Exception {
		assertEquals(0, run().exit);

		assertSameBytes("functions.bash");
		assertSameBytes("jobscript");
		assertSameBytes("RunSolverSource/Makefile");
		assertEquals("rwxr-xr-x",
				PosixFilePermissions.toString(Files.getPosixFilePermissions(volume.resolve("functions.bash"))));
	}

	/**
	 * The defect: an upgrade over a volume that already holds the previous release's helper. The
	 * old code skipped the copy because functions.bash existed.
	 */
	@Test
	public void anUpgradeReplacesTheStaleHelperAndKeepsItsCopy() throws Exception {
		write(volume.resolve("functions.bash"), "old helper\n");
		write(volume.resolve("jobscript"), "new template\n");

		Result r = run();

		assertEquals(r.output, 0, r.exit);
		assertSameBytes("functions.bash");
		List<Path> kept = backups("functions.bash");
		assertEquals("the replaced helper must be kept exactly once", 1, kept.size());
		assertEquals("old helper\n", Files.readString(kept.get(0)));
		assertTrue(r.output, r.output.contains("1 replaced"));
		assertEquals("an identical file is not backed up", 0, backups("jobscript").size());
	}

	/** Running again over a synchronised volume changes nothing and backs nothing up. */
	@Test
	public void aSecondStartIsANoOp() throws Exception {
		assertEquals(0, run().exit);
		Result again = run();

		assertEquals(again.output, 0, again.exit);
		assertTrue(again.output, again.output.contains("0 installed, 0 replaced"));
		try (Stream<Path> s = Files.list(volume)) {
			assertTrue("no backup directory for an unchanged volume",
					s.noneMatch(p -> p.getFileName().toString().startsWith(".replaced-")));
		}
	}

	/** Files the image does not ship, such as an operator's notes, are left alone. */
	@Test
	public void filesTheImageDoesNotShipAreLeftAlone() throws Exception {
		write(volume.resolve("operator-notes.txt"), "keep me\n");

		assertEquals(0, run().exit);

		assertEquals("keep me\n", Files.readString(volume.resolve("operator-notes.txt")));
	}

	/** A missing source is a failed start, not a silent one. */
	@Test
	public void aMissingSourceFails() throws Exception {
		Result r = run(tmp.getRoot().toPath().resolve("absent"), volume);

		assertNotEquals(r.output, 0, r.exit);
	}

	private void assertSameBytes(String rel) throws IOException {
		assertArrayEquals(rel, Files.readAllBytes(image.resolve(rel)), Files.readAllBytes(volume.resolve(rel)));
	}

	private List<Path> backups(String rel) throws IOException {
		if (!Files.isDirectory(volume)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(volume)) {
			return s.filter(p -> p.getFileName().toString().startsWith(".replaced-"))
					.map(p -> p.resolve(rel))
					.filter(Files::isRegularFile)
					.collect(Collectors.toList());
		}
	}

	private Result run() throws Exception {
		return run(image, volume);
	}

	private static Result run(Path src, Path dst) throws Exception {
		Process p = new ProcessBuilder(Bash.PATH, SCRIPT.toString(), src.toString(), dst.toString())
				.redirectErrorStream(true)
				.start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue("sync-sge-scripts.sh did not finish", p.waitFor(60, TimeUnit.SECONDS));
		return new Result(p.exitValue(), out);
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	private static final class Result {
		final int exit;
		final String output;

		Result(int exit, String output) {
			this.exit = exit;
			this.output = output;
		}
	}
}
