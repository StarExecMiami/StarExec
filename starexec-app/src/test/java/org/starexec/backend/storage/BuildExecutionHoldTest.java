package org.starexec.backend.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BuildExecutionHoldTest {
    @TempDir Path directory;
    private final List<Process> children=new ArrayList<>();

    private record Held(Process process,Path output,Path marker) { }

    private Held launch(int seconds,Map<String,String> environment) throws Exception {
        Path marker=directory.resolve("started");
        Path script=directory.resolve("script with spaces.bash");
        Files.writeString(script,"#!/bin/bash\nset -eu\nprintf 'started' > '"+marker+"'\nprintf 'PAYLOAD_EXECUTED\\n'\n");
        List<String> command=new ArrayList<>(BuildExecutionHold.entrypoint(seconds));
        command.addAll(BuildExecutionHold.command(script));
        Path output=directory.resolve("stdout");ProcessBuilder builder=new ProcessBuilder(command);
        builder.environment().putAll(environment);
        Process process=builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();children.add(process);
        return new Held(process,output,marker);
    }

    private void ready(Held held) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime()<deadline) {
            if (Files.exists(held.marker()) || Files.readString(held.output()).contains(BuildExecutionHold.READY) || !held.process().isAlive()) break;
            Thread.sleep(10);
        }
        assertFalse(Files.exists(held.marker()),"Build payload ran before control-plane release: "+Files.readString(held.output()));
        assertTrue(held.process().isAlive(),Files.readString(held.output()));
        assertTrue(Files.readString(held.output()).contains(BuildExecutionHold.READY),"Trusted hold never became ready");
    }

    private void signal(Held held,String signal) throws Exception {
        Process sender=new ProcessBuilder("/bin/kill","-s",signal,"--",Long.toString(held.process().pid())).start();
        assertTrue(sender.waitFor(2,TimeUnit.SECONDS));assertEquals(0,sender.exitValue());
    }

    @AfterEach void terminateOnlyOwnedChildren() throws Exception {
        for (Process child:children) {
            if (child.isAlive()) child.destroyForcibly();
            assertTrue(child.waitFor(3,TimeUnit.SECONDS),"Owned hold process remains active");
        }
    }

    @Test void aBuildCannotRunBeforeExplicitRelease() throws Exception {
        Held held=launch(5,Map.of());ready(held);
        signal(held,BuildExecutionHold.RELEASE_SIGNAL);
        assertTrue(held.process().waitFor(3,TimeUnit.SECONDS));
        assertEquals(0,held.process().exitValue(),Files.readString(held.output()));
        assertEquals("started",Files.readString(held.marker()));
        assertEquals(1,Files.readString(held.output()).split("PAYLOAD_EXECUTED",-1).length-1);
    }

    @Test void anUnreleasedHoldExpiresWithoutRunningTheBuild() throws Exception {
        long started=System.nanoTime();Held held=launch(1,Map.of());ready(held);
        assertTrue(held.process().waitFor(4,TimeUnit.SECONDS));
        assertEquals(75,held.process().exitValue());assertFalse(Files.exists(held.marker()));
        assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(4))<0);
    }

    @ParameterizedTest @ValueSource(strings={"CONT","USR1"})
    void unrelatedSignalsDoNotAuthorizeBuildCode(String other) throws Exception {
        Held held=launch(5,Map.of());ready(held);signal(held,other);
        Thread.sleep(200);assertFalse(Files.exists(held.marker()));assertTrue(held.process().isAlive());
        signal(held,BuildExecutionHold.RELEASE_SIGNAL);
        assertTrue(held.process().waitFor(3,TimeUnit.SECONDS));assertEquals(0,held.process().exitValue());
    }

    @ParameterizedTest @ValueSource(strings={"TERM","INT","HUP"})
    void cancellationSignalsNeverReleaseCode(String cancellation) throws Exception {
        Held held=launch(5,Map.of());ready(held);signal(held,cancellation);
        assertTrue(held.process().waitFor(3,TimeUnit.SECONDS));assertNotEquals(0,held.process().exitValue());
        assertFalse(Files.exists(held.marker()));
    }

    @Test void inheritedShellStartupFilesCannotRunBeforeOrAfterRelease() throws Exception {
        Path poison=directory.resolve("startup.bash"),marker=directory.resolve("startup-ran");
        Files.writeString(poison,"printf poisoned > '"+marker+"'\n");
        Held held=launch(5,Map.of("BASH_ENV",poison.toString(),"ENV",poison.toString()));ready(held);
        assertFalse(Files.exists(marker));signal(held,BuildExecutionHold.RELEASE_SIGNAL);
        assertTrue(held.process().waitFor(3,TimeUnit.SECONDS));assertEquals(0,held.process().exitValue());
        assertFalse(Files.exists(marker));assertTrue(Files.exists(held.marker()));
    }

    @ParameterizedTest @ValueSource(ints={-1,0,301,Integer.MAX_VALUE})
    void unknownOrUnboundedHoldTimesAreRefused(int seconds) {
        assertThrows(IllegalArgumentException.class,()->BuildExecutionHold.entrypoint(seconds));
    }

    @ParameterizedTest @ValueSource(strings={"relative","/","/trusted/../foreign"})
    void ambiguousScriptLocationsAreRefused(String script) {
        assertThrows(IllegalArgumentException.class,()->BuildExecutionHold.command(Path.of(script)));
    }
}
