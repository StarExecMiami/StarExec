package org.starexec.test.junit.backend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.starexec.backend.PodmanBackend;
import org.starexec.backend.exception.SubmissionDeferredException;

/**
 * A Podman backend whose {@code initialize()} did not complete runs nothing.
 *
 * <p>{@code initialize()} throws when the container engine cannot be reached or when
 * {@code STAREXEC_CPU_PARTITIONS} would split hyperthread siblings, and the application
 * starts anyway: {@code BackendInitializer} logs the failure and carries on. JobManager then
 * dispatched to the backend as if it were ready. A pair could run in an unpinned container,
 * with no monitor to record its result or free its slot.
 *
 * <p>These tests use a backend that was never initialized, which is the state a failed
 * {@code initialize()} leaves behind: it marks the backend ready only as its last step.
 */
public class PodmanBackendInitGuardTest {

    private static final String QUEUE = "container.q";

    @Test
    public void aBackendThatNeverInitializedIsNotDispatchable() {
        assertFalse(
            new PodmanBackend().isQueueDispatchable(QUEUE),
            "JobManager must not claim pairs for a backend that failed to initialize"
        );
    }

    @Test
    @Timeout(value = 30_000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void aBackendThatNeverInitializedDefersAPairInsteadOfRunningIt() {
        PodmanBackend backend = new PodmanBackend();
        try {
            backend.submitScript(42, "job.sh", "/nonexistent/work", "/nonexistent/work/42.txt");
            fail("an uninitialized backend must not accept a pair");
        } catch (SubmissionDeferredException e) {
            assertTrue(
                e.getMessage().contains("not initialized"),
                "the deferral must say why: " + e.getMessage()
            );
        }
    }

    @Test
    public void dispatchableOnlyWhileInitializedAndNotShuttingDown() throws Exception {
        PodmanBackend backend = new PodmanBackend();
        setField(backend, "initialized", true);
        assertTrue(backend.isQueueDispatchable(QUEUE));

        setField(backend, "shuttingDown", true);
        assertFalse(
            backend.isQueueDispatchable(QUEUE),
            "a backend that is shutting down must not be given new pairs"
        );
    }

    private static void setField(PodmanBackend backend, String name, boolean value)
        throws Exception {
        Field field = PodmanBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(backend, value);
    }
}
