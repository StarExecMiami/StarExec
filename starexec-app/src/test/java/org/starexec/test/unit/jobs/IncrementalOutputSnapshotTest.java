package org.starexec.test.unit.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * #159: {@code copyOutputIncrementally} must not take {@code $OUT_DIR/output_files} away from
 * a solver that is still writing to it, and must survive more than one interval.
 *
 * <p>Runs the shipped {@code functions.bash} with a fake solver (a background loop that keeps
 * creating files in the live directory by path, and holds one file open). Needs no database:
 * the disk-quota probe is stubbed, everything else is the real helper.
 */
public class IncrementalOutputSnapshotTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final String PROBE = """
			export SCRIPT_DIR="$1" STAREXEC_OUTPUT_DIR="$2/starexec-out" CONTAINER_MODE=true PAIR_ID=7
			export SHARED_DIR="$2/shared" WORKING_DIR_BASE="$2/work"
			export BENCH_PATH=L2JlbmNoL3ByaW1hcnkucA== PAIR_OUTPUT_DIRECTORY=L291dC9wYWly
			SOLVER_PATHS=()
			. "$SCRIPT_DIR/functions.bash"
			W="$2"
			OUT_DIR="$W/out"; STDOUT_FILE="$W/stdout.txt"
			PAIR_OUTPUT_DIRECTORY="$W/pair"; SAVED_OUTPUT_DIR="$W/saved"
			NUM_STAGES=1; DISK_QUOTA_EXCEEDED=0
			setDiskQuotaExceeded() { :; }
			mkdir -p "$OUT_DIR/output_files"; echo solver > "$STDOUT_FILE"
			INODE_BEFORE=$(stat -c %i "$OUT_DIR/output_files")

			# Fake solver: new files by path, one file held open across the whole run.
			(
				exec 3> "$OUT_DIR/output_files/held.txt"
				N=0
				while [ ! -e "$W/solver-stop" ]; do
					N=$(( N + 1 ))
					echo "line $N" >&3
					echo "data $N" > "$OUT_DIR/output_files/f$N.txt" 2>> "$W/solver-errors" || echo "write failed $N" >> "$W/solver-errors"
					sleep 0.1
				done
			) &
			SOLVER_PID=$!

			copyOutputIncrementally 1 3 1 "$4" "$3"
			echo "copier-survived" > "$W/copier-survived"

			touch "$W/solver-stop"; wait "$SOLVER_PID"
			INODE_AFTER=$(stat -c %i "$OUT_DIR/output_files")
			echo "$INODE_BEFORE $INODE_AFTER" > "$W/inodes"

			# The solver has exited: the final copy keeps its move semantics.
			copyOutputNoStats 1 "$4" "$3"
			echo "final-done" > "$W/final-done"
			""";

	@Test
	public void incrementalSnapshotsNeverTakeTheLiveDirectoryAway() throws Exception {
		Path dir = run(0, 0);

		String[] inodes = Files.readString(dir.resolve("inodes")).trim().split(" ");
		assertEquals("output_files must keep its identity across every interval", inodes[0], inodes[1]);
		assertTrue("the incremental copier must survive three intervals",
				Files.exists(dir.resolve("copier-survived")));
		assertFalse("the solver must never fail to write into its directory: "
				+ read(dir.resolve("solver-errors")), Files.exists(dir.resolve("solver-errors"))
				&& Files.size(dir.resolve("solver-errors")) > 0);

		// Final copy moves the (still complete) live directory, as before.
		assertTrue("final copy must complete", Files.exists(dir.resolve("final-done")));
		assertFalse("final copy moves the live directory once the solver is gone",
				Files.exists(dir.resolve("out/output_files")));
		assertTrue(Files.exists(dir.resolve("pair/7_output/held.txt")));
		assertTrue(Files.exists(dir.resolve("saved/1_output/held.txt")));
		assertTrue("later output must be in the final copy, not lost with an early snapshot",
				Files.exists(dir.resolve("pair/7_output/f5.txt")));
		assertNoTmpLeft(dir);
	}

	@Test
	public void optionOneStillSnapshotsIntoTheSavedDirectoryWithoutMoving() throws Exception {
		Path dir = run(1, 0);

		String[] inodes = Files.readString(dir.resolve("inodes")).trim().split(" ");
		assertEquals(inodes[0], inodes[1]);
		assertTrue(Files.exists(dir.resolve("copier-survived")));
		assertTrue(Files.exists(dir.resolve("saved/1_output/held.txt")));
		assertFalse("option 1 does not populate the pair output directory",
				Files.exists(dir.resolve("pair/7_output")));
	}

	private static void assertNoTmpLeft(Path dir) throws Exception {
		try (var s = Files.walk(dir)) {
			assertFalse("no half-written snapshot may remain",
					s.anyMatch(p -> p.getFileName().toString().endsWith(".incremental.tmp")));
		}
	}

	private static String read(Path p) throws Exception {
		return Files.exists(p) ? Files.readString(p) : "";
	}

	private Path run(int extraSaveOption, int stdoutSaveOption) throws Exception {
		Path dir = folder.newFolder().toPath();
		Path sge = Path.of("src/main/java/org/starexec/config/sge").toAbsolutePath();
		Path script = dir.resolve("probe.sh");
		Files.writeString(script, PROBE);
		Path log = dir.resolve("shell.log");
		Process process = new ProcessBuilder(Bash.PATH, script.toString(), sge.toString(),
				dir.toString(), Integer.toString(extraSaveOption), Integer.toString(stdoutSaveOption))
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		boolean finished = process.waitFor(60, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
		}
		assertTrue("probe must finish", finished);
		String output = Files.readString(log);
		assertEquals(output, 0, process.exitValue());
		return dir;
	}
}
