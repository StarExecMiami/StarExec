package org.starexec.test.unit.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.starexec.test.TemporaryFolders.newFolder;

/**
 * Whether the jobscript reads a stage's deadlock watchdog as having fired.
 *
 * <p>The check after the solver asked only whether the watchdog was still running, and read one that was gone as
 * one that had fired. A watchdog can end without firing: the kernel can kill it when its container runs out of
 * memory, a stray signal can stop it, and an error of its own can end it before it sleeps, as an empty sandbox
 * user could before f294478ac. The stage was then recorded as a wall-clock timeout even when its solver finished in
 * time, and that verdict comes before the memory checks. The watchdog now leaves a record when it fires, before it
 * kills anything, and the check reads the record.
 *
 * <p>The cases run the jobscript's own lines ({@link JobscriptBlocks}) against the shipped functions.bash, from a
 * script file, the way the jobscript runs.
 */
public class DeadlockWatchdogFiredTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	/** A budget the watchdog cannot sleep out while a case runs. */
	private static final String LONG_BUDGET = "STAGE_WATCHDOG_TIMEOUT=3600\nJOB_PAIR_EXTRA_TIME=120\nSANDBOX_PARAM=sandbox\n";

	/** A budget the watchdog sleeps out at once, so it fires. */
	private static final String NO_BUDGET = "STAGE_WATCHDOG_TIMEOUT=0\nJOB_PAIR_EXTRA_TIME=0\nSANDBOX_PARAM=sandbox\n";

	private static final String REPORT = "echo \"T-DEADLOCKED=$JOB_PAIR_DEADLOCKED\"\n";

	@TempDir
	public File folder;

	/** A watchdog that slept out the stage's budget and killed its tree: the stage was deadlocked. */
	@Test
	public void aWatchdogThatFiredIsADeadlock() throws Exception {
		Result r = runWithHelper(NO_BUDGET
				+ JobscriptBlocks.arming() + "\n"
				+ "wait \"$KILL_DEADLOCKED_JOB_PAIR_PID\" 2>/dev/null || true\n"
				+ JobscriptBlocks.deadlockCheck() + "\n"
				+ REPORT);

		assertEquals(0, r.exit, r.out);
		assertTrue(r.out.contains("T-DEADLOCKED=true"), "a watchdog that fired must be read as a deadlock:\n" + r.out);
	}

	/**
	 * The defect: a watchdog the kernel killed before it fired, as it can when the container runs out of memory,
	 * was read as a deadlock, and the stage recorded as a wall-clock timeout.
	 */
	@Test
	public void aWatchdogKilledBeforeItFiredIsNoDeadlock() throws Exception {
		Result r = runWithHelper(LONG_BUDGET
				+ JobscriptBlocks.arming() + "\n"
				+ JobscriptBlocks.endWatchdogBeforeItFires("KILL")
				+ JobscriptBlocks.deadlockCheck() + "\n"
				+ REPORT);

		assertEquals(0, r.exit, r.out);
		JobscriptBlocks.assertEndedBeforeItFired(r.out);
		assertTrue(r.out.contains("T-DEADLOCKED=false"), "a watchdog killed before it fired is no deadlock:\n" + r.out);
		assertTrue(r.out.contains("The deadlock watchdog ended without firing"),
				"and the pair log must say it ended without firing:\n" + r.out);
	}

	/** The same for a watchdog a stray TERM stopped: its trap ends it without firing. */
	@Test
	public void aWatchdogStoppedBeforeItFiredIsNoDeadlock() throws Exception {
		Result r = runWithHelper(LONG_BUDGET
				+ JobscriptBlocks.arming() + "\n"
				+ JobscriptBlocks.endWatchdogBeforeItFires("TERM")
				+ JobscriptBlocks.deadlockCheck() + "\n"
				+ REPORT);

		assertEquals(0, r.exit, r.out);
		JobscriptBlocks.assertEndedBeforeItFired(r.out);
		assertTrue(r.out.contains("T-DEADLOCKED=false"), "a watchdog stopped before it fired is no deadlock:\n" + r.out);
	}

	/**
	 * The record is written before the watchdog kills anything. Killing the tree ends the solver, so the
	 * jobscript can reach its check while the watchdog is still at it; that check found the watchdog running and
	 * read it as not having fired. The kill is held here until the check has run.
	 */
	@Test
	public void aWatchdogStillKillingHasFired() throws Exception {
		Result r = runWithHelper(NO_BUDGET
				+ "reapJobPairProcessTree() {\n"
				+ "  : > \"$SCRIPT_DIR/reaping\"\n"
				+ "  for _ in $(seq 400); do [ -e \"$SCRIPT_DIR/checked\" ] && return 0; sleep 0.05; done\n"
				+ "}\n"
				+ JobscriptBlocks.arming() + "\n"
				+ "for _ in $(seq 400); do [ -e \"$SCRIPT_DIR/reaping\" ] && break; sleep 0.05; done\n"
				+ "[ -e \"$SCRIPT_DIR/reaping\" ] || echo T-NEVER-KILLED\n"
				+ JobscriptBlocks.deadlockCheck() + "\n"
				+ REPORT
				+ "if isOwnLiveChild \"$KILL_DEADLOCKED_JOB_PAIR_PID\"; then echo T-WATCHDOG-STILL-KILLING; fi\n"
				+ ": > \"$SCRIPT_DIR/checked\"\n"
				+ "wait \"$KILL_DEADLOCKED_JOB_PAIR_PID\" 2>/dev/null || true\n");

		assertEquals(0, r.exit, r.out);
		assertFalse(r.out.contains("T-NEVER-KILLED"), "the watchdog must fire:\n" + r.out);
		assertTrue(r.out.contains("T-WATCHDOG-STILL-KILLING"),
				"the check must run while the watchdog is still killing, or this proves nothing:\n" + r.out);
		assertTrue(r.out.contains("T-DEADLOCKED=true"), "a watchdog that is killing has fired:\n" + r.out);
	}

	/** An earlier stage's watchdog that fired leaves its record; the next stage's check must not read it. */
	@Test
	public void anEarlierStagesRecordIsNotThisStages() throws Exception {
		Result r = runWithHelper(LONG_BUDGET
				+ ": > \"$(deadlockWatchdogFiredFile)\"\n"
				+ JobscriptBlocks.clearingAndArming() + "\n"
				+ JobscriptBlocks.endWatchdogBeforeItFires("TERM")
				+ JobscriptBlocks.deadlockCheck() + "\n"
				+ REPORT);

		assertEquals(0, r.exit, r.out);
		JobscriptBlocks.assertEndedBeforeItFired(r.out);
		assertTrue(r.out.contains("T-DEADLOCKED=false"), "this stage's watchdog did not fire:\n" + r.out);
	}

	/** A record that cannot be removed ends the pair before the stage starts, rather than be read as the stage's. */
	@Test
	public void aRecordThatCannotBeRemovedEndsThePair() throws Exception {
		Result r = runWithHelper(LONG_BUDGET
				+ "mkdir -p \"$(deadlockWatchdogFiredFile)/in-the-way\"\n"
				+ JobscriptBlocks.clearingAndArming() + "\n"
				+ "echo T-ARMED\n"
				+ "stopDeadlockWatchdog\n");

		assertNotEquals(0, r.exit, "the pair must end:\n" + r.out);
		assertFalse(r.out.contains("T-ARMED"), "before the stage's watchdog is armed:\n" + r.out);
	}

	/**
	 * A record that cannot be written must not stop the kill: the working directory here is a regular file. The
	 * hung child and the grandchild it backgrounded must both be gone, as when the record is written.
	 */
	@Test
	public void aRecordThatCannotBeWrittenDoesNotStopTheKill() throws Exception {
		Result r = runWithHelper("STAGE_WATCHDOG_TIMEOUT=1\nJOB_PAIR_EXTRA_TIME=0\nSANDBOX_PARAM=sandbox\n"
				+ "touch \"$SCRIPT_DIR/blocked\"\n"
				+ "WORKING_DIR=\"$SCRIPT_DIR/blocked\"\n"
				+ JobscriptBlocks.arming() + "\n"
				+ "sh -c 'sleep 99999 & echo \"$!\" > \"$SCRIPT_DIR/gc\"; exec sleep 20' || echo T-FOREGROUND-ENDED\n"
				+ "GC=$(cat \"$SCRIPT_DIR/gc\")\n"
				+ "echo \"$GC\" >> \"$SCRIPT_DIR/pids\"\n"
				+ "wait \"$KILL_DEADLOCKED_JOB_PAIR_PID\" 2>/dev/null || true\n"
				+ "sleep 0.2\n"
				+ "if kill -0 \"$GC\" 2>/dev/null; then echo T-GRANDCHILD-ALIVE; else echo T-GRANDCHILD-GONE; fi\n");

		assertEquals(0, r.exit, r.out);
		assertTrue(r.out.contains("T-FOREGROUND-ENDED"), "the hung child must be killed:\n" + r.out);
		assertTrue(r.out.contains("T-GRANDCHILD-GONE"), "its grandchild must be killed too:\n" + r.out);
		assertTrue(r.out.contains("could not record that it fired"),
				"the pair log must say the record could not be written:\n" + r.out);
	}

	// ----------------------------------------------------------------- harness

	private final List<Path> scriptDirs = new ArrayList<>();

	/** Every PID a case records in its pids file is killed here, pass or fail: a watchdog's sleep outlives it. */
	@AfterEach
	public void killRecordedProcesses() throws Exception {
		for (Path dir : scriptDirs) {
			Path pids = dir.resolve("pids");
			if (!Files.exists(pids)) {
				continue;
			}
			for (String line : Files.readAllLines(pids)) {
				String pid = line.trim();
				if (pid.matches("[0-9]+")) {
					ProcessHandle.of(Long.parseLong(pid)).ifPresent(ProcessHandle::destroyForcibly);
				}
			}
		}
	}

	/** Runs {@code body} after sourcing the shipped functions.bash, in container mode, as DeadlockWatchdogReapTest does. */
	private Result runWithHelper(String body) throws Exception {
		Path dir = newFolder(folder, "fired-" + System.nanoTime()).toPath();
		scriptDirs.add(dir);
		Files.copy(SGE.resolve("functions.bash"), dir.resolve("functions.bash"));
		Files.copy(SGE.resolve("status_codes.bash"), dir.resolve("status_codes.bash"));

		StringBuilder s = new StringBuilder();
		s.append("export SCRIPT_DIR=\"").append(dir).append("\"\n");
		s.append("export PAIR_ID=1\n");
		s.append("export WORKING_DIR_BASE=\"$SCRIPT_DIR/work\"\n");
		s.append("export SHARED_DIR=\"$SCRIPT_DIR/shared\"\n");
		s.append("export STAREXEC_OUTPUT_DIR=\"$SCRIPT_DIR/out\"\n");
		s.append("export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n");
		s.append("export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n");
		s.append(". \"$SCRIPT_DIR/functions.bash\"\n");
		s.append("CONTAINER_MODE=true\nBUILD_JOB=false\nWORKING_DIR=\"$SCRIPT_DIR\"\n");
		s.append(body);

		File script = new File(dir.toFile(), "generated.sh");
		Files.writeString(script.toPath(), s.toString());

		// Output goes to a file, not a pipe: a process left running would hold a pipe open.
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
		assertTrue(finished, "command must not hang:\n" + out);
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
