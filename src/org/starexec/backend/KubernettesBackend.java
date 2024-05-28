package org.starexec.backend;
/*
 * This assumes the head node is able to talk to an existing kubernetes cluster
 * using kubectl. See https://kubernetes.io/docs/tasks/tools/install-kubectl/
 * 
 * The head node itself needn't be a kubernetes node I believe.
 * LocalBackend is where a lot of this code comes from, but I didn't
 * want to extend it directly since kubernettes isn't local.
 * 
 */

import java.io.IOException;
import java.io.File;
import java.util.*;

import org.starexec.constants.R;
import org.starexec.logger.StarLogger;
import org.starexec.util.RobustRunnable;
import org.starexec.util.Util;


public class KubernettesBackend implements Backend {
    private static final StarLogger log = StarLogger.getLogger(KubernettesBackend.class);
    final java.util.Queue<LocalJob> jobsToRun = new ArrayDeque<>();
    private final Map<Integer, LocalJob> activeIds = new HashMap<>();
    private int curID = 1;


    // Yoinked From LocalBackend
    private static class LocalJob {
        public int execId = 0;
        public String scriptPath = "";
        public String workingDirectoryPath = "";
        public String logPath = "";
        public Process process;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(scriptPath).append(" ");
            sb.append(execId).append(" ");
            if (process != null) {
                sb.append("running");
            } else {
                sb.append("pending");
            }
            return sb.toString();
        }
    }


    @Override
    public void initialize(String BACKEND_ROOT) {
        // set the name of the single node used by this backend to the name of the
        // system
        final Runnable runLocalJobsRunnable = new RobustRunnable("runLocalJobsRunnable") {
            @Override
            protected void dorun() {
                log.info("initializing local job execution");
                runJobsForever();
            }
        };
        new Thread(runLocalJobsRunnable).start();
        log.debug("returning from local backend initialization");
    }

    @SuppressWarnings("InfiniteLoopStatement")
    private void runJobsForever() {
        while (true) {
            try {
                if (jobsToRun.isEmpty()) {
                    Thread.sleep(R.JOB_SUBMISSION_PERIOD * 1000);
                    continue;
                }

            } catch (Exception e) {
                log.error(e.getMessage(), e);
                continue;
            }
            LocalJob job = jobsToRun.peek();
            runJob(job);
            removeJob(job);
        }
    }

    private int generateExecId() throws Exception {
        if (activeIds.size() == Integer.MAX_VALUE) {
            throw new Exception("Cannot support more that Integer.MAX_VALUE pairs");
        }
        while (true) {
            curID = (curID + 1) % Integer.MAX_VALUE;
            // make sure the ID is not 0 when we return it
            curID = Math.max(curID, 1);
            if (!activeIds.containsKey(curID)) {
                return curID;
            }
        }
    }


    /**
     * release resources that Backend might not need anymore
     * there's a chance that initialize is never called, 
     * so always try dealing with that case
     **/
    @Override
    public void destroyIf() {
        // Local backend didn't use this either...
    }

    @Override
    public boolean isError(int execCode) {
        return execCode < 0; // this is what the local backend does...
    }

    @Override
    public int submitScript(String scriptPath, String workingDirectoryPath, String logPath) {
        return 0; // TODO: only here so it compiles
    }

    @Override
    public boolean killPair(int execId) {
        return false; // TODO: only here so it compiles
    }

    @Override
    public boolean killAll() {
        return false; // TODO: only here so it compiles
    }

    @Override
    public String getRunningJobsStatus() {
        return ""; // TODO: only here so it compiles
    }

    @Override
    public Set<Integer> getActiveExecutionIds() throws IOException {
        return new HashSet<>(); // TODO: only here so it compiles
    }

    @Override
    public String[] getWorkerNodes() {
        return new String[] { "localhost" }; // TODO: only here so it compiles
    }

    @Override
    public String[] getQueues() {
        return new String[] { R.DEFAULT_QUEUE_NAME }; // TODO: only here so it compiles
    }

    @Override
    public Map<String, String> getNodeQueueAssociations() {
        return new HashMap<>(); // TODO: only here so it compiles
    }

    @Override
    public boolean clearNodeErrorStates() {
        return true; // TODO: only here so it compiles
    }

    @Override
    public void deleteQueue(String queueName) {

    }

    @Override
    public boolean createQueue(String newQueueName, String[] nodeNames, String[] sourceQueueNames) {
        return false; // TODO: only here so it compiles
    }

    @Override
    public boolean createQueueWithSlots(String newQueueName, String[] nodeNames, String[] sourceQueueNames,
            Integer slots) {
        return false; // TODO: only here so it compiles
    }

    @Override
    public void moveNodes(String destQueueName, String[] nodeNames, String[] sourceQueueNames) {

    }

    @Override
    public void moveNode(String nodeName, String queueName) {

    }








    /*
     * Should wait for a k8s node to become
     * available and then run the job in the first available node.
     */
    private void runJob(LocalJob j) {
        log.error("Unimplemented");
        // try {
        //     j.process = Util.executeCommandAndReturnProcess(new String[] { j.scriptPath }, null,
        //             new File(j.workingDirectoryPath));
        //     ProcessBuilder builder = new ProcessBuilder(j.scriptPath);
        //     builder.redirectErrorStream(true);
        //     builder.directory(new File(j.workingDirectoryPath));
        //     builder.redirectOutput(new File(j.logPath));
        //     j.process = builder.start();
        //     j.process.waitFor();
        // } catch (Exception e) {
        //     log.error(e.getMessage(), e);
        // }
    }

    private synchronized void removeJob(LocalJob j) {
        jobsToRun.remove(j);
        activeIds.remove(j.execId);
    }

}
