package org.starexec.test.unit.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JobScriptPrivateJobPathsTest {
    @TempDir Path directory;
    private static final String PRIVATE="STAREXEC_JOB_PRIVATE_MOUNTS";

    private Path config() {
        Path root=Path.of("src/main/java/org/starexec/config/sge");
        return Files.isDirectory(root)?root:Path.of("starexec-app").resolve(root);
    }
    private static String encoded(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
    private Map<String,String> environment() {
        Map<String,String> values=new LinkedHashMap<>();
        values.put(PRIVATE,"1");values.put("CONTAINER_MODE","true");
        values.put("STAREXEC_OUTPUT_DIR","/starexec/execution/monitor");
        values.put("HOME","/starexec/execution/home");values.put("TMPDIR","/tmp");
        values.put("STAREXEC_JOB_DEPENDENCIES","");
        return values;
    }
    private Result execute(String code,Map<String,String> values) throws Exception {
        Path file=Files.createTempFile(directory,"probe-",".bash");Files.writeString(file,code);
        var builder=new ProcessBuilder("/bin/bash","-eu",file.toString());
        for (String key:environment().keySet()) builder.environment().remove(key);
        for (String key:java.util.List.of("STAREXEC_BUILD_PRIVATE_MOUNTS","STAREXEC_FORCE_SANDBOX","STAREXEC_JOB_PRIVATE_PROTOCOL"))
            builder.environment().remove(key);
        builder.environment().putAll(values);
        Path output=Files.createTempFile(directory,"output-",".txt");
        var process=builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean done=process.waitFor(10,TimeUnit.SECONDS);
        if (!done) process.destroyForcibly().waitFor();
        assertTrue(done,"bounded script observation must terminate");
        return new Result(process.exitValue(),Files.readString(output));
    }
    private String prefix(boolean build,boolean dependencies) throws Exception {
        String template=Files.readString(config().resolve("jobscript"));
        String boundary=". \"$SCRIPT_DIR/functions.bash\"";assertTrue(template.contains(boundary));
        return template.substring(0,template.indexOf(boundary))
                .replace("$$BUILD_JOB$$",Boolean.toString(build)).replace("$$HAS_DEPENDS$$",dependencies?"1":"0")
                .replace("$$SCRIPT_DIR$$",directory.resolve("controls").toString())
                .replace("$$WORKING_DIR_BASE$$",directory.resolve("legacy-work").toString())
                .replace("$$STAREXEC_DATA_DIR$$",directory.resolve("legacy-data").toString())
                .replace("$$SCRIPT_PATH$$",directory.resolve("readonly-jobscript").toString())
                .replace("$$BENCH$$",encoded("/authorized/Benchmarks/problem.p"))
                .replace("$$PAIR_OUTPUT_DIRECTORY$$",encoded("/legacy/output"))
                .replace("$$BENCH_SAVE_PATH$$","/legacy/benchmarks")
                .replaceAll("\\$\\$[A-Z0-9_]+\\$\\$","0");
    }
    private Result prefix(Map<String,String> values,boolean build,boolean dependencies) throws Exception {
        return execute(prefix(build,dependencies)+"\nprintf 'BOOTSTRAP_READY\\n'\n"
                +"printf '%s\\n' \"$SHARED_DIR\" \"$WORKING_DIR_BASE\" \"$BENCH_SAVE_DIR\"\n"
                +"base64 -d <<< \"$PAIR_OUTPUT_DIRECTORY\"; printf '\\n'; base64 -d <<< \"$BENCH_PATH\"; printf '\\n'\n",values);
    }

    @Test void ordinaryBootstrapUsesOnlyReservedWritablePathsAndKeepsInputNames() throws Exception {
        var result=prefix(environment(),false,false);
        assertEquals(0,result.exit(),result.output());
        assertTrue(result.output().contains("BOOTSTRAP_READY\n/starexec/execution\n/starexec/execution/work\n"
                +"/starexec/execution/savedoutput\n/starexec/execution/monitor\n/authorized/Benchmarks/problem.p\n"),result.output());
        assertFalse(result.output().contains("legacy-"),result.output());
    }

    @ParameterizedTest @ValueSource(strings={"STAREXEC_OUTPUT_DIR","HOME","TMPDIR"})
    void absentOrRedirectedWritesFailBeforeLoadingHelpers(String key) throws Exception {
        for (String value:java.util.List.of("","/foreign")) {
            var values=environment();
            if (value.isEmpty()) values.remove(key);else values.put(key,value);
            var result=prefix(values,false,false);assertNotEquals(0,result.exit(),result.output());
            assertFalse(result.output().contains("BOOTSTRAP_READY"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"0","true","2"})
    void unknownPrivateModesCannotFallBackToLegacyPaths(String value) throws Exception {
        var values=environment();values.put(PRIVATE,value);
        var result=prefix(values,false,false);assertNotEquals(0,result.exit());assertFalse(result.output().contains("BOOTSTRAP_READY"));
    }
    @ParameterizedTest @ValueSource(strings={"build","host","forced-sandbox","mixed-protocols"})
    void ordinaryPrivateModeCannotSelectAnUnrelatedExecutionMode(String change) throws Exception {
        var values=environment();
        switch (change) {
            case "host" -> values.put("CONTAINER_MODE","false");
            case "forced-sandbox" -> values.put("STAREXEC_FORCE_SANDBOX","true");
            case "mixed-protocols" -> values.put("STAREXEC_BUILD_PRIVATE_MOUNTS","1");
            case "build" -> { }
            default -> throw new AssertionError();
        }
        var result=prefix(values,change.equals("build"),false);assertNotEquals(0,result.exit());
        assertFalse(result.output().contains("BOOTSTRAP_READY"));
    }
    @Test void legacyOrdinaryBootstrapKeepsItsExistingPaths() throws Exception {
        var values=environment();values.remove(PRIVATE);
        var result=prefix(values,false,false);assertEquals(0,result.exit(),result.output());
        assertTrue(result.output().contains(directory.resolve("legacy-data").toString()));
        assertTrue(result.output().contains(directory.resolve("legacy-work").toString()));
        assertTrue(result.output().contains("/legacy/benchmarks\n/legacy/output\n"));
    }
    @Test void dependencyBootstrapRequiresExactlyTheDeclaredReadOnlyFile() throws Exception {
        var values=environment();
        assertNotEquals(0,prefix(values,false,true).exit());
        Path file=Files.writeString(directory.resolve("dependency file"),"input,,,../Axioms/theory.ax\n");
        values.put("STAREXEC_JOB_DEPENDENCIES",file.toString());
        assertNotEquals(0,prefix(values,false,false).exit());
        assertEquals(0,prefix(values,false,true).exit());
        Path link=Files.createSymbolicLink(directory.resolve("linked-dependency"),file);
        values.put("STAREXEC_JOB_DEPENDENCIES",link.toString());assertNotEquals(0,prefix(values,false,true).exit());
    }
    private void installHelpers(boolean current) throws Exception {
        Path controls=directory.resolve("controls");Files.createDirectories(controls);
        String functions=Files.readString(config().resolve("functions.bash"));
        if (!current) functions=functions.replace("STAREXEC_JOB_PRIVATE_PROTOCOL=1\n","");
        Files.writeString(controls.resolve("functions.bash"),functions);
        Files.copy(config().resolve("status_codes.bash"),controls.resolve("status_codes.bash"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    @ParameterizedTest @ValueSource(strings={"current","stale"})
    void helperCapabilityMustComeFromTheFreshlyLoadedHelper(String kind) throws Exception {
        installHelpers(kind.equals("current"));
        String template=Files.readString(config().resolve("jobscript"));
        int start=template.indexOf(". \"$SCRIPT_DIR/functions.bash\""),end=template.indexOf("BENCH_DEPENDS_ARRAY=()",start);
        assertTrue(start>=0 && end>start);
        var values=environment();values.put("STAREXEC_JOB_PRIVATE_PROTOCOL","1");
        var result=execute(prefix(false,false)+template.substring(start,end)+"\nprintf 'HELPER_READY\\n'\n",values);
        if (kind.equals("current")) { assertEquals(0,result.exit(),result.output());assertTrue(result.output().contains("HELPER_READY")); }
        else { assertNotEquals(0,result.exit(),result.output());assertFalse(result.output().contains("HELPER_READY")); }
    }
    private String helperSetup() throws Exception {
        installHelpers(true);
        return "export SCRIPT_DIR='"+directory.resolve("controls")+"'\nexport WORKING_DIR_BASE='"+directory.resolve("work")
                +"'\nexport SHARED_DIR='"+directory.resolve("data")+"'\nexport STAREXEC_OUTPUT_DIR='"+directory.resolve("monitor")
                +"'\nexport PAIR_ID=11\nexport BENCH_PATH='"+encoded("/authorized/problem.p")
                +"'\nexport PAIR_OUTPUT_DIRECTORY='"+encoded(directory.resolve("monitor").toString())
                +"'\n. \"$SCRIPT_DIR/functions.bash\"\n";
    }
    @Test void explicitDependenciesKeepTheirOriginalInputAndRelativeIncludeNames() throws Exception {
        Path dependency=Files.writeString(directory.resolve("dependencies file"),"/authorized/theory.ax,,,../Axioms/theory.ax\n");
        var values=environment();values.put("STAREXEC_JOB_DEPENDENCIES",dependency.toString());
        var result=execute(helperSetup()+"HAS_DEPENDS=1\nBENCH_DEPENDS_ARRAY=()\nLOCAL_DEPENDS_ARRAY=()\nfillDependArrays\n"
                +"printf 'DEPENDENCY=%s|%s\\n' \"${BENCH_DEPENDS_ARRAY[0]}\" \"${LOCAL_DEPENDS_ARRAY[0]}\"\n",values);
        assertEquals(0,result.exit(),result.output());
        assertTrue(result.output().contains("DEPENDENCY=/authorized/theory.ax|../Axioms/theory.ax\n"),result.output());
    }
    @Test void privateInputsDoNotEnterTheLegacyImagePathShim() throws Exception {
        var result=execute(helperSetup()+"SOLVER_PATHS=(original)\nadjustForK8s\nprintf 'INPUT=%s\\n' \"${SOLVER_PATHS[0]}\"\n",environment());
        assertEquals(0,result.exit(),result.output());assertTrue(result.output().contains("INPUT=original\n"));
        assertFalse(result.output().contains("adjustForK8s called"));
    }
    @Test void finalCleanupRetainsControlsAndOutputForTrustedStoppedSettlement() throws Exception {
        Path workspace=Files.createDirectory(directory.resolve("workspace"));
        Path marker=Files.writeString(workspace.resolve("result"),"scientific result");
        Path script=Files.writeString(directory.resolve("readonly-jobscript"),"trusted control");
        String code=helperSetup()+"WORKING_DIR='"+workspace+"'\nSCRIPT_PATH='"+script+"'\n"
                +"find() { printf 'GLOBAL_CLEANUP_CALLED\\n'; return 1; }\n"
                +"cleanWorkspace 0 nobody\nremovePrivateWorkspace\nprintf 'RETAINED\\n'\n";
        var result=execute(code,environment());assertEquals(0,result.exit(),result.output());
        assertTrue(result.output().contains("RETAINED"));assertFalse(result.output().contains("GLOBAL_CLEANUP_CALLED"));
        assertEquals("trusted control",Files.readString(script));assertEquals("scientific result",Files.readString(marker));
    }
    private record Result(int exit,String output) { }
}
