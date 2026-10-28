package org.starexec.test.unit.jobs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Blocks of the shipped jobscript, read from the file so that tests run the jobscript's own code rather than a
 * copy that could drift from it, and shell code that drives the deadlock watchdog those blocks arm.
 */
final class JobscriptBlocks {

	private static final Path JOBSCRIPT = Path.of("src/main/java/org/starexec/config/sge/jobscript");

	private JobscriptBlocks() {
	}

	/** The body of the jobscript's stage loop. */
	static List<String> stageLoop() throws IOException {
		List<String> lines = Files.readAllLines(JOBSCRIPT);
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

	/** The index of the first line whose text, stripped, starts with {@code prefix}; -1 if there is none. */
	static int indexOfLineStartingWith(List<String> lines, String prefix) {
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).strip().startsWith(prefix)) {
				return i;
			}
		}
		return -1;
	}

	/** The lines that arm a stage's deadlock watchdog and record its PID. */
	static String arming() throws IOException {
		List<String> loop = stageLoop();
		int arm = armingIndex(loop);
		return String.join("\n", loop.subList(arm, pidIndex(loop, arm) + 1));
	}

	/**
	 * The lines that arm a stage's deadlock watchdog, from removing the record an earlier watchdog may have left to
	 * recording the new watchdog's PID.
	 */
	static String clearingAndArming() throws IOException {
		List<String> loop = stageLoop();
		int arm = armingIndex(loop);
		int clear = indexOfLineStartingWith(loop, "rm -f \"$(deadlockWatchdogFiredFile)\"");
		assertTrue("the jobscript must remove an earlier watchdog's record before it arms the next one",
				clear >= 0 && clear < arm);
		return String.join("\n", loop.subList(clear, pidIndex(loop, arm) + 1));
	}

	private static int armingIndex(List<String> loop) {
		int arm = indexOfLineStartingWith(loop, "killDeadlockedJobPair ");
		assertTrue("the stage loop must arm the watchdog", arm >= 0);
		assertEquals("the stage loop must arm it once", -1,
				indexOfLineStartingWith(loop.subList(arm + 1, loop.size()), "killDeadlockedJobPair "));
		return arm;
	}

	private static int pidIndex(List<String> loop, int arm) {
		int pid = indexOfLineStartingWith(loop, "KILL_DEADLOCKED_JOB_PAIR_PID=$!");
		assertTrue("the jobscript must record the watchdog's PID right after arming it", pid > arm && pid - arm <= 3);
		return pid;
	}

	/**
	 * The check after the solver that decides whether the stage's deadlock watchdog fired: from
	 * {@code JOB_PAIR_DEADLOCKED=false} to the end of the if statement that follows it.
	 */
	static String deadlockCheck() throws IOException {
		List<String> loop = stageLoop();
		int start = indexOfLineStartingWith(loop, "JOB_PAIR_DEADLOCKED=false");
		assertTrue("the deadlock check must exist", start >= 0 && start + 1 < loop.size());
		String ifLine = loop.get(start + 1);
		assertTrue("an if statement must follow JOB_PAIR_DEADLOCKED=false: " + ifLine,
				ifLine.strip().startsWith("if "));
		String fi = ifLine.substring(0, ifLine.indexOf("if ")) + "fi";
		int end = -1;
		for (int i = start + 2; i < loop.size(); i++) {
			if (loop.get(i).equals(fi)) {
				end = i;
				break;
			}
		}
		assertTrue("the deadlock check must end", end > start);
		return String.join("\n", loop.subList(start, end + 1));
	}

	/**
	 * Shell code that ends the armed watchdog before it can fire, with {@code signal}, the way the kernel (KILL)
	 * or a stray signal (TERM) would, and waits for it to be gone.
	 *
	 * <p>It waits for the watchdog's sleep first, so the watchdog is past its set-up and the TERM trap is in
	 * place. That sleep is the watchdog's own child: SIGKILL leaves it running, reparented, so it is killed
	 * here too, and its PID goes to {@code $SCRIPT_DIR/pids} for a test's clean-up as well. Prints
	 * {@code T-NO-WATCHDOG-SLEEP} if the sleep never appeared and {@code T-WATCHDOG-ALREADY-GONE} if the
	 * watchdog could not be signalled; either means the case proves nothing.
	 */
	static String endWatchdogBeforeItFires(String signal) {
		return "WATCHDOG_SLEEP=\n"
				+ "for _ in $(seq 200); do\n"
				+ "  for c in $(cat \"/proc/$KILL_DEADLOCKED_JOB_PAIR_PID/task/$KILL_DEADLOCKED_JOB_PAIR_PID/children\""
				+ " 2>/dev/null); do\n"
				+ "    comm=$(cat \"/proc/$c/comm\" 2>/dev/null) || comm=\n"
				+ "    if [ \"$comm\" = sleep ]; then WATCHDOG_SLEEP=$c; fi\n"
				+ "  done\n"
				+ "  [ -n \"$WATCHDOG_SLEEP\" ] && break\n"
				+ "  sleep 0.05\n"
				+ "done\n"
				+ "echo \"$WATCHDOG_SLEEP\" >> \"$SCRIPT_DIR/pids\"\n"
				+ "[ -n \"$WATCHDOG_SLEEP\" ] || echo T-NO-WATCHDOG-SLEEP\n"
				+ "kill -" + signal + " \"$KILL_DEADLOCKED_JOB_PAIR_PID\" || echo T-WATCHDOG-ALREADY-GONE\n"
				+ "wait \"$KILL_DEADLOCKED_JOB_PAIR_PID\" 2>/dev/null || true\n"
				+ "if [ -n \"$WATCHDOG_SLEEP\" ]; then kill -KILL \"$WATCHDOG_SLEEP\" 2>/dev/null || true; fi\n";
	}

	/** Fails if {@link #endWatchdogBeforeItFires} reported that its case proves nothing. */
	static void assertEndedBeforeItFired(String out) {
		assertFalse("the watchdog must have started its sleep, or this proves nothing:\n" + out,
				out.contains("T-NO-WATCHDOG-SLEEP"));
		assertFalse("the watchdog must still be running when it is ended, or this proves nothing:\n" + out,
				out.contains("T-WATCHDOG-ALREADY-GONE"));
	}
}
