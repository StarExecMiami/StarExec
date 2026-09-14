package org.starexec.test.unit.jobs;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A monitor reading {@code attributes.txt} or {@code stats.json} while the job script replaces
 * them must see one stage's complete file, never an empty or a mixed one.
 *
 * <p>A Local monitor polls these files while the pair runs. On the integrated qualification
 * deployment a watcher saw a 0-byte {@code attributes.txt} and an 84-byte one holding stage 1's
 * attributes followed by stage 2's {@code SZSStatus=UNK}. Two writers produced those: a per-line
 * {@code >>} append from {@code processAttributes}, and the {@code cp} that then replaced the
 * file, which truncates its destination before writing. {@code containerWriteStats} truncates
 * {@code stats.json} the same way with {@code cat >}.
 *
 * <p>Deterministic rather than a sleep race. The shipped helper runs a real two-stage
 * {@code copyOutput} with a real post-processor, and the published files are observed at every
 * point a reader could interleave:
 *
 * <ul>
 *   <li>between every two commands, through a {@code DEBUG} trap inherited by functions;</li>
 *   <li>inside {@code cat >}, whose redirection has already truncated the target by the time
 *       {@code cat} runs;</li>
 *   <li>inside {@code cp}, between the truncation of an existing destination and the write --
 *       the window GNU {@code cp} opens with {@code O_TRUNC}, reproduced explicitly because a
 *       single command is otherwise opaque to the trap.</li>
 * </ul>
 *
 * <p>{@code mv} is not wrapped: a rename within one directory replaces the name atomically, and
 * that is the property the job script is expected to rely on.
 */
public class LegacyResultFileAtomicityTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	private static final String STAGE_1_ATTRIBUTES = "starexec-result=Theorem\nSZSStatus=THM\n";
	private static final String STAGE_2_ATTRIBUTES = "starexec-result=Unknown\nSZSStatus=UNK\n";

	@ClassRule
	public static TemporaryFolder folder = new TemporaryFolder();

	/**
	 * One traced run serves every test. The trace is deterministic, so rerunning it per test
	 * would observe exactly the same states, several times slower.
	 */
	private static Run run;

	private static synchronized Run run() throws Exception {
		if (run == null) {
			run = runTwoStages();
		}
		return run;
	}

	@Test
	public void attributesAreNeverObservedEmpty() throws Exception {
		assertFalse("a reader observed an empty attributes.txt",
				run().observed("attributes.txt").contains(""));
	}

	/** Empty is the test above; anything else that is not exactly one stage's file is here. */
	@Test
	public void attributesAreNeverObservedMixed() throws Exception {
		Set<String> wrong = new LinkedHashSet<>();
		for (String state : run().observed("attributes.txt")) {
			if (!state.isEmpty() && !state.equals(STAGE_1_ATTRIBUTES)
					&& !state.equals(STAGE_2_ATTRIBUTES)) {
				wrong.add(quote(state));
			}
		}
		assertTrue("a reader observed attributes.txt states that are not one stage's file: "
				+ wrong, wrong.isEmpty());
	}

	@Test
	public void statsAreNeverObservedEmpty() throws Exception {
		assertFalse("a reader observed an empty stats.json",
				run().observed("stats.json").contains(""));
	}

	@Test
	public void statsAreNeverObservedIncomplete() throws Exception {
		Set<String> settled = run().settled("stats.json");
		assertEquals("each stage settles one stats.json: " + settled, 2, settled.size());
		Set<String> wrong = new LinkedHashSet<>();
		for (String state : run().observed("stats.json")) {
			if (!state.isEmpty() && !settled.contains(state)) {
				wrong.add(quote(state));
			}
		}
		assertTrue("a reader observed stats.json states that are not one stage's record: "
				+ wrong, wrong.isEmpty());
	}

	/**
	 * The control for the four tests above: the observer ran, saw both stages' files, and the
	 * published files still end as the last stage's -- which is all an older monitor reads.
	 */
	@Test
	public void theObserverSeesBothStagesAndTheFinalFilesAreUnchanged() throws Exception {
		Run run = run();
		List<String> attributes = run.observed("attributes.txt");
		assertTrue("stage 1's attributes must have been observed: " + attributes,
				attributes.contains(STAGE_1_ATTRIBUTES));
		assertTrue("stage 2's attributes must have been observed: " + attributes,
				attributes.contains(STAGE_2_ATTRIBUTES));
		assertEquals(STAGE_2_ATTRIBUTES, Files.readString(run.out.resolve("attributes.txt")));

		String stats = Files.readString(run.out.resolve("stats.json"));
		assertTrue("the final stats.json names the last stage: " + stats,
				stats.contains("\"stageNumber\": 2,"));
		assertTrue("the final stats.json is complete: " + stats,
				stats.trim().endsWith("}"));
	}

	/** A rename leaves nothing behind for a cleanup list or a monitor to trip over. */
	@Test
	public void noTemporaryFileIsLeftBehind() throws Exception {
		Run run = run();
		try (var entries = Files.list(run.out)) {
			entries.map(p -> p.getFileName().toString())
					.forEach(name -> assertFalse("left behind: " + name, name.endsWith(".tmp")));
		}
	}

	// ------------------------------------------------------------------------- harness

	private static final class Run {
		final Path out;
		final List<String> lines;

		Run(Path out, List<String> lines) {
			this.out = out;
			this.lines = lines;
		}

		/** Every state of {@code name} a reader could have seen, in order. */
		List<String> observed(String name) {
			return decode("O", name);
		}

		/** The state each stage left once its copyOutput returned. */
		Set<String> settled(String name) {
			return new LinkedHashSet<>(decode("S", name));
		}

		private List<String> decode(String kind, String name) {
			List<String> states = new ArrayList<>();
			for (String line : lines) {
				String[] f = line.split("\t", -1);
				if (f.length == 3 && f[0].equals(kind) && f[1].equals(name)) {
					states.add(new String(Base64.getDecoder().decode(f[2]),
							StandardCharsets.UTF_8));
				}
			}
			return states;
		}
	}

	private static Run runTwoStages() throws Exception {
		Path dir = folder.newFolder("pair-" + System.nanoTime()).toPath();
		Path out = dir.resolve("out");
		Files.createDirectories(out);
		Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"));
		Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"));

		// A post-processor in the shape of the SZS one, emitting two keys per stage so a mixed
		// file is distinguishable from either stage's own.
		Path pp = dir.resolve("postprocessor");
		Files.createDirectories(pp);
		Files.writeString(pp.resolve("process"), "#!/bin/bash\n"
				+ "case \"$(cat \"$1\")\" in\n"
				+ "  *Theorem*) printf 'starexec-result=Theorem\\nSZSStatus=THM\\n' ;;\n"
				+ "  *) printf 'starexec-result=Unknown\\nSZSStatus=UNK\\n' ;;\n"
				+ "esac\n");
		Files.writeString(dir.resolve("bench.p"), "fof(a, conjecture, $true).\n");
		Path log = dir.resolve("observed.tsv");

		StringBuilder b = new StringBuilder();
		b.append("export SCRIPT_DIR='").append(dir).append("'\n");
		b.append("export STAREXEC_OUTPUT_DIR='").append(out).append("'\n");
		b.append("export CONTAINER_MODE=true\n");
		b.append("export PAIR_ID=4242\n");
		b.append("export SHARED_DIR='").append(dir).append("/shared'\n");
		b.append("export WORKING_DIR_BASE='").append(dir).append("/work'\n");
		b.append("export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n");
		b.append("export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n");
		b.append("SOLVER_PATHS=()\n");
		b.append(". \"$SCRIPT_DIR/functions.bash\"\n");
		b.append("RUNSOLVER=RUNSOLVER\nBENCHEXEC=BENCHEXEC\n");
		b.append("STAREXEC_WALLCLOCK_LIMIT=100\nSTAREXEC_CPU_LIMIT=100\nDISK_QUOTA_EXCEEDED=0\n");
		b.append("REPORT_HOST=localhost\nNUM_STAGES=2\n");
		b.append("LOCAL_BENCH_PATH='").append(dir).append("/bench.p'\n");
		// Saving solver output into the pair's output tree is not under test.
		b.append("function copyOutputNoStats { :; }\n");

		// The observer. Records what a reader would read right now, for each published file.
		b.append("OBSERVED='").append(log).append("'\n");
		b.append("function observe {\n");
		b.append("  local kind=$1 name\n");
		b.append("  for name in attributes.txt stats.json; do\n");
		b.append("    if [[ -f \"$STAREXEC_OUTPUT_DIR/$name\" ]]; then\n");
		b.append("      printf '%s\\t%s\\t%s\\n' \"$kind\" \"$name\" "
				+ "\"$(base64 -w0 < \"$STAREXEC_OUTPUT_DIR/$name\")\" >> \"$OBSERVED\"\n");
		b.append("    fi\n");
		b.append("  done\n");
		b.append("}\n");
		// cp: the truncation of an existing destination, then the write.
		b.append("function cp {\n");
		b.append("  local dest=\"${!#}\"\n");
		b.append("  if [[ -f \"$dest\" ]]; then : > \"$dest\"; fi\n");
		b.append("  observe O\n");
		b.append("  command cp \"$@\"\n");
		b.append("}\n");
		// cat: its output redirection has already truncated the target.
		b.append("function cat { observe O; command cat \"$@\"; }\n");
		b.append("set -o functrace\n");
		b.append("trap 'observe O' DEBUG\n");

		String[] szs = {"Theorem", "Unknown"};
		for (int i = 0; i < szs.length; i++) {
			int stage = i + 1;
			b.append("STAGE_INDEX=").append(i).append('\n');
			b.append("CURRENT_STAGE_NUMBER=").append(stage).append('\n');
			b.append("OUT_DIR='").append(dir).append("/work/stage").append(stage).append("'\n");
			b.append("mkdir -p \"$OUT_DIR/output_files\"\n");
			b.append("VARFILE=\"$OUT_DIR/var.out\"\nWATCHFILE=\"$OUT_DIR/watcher.out\"\n");
			b.append("STDOUT_FILE=\"$OUT_DIR/stdout.txt\"\n");
			b.append("printf '# SZS status ").append(szs[i]).append("\\n' > \"$STDOUT_FILE\"\n");
			b.append("printf 'WCTIME=").append(stage).append(".5\\nCPUTIME=").append(stage)
					.append(".25\\nUSERTIME=1\\nSYSTEMTIME=0\\nMAXVM=").append(stage * 1111)
					.append("\\nTIMEOUT=false\\nMEMOUT=false\\n' > \"$VARFILE\"\n");
			b.append("printf 'Child status: 0\\n' > \"$WATCHFILE\"\n");
			b.append("POST_PROCESSOR_PATH='").append(pp).append("'\n");
			b.append("POST_PROCESSOR_TIME_LIMIT=1\n");
			b.append("copyOutput ").append(stage).append(" 1 1 \"$RUNSOLVER\"\n");
			b.append("cd \"$SCRIPT_DIR\"\n");
			b.append("observe S\n");
		}
		b.append("trap - DEBUG\n");

		File script = dir.resolve("generated.sh").toFile();
		Files.writeString(script.toPath(), b.toString());
		Result parse = exec(Bash.PATH, "-n", script.getAbsolutePath());
		assertEquals("generated script must parse:\n" + parse.out, 0, parse.exit);
		Result run = exec(Bash.PATH, script.getAbsolutePath());
		assertEquals("the helper must not abort:\n" + run.out, 0, run.exit);
		assertTrue("the observer must have recorded something", Files.size(log) > 0);
		return new Run(out, Files.readAllLines(log));
	}

	private static String quote(String s) {
		return "\"" + s.replace("\n", "\\n") + "\"";
	}

	private static Result exec(String... command) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(command);
		// updateStats formats runsolver's decimals with printf "%.0f", which rejects "1.5" under
		// a comma-decimal LC_NUMERIC -- a separately reported finding, not this test's subject.
		// Job containers run in the C locale.
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
