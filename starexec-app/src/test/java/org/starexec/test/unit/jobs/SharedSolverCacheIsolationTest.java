package org.starexec.test.unit.jobs;

import com.google.gson.JsonParser;
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

/** Exercises the shipped workspace verifier against a cache another attempt is reading. */
public class SharedSolverCacheIsolationTest {

	@TempDir
	public File folder;

	@Test
	public void aMissingConfigurationCannotDeleteAnActiveReadersSource() throws Exception {
		Harness h = new Harness(folder);
		Path copied = Files.createDirectory(h.root.resolve("reader copy"));
		Path ready = h.root.resolve("reader-ready");
		Path resume = h.root.resolve("reader-resume");
		ProcessBuilder builder = new ProcessBuilder(Bash.PATH, "-c",
				"set -euo pipefail; cp \"$1/first\" \"$2/first\"; touch \"$3\"; "
						+ "for ((i=0; i<500; i++)); do "
						+ "if [ -f \"$4\" ]; then cp \"$1/second\" \"$2/second\"; exit 0; fi; "
						+ "sleep 0.02; done; exit 99",
				"cache-reader", h.cache.toString(), copied.toString(), ready.toString(), resume.toString());
		builder.redirectErrorStream(true).redirectOutput(h.root.resolve("reader.log").toFile());
		Process reader = builder.start();
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (!Files.exists(ready) && reader.isAlive() && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}
			assertTrue(Files.exists(ready),
					"the reader must copy the first file before the other attempt fails");
			assertEquals(1, h.verify());
			h.assertStatus(11);
			Files.createFile(resume);
			assertTrue(reader.waitFor(10, TimeUnit.SECONDS), "the reader must finish");
			assertEquals(0,
					reader.exitValue(), "a different attempt's failure must not remove this reader's source");
			assertEquals("FIRST\n", Files.readString(copied.resolve("first")));
			assertEquals("SECOND\n", Files.readString(copied.resolve("second")));
			h.assertCacheIntact();
		} finally {
			reader.destroyForcibly();
			assertTrue(reader.waitFor(5, TimeUnit.SECONDS), "the reader must be reaped");
		}
	}

	@Test
	public void aValidWorkspaceStillPassesWithoutChangingTheCache() throws Exception {
		Harness h = new Harness(folder);
		h.createConfiguration();
		Files.writeString(h.benchmark, "benchmark\n");
		assertEquals(0, h.verify());
		assertFalse(Files.exists(h.output.resolve("status.json")));
		h.assertCacheIntact();
	}

	@Test
	public void aMissingBenchmarkStillReportsItsOwnFailure() throws Exception {
		Harness h = new Harness(folder);
		h.createConfiguration();
		assertEquals(1, h.verify());
		h.assertStatus(12);
		h.assertCacheIntact();
	}

	@Test
	public void aNonExecutableConfigurationStillFailsAndPreservesTheCache() throws Exception {
		Harness h = new Harness(folder);
		Files.writeString(h.config, "not executable\n");
		assertEquals(1, h.verify());
		h.assertStatus(11);
		h.assertCacheIntact();
	}

	private static final class Harness {
		final Path root;
		final Path cache;
		final Path output;
		final Path config;
		final Path benchmark;

		Harness(File folder) throws Exception {
			root = newFolder(folder, "cache isolation").toPath();
			cache = Files.createDirectory(root.resolve("shared cache"));
			Files.createDirectory(cache.resolve("finished.lock"));
			Files.createDirectory(cache.resolve("lock.lock"));
			Files.writeString(cache.resolve("first"), "FIRST\n");
			Files.writeString(cache.resolve("second"), "SECOND\n");
			output = Files.createDirectory(root.resolve("output"));
			Path workspace = Files.createDirectory(root.resolve("private workspace"));
			config = workspace.resolve("configuration");
			benchmark = workspace.resolve("benchmark.p");
		}

		void createConfiguration() throws Exception {
			Files.writeString(config, "#!/bin/bash\nexit 0\n");
			assertTrue(config.toFile().setExecutable(true));
		}

		int verify() throws Exception {
			ProcessBuilder builder = new ProcessBuilder(Bash.PATH, "-c",
					"SOLVER_PATHS=(); source \"$SCRIPT_DIR/functions.bash\"; log() { :; }; verifyWorkspace");
			var env = builder.environment();
			env.put("SCRIPT_DIR", Path.of("src/main/java/org/starexec/config/sge").toAbsolutePath().toString());
			env.put("SHARED_DIR", root.toString());
			env.put("WORKING_DIR_BASE", root.toString());
			env.put("BENCH_PATH", encode(benchmark));
			env.put("PAIR_OUTPUT_DIRECTORY", encode(output));
			env.put("CONTAINER_MODE", "true");
			env.put("PAIR_ID", "41");
			env.put("STAREXEC_OUTPUT_DIR", output.toString());
			env.put("SOLVER_CACHE_PATH", cache.toString());
			env.put("LOCAL_CONFIG_PATH", config.toString());
			env.put("CONFIG_NAME", "configuration");
			env.put("LOCAL_BENCH_PATH", benchmark.toString());
			env.put("BENCH_NAME", "benchmark.p");
			Path log = root.resolve("verifier.log");
			builder.redirectErrorStream(true).redirectOutput(log.toFile());
			Process process = builder.start();
			try {
				assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the verifier must finish");
				return process.exitValue();
			} finally {
				process.destroyForcibly();
				assertTrue(process.waitFor(5, TimeUnit.SECONDS), "the verifier must be reaped");
			}
		}

		void assertCacheIntact() throws Exception {
			assertEquals("FIRST\n", Files.readString(cache.resolve("first")));
			assertEquals("SECOND\n", Files.readString(cache.resolve("second")));
			assertTrue(Files.isDirectory(cache.resolve("finished.lock")));
			assertTrue(Files.isDirectory(cache.resolve("lock.lock")));
		}

		void assertStatus(int expected) throws Exception {
			var status = JsonParser.parseString(Files.readString(output.resolve("status.json"))).getAsJsonObject();
			assertEquals(41, status.get("pairId").getAsInt());
			assertEquals(expected, status.get("status").getAsInt());
			assertEquals(0, status.get("stageNumber").getAsInt());
		}

		private static String encode(Path path) {
			return Base64.getEncoder().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
		}
	}
}
