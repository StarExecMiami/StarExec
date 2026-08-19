package org.starexec.backend;

import org.starexec.data.to.Status.StatusCode;

/**
 * Decides which resource limit a run breached, from runsolver's own output.
 *
 * <p>Shared by {@link ContainerJobMonitor} and {@link LocalJobMonitor} so the two cannot
 * drift: the same run must be classified the same way whichever backend executed it, and
 * two parallel implementations would eventually disagree.
 *
 * <h2>Why the boolean flags are the detector and the prose is only the discriminator</h2>
 *
 * runsolver writes two authoritative booleans to var.out
 * ({@code RunSolverSource/Watcher.hh:471-475}, via {@code boolalpha} so they read
 * {@code true}/{@code false}):
 *
 * <pre>
 *   TIMEOUT=  (Watcher.hh:438) = (limitCPUTime  &amp;&amp; cpuTime &gt; limitCPUTime)
 *                             || (limitWallClockTime &amp;&amp; wcTime &gt; limitWallClockTime)
 *   MEMOUT=   (Watcher.hh:440) =  limitVSize &amp;&amp; maxVSize &gt; limitVSize
 * </pre>
 *
 * <p>They are computed against the limits runsolver actually enforced. Nothing in
 * StarExec read them before: classification depended entirely on substring-matching the
 * English sentences {@code stopSolver} happens to print ("Maximum CPU time exceeded…",
 * {@code Watcher.hh:717-726}). That made a wording change upstream silently reclassify
 * every timeout as a clean completion — and the same fragility exists one layer earlier
 * in bash, where {@code jobscript:580-589} greps the very same prose to decide what to
 * write into status.json.
 *
 * <p>So the prose keeps a job, but a smaller one. {@code TIMEOUT=} is a <em>disjunction</em>
 * of the CPU and wallclock conditions and therefore cannot say <em>which</em> limit fired,
 * while StarExec has two distinct codes for them ({@code EXCEED_CPU}, {@code EXCEED_RUNTIME}).
 * The prose line names the limit, because {@code stopSolver} prints it at the moment of
 * the kill. Used this way, a wording change costs us the distinction between CPU and
 * wallclock — it no longer costs us the timeout itself.
 *
 * <p>When there is no prose to discriminate with, a {@code TIMEOUT=true} resolves to
 * {@code EXCEED_CPU}. That matches the order runsolver evaluates its own disjunction in,
 * and it is the right default now that parallel solvers are in scope: CPU time accrues
 * roughly N times faster than wallclock across N threads, so the CPU limit is genuinely
 * the one that fires first for them.
 */
final class RunsolverVerdict {

    private RunsolverVerdict() {
    }

    /**
     * @param timeout        runsolver's {@code TIMEOUT=} from var.out
     * @param memout         runsolver's {@code MEMOUT=} from var.out
     * @param cpuProse       watcher.out carried "Maximum CPU time exceeded"
     * @param wallclockProse watcher.out carried "Maximum wall clock time exceeded"
     * @param memProse       watcher.out carried "Maximum VSize exceeded" or
     *                       "Maximum memory exceeded"
     * @return the limit status this run breached, or {@code null} if runsolver reports
     *         no limit breach at all — in which case the caller decides the status by
     *         other means
     */
    static StatusCode classify(
        boolean timeout,
        boolean memout,
        boolean cpuProse,
        boolean wallclockProse,
        boolean memProse
    ) {
        boolean limitFired =
            timeout || memout || cpuProse || wallclockProse || memProse;
        if (!limitFired) {
            return null;
        }

        // Prose first, as the discriminator: stopSolver prints it at the kill and names
        // the limit. CPU before wallclock, per the class comment.
        if (cpuProse) {
            return StatusCode.EXCEED_CPU;
        }
        if (wallclockProse) {
            return StatusCode.EXCEED_RUNTIME;
        }
        if (memProse) {
            return StatusCode.EXCEED_MEM;
        }

        // No prose. MEMOUT is unambiguous; TIMEOUT is not, so it falls to the default.
        if (memout) {
            return StatusCode.EXCEED_MEM;
        }
        return StatusCode.EXCEED_CPU;
    }
}
