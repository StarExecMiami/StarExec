package org.starexec.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JobExecutionMountObservationTest {
    @TempDir Path input;

    private void observe(Path root, boolean directory) throws Exception {
        Method method = JobExecutionPreparation.class.getDeclaredMethod("requireNoNestedMounts", Path.class, boolean.class);
        method.setAccessible(true);
        method.invoke(null, root, directory);
    }

    @Test void acceptsAnOrdinaryPrivateInputWithoutNestedMounts() {
        assertDoesNotThrow(() -> observe(input, true));
    }

    @Test void refusesNestedMountsThroughTheIoFailureContract() {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
            () -> observe(Path.of("/"), true));
        assertInstanceOf(IOException.class, failure.getCause());
    }
}
