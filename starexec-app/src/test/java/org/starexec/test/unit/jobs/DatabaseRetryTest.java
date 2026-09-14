package org.starexec.test.unit.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Drives the shipped retry loop under its real strict-mode shell settings. */
public class DatabaseRetryTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void successfulFirstAttemptDoesNotSleep() throws Exception {
		assertAttempt(false, 1, false, 0, 1, 0, true);
	}

	@Test
	public void transientFailureIsRetriedBeforeReturningSuccess() throws Exception {
		assertAttempt(false, 2, false, 0, 2, 1, true);
	}

	@Test
	public void exhaustedRetriesFailWithoutContinuingTheJobscript() throws Exception {
		assertAttempt(false, 3, false, 1, 2, 2, false);
	}

	@Test
	public void conditionalCallerStillReceivesExhaustedFailure() throws Exception {
		assertAttempt(false, 3, true, 1, 2, 2, false);
	}

	@Test
	public void containerModeDoesNotContactTheDatabase() throws Exception {
		assertAttempt(true, 3, false, 0, 0, 0, true);
	}

	private void assertAttempt(boolean container, int successAt, boolean conditional,
			int exit, int attempts, int sleeps, boolean continued) throws Exception {
		Path dir = folder.newFolder().toPath();
		Path sge = Path.of("src/main/java/org/starexec/config/sge").toAbsolutePath();
		Path script = dir.resolve("probe.sh");
		String body = """
				export SCRIPT_DIR="$1" STAREXEC_OUTPUT_DIR="$2" CONTAINER_MODE="$3" PAIR_ID=41
				export SHARED_DIR="$2/shared" WORKING_DIR_BASE="$2/work"
				export BENCH_PATH=L2JlbmNoL3ByaW1hcnkucA== PAIR_OUTPUT_DIRECTORY=L291dC9wYWly
				SOLVER_PATHS=()
				. "$SCRIPT_DIR/functions.bash"
				# No connection is made: psql below is a deterministic shell stub.
				DB_PASS='' REPORT_HOST=unused DB_USER=unused DB_NAME=unused
				SUCCESS_AT="$4"
				log() { :; }
				sleep() { echo "$1" >> "$STAREXEC_OUTPUT_DIR/sleeps"; }
				psql() {
					local n=0
					if [[ -f "$STAREXEC_OUTPUT_DIR/attempts" ]]; then
						read -r n < "$STAREXEC_OUTPUT_DIR/attempts"
					fi
					n=$((n + 1))
					echo "$n" > "$STAREXEC_OUTPUT_DIR/attempts"
					[[ "$n" -ge "$SUCCESS_AT" ]]
				}
				""";
		body += conditional
				? "if dbExec 'SELECT 1'; then touch \"$2/continued\"; else exit 1; fi\n"
				: "dbExec 'SELECT 1'\ntouch \"$2/continued\"\n";
		Files.writeString(script, body);
		Path output = dir.resolve("shell.log");
		Process process = new ProcessBuilder(Bash.PATH, script.toString(), sge.toString(),
				dir.toString(), Boolean.toString(container), Integer.toString(successAt))
				.redirectErrorStream(true).redirectOutput(output.toFile()).start();
		boolean finished = process.waitFor(10, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
		}
		assertTrue("retry loop must terminate", finished);
		assertEquals(Files.readString(output), exit, process.exitValue());
		Path counter = dir.resolve("attempts");
		assertEquals("exact number of database attempts", attempts,
				Files.exists(counter) ? Integer.parseInt(Files.readString(counter).trim()) : 0);
		Path waits = dir.resolve("sleeps");
		assertEquals("preserve retry delay behavior", sleeps,
				Files.exists(waits) ? Files.readAllLines(waits).size() : 0);
		assertEquals("only successful calls may continue execution", continued,
				Files.exists(dir.resolve("continued")));
	}
}
