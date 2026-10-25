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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * When a build job's solver is copied back, relative to the statuses that say the pair is done.
 *
 * <p>The server records a build's outcome from the pair's status, and the monitors act on a terminal
 * status as soon as they read it: LocalBackend kills a pair's process about a second after it. So
 * COMPLETE must mean the solver is already back. The jobscript used to write COMPLETE for the stage and
 * the pair first and copy the solver afterwards, and a copy that failed then left a pair that had been
 * read as complete. These cases run the jobscript's own branch, from its success check to the result
 * claim, with the copy replaced by a marker and every status write recorded.
 */
public class SolverBuildStatusOrderTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");
	private static final String COMPLETE = "7";

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aBuildJobCopiesItsSolverBackBeforeAnyStatusSaysItIsComplete() throws Exception {
		Result r = run(true, "");

		assertEquals("the branch must finish:\n" + r.out, 0, r.exit);
		int copy = r.out.indexOf("T-COPY");
		assertTrue("a build job copies its solver back:\n" + r.out, copy >= 0);
		assertFalse("no COMPLETE may be written before the copy:\n" + r.out,
				completeWritten(r.out.substring(0, copy)));
		assertTrue("the stage and the pair are still reported complete after it:\n" + r.out,
				completeWritten(r.out.substring(copy)));
	}

	@Test
	public void aFailedCopyWritesNoComplete() throws Exception {
		Result r = run(true, "return 1");

		assertNotEquals("a failed copy must end the script under set -e:\n" + r.out, 0, r.exit);
		assertTrue(r.out, r.out.contains("T-COPY"));
		assertFalse("the pair must not be reported complete:\n" + r.out, completeWritten(r.out));
		assertFalse("nor its result claimed:\n" + r.out, r.out.contains("T-CLAIM"));
	}

	@Test
	public void anOrdinaryJobCopiesNothingAndIsStillReportedComplete() throws Exception {
		Result r = run(false, "");

		assertEquals("the branch must finish:\n" + r.out, 0, r.exit);
		assertFalse(r.out, r.out.contains("T-COPY"));
		assertTrue(r.out, completeWritten(r.out));
		assertTrue(r.out, r.out.contains("T-CLAIM"));
	}

	/** Whether {@code out} records a write of COMPLETE, for the pair or for a stage. */
	private static boolean completeWritten(String out) {
		for (String line : out.split("\n")) {
			if (line.startsWith("T-WRITE|" + COMPLETE + "|")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The jobscript's stage-loop code from the success check to the end of the result claim, as it stands
	 * in the template.
	 */
	private static String successBranchToClaim() throws Exception {
		List<String> loop = JobscriptBlocks.stageLoop();
		int start = -1;
		for (int i = 0; i < loop.size(); i++) {
			if (loop.get(i).strip().equals("if [ \"$ERROR_DETECTED\" = false ]; then")) {
				start = i;
				break;
			}
		}
		assertTrue("the stage loop must check for errors", start >= 0);
		int claim = JobscriptBlocks.indexOfLineStartingWith(loop.subList(start, loop.size()), "claimResult");
		assertTrue("the success branch must be followed by the result claim", claim > 0);
		claim += start;
		String line = loop.get(start);
		String fi = line.substring(0, line.indexOf("if ")) + "fi";
		for (int i = claim + 1; i < loop.size(); i++) {
			if (loop.get(i).equals(fi)) {
				return String.join("\n", loop.subList(start, i + 1));
			}
		}
		throw new AssertionError("the result claim must end");
	}

	/**
	 * Runs that code for a one-stage pair whose solver ran without error. The copy prints T-COPY and then
	 * runs {@code copyBody}; every status write prints T-WRITE|status|stage; the claim prints T-CLAIM.
	 */
	private Result run(boolean buildJob, String copyBody) throws Exception {
		Path dir = folder.newFolder("order-" + System.nanoTime()).toPath();
		Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"));
		Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"));
		String script = "export SCRIPT_DIR=\"" + dir + "\"\n"
				+ "export STAREXEC_OUTPUT_DIR=\"" + dir.resolve("out") + "\"\n"
				+ "export CONTAINER_MODE=true\n"
				+ "export PAIR_ID=41\n"
				+ "export SHARED_DIR=\"" + dir.resolve("shared") + "\"\n"
				+ "export WORKING_DIR_BASE=\"" + dir.resolve("work") + "\"\n"
				+ "export BENCH_PATH=\"$(printf '/bench/starexec_build' | base64 -w0)\"\n"
				+ "export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n"
				+ "SOLVER_PATHS=()\n"
				+ ". \"$SCRIPT_DIR/functions.bash\"\n"
				+ "STAGE_NUMBERS=(1)\nSTAGE_INDEX=0\nNUM_STAGES=1\nHOSTNAME=order-test\n"
				+ "BUILD_JOB=" + buildJob + "\n"
				+ "ERROR_DETECTED=false\n"
				// As sendNode leaves a running pair.
				+ "sendStatus \"$STATUS_RUNNING\"\n"
				+ "sendStageStatus \"$STATUS_RUNNING\" 1\n"
				+ "eval \"original_$(declare -f containerWriteStatus)\"\n"
				+ "containerWriteStatus() {\n"
				+ "\techo \"T-WRITE|$1|${2:-0}\"\n"
				+ "\toriginal_containerWriteStatus \"$@\"\n"
				+ "}\n"
				+ "copySolverBack() {\n\techo T-COPY\n\t" + (copyBody.isEmpty() ? ":" : copyBody) + "\n}\n"
				+ "claimResult() {\n\techo T-CLAIM\n\tSTATUS_SENT=true\n}\n"
				// The branch ends in a break, which needs a loop around it, as in the jobscript.
				+ "for _stage in 1; do\n"
				+ successBranchToClaim() + "\n"
				+ "done\n"
				+ "echo T-DONE\n";
		File file = new File(dir.toFile(), "generated.sh");
		Files.writeString(file.toPath(), script);
		assertEquals("generated script must parse", 0, exec(dir, Bash.PATH, "-n", file.getAbsolutePath()).exit);
		return exec(dir, Bash.PATH, file.getAbsolutePath());
	}

	/** Output goes to a file rather than a pipe, and the wait is bounded. */
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

	private static final class Result {
		final int exit;
		final String out;

		Result(int exit, String out) {
			this.exit = exit;
			this.out = out;
		}
	}
}
