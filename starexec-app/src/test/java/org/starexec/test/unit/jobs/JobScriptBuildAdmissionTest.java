package org.starexec.test.unit.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JobScriptBuildAdmissionTest {
    @TempDir Path directory;

    @Test void refusesAnOlderHelperBeforeExecutingABuild() throws Exception {
        Result result = run(true, true, "", "encoded-output", false);
        assertNotEquals(0, result.exit());
        assertFalse(result.output().contains("WORK_STARTED"));
    }

    @Test void anInheritedProtocolCannotAuthorizeAnOlderHelper() throws Exception {
        Result result = run(true, true, "", "encoded-output", true);
        assertNotEquals(0, result.exit());
        assertFalse(result.output().contains("WORK_STARTED"));
    }

    @Test void refusesAMissingStagingDestination() throws Exception {
        Result result = run(true, true, "STAREXEC_BUILD_STAGING_PROTOCOL=1\n", null, false);
        assertNotEquals(0, result.exit());
        assertFalse(result.output().contains("WORK_STARTED"));
    }

    @Test void refusesAnUnknownProtocol() throws Exception {
        Result result = run(true, true, "STAREXEC_BUILD_STAGING_PROTOCOL=2\n", "encoded-output", false);
        assertNotEquals(0, result.exit());
        assertFalse(result.output().contains("WORK_STARTED"));
    }

    @Test void admitsTheMatchingBuildProtocol() throws Exception {
        Result result = run(true, true, "STAREXEC_BUILD_STAGING_PROTOCOL=1\n", "encoded-output", false);
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("WORK_STARTED"));
    }

    @Test void ordinaryContainerJobsDoNotRequireBuildStaging() throws Exception {
        Result result = run(false, true, "", null, false);
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("WORK_STARTED"));
    }

    @Test void legacyDatabaseConnectedBuildsKeepTheirExistingProtocol() throws Exception {
        Result result = run(true, false, "", null, false);
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("WORK_STARTED"));
    }

    private Result run(boolean build, boolean container, String helper, String staging,
                       boolean inheritedProtocol) throws Exception {
        Path source = Path.of("src/main/java/org/starexec/config/sge/jobscript");
        if (!Files.exists(source)) source = Path.of("starexec-app").resolve(source);
        String template = Files.readString(source);
        String boundary = "# Array of secondary benchmarks starexec paths";
        assertTrue(template.contains(boundary));
        String prefix = template.substring(0, template.indexOf(boundary))
                .replace("$$BUILD_JOB$$", Boolean.toString(build))
                .replace("$$SCRIPT_DIR$$", directory.toString());
        Files.writeString(directory.resolve("functions.bash"), helper);
        Path script = directory.resolve("job.sh");
        Files.writeString(script, prefix + "\nprintf 'WORK_STARTED\\n'\n");
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", script.toString());
        builder.environment().put("CONTAINER_MODE", Boolean.toString(container));
        builder.environment().remove("STAREXEC_BUILD_OUTPUT_DIR");
        builder.environment().remove("STAREXEC_BUILD_STAGING_PROTOCOL");
        if (staging != null) builder.environment().put("STAREXEC_BUILD_OUTPUT_DIR", staging);
        if (inheritedProtocol) builder.environment().put("STAREXEC_BUILD_STAGING_PROTOCOL", "1");
        Path output = directory.resolve("stdout");
        Process process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly().waitFor();
        assertTrue(exited, "build admission must finish without running a solver");
        return new Result(process.exitValue(), Files.readString(output));
    }

    private record Result(int exit, String output) { }
}
