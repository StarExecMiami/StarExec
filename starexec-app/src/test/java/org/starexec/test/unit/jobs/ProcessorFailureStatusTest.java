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
 * What a pair's recorded status says when something other than the solver failed, driven
 * through the real {@code functions.bash}.
 *
 * <h2>The contract</h2>
 *
 * {@code exitJobscript} is the EXIT trap, and it fails closed: any non-zero exit that has not
 * already reported a status is recorded as {@code ERROR_BENCHMARK}, so a pair can never be left
 * without a terminal status. The escape hatch is {@code STATUS_SENT}, which a caller sets to
 * claim "I have already said what went wrong, do not overwrite it".
 *
 * <p>Several paths sent a specific status and then exited without claiming it, so the trap
 * overwrote every one of them with {@code ERROR_BENCHMARK} -- naming the benchmark for a
 * post-processor fault, a pre-processor fault, or a file-write limit breach. The status codes
 * existed and were emitted; nothing that read the database ever saw them.
 *
 * <p>These assert on the file the monitor actually reads, not on a log line, because the file
 * is what becomes the scientific record.
 */
public class ProcessorFailureStatusTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	/** From status_codes.bash, restated so a silent renumbering fails here too. */
	private static final int ERROR_BENCHMARK = 12;
	private static final int EXCEED_FILE_WRITE = 16;
	private static final int ERROR_PRE_PROCESSOR = 25;
	private static final int ERROR_POST_PROCESSOR = 26;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// ------------------------------------------------------- the trap's overwrite rule

	/**
	 * The defect, in the smallest form that shows it: a specific status, then a non-zero exit,
	 * with no claim. The trap overwrote it.
	 */
	@Test
	public void anUnclaimedStatusIsOverwrittenByTheFailClosedTrap() throws Exception {
		Harness h = new Harness(folder);
		h.line("sendStatus \"$ERROR_POST_PROCESSOR\"");
		h.line("exit 1");

		h.run(1);

		assertEquals(
				"this is the behaviour the affected paths were relying on and not getting",
				ERROR_BENCHMARK, h.status());
	}

	/** And the claim that suppresses it, which is what the fixed paths now make. */
	@Test
	public void aClaimedStatusSurvivesTheTrap() throws Exception {
		Harness h = new Harness(folder);
		h.line("STATUS_SENT=true");
		h.line("sendStatus \"$ERROR_POST_PROCESSOR\"");
		h.line("exit 1");

		h.run(1);

		assertEquals(ERROR_POST_PROCESSOR, h.status());
	}

	/** A clean exit is not the trap's business either way. */
	@Test
	public void aZeroExitIsNeverOverwritten() throws Exception {
		Harness h = new Harness(folder);
		h.line("sendStatus \"$ERROR_PRE_PROCESSOR\"");
		h.line("exit 0");

		h.run(0);

		assertEquals(ERROR_PRE_PROCESSOR, h.status());
	}

	// ---------------------------------------------------------------- limitExceeded

	/**
	 * {@code limitExceeded} is the SIGXFSZ handler, installed by the jobscript as
	 * {@code trap "limitExceeded 'file write' $EXCEED_FILE_WRITE" SIGXFSZ}. It exits 1, so
	 * without a claim the breach it was written to report became a benchmark error.
	 */
	@Test
	public void aFileWriteLimitBreachIsRecordedAsTheLimitItBreached() throws Exception {
		Harness h = new Harness(folder);
		h.line("limitExceeded 'file write' \"$EXCEED_FILE_WRITE\"");

		h.run(1);

		assertEquals(EXCEED_FILE_WRITE, h.status());
	}

	// ------------------------------------------------- the dead-branch pattern itself

	/**
	 * The other half of the defect is not observable from a status file, because the branch
	 * never ran: under {@code set -e} a failing command aborts before {@code $?} can be tested
	 * on the following line, so both processor failure branches were unreachable and the trap
	 * handled the exit instead.
	 *
	 * <p>Asserted against the shipped helper because the repository has already fixed this exact
	 * pattern once, in {@code checkIfBenchmarkDependenciesExists}, and it came back. A guard is
	 * cheap; rediscovering it from a misattributed job result is not.
	 */
	@Test
	public void noBranchTestsDollarQuestionAfterACommand() throws Exception {
		String helper = Files.readString(SGE.resolve("functions.bash"));

		assertFalse(
				"`if (( $? != 0 ))` on the line after a command is unreachable under set -e;"
						+ " capture with `|| STATUS=$?` on the command itself instead",
				helper.contains("if (( $? "));
	}

	/**
	 * The processor failure paths themselves, which the behavioural tests above cannot reach:
	 * they sit inside {@code copyOutput} and {@code copyDependencies}, whose surrounding
	 * environment is most of a running job pair. What can be checked directly is the thing that
	 * was wrong -- a specific status sent on a path that then exits non-zero, without claiming
	 * it, so the trap replaces it.
	 *
	 * <p>Structural rather than behavioural, and stated as such. It pins the fix and would catch
	 * a new failure path added without the claim, which is exactly how these four arose.
	 */
	@Test
	public void everyProcessorFailurePathClaimsItsStatus() throws Exception {
		String[] lines = Files.readString(SGE.resolve("functions.bash")).split("\n", -1);

		int checked = 0;
		for (int i = 0; i < lines.length; i++) {
			boolean sendsProcessorError =
					lines[i].contains("sendStatus \"$ERROR_POST_PROCESSOR\"")
							|| lines[i].contains("sendStatus \"$ERROR_PRE_PROCESSOR\"");
			if (!sendsProcessorError) {
				continue;
			}
			checked++;

			boolean claimed = false;
			for (int back = Math.max(0, i - 3); back < i; back++) {
				if (lines[back].contains("STATUS_SENT=true")) {
					claimed = true;
				}
			}
			assertTrue(
					"functions.bash:" + (i + 1) + " sends a processor status but does not claim"
							+ " it with STATUS_SENT=true, so exitJobscript overwrites it with"
							+ " ERROR_BENCHMARK: " + lines[i].trim(),
					claimed);
		}

		assertEquals("the processor failure paths must still exist to be checked", 4, checked);
	}

	/**
	 * The idiom that replaced it, exercised rather than assumed: the capture must survive
	 * {@code set -e} and must carry the real exit status through.
	 */
	@Test
	public void theCaptureIdiomSurvivesSetEAndKeepsTheStatus() throws Exception {
		Path script = folder.newFile("capture.sh").toPath();
		Files.writeString(script,
				"set -euo pipefail\n"
						+ "STATUS=0\n"
						+ "( exit 42 ) || STATUS=$?\n"
						+ "echo \"reached:$STATUS\"\n");

		Result r = exec(Bash.PATH, script.toString());

		assertEquals("the script must not abort:\n" + r.out, 0, r.exit);
		assertTrue("the branch after the capture must be reached: " + r.out,
				r.out.contains("reached:42"));
	}

	// ------------------------------------------------------------------------ harness

	private static final class Harness {

		private final Path dir;
		private final Path out;
		private final StringBuilder body = new StringBuilder();

		Harness(TemporaryFolder folder) throws Exception {
			this.dir = folder.newFolder("sge").toPath();
			this.out = folder.newFolder("out").toPath();

			// The helper reads its siblings relative to SCRIPT_DIR, so it is sourced from a copy
			// of the shipped directory rather than from a rewritten fixture.
			for (String name : new String[]{"functions.bash", "status_codes.bash", "timestamper.bash"}) {
				Path from = SGE.resolve(name);
				if (Files.exists(from)) {
					Files.copy(from, dir.resolve(name));
				}
			}

			body.append("export SCRIPT_DIR=\"").append(dir).append("\"\n");
			body.append("export STAREXEC_OUTPUT_DIR=\"").append(out).append("\"\n");
			body.append("export CONTAINER_MODE=true\n");
			body.append("export PAIR_ID=41\n");
			body.append("export SHARED_DIR=\"").append(dir).append("/shared\"\n");
			body.append("export WORKING_DIR_BASE=\"").append(dir).append("/work\"\n");
			body.append("export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n");
			body.append("export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n");
			body.append("SOLVER_PATHS=()\n");
			body.append(". \"$SCRIPT_DIR/functions.bash\"\n");
			// Installed exactly as the jobscript installs it, so the trap under test is the one
			// that actually runs in production.
			body.append("trap 'exitJobscript $?' EXIT\n");
		}

		void line(String statement) {
			body.append(statement).append('\n');
		}

		void run(int expectedExit) throws Exception {
			File script = new File(dir.toFile(), "generated.sh");
			Files.writeString(script.toPath(), body.toString());
			assertEquals("generated script must parse", 0,
					exec(Bash.PATH, "-n", script.getAbsolutePath()).exit);
			Result r = exec(Bash.PATH, script.getAbsolutePath());
			assertEquals("unexpected exit status:\n" + r.out, expectedExit, r.exit);
		}

		int status() throws Exception {
			return field(Files.readString(out.resolve("status.json")), "status");
		}
	}

	/** Reads one integer field, so the assertions do not depend on field order. */
	private static int field(String json, String name) {
		var m = java.util.regex.Pattern
				.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)")
				.matcher(json);
		assertTrue("json must carry " + name + ": " + json, m.find());
		String raw = m.group(1);
		try {
			return Integer.parseInt(raw);
		} catch (NumberFormatException e) {
			throw new AssertionError("json field " + name + " is not an int: " + raw, e);
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
