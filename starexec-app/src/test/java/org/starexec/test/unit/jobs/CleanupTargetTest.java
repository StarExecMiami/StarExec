package org.starexec.test.unit.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.starexec.test.TemporaryFolders.newFolder;

/** Real cleanup callers under the helper's strict shell options; all files are disposable. */
public class CleanupTargetTest {
	@TempDir
	public File folder;

	@Test
	public void quotaCleanupRemovesTheRequestedOutput() throws Exception {
		Harness h = new Harness(folder, false);
		h.run("setDiskQuotaExceeded 2 1");
		h.assertOutputCleaned();
	}

	@Test
	public void aSpaceInTheOutputPathCannotSelectAnUnrelatedDirectory() throws Exception {
		Harness h = new Harness(folder, true);
		h.run("setDiskQuotaExceeded 2 1");
		h.assertOutputCleaned();
	}

	@Test
	public void failedBuildCleanupFinishesAfterDeletingItsBenchmark() throws Exception {
		Harness h = new Harness(folder, false);
		h.run("BUILD_JOB=true; cleanUpAfterKilledBuildJob");
		h.assertBenchmarkCleaned();
	}

	@Test
	public void failedBuildCleanupAcceptsSpacesInTheBenchmarkDirectory() throws Exception {
		Harness h = new Harness(folder, true);
		h.run("BUILD_JOB=true; cleanUpAfterKilledBuildJob");
		h.assertBenchmarkCleaned();
	}

	@Test
	public void aNonBuildJobKeepsItsBenchmark() throws Exception {
		Harness h = new Harness(folder, true);
		h.run("BUILD_JOB=false; cleanUpAfterKilledBuildJob");
		assertEquals("BENCHMARK", Files.readString(h.benchmark));
		assertEquals("KEEP", Files.readString(h.unrelated));
	}

	@Test
	public void zeroOutputDoesNotExceedZeroQuota() throws Exception {
		Harness h = new Harness(folder, true);
		Files.writeString(h.stdout, "");
		h.run("setDiskQuotaExceeded 2 1; test \"$DISK_QUOTA_EXCEEDED\" = 0");
		assertEquals("PARTIAL", Files.readString(h.output.resolve("partial")));
		assertEquals("KEEP", Files.readString(h.unrelated));
	}

	private static final class Harness {
		final Path root;
		final Path output;
		final Path benchmark;
		final Path stdout;
		final Path unrelated;

		Harness(File folder, boolean spaces) throws Exception {
			root = newFolder(folder, "fixture").toPath();
			output = Files.createDirectory(root.resolve(spaces ? "pair output" : "pair-output"));
			Files.writeString(output.resolve("partial"), "PARTIAL");
			Path benchDir = Files.createDirectory(root.resolve(spaces ? "build input" : "build-input"));
			benchmark = benchDir.resolve("benchmark.p");
			Files.writeString(benchmark, "BENCHMARK");
			stdout = root.resolve("stdout.txt");
			Files.writeString(stdout, "RESULT");
			unrelated = Files.createDirectory(root.resolve("output")).resolve("keep");
			Files.writeString(unrelated, "KEEP");
		}

		void run(String operation) throws Exception {
			ProcessBuilder builder = new ProcessBuilder(Bash.PATH, "-c",
					"SOLVER_PATHS=(); source \"$SCRIPT_DIR/functions.bash\"; log() { :; }; "
							+ "dbExec() { :; }; setRemainingDiskQuota() { REMAINING_DISK_QUOTA=0; }; "
							+ operation);
			var env = builder.environment();
			env.put("SCRIPT_DIR", Path.of("src/main/java/org/starexec/config/sge").toAbsolutePath().toString());
			env.put("SHARED_DIR", root.toString());
			env.put("WORKING_DIR_BASE", root.toString());
			env.put("BENCH_PATH", encode(benchmark));
			env.put("PAIR_OUTPUT_DIRECTORY", encode(output));
			env.put("CONTAINER_MODE", "true");
			env.put("PAIR_ID", "42");
			env.put("SOLVER_ID", "1");
			env.put("BENCH_ID", "1");
			env.put("BENCH_NAME", "benchmark.p");
			env.put("STDOUT_FILE", stdout.toString());
			env.put("DISK_QUOTA_EXCEEDED", "0");
			Path log = root.resolve("cleanup.log");
			builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
			Process process = builder.start();
			try {
				assertTrue(process.waitFor(10, TimeUnit.SECONDS), "cleanup must finish");
				assertEquals(0, process.exitValue(), Files.readString(log));
			} finally {
				process.destroyForcibly();
				assertTrue(process.waitFor(5, TimeUnit.SECONDS), "cleanup must be reaped");
			}
		}

		void assertOutputCleaned() throws Exception {
			assertTrue(Files.isDirectory(output));
			assertFalse(Files.exists(output.resolve("partial")), "the requested output must be removed");
			assertEquals("KEEP", Files.readString(unrelated));
			assertEquals("BENCHMARK", Files.readString(benchmark));
		}

		void assertBenchmarkCleaned() throws Exception {
			assertTrue(Files.isDirectory(benchmark.getParent()));
			assertFalse(Files.exists(benchmark), "the build benchmark must be removed");
			assertEquals("KEEP", Files.readString(unrelated));
			assertEquals("PARTIAL", Files.readString(output.resolve("partial")));
		}

		private static String encode(Path path) {
			return Base64.getEncoder().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
		}
	}
}
