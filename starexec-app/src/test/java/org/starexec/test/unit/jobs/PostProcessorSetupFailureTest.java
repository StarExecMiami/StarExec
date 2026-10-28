package org.starexec.test.unit.jobs;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.starexec.test.TemporaryFolders.newFolder;

/**
 * Failures while preparing a post-processor are attributed to that post-processor and stage.
 *
 * <p>The shipped helper runs under {@code set -e}. An unguarded setup command therefore exits
 * through {@code exitJobscript}, whose deliberately generic fallback cannot know which component
 * failed. Each parameter deterministically fails one setup operation and checks the terminal file
 * consumed by the container monitors.
 */
public class PostProcessorSetupFailureTest {

	private static final Path SGE = Path.of("src/main/java/org/starexec/config/sge");
	private static final int ERROR_POST_PROCESSOR = 26;
	private static final int STAGE = 3;

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
	public String operation;
	public String failureInjection;

	@TempDir
	public File folder;

	@MethodSource("setupFailures")
	@ParameterizedTest(name = "{0}")
	public void setupFailureNamesThePostProcessorAndCurrentStage(String operation, String failureInjection) throws Exception {
		initPostProcessorSetupFailureTest(operation, failureInjection);
		Path root = newFolder(folder, operation).toPath();
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
		assertEquals(0, parse.exit, "generated script must parse:\n" + parse.output);

		Result run = exec(Bash.PATH, file.getAbsolutePath());
		assertTrue(run.exit != 0, "the injected setup failure must stop copyOutput:\n" + run.output);

		Path statusFile = output.resolve("status.json");
		assertTrue(Files.isRegularFile(statusFile),
				"the failure must leave terminal evidence:\n" + run.output);
		String status = Files.readString(statusFile);
		assertEquals(ERROR_POST_PROCESSOR, field(status, "status"), "the component that failed");
		assertEquals(STAGE, field(status, "stageNumber"),
				"the stage whose post-processor was being prepared");
		Path snapshotFile = output.resolve("stage-status").resolve(STAGE + ".json");
		assertTrue(Files.isRegularFile(snapshotFile), "the per-stage evidence must be published");
		String snapshot = Files.readString(snapshotFile);
		assertEquals(ERROR_POST_PROCESSOR, field(snapshot, "status"));
		assertEquals(STAGE, field(snapshot, "stageNumber"));
	}

	private static int field(String json, String name) {
		Matcher matcher = Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*(-?\\d+)").matcher(json);
		assertTrue(matcher.find(), name + " must be present in " + json);
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException e) {
			// The pattern admits only digits, so this is an out-of-range value: report it as
			// a failed assertion on the evidence, not as an error in the test itself.
			throw new AssertionError(name + " is not an int in " + json, e);
		}
	}

	private static Result exec(String... command) throws Exception {
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(process.waitFor(60, TimeUnit.SECONDS), "command must not hang");
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

	public void initPostProcessorSetupFailureTest(String operation, String failureInjection) {
		this.operation = operation;
		this.failureInjection = failureInjection;
	}
}
