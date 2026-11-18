package org.starexec.test.unit.jobs;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.starexec.test.TemporaryFolders.newFolder;

public class SnapshotWriteFailureTest {

	@TempDir
	public File folder;

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
		assertTrue(Files.isDirectory(out.resolve("status.json")), "fault injection must prevent writing the fallback");
	}

	private Path runFailure(boolean breakFallback) throws Exception {
		Path dir = newFolder(folder).toPath();
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
		assertTrue(finished, "storage failure must terminate promptly");
		assertEquals(1, process.exitValue(), Files.readString(log));
		assertFalse(Files.readString(log).contains("maximum function nesting"), "must not re-enter the failing writer");
		assertEquals(1, Files.readAllLines(dir.resolve("failures")).size(), "only one snapshot failure may be attempted");
		assertEquals("true", Files.readString(dir.resolve("status-sent")).trim(), "EXIT handling must not invent a benchmark failure");
		assertFalse(Files.exists(dir.resolve("continued")), "the job must not continue after storage failure");
		return dir;
	}
}
