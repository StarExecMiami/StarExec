package org.starexec.test.unit.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code decodePathArrays} against the real {@code functions.bash}.
 *
 * <p>The helper decodes arrays of two different cardinalities. The per-stage arrays have one
 * element per pipeline stage; {@code BENCH_INPUT_PATHS} has one per benchmark input of the
 * <em>pair</em>, plus a sentinel. Nothing relates the two counts, and decoding the inputs on
 * the stage loop broke in both directions -- past the end of the array it is a fatal
 * {@code unbound variable} under {@code set -u}, and short of the end it leaves entries
 * encoded for {@code copyBenchmarkDependencies} to {@code cp} as paths.
 *
 * <p>These run the shipped helper itself rather than a retyped decoder, because the defect is
 * an interaction between the loop bound, the producer's cardinality and strict mode -- all
 * three have to be the real ones for the test to mean anything. There were no shell tests in
 * this repository before; the closest precedent is {@code LocalBackendTests}, which writes a
 * temp script and executes it.
 */
public class JobscriptDecodePathArraysTest {

	/** Everything sourcing the helper needs; discovered by sourcing it, not by guessing. */
	private static final String HARNESS_PRELUDE = String.join("\n",
			"export PAIR_ID=1",
			"export WORKING_DIR_BASE=\"$SCRIPT_DIR/work\"",
			"export SHARED_DIR=\"$SCRIPT_DIR/shared\"",
			"export STAREXEC_OUTPUT_DIR=\"$SCRIPT_DIR/out\"",
			"export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"",
			"export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"");

	private static final Path HELPER =
			Path.of("src/main/java/org/starexec/config/sge/functions.bash");
	private static final Path STATUS_CODES =
			Path.of("src/main/java/org/starexec/config/sge/status_codes.bash");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// ------------------------------------------------------------ the defect

	/**
	 * The shape that produced #123: two stages, no benchmark inputs, so
	 * {@code BENCH_INPUT_PATHS} holds only its sentinel and index 1 does not exist.
	 */
	@Test
	public void theTwoStageNoInputCaseDecodes() throws Exception {
		Decoded d = run(helper(), 2, 0);

		assertEquals("the helper must not abort", 0, d.exitCode);
		assertTrue("execution must continue past decodePathArrays", d.reachedMarker);
		assertEquals("no inputs to decode", List.of(), d.inputs);
		assertEquals(2, d.stages.size());
	}

	/**
	 * The negative control: the same helper with only the benchmark-input decode moved back
	 * onto the stage loop. It must fail at exactly the unset element, not for some unrelated
	 * setup reason.
	 */
	@Test
	public void theStageIndexedDecodeIsWhatFails() throws Exception {
		Decoded d = run(helperWithStageIndexedInputDecode(), 2, 0);

		assertFalse("the pre-fix helper must not succeed", d.exitCode == 0);
		assertFalse("it must not get past the decode", d.reachedMarker);
		assertTrue("it must fail on the unset benchmark-input element, was:\n" + d.output,
				d.output.contains("BENCH_INPUT_PATHS[i]: unbound variable")
						|| d.output.contains("BENCH_INPUT_PATHS: unbound variable"));
	}

	// ------------------------------------------------------- cardinality matrix

	/**
	 * The property under test: stage count does not determine how many benchmark inputs are
	 * decoded. Both directions of mismatch are included -- more stages than inputs was fatal,
	 * more inputs than stages left entries encoded.
	 */
	@Test
	public void everyCombinationDecodesEachValueExactlyOnce() throws Exception {
		int[][] matrix = {
				{1, 0}, {1, 1}, {1, 3},
				{2, 0}, {2, 1}, {2, 2}, {2, 3},
				{3, 1}, {3, 2}, {3, 3},
		};
		Path helper = helper();

		for (int[] c : matrix) {
			int stages = c[0];
			int inputs = c[1];
			String label = "stages=" + stages + " inputs=" + inputs;

			Decoded d = run(helper, stages, inputs);

			assertEquals(label + " must not abort:\n" + d.output, 0, d.exitCode);
			assertTrue(label + " must continue past the decode", d.reachedMarker);
			assertEquals(label + ": every declared input must be decoded",
					expectedInputs(inputs), d.inputs);
			assertEquals(label + ": every stage must be decoded",
					expectedStages(stages), d.stages);
		}
	}

	/**
	 * The sentinel is storage, not data. {@code JobManager} appends an empty string so the
	 * array is never undeclared, and {@code NUM_BENCH_INPUTS} excludes it -- so the decode
	 * must stop before it, exactly as {@code copyBenchmarkDependencies} does.
	 */
	@Test
	public void theSentinelIsNotDecodedAsAnInput() throws Exception {
		Decoded d = run(helper(), 2, 2);

		assertEquals("only the real inputs are decoded", 2, d.inputs.size());
		assertEquals("the sentinel must still be the empty string", "", d.sentinel);
	}

	/**
	 * Decoding must happen exactly once. A logical value that is itself valid base64 would
	 * come back as its own decoding if anything decoded twice.
	 */
	@Test
	public void aBase64LookingValueIsNotDoubleDecoded() throws Exception {
		// "YWJjZA==" is the base64 of "abcd", used here as the literal path content.
		Decoded d = run(helper(), 1, 1, List.of("YWJjZA=="));

		assertEquals("the value must survive as itself, not as its own decoding",
				List.of("YWJjZA=="), d.inputs);
	}

	/** The four per-stage arrays keep their own cardinality and are unaffected. */
	@Test
	public void stageArraysStillDecodePerStage() throws Exception {
		Decoded d = run(helper(), 3, 1);

		assertEquals(List.of("solver-0", "solver-1", "solver-2"),
				d.stages.stream().map(s -> s.solverName).collect(Collectors.toList()));
		assertEquals(List.of("/path/0", "/path/1", "/path/2"),
				d.stages.stream().map(s -> s.solverPath).collect(Collectors.toList()));
		assertEquals(List.of("config-0", "config-1", "config-2"),
				d.stages.stream().map(s -> s.configName).collect(Collectors.toList()));
	}

	// ----------------------------------------------------------------- harness

	private Path helper() throws Exception {
		Path dir = folder.newFolder("helper-" + System.nanoTime()).toPath();
		Files.copy(HELPER, dir.resolve("functions.bash"));
		Files.copy(STATUS_CODES, dir.resolve("status_codes.bash"));
		return dir;
	}

	/**
	 * The helper as it was before this fix: the benchmark-input decode back inside the
	 * NUM_STAGES loop. Derived from the real file so the control differs in nothing else.
	 */
	private Path helperWithStageIndexedInputDecode() throws Exception {
		Path dir = helper();
		Path f = dir.resolve("functions.bash");
		String s = Files.readString(f);

		String fixedStageLoop = "\t\tCONFIG_NAMES[i]=$(   base64 -d <<< \"${CONFIG_NAMES[i]}\")\n\tdone";
		if (!s.contains(fixedStageLoop)) {
			throw new IllegalStateException("decodePathArrays no longer has the expected shape;"
					+ " this control needs updating");
		}
		s = s.replace(fixedStageLoop,
				"\t\tCONFIG_NAMES[i]=$(   base64 -d <<< \"${CONFIG_NAMES[i]}\")\n"
						+ "\t\tBENCH_INPUT_PATHS[i]=$(base64 -d <<< \"${BENCH_INPUT_PATHS[i]}\")\n"
						+ "\tdone");
		Files.writeString(f, s);
		return dir;
	}

	private Decoded run(Path helperDir, int stages, int inputs) throws Exception {
		return run(helperDir, stages, inputs, null);
	}

	private Decoded run(Path helperDir, int stages, int inputs,
	                    List<String> inputValues) throws Exception {
		List<String> wantedInputs =
				inputValues != null ? inputValues : expectedInputs(inputs);

		StringBuilder script = new StringBuilder();
		script.append("export SCRIPT_DIR=\"").append(helperDir).append("\"\n");
		script.append(HARNESS_PRELUDE).append("\n");

		for (int i = 0; i < stages; i++) {
			script.append("STAGE_NUMBERS[").append(i).append("]=\"").append(i + 1).append("\"\n");
			script.append(assign("SOLVER_NAMES", i, "solver-" + i));
			script.append(assign("SOLVER_PATHS", i, "/path/" + i));
			script.append(assign("BENCH_SUFFIXES", i, ".suffix" + i));
			script.append(assign("CONFIG_NAMES", i, "config-" + i));
		}
		for (int i = 0; i < wantedInputs.size(); i++) {
			script.append(assign("BENCH_INPUT_PATHS", i, wantedInputs.get(i)));
		}
		// The sentinel JobManager appends so the array is never undeclared.
		script.append(assign("BENCH_INPUT_PATHS", wantedInputs.size(), ""));

		script.append("NUM_STAGES=${#STAGE_NUMBERS[@]}\n");
		script.append("NUM_BENCH_INPUTS=$(( ${#BENCH_INPUT_PATHS[@]} - 1 ))\n");
		script.append(". \"$SCRIPT_DIR/functions.bash\"\n");
		script.append("decodePathArrays\n");
		// Markers prove control flow continued rather than merely that nothing threw.
		script.append("for (( i = 0; i < NUM_STAGES; ++i )); do\n")
				.append("  echo \"RESULT-STAGE|$i|${SOLVER_NAMES[i]}|${SOLVER_PATHS[i]}"
						+ "|${BENCH_SUFFIXES[i]}|${CONFIG_NAMES[i]}\"\n")
				.append("done\n");
		script.append("for (( i = 0; i < NUM_BENCH_INPUTS; ++i )); do\n")
				.append("  echo \"RESULT-INPUT|$i|${BENCH_INPUT_PATHS[i]}\"\n")
				.append("done\n");
		script.append("echo \"RESULT-SENTINEL|${BENCH_INPUT_PATHS[NUM_BENCH_INPUTS]}\"\n");
		script.append("echo RESULT-MARKER-REACHED\n");

		File file = new File(helperDir.toFile(), "harness.sh");
		Files.writeString(file.toPath(), script.toString());

		ProcessBuilder pb = new ProcessBuilder(Bash.PATH, file.getAbsolutePath());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue("the harness must not hang", p.waitFor(60, TimeUnit.SECONDS));
		return new Decoded(p.exitValue(), output);
	}

	private String assign(String array, int index, String plaintext) {
		String encoded = Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
		return array + "[" + index + "]=\"" + encoded + "\"\n";
	}

	private List<String> expectedInputs(int count) {
		List<String> v = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			v.add("/bench/input" + i + ".p");
		}
		return v;
	}

	private List<Stage> expectedStages(int count) {
		List<Stage> v = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			v.add(new Stage("solver-" + i, "/path/" + i, ".suffix" + i, "config-" + i));
		}
		return v;
	}

	// ------------------------------------------------------------------ output

	private static final class Stage {
		final String solverName, solverPath, suffix, configName;

		Stage(String solverName, String solverPath, String suffix, String configName) {
			this.solverName = solverName;
			this.solverPath = solverPath;
			this.suffix = suffix;
			this.configName = configName;
		}

		@Override public boolean equals(Object o) {
			if (!(o instanceof Stage)) return false;
			Stage s = (Stage) o;
			return solverName.equals(s.solverName) && solverPath.equals(s.solverPath)
					&& suffix.equals(s.suffix) && configName.equals(s.configName);
		}

		@Override public int hashCode() {
			return solverName.hashCode();
		}

		@Override public String toString() {
			return solverName + "," + solverPath + "," + suffix + "," + configName;
		}
	}

	private static final class Decoded {
		final int exitCode;
		final String output;
		final boolean reachedMarker;
		final List<String> inputs = new ArrayList<>();
		final List<Stage> stages = new ArrayList<>();
		String sentinel;

		Decoded(int exitCode, String output) {
			this.exitCode = exitCode;
			this.output = output;
			this.reachedMarker = output.contains("RESULT-MARKER-REACHED");
			for (String line : output.split("\n")) {
				if (line.startsWith("RESULT-INPUT|")) {
					inputs.add(line.split("\\|", 3)[2]);
				} else if (line.startsWith("RESULT-STAGE|")) {
					String[] f = line.split("\\|", 6);
					stages.add(new Stage(f[2], f[3], f[4], f[5]));
				} else if (line.startsWith("RESULT-SENTINEL|")) {
					sentinel = line.substring("RESULT-SENTINEL|".length());
				}
			}
		}
	}
}
