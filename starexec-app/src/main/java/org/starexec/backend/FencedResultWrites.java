package org.starexec.backend;

import java.util.Map;
import java.util.Properties;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;

/**
 * Routes a monitor's result writes through the attempt fence (#185).
 *
 * <p>An execution that knows the attempt it was submitted for passes it to the database, which
 * refuses the write if a rerun has since replaced that attempt. An execution that does not
 * know it -- a container created before the {@code starexec.attempt} label existed -- takes the
 * unfenced forms, exactly as it did before the fence. Keeping that choice in one place means a
 * monitor cannot fence some of an execution's writes and not others.
 */
final class FencedResultWrites {

    private FencedResultWrites() {
    }

    static PairStatusResult setPairStatusPreciseResult(
            int pairId, int stageNumber, int terminalStatus, int notReachedStatus,
            boolean forceOverride, Integer attemptNo) {
        return attemptNo == null
                ? JobPairs.setPairStatusPreciseResult(
                        pairId, stageNumber, terminalStatus, notReachedStatus, forceOverride)
                : JobPairs.setPairStatusPreciseResult(
                        pairId, stageNumber, terminalStatus, notReachedStatus, forceOverride,
                        attemptNo);
    }

    static PairStatusResult setPairLevelStatusResult(
            int pairId, int terminalStatus, int notReachedStatus, Integer attemptNo) {
        return attemptNo == null
                ? JobPairs.setPairLevelStatusResult(pairId, terminalStatus, notReachedStatus)
                : JobPairs.setPairLevelStatusResult(
                        pairId, terminalStatus, notReachedStatus, attemptNo);
    }

    static StageStatusBatchResult setEarlierStageStatuses(
            int pairId, Map<Integer, Integer> stageStatuses, Integer attemptNo) {
        return attemptNo == null
                ? JobPairs.setEarlierStageStatuses(pairId, stageStatuses)
                : JobPairs.setEarlierStageStatuses(pairId, stageStatuses, attemptNo);
    }

    static boolean updateRunSolverStats(
            int pairId, String nodeName, double wallClock, double cpu, double userTime,
            double systemTime, double maxVmem, long maxResSet, int stageNumber, long diskSize,
            Integer attemptNo) {
        return attemptNo == null
                ? JobPairs.updateRunSolverStats(pairId, nodeName, wallClock, cpu, userTime,
                        systemTime, maxVmem, maxResSet, stageNumber, diskSize)
                : JobPairs.updateRunSolverStats(pairId, nodeName, wallClock, cpu, userTime,
                        systemTime, maxVmem, maxResSet, stageNumber, diskSize, attemptNo);
    }

    /** False on failure, and false when refused as stale; JobPairs logs which at INFO/ERROR. */
    static boolean addJobPairAttributes(
            int pairId, int stageId, Properties attributes, Integer attemptNo) {
        return attemptNo == null
                ? JobPairs.addJobPairAttributes(pairId, stageId, attributes)
                : JobPairs.addJobPairAttributes(pairId, stageId, attributes, attemptNo);
    }
}
