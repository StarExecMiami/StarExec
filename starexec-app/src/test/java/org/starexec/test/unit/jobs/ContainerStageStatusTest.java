package org.starexec.test.unit.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The producer half of the container-mode stage-status contract, driven through the real
 * {@code functions.bash}.
 *
 * <p>{@code status.json} is one file for a whole pair and {@code containerWriteStatus}
 * truncates it, so in a multi-stage pair each stage erased the one before it and only the
 * last status ever reached the database -- an earlier stage that ran a solver to completion
 * kept the status it was enqueued with. The fix keeps that file exactly as it was and adds
 * one record per stage beside it.
 *
 * <p>Asserted against the shipped helper rather than a copy of it. The other half of #127 --
 * {@code adjustForK8s} leaking the stage iterator -- is independent, and lives in
 * {@link AdjustForK8sStageIndexTest}.
 */
public class ContainerStageStatusTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// --------------------------------------------------------------- containerWriteStatus

	/**
	 * The defect itself: two stages, and both statuses must survive. The legacy file is
	 * still written and still holds the last stage, because the monitor reads the stage
	 * number from it and an older monitor reads nothing else.
	 */
	@Test
	public void everyStageKeepsItsOwnStatus() throws Exception {
		Harness h = new Harness(folder);
		h.write(7, 1);
		h.write(15, 2);
		h.run();

		assertEquals("stage 1 must survive stage 2's write", 7, h.snapshotStatus(1));
		assertEquals("stage 2 must be recorded", 15, h.snapshotStatus(2));
		assertEquals("the legacy file still holds the last write", 15, h.legacyStatus());
		assertEquals("the legacy file still names the last stage", 2, h.legacyStage());
		assertEquals("the legacy file still names the pair", 41, h.legacyPairId());
	}

	/** Each snapshot carries the pair, so the monitor can check it against the container. */
	@Test
	public void eachSnapshotNamesItsPairAndStage() throws Exception {
		Harness h = new Harness(folder);
		h.write(7, 3);
		h.run();

		assertEquals(41, h.snapshotPairId(3));
		assertEquals(3, h.snapshotStage(3));
	}

	/**
	 * Stage 0 is the pair-level channel -- {@code sendStatus} defaults to it and no
	 * {@code jobpair_stage_data} row exists for it -- so it must not produce a snapshot the
	 * monitor would then try to write.
	 */
	@Test
	public void thePairLevelChannelWritesNoSnapshot() throws Exception {
		Harness h = new Harness(folder);
		h.write(4, 0);
		h.run();

		assertEquals("the legacy file is still written", 4, h.legacyStatus());
		assertTrue("stage 0 must leave no snapshot", h.snapshotNames().isEmpty());
	}

	/**
	 * A stage number that is not a plain positive integer is refused rather than used to
	 * build a path. {@code ../escape} is the case that matters: it must not write outside
	 * the snapshot directory.
	 */
	@Test
	public void aStageNumberThatIsNotAPositiveIntegerWritesNothing() throws Exception {
		for (String stage : new String[]{"abc", "-1", "0", "1x", "../escape", "1 2", ""}) {
			Harness h = new Harness(folder);
			h.writeRaw(7, stage);
			h.run();

			assertTrue("stage '" + stage + "' must leave no snapshot: " + h.snapshotNames(),
					h.snapshotNames().isEmpty());
			assertFalse("stage '" + stage + "' must not write outside the snapshot directory",
					Files.exists(h.out.resolve("escape")));
		}
	}

	/** The temporary file the atomic rename goes through must not be left behind. */
	@Test
	public void theWriteLeavesNoTemporaryFile() throws Exception {
		Harness h = new Harness(folder);
		h.write(7, 1);
		h.write(7, 2);
		h.run();

		for (String name : h.snapshotNames()) {
			assertFalse("a temporary file was left behind: " + name, name.endsWith(".tmp"));
		}
		assertEquals("only the two snapshots", 2, h.snapshotNames().size());
	}

	/** Re-reporting a stage replaces that stage only, and leaves the others alone. */
	@Test
	public void rewritingOneStageLeavesTheOthersAlone() throws Exception {
		Harness h = new Harness(folder);
		h.write(4, 1);
		h.write(7, 1);
		h.write(4, 2);
		h.run();

		assertEquals("stage 1 holds its latest status", 7, h.snapshotStatus(1));
		assertEquals("stage 2 is untouched by stage 1's rewrite", 4, h.snapshotStatus(2));
	}

	// ------------------------------------------------------------------------- harness

	/** A generated script that sources the shipped helper and drives one function. */
	private static final class Harness {

		private final Path dir;
		private final Path out;
		private final StringBuilder body = new StringBuilder();

		Harness(TemporaryFolder folder) throws Exception {
			dir = folder.newFolder("h-" + System.nanoTime()).toPath();
			out = dir.resolve("out");
			Files.createDirectories(out);
			// The shipped files, copied rather than re-implemented, so the assertions are
			// about the helper that actually runs jobs.
			Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"));
			Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"));

			body.append("export SCRIPT_DIR=\"").append(dir).append("\"\n");
			body.append("export STAREXEC_OUTPUT_DIR=\"").append(out).append("\"\n");
			body.append("export CONTAINER_MODE=true\n");
			body.append("export PAIR_ID=41\n");
			body.append("export SHARED_DIR=\"").append(dir).append("/shared\"\n");
			body.append("export WORKING_DIR_BASE=\"").append(dir).append("/work\"\n");
			body.append("export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n");
			body.append("export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n");
			body.append("SOLVER_PATHS=()\n");
			body.append(". \"$SCRIPT_DIR/functions.bash\"\n");
		}

		void write(int status, int stage) {
			body.append("containerWriteStatus ").append(status).append(' ').append(stage).append('\n');
		}

		/** Deliberately unquoted-safe: the stage argument is passed verbatim, however odd. */
		void writeRaw(int status, String stage) {
			body.append("containerWriteStatus ").append(status).append(" '").append(stage).append("'\n");
		}

		void run() throws Exception {
			File script = new File(dir.toFile(), "generated.sh");
			Files.writeString(script.toPath(), body.toString());
			assertEquals("generated script must parse", 0,
					exec(Bash.PATH, "-n", script.getAbsolutePath()).exit);
			Result r = exec(Bash.PATH, script.getAbsolutePath());
			assertEquals("the helper must not abort:\n" + r.out, 0, r.exit);
		}

		Path snapshotDir() {
			return out.resolve("stage-status");
		}

		List<String> snapshotNames() throws Exception {
			List<String> names = new ArrayList<>();
			if (!Files.isDirectory(snapshotDir())) {
				return names;
			}
			try (var s = Files.list(snapshotDir())) {
				s.forEach(p -> names.add(p.getFileName().toString()));
			}
			names.sort(String::compareTo);
			return names;
		}

		int snapshotStatus(int stage) throws Exception {
			return field(Files.readString(snapshotDir().resolve(stage + ".json")), "status");
		}

		int snapshotStage(int stage) throws Exception {
			return field(Files.readString(snapshotDir().resolve(stage + ".json")), "stageNumber");
		}

		int snapshotPairId(int stage) throws Exception {
			return field(Files.readString(snapshotDir().resolve(stage + ".json")), "pairId");
		}

		int legacyStatus() throws Exception {
			return field(Files.readString(out.resolve("status.json")), "status");
		}

		int legacyStage() throws Exception {
			return field(Files.readString(out.resolve("status.json")), "stageNumber");
		}

		int legacyPairId() throws Exception {
			return field(Files.readString(out.resolve("status.json")), "pairId");
		}
	}

	/** Reads one integer field, so the assertions do not depend on field order. */
	private static int field(String json, String name) {
		var m = java.util.regex.Pattern
				.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)")
				.matcher(json);
		assertTrue("json must carry " + name + ": " + json, m.find());
		String raw = m.group(1);
		try {
			return Integer.parseInt(raw);
		} catch (NumberFormatException e) {
			// Digits, but not necessarily an int: the pattern would also match something
			// far too long. A test reading its own fixture should say that plainly rather
			// than die of an unhandled exception halfway through an assertion.
			throw new AssertionError("json field " + name + " is not an int: " + raw, e);
		}
	}

	private static Result exec(String... command) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue("command must not hang", p.waitFor(60, TimeUnit.SECONDS));
		return new Result(p.exitValue(), out);
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
