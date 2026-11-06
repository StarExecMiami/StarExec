package org.starexec.backend;

import io.fabric8.kubernetes.api.model.EnvVar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BuildExecutionContextTest {
    @TempDir Path directory;

    private BuildExecutionContext context(String output) {
        return new BuildExecutionContext(5, 42, 1, UUID.randomUUID(),
                directory.resolve("source"), directory.resolve(output));
    }

    @Test void passesOnlyTheEncodedOutputPathToTheRunner() {
        BuildExecutionContext build = context("output with ' quotes and $variables");
        Map<String, String> environment = build.environment();
        assertEquals(1, environment.size());
        assertEquals(build.staging().toString(), new String(Base64.getDecoder().decode(
                environment.get(BuildExecutionContext.OUTPUT_VARIABLE)), StandardCharsets.UTF_8));
        assertFalse(environment.toString().contains(build.token().toString()));
        assertFalse(build.toString().contains(build.token().toString()));
        assertThrows(UnsupportedOperationException.class, () -> environment.put("other", "value"));
    }

    @Test void rejectsInvalidOrOverlappingPaths() {
        BuildExecutionContext good = context("output");
        for (Path path : List.of(Path.of("relative"), Path.of("/"), directory.resolve("other/../output"),
                good.source(), good.source().resolve("child"), directory)) {
            assertThrows(IllegalArgumentException.class, () -> new BuildExecutionContext(5, 42, 1,
                    good.token(), good.source(), path));
        }
    }

    @Test void rejectsMissingIdentity() {
        BuildExecutionContext good = context("output");
        assertThrows(IllegalArgumentException.class, () -> new BuildExecutionContext(0, 42, 1,
                good.token(), good.source(), good.staging()));
        assertThrows(IllegalArgumentException.class, () -> new BuildExecutionContext(5, -1, 1,
                good.token(), good.source(), good.staging()));
        assertThrows(IllegalArgumentException.class, () -> new BuildExecutionContext(5, 42, 0,
                good.token(), good.source(), good.staging()));
        assertThrows(NullPointerException.class, () -> new BuildExecutionContext(5, 42, 1,
                null, good.source(), good.staging()));
    }

    @Test void refusesADifferentOrUnknownCapturedAttempt() {
        BuildExecutionContext build = context("output");
        assertDoesNotThrow(() -> build.requireMatches(42, 1));
        assertThrows(IllegalArgumentException.class, () -> build.requireMatches(43, 1));
        assertThrows(IllegalArgumentException.class, () -> build.requireMatches(42, 2));
        assertThrows(IllegalArgumentException.class, () -> build.requireMatches(42, null));
    }

    @Test void aLegacyBackendMustRefuseRatherThanDiscardABuildContext() {
        Backend backend = Mockito.mock(Backend.class, Mockito.CALLS_REAL_METHODS);
        assertFalse(backend.supportsBuildStaging());
        assertThrows(UnsupportedOperationException.class, () -> backend.submitScript(
                42, "script", "work", "log", "queue", context("output")));
        Mockito.verify(backend, Mockito.never()).submitScript(
                Mockito.anyInt(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    }

    @Test void theDefaultNoContextOverloadPreservesQueueDispatch() {
        Backend backend = Mockito.mock(Backend.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(17).when(backend).submitScript(42, "script", "work", "log", "queue");
        assertEquals(17, backend.submitScript(42, "script", "work", "log", "queue", null));
        Mockito.verify(backend).submitScript(42, "script", "work", "log", "queue");
    }

    @Test void localProcessesReceiveTheirOwnBuildDestination() throws Exception {
        BuildExecutionContext build = context("output with spaces");
        Path output = directory.resolve("build.log");
        assertEquals(build.environment().get(BuildExecutionContext.OUTPUT_VARIABLE),
                runLocal(build, output).trim());
        assertEquals("UNSET", runLocal(null, directory.resolve("ordinary.log")).trim());
    }

    private String runLocal(BuildExecutionContext build, Path output) throws Exception {
        Path script = directory.resolve("print.sh");
        Files.writeString(script, "printf '%s\\n' \"${STAREXEC_BUILD_OUTPUT_DIR-UNSET}\"\n");
        Class<?> type = Class.forName("org.starexec.backend.LocalBackend$LocalJob");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, int.class,
                String.class, String.class, String.class);
        constructor.setAccessible(true);
        Object job = constructor.newInstance(7, 42, script.toString(), directory.toString(), output.toString());
        Field context = type.getDeclaredField("buildContext"); context.setAccessible(true); context.set(job, build);
        String allowed = Files.readAllLines(Path.of("/proc/self/status")).stream()
                .filter(line -> line.startsWith("Cpus_allowed_list:"))
                .findFirst().orElseThrow().split(":", 2)[1].trim();
        int core = Integer.parseInt(allowed.split("[-,]", 2)[0]);
        Field coreId = type.getDeclaredField("coreId"); coreId.setAccessible(true); coreId.set(job, core);
        LocalBackend backend = new LocalBackend();
        Field runsolver = LocalBackend.class.getDeclaredField("useRunsolver");
        runsolver.setAccessible(true); runsolver.set(backend, false);
        Method start = LocalBackend.class.getDeclaredMethod("startJobProcess", type); start.setAccessible(true);
        Process process;
        try (var artifacts = Mockito.mockStatic(org.starexec.data.database.SolverBuildArtifacts.class)) {
            if (build != null) artifacts.when(() -> org.starexec.data.database.SolverBuildArtifacts.admitExecution(
                    Mockito.eq(build), Mockito.anyString())).thenReturn(true);
            process = (Process) start.invoke(backend, job);
        }
        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly().waitFor();
        assertTrue(exited); assertEquals(0, process.exitValue(), Files.readString(output));
        return Files.readString(output);
    }

    @Test void concurrentKubernetesSubmissionsKeepTheirDestinationsSeparate() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        KubernetesNativeBackend backend = new KubernetesNativeBackend() {
            @Override public int submitScript(int pair, String script, String work, String log, String queue) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    assertEquals(queue, environment(this).get(0).getValue());
                    return 7;
                } catch (Exception e) { throw new IllegalStateException(e); }
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            BuildExecutionContext first = context("first"), second = context("second");
            var a = pool.submit(() -> backend.submitScript(42, "script", "work", "log",
                    first.environment().get(BuildExecutionContext.OUTPUT_VARIABLE), first));
            var b = pool.submit(() -> backend.submitScript(42, "script", "work", "log",
                    second.environment().get(BuildExecutionContext.OUTPUT_VARIABLE), second));
            assertEquals(7, a.get(15, TimeUnit.SECONDS));
            assertEquals(7, b.get(15, TimeUnit.SECONDS));
            assertTrue(environment(backend).isEmpty());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void failedKubernetesSubmissionClearsItsContext() throws Exception {
        KubernetesNativeBackend backend = new KubernetesNativeBackend() {
            @Override public int submitScript(int pair, String script, String work, String log, String queue) {
                throw new IllegalStateException("submission failed before creating a Job");
            }
        };
        assertThrows(IllegalStateException.class, () -> backend.submitScript(
                42, "script", "work", "log", "queue", context("output")));
        assertTrue(environment(backend).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static List<EnvVar> environment(KubernetesNativeBackend backend) throws Exception {
        Method method = KubernetesNativeBackend.class.getDeclaredMethod("buildStagingEnvironment");
        method.setAccessible(true);
        return (List<EnvVar>) method.invoke(backend);
    }
}
