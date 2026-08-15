package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Method;
import org.junit.Test;
import org.starexec.data.to.Status.StatusCode;

/**
 * Tests the shared limit classifier used by both job monitors.
 *
 * <p>It is shared precisely so the two backends cannot drift: the same run must be
 * classified identically whether it executed under the container monitor or the local
 * one. Testing it once, here, is what makes that guarantee real — parallel test suites in
 * both monitors would eventually disagree.
 *
 * <p>Every claim about runsolver's output is traceable to
 * {@code org/starexec/config/sge/RunSolverSource/Watcher.hh}, cited inline.
 */
public class RunsolverVerdictTests {

    /** The class is package-private in org.starexec.backend, so reach it reflectively. */
    private StatusCode classify(
        boolean timeout,
        boolean memout,
        boolean cpuProse,
        boolean wallclockProse,
        boolean memProse
    ) throws Exception {
        Class<?> c = Class.forName("org.starexec.backend.RunsolverVerdict");
        Method m = c.getDeclaredMethod(
            "classify", boolean.class, boolean.class,
            boolean.class, boolean.class, boolean.class);
        m.setAccessible(true);
        return (StatusCode) m.invoke(null, timeout, memout, cpuProse, wallclockProse, memProse);
    }

    @Test
    public void aCleanRunIsNotClassifiedAsALimitBreach() throws Exception {
        assertNull(
            "with no limit reported, the caller decides the status by other means",
            classify(false, false, false, false, false)
        );
    }

    /**
     * The defect this exists to fix. A solver killed for exceeding its limit is SIGKILLed,
     * so Watcher.hh:326-331 prints no "Child status:" line and the exit code stays 0. If
     * the prose sentence also fails to match, the old code returned STATUS_COMPLETE — a
     * timed-out solver recorded as having finished successfully.
     */
    @Test
    public void aTimeoutWithNoProseIsStillATimeout() throws Exception {
        assertEquals(
            "TIMEOUT=true must classify a limit breach on its own; depending on the"
                + " English prose is what let timeouts be recorded as completions",
            StatusCode.EXCEED_CPU,
            classify(true, false, false, false, false)
        );
    }

    @Test
    public void aMemoutWithNoProseIsAMemoryBreach() throws Exception {
        // MEMOUT (Watcher.hh:440) is a single condition, so it needs no discriminator.
        assertEquals(
            StatusCode.EXCEED_MEM,
            classify(false, true, false, false, false)
        );
    }

    @Test
    public void proseDiscriminatesCpuFromWallclock() throws Exception {
        // TIMEOUT (Watcher.hh:438) is a disjunction of the CPU and wallclock conditions
        // and cannot say which fired; the prose stopSolver prints names the limit.
        assertEquals(
            StatusCode.EXCEED_CPU,
            classify(true, false, true, false, false)
        );
        assertEquals(
            StatusCode.EXCEED_RUNTIME,
            classify(true, false, false, true, false)
        );
    }

    /**
     * CPU before wallclock, matching Watcher.hh:438's own evaluation order. This matters
     * for parallel solvers: CPU accrues roughly N times faster than wallclock across N
     * threads, so the CPU limit is genuinely the one that fires first for them.
     */
    @Test
    public void cpuWinsWhenBothProseLinesArePresent() throws Exception {
        assertEquals(
            StatusCode.EXCEED_CPU,
            classify(true, false, true, true, false)
        );
    }

    @Test
    public void proseAloneStillClassifies() throws Exception {
        // Older runs, or a runsolver invoked without limits set, may carry the prose
        // without the flags. That must not regress to STATUS_COMPLETE.
        assertEquals(StatusCode.EXCEED_CPU, classify(false, false, true, false, false));
        assertEquals(StatusCode.EXCEED_RUNTIME, classify(false, false, false, true, false));
        assertEquals(StatusCode.EXCEED_MEM, classify(false, false, false, false, true));
    }

    @Test
    public void memoryProseOutranksAnAmbiguousTimeout() throws Exception {
        assertEquals(
            "a named memory kill is more specific than an undiscriminated TIMEOUT",
            StatusCode.EXCEED_MEM,
            classify(true, false, false, false, true)
        );
    }
}
