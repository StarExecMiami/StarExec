package org.starexec.test.unit.jobs;

import org.junit.After;
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

	/**
	 * The jobscript stops the watchdog with SIGTERM when the stage ends. If that lands after the
	 * watchdog has frozen part of the tree but before it has killed it, the frozen processes stay
	 * stopped for good. The hook sends the TERM stopDeadlockWatchdog sends, to the watchdog,
	 * right after the first SIGSTOP; it sends it directly because stopDeadlockWatchdog now waits
	 * for the watchdog, which the watchdog cannot do for itself. The victim runs in the
	 * background so a regression fails here instead of hanging on a stopped foreground child.
	 */
	@Test
	public void aStopMidFreezeLeavesNothingStopped() throws Exception {
		Result r = runWithHelper(
				"kill() {\n"
				+ "  builtin kill \"$@\"; local rc=$?\n"
				+ "  if [[ ${1:-} == -STOP && ! -e \"$SCRIPT_DIR/termed\" ]]; then\n"
				+ "    : > \"$SCRIPT_DIR/termed\"\n"
				+ "    builtin kill -TERM \"$BASHPID\"\n"
				+ "  fi\n"
				+ "  return $rc\n"
				+ "}\n"
				+ "sh -c 'sleep 99999 & echo \"$!\" > \"$SCRIPT_DIR/gc\"; exec sleep 99999' &\n"
				+ "VICTIM=$!\n"
				+ "echo \"$VICTIM\" >> \"$SCRIPT_DIR/pids\"\n"
				+ "for _ in $(seq 200); do [ -s \"$SCRIPT_DIR/gc\" ] && break; sleep 0.05; done\n"
				+ "GC=$(cat \"$SCRIPT_DIR/gc\")\n"
				+ "echo \"$GC\" >> \"$SCRIPT_DIR/pids\"\n"
				+ "killDeadlockedJobPair 0 0 starexec1 &\n"
				+ "WATCHDOG=$!\n"
				+ "wait \"$WATCHDOG\" 2>/dev/null || true\n"
				+ "if [ -e \"$SCRIPT_DIR/termed\" ]; then echo T-TERM-DELIVERED; fi\n"
				+ "sleep 0.3\n"
				+ "for pid in \"$VICTIM\" \"$GC\"; do\n"
				+ "  st=$(sed 's/.*) //' \"/proc/$pid/stat\" 2>/dev/null | cut -d' ' -f1) || st=\n"
				+ "  if [ \"$st\" = T ]; then echo \"T-STOPPED-$pid\"; fi\n"
				+ "done\n"
				+ "builtin kill -9 \"$VICTIM\" \"$GC\" 2>/dev/null || true\n");

		assertEquals(r.out, 0, r.exit);
		assertTrue("the TERM must have landed mid-freeze:\n" + r.out, r.out.contains("T-TERM-DELIVERED"));
		assertFalse("no process may be left stopped:\n" + r.out, r.out.contains("T-STOPPED-"));
	}

	/**
	 * A watchdog stopped mid-reap ignores the TERM and keeps rescanning the script's descendants,
	 * so stopDeadlockWatchdog must not return until it has exited: otherwise work the script
	 * starts next is frozen and killed too. The hook holds the first reap round, for at most two
	 * seconds, until a post-stop child exists; the child is only started once stop returns.
	 */
	@Test
	public void workStartedAfterTheStopSurvivesAReapInProgress() throws Exception {
		Result r = runWithHelper(
				"kill() {\n"
				+ "  builtin kill \"$@\"; local rc=$?\n"
				+ "  if [[ ${1:-} == -STOP && ! -e \"$SCRIPT_DIR/armed\" ]]; then\n"
				+ "    echo \"$BASHPID\" > \"$SCRIPT_DIR/armed\"\n"
				+ "    for _ in $(seq 100); do [ -s \"$SCRIPT_DIR/newchild\" ] && break; sleep 0.02; done\n"
				+ "  fi\n"
				+ "  return $rc\n"
				+ "}\n"
				+ "sh -c 'exec sleep 99999' &\n"
				+ "echo \"$!\" >> \"$SCRIPT_DIR/pids\"\n"
				+ "killDeadlockedJobPair 0 0 starexec1 &\n"
				+ "KILL_DEADLOCKED_JOB_PAIR_PID=$!\n"
				+ "for _ in $(seq 200); do [ -s \"$SCRIPT_DIR/armed\" ] && break; sleep 0.02; done\n"
				+ "stopDeadlockWatchdog\n"
				+ "sleep 99999 &\n"
				+ "NEW=$!\n"
				+ "echo \"$NEW\" >> \"$SCRIPT_DIR/pids\"\n"
				+ "echo \"$NEW\" > \"$SCRIPT_DIR/newchild\"\n"
				+ "sleep 0.5\n"
				+ "st=$(sed 's/.*) //' \"/proc/$NEW/stat\" 2>/dev/null | cut -d' ' -f1) || st=\n"
				+ "if [ \"$st\" = S ]; then echo T-NEW-ALIVE; else echo \"T-NEW-GONE-$st\"; fi\n"
				+ "builtin kill -9 \"$NEW\" 2>/dev/null || true\n");

		assertEquals(r.out, 0, r.exit);
		assertTrue("work started after the stop must survive:\n" + r.out, r.out.contains("T-NEW-ALIVE"));
	}

	/** A sleeping watchdog dies on the TERM itself, so stopping it costs about nothing. */
	@Test
	public void stoppingASleepingWatchdogReturnsAtOnce() throws Exception {
		Result r = runWithHelper(
				"killDeadlockedJobPair 3600 0 starexec1 &\n"
				+ "KILL_DEADLOCKED_JOB_PAIR_PID=$!\n"
				+ "sleep 0.3\n"
				+ "for c in $(cat \"/proc/$KILL_DEADLOCKED_JOB_PAIR_PID/task/$KILL_DEADLOCKED_JOB_PAIR_PID/children\" 2>/dev/null); do echo \"$c\" >> \"$SCRIPT_DIR/pids\"; done\n"
				+ "t0=$(date +%s%N)\n"
				+ "stopDeadlockWatchdog\n"
				+ "echo \"T-ELAPSED-MS=$(( ($(date +%s%N) - t0) / 1000000 ))\"\n");

		assertEquals(r.out, 0, r.exit);
		long ms = elapsedMs(r.out);
		assertTrue("a sleeping watchdog must stop in under 2 s, took " + ms + " ms:\n" + r.out, ms < 2000);
	}

	/**
	 * A watchdog that ignores the TERM and never exits (wedged, e.g. on a hung log append) must
	 * not hold the pair: stop gives up at the cap, SIGKILLs it, and returns.
	 */
	@Test
	public void aWedgedWatchdogIsKilledAtTheCap() throws Exception {
		Result r = runWithHelper(
				"sh -c 'trap \"\" TERM; exec sleep 99999' &\n"
				+ "WEDGED=$!\n"
				+ "echo \"$WEDGED\" >> \"$SCRIPT_DIR/pids\"\n"
				+ "sleep 0.2\n"
				+ "DEADLOCK_WATCHDOG_STOP_CAP_TENTHS=10\n"
				+ "KILL_DEADLOCKED_JOB_PAIR_PID=$WEDGED\n"
				+ "t0=$(date +%s%N)\n"
				+ "stopDeadlockWatchdog\n"
				+ "echo \"T-ELAPSED-MS=$(( ($(date +%s%N) - t0) / 1000000 ))\"\n"
				+ "sleep 0.2\n"
				+ "if isOwnLiveChild \"$WEDGED\"; then echo T-WEDGED-ALIVE; else echo T-WEDGED-GONE; fi\n");

		assertEquals(r.out, 0, r.exit);
		long ms = elapsedMs(r.out);
		assertTrue("stop must wait out the 1 s cap, took " + ms + " ms:\n" + r.out, ms >= 1000);
		assertTrue("stop must return soon after the 1 s cap, took " + ms + " ms:\n" + r.out, ms < 3000);
		assertTrue("the wedged watchdog must be gone:\n" + r.out, r.out.contains("T-WEDGED-GONE"));
	}

	private static long elapsedMs(String out) {
		for (String line : out.split("\n")) {
			if (line.startsWith("T-ELAPSED-MS=")) {
				String value = line.substring("T-ELAPSED-MS=".length()).trim();
				try {
					return Long.parseLong(value);
				} catch (NumberFormatException e) {
					// The script computes this from date(1), so a value that is not a number
					// is a defect in the evidence: report it as a failed assertion rather
					// than an error in the test itself.
					throw new AssertionError("elapsed time is not a number [" + value + "]:\n" + out, e);
				}
			}
		}
		throw new AssertionError("no elapsed time reported:\n" + out);
	}

	/**
	 * Under set -e, bash 5.2 -- the job image's, and CI's -- exits the whole shell when
	 * {@code $(<file)} names a missing file, even inside {@code || return}. bash 5.3 does not, so a
	 * local run cannot catch it. The watchdog reads /proc entries that vanish as processes are
	 * reaped, so a guarded read must use the {@code read} builtin instead.
	 */
	@Test
	public void noGuardedReadUsesCommandSubstitution() throws Exception {
		List<String> lines = Files.readAllLines(SGE.resolve("functions.bash"));
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			assertFalse("functions.bash:" + (i + 1) + " guards a $(<file) read with ||: " + line.trim(),
					line.contains("$(<") && line.contains("||"));
		}
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

	private final List<Path> scriptDirs = new ArrayList<>();

	/**
	 * A frozen process outlives the script that spawned it, so the timeout path in runWithHelper
	 * cannot reach it: it has been reparented. Every PID a case records in its pids file is
	 * killed here, pass or fail; SIGKILL ends a stopped process too.
	 */
	@After
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

	private Result runWithHelper(String body) throws Exception {
		Path dir = folder.newFolder("reap-" + System.nanoTime()).toPath();
		scriptDirs.add(dir);
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
