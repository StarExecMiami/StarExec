package org.starexec.data.database;

import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.Map.Entry;
import org.apache.commons.io.FileUtils;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.to.*;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.data.to.enums.ConfigXmlAttribute;
import org.starexec.data.to.pipelines.JoblineStage;
import org.starexec.data.to.pipelines.PairStageProcessorTriple;
import org.starexec.data.to.tuples.ConfigAttrMapPair;
import org.starexec.data.to.tuples.PairIdJobId;
import org.starexec.logger.StarLogger;
import org.starexec.util.Hash;
import org.starexec.util.Util;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Contains handles on database queries for retrieving and updating job pairs.
 */
public class JobPairs {

    private static final StarLogger log = StarLogger.getLogger(JobPairs.class);
    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private static final int MANIFEST_STATE_COLLECTING = 0;
    private static final int MANIFEST_STATE_FINALIZING = 1;
    private static final int MANIFEST_STATE_FINAL = 2;
    private static final int MANIFEST_STATE_FINAL_DERIVED = 3;
    private static final int MANIFEST_STATE_FAILED = 4;

    private static final int MANIFEST_PROVENANCE_LIVE = 0;
    private static final int MANIFEST_PROVENANCE_DERIVED_LEGACY = 1;

    public static final class PairReproManifestResult {
        public final int pairId;
        public final int attemptNo;
        public final int state;
        public final int provenance;
        public final int schemaVersion;
        public final String manifestJson;
        public final String manifestSha256;
        public final Integer sourceStatusCode;
        public final Timestamp createdAt;
        public final Timestamp updatedAt;
        public final Timestamp finalizedAt;

        private PairReproManifestResult(
            int pairId,
            int attemptNo,
            int state,
            int provenance,
            int schemaVersion,
            String manifestJson,
            String manifestSha256,
            Integer sourceStatusCode,
            Timestamp createdAt,
            Timestamp updatedAt,
            Timestamp finalizedAt
        ) {
            this.pairId = pairId;
            this.attemptNo = attemptNo;
            this.state = state;
            this.provenance = provenance;
            this.schemaVersion = schemaVersion;
            this.manifestJson = manifestJson;
            this.manifestSha256 = manifestSha256;
            this.sourceStatusCode = sourceStatusCode;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.finalizedAt = finalizedAt;
        }
    }

    /**
     * @return true when every input was written; false when none were, or an unknown
     * number were. Returning void here meant a failed batch was logged and then reported
     * as success by the caller, so a pair could be committed without the inputs its
     * pipeline requires.
     */
    private static boolean addJobPairInputs(List<JobPair> pairs, Connection con) {
        final String methodName = "addJobPairInputs";
        PreparedStatement ps = null;
        int batchCounter = 0;
        int totalPairsSubmitted = 0;
        try {
            ps = con.prepareStatement(
                "SELECT starexec.AddJobPairInput(?, ?, ?)"
            );

            for (JobPair pair : pairs) {
                for (int i = 0; i < pair.getBenchInputs().size(); i++) {
                    ps.setInt(1, pair.getId());
                    ps.setInt(2, i + 1);
                    ps.setInt(3, pair.getBenchInputs().get(i));

                    ps.addBatch();
                    batchCounter++;
                    final int batchSize = 1000;
                    if (batchCounter > batchSize) {
                        totalPairsSubmitted += batchSize;
                        log.debug(
                            methodName,
                            "Submitting batch of " +
                                batchSize +
                                " inputs. Total pairs submitted: " +
                                totalPairsSubmitted
                        );
                        ps.executeBatch();
                        batchCounter = 0;
                    }
                }
            }
            if (batchCounter > 0) {
                totalPairsSubmitted += batchCounter;
                log.debug(
                    methodName,
                    "Submitting final batch of " +
                        batchCounter +
                        " inputs. Total pairs submitted: " +
                        totalPairsSubmitted
                );
                ps.executeBatch();
            }
            return true;
        } catch (Exception e) {
            log.error(
                methodName,
                "Exception occurred while adding job pair inputs.",
                e
            );
        } finally {
            Common.safeClose(ps);
        }
        return false;
    }

    public static Optional<
        String
    > populateConfigIdsToSolversMapAndJobPairsForJobXMLUpload(
        final String rootName,
        final int userId,
        final Map<Integer, Benchmark> accessibleCachedBenchmarks,
        final HashMap<Integer, Solver> configIdsToSolvers,
        final Job job,
        final int spaceId,
        final HashSet<String> jobRootPaths,
        final ConfigAttrMapPair configAttrMapPair,
        final NodeList jobPairs
    ) {
        final String methodName = "populateJobPairsForJobXMLUpload";
        Connection con = null;

        // Benchmarks the user can see that we've already seen.
        try {
            con = Common.getConnection();

            // we now iterate through all the job pair elements and add them all to the job
            final int jobPairsLength = jobPairs.getLength();
            for (int i = 0; i < jobPairsLength; i++) {
                final Node jobPairNode = jobPairs.item(i);
                if (jobPairNode.getNodeType() == Node.ELEMENT_NODE) {
                    final Element jobPairElement = (Element) jobPairNode;
                    final JobPair jobPair = new JobPair();
                    final int benchmarkId = Integer.parseInt(
                        jobPairElement.getAttribute("bench-id")
                    );
                    final int configId = getConfigIdFromElement(
                        jobPairElement,
                        configAttrMapPair
                    );
                    // final int configId =
                    // Integer.parseInt(jobPairElement.getAttribute("config-id"));
                    String path = jobPairElement.getAttribute("job-space-path");
                    if (path.isEmpty()) {
                        path = rootName;
                    }
                    jobPair.setPath(path);
                    if (path.contains(R.JOB_PAIR_PATH_DELIMITER)) {
                        jobRootPaths.add(
                            path.substring(
                                0,
                                path.indexOf(R.JOB_PAIR_PATH_DELIMITER)
                            )
                        );
                    } else {
                        jobRootPaths.add(path);
                    }

                    Benchmark b = null;
                    // permissions check on the benchmark for this job pair
                    if (accessibleCachedBenchmarks.containsKey(benchmarkId)) {
                        b = accessibleCachedBenchmarks.get(benchmarkId);
                    } else {
                        b = Benchmarks.get(con, benchmarkId, false);
                        if (b == null) {
                            Benchmark errorBench = Benchmarks.get(
                                con,
                                benchmarkId,
                                true,
                                true
                            );
                            if (errorBench == null) {
                                return Optional.of(
                                    "Found null reference to benchmark: " +
                                        benchmarkId
                                );
                            } else if (errorBench.isDeleted()) {
                                return Optional.of(
                                    errorBench.getName() +
                                        " has been deleted by it's user."
                                );
                            } else if (errorBench.isRecycled()) {
                                return Optional.of(
                                    errorBench.getName() +
                                        " has been recycled by it's user."
                                );
                            } else {
                                return Optional.of(
                                    "Unknown problem with benchmark: " +
                                        benchmarkId
                                );
                            }
                        }
                        if (
                            !Permissions.canUserSeeBench(
                                con,
                                benchmarkId,
                                userId
                            )
                        ) {
                            return Optional.of(
                                "You do not have permission to see benchmark " +
                                    benchmarkId
                            );
                        }

                        // Cache the benchmark
                        accessibleCachedBenchmarks.put(benchmarkId, b);
                    }
                    jobPair.setBench(b);
                    if (!configIdsToSolvers.containsKey(configId)) {
                        // permissions check on the solver for the pair. Configurations do
                        // not have permissions by themselves-- their permissions are identical to the
                        // solver
                        // permissions
                        Solver s = Solvers.getSolverByConfig(
                            con,
                            configId,
                            true
                        );
                        if (s == null) {
                            return Optional.of(
                                "Found null reference to solver referenced by config id: " +
                                    configId
                            );
                        }
                        if (s.isDeleted() || s.isRecycled()) {
                            return Optional.of(
                                "This solver associated with config " +
                                    configId +
                                    " has been deleted or recycled, solverId: " +
                                    s.getId()
                            );
                        }

                        if (
                            !Permissions.canUserSeeSolver(
                                con,
                                s.getId(),
                                userId
                            )
                        ) {
                            return Optional.of(
                                "You do not have permission to see the solver " +
                                    s.getId()
                            );
                        }

                        s.addConfiguration(
                            Solvers.getConfiguration(con, configId)
                        );
                        configIdsToSolvers.put(configId, s);
                    }
                    Solver s = configIdsToSolvers.get(configId);

                    // JobPair elements are for pairs with exactly one stage, so we create a stage
                    // to house the solver and benchmark
                    JoblineStage stage = new JoblineStage();
                    stage.setStageNumber(1);
                    stage.setSolver(s);
                    stage.setConfiguration(s.getConfigurations().get(0));

                    jobPair.addStage(stage);
                    // the primary stage is the one we just added
                    jobPair.setPrimaryStageNumber(jobPair.getStages().size());
                    jobPair.setSpace(Spaces.get(spaceId, con));

                    job.addJobPair(jobPair);
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            log.error(methodName, e);
            return Optional.empty();
        } finally {
            Common.safeClose(con);
        }
    }

    public static Integer getConfigIdFromElement(
        Element element,
        ConfigAttrMapPair configAttrMapPair
    ) {
        String attribute = element.getAttribute(
            configAttrMapPair.attribute.attribute
        );
        if (configAttrMapPair.attribute == ConfigXmlAttribute.NAME) {
            // The attribute should be the config name.
            Map<String, Integer> configNameToId =
                configAttrMapPair.configNameToId;
            if (configNameToId.containsKey(attribute)) {
                return configNameToId.get(attribute);
            } else {
                throw new IllegalStateException(
                    "There is no config with the name, " +
                        attribute +
                        ", in the uploaded solver."
                );
            }
        } else {
            // The attribute should be the id of the config.
            return Integer.parseInt(attribute);
        }
    }

    /**
     * Retrieves all the inputs to the given pair from the jobpair_inputs table.
     * Inputs will be ordered by their input
     * numbers (in other words, first input, second input, and so on)
     *
     * @param pairId
     * @param con    An open database connection to make calls on
     * @return A list of strings pointing to the inputs for this pair, or null on
     *         error.
     */
    public static List<String> getJobPairInputPaths(
        int pairId,
        Connection con
    ) {
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetJobPairInputPaths(?)"
            );
            ps.setInt(1, pairId);
            results = ps.executeQuery();
            List<String> benchmarkPaths = new ArrayList<>();
            while (results.next()) {
                benchmarkPaths.add(results.getString("path"));
            }
            return benchmarkPaths;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            Common.safeClose(ps);
            Common.safeClose(results);
        }
        return null;
    }

    /**
     * Adds all the jobline stages for all of the given pairs to the database
     *
     * @param pairs The pairs to add the stages of
     * @param con   The open connection to make the call on
     */
    private static boolean addJobPairStages(List<JobPair> pairs, Connection con) {
        final String methodName = "addJobPairStages";
        PreparedStatement ps = null;
        int totalPairsSubmitted = 0;
        try {
            int batchCounter = 0;
            ps = con.prepareStatement(
                "SELECT starexec.AddJobPairStage(?, ?, ?, ?, ?, ?, ?, ?, ?)"
            );

            for (JobPair pair : pairs) {
                for (JoblineStage stage : pair.getStages()) {
                    if (stage.isNoOp()) {
                        continue;
                    }

                    ps.setInt(1, pair.getId());
                    if (stage.getStageId() != null) {
                        ps.setInt(2, stage.getStageId());
                    } else {
                        ps.setNull(2, java.sql.Types.INTEGER);
                    }
                    ps.setInt(3, stage.getStageNumber());
                    ps.setBoolean(
                        4,
                        Objects.equals(
                            pair.getPrimaryStageNumber(),
                            stage.getStageNumber()
                        )
                    );
                    ps.setInt(5, stage.getSolver().getId());
                    ps.setString(6, stage.getSolver().getName());
                    ps.setInt(7, stage.getConfiguration().getId());
                    ps.setString(8, stage.getConfiguration().getName());
                    ps.setInt(9, pair.getJobSpaceId());
                    // Update the pair's ID so it can be used outside this method
                    ps.addBatch();

                    batchCounter++;
                    final int batchSize = 1000;
                    if (batchCounter > batchSize) {
                        totalPairsSubmitted += batchSize;
                        log.debug(
                            methodName,
                            "Submitting batch of " +
                                batchSize +
                                ", total pairs submitted: " +
                                totalPairsSubmitted
                        );
                        ps.executeBatch();
                        batchCounter = 0;
                    }
                }
            }
            if (batchCounter > 0) {
                totalPairsSubmitted += batchCounter;
                log.debug(
                    methodName,
                    "Submitting batch of " +
                        batchCounter +
                        ", total pairs submitted: " +
                        totalPairsSubmitted
                );
                ps.executeBatch();
            }
            return true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            Common.safeClose(ps);
        }
        return false;
    }

    public static void addJobPairs(int jobId, List<JobPair> pairs) {
        final String methodName = "addJobPairs";

        Connection con = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);
            log.entry(methodName);
            boolean success = incrementTotalJobPairsForJob(
                jobId,
                pairs.size(),
                con
            );
            if (!success) {
                log.error(
                    methodName,
                    "Failed to increment total job pairs for jobId: " + jobId
                );
                return;
            }
            log.debug(
                methodName,
                "Successfully incremented total job pairs for jobId: " + jobId
            );

            addJobPairs(con, jobId, pairs);
            log.exit(methodName);
        } catch (SQLException e) {
            log.error(
                methodName,
                "SQL Exception occurred while adding job pairs for jobId: " +
                    jobId,
                e
            );
        } finally {
            Common.endTransaction(con);
            Common.safeClose(con);
        }
    }

    /**
     * Adds a job pair record to the database. This is a helper method for the
     * Jobs.add method
     *
     * @param con The connection the update will take place on
     * @return True if the operation was successful
     */
    protected static boolean addJobPairs(
        Connection con,
        int jobId,
        List<JobPair> pairs
    ) {
        final String methodName = "addJobPairs";
        log.entry(methodName);
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = con.prepareStatement(
                "SELECT AddJobPair(?, ?, ?, ?, ?, ?, ?)"
            );
            int pairsProcessed = 0;
            for (JobPair pair : pairs) {
                pair.setJobId(jobId);
                stmt.setInt(1, jobId);
                stmt.setInt(2, pair.getBench().getId());
                stmt.setShort(
                    3,
                    (short) StatusCode.STATUS_PENDING_SUBMIT.getVal()
                );

                stmt.setString(4, pair.getPath());
                stmt.setInt(5, pair.getJobSpaceId());

                stmt.setString(6, pair.getBench().getName());
                // The function will return the pair's new ID
                stmt.setInt(7, pair.getPrimaryStageNumber());
                rs = stmt.executeQuery();
                rs.next();

                // Update the pair's ID so it can be used outside this method
                int newPairId = rs.getInt(1);
                pair.setId(newPairId);
                pairsProcessed += 1;
                if (pairsProcessed % 1000 == 0) {
                    log.debug(methodName, "Pairs Processed: " + pairsProcessed);
                }
            }
            log.debug(methodName, "Pairs Processed: " + pairsProcessed);

            // A pair row without its stages is a pair that can never run, and one without
            // its inputs is a pair that runs against the wrong benchmarks. Both used to be
            // written, logged on failure, and then reported as success from here.
            log.debug(methodName, "Adding job pair stages.");
            if (!addJobPairStages(pairs, con)) {
                log.error(methodName, "Failed to add stages for the pairs of job " + jobId);
                return false;
            }
            log.debug(methodName, "Adding job pair inputs.");
            if (!addJobPairInputs(pairs, con)) {
                log.error(methodName, "Failed to add inputs for the pairs of job " + jobId);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error(
                methodName,
                "Exception occurred while adding job pairs to the database.",
                e
            );
        } finally {
            Common.safeClose(rs);
            Common.safeClose(stmt);
        }
        return false;
    }

    /**
     * Finds the standard output of a job pair and returns it as a string. Null is
     * returned if the output doesn't exist
     * or cannot be found
     *
     * @param pairId      The pair to get output for
     * @param stageNumber The stage to get pair info for
     * @param limit       The maximum number of lines to return
     * @return All console output from a job pair run for the given pair
     */
    public static Optional<String> getStdOut(
        int pairId,
        int stageNumber,
        int limit
    ) throws IOException {
        String stdoutPath = JobPairs.getStdout(pairId, stageNumber);
        return Util.readFileLimited(new File(stdoutPath), limit);
    }

    /**
     * Returns all pairs that are waiting on post processing. Returns a hashmap
     * mapping job pair IDs to post processors
     *
     * @return A list of triples containing pair id, stage number, post processor id
     *         that represents all stages that
     *         need to be processed.
     */
    public static List<PairStageProcessorTriple> getAllPairsForProcessing() {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetPairsToBeProcessed(?)"
            );
            ps.setInt(1, StatusCode.STATUS_PROCESSING.getVal());
            results = ps.executeQuery();
            List<PairStageProcessorTriple> list = new ArrayList<>();
            while (results.next()) {
                PairStageProcessorTriple next = new PairStageProcessorTriple();
                next.setPairId(results.getInt("id"));
                next.setStageNumber(results.getInt("stageNumber"));
                next.setProcessorId(results.getInt("post_processor"));
                list.add(next);
            }
            return list;
        } catch (Exception e) {
            log.error("getAllPairsForProcessing", e);
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
            Common.safeClose(results);
        }
        return null;
    }

    /**
     * Updates the total_pairs column for the given job by summing it with the given
     * increment
     *
     * @param jobId     The ID of the job to update
     * @param increment The amount to change total_pairs by. Note that if this is
     *                  negative it means the total_pairs
     *                  column will decrease
     * @param con       The open connection to make the call on
     * @return true on success and false otherwise
     */
    public static boolean incrementTotalJobPairsForJob(
        int jobId,
        int increment,
        Connection con
    ) {
        PreparedStatement ps = null;
        try {
            ps = con.prepareStatement(
                "SELECT starexec.IncrementTotalJobPairsForJob(?, ?)"
            );
            ps.setInt(1, jobId);
            ps.setInt(2, increment);
            boolean hasResultSet = ps.execute();
            if (hasResultSet) {
                ResultSet rs = ps.getResultSet();
                while (rs.next()) {
                    // consume the result set
                }
                Common.safeClose(rs);
            }
            return true;
        } catch (SQLException e) {
            if ("P0002".equals(e.getSQLState())) {
                log.warn("Job " + jobId + " not found");
            } else {
                log.error("incrementTotalJobPairsForJob", e.getMessage(), e);
            }
        } finally {
            Common.safeClose(ps);
        }
        return false;
    }

    /**
     * Deletes a list of job pairs. All pairs are expected to belong to the same job
     *
     * @param jobPairs the job pairs to delete.
     * @author Albert Giegerich
     */
    public static void deleteJobPairs(List<JobPair> jobPairs)
        throws SQLException {
        final String methodName = "deleteJobPairs";
        Connection con = null;

        try {
            con = Common.getConnection();
            Common.beginTransaction(con);
            log.debug("beginning to delete pairs");
            for (JobPair pair : jobPairs) {
                deleteJobPair(con, pair);
                log.debug("pair deleted");
            }
        } catch (SQLException e) {
            log.debug(methodName, "Caught an SQLException, database failed.");
            Common.doRollback(con);
            throw e;
        } finally {
            Common.endTransaction(con);
            Common.safeClose(con);
        }
    }

    // Deletes a given JobPair
    private static void deleteJobPair(Connection con, JobPair pairToDelete)
        throws SQLException {
        if (pairToDelete == null) {
            throw new NullPointerException("Input JobPair was null.");
        }

        PreparedStatement ps = null;
        try {
            ps = con.prepareStatement("SELECT starexec.DeleteJobPair(?)");
            ps.setInt(1, pairToDelete.getId());
            boolean hasResultSet = ps.execute();
            if (hasResultSet) {
                ResultSet rs = ps.getResultSet();
                while (rs.next()) {
                    // consume the result set
                }
                Common.safeClose(rs);
            }
        } finally {
            Common.safeClose(ps);
        }
    }

    /**
     * Post processes the given pair with the given processor ID, add the properties
     * to the pair attributes table, and
     * removes the pair from the processing job pairs table
     *
     * @param pairId      The ID of the pair to process
     * @param stageNumber
     * @param processorId The ID of the processor to use
     */
    public static void postProcessPair(
        int pairId,
        int stageNumber,
        int processorId
    ) {
        Connection con = null;
        try {
            Properties props = runPostProcessorOnPair(
                pairId,
                stageNumber,
                processorId
            );
            con = Common.getConnection();
            Common.beginTransaction(con);
            JobPairs.addJobPairAttributes(pairId, stageNumber, props, con);
            JobPairs.setPairStatus(
                pairId,
                StatusCode.STATUS_COMPLETE.getVal(),
                con
            );
            JobPairs.setPairStageStatus(
                pairId,
                StatusCode.STATUS_COMPLETE.getVal(),
                stageNumber,
                con
            );
            Common.endTransaction(con);
        } catch (Exception e) {
            Common.doRollback(con);
            log.error("postProcessPair", e);
        } finally {
            Common.endTransaction(con);
            Common.safeClose(con);
        }
    }

    /**
     * Runs the given post processor on the given pair stage and returns the
     * properties that were obtained
     *
     * @param pairId      The ID of the pair in question
     * @param processorId The ID of the processor in question
     * @return The properties on success, or null otherwise
     */
    private static Properties runPostProcessorOnPair(
        int pairId,
        int stageNumber,
        int processorId
    ) {
        try {
            JobPair pair = JobPairs.getPairDetailed(pairId);
            File output = new File(JobPairs.getFilePath(pair, stageNumber));
            Processor p = Processors.get(processorId);
            // Run the processor on the benchmark file
            List<File> files = new ArrayList<>();
            files.add(new File(p.getFilePath()));
            files.add(new File(pair.getBench().getPath()));
            files.add(output);
            File sandbox = Util.copyFilesToNewSandbox(files);
            String benchPath = new File(
                sandbox,
                new File(pair.getBench().getPath()).getName()
            ).getAbsolutePath();
            String outputPath = new File(
                sandbox,
                output.getName()
            ).getAbsolutePath();
            File working = new File(
                sandbox,
                new File(p.getFilePath()).getName()
            );

            String[] procCmd = new String[3];
            procCmd[0] = "./" + R.PROCESSOR_RUN_SCRIPT;

            procCmd[1] = outputPath;

            procCmd[2] = benchPath;
            String propstr = Util.executeSandboxCommand(procCmd, null, working);
            FileUtils.deleteQuietly(sandbox);

            // Load results into a properties file
            Properties prop = new Properties();
            prop.load(new StringReader(propstr));

            return prop;
        } catch (Exception e) {
            log.error("runPostProcessorOnPair", e);
        }
        return null;
    }

    /**
     * Adds a new attribute to a job pair
     *
     * @param con    The connection to make the update on
     * @param pairId The id of the job pair the attribute is for
     * @param key    The key of the attribute
     * @param val    The value of the attribute
     * @author Tyler Jensen
     */
    protected static void addJobPairAttr(
        Connection con,
        int pairId,
        int stageId,
        String key,
        String val
    ) {
        PreparedStatement ps = null;
        try {
            ps = con.prepareStatement("CALL starexec.AddJobAttr(?, ?, ?, ?)");
            ps.setInt(1, pairId);

            ps.setString(2, key);
            ps.setString(3, val);
            ps.setInt(4, stageId);
            boolean hasResultSet = ps.execute();
            if (hasResultSet) {
                ResultSet rs = ps.getResultSet();
                while (rs.next()) {
                    // consume the result set
                }
                Common.safeClose(rs);
            }
        } catch (Exception e) {
            log.error("addJobPairAttr", e);
        } finally {
            Common.safeClose(ps);
        }
    }

    /**
     * Adds the list of attributes to the given job pair. If old attributes have the
     * same keys as new ones, the old
     * ones
     * are replaced
     *
     * @param pairId     The ID of the pair to add attributes to
     * @param stageId    The ID of the stage to add attributes for.
     * @param attributes The key/value attributes
     * @param con        The open connection to make the call on
     * @return True on success, false on error
     */
    public static boolean addJobPairAttributes(
        int pairId,
        int stageId,
        Properties attributes,
        Connection con
    ) {
        try {
            // For each attribute (key, value)...
            log.info(
                "Adding " +
                    attributes.entrySet().size() +
                    " attributes to job pair " +
                    pairId
            );
            for (Entry<Object, Object> keyVal : attributes.entrySet()) {
                // Add the attribute to the database
                JobPairs.addJobPairAttr(
                    con,
                    pairId,
                    stageId,
                    (String) keyVal.getKey(),
                    (String) keyVal.getValue()
                );
            }

            return true;
        } catch (Exception e) {
            log.error("addJobPairAttributes", e);
        }
        return false;
    }

    /**
     * Adds a set of attributes to a job pair
     *
     * @param pairId     The id of the job pair the attribute is for
     * @param stageId    the ID of the stage to add attributes to
     * @param attributes The attributes to add to the job pair
     * @return True if the operation was a success, false otherwise
     * @author Tyler Jensen
     */
    public static boolean addJobPairAttributes(
        int pairId,
        int stageId,
        Properties attributes
    ) {
        Connection con = null;
        try {
            con = Common.getConnection();
            return addJobPairAttributes(pairId, stageId, attributes, con);
        } catch (Exception e) {
            log.error("error adding Job Attributes = " + e.getMessage(), e);
        } finally {
            Common.safeClose(con);
        }

        return false;
    }

    /**
     * Filters job pairs based on their status codes
     *
     * @param pairs
     * @param type
     * @return
     */
    protected static List<JobPair> filterPairsByType(
        List<JobPair> pairs,
        String type,
        int stageNumber
    ) {
        log.debug("filtering pairs by type with type = " + type);
        List<JobPair> filteredPairs = new ArrayList<>();

        switch (type) {
            case "incomplete":
                for (JobPair jp : pairs) {
                    if (
                        jp
                            .getStageFromNumber(stageNumber)
                            .getStatus()
                            .getCode()
                            .statIncomplete()
                    ) {
                        filteredPairs.add(jp);
                    }
                }
                break;
            case "resource":
                for (JobPair jp : pairs) {
                    if (
                        jp
                            .getStageFromNumber(stageNumber)
                            .getStatus()
                            .getCode()
                            .resource()
                    ) {
                        filteredPairs.add(jp);
                    }
                }
                break;
            case "failed":
                for (JobPair jp : pairs) {
                    if (
                        jp
                            .getStageFromNumber(stageNumber)
                            .getStatus()
                            .getCode()
                            .failed()
                    ) {
                        filteredPairs.add(jp);
                    }
                }
                break;
            case "solved":
                for (JobPair jp : pairs) {
                    JoblineStage stage = jp.getStageFromNumber(stageNumber);
                    if (JobPairs.isPairCorrect(stage) == 0) {
                        filteredPairs.add(jp);
                    }
                }
                break;
            case "wrong":
                for (JobPair jp : pairs) {
                    JoblineStage stage = jp.getStageFromNumber(stageNumber);
                    if (JobPairs.isPairCorrect(stage) == 1) {
                        filteredPairs.add(jp);
                    }
                }
                break;
            case "unknown":
                for (JobPair jp : pairs) {
                    JoblineStage stage = jp.getStageFromNumber(stageNumber);
                    if (JobPairs.isPairCorrect(stage) == 2) {
                        filteredPairs.add(jp);
                    }
                }
                break;
            case "complete":
                for (JobPair jp : pairs) {
                    if (
                        jp
                            .getStageFromNumber(stageNumber)
                            .getStatus()
                            .getCode()
                            .statComplete()
                    ) {
                        filteredPairs.add(jp);
                    }
                }
                break;
            default:
                filteredPairs = pairs;
                break;
        }
        return filteredPairs;
    }

    /**
     * Checks whether a given stage is correct
     *
     * @param stage
     * @return -1 == pair is not complete (as in, does not have STATUS_COMPLETE) 0
     *         == pair is correct 1 == pair is
     *         incorrect 2 == pair is unknown
     */
    public static int isPairCorrect(JoblineStage stage) {
        StatusCode statusCode = stage.getStatus().getCode();

        if (statusCode.getVal() == StatusCode.STATUS_COMPLETE.getVal()) {
            if (stage.getAttributes() != null) {
                Properties attrs = stage.getAttributes();
                // log.debug("expected = "+attrs.get(R.EXPECTED_RESULT));
                // log.debug("actual = "+attrs.get(R.STAREXEC_RESULT));
                if (
                    attrs.containsKey(R.STAREXEC_RESULT) &&
                    attrs.get(R.STAREXEC_RESULT).equals(R.STAREXEC_UNKNOWN)
                ) {
                    // don't know the result, so don't mark as correct or incorrect.
                    return 2;
                } else if (
                    attrs.containsKey(R.EXPECTED_RESULT) &&
                    !attrs.get(R.EXPECTED_RESULT).equals(R.STAREXEC_UNKNOWN)
                ) {
                    if (
                        !attrs.containsKey(R.STAREXEC_RESULT) ||
                        !attrs
                            .get(R.STAREXEC_RESULT)
                            .equals(attrs.get(R.EXPECTED_RESULT))
                    ) {
                        // the absence of a result, or a nonmatching result, is counted as wrong
                        return 1;
                    } else {
                        return 0;
                    }
                } else {
                    // if the attributes don't have an expected result, we will mark as unknown
                    return 2;
                }
            } else {
                return 0;
            }
        } else {
            return -1;
        }
    }

    /**
     * Filters a list of solver comparisons against a given query
     *
     * @param comparisons
     * @param searchQuery
     * @return
     */
    protected static List<SolverComparison> filterComparisons(
        List<SolverComparison> comparisons,
        String searchQuery
    ) {
        // no filtering is necessary if there's no query
        if (Util.isNullOrEmpty(searchQuery)) {
            return comparisons;
        }

        searchQuery = searchQuery.toLowerCase();
        List<SolverComparison> filteredComparisons = new ArrayList<>();
        for (SolverComparison c : comparisons) {
            try {
                if (
                    c
                        .getBenchmark()
                        .getName()
                        .toLowerCase()
                        .contains(searchQuery)
                ) {
                    filteredComparisons.add(c);
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }

        return filteredComparisons;
    }

    /**
     * Filters a list of job pairs against some search query. The query is compared
     * to solver, benchmark, and config
     * names, as well as integer status code and result. The job pair is not
     * filtered if the query is a
     * case-insensitive
     * substring of any of those names
     *
     * @param pairs       The pairs to filter
     * @param searchQuery The query
     * @return A filtered list of job pairs
     * @author Eric Burns
     */

    protected static List<JobPair> filterPairs(
        List<JobPair> pairs,
        String searchQuery,
        int stageNumber
    ) {
        // no filtering is necessary if there's no query
        if (Util.isNullOrEmpty(searchQuery)) {
            return pairs;
        }

        searchQuery = searchQuery.toLowerCase();
        List<JobPair> filteredPairs = new ArrayList<>();
        for (JobPair jp : pairs) {
            JoblineStage stage = jp.getStageFromNumber(stageNumber);
            try {
                if (
                    jp
                        .getBench()
                        .getName()
                        .toLowerCase()
                        .contains(searchQuery) ||
                    String.valueOf(stage.getStatus().getCode().getVal()).equals(
                        searchQuery
                    ) ||
                    stage
                        .getSolver()
                        .getName()
                        .toLowerCase()
                        .contains(searchQuery) ||
                    stage
                        .getConfiguration()
                        .getName()
                        .toLowerCase()
                        .contains(searchQuery) ||
                    stage.getStarexecResult().contains(searchQuery)
                ) {
                    filteredPairs.add(jp);
                }
            } catch (Exception e) {
                log.warn("filterPairs", "JobPair: " + jp.getId(), e);
            }
        }

        return filteredPairs;
    }

    /**
     * Retrieves all attributes (key/value) of the given job pair. Returns a mapping
     * of those attributes to stages
     * based
     * on the jobpair_stage_data.stage_number
     *
     * @param con    The connection to make the query on
     * @param pairId The id of the pair to get the attributes of
     * @return The properties object which holds all the pair's attributes
     * @author Tyler Jensen
     */
    protected static HashMap<Integer, Properties> getAttributes(
        Connection con,
        int pairId
    ) {
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            HashMap<Integer, Properties> props = new HashMap<>();
            ps = con.prepareStatement("SELECT * FROM starexec.GetPairAttrs(?)");
            ps.setInt(1, pairId);
            results = ps.executeQuery();

            while (results.next()) {
                int joblineStageNumber = results.getInt("stage_number");
                if (!props.containsKey(joblineStageNumber)) {
                    props.put(joblineStageNumber, new Properties());
                }
                props
                    .get(joblineStageNumber)
                    .put(
                        results.getString("attr_key"),
                        results.getString("attr_value")
                    );
            }

            return props;
        } catch (Exception e) {
            log.error("getAttributes", e);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
        }
        return null;
    }

    /**
     * Retrieves all attributes (key/value) of the given job pair
     *
     * @param pairId The id of the pair to get the attributes of
     * @return The properties object which holds all the pair's attributes
     * @author Tyler Jensen
     */
    public static HashMap<Integer, Properties> getAttributes(int pairId) {
        Connection con = null;
        log.debug("Calling JobPairs.getAttributes for an individual pair");
        try {
            con = Common.getConnection();
            return getAttributes(con, pairId);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            Common.safeClose(con);
        }

        return null;
    }

    /**
     * Gets the path to the output file for this pair.
     *
     * @param pairId The id of the pair to get the filepath for
     * @return The string path, or null on failure
     * @author Eric Burns
     */

    public static String getLogPath(int pairId) {
        return getLogFilePath(getFilePathInfo(pairId));
    }

    /**
     * Populates a job pair with just enough information to find the file path. The
     * pair will be returned with a single
     * primary stage set with a solver name and config name
     *
     * @param pairId
     * @return
     */
    private static JobPair getFilePathInfo(int pairId) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.getJobPairFilePathInfo(?)"
            );
            ps.setInt(1, pairId);
            results = ps.executeQuery();
            if (results.next()) {
                JobPair pair = new JobPair();

                pair.addStage(new JoblineStage());

                pair.setPrimaryStageNumber(results.getInt("stage_number"));
                pair
                    .getStages()
                    .get(0)
                    .setStageNumber(pair.getPrimaryStageNumber());
                Solver s = pair.getPrimarySolver();
                s.setName(results.getString("solver_name"));
                Benchmark b = pair.getBench();
                b.setName(results.getString("bench_name"));
                Configuration c = pair.getPrimaryConfiguration();
                c.setName(results.getString("config_name"));
                pair.setJobId(results.getInt("job_id"));
                pair.setPath(results.getString("path"));
                pair.setJobSpaceId(results.getInt("job_space_id"));

                pair.setId(pairId);
                return pair;
            }
        } catch (Exception e) {
            log.debug("getFilePathInfo", e);
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
            Common.safeClose(results);
        }
        return null;
    }

    /**
     * Gets the path to the directory containing all output files for this job. For
     * jobs created before solver
     * pipelines, returns the single output file for the job
     *
     * @param pairId The id of the pair to get the filepath for
     * @return The string path, or null on failure
     * @author Eric Burns
     */

    public static String getStdout(int pairId) {
        return getPairStdout(getFilePathInfo(pairId));
    }

    /**
     * Returns a list of files representing paths to both a pair's standard output
     * and additional output directories
     * . If
     * the given file is a directory, it is returned alone. Otherwise, it is
     * interpreted as the single stdout file and
     * the additional directory is returned as well if it exists.
     *
     * @param pairId
     * @param stdout
     * @return
     */
    private static List<File> getOutputPathsFromStdout(
        int pairId,
        File stdout
    ) {
        List<File> files = new ArrayList<>();
        files.add(stdout);
        // if we need the other directory
        if (!stdout.isDirectory()) {
            File otherOutput = new File(
                stdout.getParentFile(),
                pairId + "_output"
            );
            if (otherOutput.exists()) {
                files.add(otherOutput);
            }
        }
        return files;
    }

    /**
     * Returns a list of files representing paths to both a pair's standard output
     * and additional output directories
     * . If
     * these two things are contained in a single top level directory, as they are
     * when joblines are used, only that
     * directory is returned. No extra output dir is returned if extra outputs are
     * not used.
     *
     * @param pairId The pair to get output for
     * @return Paths to all output for this job. has no output yet
     */
    public static List<File> getOutputPaths(int pairId) {
        File stdout = new File(getStdout(pairId));
        return getOutputPathsFromStdout(pairId, stdout);
    }

    /**
     * Same as getOutputPaths(pairId), except the given pair is expected to have all
     * relevant fields populated and will
     * not need to be retrieved from the database
     *
     * @param pair
     * @return See getOutputPaths(pairId)
     */
    public static List<File> getOutputPaths(JobPair pair) {
        File stdout = new File(getPairStdout(pair));
        return getOutputPathsFromStdout(pair.getId(), stdout);
    }

    /**
     * Gets the path to the output file for the given job pair and stage
     *
     * @param pairId
     * @param stageNumber
     * @return The absolute file path to the output for the given stage of the given
     *         pair
     */

    public static String getStdout(int pairId, int stageNumber) {
        return getFilePath(getFilePathInfo(pairId), stageNumber);
    }

    /**
     * Removes a specific pair from the job_pair_completion table
     *
     * @param pairId The ID of the pair being removed
     */

    public static void removePairFromCompletedTable(int pairId) {
        Connection con = null;
        PreparedStatement ps = null;

        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT starexec.RemovePairFromCompletedTable(?)"
            );
            ps.setInt(1, pairId);
            boolean hasResultSet = ps.execute();
            if (hasResultSet) {
                ResultSet rs = ps.getResultSet();
                while (rs.next()) {
                    // consume the result set
                }
                Common.safeClose(rs);
            }
        } catch (SQLException e) {
            // Check if this is a "not found" error (SQLSTATE P0002)
            if ("P0002".equals(e.getSQLState())) {
                log.debug(
                    "Completion record for pair " +
                        pairId +
                        " not found (job may still be running or was never marked complete). " +
                        "This is expected if the pair is still executing. Continuing anyway."
                );
                // This is not an error - the pair might still be executing, or completion wasn't tracked
                return;
            }
            // Other SQL errors should be logged as warnings
            log.warn(
                "SQL error removing pair " +
                    pairId +
                    " from completion table: " +
                    e.getMessage() +
                    " (SQLSTATE: " +
                    e.getSQLState() +
                    ")",
                e
            );
        } catch (Exception e) {
            log.error(
                "Unexpected error removing pair " +
                    pairId +
                    " from completion table: " +
                    e.getMessage(),
                e
            );
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
        }
    }

    /**
     * Returns the log of a job pair by reading in the physical log file into a
     * string.
     *
     * @param pairId The id of the pair to get the log for
     * @return The log of the job run
     */
    public static String getJobLog(int pairId) {
        try {
            String logPath = JobPairs.getLogPath(pairId);

            File logFile = new File(logPath);

            if (logFile.exists()) {
                return FileUtils.readFileToString(
                    logFile,
                    StandardCharsets.UTF_8
                );
            }
        } catch (Exception e) {
            log.warn(e.getMessage(), e);
        }

        return null;
    }

    /**
     * Returns the absolute path to where the log for a pair is stored given the
     * pair.
     *
     * @param pair
     * @return The absolute path to the log file for the given pair, or null if it
     *         could not be found
     */
    public static String getLogFilePath(JobPair pair) {
        try {
            File file = new File(Jobs.getLogDirectory(pair.getJobId()));
            file = new File(file, String.valueOf(pair.getJobSpaceId()));
            // Add pair-specific subdirectory to prevent concurrent pair registration conflicts
            // This ensures each pair from the same job has a unique parent directory
            // for LocalJobMonitor tracking (prevents one-to-one map overwrite issues)
            //
            // SECURITY NOTE: pair.getId() returns a primitive int (from Identifiable base class)
            // which is a database-assigned primary key. This is inherently safe from path
            // injection attacks as it cannot contain path traversal characters like "../".
            // No additional sanitization is required.
            file = new File(file, "pair_" + pair.getId());
            file = new File(file, pair.getId() + ".txt");
            log.trace("found this log path " + file.getAbsolutePath());
            return file.getAbsolutePath();
        } catch (Exception e) {
            log.error("getLogFilePath", e);
        }
        return null;
    }

    /**
     * Retrieves the output of a single stage of the given job pair. Requires that
     * the jobId, path, solver name, config
     * name, and bench names of the PRIMARY STAGE be populated. The fields do NOT
     * need to be populated for given stage,
     * ONLY the primary stage
     *
     * @param pair
     * @param stageNumber A number >=1 representing the stage of this pair
     * @return The absolute file path to the output file for the given stage of the
     *         given pair
     */
    public static String getFilePath(JobPair pair, int stageNumber) {
        String path = getPairStdout(pair); // this is the path to the top level directory of the pair

        File f = new File(path);
        if (f.isDirectory()) {
            // means this is a job created after stages were implemented
            return new File(f, stageNumber + ".txt").getAbsolutePath();
        } 
        
        // Let's see if the path already ends with .txt (e.g. getPairStdout found the single-stage file)
        if (path.endsWith(".txt")) {
            return path;
        }
        
        // If it's not a directory and doesn't end with .txt, it's the fallback base name.
        // We need to assume it's a single-stage file that hasn't been created yet.
        return path + ".txt";
    }

    /**
     * Gets the path to the directory that contains all the output files for every
     * stage in this pair. For old pairs
     * that do not have stages, simply returns the path to the single output file
     * for this pair. Requires that the
     * jobId, path, solver name, config name, and bench names be populated for the
     * PRIMARY STAGES
     *
     * @param pair The pair to get the filepath for
     * @return The string path, or null on failure
     * @author Eric Burns
     */

    // Note that this function tries several things due to supporting several layers
    // of backwards compatibility
    public static String getPairStdout(JobPair pair) {
        try {
            if (pair == null || pair.getBenchPath() == null) {
                log.warn("getPairStdout: pair or benchPath is null");
                return null;
            }
            final String path = Util.normalizeFilePath(pair.getBenchPath());
            String jobDir = Jobs.getDirectory(pair.getJobId());
            if (jobDir == null) {
                log.warn(
                    "getPairStdout: jobDir is null for jobId " + pair.getJobId()
                );
                return null;
            }
            File file = new File(jobDir, path);

            if (!file.exists()) {
                // if the job output could not be found
                if (
                    pair.getPrimarySolver() == null ||
                    pair.getPrimaryConfiguration() == null ||
                    pair.getBench() == null
                ) {
                    log.warn(
                        "getPairStdout: solver, config, or bench is null for pair " +
                            pair.getId()
                    );
                    return null;
                }
                // Also check that the names are not null before creating File objects
                String solverName = pair.getPrimarySolver().getName();
                String configName = pair.getPrimaryConfiguration().getName();
                String benchName = pair.getBench().getName();
                if (
                    solverName == null ||
                    configName == null ||
                    benchName == null
                ) {
                    log.warn(
                        "getPairStdout: solverName, configName, or benchName is null. solver=" +
                            solverName +
                            ", config=" +
                            configName +
                            ", bench=" +
                            benchName
                    );
                    return null;
                }
                File testFile = new File(file, solverName);
                testFile = new File(testFile, configName);
                testFile = new File(testFile, benchName);
                if (testFile.exists()) {
                    // check the alternate path some pairs are still stored at
                    FileUtils.copyFile(testFile, file);
                    if (file.exists()) {
                        testFile.delete();
                    }
                }
            } else if (file.isFile()) {
                // if it is a file, we have already got the full path
                return file.getAbsolutePath();
            }

            // before solver pipelines, pairs were stored as a single file titled
            // <pairid>.txt . If that file exists,
            // returns it
            File testFile = new File(file, pair.getId() + ".txt");

            if (testFile.exists()) {
                return testFile.getAbsolutePath();
            }

            // otherwise, this is a modern job, and we return a directory with the name of
            // the pair id

            file = new File(file, String.valueOf(pair.getId()));

            return file.getAbsolutePath();
        } catch (Exception e) {
            log.error("getPairStdout", e);
        }
        return null;
    }

    /**
     * Gets the job pair with the given id non-recursively (Worker node, status,
     * benchmark and solver will NOT be
     * populated). Only the primary stage is created! To get all the stages, you
     * need to call getPairDetailed
     *
     * @param pairId The id of the pair to get
     * @return The job pair object with the given id.
     * @author Tyler Jensen
     */
    public static JobPair getPair(int pairId) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetJobPairById(?)"
            );
            ps.setInt(1, pairId);
            results = ps.executeQuery();

            if (results.next()) {
                JobPair jp = JobPairs.resultToPair(results);
                jp.addStage(new JoblineStage()); // just add an empty stage that we can populate below
                jp
                    .getStages()
                    .get(0)
                    .setStageNumber(jp.getPrimaryStageNumber());

                jp.getNode().setId(results.getInt("node_id"));
                jp.getStatus().setCode(results.getInt("status_code"));
                jp.getBench().setId(results.getInt("bench_id"));
                jp.getBench().setName(results.getString("bench_name"));
                jp
                    .getPrimarySolver()
                    .getConfigurations()
                    .add(new Configuration(results.getInt("config_id")));
                jp.getPrimarySolver().setId(results.getInt("solver_id"));
                jp.getPrimarySolver().setName(results.getString("solver_name"));
                jp
                    .getPrimarySolver()
                    .getConfigurations()
                    .get(0)
                    .setName(results.getString("config_name"));
                if (
                    !jp.getStages().isEmpty() &&
                    !jp.getPrimarySolver().getConfigurations().isEmpty()
                ) {
                    jp
                        .getStages()
                        .get(0)
                        .setConfiguration(
                            jp.getPrimarySolver().getConfigurations().get(0)
                        );
                }
                return jp;
            } else {
                log.warn("getPair", "Pair not found: " + pairId);
            }
            Common.safeClose(results);
        } catch (Exception e) {
            log.error("getPair", e);
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
            Common.safeClose(results);
        }

        return null;
    }

    /**
     * Gets the job pair with the given id recursively (Worker node, status,
     * benchmark and solver WILL be populated)
     *
     * @param con    The connection to make the query on
     * @param pairId The id of the pair to get
     * @return The job pair object with the given id.
     * @author Tyler Jensen
     */
    protected static JobPair getPairDetailed(Connection con, int pairId) {
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetJobPairById(?)"
            );
            ps.setInt(1, pairId);
            results = ps.executeQuery();

            JobPair jp = null;
            // first, we get the top level info from the job_pairs table
            if (results.next()) {
                jp = JobPairs.resultToPair(results);
                jp.setCompletionId(results.getInt("completion_id"));
                jp.setNode(
                    Cluster.getNodeDetails(con, results.getInt("node_id"))
                );
                jp.setBench(
                    Benchmarks.get(con, results.getInt("bench_id"), true)
                );

                Status s = new Status();
                s.setCode(results.getInt("status_code"));
                jp.setStatus(s);
                jp.setJobSpaceName(results.getString("job_space_name"));
            } else {
                // couldn't find the pair for some reason
                return null;
            }

            populateJobPairStagesDetailed(jp, con);

            return jp;
        } catch (Exception e) {
            log.error("getPairDetailed", e);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
        }

        return null;
    }

    private static void populateJobPairStagesDetailed(
        JobPair jp,
        Connection con
    ) throws SQLException {
        PreparedStatement ps = null;
        ResultSet results = null;

        try {
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetJobPairStagesById(?)"
            );
            ps.setInt(1, jp.getId());
            results = ps.executeQuery();
            // next, we get data at the stage level
            while (results.next()) {
                JoblineStage stage = resultToStage(results);
                int configId = results.getInt("config_id");
                int solverId = results.getInt("solver_id");
                String configName = results.getString("config_name");
                String solverName = results.getString("solver_name");
                // means this stage has no configuration
                if (configId == -1) {
                    stage.setNoOp(true);
                } else if (configId > 0) {
                    Solver solver = Solvers.getSolverByConfig(
                        con,
                        configId,
                        true
                    );
                    Configuration c = Solvers.getConfiguration(configId);

                    // this can happen if the pair references a deleted solver
                    if (solver == null) {
                        solver = new Solver();
                        solver.setId(solverId);
                        solver.setName(solverName);
                    }

                    if (c == null) {
                        c = new Configuration();
                        c.setId(configId);
                        c.setName(configName);
                    }

                    stage.setSolver(solver);
                    stage.setConfiguration(c);
                    stage.getSolver().addConfiguration(c);
                }
                jp.addStage(stage);
            }
            // last, we get attributes for everything
            HashMap<Integer, Properties> attrs = getAttributes(jp.getId());
            for (JoblineStage stage : jp.getStages()) {
                if (attrs.containsKey(stage.getStageNumber())) {
                    stage.setAttributes(attrs.get(stage.getStageNumber()));
                }
            }
        } finally {
            Common.safeClose(ps);
            Common.safeClose(results);
        }
    }

    /**
     * Gets the job pair with the given id recursively (Worker node, status,
     * benchmark and solver WILL be populated)
     *
     * @param pairId The id of the pair to get
     * @return The job pair object with the given id.
     * @author Tyler Jensen
     */
    public static JobPair getPairDetailed(int pairId) {
        Connection con = null;

        try {
            con = Common.getConnection();
            return getPairDetailed(con, pairId);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            Common.safeClose(con);
        }

        return null;
    }

    public static List<JobPair> getPairsInJobContainingBenchmark(
        int jobId,
        int benchmarkId
    ) throws SQLException {
        java.sql.Connection con = null;
        java.sql.PreparedStatement ps = null;
        java.sql.ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetJobPairsInJobContainingBenchmark(?, ?)"
            );
            ps.setInt(1, jobId);
            ps.setInt(2, benchmarkId);
            results = ps.executeQuery();
            List<JobPair> jobPairs = new ArrayList<>();
            while (results.next()) {
                JobPair pair = resultToPair(results);
                populateJobPairStagesDetailed(pair, con);
                jobPairs.add(pair);
            }
            return jobPairs;
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    public static List<JobPair> getPairsInJobContainingSolver(
        int jobId,
        int solverId
    ) throws SQLException {
        java.sql.Connection con = null;
        java.sql.PreparedStatement ps = null;
        java.sql.ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetJobPairsInJobContainingSolver(?, ?)"
            );
            ps.setInt(1, jobId);
            ps.setInt(2, solverId);
            results = ps.executeQuery();
            List<JobPair> jobPairs = new ArrayList<>();
            while (results.next()) {
                JobPair pairFromResults = resultToPair(results);
                populateJobPairStagesDetailed(pairFromResults, con);
                jobPairs.add(pairFromResults);
            }
            return jobPairs;
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    /**
     * Extracts query information into a JoblineStage. Does NOT get deep information
     * like solver and configuration
     *
     * @param result
     * @return
     * @throws Exception
     */
    protected static JoblineStage resultToStage(ResultSet result)
        throws SQLException {
        JoblineStage stage = new JoblineStage();

        stage.setStageNumber(
            getIntSafe(
                result,
                "jobpair_stage_data.stage_number",
                "stage_number"
            )
        );
        stage.setWallclockTime(
            getDoubleSafe(result, "jobpair_stage_data.wallclock", "wallclock")
        );
        stage.setCpuUsage(
            getDoubleSafe(result, "jobpair_stage_data.cpu", "cpu")
        );
        stage.setUserTime(
            getDoubleSafe(result, "jobpair_stage_data.user_time", "user_time")
        );
        stage.setSystemTime(
            getDoubleSafe(
                result,
                "jobpair_stage_data.system_time",
                "system_time"
            )
        );
        stage.setMaxVirtualMemory(
            getDoubleSafe(result, "jobpair_stage_data.max_vmem", "max_vmem")
        );
        stage.setMaxResidenceSetSize(
            getDoubleSafe(
                result,
                "jobpair_stage_data.max_res_set",
                "max_res_set"
            )
        );
        stage.setStageId(
            getIntSafe(result, "jobpair_stage_data.stage_id", "stage_id")
        );
        stage
            .getStatus()
            .setCode(
                getIntSafe(
                    result,
                    "jobpair_stage_data.status_code",
                    "status_code"
                )
            );
        return stage;
    }

    /**
     * Helper method to extract information from a query for job pairs
     *
     * @param result The resultset that is the results from querying for job pairs
     * @return A job pair object populated with data from the result set
     */
    protected static JobPair resultToPair(ResultSet result)
        throws SQLException {
        JobPair jp = new JobPair();

        jp.setId(getIntSafe(result, "job_pairs.id", "id"));
        jp.setJobId(getIntSafe(result, "job_pairs.job_id", "job_id"));
        jp.setBackendExecId(getIntSafe(result, "job_pairs.sge_id", "sge_id"));
        jp.setQueueSubmitTime(
            getTimestampSafe(result, "job_pairs.queuesub_time", "queuesub_time")
        );
        jp.setStartTime(
            getTimestampSafe(result, "job_pairs.start_time", "start_time")
        );
        jp.setEndTime(
            getTimestampSafe(result, "job_pairs.end_time", "end_time")
        );
        // Populate basic benchmark info.
        jp.getBench().setId(getIntSafe(result, "bench_id", "bench_id"));
        jp
            .getBench()
            .setName(getStringSafe(result, "bench_name", "bench_name"));

        jp.getNode().setId(getIntSafe(result, "job_pairs.node_id", "node_id"));
        jp
            .getStatus()
            .setCode(
                getIntSafe(result, "job_pairs.status_code", "status_code")
            );

        jp.setPath(getStringSafe(result, "job_pairs.path", "path"));
        jp.setJobSpaceId(
            getIntSafe(result, "job_pairs.job_space_id", "job_space_id")
        );
        jp.setPrimaryStageNumber(
            getIntSafe(
                result,
                "job_pairs.primary_jobpair_data",
                "primary_jobpair_data"
            )
        );
        jp.setSandboxNum(
            getIntSafe(result, "job_pairs.sandbox_num", "sandbox_num")
        );
        // log.debug("getting job pair from result set for id " + jp.getId());
        return jp;
    }

    /**
     * Helper getters that try multiple column labels (prefixed and unprefixed) and
     * do safe conversions.
     */
    private static boolean columnExists(ResultSet rs, String col)
        throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        for (int i = 1; i <= cols; i++) {
            if (
                col.equalsIgnoreCase(md.getColumnLabel(i)) ||
                col.equalsIgnoreCase(md.getColumnName(i))
            ) return true;
        }
        return false;
    }

    private static int getIntSafe(ResultSet rs, String... cols)
        throws SQLException {
        for (String c : cols) {
            if (columnExists(rs, c)) return rs.getInt(c);
        }
        throw new SQLException(
            "None of the columns found: " + Arrays.toString(cols)
        );
    }

    private static long getLongSafe(ResultSet rs, String... cols)
        throws SQLException {
        for (String c : cols) {
            if (columnExists(rs, c)) return rs.getLong(c);
        }
        throw new SQLException(
            "None of the columns found: " + Arrays.toString(cols)
        );
    }

    private static String getStringSafe(ResultSet rs, String... cols)
        throws SQLException {
        for (String c : cols) {
            if (columnExists(rs, c)) return rs.getString(c);
        }
        return null;
    }

    private static Timestamp getTimestampSafe(ResultSet rs, String... cols)
        throws SQLException {
        for (String c : cols) {
            if (columnExists(rs, c)) return rs.getTimestamp(c);
        }
        return null;
    }

    private static double getDoubleSafe(ResultSet rs, String... cols)
        throws SQLException {
        for (String c : cols) {
            if (columnExists(rs, c)) {
                try {
                    return rs.getDouble(c);
                } catch (SQLException e) {
                    Object o = rs.getObject(c);
                    if (o instanceof Number) return ((Number) o).doubleValue();
                    throw e;
                }
            }
        }
        throw new SQLException(
            "None of the columns found: " + Arrays.toString(cols)
        );
    }

    /**
     * Sets the status of a given job pair stage to the given status
     *
     * @param pairId      The ID of the pair to update
     * @param stageNumber The number of the stage to update
     * @param statusCode  The code to give the stage
     * @param con         An open database connection to make the call on
     * @return True on success and false on error
     */
    public static boolean setPairStageStatus(
        int pairId,
        int statusCode,
        int stageNumber,
        Connection con
    ) {
        PreparedStatement ps = null;
        try {
            ps = con.prepareStatement(
                "CALL starexec.UpdatePairStageStatus(?, ?, ?)"
            );
            ps.setInt(1, pairId);
            ps.setInt(2, stageNumber);
            ps.setShort(3, (short) statusCode);

            ps.execute();
            try {
                Common.safeClose(ps.getResultSet());
            } catch (SQLException ignore) {}

            return true;
        } catch (Exception e) {
            log.debug("setPairStageStatus", e);
        } finally {
            Common.safeClose(ps);
        }
        return false;
    }

    /**
     * Records statuses a monitor reconstructed for stages of a pair that finished
     * earlier, without moving any stage that already carries a result.
     *
     * <p>Separate from {@link #setPairStageStatus} because the writers differ. That one is
     * called by the job script, which is the only writer for its own pair and reports each
     * stage exactly once and in order, so an unconditional write is correct there. This one
     * is called by {@code ContainerJobMonitor} from files a finished container left behind,
     * and the same files can be read more than once -- after a partial write, a duplicate
     * completion event, or an application restart. It therefore goes through
     * {@code UpdatePairStageStatusIfUnresolved}, which refuses to overwrite a result rather
     * than moving a completed stage back to RUNNING.
     *
     * <p>One transaction for the whole set. A stage number that does not belong to this
     * pair raises {@code P0002} and rolls the batch back, so rejected input leaves no
     * partial write behind for the caller to reason about.
     *
     * <p>A stage the database refuses is logged, not failed: it means some other writer
     * recorded a different terminal result for it first, which is the same situation
     * {@link PairStatusResult#SUPERSEDED} describes at the pair level.
     *
     * @param pairId        the pair every stage belongs to
     * @param stageStatuses stage number to status code; an empty map is a no-op success
     * @return true when every stage holds a recorded result, false when nothing was written
     */
    public static boolean setEarlierStageStatuses(
        int pairId,
        Map<Integer, Integer> stageStatuses
    ) {
        if (stageStatuses.isEmpty()) {
            return true;
        }
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);
            ps = con.prepareStatement(
                "SELECT starexec.UpdatePairStageStatusIfUnresolved(?, ?, ?)"
            );
            List<Integer> refused = new ArrayList<>();
            for (Entry<Integer, Integer> stage : stageStatuses.entrySet()) {
                ps.setInt(1, pairId);
                ps.setInt(2, stage.getKey());
                ps.setInt(3, stage.getValue());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!(rs.next() && rs.getBoolean(1))) {
                        refused.add(stage.getKey());
                    }
                }
            }
            con.commit();
            Common.enableAutoCommit(con);
            if (!refused.isEmpty()) {
                log.info(
                    "Pair " + pairId + ": stages " + refused +
                        " already carried a different result and were left alone"
                );
            }
            return true;
        } catch (Exception e) {
            log.error(
                "Could not record earlier stage statuses " + stageStatuses +
                    " for pair " + pairId,
                e
            );
            Common.doRollback(con);
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return false;
    }

    /**
     * Sets the status code of every stage that comes after the given stage to the
     * given value
     *
     * @param pairId
     * @param statusCode
     * @param stageNumber
     * @param con
     * @return True on success and false otherwise
     */
    public static boolean setLaterPairStageStatus(
        int pairId,
        int statusCode,
        int stageNumber,
        Connection con
    ) {
        PreparedStatement ps = null;
        try {
            ps = con.prepareStatement(
                "CALL starexec.UpdateLaterStageStatuses(?, ?, ?)"
            );
            ps.setInt(1, pairId);
            ps.setInt(2, stageNumber);
            ps.setShort(3, (short) statusCode);

            ps.execute();
            return true;
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
        } finally {
            Common.safeClose(ps);
        }
        return false;
    }

    /**
     * Sets the status code of every stage that comes after the given stage to the
     * given value
     *
     * @param pairId
     * @param statusCode
     * @param stageNumber
     * @return True on success and false on error
     */

    public static boolean setLaterPairStageStatus(
        int pairId,
        int statusCode,
        int stageNumber
    ) {
        Connection con = null;
        try {
            con = Common.getConnection();

            return setLaterPairStageStatus(
                pairId,
                statusCode,
                stageNumber,
                con
            );
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            Common.safeClose(con);
        }
        return false;
    }

    /**
     * Sets the status code of every stage for the given pair to the given code
     *
     * @param pairId
     * @param statusCode
     * @return True on success and false otherwise
     */
    public static boolean setAllPairStageStatus(int pairId, int statusCode) {
        return setLaterPairStageStatus(pairId, statusCode, -1);
    }

    /**
     * Assigns a given status code to a job pair and all of its stages
     *
     * @param pairId
     * @param statusCode
     * @return True on success and false otherwise
     */
    public static boolean setStatusForPairAndStages(
        int pairId,
        int statusCode
    ) {
        Connection con = null;
        boolean success = false;
        Integer attemptNoForFinalize = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);
            success = (
                setPairStatus(pairId, statusCode, con) &&
                setAllPairStageStatus(pairId, statusCode, con)
            );
            if (!success) {
                Common.doRollback(con);
                return false;
            }
            if (success && isTerminalStatusCode(statusCode)) {
                attemptNoForFinalize = getOrCreateCurrentAttemptNo(con, pairId, true);
            }
            Common.endTransaction(con);
            return success;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            Common.doRollback(con);
        } finally {
            Common.safeClose(con);
            if (success && isTerminalStatusCode(statusCode) && attemptNoForFinalize != null) {
                finalizePairManifest(pairId, attemptNoForFinalize, statusCode);
            }
        }
        return false;
    }

    /**
     * Atomically updates a job pair's terminal stage status and marks all later
     * stages as STATUS_NOT_REACHED, with full transactional safety.
     *
     * <p>
     * Replaces the non-atomic {@link #setStatusForPairAndStages} double-call
     * pattern in the container execution path. A single JDBC transaction wraps
     * the stored procedure call so no dirty-read window exists between the
     * pair-level and stage-level updates.
     * </p>
     *
     * @param pairId          The job pair ID
     * @param stageNumber     The stage that reached the terminal status (1-based)
     * @param terminalStatus  Status code for the pair and the terminal stage
     * @param notReachedStatus Status code for stages after {@code stageNumber}
     * @return True on success and false otherwise
     */
    public static boolean setPairStatusPrecise(
        int pairId,
        int stageNumber,
        int terminalStatus,
        int notReachedStatus
    ) {
        return setPairStatusPrecise(pairId, stageNumber, terminalStatus, notReachedStatus, false);
    }

    /**
     * As {@link #setPairStatusPrecise(int, int, int, int)}, but able to replace a
     * terminal status that was already recorded.
     *
     * <p>Overriding is for deliberate administrative correction only. Automated writers
     * -- monitors and reconcilers -- must pass {@code false} and accept losing the race,
     * or a background sweep can overwrite a real result.
     *
     * @return false when the pair already held a different terminal status and
     *         {@code forceOverride} was not set. Nothing was written in that case.
     */
    public static boolean setPairStatusPrecise(
        int pairId,
        int stageNumber,
        int terminalStatus,
        int notReachedStatus,
        boolean forceOverride
    ) {
        return setPairStatusPreciseResult(
            pairId, stageNumber, terminalStatus, notReachedStatus, forceOverride)
            == PairStatusResult.APPLIED;
    }

    /**
     * As {@link #setPairStatusPrecise(int, int, int, int, boolean)}, but distinguishing a
     * refusal from a failure.
     *
     * <p>Callers that own a container, pod or process for this pair need that
     * distinction: {@link PairStatusResult#SUPERSEDED} means the pair is finished and the
     * resource should be released, while {@link PairStatusResult#FAILED} means the work
     * must stay discoverable for a later attempt. Collapsing them into a boolean forces a
     * choice between leaking the resource and losing the result.
     */
    public static PairStatusResult setPairStatusPreciseResult(
        int pairId,
        int stageNumber,
        int terminalStatus,
        int notReachedStatus,
        boolean forceOverride
    ) {
        Connection con = null;
        PreparedStatement ps = null;
        Integer attemptNoForFinalize = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);
            ps = con.prepareStatement(
                "SELECT starexec.UpdatePairStatusPrecise(?, ?, ?, ?, ?)"
            );
            ps.setInt(1, pairId);
            ps.setInt(2, stageNumber);
            ps.setInt(3, terminalStatus);
            ps.setInt(4, notReachedStatus);
            ps.setBoolean(5, forceOverride);

            boolean applied;
            try (ResultSet rs = ps.executeQuery()) {
                applied = rs.next() && rs.getBoolean(1);
            }
            if (!applied) {
                // Another writer recorded a different terminal result first. Nothing was
                // written, so roll back and report the loss. Falling through would
                // finalize a manifest on disk describing a status the database refused,
                // leaving the filesystem and the database contradicting each other.
                Common.doRollback(con);
                return PairStatusResult.SUPERSEDED;
            }

            if (isTerminalStatusCode(terminalStatus)) {
                attemptNoForFinalize = getOrCreateCurrentAttemptNo(con, pairId, true);
            }
            // Committed here rather than through endTransaction, which swallows a failed
            // commit. The manifest is written below on the strength of this commit, so a
            // silently rolled-back transaction would produce exactly the same
            // disk-versus-database contradiction as the case above.
            con.commit();
            Common.enableAutoCommit(con);
            if (isTerminalStatusCode(terminalStatus) && attemptNoForFinalize != null) {
                finalizePairManifest(pairId, attemptNoForFinalize, terminalStatus);
            }
            return PairStatusResult.APPLIED;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            Common.doRollback(con);
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return PairStatusResult.FAILED;
    }

    /**
     * Sets the status code of every stage for the given pair to the given code
     *
     * @param pairId
     * @param statusCode
     * @param con        An open database connection to make calls on
     * @return True on success and false on error
     */
    public static boolean setAllPairStageStatus(
        int pairId,
        int statusCode,
        Connection con
    ) {
        return setLaterPairStageStatus(pairId, statusCode, -1, con);
    }

    /**
     * Sets the disk_size for the given job pair to 0, updating the
     * jobpair_stage_data, jobs, and users tables
     *
     * @param jobPairId
     */
    public static void setJobPairDiskSizeToZero(int jobPairId) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT starexec.RemoveJobPairDiskSize(?)"
            );
            ps.setInt(1, jobPairId);
            boolean hasResultSet = ps.execute();
            if (hasResultSet) {
                ResultSet rs = ps.getResultSet();
                while (rs.next()) {
                    // consume the result set
                }
                Common.safeClose(rs);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
        }
    }

    /**
     * Gets pair ids, node ids, and job ids, that have been enqueued longer than a
     * given amount of time.
     *
     * @param minutes if a pair has been enqueued longer than this number of minutes
     *                it will be returned with its node
     *                and job ids.
     * @return the pair ids and their node ids and jobs ids that have been enqueued
     *         longer than the given amount of
     *         time.
     * @throws SQLException if something goes wrong in the database.
     */
    public static ImmutableSet<PairIdJobId> getPairsEnqueuedLongerThan(
        int minutes
    ) throws SQLException {
        java.sql.Connection con = null;
        java.sql.PreparedStatement ps = null;
        java.sql.ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetPairsEnqueuedLongerThan(?)"
            );
            ps.setInt(1, minutes);
            results = ps.executeQuery();
            Set<PairIdJobId> brokenPairs = new HashSet<>();
            while (results.next()) {
                brokenPairs.add(
                    new PairIdJobId(
                        results.getInt("pair_id"),
                        results.getInt("job_id")
                    )
                );
            }
            return ImmutableSet.copyOf(brokenPairs);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    /**
     * Gets nodes that may have had pairs enqueued longer than the given amount of
     * time without setting them to
     * "running". The SQL procedure gets the queues for pairs that have been
     * enqueued for the amount of time without
     * being set to running and then gets the nodes from that queue that haven't
     * been running jobs in that time.
     *
     * @param minutes the time that nodes must have been idle for to qualify as
     *                broken.
     * @return the ids of the identified nodes.
     * @throws SQLException if there is a database error.
     */
    public static ImmutableSet<
        Integer
    > getNodesThatMayHavePairsEnqueuedLongerThan(int minutes)
        throws SQLException {
        java.sql.Connection con = null;
        java.sql.PreparedStatement ps = null;
        java.sql.ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetNodesThatMayHavePairsEnqueuedLongerThan(?)"
            );
            ps.setInt(1, minutes);
            results = ps.executeQuery();
            Set<Integer> potentiallyBrokenNodes = new HashSet<>();
            while (results.next()) {
                potentiallyBrokenNodes.add(results.getInt("node_id"));
            }
            return ImmutableSet.copyOf(potentiallyBrokenNodes);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    /**
     * Sets the status of a given job pair to the given status
     *
     * @param pairId
     * @param statusCode
     * @param con
     * @return True on success and false on error
     */
    public static boolean setPairStatus(
        int pairId,
        int statusCode,
        Connection con
    ) {
        PreparedStatement ps = null;
        try {
            ps = con.prepareStatement("CALL starexec.UpdatePairStatus(?, ?)");
            ps.setInt(1, pairId);
            ps.setShort(2, (short) statusCode);

            Common.executeAndDrain(ps);

            return true;
        } catch (Exception e) {
            log.debug("setPairStatus", e);
        } finally {
            Common.safeClose(ps);
        }
        return false;
    }

    /**
     * Updates the status code for a given stage of a specific pair.
     *
     * @param pairId      the id of the pair to update the status of
     * @param stageNumber The stage to update
     * @param statusCode  the status code to set for the pair
     * @return True if the operation was a success, false otherwise
     */
    public static boolean setPairStatus(
        int pairId,
        int stageNumber,
        int statusCode
    ) {
        Connection con = null;

        try {
            con = Common.getConnection();
            return setPairStageStatus(pairId, statusCode, stageNumber, con);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            Common.safeClose(con);
        }

        return false;
    }

    /**
     * Updates the status_code for a pair. If the pair is being set to enqueued,
     * also sets the pair's queue_sub_time
     * . If
     * the pair is being set to a completed status, the pair's completion entry is
     * updated.
     *
     * @param pairId     the id of the pair to update the status of
     * @param statusCode the status code to set for the pair
     * @return True if the operation was a success, false otherwise
     */
    public static boolean setPairStatus(int pairId, int statusCode) {
        Connection con = null;
        boolean success = false;
        Integer attemptNoForFinalize = null;

        try {
            con = Common.getConnection();
            Common.beginTransaction(con);
            success = setPairStatus(pairId, statusCode, con);
            if (!success) {
                Common.doRollback(con);
                return false;
            }
            if (success && isTerminalStatusCode(statusCode)) {
                attemptNoForFinalize = getOrCreateCurrentAttemptNo(con, pairId, true);
            }
            Common.endTransaction(con);
            return success;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            Common.doRollback(con);
        } finally {
            Common.safeClose(con);
            if (success && isTerminalStatusCode(statusCode) && attemptNoForFinalize != null) {
                finalizePairManifest(pairId, attemptNoForFinalize, statusCode);
            }
        }

        return false;
    }

    /**
     * Reads all data for a specific queue from the jobpair_time_delta table and
     * then clears the data from that queue
     * all inside a single transaction.
     *
     * @param queueID The ID of the queue to get data for. If this is -1, gets and
     *                clears all data
     * @return A HashMap mapping userIds to their time delta values.
     */

    public static HashMap<Integer, Integer> getAndClearTimeDeltas(int queueID) {
        Connection con = null;
        PreparedStatement ps = null;
        PreparedStatement psClear = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);

            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetJobpairTimeDeltaData(?)"
            );
            ps.setInt(1, queueID);
            results = ps.executeQuery();
            HashMap<Integer, Integer> data = new HashMap<>();
            while (results.next()) {
                data.put(
                    results.getInt("user_id"),
                    results.getInt("time_delta")
                );
            }
            Common.safeClose(ps);
            psClear = con.prepareStatement(
                "SELECT starexec.ClearJobpairTimeDeltaData(?)"
            );
            psClear.setInt(1, queueID);
            Common.executeAndDrain(psClear);

            Common.endTransaction(con);
            return data;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            Common.doRollback(con);
            return null;
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
            Common.safeClose(psClear);
            Common.safeClose(results);
        }
    }

    /**
     * Update's a job pair's backend execution ID (SGE, OAR, or so on)
     *
     * @param pairId The id of the pair to update
     * @param execId The backend id to set for the pair
     * @return True if the operation was a success, false otherwise.
     */
    public static boolean updateBackendExecId(int pairId, int execId) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement("SELECT starexec.SetBackendExecId(?, ?)");

            ps.setInt(1, pairId);
            ps.setInt(2, execId);
            Common.executeAndDrain(ps);

            return true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
        }

        return false;
    }

    public enum PairStatusLookupState {
        FOUND,
        MISSING,
        ERROR,
    }

    public static final class PairStatusLookupResult {
        private final PairStatusLookupState state;
        private final int statusCode;

        private PairStatusLookupResult(
            PairStatusLookupState state,
            int statusCode
        ) {
            this.state = state;
            this.statusCode = statusCode;
        }

        public PairStatusLookupState getState() {
            return state;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public boolean isFound() {
            return state == PairStatusLookupState.FOUND;
        }

        public boolean isMissing() {
            return state == PairStatusLookupState.MISSING;
        }

        public boolean isError() {
            return state == PairStatusLookupState.ERROR;
        }
    }

    public enum ConditionalPairUpdateResult {
        UPDATED,
        STALE,
        ERROR,
    }

    /**
     * Conditionally claims a pending pair for submission by transitioning both the
     * pair and its stages to STATUS_ENQUEUED only if the row still exists and the
     * parent job is still submit-eligible.
     *
     * <p>This is used by {@code JobManager.submitJobs()} to prevent stale schedule
     * snapshots from launching backend work for pairs that were paused, killed,
     * deleted, or otherwise changed after the schedule was built.</p>
     *
     * @param pairId The pair to claim for submission
     * @return UPDATED if the pair was claimed, STALE if it no longer exists or is
     *         no longer pending/eligible, ERROR if the database operation failed
     */
    public static ConditionalPairUpdateResult tryMarkPendingPairEnqueued(
        int pairId
    ) {
        Connection con = null;
        PreparedStatement pairPs = null;
        PreparedStatement stagePs = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);

            pairPs = con.prepareStatement(
                "UPDATE starexec.job_pairs jp " +
                "SET status_code = ?, queuesub_time = NOW() " +
                "WHERE jp.id = ? AND jp.status_code = ? " +
                "AND EXISTS (" +
                "    SELECT 1 FROM starexec.jobs j " +
                "    WHERE j.id = jp.job_id " +
                "    AND j.paused = FALSE " +
                "    AND j.killed = FALSE " +
                "    AND j.deleted = FALSE" +
                ")"
            );
            pairPs.setInt(1, StatusCode.STATUS_ENQUEUED.getVal());
            pairPs.setInt(2, pairId);
            pairPs.setInt(3, StatusCode.STATUS_PENDING_SUBMIT.getVal());

            if (pairPs.executeUpdate() == 0) {
                Common.doRollback(con);
                return ConditionalPairUpdateResult.STALE;
            }

            stagePs = con.prepareStatement(
                "UPDATE starexec.jobpair_stage_data " +
                "SET status_code = ? " +
                "WHERE jobpair_id = ?"
            );
            stagePs.setInt(1, StatusCode.STATUS_ENQUEUED.getVal());
            stagePs.setInt(2, pairId);
            stagePs.executeUpdate();

            Common.endTransaction(con);
            return ConditionalPairUpdateResult.UPDATED;
        } catch (Exception e) {
            log.error("tryMarkPendingPairEnqueued pairId=" + pairId, e);
            Common.doRollback(con);
        } finally {
            Common.safeClose(stagePs);
            Common.safeClose(pairPs);
            Common.safeClose(con);
        }

        return ConditionalPairUpdateResult.ERROR;
    }

    /**
     * Updates a job pair's backend execution id without raising or logging a
     * "pair not found" error when the row has already disappeared.
     *
     * <p>This is used immediately after backend submission so callers can tear down
     * orphaned backend work if the pair row was deleted between launch and the DB
     * write.</p>
     *
     * @param pairId The pair to update
     * @param execId The backend execution id
     * @return UPDATED if the row was updated, STALE if the pair is no longer in
     *         a submit-eligible state, ERROR if the database operation failed
     */
    public static ConditionalPairUpdateResult tryUpdateBackendExecId(
        int pairId,
        int execId
    ) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "UPDATE starexec.job_pairs jp " +
                "SET sge_id = ? " +
                "WHERE jp.id = ? " +
                "AND jp.status_code <> ? " +
                "AND jp.status_code <> ? " +
                "AND EXISTS (" +
                "    SELECT 1 FROM starexec.jobs j " +
                "    WHERE j.id = jp.job_id " +
                "    AND j.paused = FALSE " +
                "    AND j.killed = FALSE " +
                "    AND j.deleted = FALSE" +
                ")"
            );
            ps.setInt(1, execId);
            ps.setInt(2, pairId);
            ps.setInt(3, StatusCode.STATUS_PAUSED.getVal());
            ps.setInt(4, StatusCode.STATUS_KILLED.getVal());
            return ps.executeUpdate() > 0
                ? ConditionalPairUpdateResult.UPDATED
                : ConditionalPairUpdateResult.STALE;
        } catch (Exception e) {
            log.error("tryUpdateBackendExecId pairId=" + pairId + ", execId=" + execId, e);
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
        }

        return ConditionalPairUpdateResult.ERROR;
    }

    /**
     * Returns the current status code for a given job pair while distinguishing a
     * missing row from a database error.
     *
     * @param pairId the id of the pair to query
     * @return a structured lookup result containing the row state and, when found,
     *         the current status code
     */
    public static PairStatusLookupResult getPairStatusLookup(int pairId) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT status_code FROM job_pairs WHERE id = ?"
            );
            ps.setInt(1, pairId);
            results = ps.executeQuery();
            if (results.next()) {
                return new PairStatusLookupResult(
                    PairStatusLookupState.FOUND,
                    results.getInt("status_code")
                );
            }
            return new PairStatusLookupResult(
                PairStatusLookupState.MISSING,
                StatusCode.STATUS_UNKNOWN.getVal()
            );
        } catch (Exception e) {
            log.error("getPairStatusLookup pairId=" + pairId, e);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return new PairStatusLookupResult(
            PairStatusLookupState.ERROR,
            StatusCode.STATUS_UNKNOWN.getVal()
        );
    }

    /**
     * Updates a job pair's node assignment only while the pair is still actively
     * running or waiting to run.
     *
     * @param pairId The pair to update
     * @param nodeId The execution node id
     * @return UPDATED if the row was updated, STALE if the pair no longer exists or
     *         is no longer active, ERROR if the database operation failed
     */
    public static ConditionalPairUpdateResult tryUpdatePairExecutionHost(
        int pairId,
        int nodeId
    ) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "UPDATE starexec.job_pairs jp " +
                "SET node_id = ? " +
                "WHERE jp.id = ? " +
                "AND jp.status_code < ? " +
                "AND EXISTS (" +
                "    SELECT 1 FROM starexec.jobs j " +
                "    WHERE j.id = jp.job_id " +
                "    AND j.paused = FALSE " +
                "    AND j.killed = FALSE " +
                "    AND j.deleted = FALSE" +
                ")"
            );
            ps.setInt(1, nodeId);
            ps.setInt(2, pairId);
            ps.setInt(3, StatusCode.STATUS_COMPLETE.getVal());
            return ps.executeUpdate() > 0
                ? ConditionalPairUpdateResult.UPDATED
                : ConditionalPairUpdateResult.STALE;
        } catch (Exception e) {
            log.error(
                "tryUpdatePairExecutionHost pairId=" + pairId + ", nodeId=" + nodeId,
                e
            );
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return ConditionalPairUpdateResult.ERROR;
    }

    /**
     * Marks a pair as STATUS_RUNNING only if it is still an active pair.
     *
     * @param pairId The pair to update
     * @return UPDATED if the row was updated, STALE if the pair no longer exists or
     *         is no longer active, ERROR if the database operation failed
     */
    public static ConditionalPairUpdateResult trySetPairRunning(int pairId) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "UPDATE starexec.job_pairs jp " +
                "SET status_code = ?, start_time = COALESCE(start_time, NOW()) " +
                "WHERE jp.id = ? " +
                "AND jp.status_code < ? " +
                "AND EXISTS (" +
                "    SELECT 1 FROM starexec.jobs j " +
                "    WHERE j.id = jp.job_id " +
                "    AND j.paused = FALSE " +
                "    AND j.killed = FALSE " +
                "    AND j.deleted = FALSE" +
                ")"
            );
            ps.setInt(1, StatusCode.STATUS_RUNNING.getVal());
            ps.setInt(2, pairId);
            ps.setInt(3, StatusCode.STATUS_COMPLETE.getVal());
            return ps.executeUpdate() > 0
                ? ConditionalPairUpdateResult.UPDATED
                : ConditionalPairUpdateResult.STALE;
        } catch (Exception e) {
            log.error("trySetPairRunning pairId=" + pairId, e);
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return ConditionalPairUpdateResult.ERROR;
    }

    /**
     * Resets an ENQUEUED pair back to PENDING_SUBMIT, but only if the pair
     * is still in ENQUEUED status (prevents race with concurrent updates).
     *
     * <p>Used by startup reconciliation: if a pair was claimed (ENQUEUED)
     * but never submitted to a backend and no container evidence exists,
     * it is safe to re-queue.</p>
     *
     * @param pairId The pair to reset
     * @return UPDATED if reset, STALE if pair is no longer ENQUEUED, ERROR on failure
     */
    public static ConditionalPairUpdateResult tryResetEnqueuedToPending(int pairId) {
        Connection con = null;
        PreparedStatement pairPs = null;
        PreparedStatement stagePs = null;
        PreparedStatement lockPs = null;
        ResultSet rs = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);

            // Lock the row and verify it is still ENQUEUED
            lockPs = con.prepareStatement(
                "SELECT status_code FROM starexec.job_pairs WHERE id = ? FOR UPDATE");
            lockPs.setInt(1, pairId);
            rs = lockPs.executeQuery();
            if (!rs.next() || rs.getInt(1) != StatusCode.STATUS_ENQUEUED.getVal()) {
                Common.doRollback(con);
                return ConditionalPairUpdateResult.STALE;
            }
            Common.safeClose(rs);
            Common.safeClose(lockPs);

            // Reset the pair
            pairPs = con.prepareStatement(
                "UPDATE starexec.job_pairs SET status_code = ? WHERE id = ?");
            pairPs.setInt(1, StatusCode.STATUS_PENDING_SUBMIT.getVal());
            pairPs.setInt(2, pairId);
            pairPs.executeUpdate();

            // Reset all stages
            stagePs = con.prepareStatement(
                "UPDATE starexec.jobpair_stage_data SET status_code = ? WHERE jobpair_id = ?");
            stagePs.setInt(1, StatusCode.STATUS_PENDING_SUBMIT.getVal());
            stagePs.setInt(2, pairId);
            stagePs.executeUpdate();

            Common.endTransaction(con);
            return ConditionalPairUpdateResult.UPDATED;
        } catch (Exception e) {
            log.error("tryResetEnqueuedToPending pairId=" + pairId, e);
            Common.doRollback(con);
        } finally {
            Common.safeClose(rs);
            Common.safeClose(lockPs);
            Common.safeClose(stagePs);
            Common.safeClose(pairPs);
            Common.safeClose(con);
        }
        return ConditionalPairUpdateResult.ERROR;
    }

    /**
     * Marks a RUNNING pair as a terminal failure, but only if the pair
     * is still in RUNNING status (prevents race with concurrent updates).
     *
     * <p>Used by startup reconciliation: if a pair was RUNNING but no
     * container evidence exists after restart, the solver may have executed
     * but its output is lost.  Marking as failed is safer than auto-rerun.</p>
     *
     * @param pairId The pair to mark as failed
     * @return UPDATED if marked, STALE if pair is no longer RUNNING, ERROR on failure
     */
    public static ConditionalPairUpdateResult tryMarkRunningAsFailed(int pairId) {
        Connection con = null;
        PreparedStatement lockPs = null;
        ResultSet rs = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);

            // Lock the row and verify it is still RUNNING
            lockPs = con.prepareStatement(
                "SELECT status_code FROM starexec.job_pairs WHERE id = ? FOR UPDATE");
            lockPs.setInt(1, pairId);
            rs = lockPs.executeQuery();
            if (!rs.next() || rs.getInt(1) != StatusCode.STATUS_RUNNING.getVal()) {
                Common.doRollback(con);
                return ConditionalPairUpdateResult.STALE;
            }
            Common.endTransaction(con);
            // Transaction committed; now use the precise procedure which
            // handles pair, stages, job_pair_completion, and parent job
            // finalisation in its own transaction.
        } catch (Exception e) {
            log.error("tryMarkRunningAsFailed lock pairId=" + pairId, e);
            Common.doRollback(con);
            return ConditionalPairUpdateResult.ERROR;
        } finally {
            Common.safeClose(rs);
            Common.safeClose(lockPs);
            Common.safeClose(con);
        }

        // Outside the lock transaction: call the stored procedure which
        // has its own internal transaction for the update + side effects.
        try {
            // Never overrides. The lock taken above is released before this runs, so the
            // monitor can record a real result in between; reconciliation has to lose
            // that race. Reporting UPDATED unconditionally, as this did, is what allowed
            // a genuine result to be replaced by ERROR_RUNSCRIPT and counted as a fix.
            boolean applied = setPairStatusPrecise(pairId, 1,
                StatusCode.ERROR_RUNSCRIPT.getVal(),
                StatusCode.STATUS_NOT_REACHED.getVal(),
                false);
            if (!applied) {
                log.info("tryMarkRunningAsFailed", "pair " + pairId
                        + " reached a terminal status before reconciliation could mark it"
                        + " failed; leaving the recorded result alone");
                return ConditionalPairUpdateResult.STALE;
            }
            return ConditionalPairUpdateResult.UPDATED;
        } catch (Exception e) {
            log.error("tryMarkRunningAsFailed setPairStatusPrecise pairId=" + pairId, e);
            return ConditionalPairUpdateResult.ERROR;
        }
    }

    /**
     * Updates job pair run solver statistics (CPU time, wallclock, memory, etc.)
     *
     * @param pairId The ID of the job pair
     * @param nodeName The name of the node where job ran (hostname)
     * @param wallClock Wallclock time in seconds
     * @param cpu Total CPU time in seconds
     * @param userTime User time in seconds
     * @param systemTime System time in seconds
     * @param maxVmem Maximum virtual memory in KB
     * @param maxResSet Maximum resident set size in KB
     * @param stageNumber The stage number (usually 1 for single-stage jobs)
     * @param diskSize Disk size used in KB
     * @return True if successful, false otherwise
     */
    public static boolean updateRunSolverStats(
        int pairId,
        String nodeName,
        double wallClock,
        double cpu,
        double userTime,
        double systemTime,
        double maxVmem,
        long maxResSet,
        int stageNumber,
        long diskSize
    ) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "CALL starexec.UpdatePairRunSolverStats(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            );

            ps.setInt(1, pairId);
            ps.setString(2, nodeName);
            ps.setDouble(3, wallClock);
            ps.setDouble(4, cpu);
            ps.setDouble(5, userTime);
            ps.setDouble(6, systemTime);
            ps.setDouble(7, maxVmem);
            ps.setLong(8, maxResSet);
            ps.setInt(9, stageNumber);
            ps.setLong(10, diskSize);

            Common.executeAndDrain(ps);

            return true;
        } catch (Exception e) {
            log.error("Error updating run solver stats for pair " + pairId, e);
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
        }

        return false;
    }

    /**
     * Sets the start time of a job pair to the current database time, only if
     * it has not already been set.
     *
     * <p>The conditional update ({@code WHERE start_time IS NULL}) makes this
     * safe to call multiple times — only the first call takes effect.  This
     * mirrors the semantics of {@code SetPairStartTime} which is used in the
     * SGE/local backend paths but is a no-op in container mode.</p>
     *
     * @param pairId The ID of the job pair to update
     * @return true if the row was updated, false if already set or on error
     */
    public static boolean setStartTime(int pairId) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "UPDATE starexec.job_pairs SET start_time = NOW() " +
                "WHERE id = ? AND start_time IS NULL"
            );
            ps.setInt(1, pairId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.error("setStartTime pairId=" + pairId, e);
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return false;
    }

    /**
     * Sets the end time of a job pair to the current database time.
     *
     * <p>Unlike {@link #setStartTime}, this always overwrites any existing
     * value so that retries (e.g. via reconciliation) record the latest
     * completion time rather than a stale one from a previous attempt.</p>
     *
     * @param pairId The ID of the job pair to update
     * @return true if the row was updated, false if the pair was not found or on error
     */
    public static boolean setEndTime(int pairId) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "UPDATE starexec.job_pairs SET end_time = NOW() WHERE id = ?"
            );
            ps.setInt(1, pairId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.error("setEndTime pairId=" + pairId, e);
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return false;
    }

    /**
     * Updates the database to give the job pair with the given ID the given job
     * space.
     *
     * @param jobPairId  The ID of the job pair in question
     * @param jobSpaceId The job space ID of the pair
     * @param con        The open connection to perform the update on
     * @throws Exception
     * @author Eric Burns
     */

    public static void UpdateJobSpaces(
        int jobPairId,
        int jobSpaceId,
        Connection con
    ) {
        PreparedStatement ps = null;
        try {
            ps = con.prepareStatement("SELECT starexec.UpdateJobSpaceId(?, ?)");
            ps.setInt(1, jobPairId);
            ps.setInt(2, jobSpaceId);
            Common.executeAndDrain(ps);
        } catch (Exception e) {
            log.error("UpdateJobSpaces", e);
        } finally {
            Common.safeClose(ps);
        }
    }

    /**
     * Calls buildJobSpaceIdtoJobPairMapForJob, and then rounds all cpu and
     * wallclock times before returning results
     *
     * @param job
     * @return Identical to buildJobSpaceIdtoJobPairMapForJob, but with rounded
     *         times
     */
    public static Map<
        Integer,
        List<JobPair>
    > buildJobSpaceIdToJobPairMapWithWallCpuTimesRounded(Job job) {
        Map<Integer, List<JobPair>> outputMap =
            buildJobSpaceIdToJobPairMapForJob(job);
        roundWallclockAndCpuTimesInJobSpaceIdToJobPairMap(outputMap);
        return outputMap;
    }

    private static void roundWallclockAndCpuTimesInJobSpaceIdToJobPairMap(
        Map<Integer, List<JobPair>> jobSpaceIdToJobPairMap
    ) {
        for (Integer jobSpaceId : jobSpaceIdToJobPairMap.keySet()) {
            List<JobPair> jobPairs = jobSpaceIdToJobPairMap.get(jobSpaceId);
            for (JobPair jp : jobPairs) {
                jp.setPrimaryWallclockTime(
                    Math.round(jp.getPrimaryWallclockTime() * 100) / 100.0
                );
                jp.setPrimaryCpuTime(
                    Math.round(jp.getPrimaryCpuTime() * 100) / 100.0
                );
            }
        }
    }

    /**
     * Builds a mapping of job space IDs to JobPairs in that JobSpace given the
     * JobSpaces and JobPairs
     *
     * @param job The job to work on
     * @return The mapping of job space IDs to in that job space
     * @author Albert Giegerich
     */
    public static Map<Integer, List<JobPair>> buildJobSpaceIdToJobPairMapForJob(
        Job job
    ) {
        int jobId = job.getId();
        int primaryJobSpaceId = job.getPrimarySpace();
        List<JobSpace> jobSpaces = Spaces.getSubSpacesForJob(
            primaryJobSpaceId,
            true
        );
        jobSpaces.add(Spaces.getJobSpace(job.getPrimarySpace()));
        List<JobPair> allJobPairsInJob = Jobs.getDetailed(
            jobId,
            0
        ).getJobPairs();
        Map<Integer, List<JobPair>> jobSpaceIdToJobPairMap = new HashMap<>();
        for (JobSpace js : jobSpaces) {
            List<JobPair> jobPairsAssociatedWithJs = new ArrayList<>();
            for (JobPair jp : allJobPairsInJob) {
                if (jp.getJobSpaceId() == js.getId()) {
                    jobPairsAssociatedWithJs.add(jp);
                }
            }
            if (!jobPairsAssociatedWithJs.isEmpty()) {
                jobSpaceIdToJobPairMap.put(
                    js.getId(),
                    jobPairsAssociatedWithJs
                );
            }
        }
        return jobSpaceIdToJobPairMap;
    }

    /**
     * Given a list of JobPair objects that have their jobSpaceIds set, updates the
     * database to reflect these new job
     * space ids
     *
     * @param jobPairs The pairs to update
     * @param con      An open connection to make calls on
     * @author Eric Burns
     */

    public static void updateJobSpaces(List<JobPair> jobPairs, Connection con) {
        try {
            for (JobPair jp : jobPairs) {
                UpdateJobSpaces(jp.getId(), jp.getJobSpaceId(), con);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * Kills the given job pair and updates the status for the pair to
     *
     * @param pairId
     * @param execId
     */
    public static void killPair(int pairId, int execId) {
        try {
            R.BACKEND.killPair(execId);
            JobPairs.setJobPairDiskSizeToZero(pairId);
            JobPairs.UpdateStatus(
                pairId,
                Status.StatusCode.STATUS_KILLED.getVal()
            );
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * Kills a pair and writes {@code STATUS_KILLED} only if the execution provably stopped.
     *
     * <p>{@link #killPair(int, int)} writes that status unconditionally — it discards the
     * backend's answer, and is {@code void}, so no caller can tell either. On a backend whose
     * teardown is asynchronous that records "this pair was killed" while its pod is still
     * running: the pair reads as finished, its node is considered free, and a late write from
     * the surviving execution lands on a pair nobody is watching.
     *
     * <p>On an unproven kill nothing is written at all. The pair keeps its current status, so
     * it stays visible as in-flight rather than being misreported as terminal.
     *
     * @return true only when the execution was confirmed stopped and the status was written
     */
    public static boolean killPairConfirmed(int pairId, int execId) {
        try {
            org.starexec.backend.Backend.KillOutcome outcome =
                R.BACKEND.killPairConfirmed(execId);
            if (outcome != org.starexec.backend.Backend.KillOutcome.CONFIRMED_SAFE) {
                log.warn(
                    "Not recording pair " + pairId + " as killed: execution " + execId +
                        " could not be confirmed stopped"
                );
                return false;
            }
            JobPairs.setJobPairDiskSizeToZero(pairId);
            JobPairs.UpdateStatus(
                pairId,
                Status.StatusCode.STATUS_KILLED.getVal()
            );
            return true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return false;
        }
    }

    /**
     * Updates the status of the given job pair, replacing its current status code
     * with the given one
     *
     * @param jobPairId   The ID of the job pair in question
     * @param status_code The new status code to assign to the job pair
     */
    public static void UpdateStatus(int jobPairId, int status_code) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT starexec.UpdateJobPairStatus(?, ?)"
            );
            ps.setInt(1, jobPairId);
            ps.setInt(2, status_code);
            Common.executeAndDrain(ps);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
        }
    }

    /**
     * Gets all job pairs in the database that have the given status code. This
     * should really only be called for rare
     * codes like 2-5, as otherwise it may read a very large number of pairs and be
     * very slow.
     *
     * @param statusCode
     * @return A list of all pairs with the given status code
     */
    public static List<JobPair> getPairsByStatus(int statusCode) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            List<JobPair> pairs = new ArrayList<>();
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetJobPairsWithStatus(?)"
            );
            ps.setInt(1, statusCode);
            results = ps.executeQuery();
            while (results.next()) {
                JobPair p = JobPairs.resultToPair(results);
                p.getStatus().setCode(statusCode);
                pairs.add(p);
            }
            return pairs;
        } catch (Exception e) {
            log.error("getPairsByStatus", e);
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
            Common.safeClose(results);
        }

        return null;
    }

    /**
     * Gets all job pairs in the database that have the given status code. This
     * should really only be called for rare
     * codes like 2-5, as otherwise it may read a very large number of pairs and be
     * very slow.
     *
     * @param statusCode
     * @return A list of all pairs with the given status code
     */
    public static List<Integer> getPairIdsByStatusNotRerunAfterDate(
        StatusCode statusCode,
        Timestamp timestamp
    ) throws SQLException {
        final String methodName = "getPairIdsByStatusNotRerunAfterDate";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet results = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT * FROM starexec.GetJobPairIdsWithStatusNotRerunAfterDate(?, ?)"
            );
            ps.setInt(1, statusCode.getVal());
            ps.setObject(2, timestamp);
            results = ps.executeQuery();

            List<Integer> pairIds = new ArrayList<>();
            while (results.next()) {
                pairIds.add(results.getInt("id"));
            }
            return pairIds;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            log.error(methodName, e.getMessage(), e);
            throw new SQLException(e);
        } finally {
            Common.safeClose(results);
            Common.safeClose(ps);
            Common.safeClose(con);
        }
    }

    /**
     * Sets the status code for a given pair and all of its stages to
     * ERROR_SUBMIT_FAIL if and only if the pair's
     * status
     * code in the database matches the status code set in the given pair. This
     * check is done to prevent a race
     * condition in which a pair that is actually not stuck (hence its status code
     * has changed since the pair was read)
     * but is still set to the error status code.
     *
     * @param p The JobPair to affect. Must have ID and status code set
     */
    public static void setBrokenPairStatus(JobPair p) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT starexec.SetBrokenPairStatus(?, ?, ?)"
            );
            ps.setInt(1, p.getId());
            ps.setInt(2, p.getStatus().getCode().getVal());
            ps.setInt(3, Status.StatusCode.ERROR_SUBMIT_FAIL.getVal());
            Common.executeAndDrain(ps);
        } catch (Exception e) {
            log.error("setBrokenPairStatus", e);
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
        }
    }

    /**
     * Gets all job pairs are currently under the jurisdiction of the backend. These
     * are the pairs with status codes
     * 2-5.
     *
     * @return The list of job pairs. Stages will not be populated
     */
    public static List<JobPair> getPairsInBackend() {
        List<JobPair> pairs = new ArrayList<>();
        List<JobPair> enqueuedPairs = getPairsByStatus(
            Status.StatusCode.STATUS_ENQUEUED.getVal()
        );
        if (enqueuedPairs != null) {
            pairs.addAll(enqueuedPairs);
        }
        List<JobPair> runningPairs = getPairsByStatus(
            Status.StatusCode.STATUS_RUNNING.getVal()
        );
        if (runningPairs != null) {
            pairs.addAll(runningPairs);
        }
        return pairs;
    }

    /**
     * Returns the IDs of all job pairs with the given status code.
     *
     * <p>Used by {@code PodmanBackend.reconcileOrphanedPairs()} on startup
     * to find pairs that were left in ENQUEUED or RUNNING state after a crash.</p>
     *
     * @param statusCode The status code to filter by
     * @return List of pair IDs, never null
     */
    public static List<Integer> getPairIdsByStatusCode(int statusCode) {
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            List<Integer> ids = new ArrayList<>();
            con = Common.getConnection();
            ps = con.prepareStatement(
                "SELECT id FROM starexec.job_pairs WHERE status_code = ?"
            );
            ps.setInt(1, statusCode);
            rs = ps.executeQuery();
            while (rs.next()) {
                ids.add(rs.getInt("id"));
            }
            return ids;
        } catch (Exception e) {
            log.error("getPairIdsByStatusCode", e);
            return new ArrayList<>();
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
            Common.safeClose(rs);
        }
    }

    /**
     * Updates a job pair's node_id in the database.
     *
     * @param pairId
     * @param nodeId
     * @return True on success and false otherwise
     */
    public static boolean updatePairExecutionHost(int pairId, int nodeId) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            ps = con.prepareStatement("SELECT starexec.UpdatePairNodeId(?, ?)");
            ps.setInt(1, pairId);
            ps.setInt(2, nodeId);
            Common.executeAndDrain(ps);
            return true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            Common.safeClose(con);
            Common.safeClose(ps);
        }
        return false;
    }

    public static String getManifestStateName(int state) {
        switch (state) {
            case MANIFEST_STATE_COLLECTING:
                return "COLLECTING";
            case MANIFEST_STATE_FINALIZING:
                return "FINALIZING";
            case MANIFEST_STATE_FINAL:
                return "FINAL";
            case MANIFEST_STATE_FINAL_DERIVED:
                return "FINAL_DERIVED";
            case MANIFEST_STATE_FAILED:
                return "FAILED";
            default:
                return "UNKNOWN";
        }
    }

    public static String getManifestProvenanceName(int provenance) {
        switch (provenance) {
            case MANIFEST_PROVENANCE_LIVE:
                return "LIVE";
            case MANIFEST_PROVENANCE_DERIVED_LEGACY:
                return "DERIVED_LEGACY";
            default:
                return "UNKNOWN";
        }
    }

    public static boolean incrementPairAttempt(int pairId) {
        Connection con = null;
        PreparedStatement ensurePs = null;
        PreparedStatement updatePs = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);

            ensurePs = con.prepareStatement(
                "INSERT INTO starexec.job_pair_attempts(pair_id, current_attempt_no, updated_at) VALUES (?, 1, NOW()) ON CONFLICT (pair_id) DO NOTHING"
            );
            ensurePs.setInt(1, pairId);
            ensurePs.executeUpdate();

            updatePs = con.prepareStatement(
                "UPDATE starexec.job_pair_attempts SET current_attempt_no = current_attempt_no + 1, updated_at = NOW() WHERE pair_id = ?"
            );
            updatePs.setInt(1, pairId);
            updatePs.executeUpdate();

            Common.endTransaction(con);
            return true;
        } catch (Exception e) {
            log.error("incrementPairAttempt", e);
            Common.doRollback(con);
        } finally {
            Common.safeClose(ensurePs);
            Common.safeClose(updatePs);
            Common.safeClose(con);
        }
        return false;
    }

    public static boolean upsertPairManifestCollecting(int pairId) {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);

            int attemptNo = getOrCreateCurrentAttemptNo(con, pairId);
            String manifestJson = buildManifestJson(pairId, attemptNo, MANIFEST_PROVENANCE_LIVE, false, null);
            if (Util.isNullOrEmpty(manifestJson)) {
                Common.endTransaction(con);
                return false;
            }
            String digest = getSha256(manifestJson);

            ps = con.prepareStatement(
                "INSERT INTO starexec.job_pair_repro_manifests(pair_id, attempt_no, state, provenance, schema_version, manifest_json, manifest_sha256, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, 1, ?::jsonb, ?, NOW(), NOW()) " +
                    "ON CONFLICT (pair_id, attempt_no) DO UPDATE SET manifest_json = EXCLUDED.manifest_json, " +
                    "manifest_sha256 = EXCLUDED.manifest_sha256, updated_at = NOW() " +
                    "WHERE starexec.job_pair_repro_manifests.state IN (0, 1, 4)"
            );
            ps.setInt(1, pairId);
            ps.setInt(2, attemptNo);
            ps.setInt(3, MANIFEST_STATE_COLLECTING);
            ps.setInt(4, MANIFEST_PROVENANCE_LIVE);
            ps.setString(5, manifestJson);
            ps.setString(6, digest);
            ps.executeUpdate();

            Common.endTransaction(con);
            return true;
        } catch (Exception e) {
            log.error("upsertPairManifestCollecting", e);
            Common.doRollback(con);
        } finally {
            Common.safeClose(ps);
            Common.safeClose(con);
        }
        return false;
    }

    public static boolean finalizePairManifest(int pairId, Integer sourceStatusCode) {
        Connection con = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);
            int attemptNo = getOrCreateCurrentAttemptNo(con, pairId, true);
            Common.endTransaction(con);
            return finalizePairManifest(pairId, attemptNo, sourceStatusCode);
        } catch (Exception e) {
            log.error("finalizePairManifest", e);
            Common.doRollback(con);
        } finally {
            Common.safeClose(con);
        }
        return false;
    }

    public static boolean finalizePairManifest(int pairId, int attemptNo, Integer sourceStatusCode) {
        Connection con = null;
        PreparedStatement ensureRowPs = null;
        PreparedStatement updatePs = null;
        PreparedStatement selectPs = null;
        ResultSet rs = null;
        try {
            con = Common.getConnection();
            Common.beginTransaction(con);

            ensureRowPs = con.prepareStatement(
                "INSERT INTO starexec.job_pair_repro_manifests(pair_id, attempt_no, state, provenance, schema_version, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, 1, NOW(), NOW()) ON CONFLICT (pair_id, attempt_no) DO NOTHING"
            );
            ensureRowPs.setInt(1, pairId);
            ensureRowPs.setInt(2, attemptNo);
            ensureRowPs.setInt(3, MANIFEST_STATE_COLLECTING);
            ensureRowPs.setInt(4, MANIFEST_PROVENANCE_LIVE);
            ensureRowPs.executeUpdate();

            String manifestJson = buildManifestJson(pairId, attemptNo, MANIFEST_PROVENANCE_LIVE, true, sourceStatusCode);
            if (Util.isNullOrEmpty(manifestJson)) {
                Common.endTransaction(con);
                return false;
            }
            String digest = getSha256(manifestJson);

            updatePs = con.prepareStatement(
                "UPDATE starexec.job_pair_repro_manifests SET state=?, provenance=?, schema_version=1, " +
                    "manifest_json=?::jsonb, manifest_sha256=?, source_status_code=?, finalized_at=NOW(), updated_at=NOW() " +
                    "WHERE pair_id=? AND attempt_no=? AND state IN (0,1,4)"
            );
            updatePs.setInt(1, MANIFEST_STATE_FINAL);
            updatePs.setInt(2, MANIFEST_PROVENANCE_LIVE);
            updatePs.setString(3, manifestJson);
            updatePs.setString(4, digest);
            if (sourceStatusCode == null) {
                updatePs.setNull(5, Types.INTEGER);
            } else {
                updatePs.setInt(5, sourceStatusCode);
            }
            updatePs.setInt(6, pairId);
            updatePs.setInt(7, attemptNo);
            int updated = updatePs.executeUpdate();

            if (updated == 0) {
                selectPs = con.prepareStatement(
                    "SELECT state, manifest_sha256 FROM starexec.job_pair_repro_manifests WHERE pair_id=? AND attempt_no=?"
                );
                selectPs.setInt(1, pairId);
                selectPs.setInt(2, attemptNo);
                rs = selectPs.executeQuery();
                if (!rs.next()) {
                    Common.endTransaction(con);
                    return false;
                }
                int state = rs.getInt("state");
                String currentDigest = rs.getString("manifest_sha256");
                if ((state == MANIFEST_STATE_FINAL || state == MANIFEST_STATE_FINAL_DERIVED) && digest.equals(currentDigest)) {
                    Common.endTransaction(con);
                    return true;
                }
                Common.endTransaction(con);
                return false;
            }

            Common.endTransaction(con);
            return true;
        } catch (Exception e) {
            log.error("finalizePairManifest", e);
            Common.doRollback(con);
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ensureRowPs);
            Common.safeClose(updatePs);
            Common.safeClose(selectPs);
            Common.safeClose(con);
        }
        return false;
    }

    public static PairReproManifestResult getPairReproManifest(int pairId, Integer requestedAttemptNo) {
        Connection con = null;
        try {
            con = Common.getConnection();
            if (requestedAttemptNo != null && requestedAttemptNo < 1) {
                return null;
            }

            if (requestedAttemptNo == null) {
                PairReproManifestResult latestFinal = getLatestFinalManifestRow(con, pairId);
                if (latestFinal != null) {
                    return latestFinal;
                }
            }

            int attemptNo = requestedAttemptNo == null ? getOrCreateCurrentAttemptNo(con, pairId) : requestedAttemptNo;

            PairReproManifestResult existing = getManifestRow(con, pairId, attemptNo);
            if (existing != null) {
                return existing;
            }

            if (requestedAttemptNo != null) {
                return null;
            }

            JobPair pair = getPair(pairId);
            if (pair == null) {
                return null;
            }

            if (pair.getStatus().getCode().incomplete()) {
                if (!upsertPairManifestCollecting(pairId)) {
                    return null;
                }
            } else {
                if (!createLegacyDerivedManifest(con, pairId, attemptNo, pair.getStatus().getCode().getVal())) {
                    return null;
                }
            }

            return getManifestRow(con, pairId, attemptNo);
        } catch (Exception e) {
            log.error("getPairReproManifest", e);
        } finally {
            Common.safeClose(con);
        }
        return null;
    }

    private static PairReproManifestResult getLatestFinalManifestRow(Connection con, int pairId) throws SQLException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = con.prepareStatement(
                "SELECT pair_id, attempt_no, state, provenance, schema_version, manifest_json::text AS manifest_json, " +
                    "manifest_sha256, source_status_code, created_at, updated_at, finalized_at " +
                    "FROM starexec.job_pair_repro_manifests " +
                    "WHERE pair_id=? AND state IN (?, ?) ORDER BY attempt_no DESC LIMIT 1"
            );
            ps.setInt(1, pairId);
            ps.setInt(2, MANIFEST_STATE_FINAL);
            ps.setInt(3, MANIFEST_STATE_FINAL_DERIVED);
            rs = ps.executeQuery();
            if (!rs.next()) {
                return null;
            }
            Integer sourceStatusCode = rs.getObject("source_status_code") == null ? null : rs.getInt("source_status_code");
            return new PairReproManifestResult(
                rs.getInt("pair_id"),
                rs.getInt("attempt_no"),
                rs.getInt("state"),
                rs.getInt("provenance"),
                rs.getInt("schema_version"),
                rs.getString("manifest_json"),
                rs.getString("manifest_sha256"),
                sourceStatusCode,
                rs.getTimestamp("created_at"),
                rs.getTimestamp("updated_at"),
                rs.getTimestamp("finalized_at")
            );
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ps);
        }
    }

    private static boolean createLegacyDerivedManifest(Connection con, int pairId, int attemptNo, Integer sourceStatusCode)
        throws SQLException {
        PreparedStatement ps = null;
        try {
            String manifestJson = buildManifestJson(pairId, attemptNo, MANIFEST_PROVENANCE_DERIVED_LEGACY, true, sourceStatusCode);
            if (Util.isNullOrEmpty(manifestJson)) {
                return false;
            }
            String digest = getSha256(manifestJson);

            ps = con.prepareStatement(
                "INSERT INTO starexec.job_pair_repro_manifests(pair_id, attempt_no, state, provenance, schema_version, " +
                    "manifest_json, manifest_sha256, source_status_code, created_at, updated_at, finalized_at) " +
                    "VALUES (?, ?, ?, ?, 1, ?::jsonb, ?, ?, NOW(), NOW(), NOW()) ON CONFLICT (pair_id, attempt_no) DO NOTHING"
            );
            ps.setInt(1, pairId);
            ps.setInt(2, attemptNo);
            ps.setInt(3, MANIFEST_STATE_FINAL_DERIVED);
            ps.setInt(4, MANIFEST_PROVENANCE_DERIVED_LEGACY);
            ps.setString(5, manifestJson);
            ps.setString(6, digest);
            if (sourceStatusCode == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setInt(7, sourceStatusCode);
            }
            ps.executeUpdate();
            return true;
        } finally {
            Common.safeClose(ps);
        }
    }

    private static PairReproManifestResult getManifestRow(Connection con, int pairId, int attemptNo) throws SQLException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = con.prepareStatement(
                "SELECT pair_id, attempt_no, state, provenance, schema_version, manifest_json::text AS manifest_json, " +
                    "manifest_sha256, source_status_code, created_at, updated_at, finalized_at " +
                    "FROM starexec.job_pair_repro_manifests WHERE pair_id=? AND attempt_no=?"
            );
            ps.setInt(1, pairId);
            ps.setInt(2, attemptNo);
            rs = ps.executeQuery();
            if (!rs.next()) {
                return null;
            }
            Integer sourceStatusCode = rs.getObject("source_status_code") == null ? null : rs.getInt("source_status_code");
            return new PairReproManifestResult(
                rs.getInt("pair_id"),
                rs.getInt("attempt_no"),
                rs.getInt("state"),
                rs.getInt("provenance"),
                rs.getInt("schema_version"),
                rs.getString("manifest_json"),
                rs.getString("manifest_sha256"),
                sourceStatusCode,
                rs.getTimestamp("created_at"),
                rs.getTimestamp("updated_at"),
                rs.getTimestamp("finalized_at")
            );
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ps);
        }
    }

    private static int getOrCreateCurrentAttemptNo(Connection con, int pairId) throws SQLException {
        return getOrCreateCurrentAttemptNo(con, pairId, false);
    }

    private static int getOrCreateCurrentAttemptNo(Connection con, int pairId, boolean lockForUpdate) throws SQLException {
        PreparedStatement ensurePs = null;
        PreparedStatement selectPs = null;
        ResultSet rs = null;
        try {
            ensurePs = con.prepareStatement(
                "INSERT INTO starexec.job_pair_attempts(pair_id, current_attempt_no, updated_at) VALUES (?, 1, NOW()) ON CONFLICT (pair_id) DO NOTHING"
            );
            ensurePs.setInt(1, pairId);
            ensurePs.executeUpdate();

            selectPs = con.prepareStatement(
                lockForUpdate
                    ? "SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id=? FOR UPDATE"
                    : "SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id=?"
            );
            selectPs.setInt(1, pairId);
            rs = selectPs.executeQuery();
            if (rs.next()) {
                return rs.getInt("current_attempt_no");
            }
            return 1;
        } finally {
            Common.safeClose(rs);
            Common.safeClose(ensurePs);
            Common.safeClose(selectPs);
        }
    }

    private static String buildManifestJson(
        int pairId,
        int attemptNo,
        int provenance,
        boolean finalized,
        Integer sourceStatusCode
    ) {
        JobPair pair = getPair(pairId);
        if (pair == null) {
            return null;
        }

        Job job = Jobs.get(pair.getJobId());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("manifestVersion", "1.0.0");
        manifest.put("schemaVersion", 1);
        manifest.put("pairId", pairId);
        manifest.put("attemptNo", attemptNo);
        manifest.put("jobId", pair.getJobId());

        Map<String, Object> provenanceMap = new LinkedHashMap<>();
        provenanceMap.put("type", getManifestProvenanceName(provenance));
        provenanceMap.put("generatedAt", new Timestamp(System.currentTimeMillis()).toString());
        manifest.put("provenance", provenanceMap);

        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("backendType", EnvironmentConfig.getBackendType());
        execution.put("pairStatusCode", pair.getStatus().getCode().getVal());
        execution.put("finalized", finalized);
        if (sourceStatusCode != null) {
            execution.put("sourceStatusCode", sourceStatusCode);
        }
        if (job != null) {
            execution.put("benchmarkingFramework", String.valueOf(job.getBenchmarkingFramework()));
            execution.put("cpuTimeout", job.getCpuTimeout());
            execution.put("wallclockTimeout", job.getWallclockTimeout());
            execution.put("maxMemory", job.getMaxMemory());
        }
        manifest.put("execution", execution);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("benchId", pair.getBench().getId());
        inputs.put("benchName", pair.getBench().getName());
        if (!pair.getStages().isEmpty()) {
            JoblineStage stage = pair.getStages().get(0);
            if (stage.getSolver() != null) {
                inputs.put("solverId", stage.getSolver().getId());
                inputs.put("solverName", stage.getSolver().getName());
            }
            if (stage.getConfiguration() != null) {
                inputs.put("configId", stage.getConfiguration().getId());
                inputs.put("configName", stage.getConfiguration().getName());
            }
        }
        manifest.put("inputs", inputs);

        List<String> warnings = new ArrayList<>();
        if (provenance == MANIFEST_PROVENANCE_DERIVED_LEGACY) {
            warnings.add("legacy-derived-manifest");
        }
        manifest.put("warnings", warnings);
        return gson.toJson(manifest);
    }

    private static String getSha256(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(raw.getBytes(StandardCharsets.UTF_8));
            return Hash.getHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            log.error("getSha256", e);
            return null;
        }
    }

    /**
     * Returns the current status code for a given job pair by reading directly
     * from the {@code job_pairs} table. This is a lightweight point-read
     * intended for use in guards where a full pair hydration would be wasteful
     * (e.g., before writing a transitional status update to avoid overwriting
     * an already-terminal state).
     *
     * <p>Returns {@link StatusCode#STATUS_UNKNOWN} (0) when the pair is not
     * found or a database error occurs. Callers should treat a 0 return value
     * as "status could not be determined" and act conservatively.
     *
     * @param pairId the id of the pair to query
     * @return the current {@code status_code} column value, or 0 on error
     */
    public static int getPairStatusCode(int pairId) {
        return getPairStatusLookup(pairId).getStatusCode();
    }

    private static boolean isTerminalStatusCode(int statusCode) {
        return !StatusCode.toStatusCode(statusCode).incomplete();
    }
}
