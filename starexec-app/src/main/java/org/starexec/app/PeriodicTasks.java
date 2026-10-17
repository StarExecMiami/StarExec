package org.starexec.app;

import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.lang3.time.StopWatch;
import org.starexec.constants.R;
import org.starexec.data.database.*;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.to.ErrorLog;
import org.starexec.data.to.Queue;
import org.starexec.data.to.Status;
import org.starexec.data.to.User;
import org.starexec.data.to.tuples.PairIdJobId;
import org.starexec.exceptions.StarExecException;
import org.starexec.jobs.JobManager;
import org.starexec.jobs.ProcessingManager;
import org.starexec.logger.StarLogger;
import org.starexec.util.Mail;
import org.starexec.util.RobustRunnable;
import org.starexec.util.Util;

/**
 * Class that contains the periodic task enumerations.
 * The PeriodicTask enumeration contains all the periodic tasks as constant enum values so that these
 * values can be references from any location. Storing these values as an enum with fields makes sense because
 * the period, delay, task, etc. are all related to one task and storing these as separate constants would not enforce
 * this relationship. Using an enum also allows all the tasks to be gotten with EnumSet.allOf()
 */
class PeriodicTasks {

    private static final StarLogger log = StarLogger.getLogger(
        PeriodicTasks.class
    );

    // Enum constants of all the periodic tasks.
    enum PeriodicTask {
        // period needs to be a supplier since CLUSTER_UPDATE_PERIOD is dynamically set by configuration.
        UPDATE_CLUSTER(
            true,
            UPDATE_CLUSTER_TASK,
            0,
            () -> R.CLUSTER_UPDATE_PERIOD,
            TimeUnit.SECONDS
        ),
        SUBMIT_JOBS(true, SUBMIT_JOBS_TASK, 0, () -> 20, TimeUnit.SECONDS),
        POST_PROCESS_JOBS(
            true,
            POST_PROCESS_JOBS_TASK,
            0,
            () -> 45,
            TimeUnit.SECONDS
        ),
        RERUN_FAILED_PAIRS(
            true,
            RERUN_FAILED_PAIRS_TASK,
            0,
            () -> 90,
            TimeUnit.MINUTES
        ),
        FIND_BROKEN_JOB_PAIRS(
            true,
            FIND_BROKEN_JOB_PAIRS_TASK,
            0,
            () -> 3,
            TimeUnit.HOURS
        ),
        SEND_ERROR_LOGS(true, SEND_ERROR_LOGS_TASK, 0, () -> 1, TimeUnit.DAYS),
        CLEAR_TEMPORARY_FILES(
            false,
            CLEAR_TEMPORARY_FILES_TASK,
            0,
            () -> 3,
            TimeUnit.HOURS
        ),
        CLEAR_JOB_LOG(false, CLEAR_JOB_LOG_TASK, 0, () -> 7, TimeUnit.DAYS),
        CLEAR_JOB_GRAPHS(
            false,
            CLEAR_JOB_GRAPHS_TASK,
            0,
            () -> 7,
            TimeUnit.DAYS
        ),
        FIND_BROKEN_NODES(
            true,
            FIND_BROKEN_NODES_TASK,
            0,
            () -> 6,
            TimeUnit.HOURS
        ),
        CLEAR_JOB_SCRIPTS(
            false,
            CLEAR_JOB_SCRIPTS_TASK,
            0,
            () -> 12,
            TimeUnit.HOURS
        ),
        CLEAN_DATABASE(false, CLEAN_DATABASE_TASK, 0, () -> 7, TimeUnit.DAYS),
        CREATE_WEEKLY_REPORTS(
            false,
            CREATE_WEEKLY_REPORTS_TASK,
            0,
            () -> 1,
            TimeUnit.DAYS
        ),
        DELETE_OLD_ANONYMOUS_LINKS(
            false,
            DELETE_OLD_ANONYMOUS_LINKS_TASK,
            0,
            () -> 30,
            TimeUnit.DAYS
        ),
        UPDATE_USER_DISK_SIZES(
            false,
            UPDATE_USER_DISK_SIZES_TASK,
            0,
            () -> 1,
            TimeUnit.DAYS
        ),
        UPDATE_COMMUNITY_STATS(
            false,
            UPDATE_COMMUNITY_STATS_TASK,
            0,
            () -> 6,
            TimeUnit.HOURS
        ),
        SAVE_ANALYTICS(
            false,
            SAVE_ANALYTICS_TASK,
            10,
            () -> 10,
            TimeUnit.MINUTES
        ),
        NOTIFY_USERS_OF_JOBS(
            false,
            NOTIFY_USERS_OF_JOBS_TASK,
            0,
            () -> 5,
            TimeUnit.MINUTES
        ),
        GENERATE_CLUSTER_GRAPH(
            true,
            GENERATE_CLUSTER_GRAPH_TASK,
            5,
            () -> 5,
            TimeUnit.SECONDS
        ),
        CLEAN_QUEUE_METRICS(
            true,
            CLEAN_QUEUE_METRICS_TASK,
            0,
            () -> 1,
            TimeUnit.HOURS
        ),
        CHECK_STUCK_JOBS(
            true,
            CHECK_STUCK_JOBS_TASK,
            0,
            () -> 5,
            TimeUnit.MINUTES
        ),
        RETRY_REFUSED_JOB_DELETIONS(
            true,
            RETRY_REFUSED_JOB_DELETIONS_TASK,
            0,
            () -> 5,
            TimeUnit.MINUTES
        );

        //CLEAR_JOB_SCRIPTS(true, CLEAR_JOB_SCRIPTS_TASK, 0, () -> 7, TimeUnit.DAYS);

        public final boolean fullInstanceOnly;
        public final Runnable task;
        public final int delay;
        public final Supplier<Integer> period;
        public final TimeUnit unit;

        /**
         *
         * @param fullInstanceOnly true if this task should only be run when a working backend is configured
         * @param task the runnable that will be run for the task.
         * @param delay initial delay before the task should be run.
         * @param period the period between each successive run of the task.
         * @param unit the time unit to use for period and delay.
         */
        PeriodicTask(
            boolean fullInstanceOnly,
            Runnable task,
            int delay,
            Supplier<Integer> period,
            TimeUnit unit
        ) {
            this.fullInstanceOnly = fullInstanceOnly;
            this.delay = delay;
            this.period = period;
            this.unit = unit;
            this.task = task;
        }
    }

    private static final String sendErrorLogsTaskName = "sendErrorReportsTask";
    private static final Runnable SEND_ERROR_LOGS_TASK = new RobustRunnable(
        sendErrorLogsTaskName
    ) {
        @Override
        protected void dorun() {
            // Calculate the timestamp from yesterday.
            Calendar calendar = Calendar.getInstance();
            calendar.add(
                Calendar.DAY_OF_YEAR,
                -R.TIME_BETWEEN_SENDING_ERROR_LOGS
            );
            Timestamp yesterday = new Timestamp(calendar.getTime().getTime());

            try {
                if (ErrorLogs.existBefore(yesterday)) {
                    log.info(
                        "Found error logs from " +
                            R.TIME_BETWEEN_SENDING_ERROR_LOGS +
                            " days ago. Sending error logs..."
                    );
                    // Make sure users we're emailing are developers and admins.
                    List<User> usersSubscribedToErrorLogs =
                        Users.getUsersSubscribedToErrorLogs();
                    for (User user : usersSubscribedToErrorLogs) {
                        if (
                            !GeneralSecurity.hasAdminReadPrivileges(
                                user.getId()
                            )
                        ) {
                            log.error(
                                "Found user who wasn't developer/admin while emailing error logs: " +
                                    user.getEmail()
                            );
                        }
                    }

                    usersSubscribedToErrorLogs = usersSubscribedToErrorLogs
                        .stream()
                        .filter(u ->
                            GeneralSecurity.hasAdminReadPrivileges(u.getId())
                        )
                        .collect(Collectors.toList());

                    // Gather the error logs and send them.
                    List<ErrorLog> allLogs = ErrorLogs.getAll();
                    Mail.sendErrorLogEmails(
                        allLogs,
                        usersSubscribedToErrorLogs
                    );

                    // Delete all the error logs, in the future we may keep some until they get too old.
                    ErrorLogs.deleteAll();
                } else {
                    log.info(
                        "No error logs from " +
                            R.TIME_BETWEEN_SENDING_ERROR_LOGS +
                            " days ago. Not sending error logs."
                    );
                }
            } catch (SQLException e) {
                log.error("Failed to send error log emails.", e);
            }
        }
    };

    private static final String updateClusterTaskName = "updateClusterTask";
    // Create a task that updates the cluster usage info (this may take some time)
    private static final Runnable UPDATE_CLUSTER_TASK = new RobustRunnable(
        updateClusterTaskName
    ) {
        @Override
        protected void dorun() {
            Cluster.loadWorkerNodes();
            Cluster.loadQueueDetails();
        }
    };

    private static final String generateClusterGraphTaskName =
        "generateClusterGraphTask";
    private static final Runnable GENERATE_CLUSTER_GRAPH_TASK =
        new RobustRunnable(generateClusterGraphTaskName) {
            @Override
            protected void dorun() {
                try {
                    List<Queue> activeQueues = Queues.getAllActive();
                    if (activeQueues != null) {
                        for (Queue q : activeQueues) {
                            int qId = q.getId();
                            int size = Queues.getSizeOfQueue(qId);
                            Queues.recordQueueSize(qId, size);
                        }
                    }
                } catch (Exception e) {
                    log.warn(e.getMessage());
                }
            }
        };

    private static final String cleanQueueMetricsTaskName = "cleanQueueMetricsTask";
    private static final Runnable CLEAN_QUEUE_METRICS_TASK = new RobustRunnable(
        cleanQueueMetricsTaskName
    ) {
        @Override
        protected void dorun() {
            try {
                Queues.pruneOldQueueMetrics(24);
            } catch (Exception e) {
                log.error("Error pruning old queue metrics", e);
            }
        }
    };

    private static final String submitJobTasksName = "submitJobTasks";
    // Create a task that submits jobs that have pending/rejected job pairs
    private static final Runnable SUBMIT_JOBS_TASK = new RobustRunnable(
        submitJobTasksName
    ) {
        @Override
        protected void dorun() {
            JobManager.checkPendingJobs();
        }
    };

    private static final String rerunFailedPairsTaskName =
        "rerunFailedPairsTask";
    private static final Runnable RERUN_FAILED_PAIRS_TASK = new RobustRunnable(
        rerunFailedPairsTaskName
    ) {
        @Override
        protected void dorun() {
            try {
                // Get all pairs that haven't already been rerun that have the ERROR_RUNSCRIPT status and
                // rerun them.
                StopWatch timer = new StopWatch();
                timer.start();
                List<Integer> pairIdsToRerun =
                    JobPairs.getPairIdsByStatusNotRerunAfterDate(
                        Status.StatusCode.ERROR_RUNSCRIPT,
                        R.earliestDateToRerunFailedPairs()
                    );
                timer.stop();
                log.info(
                    "(" +
                        this.name +
                        ")" +
                        " Got " +
                        pairIdsToRerun.size() +
                        " in " +
                        timer.toString()
                );

                for (Integer pairId : pairIdsToRerun) {
                    // Per pair, so one failure cannot abort the pass. The previous code
                    // caught outside this loop, so a single throwing pair skipped every
                    // remaining one under a message describing a selection failure that
                    // had already succeeded.
                    try {
                        Jobs.RerunOutcome outcome = Jobs.rerunPairAutomatic(pairId);
                        if (outcome != Jobs.RerunOutcome.COMPLETED) {
                            // Not an error. Nothing was written, so the pair keeps its
                            // ERROR_RUNSCRIPT status and end_time and the next pass — or
                            // the next JVM — selects it again. The database owns this
                            // retry; there is deliberately no in-memory record of it.
                            log.info(
                                "(" + this.name + ") pair " + pairId +
                                    " not rerun this pass: " + outcome
                            );
                        }
                    } catch (Exception e) {
                        log.warn(
                            this.name + " could not rerun pair " + pairId,
                            e
                        );
                    }
                }
            } catch (SQLException e) {
                log.warn(
                    this.name +
                        " caught SQLException. Could not get pairs to rerun from database.",
                    e
                );
            }
        }
    };

    private static final String postProcessJobsTaskName = "postProcessJobsTask";
    // Create a task that submits jobs that have pending/rejected job pairs
    private static final Runnable POST_PROCESS_JOBS_TASK = new RobustRunnable(
        postProcessJobsTaskName
    ) {
        @Override
        protected void dorun() {
            ProcessingManager.checkProcessingPairs();
        }
    };

    // Create a task that deletes download files older than 1 day
    private static final String clearTemporaryFilesTaskName =
        "clearTemporaryFilesTask";
    private static final Runnable CLEAR_TEMPORARY_FILES_TASK =
        new RobustRunnable(clearTemporaryFilesTaskName) {
            @Override
            protected void dorun() {
                Util.clearOldFiles(
                    new File(
                        R.STAREXEC_ROOT,
                        R.DOWNLOAD_FILE_DIR
                    ).getAbsolutePath(),
                    1,
                    false
                );

                // clear sandbox files older than one day old
                Util.clearOldSandboxFiles(
                    Util.getSandboxDirectory().getAbsolutePath(),
                    1
                );
            }
        };

    /*  Create a task that deletes job logs older than 7 days */
    private static final String clearJobLogTaskName = "clearJobLogTask";
    private static final Runnable CLEAR_JOB_LOG_TASK = new RobustRunnable(
        clearJobLogTaskName
    ) {
        @Override
        protected void dorun() {
            Util.clearOldFiles(
                R.JOB_LOG_DIRECTORY,
                R.CLEAR_JOB_LOG_PERIOD,
                true
            );
        }
    };
    /*  Create a task that deletes job graphs older than 7 days */
    private static final String clearJobGraphsTaskName = "clearJobGraphsTask";
    private static final Runnable CLEAR_JOB_GRAPHS_TASK = new RobustRunnable(
        clearJobGraphsTaskName
    ) {
        @Override
        protected void dorun() {
            // Statistics writes graphs under the web root; R.JOBGRAPH_FILE_DIR alone is "/jobgraphs" at the
            // filesystem root, which does not exist, so the task used to delete nothing.
            Util.clearOldFiles(
                new File(R.STAREXEC_ROOT, R.JOBGRAPH_FILE_DIR).getAbsolutePath(),
                7,
                true
            );
        }
    };
    /*  Create a task that deletes job scripts older than 3 days */
    private static final String clearJobScriptTaskName = "clearJobScriptTask";
    private static final Runnable CLEAR_JOB_SCRIPTS_TASK = new RobustRunnable(
        clearJobScriptTaskName
    ) {
        @Override
        protected void dorun() {
            Util.clearOldFiles(R.getJobInboxDir(), 1, false);
        }
    };
    /**
     * Removes solvers and benchmarks from the database that are both orphaned (unaffiliated
     * with any spaces or job pairs) AND have already been deleted on disk.
     */
    private static final String cleanDatabaseTaskName = "cleanDatabaseTask";
    private static final Runnable CLEAN_DATABASE_TASK = new RobustRunnable(
        cleanDatabaseTaskName
    ) {
        @Override
        protected void dorun() {
            Solvers.cleanOrphanedDeletedSolvers();
            Benchmarks.cleanOrphanedDeletedBenchmarks();
            Jobs.cleanOrphanedDeletedJobs();
        }
    };

    private static final String findBrokenNodesTaskName = "findBrokenNodes";
    private static final Runnable FIND_BROKEN_NODES_TASK = new RobustRunnable(
        findBrokenNodesTaskName
    ) {
        @Override
        protected void dorun() {
            try {
                ImmutableSet<PairIdJobId> brokenPairs =
                    JobPairs.getPairsEnqueuedLongerThan(
                        R.PAIR_ENQUEUE_TIME_THRESHOLD
                    );
                for (PairIdJobId pairAndJob : brokenPairs) {
                    log.warn(
                        "Detected pair that has been enqueued for " +
                            R.PAIR_ENQUEUE_TIME_THRESHOLD +
                            " minutes " +
                            "without running. Pair has id " +
                            pairAndJob.pairId +
                            " and is in job with id " +
                            pairAndJob.jobId
                    );
                }
            } catch (SQLException e) {
                log.error("Database error while searching for broken pairs.");
            }
        }
    };

    private static final String findBrokenJobPairsTaskName =
        "findBrokenJobPairs";
    private static final Runnable FIND_BROKEN_JOB_PAIRS_TASK =
        new RobustRunnable(findBrokenJobPairsTaskName) {
            @Override
            protected void dorun() {
                try {
                    Jobs.setBrokenPairsToErrorStatus(R.BACKEND);
                } catch (IOException e) {
                    log.error("Caught IOException: " + e.getMessage(), e);
                }
            }
        };

    private static final String updateUserDiskSizesTaskName =
        "updateUserDiskSizesTask";
    private static final Runnable UPDATE_USER_DISK_SIZES_TASK =
        new RobustRunnable(updateUserDiskSizesTaskName) {
            @Override
            protected void dorun() {
                if (!Users.updateAllUserDiskSizes()) {
                    log.error("failed to update user disk sizes (periodic)");
                }
            }
        };

    private static final String deleteOldAnonymousLinksTaskName =
        "deleteOldAnonymousLinksTask";
    private static final Runnable DELETE_OLD_ANONYMOUS_LINKS_TASK =
        new RobustRunnable(deleteOldAnonymousLinksTaskName) {
            @Override
            protected void dorun() {
                try {
                    AnonymousLinks.deleteOldLinks(
                        R.MAX_AGE_OF_ANONYMOUS_LINKS_IN_DAYS
                    );
                } catch (SQLException e) {
                    log.error(
                        "Caught SQLException: Failed to delete old anonymous links.",
                        e
                    );
                }
            }
        };

    private static final String updateCommunityStatsTaskName =
        "updateCommunityStats";
    private static final Runnable UPDATE_COMMUNITY_STATS_TASK =
        new RobustRunnable(updateCommunityStatsTaskName) {
            @Override
            protected void dorun() {
                Communities.updateCommunityMap();
            }
        };

    private static final String weeklyReportsTaskName = "weeklyReportsTask";
    private static final Runnable CREATE_WEEKLY_REPORTS_TASK =
        new RobustRunnable(weeklyReportsTaskName) {
            @Override
            protected void dorun() {
                // create the reports directory in the starexec data directory if it does not already exist
                String dataDirectory = R.STAREXEC_DATA_DIR;
                File reportsDirectory = new File(dataDirectory, "/reports/");
                if (!reportsDirectory.exists()) {
                    try {
                        log.debug(
                            "Attempting to create reports directory " +
                                dataDirectory +
                                "/reports/"
                        );
                        boolean reportsDirectoryCreated =
                            reportsDirectory.mkdir();
                        if (!reportsDirectoryCreated) {
                            log.error(
                                "Starexec does not have permission to create the reports directory in " +
                                    dataDirectory
                            );
                        }
                    } catch (SecurityException e) {
                        log.error(
                            "Starexec does not have permission to create the reports directory in " +
                                dataDirectory,
                            e
                        );
                    }
                }

                List<User> subscribedUsers =
                    Users.getAllUsersSubscribedToReports();
                try {
                    if (
                        subscribedUsers != null &&
                        subscribedUsers.size() > Users.getCount()
                    ) {
                        // make sure that we're not sending unnecessary emails
                        throw new StarExecException(
                            "There are more users subscribed to reports than users in the system!"
                        );
                    }

                    Calendar today = Calendar.getInstance();
                    // check if it's the day to email reports and check if reports were already sent today
                    if (
                        subscribedUsers != null &&
                        today.get(Calendar.DAY_OF_WEEK) ==
                        R.EMAIL_REPORTS_DAY &&
                        !Mail.reportsEmailedToday()
                    ) {
                        String reportsEmail =
                            Mail.generateGenericReportsEmail();
                        log.info(
                            "Storing reports and sending reports to subscribed users."
                        );
                        Mail.storeReportsEmail(reportsEmail);
                        Mail.sendReports(subscribedUsers, reportsEmail);
                        // Set all the events occurrences in the reports table back to 0.
                        Reports.resetReports();
                        // Erase all data from the logins table.
                        Logins.resetLogins();
                    }
                } catch (IOException e) {
                    log.error(
                        "Issue while storing reports email as text file.",
                        e
                    );
                } catch (StarExecException e) {
                    log.error("Caught StarExecException: " + e.getMessage(), e);
                } catch (SQLException e) {
                    log.error("Caught SQLException: " + e.getMessage(), e);
                }
            }
        };

    private static final String saveAnalyticsTask = "saveAnalyticsTask";
    // Create a task that submits jobs that have pending/rejected job pairs
    private static final Runnable SAVE_ANALYTICS_TASK = new RobustRunnable(
        saveAnalyticsTask
    ) {
        @Override
        protected void dorun() {
            Analytics.saveToDB();
        }
    };

    // Create a task that notifies Users of status changes to Jobs they have
    // subscribed to
    private static final String notifyUsersOfJobsTask = "notifyUsersOfJobsTask";
    private static final Runnable NOTIFY_USERS_OF_JOBS_TASK =
        new RobustRunnable(notifyUsersOfJobsTask) {
            @Override
            protected void dorun() {
                Notifications.sendEmailNotifications();
            }
        };

    // Finish job deletions that were refused because a pair could not be confirmed stopped
    private static final String retryRefusedJobDeletionsTaskName = "retryRefusedJobDeletionsTask";
    private static final Runnable RETRY_REFUSED_JOB_DELETIONS_TASK = new RobustRunnable(
        retryRefusedJobDeletionsTaskName
    ) {
        @Override
        protected void dorun() {
            Jobs.retryRefusedDeletions();
        }
    };

    // Check for stuck jobs that have been incomplete for too long
    private static final String checkStuckJobsTaskName = "checkStuckJobsTask";
    private static final Runnable CHECK_STUCK_JOBS_TASK = new RobustRunnable(
        checkStuckJobsTaskName
    ) {
        @Override
        protected void dorun() {
            checkForStuckJobs();
        }
    };

    /** Grace beyond a job's own wallclock limit before a silent job is reported as stuck. */
    private static final int STUCK_JOB_GRACE_SECONDS = 600;
    /** The shortest silence reported as stuck, whatever the job's wallclock limit. */
    private static final int STUCK_JOB_MIN_SILENCE_SECONDS = 1800;
    /** How long a job that stays stuck goes before it is reported again. */
    public static final long STUCK_JOB_REPEAT_MILLIS = TimeUnit.HOURS.toMillis(6);
    /** Job id to when it was last reported, for the jobs currently stuck. */
    private static final Map<Integer, Long> stuckJobsLastReported = new ConcurrentHashMap<>();

    /** A job with unfinished pairs that has stopped making progress, as the database shows it. */
    public static final class StuckJob {
        public final int jobId;
        public final String name;
        /** The job's queue, or null when it no longer exists. */
        public final String queueName;
        public final String queueStatus;
        public final int pending;
        public final int enqueued;
        public final int running;
        public final int processing;
        /** Minutes since a pair of the job last started or finished, or since it was created. */
        public final long idleMinutes;

        public StuckJob(
            int jobId,
            String name,
            String queueName,
            String queueStatus,
            int pending,
            int enqueued,
            int running,
            int processing,
            long idleMinutes
        ) {
            this.jobId = jobId;
            this.name = name;
            this.queueName = queueName;
            this.queueStatus = queueStatus;
            this.pending = pending;
            this.enqueued = enqueued;
            this.running = running;
            this.processing = processing;
            this.idleMinutes = idleMinutes;
        }
    }

    /**
     * Reports jobs that have stopped making progress.
     *
     * <p>This used to report every job older than ten minutes that still had unfinished pairs:
     * a healthy job running for hours as much as one that cannot run at all. It named no cause,
     * only advice about LocalBackend and LocalJobMonitor whatever the backend, and it wrote five
     * WARN lines, so five error_logs rows, per job every five minutes. On Quokka it reported a
     * job that way around the clock without saying why it was stuck: its queue was inactive.
     *
     * <p>Now a job is reported only when no pair of it has started or finished for longer than
     * its own wallclock limit plus {@link #STUCK_JOB_GRACE_SECONDS}, and for at least
     * {@link #STUCK_JOB_MIN_SILENCE_SECONDS}. Paused, killed and deleted jobs are not stuck.
     * The report is one line naming what the database shows, and a job that stays stuck is
     * reported again only after {@link #STUCK_JOB_REPEAT_MILLIS}.
     */
    private static void checkForStuckJobs() {
        try {
            List<StuckJob> stuck = findStuckJobs();
            long now = System.currentTimeMillis();
            Set<Integer> stuckIds = new HashSet<>();
            for (StuckJob job : stuck) {
                stuckIds.add(job.jobId);
                if (shouldReportStuckJob(stuckJobsLastReported, job.jobId, now)) {
                    log.warn(describeStuckJob(job));
                }
            }
            // A job that is no longer stuck is reported afresh if it gets stuck again.
            stuckJobsLastReported.keySet().retainAll(stuckIds);
        } catch (Exception e) {
            log.error("Error checking for stuck jobs", e);
        }
    }

    /** The jobs that have stopped making progress; see {@link #checkForStuckJobs}. */
    public static List<StuckJob> findStuckJobs() throws SQLException {
        // Unfinished means 1 PENDING_SUBMIT, 2 ENQUEUED, 4 RUNNING, 19 PROCESSING_RESULTS or
        // 22 PROCESSING. PAUSED (20) is left out: a paused pair is waiting on purpose.
        final String query =
            "WITH unfinished AS (" +
            "  SELECT DISTINCT job_id FROM starexec.job_pairs" +
            "  WHERE status_code IN (1, 2, 4, 19, 22)" +
            ") " +
            "SELECT j.id, j.name, q.name AS queue_name, q.status AS queue_status, " +
            "  count(*) FILTER (WHERE jp.status_code = 1) AS pending, " +
            "  count(*) FILTER (WHERE jp.status_code = 2) AS enqueued, " +
            "  count(*) FILTER (WHERE jp.status_code = 4) AS running, " +
            "  count(*) FILTER (WHERE jp.status_code IN (19, 22)) AS processing, " +
            "  floor(extract(epoch FROM now() - greatest(max(jp.start_time), max(jp.end_time)," +
            "    j.created)) / 60)::bigint AS idle_minutes " +
            "FROM unfinished u " +
            "JOIN starexec.jobs j ON j.id = u.job_id " +
            "JOIN starexec.job_pairs jp ON jp.job_id = j.id " +
            "LEFT JOIN starexec.queues q ON q.id = j.queue_id " +
            "WHERE NOT coalesce(j.paused, false) AND NOT coalesce(j.killed, false)" +
            "  AND NOT coalesce(j.deleted, false) " +
            "GROUP BY j.id, j.name, q.name, q.status, j.created, j.clocktimeout " +
            "HAVING now() - greatest(max(jp.start_time), max(jp.end_time), j.created)" +
            "  > make_interval(secs => greatest(coalesce(j.clocktimeout, 0) + ?, ?)) " +
            "ORDER BY j.id";
        return Common.runInTransaction(con -> {
            List<StuckJob> jobs = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(query)) {
                ps.setInt(1, STUCK_JOB_GRACE_SECONDS);
                ps.setInt(2, STUCK_JOB_MIN_SILENCE_SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        jobs.add(new StuckJob(
                            rs.getInt("id"),
                            rs.getString("name"),
                            rs.getString("queue_name"),
                            rs.getString("queue_status"),
                            rs.getInt("pending"),
                            rs.getInt("enqueued"),
                            rs.getInt("running"),
                            rs.getInt("processing"),
                            rs.getLong("idle_minutes")
                        ));
                    }
                }
            }
            return jobs;
        });
    }

    /** One line naming what the database shows about a stuck job, and the likely cause. */
    public static String describeStuckJob(StuckJob job) {
        String state = String.format(
            "%d pending, %d enqueued, %d running, %d awaiting result processing",
            job.pending, job.enqueued, job.running, job.processing
        );
        String cause;
        if (job.pending > 0 && job.queueName == null) {
            cause = "its queue no longer exists, so nothing can dispatch its pending pairs";
        } else if (job.pending > 0 && !R.QUEUE_STATUS_ACTIVE.equals(job.queueStatus)) {
            cause = "its queue '" + job.queueName + "' is " + job.queueStatus +
                ", so its pending pairs cannot be dispatched";
        } else if (job.enqueued + job.running > 0) {
            cause = "pairs are in flight but none has started or finished; check the backend" +
                " and its job monitor";
        } else if (job.pending > 0) {
            cause = "nothing is being dispatched from queue '" + job.queueName + "'; check that" +
                " the queue has nodes the backend can use";
        } else {
            cause = "its pairs are waiting for result processing";
        }
        return String.format(
            "Job %d (%s) has made no progress for %d minutes (%s): %s",
            job.jobId, job.name, job.idleMinutes, state, cause
        );
    }

    /** Whether a stuck job is due a report now; records the report when it is. */
    public static boolean shouldReportStuckJob(
        Map<Integer, Long> lastReported,
        int jobId,
        long now
    ) {
        Long last = lastReported.get(jobId);
        if (last != null && now - last < STUCK_JOB_REPEAT_MILLIS) {
            return false;
        }
        lastReported.put(jobId, now);
        return true;
    }
}
