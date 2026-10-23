package org.starexec.test.junit.backend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;

import org.junit.Test;
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
            "JobManager must not claim pairs for a backend that failed to initialize",
            new PodmanBackend().isQueueDispatchable(QUEUE)
        );
    }

    @Test(timeout = 30_000)
    public void aBackendThatNeverInitializedDefersAPairInsteadOfRunningIt() {
        PodmanBackend backend = new PodmanBackend();
        try {
            backend.submitScript(42, "job.sh", "/nonexistent/work", "/nonexistent/work/42.txt");
            fail("an uninitialized backend must not accept a pair");
        } catch (SubmissionDeferredException e) {
            assertTrue(
                "the deferral must say why: " + e.getMessage(),
                e.getMessage().contains("not initialized")
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
            "a backend that is shutting down must not be given new pairs",
            backend.isQueueDispatchable(QUEUE)
        );
    }

    private static void setField(PodmanBackend backend, String name, boolean value)
        throws Exception {
        Field field = PodmanBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(backend, value);
    }
}
