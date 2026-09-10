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
	private static final int ERROR_RUNSCRIPT = 11;
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

	// ------------------------------------------------- which stage the fault is named against

	/**
	 * A processor fault belongs to the stage that was running, and saying so is what keeps it
	 * out of {@code UpdatePairStatusPrecise}'s pair-level path.
	 *
	 * <p>That routine sets the terminal status {@code WHERE stage_number = _stageNumber} and
	 * NOT_REACHED {@code WHERE stage_number > _stageNumber}. Stage numbers start at 1, so the
	 * 0 that {@code sendStatus} defaults to gives the status to no row and NOT_REACHED to every
	 * stage the pair has -- a stage that genuinely completed included (#152).
	 *
	 * <p>Behavioural, and paired with the structural test below on purpose. This one shows that
	 * naming a stage changes what reaches the monitor; that one shows the four processor paths
	 * actually name one. Neither is sufficient alone: the failure paths sit inside
	 * {@code copyOutput} and {@code copyDependencies}, whose surrounding environment is most of
	 * a running job pair, so the call sites themselves cannot be driven from here.
	 */
	@Test
	public void aStatusThatNamesAStageReachesTheMonitorAsThatStage() throws Exception {
		Harness h = new Harness(folder);
		h.line("sendStatus \"$ERROR_POST_PROCESSOR\" 2");
		h.line("exit 0");

		h.run(0);

		assertEquals(ERROR_POST_PROCESSOR, h.status());
		assertEquals(2, h.stage());
		assertTrue(
				"a named stage must also leave the per-stage snapshot the monitor reads, or a"
						+ " later stage's write erases this result",
				h.hasSnapshot(2));
	}

	/**
	 * The negative control, and the reason the argument has to be supplied at the call site
	 * rather than defaulted. Nothing here is broken -- this is what the pair-level channel is
	 * for -- but it is not a stage, and it leaves no snapshot to be read back.
	 */
	@Test
	public void aStatusWithNoStageArgumentNamesNoStage() throws Exception {
		Harness h = new Harness(folder);
		h.line("sendStatus \"$ERROR_POST_PROCESSOR\"");
		h.line("exit 0");

		h.run(0);

		assertEquals(ERROR_POST_PROCESSOR, h.status());
		assertEquals("sendStatus's stage argument defaults to 0", 0, h.stage());
		assertFalse("stage 0 owns no jobpair_stage_data row, so it gets no snapshot",
				h.hasSnapshot(0));
	}

	/**
	 * The four processor failure paths, checked in the source for the same reason the claim
	 * test above is: they cannot be reached from this harness. It pins the stage argument and
	 * would catch a fifth path added without one.
	 *
	 * <p>The argument is required to come from an authoritative source rather than merely to be
	 * present, so a literal cannot satisfy it. {@code copyOutput} takes the stage as its own
	 * first argument, which every caller passes as {@code CURRENT_STAGE_NUMBER}
	 * (jobscript:539, :557). {@code copyDependencies} takes none and runs at jobscript:307,
	 * before {@code CURRENT_STAGE_NUMBER} is assigned at :314, so it reads
	 * {@code STAGE_NUMBERS[STAGE_INDEX]} -- the loop's own index -- instead of a variable that
	 * still holds the previous iteration's value.
	 */
	@Test
	public void everyProcessorFailurePathNamesItsStage() throws Exception {
		String[] lines = Files.readString(SGE.resolve("functions.bash")).split("\n", -1);

		int checked = 0;
		for (String raw : lines) {
			String line = raw.trim();
			String call = null;
			if (line.startsWith("sendStatus \"$ERROR_POST_PROCESSOR\"")) {
				call = "sendStatus \"$ERROR_POST_PROCESSOR\"";
			} else if (line.startsWith("sendStatus \"$ERROR_PRE_PROCESSOR\"")) {
				call = "sendStatus \"$ERROR_PRE_PROCESSOR\"";
			}
			if (call == null) {
				continue;
			}
			checked++;

			String stage = line.substring(call.length()).trim();
			assertFalse(
					"a processor fault sent with no stage number defaults to 0, and a precise"
							+ " write keyed on \"= 0\" gives the status to no stage and NOT_REACHED"
							+ " to every stage the pair has (#152): " + line,
					stage.isEmpty());
			assertTrue(
					"the stage must come from the stage the pair is actually running, not a"
							+ " literal: " + line,
					stage.contains("$1") || stage.contains("STAGE_NUMBERS[STAGE_INDEX]"));
		}

		assertEquals("the processor failure paths must still exist to be checked", 4, checked);
	}

	// ------------------------------------------------------ the runscript error path

	/**
	 * {@code markRunscriptError} took a 1-based stage and wrote {@code stage - 1}, so the
	 * failure of the first stage reached the monitor as stage 0 — the pair-level channel — and
	 * a precise write keyed on {@code = 0} then applied it to no stage while marking every
	 * stage of the pair not reached (#152).
	 *
	 * <p>The subtraction was not a typo. It is correct for the other branch: the SGE path calls
	 * {@code RunscriptError}, which feeds its stage argument to {@code UpdateLaterStageStatuses}
	 * and {@code SetRunStatsForLaterStagesToZero}, and both act on stages strictly greater than
	 * it. One number was serving as a threshold in one branch and an identity in the other.
	 */
	@Test
	public void aRunscriptErrorNamesTheStageThatFailedNotTheOneBeforeIt() throws Exception {
		Harness h = new Harness(folder);
		h.line("STATUS_SENT=true");
		h.line("markRunscriptError 1");
		h.line("exit 0");

		h.run(0);

		assertEquals(ERROR_RUNSCRIPT, h.status());
		assertEquals("the first stage's failure must name stage 1, not stage 0",
				1, h.stage());
		assertTrue("and must leave the per-stage snapshot that stage 0 cannot have",
				h.hasSnapshot(1));
	}

	/** The same for a later stage, so the fix is not an off-by-one in the other direction. */
	@Test
	public void aRunscriptErrorInALaterStageNamesThatStage() throws Exception {
		Harness h = new Harness(folder);
		h.line("STATUS_SENT=true");
		h.line("markRunscriptError 3");
		h.line("exit 0");

		h.run(0);

		assertEquals(3, h.stage());
		assertTrue(h.hasSnapshot(3));
		assertFalse("no snapshot may be written for a stage that did not fail",
				h.hasSnapshot(2));
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

		int stage() throws Exception {
			return field(Files.readString(out.resolve("status.json")), "stageNumber");
		}

		/** The per-stage record beside status.json, written only for a real stage number. */
		boolean hasSnapshot(int stageNumber) {
			return Files.exists(out.resolve("stage-status").resolve(stageNumber + ".json"));
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
