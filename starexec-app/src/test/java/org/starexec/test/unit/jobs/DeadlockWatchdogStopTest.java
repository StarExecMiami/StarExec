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
import static org.junit.Assert.assertTrue;

/**
 * {@code stopDeadlockWatchdog} in the shipped helper (B7).
 *
 * <p>The watchdog now starts before the pre-processor and is stopped explicitly once a stage's
 * output is copied, and again from the EXIT trap. On a pair's last stage both calls happen, so
 * the second one must not re-signal the PID the first one already used: by then the OS may have
 * handed it to an unrelated process. Each case runs the real helper under its own
 * {@code set -euo pipefail}.
 */
public class DeadlockWatchdogStopTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** Before the first watchdog of the script's life starts, the variable is unset. */
	@Test
	public void isSafeBeforeAnyWatchdogStarted() throws Exception {
		Result r = runWithHelper(
				"unset KILL_DEADLOCKED_JOB_PAIR_PID\n"
				+ "stopDeadlockWatchdog\n"
				+ "echo T-COMPLETED\n");

		assertEquals("must not abort under set -u:\n" + r.out, 0, r.exit);
		assertTrue(r.out, r.out.contains("T-COMPLETED"));
	}

	/** A running watchdog is terminated and its PID is forgotten. */
	@Test
	public void stopsARunningWatchdogAndClearsItsPid() throws Exception {
		Result r = runWithHelper(
				"sleep 300 &\n"
				+ "KILL_DEADLOCKED_JOB_PAIR_PID=$!\n"
				+ "W=$KILL_DEADLOCKED_JOB_PAIR_PID\n"
				+ "stopDeadlockWatchdog\n"
				+ "wait \"$W\" 2>/dev/null || true\n"
				+ "if kill -0 \"$W\" 2>/dev/null; then echo T-ALIVE; kill \"$W\"; else echo T-STOPPED; fi\n"
				+ "echo \"T-PID|${KILL_DEADLOCKED_JOB_PAIR_PID}|\"\n");

		assertEquals(r.out, 0, r.exit);
		assertTrue("the watchdog must be stopped:\n" + r.out, r.out.contains("T-STOPPED"));
		assertTrue("the PID must be cleared:\n" + r.out, r.out.contains("T-PID||"));
	}

	/**
	 * The last-stage double call: the second call must send no signal at all. PID reuse cannot
	 * be forced from a test, so every {@code kill} the second call issues is recorded instead;
	 * any recorded signal would land on whatever process holds the stale PID by then.
	 */
	@Test
	public void secondCallSendsNoSignal() throws Exception {
		Result r = runWithHelper(
				"sleep 300 &\n"
				+ "KILL_DEADLOCKED_JOB_PAIR_PID=$!\n"
				+ "W=$KILL_DEADLOCKED_JOB_PAIR_PID\n"
				+ "stopDeadlockWatchdog\n"
				+ "wait \"$W\" 2>/dev/null || true\n"
				+ "kill() { echo \"T-SIGNALED|$*\"; }\n"
				+ "stopDeadlockWatchdog\n"
				+ "unset -f kill\n"
				+ "echo T-COMPLETED\n");

		assertEquals(r.out, 0, r.exit);
		assertTrue(r.out, r.out.contains("T-COMPLETED"));
		assertTrue("the second call must not signal the stale PID:\n" + r.out,
				!r.out.contains("T-SIGNALED"));
	}

	// ----------------------------------------------------------------- harness

	private Result runWithHelper(String body) throws Exception {
		Path dir = folder.newFolder("wd-" + System.nanoTime()).toPath();
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
		s.append(body);

		File script = new File(dir.toFile(), "generated.sh");
		Files.writeString(script.toPath(), s.toString());

		ProcessBuilder pb = new ProcessBuilder(Bash.PATH, script.getAbsolutePath());
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
