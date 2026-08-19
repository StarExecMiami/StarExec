package org.starexec.backend;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * This interface is how StarExec should communicate with whatever backend is being used
 * for handling distributing jobs across the compute nodes.
 *
 */
public interface Backend {
    /*
     * NOTES:
     *
     * BACKEND_ROOT
     *
     * BACKEND_ROOT should be the location of the BACKEND software (path/to/cluster_management_system_dir)
     * the admin should know specifically where BACKEND_ROOT points
     * -------
     * IDENTIFIERS
     *
     * All queue names and node names should also be identifiers
     * Starexec has its own ids for queues and nodes but these are database specific and should be meaningless to the BACKEND
     * Rather, it's expected that all names give to Starexec are also identifers so that when we return names
     * the BACKEND should have all the information it needs
     *
     **/

    /**
     * use to initialize fields and prepare backend for tasks
     * @param BACKEND_ROOT the path to the backend root, for sge found in R.SGE_ROOT
     **/
    void initialize(String BACKEND_ROOT);

    /**
     * release resources that Backend might not need anymore
     * there's a chance that initialize is never called, so always try dealing with that case

     **/
    void destroyIf();

    /**
     * @param execCode : an execution code (returned by submitScript)
     * @return false if the execution code represents an error, true otherwise
     *
     **/
    boolean isError(int execCode);

    /**
     * @param pairId : the id of the job pair being submitted, or -1 if none
     * @param scriptPath : the full path to the jobscript file
     * @param workingDirectoryPath  :  path to a directory that can be used for scratch space (read/write)
     * @param logPath  :  path to a directory that should be used to store jobscript logs
     * @return an identifier for the task that submitScript starts, should allow a user to identify which task/script to kill
     **/
    int submitScript(
        int pairId,
        String scriptPath,
        String workingDirectoryPath,
        String logPath
    );

    /**
     * Submits a job pair, telling the backend which queue it belongs to.
     *
     * <p>The four-argument form carries no queue, because under SGE it never needed to:
     * {@code JobManager} substitutes the queue into the generated script as
     * {@code #$ -q <name>} and the grid engine reads it from there. On Kubernetes that
     * line is an inert bash comment, so a backend that places pods itself has no way to
     * learn where a pair should run — which is why a pair submitted to one queue could
     * execute on any worker node in any other.
     *
     * <p>Defaulted rather than added to the interface proper so the backends that already
     * receive the queue through the script — GridEngine, Local, Podman, OAR — are
     * untouched. Only a backend that needs the queue overrides this.
     *
     * @param queueName the queue the pair was submitted to, never null
     * @return as {@link #submitScript(int, String, String, String)}
     */
    default int submitScript(
        int pairId,
        String scriptPath,
        String workingDirectoryPath,
        String logPath,
        String queueName
    ) {
        return submitScript(pairId, scriptPath, workingDirectoryPath, logPath);
    }

    /**
     * Whether this queue can accept work right now.
     *
     * <p>Asked once per queue per scheduling pass, before any pair is submitted, so a
     * queue that is temporarily unable to run anything is skipped rather than having its
     * pairs failed. That distinction matters: {@link #submitScript} can only answer with
     * an execution id or an error, and {@code JobManager} turns any error into a terminal
     * status. There is no return value meaning "not now", so a drain or a brief node
     * outage would otherwise destroy a benchmark run for a condition that fixes itself.
     *
     * <p>A permanently unroutable queue is a different matter and is still rejected at
     * submission, because it will not resolve on its own and should be visible.
     *
     * @return true by default, so a backend with no notion of queue readiness is
     *         unaffected
     */
    default boolean isQueueDispatchable(String queueName) {
        return true;
    }

    /**
     * @param execId an int that identifies the pair to be killed, should match what is returned by submitScript
     * @return true if successful, false otherwise
     * kills a jobpair
     */
    boolean killPair(int execId);

    /** Whether an execution is provably incapable of running or writing results. */
    enum KillOutcome {
        /** Established: no execution of this pair can still run or write. */
        CONFIRMED_SAFE,
        /** Not established. The caller must not replace, publish, or release it. */
        UNPROVEN,
    }

    /**
     * Kills an execution and reports whether its absence was <em>established</em>.
     *
     * <p>Distinct from {@link #killPair(int)} because that method's boolean cannot carry
     * this meaning. Audited across the implementations: {@code LocalBackend} returns false
     * when the job is not in its map, {@code PodmanBackend} returns false when the
     * container is absent or has already exited (releasing the slot as it does so), and
     * the SGE and OAR backends return false when {@code qdel}/{@code oardel} throws,
     * including for a job that no longer exists. In every one of those cases false means
     * <em>already gone</em> — the safe case. Reinterpreting it as "unproven" would strand
     * pairs on all four.
     *
     * <p>So the strict contract is opt-in. The default keeps each backend's existing
     * behaviour exactly, and a backend adopts the contract by overriding this method.
     * Kubernetes does, because deleting a {@code batch/v1} Job does not synchronously stop
     * its pod. Podman is the next candidate: it has the same asynchronous-teardown shape.
     *
     * @return {@link KillOutcome#CONFIRMED_SAFE} only when nothing for this execution can
     *         still run or write
     */
    default KillOutcome killPairConfirmed(int execId) {
        killPair(execId);
        return KillOutcome.CONFIRMED_SAFE;
    }

    /**
     * kills all pairs
     * @return true on success and false on error.
     */
    boolean killAll();

    /**
     * @return a string representing the status of jobs running on the system
     */
    String getRunningJobsStatus();

    /**
     * Gets execution codes for all jobs currently active (enqueued or running)
     * @return array of active execution codes
     * @throws IOException
     */
    Set<Integer> getActiveExecutionIds() throws IOException;

    /**
     * @return returns a list of names of all active worker nodes
     */
    String[] getWorkerNodes();

    /**
     * @return returns a list of all active queue names
     */
    String[] getQueues();

    /**
     * @return a map from node name to queue name
     */
    Map<String, String> getNodeQueueAssociations();

    /**
     * @return true if sucessful, false otherwise
     * should clear any states caused by errors on both queues and nodes
     */
    boolean clearNodeErrorStates();

    /**
     * deletes a queue that no longer has nodes associated with it
     * @param queueName the name of the queue to be removed
     */
    void deleteQueue(String queueName);

    /**
     * Notifies the backend that a pair is being rerun.
     *
     * <p>For backends that track completed jobs (like LocalBackend with LocalJobMonitor),
     * this method clears any cached state for the pair so that results from the rerun
     * will be properly detected and processed.</p>
     *
     * <p><b>CRITICAL FOR LOCALBACKEND:</b> LocalJobMonitor maintains a Set of processed
     * status files to avoid redundant updates. When a pair is rerun, the old status file
     * remains in the output directory. Without clearing the tracking, the monitor will skip
     * the new results, leaving the pair stuck in ENQUEUED status. This method ensures that
     * state is cleared so the rerun results are processed correctly.</p>
     *
     * @param pairId The pair ID being rerun. For most backends (SGE, Kubernetes, etc.),
     *               this is a no-op. For LocalBackend, this clears monitor tracking.
     */
    void clearPairTracking(int pairId);

    /**
     * creates a new queue
     *@param newQueueName the name of the destination queue
     *@param nodeNames the names of the nodes to be moved
     *@param sourceQueueNames the names of the source queues
     *@return true if successful, false otherwise
     */
    boolean createQueue(
        String newQueueName,
        String[] nodeNames,
        String[] sourceQueueNames
    );

    /**
     * creates a new queue with variable number of slots, currently not implemented for non-grid backengines
     *@param newQueueName the name of the destination queue
     *@param nodeNames the names of the nodes to be moved
     *@param sourceQueueNames the names of the source queues
     *@param slots the number of jobs run per queue node
     *@return true if successful, false otherwise
     */
    boolean createQueueWithSlots(
        String newQueueName,
        String[] nodeNames,
        String[] sourceQueueNames,
        Integer slots
    );

    /**
     * @param destQueueName the name of the destination queue
     * @param nodeNames the names of the nodes to be moved
     * @param sourceQueueNames the names of the source queues
     * moves nodes from source queues to the destination queue <queueName>
     * the ith element of nodeNames corresponds to the ith element of sourceQueueNames for every i
     * if node is an orphaned node, the corresponding queue name in sourceQueueNames will be null
     */
    void moveNodes(
        String destQueueName,
        String[] nodeNames,
        String[] sourceQueueNames
    );

    /**
     * moves the given node to the given queue
     * @param nodeName the name of a node
     * @param queueName the name of a queue
     */
    void moveNode(String nodeName, String queueName);
}
