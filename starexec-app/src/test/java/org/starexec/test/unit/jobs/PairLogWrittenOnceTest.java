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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The pair log that {@code log} in the shipped {@code functions.bash} writes.
 *
 * <p>In container mode {@code log} also appends each line to {@code CONTAINER_LOG_FILE}, the
 * pair log in {@code STAREXEC_OUTPUT_DIR}, because on Kubernetes and Podman the job script's
 * stdout goes to the container runtime. The local backend sets the same variables, but it also
 * makes the script's stdout the pair log itself, with {@code ProcessBuilder.redirectOutput(File)},
 * which opens the file without {@code O_APPEND}. Each line then reached the file twice, through
 * two descriptors: the appended copy landed past the offset stdout writes at, and the next output
 * overwrote it. A finished log ended with fragments of earlier lines, and a reader following the
 * log while it was written, as the live pair log stream does, saw some lines twice and missed
 * others.
 */
public class PairLogWrittenOnceTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");
	private static final int PAIR_ID = 21;

	/**
	 * Log lines between output that only goes to stdout. The first line is the longest, so that a
	 * copy appended after it would run furthest ahead of the stdout offset, as the runsolver
	 * command line does in a real job log. After every step the script copies the log aside.
	 */
	private static final String STEPS = "log \"Running runsolver: a long command line, longer than any line after it\"\n"
			+ "snapshot\n"
			+ "log \"short line\"\n"
			+ "snapshot\n"
			+ "echo \"plain output, stdout only\"\n"
			+ "snapshot\n"
			+ "log \"cleaned\"\n"
			+ "snapshot\n"
			+ "echo \"Jobscript ending.\"\n"
			+ "snapshot\n";

	private static final List<String> LOG_LINES = List.of(
			"TS: Running runsolver: a long command line, longer than any line after it",
			"TS: short line",
			"TS: cleaned");

	private static final List<String> ALL_OUTPUT = List.of(
			"TS: Running runsolver: a long command line, longer than any line after it",
			"TS: short line",
			"plain output, stdout only",
			"TS: cleaned",
			"Jobscript ending.");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** The defect: the log ended with fragments and repeats of lines written before it. */
	@Test
	public void aLogThatIsTheScriptsOwnStdoutHoldsEachLineOnce() throws Exception {
		Run run = Run.withStdoutIntoTheLog(folder);

		assertEquals(lines(ALL_OUTPUT), run.log());
	}

	/**
	 * What the live stream relies on: it sends what the log holds and then reads on from where it
	 * stopped, so a byte it has sent must never change. Every copy taken while the script ran is
	 * a prefix of the finished log. One line is logged with its stdout sent elsewhere: whether or
	 * not it reaches the log, it must not be written where later output overwrites it.
	 */
	@Test
	public void aLogThatIsTheScriptsOwnStdoutOnlyEverGrows() throws Exception {
		String steps = STEPS.replace("log \"short line\"\nsnapshot\n",
				"log \"short line\"\nsnapshot\nlog \"sent elsewhere\" > /dev/null\nsnapshot\n");
		Run run = Run.start(folder, true, steps);

		String finished = run.log();
		List<String> snapshots = run.snapshots();
		assertEquals("one copy per step", 6, snapshots.size());
		for (int i = 0; i < snapshots.size(); i++) {
			assertTrue("copy " + (i + 1) + " is not a prefix of the finished log:\n" + snapshots.get(i),
					finished.startsWith(snapshots.get(i)));
		}
	}

	/** Kubernetes and Podman: stdout goes elsewhere, and the log still gets each log line once. */
	@Test
	public void aLogBesideAnotherStdoutStillGetsEachLogLineOnce() throws Exception {
		Run run = Run.withStdoutToAPipe(folder);

		assertEquals(lines(LOG_LINES), run.log());
		assertEquals(lines(ALL_OUTPUT), run.stdout);
	}

	private static String lines(List<String> lines) {
		return lines.stream().map(line -> line + "\n").collect(Collectors.joining());
	}

	/** One run of {@link #STEPS} with the shipped helper sourced, as the jobscript sources it. */
	private static final class Run {

		private final Path log;
		private final Path snapshots;
		private final String stdout;

		private Run(Path log, Path snapshots, String stdout) {
			this.log = log;
			this.snapshots = snapshots;
			this.stdout = stdout;
		}

		/** As LocalBackend starts a job script: stderr into stdout, and stdout into the pair log. */
		static Run withStdoutIntoTheLog(TemporaryFolder folder) throws Exception {
			return start(folder, true, STEPS);
		}

		/** As a pod starts one: stdout is a pipe the container runtime reads. */
		static Run withStdoutToAPipe(TemporaryFolder folder) throws Exception {
			return start(folder, false, STEPS);
		}

		static Run start(TemporaryFolder folder, boolean stdoutIntoTheLog, String steps) throws Exception {
			Path root = folder.newFolder("log-" + System.nanoTime()).toPath();
			Path sge = Files.createDirectories(root.resolve("sge"));
			for (String name : new String[] {"functions.bash", "status_codes.bash", "timestamper.bash"}) {
				Path from = SGE.resolve(name);
				if (Files.exists(from)) {
					Files.copy(from, sge.resolve(name));
				}
			}
			Path outputDir = Files.createDirectories(root.resolve("out").resolve(String.valueOf(PAIR_ID)));
			Path log = outputDir.resolve(PAIR_ID + ".txt");
			Path snapshots = Files.createDirectories(root.resolve("snapshots"));
			Files.createDirectories(root.resolve("work"));

			String script = "export SCRIPT_DIR=\"" + sge + "\"\n"
					+ "export STAREXEC_OUTPUT_DIR=\"" + outputDir + "\"\n"
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
					+ "SOLVER_PATHS=()\n"
					+ "STAGE_NUMBERS=(1)\n"
					+ "STAGE_INDEX=0\n"
					+ ". \"$SCRIPT_DIR/functions.bash\"\n"
					// The same text on every run, so the log can be compared exactly.
					+ "date() { echo TS; }\n"
					+ "copies=0\n"
					+ "snapshot() {\n"
					+ "    copies=$((copies + 1))\n"
					+ "    if [ -e \"" + log + "\" ]; then cp \"" + log + "\" \"" + snapshots + "/$copies\"; "
					+ "else : > \"" + snapshots + "/$copies\"; fi\n"
					+ "}\n"
					+ steps;
			File file = new File(root.toFile(), "run.sh");
			Files.writeString(file.toPath(), script);

			ProcessBuilder pb = new ProcessBuilder(Bash.PATH, file.getAbsolutePath());
			pb.redirectErrorStream(true);
			if (stdoutIntoTheLog) {
				pb.redirectOutput(log.toFile());
			}
			Process p = pb.start();
			String stdout = stdoutIntoTheLog ? "" : new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertTrue("the script must not hang", p.waitFor(60, TimeUnit.SECONDS));
			assertEquals("the script must succeed:\n" + (stdoutIntoTheLog ? Files.readString(log) : stdout),
					0, p.exitValue());
			return new Run(log, snapshots, stdout);
		}

		String log() throws Exception {
			return Files.readString(log);
		}

		/** The copies in the order they were taken. */
		List<String> snapshots() throws Exception {
			try (Stream<Path> files = Files.list(snapshots)) {
				List<Path> sorted = files
						.sorted((a, b) -> Integer.compare(number(a), number(b)))
						.collect(Collectors.toList());
				List<String> contents = new java.util.ArrayList<>();
				for (Path f : sorted) {
					contents.add(Files.readString(f));
				}
				return contents;
			}
		}

		private static int number(Path f) {
			return Integer.parseInt(f.getFileName().toString());
		}
	}
}
