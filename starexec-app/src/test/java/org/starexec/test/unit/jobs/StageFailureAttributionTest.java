package org.starexec.test.unit.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Which stage a failed pair names, driven through the shipped {@code functions.bash} (#145).
 *
 * <p>A pair that fails while a stage is running belongs to that stage: the monitor hands the
 * number it reads to {@code UpdatePairStatusPrecise}, and stage 0 is the pair-level channel,
 * which owns no {@code jobpair_stage_data} row. Reporting 0 for a failure inside stage 2 marks
 * no stage with the result and every stage of the pair as not reached.
 *
 * <p>{@code STAGE_INDEX} cannot say whether a stage is running: {@code functions.bash} defaults
 * it to 0 and the jobscript sets it to 0 before the loop, so it is always set.
 * {@code CURRENT_STAGE_NUMBER} is the stage the loop is executing, and nothing else.
 */
public class StageFailureAttributionTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	/** ERROR_RUNSCRIPT, the code the other failures on this path already report. */
	private static final int ERROR_RUNSCRIPT = 11;
	private static final int ERROR_BENCHMARK = 12;
	private static final int EXCEED_MEM = 17;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// ------------------------------------------------------------ what a failure names

	/**
	 * The defect. A pair held at stage 0 with an ERROR_BENCHMARK it never earned: the EXIT trap
	 * reports the pair-level channel for a failure that happened while a stage was running.
	 */
	@Test
	public void aFailureInsideAStageNamesThatStage() throws Exception {
		Probe probe = new Probe(folder);

		probe.runToExit("CURRENT_STAGE_NUMBER=3\n" + "exit 1\n");

		assertEquals("the stage that was running", 3, probe.stage());
		assertEquals("a runscript error, as its siblings on this path report", ERROR_RUNSCRIPT, probe.status());
	}

	/** A failure before any stage runs keeps the pair-level channel. */
	@Test
	public void aFailureBeforeTheStageLoopNamesThePair() throws Exception {
		Probe probe = new Probe(folder);

		probe.runToExit("exit 1\n");

		assertEquals("no stage was running", 0, probe.stage());
		assertEquals(ERROR_RUNSCRIPT, probe.status());
	}

	/** And a failure between two stages names neither: the loop clears the number. */
	@Test
	public void aFailureBetweenStagesNamesThePair() throws Exception {
		Probe probe = new Probe(folder);

		probe.runToExit("CURRENT_STAGE_NUMBER=3\n" + "CURRENT_STAGE_NUMBER=\n" + "exit 1\n");

		assertEquals("the finished stage must not be named", 0, probe.stage());
	}

	/** A stage number is a number, not a digit: a two-digit stage must arrive intact. */
	@Test
	public void aTwoDigitStageNumberIsReportedIntact() throws Exception {
		Probe probe = new Probe(folder);

		probe.runToExit("CURRENT_STAGE_NUMBER=12\n" + "exit 1\n");

		assertEquals(12, probe.stage());
		assertEquals(ERROR_RUNSCRIPT, probe.status());
	}

	/**
	 * A missing configuration is found by verifyWorkspace, which runs inside the loop, so it
	 * belongs to the stage it was about to run.
	 */
	@Test
	public void aMissingConfigurationNamesTheStageItWouldHaveRun() throws Exception {
		Probe probe = new Probe(folder);

		probe.runToExit("CURRENT_STAGE_NUMBER=2\n"
				+ "LOCAL_CONFIG_PATH=\"" + probe.root + "/no-such-config\"\n"
				+ "CONFIG_NAME=starexec_run_default\n"
				+ "verifyWorkspace\n");

		assertEquals(2, probe.stage());
		assertEquals(ERROR_RUNSCRIPT, probe.status());
	}

	/** A missing benchmark keeps its own code, and gains the stage. */
	@Test
	public void aMissingBenchmarkNamesTheStageItWouldHaveRun() throws Exception {
		Probe probe = new Probe(folder);
		Path config = Files.writeString(probe.root.resolve("starexec_run_default"), "#!/bin/bash\n");
		config.toFile().setExecutable(true);

		probe.runToExit("CURRENT_STAGE_NUMBER=2\n"
				+ "LOCAL_CONFIG_PATH=\"" + config + "\"\n"
				+ "CONFIG_NAME=starexec_run_default\n"
				+ "LOCAL_BENCH_PATH=\"" + probe.root + "/no-such-benchmark\"\n"
				+ "BENCH_NAME=primary.p\n"
				+ "verifyWorkspace\n");

		assertEquals(2, probe.stage());
		assertEquals(ERROR_BENCHMARK, probe.status());
	}

	/** A limit is breached while the solver runs, so the breach belongs to that stage. */
	@Test
	public void aLimitBreachNamesTheStageThatWasRunning() throws Exception {
		Probe probe = new Probe(folder);

		probe.runToExit("CURRENT_STAGE_NUMBER=2\n" + "limitExceeded \"virtual memory\" $EXCEED_MEM\n");

		assertEquals(2, probe.stage());
		assertEquals(EXCEED_MEM, probe.status());
	}

	// ------------------------------------------------------------------ the jobscript

	/**
	 * The stage number is assigned at the top of the loop, before the setup that can fail:
	 * checkCache, copyDependencies, verifyWorkspace and sandboxWorkspace all run first, and a
	 * failure in any of them belongs to the stage being prepared.
	 */
	@Test
	public void theStageNumberIsAssignedBeforeTheStagesSetup() throws Exception {
		List<String> loop = stageLoop();

		int assignment = indexOfLineStartingWith(loop, "CURRENT_STAGE_NUMBER=${STAGE_NUMBERS[STAGE_INDEX]}");
		assertTrue("the loop must assign CURRENT_STAGE_NUMBER", assignment >= 0);
		for (String setup : new String[] {"checkCache", "copyDependencies", "verifyWorkspace", "sandboxWorkspace"}) {
			int call = indexOfLineStartingWith(loop, setup);
			assertTrue("the loop must call " + setup, call >= 0);
			assertTrue(setup + " runs before the stage number is assigned", assignment < call);
		}
	}

	/** And cleared at the end of an iteration: what runs between stages belongs to no stage. */
	@Test
	public void theStageNumberIsClearedAtTheEndOfAnIteration() throws Exception {
		List<String> loop = stageLoop();

		int cleared = indexOfLineStartingWith(loop, "CURRENT_STAGE_NUMBER=");
		int lastClear = -1;
		for (int i = 0; i < loop.size(); i++) {
			if (loop.get(i).strip().equals("CURRENT_STAGE_NUMBER=")) {
				lastClear = i;
			}
		}
		assertTrue("the loop must clear CURRENT_STAGE_NUMBER before it ends", lastClear > cleared);
		assertTrue("nothing after the clearing may use the stage number",
				loop.subList(lastClear + 1, loop.size()).stream().noneMatch(l -> l.contains("CURRENT_STAGE_NUMBER")));
	}

	/**
	 * A missing watchfile is reported the same way a missing varfile is: the output is saved
	 * without statistics, because the statistics are what the missing file holds, and then the
	 * runscript error is recorded. Saving with statistics reads the absent file, which under
	 * {@code set -e} ends the script before {@code markRunscriptError} is reached.
	 */
	@Test
	public void aMissingWatchfileSavesOutputWithoutStatistics() throws Exception {
		String jobscript = Files.readString(SGE.resolve("jobscript"));
		// The whole branch, to its exit: a comment naming a function would end a shorter match
		// before the body it is meant to read.
		Matcher branch = Pattern.compile(
				"Runsolver watchfile could not be found.*?\\n\\s*exit 0", Pattern.DOTALL)
				.matcher(jobscript);

		assertTrue("the missing-watchfile branch must exist", branch.find());
		assertTrue("it must save output without statistics, as the missing-varfile branch does:\n"
				+ branch.group(), branch.group().contains("copyOutputNoStats"));
	}

	// ----------------------------------------------------------------------- helpers

	/** The body of the jobscript's stage loop. */
	private static List<String> stageLoop() throws Exception {
		List<String> lines = Files.readAllLines(SGE.resolve("jobscript"));
		int start = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith("for (( STAGE_INDEX")) {
				start = i;
				break;
			}
		}
		assertTrue("the stage loop must exist", start >= 0);
		int end = -1;
		for (int i = start; i < lines.size(); i++) {
			if (lines.get(i).equals("done")) {
				end = i;
				break;
			}
		}
		assertTrue("the stage loop must end", end > start);
		return lines.subList(start + 1, end);
	}

	private static int indexOfLineStartingWith(List<String> lines, String prefix) {
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).strip().startsWith(prefix)) {
				return i;
			}
		}
		return -1;
	}

	/** Runs a script with the jobscript's own EXIT trap and reports what reached status.json. */
	private static final class Probe {

		private static final int PAIR_ID = 11;

		private final Path root;
		private final Path sge;
		private String lastOutput = "";

		Probe(TemporaryFolder folder) throws Exception {
			this.root = folder.newFolder("stage-" + System.nanoTime()).toPath();
			this.sge = root.resolve("sge");
			Files.createDirectories(sge);
			Files.createDirectories(root.resolve("work"));
			for (String name : new String[] {"functions.bash", "status_codes.bash", "timestamper.bash"}) {
				Path from = SGE.resolve(name);
				if (Files.exists(from)) {
					Files.copy(from, sge.resolve(name));
				}
			}
		}

		void runToExit(String body) throws Exception {
			String script = "export SCRIPT_DIR=\"" + sge + "\"\n"
					+ "export STAREXEC_OUTPUT_DIR=\"" + root + "/out/" + PAIR_ID + "\"\n"
					+ "export CONTAINER_MODE=true\n"
					+ "export PAIR_ID=" + PAIR_ID + "\n"
					+ "export STAREXEC_JOB_ID=7\n"
					+ "export WORKING_DIR_BASE=\"" + root + "/work\"\n"
					+ "export SHARED_DIR=\"" + root + "/shared\"\n"
					+ "export REPORT_HOST=localhost\n"
					+ "export BUILD_JOB=false\n"
					+ "export MAX_MEM=1000000000\n"
					+ "export NODE_MEM=8000000000\n"
					+ "export SANDBOX_USER_ONE=sandbox\n"
					+ "export SANDBOX_USER_TWO=sandbox2\n"
					+ "export HOSTNAME=\"${HOSTNAME:-testhost}\"\n"
					+ "export SCRIPT_PATH=\"" + root + "/script.bash\"\n"
					+ "export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n"
					+ "export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n"
					+ "mkdir -p \"$STAREXEC_OUTPUT_DIR\"\n"
					+ "SOLVER_PATHS=()\n"
					+ "STAGE_NUMBERS=(1 2 3)\n"
					+ "STAGE_INDEX=0\n"
					+ ". \"$SCRIPT_DIR/functions.bash\"\n"
					+ "trap 'exitJobscript $?' EXIT\n"
					+ "initSandbox\n"
					+ "initWorkspaceVariables\n"
					+ body;
			File file = new File(root.toFile(), "run-" + System.nanoTime() + ".sh");
			Files.writeString(file.toPath(), script);
			assertEquals("generated script must parse", 0, exec(Bash.PATH, "-n", file.getAbsolutePath()).exit);
			lastOutput = exec(Bash.PATH, file.getAbsolutePath()).out;
		}

		int status() throws Exception {
			return field("status");
		}

		int stage() throws Exception {
			return field("stageNumber");
		}

		private int field(String name) throws Exception {
			Path status = root.resolve("out").resolve(String.valueOf(PAIR_ID)).resolve("status.json");
			assertTrue("the pair must report a status:\n" + lastOutput, Files.exists(status));
			Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(Files.readString(status));
			assertTrue(name + " must be recorded:\n" + Files.readString(status), m.find());
			return Integer.parseInt(m.group(1));
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
