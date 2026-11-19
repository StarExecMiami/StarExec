package org.starexec.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CurlTransportPathTest {
    @TempDir Path files;

    @Test
    void callerPathCannotReplaceTheCreationTransportExecutable() throws Exception {
        assertTrue(Files.isExecutable(Path.of("/usr/bin/curl")), "The supported curl executable must be installed");
        Path bin=Files.createDirectory(files.resolve("bin"));
        Path marker=files.resolve("unexpected-command");
        Path fake=bin.resolve("curl");
        Files.writeString(fake, "#!/bin/sh\nprintf replaced > \"$CURL_TEST_MARKER\"\nprintf '{\"Id\":\"%064d\"}\\n201' 0\n");
        Files.setPosixFilePermissions(fake,PosixFilePermissions.fromString("rwx------"));
        Path temporary=Files.createDirectory(files.resolve("process-files"));
        Path output=files.resolve("probe-output");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Djava.io.tmpdir="+temporary,"-cp",classpath,Probe.class.getName(),files.resolve("absent.sock").toString());
        command.environment().put("PATH",bin+File.pathSeparator+System.getenv().getOrDefault("PATH",""));
        command.environment().put("CURL_TEST_MARKER",marker.toString());
        command.redirectErrorStream(true).redirectOutput(output.toFile());
        Process child=command.start();
        try {
            assertTrue(child.waitFor(20,TimeUnit.SECONDS),"The isolated transport probe must terminate");
            assertAll(
                    ()->assertFalse(Files.exists(marker),"A command from the caller PATH replaced the transport executable"),
                    ()->assertEquals(0,child.exitValue(),Files.readString(output)));
            try (var remaining=Files.list(temporary)) {
                assertEquals(0,remaining.count(),"Transport request files must be removed after failure");
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5,TimeUnit.SECONDS),"The owned probe process must stop");
            }
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Class<?> decision=Class.forName(PodmanJobTransport.class.getName()+"$Decision");
            Object admission=Proxy.newProxyInstance(decision.getClassLoader(),new Class<?>[]{decision},
                    (proxy,method,values)->true);
            var send=PodmanJobTransport.class.getDeclaredMethod("curlPost",String.class,int.class,int.class,
                    String.class,String.class,int.class,decision);
            send.setAccessible(true);
            try {
                send.invoke(null,"unix://"+args[0],1,1,"/v1.41/containers/create?name=probe","{}",201,admission);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof IOException) return;
                throw failure;
            }
            throw new AssertionError("An absent provider socket must not return a creation acknowledgement");
        }
    }
}
