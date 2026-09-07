package org.starexec.test.unit.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code adjustForK8s} must not disturb the stage loop's iterator.
 *
 * <p>It used the global {@code STAGE_INDEX} as its own loop variable. Bash leaves a
 * {@code for} variable at its final value, and this function runs near the top of the job
 * script, well before the stage loop — so by the time {@code initSandbox} called
 * {@code sendNode}, the global was already sitting at the last stage. The pair's initial
 * RUNNING status was therefore filed against the last stage of a multi-stage pair rather
 * than the first one that actually runs.
 *
 * <p>Five shapes, because the leak depended on where — or whether — the loop found its
 * script: a break at the first stage leaves a different value behind than running to the end.
 */
public class AdjustForK8sStageIndexTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void oneStageWithNoScript() throws Exception {
		assertStageIndexPreserved(1, -1);
	}

	@Test
	public void severalStagesWithNoScript() throws Exception {
		assertStageIndexPreserved(3, -1);
	}

	/** Found at the first stage, so the loop breaks immediately. */
	@Test
	public void severalStagesWithTheScriptAtTheFirst() throws Exception {
		assertStageIndexPreserved(3, 0);
	}

	/** Found at the last stage, so the loop runs to the end and then breaks. */
	@Test
	public void severalStagesWithTheScriptAtTheLast() throws Exception {
		assertStageIndexPreserved(3, 2);
	}

	@Test
	public void oneStageWithTheScript() throws Exception {
		assertStageIndexPreserved(1, 0);
	}

	/**
	 * The mixed-version case, and the reason the helper defaults {@code STAGE_INDEX} itself.
	 *
	 * <p>{@code functions.bash} lives in the data volume and is only copied when absent, while
	 * the {@code jobscript} template ships inside the application image. A deployment can
	 * therefore run this helper against a template generated before {@code STAGE_INDEX=0}
	 * existed. That template sets the variable nowhere before {@code initSandbox}, and it used
	 * to work only because {@code adjustForK8s} leaked a value into it.
	 *
	 * <p>So: source the helper, never assign {@code STAGE_INDEX}, and do what {@code sendNode}
	 * does. Under {@code set -u} an unset index is fatal, which would kill every pair on such a
	 * deployment before its solver ran.
	 */
	@Test
	public void theHelperIsUsableWithAJobscriptThatNeverSetsTheStageIndex() throws Exception {
		Path dir = folder.newFolder("old-template").toPath();
		Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"));
		Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"));

		StringBuilder s = new StringBuilder();
		s.append("set -euo pipefail\n");
		s.append(preamble(dir));
		s.append("SOLVER_PATHS=()\n");
		s.append("STAGE_NUMBERS=(1 2)\n");
		s.append(". \"$SCRIPT_DIR/functions.bash\"\n");
		// No STAGE_INDEX assignment anywhere -- exactly what the old template does.
		s.append("adjustForK8s\n");
		s.append("echo \"OUT-STAGE_INDEX|$STAGE_INDEX\"\n");
		// The read sendNode performs, which is what actually died.
		s.append("echo \"OUT-STAGE_NUMBER|${STAGE_NUMBERS[STAGE_INDEX]}\"\n");

		File script = new File(dir.toFile(), "old-template.sh");
		Files.writeString(script.toPath(), s.toString());
		Result r = run(Bash.PATH, script.getAbsolutePath());

		assertEquals("an old template must not die on an unbound STAGE_INDEX:\n" + r.out,
				0, r.exit);
		assertEquals("the helper must default the index to 0", "0", value(r.out, "OUT-STAGE_INDEX"));
		assertEquals("the initial status must name the first stage that runs",
				"1", value(r.out, "OUT-STAGE_NUMBER"));
	}

	/**
	 * The invariant tests above would all pass if {@code adjustForK8s} did nothing at all, so
	 * this pins the work it is actually there to do: when it finds the launcher it redirects
	 * {@code WORKING_DIR_BASE} at the per-pair k8s sandbox.
	 */
	@Test
	public void adjustForK8sStillRedirectsTheWorkingDirWhenItFindsTheLauncher() throws Exception {
		Path dir = folder.newFolder("still-works").toPath();
		Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"));
		Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"));
		Path solver = dir.resolve("solver0");
		Files.createDirectories(solver.resolve("bin"));
		Files.writeString(solver.resolve("bin/run_image_k8s.py"), "#\n");

		StringBuilder s = new StringBuilder();
		s.append(preamble(dir));
		s.append("SOLVER_PATHS=(\"").append(Base64.getEncoder().encodeToString(
				solver.toString().getBytes(StandardCharsets.UTF_8))).append("\")\n");
		s.append(". \"$SCRIPT_DIR/functions.bash\"\n");
		s.append("BEFORE=\"$WORKING_DIR_BASE\"\n");
		s.append("adjustForK8s\n");
		s.append("echo \"OUT-BEFORE|$BEFORE\"\n");
		s.append("echo \"OUT-AFTER|$WORKING_DIR_BASE\"\n");

		File script = new File(dir.toFile(), "still-works.sh");
		Files.writeString(script.toPath(), s.toString());
		Result r = run(Bash.PATH, script.getAbsolutePath());

		assertEquals("must not abort:\n" + r.out, 0, r.exit);
		assertTrue("adjustForK8s must still redirect WORKING_DIR_BASE, was "
						+ value(r.out, "OUT-AFTER"),
				value(r.out, "OUT-AFTER").endsWith("/k8sSandboxes/41"));
		assertTrue("and it must have changed from the original",
				!value(r.out, "OUT-AFTER").equals(value(r.out, "OUT-BEFORE")));
	}

	/** The exports every generated script carries before it sources the helper. */
	private static String preamble(Path dir) {
		StringBuilder s = new StringBuilder();
		s.append("export SCRIPT_DIR=\"").append(dir).append("\"\n");
		s.append("export STAREXEC_OUTPUT_DIR=\"").append(dir).append("/out\"\n");
		s.append("export CONTAINER_MODE=true\n");
		s.append("export PAIR_ID=41\n");
		s.append("export SHARED_DIR=\"").append(dir).append("/shared\"\n");
		s.append("export WORKING_DIR_BASE=\"").append(dir).append("/work\"\n");
		s.append("export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n");
		s.append("export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n");
		return s.toString();
	}

	/**
	 * Runs the shipped helper's {@code adjustForK8s} between two reads of the global, and
	 * requires them to agree.
	 *
	 * @param stages        how many stages the pair has
	 * @param scriptAtIndex which stage carries {@code run_image_k8s.py}, or -1 for none
	 */
	private void assertStageIndexPreserved(int stages, int scriptAtIndex) throws Exception {
		String label = "stages=" + stages + " scriptAt=" + scriptAtIndex;
		Path dir = folder.newFolder("k8s-" + stages + "-" + scriptAtIndex).toPath();
		Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"));
		Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"));

		StringBuilder s = new StringBuilder();
		s.append("export SCRIPT_DIR=\"").append(dir).append("\"\n");
		s.append("export STAREXEC_OUTPUT_DIR=\"").append(dir).append("/out\"\n");
		s.append("export CONTAINER_MODE=true\n");
		s.append("export PAIR_ID=41\n");
		s.append("export SHARED_DIR=\"").append(dir).append("/shared\"\n");
		s.append("export WORKING_DIR_BASE=\"").append(dir).append("/work\"\n");
		s.append("export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n");
		s.append("export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n");

		s.append("SOLVER_PATHS=(");
		for (int i = 0; i < stages; i++) {
			Path solver = dir.resolve("solver" + i);
			Files.createDirectories(solver.resolve("bin"));
			if (i == scriptAtIndex) {
				Files.writeString(solver.resolve("bin/run_image_k8s.py"), "#\n");
			}
			s.append('"').append(Base64.getEncoder().encodeToString(
					solver.toString().getBytes(StandardCharsets.UTF_8))).append("\" ");
		}
		s.append(")\n");

		s.append(". \"$SCRIPT_DIR/functions.bash\"\n");
		// The value jobscript sets before initSandbox, which is where sendNode reads it.
		s.append("STAGE_INDEX=0\n");
		s.append("adjustForK8s\n");
		s.append("echo \"OUT-STAGE_INDEX|$STAGE_INDEX\"\n");

		File script = new File(dir.toFile(), "generated.sh");
		Files.writeString(script.toPath(), s.toString());
		assertEquals(label + ": generated script must parse", 0,
				run(Bash.PATH, "-n", script.getAbsolutePath()).exit);

		Result r = run(Bash.PATH, script.getAbsolutePath());
		assertEquals(label + ": the helper must not abort:\n" + r.out, 0, r.exit);
		assertEquals(label + ": adjustForK8s must not move STAGE_INDEX",
				"0", value(r.out, "OUT-STAGE_INDEX"));
	}

	private static String value(String output, String key) {
		for (String line : output.split("\n")) {
			if (line.startsWith(key + "|")) {
				return line.substring(key.length() + 1);
			}
		}
		return null;
	}

	private static Result run(String... command) throws Exception {
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
