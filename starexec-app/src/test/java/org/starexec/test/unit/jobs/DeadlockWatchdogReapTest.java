package org.starexec.test.unit.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the container-mode watchdog actually kills when it fires (#254).
 *
 * <p>It used to run {@code killall -SIGKILL --user <sandbox user>}. Every image ships busybox
 * killall, which has no {@code --user}, and in container mode nothing runs as the sandbox user,
 * so it killed nothing. It now reaps the jobscript's own process tree. These cases run the real
 * helper from a script file, the way the jobscript runs, because a bash subshell is recognised
 * by carrying the script's own command line.
 */
public class DeadlockWatchdogReapTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/**
	 * A hung child and the grandchild it backgrounded are both gone once the watchdog fires, and
	 * the jobscript itself carries on to handle the deadlock. The foreground sleep is bounded so
	 * that a watchdog that kills nothing fails the assertions instead of hanging the build.
	 */
	@Test
	public void reapsTheHungTreeAndLetsTheJobscriptContinue() throws Exception {
		Result r = runWithHelper(
				"killDeadlockedJobPair 1 0 starexec1 &\n"
				+ "WATCHDOG=$!\n"
				+ "sh -c 'sleep 99999 & echo \"$!\" > \"$SCRIPT_DIR/gc\"; exec sleep 20' || echo T-FOREGROUND-ENDED\n"
				+ "GC=$(cat \"$SCRIPT_DIR/gc\")\n"
				+ "wait \"$WATCHDOG\" 2>/dev/null || true\n"
				+ "sleep 0.2\n"
				+ "if kill -0 \"$GC\" 2>/dev/null; then echo T-GRANDCHILD-ALIVE; kill -9 \"$GC\"; else echo T-GRANDCHILD-GONE; fi\n"
				+ "echo T-JOBSCRIPT-CONTINUED\n");

		assertEquals(r.out, 0, r.exit);
		assertTrue("the hung child must be killed:\n" + r.out, r.out.contains("T-FOREGROUND-ENDED"));
		assertTrue("its grandchild must be killed too:\n" + r.out, r.out.contains("T-GRANDCHILD-GONE"));
		assertTrue("the jobscript itself must survive:\n" + r.out, r.out.contains("T-JOBSCRIPT-CONTINUED"));
	}

	/**
	 * The jobscript's own bash subshells are not targets: they carry the script's command line.
	 * This one waits on a fifo with the read builtin, so it has no child that could be reaped.
	 */
	@Test
	public void sparesTheJobscriptsOwnSubshells() throws Exception {
		Result r = runWithHelper(
				"mkfifo \"$SCRIPT_DIR/f\"\n"
				+ "spare() { while true; do read -r -t 0.5 _ <> \"$SCRIPT_DIR/f\" || true; done; }\n"
				+ "spare &\n"
				+ "SPARE=$!\n"
				+ "killDeadlockedJobPair 1 0 starexec1 &\n"
				+ "WATCHDOG=$!\n"
				+ "sleep 20 || true\n"
				+ "wait \"$WATCHDOG\" 2>/dev/null || true\n"
				+ "if kill -0 \"$SPARE\" 2>/dev/null; then echo T-SUBSHELL-ALIVE; kill \"$SPARE\"; else echo T-SUBSHELL-KILLED; fi\n");

		assertEquals(r.out, 0, r.exit);
		assertTrue("the jobscript's subshell must survive:\n" + r.out, r.out.contains("T-SUBSHELL-ALIVE"));
		assertFalse(r.out, r.out.contains("T-SUBSHELL-KILLED"));
	}

	/**
	 * The container log lives on the output volume, which can fail (full, NFS outage). Under
	 * {@code set -e} a failed log line used to end the watchdog between freezing the tree and
	 * killing it, leaving the hung processes stopped for good. Here the log's directory cannot
	 * be created, because a regular file sits where it should be.
	 */
	@Test
	public void killsEvenWhenTheLogCannotBeWritten() throws Exception {
		Result r = runWithHelper(
				"touch \"$SCRIPT_DIR/blocked\"\n"
				+ "CONTAINER_LOG_FILE=\"$SCRIPT_DIR/blocked/log/1.txt\"\n"
				+ "killDeadlockedJobPair 1 0 starexec1 &\n"
				+ "WATCHDOG=$!\n"
				+ "sh -c 'sleep 99999 & echo \"$!\" > \"$SCRIPT_DIR/gc\"; exec sleep 20' || echo T-FOREGROUND-ENDED\n"
				+ "GC=$(cat \"$SCRIPT_DIR/gc\")\n"
				+ "wait \"$WATCHDOG\" 2>/dev/null || true\n"
				+ "sleep 0.2\n"
				+ "if kill -0 \"$GC\" 2>/dev/null; then echo T-GRANDCHILD-ALIVE; kill -9 \"$GC\"; else echo T-GRANDCHILD-GONE; fi\n");

		assertEquals(r.out, 0, r.exit);
		assertTrue("the hung child must be killed, not left stopped:\n" + r.out, r.out.contains("T-FOREGROUND-ENDED"));
		assertTrue("its grandchild must be killed too:\n" + r.out, r.out.contains("T-GRANDCHILD-GONE"));
	}

	/**
	 * The jobscript arms the watchdog with its own line, run here verbatim with an empty sandbox
	 * user. Unquoted, the empty word vanished, so under {@code set -u} the watchdog's
	 * {@code CURRENT_USER=$3} aborted it before it slept: a pair with no watchdog, silently.
	 */
	@Test
	public void armsAndReapsWithAnEmptySandboxUser() throws Exception {
		Result r = runWithHelper(
				"SANDBOX_PARAM=\nSTAGE_WATCHDOG_TIMEOUT=1\nJOB_PAIR_EXTRA_TIME=0\n"
				+ armingLine() + "\n"
				+ "WATCHDOG=$!\n"
				+ "sh -c 'sleep 99999 & echo \"$!\" > \"$SCRIPT_DIR/gc\"; exec sleep 20' || echo T-FOREGROUND-ENDED\n"
				+ "GC=$(cat \"$SCRIPT_DIR/gc\")\n"
				+ "wait \"$WATCHDOG\" 2>/dev/null || true\n"
				+ "sleep 0.2\n"
				+ "if kill -0 \"$GC\" 2>/dev/null; then echo T-GRANDCHILD-ALIVE; kill -9 \"$GC\"; else echo T-GRANDCHILD-GONE; fi\n");

		assertEquals(r.out, 0, r.exit);
		assertTrue("the watchdog must arm and kill the hung child:\n" + r.out, r.out.contains("T-FOREGROUND-ENDED"));
		assertTrue("its grandchild must be killed too:\n" + r.out, r.out.contains("T-GRANDCHILD-GONE"));
	}

	/** The jobscript's own arming line, so this test cannot drift from what production runs. */
	private static String armingLine() throws Exception {
		return Files.readAllLines(SGE.resolve("jobscript")).stream()
				.map(String::trim)
				.filter(l -> l.startsWith("killDeadlockedJobPair "))
				.reduce((a, b) -> { throw new AssertionError("more than one arming line"); })
				.orElseThrow(() -> new AssertionError("jobscript no longer arms the watchdog"));
	}

	// ----------------------------------------------------------------- harness

	private Result runWithHelper(String body) throws Exception {
		Path dir = folder.newFolder("reap-" + System.nanoTime()).toPath();
		Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"));
		Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"));

		// The same load-time environment JobscriptArrayCardinalityTest sources the helper with.
		StringBuilder s = new StringBuilder();
		s.append("export SCRIPT_DIR=\"").append(dir).append("\"\n");
		s.append("export PAIR_ID=1\n");
		s.append("export WORKING_DIR_BASE=\"$SCRIPT_DIR/work\"\n");
		s.append("export SHARED_DIR=\"$SCRIPT_DIR/shared\"\n");
		s.append("export STAREXEC_OUTPUT_DIR=\"$SCRIPT_DIR/out\"\n");
		s.append("export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n");
		s.append("export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n");
		s.append(". \"$SCRIPT_DIR/functions.bash\"\n");
		// Set after sourcing: container mode is read when the watchdog fires, not at load time.
		s.append("CONTAINER_MODE=true\nBUILD_JOB=false\nWORKING_DIR=\"$SCRIPT_DIR\"\n");
		s.append(body);

		File script = new File(dir.toFile(), "generated.sh");
		Files.writeString(script.toPath(), s.toString());

		// Output goes to a file, not a pipe: a regression here leaves processes stopped, and
		// they would hold a pipe open so that reading it never returns. Bounded instead, and
		// the stopped tree is killed so it does not outlive the test.
		File log = new File(dir.toFile(), "out.txt");
		ProcessBuilder pb = new ProcessBuilder(Bash.PATH, script.getAbsolutePath());
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
