package org.starexec.test.unit.jobs;

import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SnapshotWriteFailureTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void snapshotDirectoryFailureReportsThePreciseStageWithoutRecursion() throws Exception {
		Path out = runFailure(false);
		var status = JsonParser.parseString(Files.readString(out.resolve("status.json"))).getAsJsonObject();
		assertEquals(41, status.get("pairId").getAsInt());
		assertEquals(2, status.get("stageNumber").getAsInt());
		assertEquals(18, status.get("status").getAsInt());
	}

	@Test
	public void failureOfTheFallbackWriteStillExitsWithoutRecursion() throws Exception {
		Path out = runFailure(true);
		assertTrue("fault injection must prevent writing the fallback", Files.isDirectory(out.resolve("status.json")));
	}

	private Path runFailure(boolean breakFallback) throws Exception {
		Path dir = folder.newFolder().toPath();
		Path sge = Path.of("src/main/java/org/starexec/config/sge").toAbsolutePath();
		Path script = dir.resolve("probe.sh");
		Files.writeString(script, """
				export SCRIPT_DIR="$1" STAREXEC_OUTPUT_DIR="$2" CONTAINER_MODE=true PAIR_ID=41
				export SHARED_DIR="$2/shared" WORKING_DIR_BASE="$2/work"
				export BENCH_PATH=L2JlbmNoL3ByaW1hcnkucA== PAIR_OUTPUT_DIRECTORY=L291dC9wYWly
				SOLVER_PATHS=()
				. "$SCRIPT_DIR/functions.bash"
				BREAK_FALLBACK="$3"
				log() {
					if [[ "$1" == 'job error: could not record'* ]]; then
						echo failed >> "$STAREXEC_OUTPUT_DIR/failures"
						if [[ "$BREAK_FALLBACK" == true ]]; then
							rm "$CONTAINER_STATUS_FILE"
							mkdir "$CONTAINER_STATUS_FILE"
						fi
					fi
				}
				# Bound the buggy version without relying on a shell crash or memory exhaustion.
				FUNCNEST=12
				trap 'echo "$STATUS_SENT" > "$STAREXEC_OUTPUT_DIR/status-sent"' EXIT
				touch "$STAREXEC_OUTPUT_DIR/block"
				CONTAINER_STAGE_STATUS_DIR="$STAREXEC_OUTPUT_DIR/block/child"
				containerWriteStatus "$STATUS_COMPLETE" 2
				touch "$STAREXEC_OUTPUT_DIR/continued"
				""");
		Path log = dir.resolve("shell.log");
		Process process = new ProcessBuilder(Bash.PATH, script.toString(), sge.toString(),
				dir.toString(), Boolean.toString(breakFallback))
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		boolean finished = process.waitFor(10, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
		}
		assertTrue("storage failure must terminate promptly", finished);
		assertEquals(Files.readString(log), 1, process.exitValue());
		assertFalse("must not re-enter the failing writer", Files.readString(log).contains("maximum function nesting"));
		assertEquals("only one snapshot failure may be attempted", 1, Files.readAllLines(dir.resolve("failures")).size());
		assertEquals("EXIT handling must not invent a benchmark failure", "true", Files.readString(dir.resolve("status-sent")).trim());
		assertFalse("the job must not continue after storage failure", Files.exists(dir.resolve("continued")));
		return dir;
	}
}
