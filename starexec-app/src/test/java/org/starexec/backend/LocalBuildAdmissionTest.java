package org.starexec.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.starexec.data.database.SolverBuildArtifacts;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalBuildAdmissionTest {
    @TempDir Path directory;
    private Object job;
    private Method start;
    private Field processField;
    private BuildExecutionContext build;
    private Path output;
    private int core;
    private LocalBackend backend;
    private Process launched;

    @BeforeEach void setup() throws Exception {
        output = directory.resolve("stdout");
        Path script = directory.resolve("solver.sh");
        Files.writeString(script, "printf 'WORK_STARTED\\n'\n"
                + "while IFS= read -r line; do case \"$line\" in Cpus_allowed_list:*) printf '%s\\n' \"$line\";; esac; done < /proc/self/status\n");
        Class<?> type = Class.forName("org.starexec.backend.LocalBackend$LocalJob");
        var constructor = type.getDeclaredConstructor(int.class, int.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        job = constructor.newInstance(7, 42, script.toString(), directory.toString(), output.toString());
        build = new BuildExecutionContext(5, 42, 1, UUID.randomUUID(),
                directory.resolve("source"), directory.resolve("staging"));
        var context = type.getDeclaredField("buildContext"); context.setAccessible(true); context.set(job, build);
        String allowed = Files.readAllLines(Path.of("/proc/self/status")).stream()
                .filter(line -> line.startsWith("Cpus_allowed_list:")).findFirst().orElseThrow()
                .split(":", 2)[1].trim();
        core = Integer.parseInt(allowed.split("[-,]", 2)[0]);
        var coreId = type.getDeclaredField("coreId"); coreId.setAccessible(true); coreId.set(job, core);
        processField = type.getDeclaredField("process"); processField.setAccessible(true);
        backend = new LocalBackend();
        var runsolver = LocalBackend.class.getDeclaredField("useRunsolver");
        runsolver.setAccessible(true); runsolver.set(backend, false);
        start = LocalBackend.class.getDeclaredMethod("startJobProcess", type); start.setAccessible(true);
    }

    private Process launch() throws Exception {
        try { launched = (Process) start.invoke(backend, job); return launched; }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw (Error) failure.getCause();
        }
    }

    private Process process() throws Exception { return (Process) processField.get(job); }

    @AfterEach void cleanup() throws Exception {
        Process owned = launched == null ? process() : launched;
        if (owned != null && owned.isAlive()) owned.destroyForcibly().waitFor();
    }

    private void assertNoWorkAndNoLiveGate() throws Exception {
        Process owned = process();
        assertNotNull(owned, "the exact gate must remain observable during admission");
        boolean exited = owned.waitFor(10, TimeUnit.SECONDS);
        if (!exited) owned.destroyForcibly().waitFor();
        assertTrue(exited, "admission failure must reap its gate");
        assertEquals("", Files.readString(output), "the solver must not run before admission");
    }

    @Test void aRealProcessIsAdmittedBeforeItExecutesOnTheLeasedCore() throws Exception {
        try (MockedStatic<SolverBuildArtifacts> artifacts = mockStatic(SolverBuildArtifacts.class)) {
            artifacts.when(() -> SolverBuildArtifacts.admitExecution(eq(build), anyString())).thenAnswer(call -> {
                Process gate = process();
                assertNotNull(gate); assertTrue(gate.isAlive());
                assertEquals("", Files.readString(output));
                assertEquals("local:7:" + gate.pid() + ":" + gate.info().startInstant().orElseThrow(), call.getArgument(1));
                return true;
            });
            Process process = launch();
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly().waitFor();
            assertTrue(exited); assertEquals(0, process.exitValue(), Files.readString(output));
            String content = Files.readString(output);
            assertTrue(content.startsWith("WORK_STARTED\n"), content);
            assertEquals(Integer.toString(core), content.split("Cpus_allowed_list:", 2)[1].trim());
            artifacts.verify(() -> SolverBuildArtifacts.admitExecution(eq(build), anyString()), times(1));
        }
    }

    @Test void refusedAdmissionCannotExecuteTheSolver() throws Exception {
        try (MockedStatic<SolverBuildArtifacts> artifacts = mockStatic(SolverBuildArtifacts.class)) {
            assertThrows(IOException.class, this::launch);
            assertNoWorkAndNoLiveGate();
            artifacts.verify(() -> SolverBuildArtifacts.admitExecution(eq(build), anyString()), times(1));
        }
    }

    @Test void aDatabaseFailureCannotExecuteTheSolver() throws Exception {
        try (MockedStatic<SolverBuildArtifacts> artifacts = mockStatic(SolverBuildArtifacts.class)) {
            artifacts.when(() -> SolverBuildArtifacts.admitExecution(eq(build), anyString()))
                    .thenThrow(new SQLException("synthetic database failure"));
            assertThrows(IOException.class, this::launch);
            assertNoWorkAndNoLiveGate();
        }
    }

    @Test void anUncheckedAdmissionFailureCannotExecuteTheSolver() throws Exception {
        try (MockedStatic<SolverBuildArtifacts> artifacts = mockStatic(SolverBuildArtifacts.class)) {
            artifacts.when(() -> SolverBuildArtifacts.admitExecution(eq(build), anyString()))
                    .thenThrow(new IllegalStateException("synthetic pool failure"));
            assertThrows(IOException.class, this::launch);
            assertNoWorkAndNoLiveGate();
        }
    }

    @Test void cancellationDuringAdmissionCannotReleaseTheSolver() throws Exception {
        try (MockedStatic<SolverBuildArtifacts> artifacts = mockStatic(SolverBuildArtifacts.class)) {
            artifacts.when(() -> SolverBuildArtifacts.admitExecution(eq(build), anyString())).thenAnswer(call -> {
                Process gate = process(); gate.destroyForcibly().waitFor(); return true;
            });
            assertThrows(IOException.class, this::launch);
            assertNoWorkAndNoLiveGate();
        }
    }
}
