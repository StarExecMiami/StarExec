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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A solver the kernel stops at its container's memory limit.
 *
 * <p>The Podman backend caps each pair's container at {@code STAREXEC_CONTAINER_DEFAULT_MEMORY_MB} (2048 by
 * default), while runsolver enforces the job's own memory limit (16 GiB when a job names none). A solver that
 * reaches the container's limit first is killed by the kernel's OOM killer with SIGKILL. runsolver reports a child
 * ended by a signal, with {@code MEMOUT=false}, and the jobscript's runsolver checks read only runsolver's own limit
 * messages, so the pair was recorded as {@code STATUS_COMPLETE}: a solver stopped for lack of memory read as one
 * that finished.
 *
 * <p>The kernel counts the kill in the container's cgroup ({@code oom_kill} in {@code memory.events}). The
 * jobscript reads that count before and after the solver runs, and a rise is a memory breach of the running stage.
 *
 * <p>The runsolver files below are runsolver 3.4.1's own output for such a run, trimmed to the lines the checks
 * read: the job-runner image, a 256 MiB container, {@code runsolver -M 1024}, a solver touching 400 MiB.
 */
public class ContainerOomKillTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	private static final int STATUS_RUNNING = 4;
	private static final int EXCEED_CPU = 15;
	private static final int EXCEED_MEM = 17;

	private static final long CONTAINER_LIMIT_BYTES = 256L * 1024 * 1024;

	/** A solver the kernel stopped: runsolver printed no limit message, only the SIGKILL. */
	private static final String WATCHER_KILLED_BY_SIGNAL = String.join("\n",
			"runsolver version 3.4.1 (svn: 4412) Copyright (C) 2010-2013 Olivier ROUSSEL",
			"Enforcing CPUTime limit (soft limit, will send SIGTERM then SIGKILL): 60 seconds",
			"Enforcing CPUTime limit (hard limit, will send SIGXCPU): 90 seconds",
			"Enforcing wall clock limit (soft limit, will send SIGTERM then SIGKILL): 60 seconds",
			"Enforcing VSIZE limit (soft limit, will send SIGTERM then SIGKILL): 1048576 KiB",
			"Enforcing VSIZE limit (hard limit, stack expansion will fail with SIGSEGV, brk() and mmap() will return"
					+ " ENOMEM): 1099776 KiB",
			"Solver just ended.",
			"Child ended because it received signal 9 (SIGKILL)",
			"Max. virtual memory (cumulated for all children) (KiB): 411244",
			"maximum resident set size= 260864",
			"The end",
			"");

	/** runsolver's own verdict on that run: no limit breached. */
	private static final String VAR_NO_BREACH = String.join("\n",
			"WCTIME=0.306163", "CPUTIME=0.282748", "MAXVM=411244", "TIMEOUT=false", "MEMOUT=false", "");

	/** A CPU limit runsolver enforced itself, with the message stopSolver prints (Watcher.hh). */
	private static final String WATCHER_CPU_EXCEEDED = String.join("\n",
			"runsolver version 3.4.1 (svn: 4412) Copyright (C) 2010-2013 Olivier ROUSSEL",
			"Maximum CPU time exceeded: sending SIGTERM then SIGKILL",
			"Child ended because it received signal 15 (SIGTERM)",
			"The end",
			"");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// ------------------------------------------------------------ the checks after the solver

	/** The defect: a pair recorded as complete although the kernel stopped its solver. */
	@Test
	public void aSolverTheKernelStoppedIsAMemoryBreachOfTheRunningStage() throws Exception {
		Probe probe = new Probe(folder, true);
		probe.cgroupV2(1, CONTAINER_LIMIT_BYTES);

		String out = probe.runChecks(WATCHER_KILLED_BY_SIGNAL, VAR_NO_BREACH, "0", "1");

		assertEquals("a memory breach, not a completed pair:\n" + out, EXCEED_MEM, probe.status());
		assertEquals("the stage that was running", 2, probe.stage());
		assertTrue(out, out.contains("ERROR_DETECTED=true"));
		assertTrue("the pair log must give the container's limit and the stage's:\n" + out,
				out.contains("256 MiB") && out.contains("1024 MiB"));
	}

	/** A kill counted before this solver started belongs to something else. */
	@Test
	public void noKillWhileTheSolverRanMakesNoClaim() throws Exception {
		Probe probe = new Probe(folder, true);
		probe.cgroupV2(1, CONTAINER_LIMIT_BYTES);

		String out = probe.runChecks(WATCHER_KILLED_BY_SIGNAL, VAR_NO_BREACH, "1", "1");

		assertEquals("the pair must still be running, with no result recorded:\n" + out, STATUS_RUNNING, probe.status());
		assertTrue(out, out.contains("ERROR_DETECTED=false"));
	}

	/** No count (no cgroup of its own, or none readable) is no evidence, so nothing is claimed. */
	@Test
	public void aCountThatWasNotReadMakesNoClaim() throws Exception {
		Probe probe = new Probe(folder, true);

		String out = probe.runChecks(WATCHER_KILLED_BY_SIGNAL, VAR_NO_BREACH, "", "");

		assertEquals("the pair must still be running, with no result recorded:\n" + out, STATUS_RUNNING, probe.status());
		assertTrue(out, out.contains("ERROR_DETECTED=false"));
	}

	/** runsolver's own verdict names the limit it enforced, so it is checked first. */
	@Test
	public void runsolversOwnVerdictComesFirst() throws Exception {
		Probe probe = new Probe(folder, true);
		probe.cgroupV2(1, CONTAINER_LIMIT_BYTES);

		String out = probe.runChecks(WATCHER_CPU_EXCEEDED, VAR_NO_BREACH, "0", "1");

		assertEquals(out, EXCEED_CPU, probe.status());
	}

	// ------------------------------------------------------------ where the count comes from

	@Test
	public void theCountIsReadFromThePairsOwnCgroup() throws Exception {
		Probe probe = new Probe(folder, true);
		probe.cgroupV2(2, CONTAINER_LIMIT_BYTES);

		assertEquals("2", probe.print("pairCgroupOomKills"));
		assertEquals("256", probe.print("pairCgroupMemoryLimitMib"));
	}

	/**
	 * The local backend runs every pair inside the application's own cgroup, so a kill there cannot be
	 * attributed to one pair. It never sets the flag, and without it nothing is read.
	 */
	@Test
	public void aCgroupShapedByOtherPairsIsNotRead() throws Exception {
		Probe probe = new Probe(folder, false);
		probe.cgroupV2(2, CONTAINER_LIMIT_BYTES);

		assertEquals("", probe.print("pairCgroupOomKills"));
	}

	@Test
	public void cgroupV1IsReadToo() throws Exception {
		Probe probe = new Probe(folder, true);
		probe.cgroupV1(4, CONTAINER_LIMIT_BYTES);

		assertEquals("4", probe.print("pairCgroupOomKills"));
		assertEquals("256", probe.print("pairCgroupMemoryLimitMib"));
	}

	@Test
	public void withoutCgroupFilesThereIsNoCount() throws Exception {
		Probe probe = new Probe(folder, true);

		assertEquals("", probe.print("pairCgroupOomKills"));
		assertEquals("", probe.print("pairCgroupMemoryLimitMib"));
	}

	/** cgroup v2 writes "max" for a container without a memory limit. */
	@Test
	public void anUnlimitedContainerHasNoLimitToReport() throws Exception {
		Probe probe = new Probe(folder, true);
		probe.cgroupV2(0, CONTAINER_LIMIT_BYTES);
		Files.writeString(probe.cgroup.resolve("memory.max"), "max\n");

		assertEquals("", probe.print("pairCgroupMemoryLimitMib"));
	}

	/** cgroup v1 writes the largest page-aligned value instead. */
	@Test
	public void anUnlimitedV1ContainerHasNoLimitToReport() throws Exception {
		Probe probe = new Probe(folder, true);
		probe.cgroupV1(0, 9223372036854771712L);

		assertEquals("", probe.print("pairCgroupMemoryLimitMib"));
	}

	/** A count or a limit the files do not give as a number is none, and reading it does not end the job script. */
	@Test
	public void aValueThatIsNotANumberIsNone() throws Exception {
		Probe probe = new Probe(folder, true);
		probe.cgroupV2(0, CONTAINER_LIMIT_BYTES);

		Files.writeString(probe.cgroup.resolve("memory.events"), "low 0\nhigh 0\nmax 3\noom 1\noom_kill x\n");
		assertEquals("", probe.print("pairCgroupOomKills"));

		Files.writeString(probe.cgroup.resolve("memory.events"), "low 0\nhigh 0\nmax 3\noom 1\n");
		assertEquals("a kernel that does not count OOM kills", "", probe.print("pairCgroupOomKills"));

		Files.writeString(probe.cgroup.resolve("memory.events"), "");
		assertEquals("", probe.print("pairCgroupOomKills"));

		Files.writeString(probe.cgroup.resolve("memory.max"), "268435456 bytes\n");
		assertEquals("", probe.print("pairCgroupMemoryLimitMib"));
	}

	/** Without a limit to name, the pair log still records the breach. */
	@Test
	public void aBreachUnderAnUnreadableLimitIsStillRecorded() throws Exception {
		Probe probe = new Probe(folder, true);
		probe.cgroupV2(1, CONTAINER_LIMIT_BYTES);
		Files.delete(probe.cgroup.resolve("memory.max"));

		String out = probe.runChecks(WATCHER_KILLED_BY_SIGNAL, VAR_NO_BREACH, "0", "1");

		assertEquals(out, EXCEED_MEM, probe.status());
	}

	// ------------------------------------------------------------ the jobscript's wiring

	/**
	 * The count is taken right before the solver starts and right after it returns: the pre-processor has run
	 * by then, and the post-processor (inside copyOutput) has not, so a kill of either is not the solver's.
	 */
	@Test
	public void theCountIsTakenRightAroundTheSolver() throws Exception {
		List<String> loop = stageLoop();

		int preProcessing = indexOfLineStartingWith(loop, "copyDependencies");
		int before = indexOfLineStartingWith(loop, "OOM_KILLS_BEFORE_SOLVER=$(pairCgroupOomKills)");
		int run = indexOfLineStartingWith(loop, "if [ \"$BENCHMARKING_FRAMEWORK\" == \"$BENCHEXEC\" ]; then");
		int solver = indexOfLineStartingWith(loop, "eval \"$RUNSOLVER_CMD\"");
		int after = indexOfLineStartingWith(loop, "OOM_KILLS_AFTER_SOLVER=$(pairCgroupOomKills)");
		int postProcessing = indexOfLineStartingWith(loop, "copyOutput \"$CURRENT_STAGE_NUMBER\"");

		assertTrue("the solver must be run in the loop", run >= 0 && solver > run);
		assertTrue("the count must be taken before the solver starts", before >= 0 && before < run);
		assertTrue("and after this stage's pre-processor has run", preProcessing >= 0 && preProcessing < before);
		assertTrue("and again after it returns", after > solver);
		assertTrue("and before the post-processor runs", postProcessing > after);
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

	/**
	 * The jobscript's limit checks as they run after the solver, from {@code ERROR_DETECTED=false} to the disk
	 * quota check, so the tests run the shipped code rather than a copy of it.
	 */
	private static String limitChecks() throws Exception {
		List<String> loop = stageLoop();
		int start = -1;
		int end = -1;
		for (int i = 0; i < loop.size(); i++) {
			String line = loop.get(i).strip();
			if (start < 0 && line.equals("ERROR_DETECTED=false")) {
				start = i;
			} else if (start >= 0 && line.startsWith("if [ \"$ERROR_DETECTED\" = false ] && ((DISK_QUOTA_EXCEEDED")) {
				end = i;
				break;
			}
		}
		assertTrue("the limit checks must exist", start >= 0 && end > start);
		String block = String.join("\n", loop.subList(start, end));
		assertFalse("the checks must run as shipped, with no template placeholder", block.contains("$$"));
		return block;
	}

	private static int indexOfLineStartingWith(List<String> lines, String prefix) {
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).strip().startsWith(prefix)) {
				return i;
			}
		}
		return -1;
	}

	/** Runs shell code against the shipped functions.bash, with the jobscript's EXIT trap and a fake cgroup. */
	private static final class Probe {

		private static final int PAIR_ID = 11;

		private final Path root;
		private final Path sge;
		private final Path cgroup;
		private final boolean ownCgroup;

		Probe(TemporaryFolder folder, boolean ownCgroup) throws Exception {
			this.root = folder.newFolder("oom-" + System.nanoTime()).toPath();
			this.sge = Files.createDirectories(root.resolve("sge"));
			this.cgroup = Files.createDirectories(root.resolve("cgroup"));
			this.ownCgroup = ownCgroup;
			Files.createDirectories(root.resolve("work"));
			for (String name : new String[] {"functions.bash", "status_codes.bash", "timestamper.bash"}) {
				Path from = SGE.resolve(name);
				if (Files.exists(from)) {
					Files.copy(from, sge.resolve(name));
				}
			}
		}

		void cgroupV2(int oomKills, long limitBytes) throws Exception {
			Files.writeString(cgroup.resolve("memory.events"), "low 0\nhigh 0\nmax 825\noom " + oomKills
					+ "\noom_kill " + oomKills + "\noom_group_kill 0\nsock_throttled 0\n");
			Files.writeString(cgroup.resolve("memory.max"), limitBytes + "\n");
		}

		void cgroupV1(int oomKills, long limitBytes) throws Exception {
			Path memory = Files.createDirectories(cgroup.resolve("memory"));
			Files.writeString(memory.resolve("memory.oom_control"),
					"oom_kill_disable 0\nunder_oom 0\noom_kill " + oomKills + "\n");
			Files.writeString(memory.resolve("memory.limit_in_bytes"), limitBytes + "\n");
		}

		/** Runs the jobscript's limit checks on these runsolver files and these counts. */
		String runChecks(String watcher, String var, String before, String after) throws Exception {
			Path watchFile = root.resolve("watcher.out");
			Path varFile = root.resolve("var.out");
			Files.writeString(watchFile, watcher);
			Files.writeString(varFile, var);
			return run("BENCHEXEC='BENCHEXEC'\n"
					+ "RUNSOLVER='RUNSOLVER'\n"
					+ "BENCHMARKING_FRAMEWORK=$RUNSOLVER\n"
					+ "STAGE_INDEX=1\n"
					+ "CURRENT_STAGE_NUMBER=2\n"
					+ "CURRENT_STAGE_MEMORY=1024\n"
					+ "WATCHFILE=\"" + watchFile + "\"\n"
					+ "VARFILE=\"" + varFile + "\"\n"
					+ "JOB_PAIR_DEADLOCKED=false\n"
					+ "OOM_KILLS_BEFORE_SOLVER='" + before + "'\n"
					+ "OOM_KILLS_AFTER_SOLVER='" + after + "'\n"
					+ limitChecks() + "\n"
					+ "echo \"ERROR_DETECTED=$ERROR_DETECTED\"\n");
		}

		/**
		 * What a functions.bash function prints, read as the jobscript reads it: by assignment, where set -e ends
		 * the script if the function fails, which run() reports.
		 */
		String print(String function) throws Exception {
			String out = run("declare -F " + function + " > /dev/null || { echo 'no function " + function + "'; exit 3; }\n"
					+ "PRINTED=$(" + function + ")\n"
					+ "echo \"PRINTED=[$PRINTED]\"\n");
			Matcher m = Pattern.compile("PRINTED=\\[(.*)\\]").matcher(out);
			assertTrue(out, m.find());
			return m.group(1);
		}

		private String run(String body) throws Exception {
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
					+ "export STAREXEC_CGROUP_ROOT=\"" + cgroup + "\"\n"
					+ (ownCgroup ? "export STAREXEC_PAIR_HAS_OWN_CGROUP=true\n" : "unset STAREXEC_PAIR_HAS_OWN_CGROUP\n")
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
			Result parse = exec(Bash.PATH, "-n", file.getAbsolutePath());
			assertEquals("generated script must parse:\n" + parse.out, 0, parse.exit);
			Result result = exec(Bash.PATH, file.getAbsolutePath());
			assertEquals("the script must end normally:\n" + result.out, 0, result.exit);
			return result.out;
		}

		int status() throws Exception {
			return field("status");
		}

		int stage() throws Exception {
			return field("stageNumber");
		}

		private Path statusFile() {
			return root.resolve("out").resolve(String.valueOf(PAIR_ID)).resolve("status.json");
		}

		private int field(String name) throws Exception {
			assertTrue("the pair must report a status", Files.exists(statusFile()));
			String json = Files.readString(statusFile());
			Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
			assertTrue(name + " must be recorded:\n" + json, m.find());
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
