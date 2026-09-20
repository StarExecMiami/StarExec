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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a stage failure writes, in container mode, to the files the Local monitor polls while
 * the pair is still running.
 *
 * <p>The failure helpers used to write two records: a pair-level one (stage 0) and then the
 * stage's own. Everything that monitor would read between the two was wrong:
 * <ul>
 *   <li>A pair-level result has every stage snapshot checked for terminality, and the failing
 *       stage's snapshot still read RUNNING, so the pair was held for intervention for good.
 *       Reproduced on the Local backend: a wallclock timeout left stuck at RUNNING.</li>
 *   <li>In a later stage, whose snapshot does not exist yet, the pair-level result was accepted
 *       instead: the stage that failed was recorded as never reached, and no stage's
 *       measurements were published.</li>
 * </ul>
 * The stage-level record alone ends exactly where the two writes ended, so container mode now
 * writes only that. These cases run the real helper, and for the disk quota the jobscript's own
 * branch, recording every write and the files after it.
 */
public class StageFailureWriteOrderTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	private static final int STATUS_RUNNING = 4;
	private static final int STATUS_COMPLETE = 7;
	private static final int ERROR_DISK_QUOTA_EXCEEDED = 13;
	private static final int EXCEED_RUNTIME = 14;
	private static final int EXCEED_CPU = 15;
	private static final int EXCEED_MEM = 17;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aWallclockTimeoutIsReportedAgainstItsStageOnly() throws Exception {
		assertReportedAgainstTheStageOnly(runInFirstStage("sendWallclockExceededStatus\n"), EXCEED_RUNTIME, 1);
	}

	@Test
	public void aCpuTimeoutIsReportedAgainstItsStageOnly() throws Exception {
		assertReportedAgainstTheStageOnly(runInFirstStage("sendCpuExceededStatus\n"), EXCEED_CPU, 1);
	}

	@Test
	public void aMemoryLimitIsReportedAgainstItsStageOnly() throws Exception {
		assertReportedAgainstTheStageOnly(runInFirstStage("sendExceedMemStatus\n"), EXCEED_MEM, 1);
	}

	/** The jobscript's own disk-quota branch, executed as it stands in the template. */
	@Test
	public void theJobscriptsDiskQuotaBranchIsReportedAgainstItsStageOnly() throws Exception {
		Run run = runInFirstStage("ERROR_DETECTED=false\nDISK_QUOTA_EXCEEDED=1\n" + diskQuotaBranch() + "\n");

		assertReportedAgainstTheStageOnly(run, ERROR_DISK_QUOTA_EXCEEDED, 1);
	}

	/**
	 * The second symptom. Stage 2 has no snapshot until it ends, and stage 1's reads COMPLETE, so
	 * a pair-level record in between would pass every check and be recorded as the result.
	 */
	@Test
	public void aFailureInALaterStageIsNeverOfferedAsAPairLevelResult() throws Exception {
		Run run = run(true, "STAGE_NUMBERS=(1 2)\nSTAGE_INDEX=0\n"
				+ "sendStatus \"$STATUS_RUNNING\"\n"
				+ "sendStageStatus \"$STATUS_RUNNING\" 1\n"
				+ "sendStageStatus \"$STATUS_COMPLETE\" 1\n"
				+ "STAGE_INDEX=1\n"
				+ RECORDER
				+ "sendWallclockExceededStatus\n");

		assertReportedAgainstTheStageOnly(run, EXCEED_RUNTIME, 2);
		assertEquals("stage 1 keeps its own result:\n" + run.out, STATUS_COMPLETE,
				field(run.lastState().get(1), "status"));
	}

	/** Outside container mode both database calls are still made, in the same order. */
	@Test
	public void theDatabasePathStillRecordsThePairAndThenTheStage() throws Exception {
		Run run = run(false, "STAGE_NUMBERS=(1)\nSTAGE_INDEX=0\n"
				+ "dbExec() { echo \"T-SQL|$1\"; }\n"
				+ "sendWallclockExceededStatus\n");

		int pair = run.out.indexOf("T-SQL|CALL UpdatePairStatus(41, " + EXCEED_RUNTIME + ")");
		int stage = run.out.indexOf("T-SQL|CALL UpdatePairStageStatus(41, 1, " + EXCEED_RUNTIME + ")");
		assertTrue("the pair status is recorded:\n" + run.out, pair >= 0);
		assertTrue("and then the stage status:\n" + run.out, stage > pair);
	}

	// ------------------------------------------------------------------------- assertions

	private static void assertReportedAgainstTheStageOnly(Run run, int status, int stage) {
		assertFalse("the failure must write something:\n" + run.out, run.writes.isEmpty());
		for (String[] write : run.writes) {
			assertFalse("a terminal status was written on the pair-level channel (stage 0):\n" + run.out,
					"0".equals(write[1]) && !String.valueOf(STATUS_RUNNING).equals(write[0]));
		}
		// The monitor's view after every write: a pair-level terminal status with any
		// snapshot still RUNNING is the state it holds for intervention.
		for (List<String> state : run.states) {
			boolean pairLevelTerminal = field(state.get(0), "stageNumber") == 0
					&& field(state.get(0), "status") != STATUS_RUNNING;
			boolean aStageStillRunning = state.subList(1, state.size()).stream()
					.anyMatch(snapshot -> field(snapshot, "status") == STATUS_RUNNING);
			assertFalse("status.json was a terminal pair-level result while a stage read RUNNING:\n"
					+ run.out, pairLevelTerminal && aStageStillRunning);
		}

		List<String> last = run.lastState();
		assertEquals("status.json ends with the failure:\n" + run.out, status, field(last.get(0), "status"));
		assertEquals("naming the stage that failed:\n" + run.out, stage, field(last.get(0), "stageNumber"));
		assertEquals("and that stage's snapshot carries it:\n" + run.out, status,
				field(last.get(stage), "status"));
	}

	// ---------------------------------------------------------------------------- harness

	/**
	 * Replaces the writer with one that records its arguments, calls the real one and then
	 * prints status.json followed by every snapshot in stage order.
	 */
	private static final String RECORDER = ""
			+ "eval \"original_$(declare -f containerWriteStatus)\"\n"
			+ "containerWriteStatus() {\n"
			+ "\techo \"T-WRITE|$1|${2:-0}|${3:-true}\"\n"
			+ "\toriginal_containerWriteStatus \"$@\"\n"
			+ "\tlocal line snapshot\n"
			+ "\tline=\"T-STATE|$(cat \"$CONTAINER_STATUS_FILE\")\"\n"
			+ "\tfor snapshot in \"$CONTAINER_STAGE_STATUS_DIR\"/*.json; do\n"
			+ "\t\tline+=\"|$(cat \"$snapshot\")\"\n"
			+ "\tdone\n"
			+ "\techo \"$line\"\n"
			+ "}\n";

	/** One stage, in the state sendNode leaves it: status.json and its snapshot read RUNNING. */
	private Run runInFirstStage(String failure) throws Exception {
		return run(true, "STAGE_NUMBERS=(1)\nSTAGE_INDEX=0\n"
				+ "sendStatus \"$STATUS_RUNNING\"\n"
				+ "sendStageStatus \"$STATUS_RUNNING\" \"${STAGE_NUMBERS[STAGE_INDEX]}\"\n"
				+ RECORDER
				+ failure);
	}

	private Run run(boolean containerMode, String body) throws Exception {
		Path dir = folder.newFolder("order-" + System.nanoTime()).toPath();
		// The shipped files, copied rather than re-implemented.
		Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"));
		Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"));

		String script = "export SCRIPT_DIR=\"" + dir + "\"\n"
				+ "export STAREXEC_OUTPUT_DIR=\"" + dir.resolve("out") + "\"\n"
				+ "export CONTAINER_MODE=" + containerMode + "\n"
				+ "export PAIR_ID=41\n"
				+ "export SHARED_DIR=\"" + dir.resolve("shared") + "\"\n"
				+ "export WORKING_DIR_BASE=\"" + dir.resolve("work") + "\"\n"
				+ "export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n"
				+ "export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n"
				+ "SOLVER_PATHS=()\n"
				+ ". \"$SCRIPT_DIR/functions.bash\"\n"
				// Snapshots are listed with a glob; with none it must expand to nothing.
				+ "shopt -s nullglob\n"
				+ body;
		File file = new File(dir.toFile(), "generated.sh");
		Files.writeString(file.toPath(), script);
		assertEquals("generated script must parse", 0, exec(dir, Bash.PATH, "-n", file.getAbsolutePath()).exit);

		Result result = exec(dir, Bash.PATH, file.getAbsolutePath());
		assertEquals("the helper must not abort:\n" + result.out, 0, result.exit);
		return new Run(result.out);
	}

	/** The disk-quota branch of the jobscript's stage loop, from its `if` to its `fi`. */
	private static String diskQuotaBranch() throws Exception {
		List<String> lines = Files.readAllLines(SGE.resolve("jobscript"));
		String opening = "if [ \"$ERROR_DETECTED\" = false ] && ((DISK_QUOTA_EXCEEDED == 1)); then";
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.strip().equals(opening)) {
				String indent = line.substring(0, line.indexOf('i'));
				for (int j = i + 1; j < lines.size(); j++) {
					if (lines.get(j).equals(indent + "fi")) {
						return String.join("\n", lines.subList(i, j + 1));
					}
				}
			}
		}
		throw new AssertionError("the jobscript's disk-quota branch was not found: " + opening);
	}

	/** Reads one integer field, so the assertions do not depend on field order. */
	private static int field(String json, String name) {
		Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
		assertTrue("json must carry " + name + ": " + json, m.find());
		return Integer.parseInt(m.group(1));
	}

	/**
	 * Output goes to a file rather than a pipe, and the wait is bounded, so a script that
	 * leaves a child behind cannot hang the build.
	 */
	private static Result exec(Path dir, String... command) throws Exception {
		File log = new File(dir.toFile(), "out-" + System.nanoTime() + ".txt");
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(true);
		pb.redirectOutput(log);
		Process p = pb.start();
		boolean finished = p.waitFor(60, TimeUnit.SECONDS);
		if (!finished) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
		}
		String out = Files.readString(log.toPath(), StandardCharsets.UTF_8);
		assertTrue("command must not hang:\n" + out, finished);
		return new Result(p.exitValue(), out);
	}

	/** The recorded writes, and status.json plus each snapshot (by stage) after each one. */
	private static final class Run {
		final String out;
		final List<String[]> writes = new ArrayList<>();
		final List<List<String>> states = new ArrayList<>();

		Run(String out) {
			this.out = out;
			for (String line : out.split("\n")) {
				if (line.startsWith("T-WRITE|")) {
					writes.add(line.substring("T-WRITE|".length()).split("\\|"));
				} else if (line.startsWith("T-STATE|")) {
					List<String> state = new ArrayList<>(List.of(line.substring("T-STATE|".length()).split("\\|")));
					states.add(state);
				}
			}
		}

		/** status.json at index 0, then stage n's snapshot at index n. */
		List<String> lastState() {
			assertFalse("no write was recorded:\n" + out, states.isEmpty());
			List<String> last = states.get(states.size() - 1);
			for (int n = 1; n < last.size(); n++) {
				assertEquals("snapshots are listed in stage order:\n" + out, n, field(last.get(n), "stageNumber"));
			}
			return last;
		}
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
