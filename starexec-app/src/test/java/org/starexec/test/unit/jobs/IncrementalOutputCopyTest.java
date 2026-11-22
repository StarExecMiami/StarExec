package org.starexec.test.unit.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the solver's live output directory while periodic snapshots are published. */
public class IncrementalOutputCopyTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");

	@TempDir
	public Path root;

	@Test
	public void incrementalSnapshotsKeepTheLiveOutputPathAvailable() throws Exception {
		Path output = Files.createDirectories(root.resolve("workspace/output_files"));
		Path pairOutput = Files.createDirectory(root.resolve("pair-output"));
		Path savedOutput = Files.createDirectory(root.resolve("saved-output"));
		Path stdout = Files.writeString(root.resolve("stdout.txt"), "solver output\n");
		Path script = root.resolve("snapshot-test.sh");
		Files.writeString(script, """
				#!/usr/bin/env bash
				set -eo pipefail
				PAIR_ID=42
				source "$SCRIPT_DIR/functions.bash"
				set -u
				log() { :; }
				setDiskQuotaExceeded() { :; }
				DISK_QUOTA_EXCEEDED=0
				NUM_STAGES=1
				writer() {
				  for i in {1..300}; do
				    [[ -d "$OUT_DIR/output_files" ]] || exit 17
				    printf '%s\\n' "$i" > "$OUT_DIR/output_files/result-$i"
				    sleep 0.01
				  done
			}
			writer & writer_pid=$!
			copyOutputIncrementally 1 3 1 2 2
			wait "$writer_pid"
			[[ -d "$OUT_DIR/output_files" ]]
			[[ -f "$OUT_DIR/output_files/result-300" ]]
			[[ -f "$PAIR_OUTPUT_DIRECTORY/42_output/result-1" ]]
			[[ -f "$SAVED_OUTPUT_DIR/1_output/result-1" ]]
			""");

		ProcessBuilder builder = new ProcessBuilder("/bin/bash", script.toString());
		builder.environment().put("SCRIPT_DIR", SGE.toAbsolutePath().toString());
		builder.environment().put("OUT_DIR", output.getParent().toString());
		builder.environment().put("PAIR_OUTPUT_DIRECTORY", encode(pairOutput));
		builder.environment().put("SAVED_OUTPUT_DIR", savedOutput.toString());
		builder.environment().put("STDOUT_FILE", stdout.toString());
		builder.environment().put("BENCH_PATH", encode(root.resolve("benchmark.p")));
		builder.environment().put("WORKING_DIR_BASE", root.toString());
		builder.environment().put("SHARED_DIR", root.toString());
		Path log = root.resolve("harness.log");
		builder.redirectErrorStream(true).redirectOutput(log.toFile());
		Process process = builder.start();
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "snapshot harness timed out");
			assertEquals(0, process.exitValue(),
					"live output path must remain available to the solver: " + Files.readString(log));
		} finally {
			process.destroyForcibly();
			assertTrue(process.waitFor(5, TimeUnit.SECONDS), "snapshot harness must be reaped");
		}
	}

	private static String encode(Path path) {
		return Base64.getEncoder().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
	}
}
