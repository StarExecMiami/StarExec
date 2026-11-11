package org.starexec.test.unit.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.backend.storage.BuildExecutionMounts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JobScriptPrivateBuildPathsTest {
    @TempDir Path directory;

    private Path config() {
        Path root=Path.of("src/main/java/org/starexec/config/sge");
        return Files.isDirectory(root)?root:Path.of("starexec-app").resolve(root);
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String,String> environment() {
        Map<String,String> values=new LinkedHashMap<>();
        values.put(BuildExecutionMounts.PRIVATE_VARIABLE,"1");
        values.put("STAREXEC_BUILD_OUTPUT_DIR",encoded(BuildExecutionMounts.EXECUTION.toString()));
        values.put("STAREXEC_BUILD_SOURCE_DIR",BuildExecutionMounts.SOURCE.toString());
        values.put("STAREXEC_BUILD_HELPER_FILE",BuildExecutionMounts.HELPER.toString());
        values.put("STAREXEC_BUILD_CONTROL_DIR",BuildExecutionMounts.CONTROL.toString());
        values.put("STAREXEC_BUILD_WORK_DIR",BuildExecutionMounts.EXECUTION.resolve("work").toString());
        values.put("STAREXEC_OUTPUT_DIR",BuildExecutionMounts.EXECUTION.resolve("monitor").toString());
        values.put("HOME",BuildExecutionMounts.EXECUTION.resolve("home").toString());
        values.put("TMPDIR","/tmp");
        values.put("CONTAINER_MODE","true");
        return values;
    }

    private Result execute(String script,Map<String,String> values) throws Exception {
        Path file=Files.createTempFile(directory,"probe-",".bash");Files.writeString(file,script);
        ProcessBuilder builder=new ProcessBuilder("/bin/bash","-eu",file.toString());
        for (String key:environment().keySet()) builder.environment().remove(key);
        builder.environment().putAll(values);
        Path output=Files.createTempFile(directory,"output-",".txt");
        Process child=builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean done=child.waitFor(10,TimeUnit.SECONDS);
        if (!done) child.destroyForcibly().waitFor();
        assertTrue(done,"bounded bootstrap observation must terminate");
        return new Result(child.exitValue(),Files.readString(output));
    }

    private Result prefix(Map<String,String> environment,boolean build) throws Exception {
        String template=Files.readString(config().resolve("jobscript"));
        String boundary=". \"$SCRIPT_DIR/functions.bash\"";
        assertTrue(template.contains(boundary));
        String script=template.substring(0,template.indexOf(boundary))
                .replace("$$BUILD_JOB$$",Boolean.toString(build))
                .replace("$$SCRIPT_DIR$$",directory.resolve("legacy-control").toString())
                .replace("$$WORKING_DIR_BASE$$",directory.resolve("legacy-work").toString())
                .replace("$$STAREXEC_DATA_DIR$$",directory.resolve("legacy-data").toString())
                .replace("$$SCRIPT_PATH$$",directory.resolve("legacy-jobscript").toString())
                .replace("$$BENCH$$",encoded("/legacy/helper"))
                .replace("$$PAIR_OUTPUT_DIRECTORY$$",encoded("/legacy/output"))
                .replaceAll("\\$\\$[A-Z0-9_]+\\$\\$","0");
        script+="\nprintf 'BOOTSTRAP_READY\\n'\nprintf '%s\\n' \"$SCRIPT_DIR\" \"$SCRIPT_PATH\" \"$SHARED_DIR\" \"$WORKING_DIR_BASE\"\n"
                +"base64 -d <<< \"$BENCH_PATH\"; printf '\\n'; base64 -d <<< \"$PAIR_OUTPUT_DIRECTORY\"; printf '\\n'\n";
        return execute(script,environment);
    }

    @Test void privateBootstrapReplacesEverySharedControlAndDataPath() throws Exception {
        Result result=prefix(environment(),true);
        assertEquals(0,result.exit(),result.output());
        assertTrue(result.output().contains("BOOTSTRAP_READY\n/starexec/control\n/starexec/control/jobscript\n"
                +"/starexec/execution\n/starexec/execution/work\n/starexec/inputs/starexec_build\n/starexec/execution/monitor\n"),result.output());
        assertFalse(result.output().contains("legacy-"),result.output());
    }

    @ParameterizedTest @ValueSource(strings={"STAREXEC_BUILD_OUTPUT_DIR","STAREXEC_BUILD_SOURCE_DIR",
            "STAREXEC_BUILD_HELPER_FILE","STAREXEC_BUILD_CONTROL_DIR","STAREXEC_BUILD_WORK_DIR",
            "STAREXEC_OUTPUT_DIR","HOME","TMPDIR"})
    void missingPrivatePathsRefuseBeforeLoadingAnyHelper(String key) throws Exception {
        var values=environment();values.remove(key);Result result=prefix(values,true);
        assertNotEquals(0,result.exit());assertFalse(result.output().contains("BOOTSTRAP_READY"));
    }

    @ParameterizedTest @ValueSource(strings={"STAREXEC_BUILD_OUTPUT_DIR","STAREXEC_BUILD_SOURCE_DIR",
            "STAREXEC_BUILD_HELPER_FILE","STAREXEC_BUILD_CONTROL_DIR","STAREXEC_BUILD_WORK_DIR",
            "STAREXEC_OUTPUT_DIR","HOME","TMPDIR"})
    void redirectedPrivatePathsRefuseBeforeLoadingAnyHelper(String key) throws Exception {
        var values=environment();values.put(key,"/foreign/shared-data");Result result=prefix(values,true);
        assertNotEquals(0,result.exit());assertFalse(result.output().contains("BOOTSTRAP_READY"));
    }

    @ParameterizedTest @ValueSource(strings={"0","true","2"})
    void unknownPrivateProtocolsAreNotLegacyFallbacks(String value) throws Exception {
        var values=environment();values.put(BuildExecutionMounts.PRIVATE_VARIABLE,value);
        Result result=prefix(values,true);assertNotEquals(0,result.exit());
        assertFalse(result.output().contains("BOOTSTRAP_READY"));
    }

    @Test void privatePathsCannotAuthorizeAnOrdinaryJobOrHostProcess() throws Exception {
        assertNotEquals(0,prefix(environment(),false).exit());
        var values=environment();values.put("CONTAINER_MODE","false");
        assertNotEquals(0,prefix(values,true).exit());
    }

    @Test void legacyBootstrapKeepsItsOriginalPaths() throws Exception {
        var values=environment();values.remove(BuildExecutionMounts.PRIVATE_VARIABLE);
        Result result=prefix(values,false);assertEquals(0,result.exit(),result.output());
        assertTrue(result.output().contains(directory.resolve("legacy-control").toString()));
        assertTrue(result.output().contains("/legacy/helper\n/legacy/output\n"));
    }

    private String functions() throws Exception {
        Path controls=Files.createTempDirectory(directory,"controls-");
        Files.copy(config().resolve("functions.bash"),controls.resolve("functions.bash"));
        Files.copy(config().resolve("status_codes.bash"),controls.resolve("status_codes.bash"));
        return "export SCRIPT_DIR='"+controls+"'\nexport WORKING_DIR_BASE='"+directory.resolve("work")+"'\n"
                +"export STAREXEC_OUTPUT_DIR='"+directory.resolve("monitor")+"'\n"
                +"export SHARED_DIR='"+directory.resolve("data")+"'\nexport PAIR_ID=11\n"
                +"export BENCH_PATH='"+encoded("/legacy/helper")+"'\nexport PAIR_OUTPUT_DIRECTORY='"+encoded("/legacy/output")+"'\n"
                +". \"$SCRIPT_DIR/functions.bash\"\n";
    }

    private String stage() {
        return "BUILD_JOB=true\nNUM_STAGES=1\nNUM_BENCH_INPUTS=0\nHAS_DEPENDS=0\nPRIMARY_PREPROCESSOR_PATH=''\n"
                +"SOLVER_PATHS=('"+encoded("/legacy/source_src")+"')\nSOLVER_IDS=(7)\n"
                +"PRE_PROCESSOR_PATHS=('')\nPOST_PROCESSOR_PATHS=('')\nSTDOUT_SAVE_OPTIONS=(2)\nEXTRA_SAVE_OPTIONS=(1)\n";
    }

    @Test void privateStageUsesOnlyTheConservedSourceAndSkipsTheLegacyImageShim() throws Exception {
        var values=environment();values.remove("STAREXEC_BUILD_OUTPUT_DIR");
        Result result=execute(functions()+stage()+"configurePrivateBuildStagePaths\nadjustForK8s\n"
                +"printf '%s\\n' \"$(base64 -d <<< \"${SOLVER_PATHS[0]}\")\"\n",values);
        assertEquals(0,result.exit(),result.output());assertTrue(result.output().contains("/starexec/inputs/source_src\n"));
        assertFalse(result.output().contains("Listing contents of:"));
    }

    @ParameterizedTest @ValueSource(strings={"NUM_STAGES=2","NUM_BENCH_INPUTS=1","HAS_DEPENDS=1",
            "PRIMARY_PREPROCESSOR_PATH=/foreign","PRE_PROCESSOR_PATHS=(/foreign)","POST_PROCESSOR_PATHS=(/foreign)",
            "SOLVER_IDS=(7 8)","SOLVER_PATHS=(a b)","STDOUT_SAVE_OPTIONS=(3)","EXTRA_SAVE_OPTIONS=(3)"})
    void unexportedStageInputsOrOutputsAreRefused(String change) throws Exception {
        var values=environment();values.remove("STAREXEC_BUILD_OUTPUT_DIR");
        Result result=execute(functions()+stage()+change+"\nconfigurePrivateBuildStagePaths\nprintf 'STAGE_READY\\n'\n",values);
        assertNotEquals(0,result.exit());assertFalse(result.output().contains("STAGE_READY"));
    }

    @ParameterizedTest @ValueSource(strings={"artifact input","$SAVED_OUTPUT_DIR/1"})
    void privateStagesRefuseUnexportedPipelineArguments(String dependency) throws Exception {
        String template=Files.readString(config().resolve("jobscript"));
        String start="$$STAGE_DEPENDENCY_ARRAY$$";
        String end="\nfillDependArrays";
        int from=template.indexOf(start),to=template.indexOf(end,from);
        assertTrue(from>=0 && to>from);
        String boundary=template.substring(from+start.length(),to);
        var values=environment();values.remove("STAREXEC_BUILD_OUTPUT_DIR");
        Result result=execute(functions()+stage()+"STAGE_DEPENDENCIES=('"+dependency+"')\n"
                +boundary+"\nprintf 'DEPENDENCIES_READY\\n'\n",values);
        assertNotEquals(0,result.exit());assertFalse(result.output().contains("DEPENDENCIES_READY"));
    }

    @Test void privateFinalCleanupRetainsControlsAndWorkspaceForTrustedSettlement() throws Exception {
        Path workspace=Files.createDirectory(directory.resolve("workspace"));
        Path script=Files.writeString(directory.resolve("readonly-jobscript"),"control");
        Path marker=Files.writeString(workspace.resolve("keep"),"scratch");
        var values=environment();values.remove("STAREXEC_BUILD_OUTPUT_DIR");
        String code=functions()+"WORKING_DIR='"+workspace+"'\nSCRIPT_PATH='"+script+"'\nHOSTNAME=synthetic\n"
                +"find() { printf 'GLOBAL_CLEANUP_CALLED\\n'; return 1; }\n"
                +"cleanWorkspace 0 nobody\nremovePrivateWorkspace\nprintf 'CLEANUP_DEFERRED\\n'\n";
        Result result=execute(code,values);assertEquals(0,result.exit(),result.output());
        assertFalse(result.output().contains("GLOBAL_CLEANUP_CALLED"));
        assertEquals("control",Files.readString(script));assertEquals("scratch",Files.readString(marker));
    }

    private record Result(int exit,String output) { }
}
