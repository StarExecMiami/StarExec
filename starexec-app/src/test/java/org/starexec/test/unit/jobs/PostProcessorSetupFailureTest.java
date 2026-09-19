package org.starexec.test.unit.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Failures while preparing a post-processor are attributed to that post-processor and stage.
 *
 * <p>The shipped helper runs under {@code set -e}. An unguarded setup command therefore exits
 * through {@code exitJobscript}, whose deliberately generic fallback cannot know which component
 * failed. Each parameter deterministically fails one setup operation and checks the terminal file
 * consumed by the container monitors.
 */
@RunWith(Parameterized.class)
public class PostProcessorSetupFailureTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");
	private static final int ERROR_POST_PROCESSOR = 26;
	private static final int STAGE = 3;

	@Parameterized.Parameters(name = "{0}")
	public static Object[][] setupFailures() {
		return new Object[][] {
				{"create-directory",
						"function mkdir {\n"
								+ "  if [[ \"${!#}\" == \"$OUT_DIR/postProcessor\" ]]; then return 97; fi\n"
								+ "  command mkdir \"$@\"\n"
								+ "}\n"},
				{"copy-files", "function safeCpAll { return 97; }\n"},
				{"recursive-permissions",
						"function chmod {\n"
								+ "  if [[ \"${!#}\" == \"$OUT_DIR/postProcessor\" ]]; then return 97; fi\n"
								+ "  command chmod \"$@\"\n"
								+ "}\n"},
				{"file-permissions",
						"function find {\n"
								+ "  if [[ \"${1:-}\" == \"$OUT_DIR/postProcessor\" && \"${3:-}\" == f ]]; then return 97; fi\n"
								+ "  command find \"$@\"\n"
								+ "}\n"},
				{"directory-permissions",
						"function find {\n"
								+ "  if [[ \"${1:-}\" == \"$OUT_DIR/postProcessor\" && \"${3:-}\" == d ]]; then return 97; fi\n"
								+ "  command find \"$@\"\n"
								+ "}\n"},
				{"enter-directory",
						"function cd {\n"
								+ "  if [[ \"${1:-}\" == \"$OUT_DIR/postProcessor\" ]]; then return 97; fi\n"
								+ "  builtin cd \"$@\"\n"
								+ "}\n"}
		};
	}

	@Parameterized.Parameter(0)
	public String operation;

	@Parameterized.Parameter(1)
	public String failureInjection;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void setupFailureNamesThePostProcessorAndCurrentStage() throws Exception {
		Path root = folder.newFolder(operation).toPath();
		Path sge = root.resolve("sge");
		Path output = root.resolve("evidence");
		Path postProcessor = root.resolve("uploaded-postprocessor");
		Files.createDirectories(sge);
		Files.createDirectories(postProcessor);
		Files.writeString(postProcessor.resolve("process"), "#!/bin/bash\nexit 0\n");
		for (String name : new String[] {"functions.bash", "status_codes.bash"}) {
			Files.copy(SGE.resolve(name), sge.resolve(name));
		}

		String script = "export SCRIPT_DIR='" + sge + "'\n"
				+ "export STAREXEC_OUTPUT_DIR='" + output + "'\n"
				+ "export CONTAINER_MODE=true\n"
				+ "export PAIR_ID=145\n"
				+ "export SHARED_DIR='" + root + "/shared'\n"
				+ "export WORKING_DIR_BASE='" + root + "/work-base'\n"
				+ "export BENCH_PATH=\"$(printf '/bench/primary.p' | base64 -w0)\"\n"
				+ "export PAIR_OUTPUT_DIRECTORY=\"$(printf '/out/pair' | base64 -w0)\"\n"
				+ "SOLVER_PATHS=()\n"
				+ ". \"$SCRIPT_DIR/functions.bash\"\n"
				+ "trap 'exitJobscript $?' EXIT\n"
				+ "CURRENT_STAGE_NUMBER=" + STAGE + "\n"
				+ "WORKING_DIR='" + root + "/work'\n"
				+ "OUT_DIR=\"$WORKING_DIR/output\"\n"
				+ "VARFILE=\"$OUT_DIR/var.out\"\n"
				+ "WATCHFILE=\"$OUT_DIR/watcher.out\"\n"
				+ "STDOUT_FILE=\"$OUT_DIR/stdout.txt\"\n"
				+ "LOCAL_BENCH_PATH='" + root + "/primary.p'\n"
				+ "POST_PROCESSOR_PATH='" + postProcessor + "'\n"
				+ "POST_PROCESSOR_TIME_LIMIT=1\n"
				+ "RUNSOLVER=RUNSOLVER\n"
				+ "mkdir -p \"$OUT_DIR/output_files\"\n"
				+ ": > \"$VARFILE\"\n"
				+ ": > \"$WATCHFILE\"\n"
				+ ": > \"$STDOUT_FILE\"\n"
				+ "function updateStats { :; }\n"
				+ "function copyOutputNoStats { :; }\n"
				+ failureInjection
				+ "copyOutput " + STAGE + " 1 1 \"$RUNSOLVER\"\n";

		File file = root.resolve("run.sh").toFile();
		Files.writeString(file.toPath(), script);
		Result parse = exec(Bash.PATH, "-n", file.getAbsolutePath());
		assertEquals("generated script must parse:\n" + parse.output, 0, parse.exit);

		Result run = exec(Bash.PATH, file.getAbsolutePath());
		assertTrue("the injected setup failure must stop copyOutput:\n" + run.output, run.exit != 0);

		Path statusFile = output.resolve("status.json");
		assertTrue("the failure must leave terminal evidence:\n" + run.output,
				Files.isRegularFile(statusFile));
		String status = Files.readString(statusFile);
		assertEquals("the component that failed", ERROR_POST_PROCESSOR, field(status, "status"));
		assertEquals("the stage whose post-processor was being prepared", STAGE,
				field(status, "stageNumber"));
		Path snapshotFile = output.resolve("stage-status").resolve(STAGE + ".json");
		assertTrue("the per-stage evidence must be published", Files.isRegularFile(snapshotFile));
		String snapshot = Files.readString(snapshotFile);
		assertEquals(ERROR_POST_PROCESSOR, field(snapshot, "status"));
		assertEquals(STAGE, field(snapshot, "stageNumber"));
	}

	private static int field(String json, String name) {
		Matcher matcher = Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*(-?\\d+)").matcher(json);
		assertTrue(name + " must be present in " + json, matcher.find());
		return Integer.parseInt(matcher.group(1));
	}

	private static Result exec(String... command) throws Exception {
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue("command must not hang", process.waitFor(60, TimeUnit.SECONDS));
		return new Result(process.exitValue(), output);
	}

	private static final class Result {
		final int exit;
		final String output;

		Result(int exit, String output) {
			this.exit = exit;
			this.output = output;
		}
	}
}
