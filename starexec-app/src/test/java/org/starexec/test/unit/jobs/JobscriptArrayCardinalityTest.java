package org.starexec.test.unit.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.jobs.JobManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The producer half of the contract, joined to the consumer half.
 *
 * <p>{@code JobscriptDecodePathArraysTest} drives the real helper with hand-built arrays.
 * This drives the real helper with arrays built by the real producer, so the two cardinalities
 * are whatever {@code JobManager} actually emits rather than what a test assumes it emits.
 *
 * <p>{@code writeJobScript} itself is private and reaches the database -- {@code Spaces},
 * {@code JobPairs}, {@code Jobs}, {@code Benchmarks} -- so it cannot be called here. What can
 * be called is {@code JobManager.toBashArray}, the function that renders every one of those
 * arrays, plus the terminator rule beside it; and the counts come out of the real
 * {@code jobscript} template rather than being retyped. That covers the whole path from the
 * producer's array text to the helper's decode.
 */
public class JobscriptArrayCardinalityTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");
	private static final Path TEMPLATE = SGE.resolve("jobscript");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/**
	 * The representation, stated as the producer defines it: one element per input, plus the
	 * empty string {@code JobManager} appends "so the Bash array will always have some
	 * element". The sentinel is storage, not an input, which is why the count the helper uses
	 * is the length minus one.
	 */
	@Test
	public void theInputArrayCarriesOneSentinelBeyondTheInputs() {
		for (int inputs = 0; inputs <= 3; inputs++) {
			String rendered = JobManager.toBashArray("BENCH_INPUT_PATHS", withSentinel(paths(inputs)), true);
			int declared = countAssignments(rendered, "BENCH_INPUT_PATHS");

			assertEquals(inputs + " inputs must render " + (inputs + 1) + " elements",
					inputs + 1, declared);
		}
	}

	/** The template must define the two counts the helper's loops are bounded by. */
	@Test
	public void theTemplateDefinesBothCounts() throws Exception {
		String template = Files.readString(TEMPLATE);

		assertTrue("the template must define NUM_STAGES", numStagesLine(template) != null);
		assertTrue("the template must define NUM_BENCH_INPUTS", numBenchInputsLine(template) != null);
		assertTrue("NUM_BENCH_INPUTS must exclude the sentinel, was: " + numBenchInputsLine(template),
				numBenchInputsLine(template).contains("- 1"));
	}

	/**
	 * End to end across the seam: arrays rendered by the real producer, counts taken verbatim
	 * from the real template, decoded by the real helper. Two stages and one input is the
	 * shape that could not run at all.
	 */
	@Test
	public void producerOutputDecodesThroughTheRealHelper() throws Exception {
		assertDecodes(2, 1);
	}

	/** And the mismatch in the other direction, which left entries encoded. */
	@Test
	public void producerOutputWithMoreInputsThanStagesDecodes() throws Exception {
		assertDecodes(2, 3);
	}

	/** The shape that made #123 fatal: more stages than the input array has elements. */
	@Test
	public void producerOutputWithNoInputsDecodes() throws Exception {
		assertDecodes(2, 0);
	}

	// ----------------------------------------------------------------- harness

	private void assertDecodes(int stages, int inputs) throws Exception {
		String label = "stages=" + stages + " inputs=" + inputs;
		Path dir = folder.newFolder("gen-" + stages + "-" + inputs + "-" + System.nanoTime()).toPath();
		Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"));
		Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"));

		String template = Files.readString(TEMPLATE);
		StringBuilder s = new StringBuilder();
		s.append("export SCRIPT_DIR=\"").append(dir).append("\"\n");
		s.append("export PAIR_ID=1\n");
		s.append("export WORKING_DIR_BASE=\"$SCRIPT_DIR/work\"\n");
		s.append("export SHARED_DIR=\"$SCRIPT_DIR/shared\"\n");
		s.append("export STAREXEC_OUTPUT_DIR=\"$SCRIPT_DIR/out\"\n");
		s.append("export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n");
		s.append("export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n");

		// Rendered by the production function, exactly as writeJobScript renders them.
		s.append(JobManager.toBashArray("STAGE_NUMBERS", stageNumbers(stages), false));
		s.append(JobManager.toBashArray("SOLVER_NAMES", named("solver", stages), true));
		s.append(JobManager.toBashArray("SOLVER_PATHS", named("/path", stages), true));
		s.append(JobManager.toBashArray("BENCH_SUFFIXES", named(".sfx", stages), true));
		s.append(JobManager.toBashArray("CONFIG_NAMES", named("config", stages), true));
		s.append(JobManager.toBashArray("BENCH_INPUT_PATHS", withSentinel(paths(inputs)), true));

		// Counts lifted verbatim from the shipped template, not retyped.
		s.append(numStagesLine(template)).append("\n");
		s.append(numBenchInputsLine(template)).append("\n");

		s.append(". \"$SCRIPT_DIR/functions.bash\"\n");
		s.append("decodePathArrays\n");
		s.append("echo \"GEN-NUM_STAGES|$NUM_STAGES\"\n");
		s.append("echo \"GEN-NUM_BENCH_INPUTS|$NUM_BENCH_INPUTS\"\n");
		s.append("echo \"GEN-ARRAY_LEN|${#BENCH_INPUT_PATHS[@]}\"\n");
		s.append("for (( i = 0; i < NUM_BENCH_INPUTS; ++i )); do echo \"GEN-INPUT|${BENCH_INPUT_PATHS[i]}\"; done\n");
		s.append("echo GEN-DECODE-COMPLETED\n");

		File script = new File(dir.toFile(), "generated.sh");
		Files.writeString(script.toPath(), s.toString());

		// The generated text must be syntactically valid before it is run.
		assertEquals(label + ": generated script must parse", 0,
				run(Bash.PATH, "-n", script.getAbsolutePath()).exit);

		Result r = run(Bash.PATH, script.getAbsolutePath());
		assertEquals(label + ": must not abort:\n" + r.out, 0, r.exit);
		assertTrue(label + ": must complete the decode:\n" + r.out, r.out.contains("GEN-DECODE-COMPLETED"));
		assertEquals(label + ": NUM_STAGES", String.valueOf(stages), value(r.out, "GEN-NUM_STAGES"));
		assertEquals(label + ": NUM_BENCH_INPUTS", String.valueOf(inputs), value(r.out, "GEN-NUM_BENCH_INPUTS"));
		assertEquals(label + ": array length is inputs plus the sentinel",
				String.valueOf(inputs + 1), value(r.out, "GEN-ARRAY_LEN"));

		List<String> decoded = new ArrayList<>();
		for (String line : r.out.split("\n")) {
			if (line.startsWith("GEN-INPUT|")) {
				decoded.add(line.substring("GEN-INPUT|".length()));
			}
		}
		assertEquals(label + ": every input decoded exactly once", paths(inputs), decoded);
	}

	private static String numStagesLine(String template) {
		return firstMatch(template, "^NUM_STAGES=.*$");
	}

	private static String numBenchInputsLine(String template) {
		return firstMatch(template, "^NUM_BENCH_INPUTS=.*$");
	}

	private static String firstMatch(String haystack, String regex) {
		Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(haystack);
		return m.find() ? m.group() : null;
	}

	private static int countAssignments(String rendered, String array) {
		int n = 0;
		for (String line : rendered.split("\n")) {
			if (line.startsWith(array + "[")) {
				n++;
			}
		}
		return n;
	}

	/** Replicates JobManager.writeJobScript's terminator, which is not part of toBashArray. */
	private static List<String> withSentinel(List<String> inputs) {
		List<String> v = new ArrayList<>(inputs);
		v.add("");
		return v;
	}

	private static List<String> paths(int count) {
		List<String> v = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			v.add("/bench/input" + i + ".p");
		}
		return v;
	}

	private static List<String> named(String prefix, int count) {
		List<String> v = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			v.add(prefix + "-" + i);
		}
		return v;
	}

	private static List<String> stageNumbers(int count) {
		List<String> v = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			v.add(String.valueOf(i + 1));
		}
		return v;
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
