package org.starexec.data.database;

import com.google.common.collect.ImmutableSet;
import org.apache.commons.io.FileUtils;
import org.starexec.constants.R;
import org.starexec.data.to.*;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.data.to.enums.ConfigXmlAttribute;
import org.starexec.data.to.pipelines.JoblineStage;
import org.starexec.data.to.pipelines.PairStageProcessorTriple;
import org.starexec.data.to.tuples.ConfigAttrMapPair;
import org.starexec.data.to.tuples.PairIdJobId;
import org.starexec.logger.StarLogger;
import org.starexec.util.Util;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.sql.*;
import java.util.*;
import java.util.Map.Entry;
import java.nio.charset.StandardCharsets;

/**
 * Contains handles on database queries for retrieving and updating job pairs.
 */
public class JobPairs {
	private static final StarLogger log = StarLogger.getLogger(JobPairs.class);

	private static void addJobPairInputs(List<JobPair> pairs, Connection con) {
		final String methodName = "addJobPairInputs";
		PreparedStatement ps = null;
		int batchCounter = 0;
		int totalPairsSubmitted = 0;
		try {
			ps = con.prepareStatement("SELECT starexec.AddJobPairInput(?, ?, ?)");

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
								"Submitting batch of " + batchSize + " inputs. Total pairs submitted: " + totalPairsSubmitted
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
						"Submitting final batch of " + batchCounter + " inputs. Total pairs submitted: " + totalPairsSubmitted
				);
				ps.executeBatch();
			}
		} catch (Exception e) {
			log.error(methodName, "Exception occurred while adding job pair inputs.", e);
		} finally {
			Common.safeClose(ps);
		}
	}

	public static Optional<String> populateConfigIdsToSolversMapAndJobPairsForJobXMLUpload(
			final String rootName, final int userId, final Map<Integer, Benchmark> accessibleCachedBenchmarks,
			final HashMap<Integer, Solver> configIdsToSolvers, final Job job, final int spaceId,
			final HashSet<String> jobRootPaths, final ConfigAttrMapPair configAttrMapPair, final NodeList jobPairs
	) {
		final String methodName = "populateJobPairsForJobXMLUpload";
		Connection con = null;

		// Benchmarks the user can see that we've already seen.
		try {
			con = Common.getConnection();

			//we now iterate through all the job pair elements and add them all to the job
			final int jobPairsLength = jobPairs.getLength();
			for (int i = 0; i < jobPairsLength; i++) {
				final Node jobPairNode = jobPairs.item(i);
				if (jobPairNode.getNodeType() == Node.ELEMENT_NODE) {
					final Element jobPairElement = (Element) jobPairNode;
					final JobPair jobPair = new JobPair();
					final int benchmarkId = Integer.parseInt(jobPairElement.getAttribute("bench-id"));
					final int configId = getConfigIdFromElement(jobPairElement, configAttrMapPair);
					//final int configId = Integer.parseInt(jobPairElement.getAttribute("config-id"));
					String path = jobPairElement.getAttribute("job-space-path");
					if (path.isEmpty()) {
						path = rootName;
					}
					jobPair.setPath(path);
					if (path.contains(R.JOB_PAIR_PATH_DELIMITER)) {
						jobRootPaths.add(path.substring(0, path.indexOf(R.JOB_PAIR_PATH_DELIMITER)));
					} else {
						jobRootPaths.add(path);
					}

					Benchmark b = null;
					//permissions check on the benchmark for this job pair
					if (accessibleCachedBenchmarks.containsKey(benchmarkId)) {
						b = accessibleCachedBenchmarks.get(benchmarkId);
					} else {
						b = Benchmarks.get(con, benchmarkId, false);
						if (b == null) {
							Benchmark errorBench = Benchmarks.get(con, benchmarkId, true, true);
							if (errorBench == null) {
								return Optional.of("Found null reference to benchmark: " + benchmarkId);
							} else if (errorBench.isDeleted()) {
								return Optional.of(errorBench.getName() + " has been deleted by it's user.");
							} else if (errorBench.isRecycled()) {
								return Optional.of(errorBench.getName() + " has been recycled by it's user.");
							} else {
								return Optional.of("Unknown problem with benchmark: " + benchmarkId);
							}
						}
						if (!Permissions.canUserSeeBench(con, benchmarkId, userId)) {
							return Optional.of("You do not have permission to see benchmark " + benchmarkId);
						}

						// Cache the benchmark
						accessibleCachedBenchmarks.put(benchmarkId, b);
					}
					jobPair.setBench(b);
					if (!configIdsToSolvers.containsKey(configId)) {
						//permissions check on the solver for the pair. Configurations do
						//not have permissions by themselves-- their permissions are identical to the solver
						// permissions
						Solver s = Solvers.getSolverByConfig(con, configId, true);
						if (s == null) {
							return Optional.of("Found null reference to solver referenced by config id: " + configId);
						}
						if (s.isDeleted() || s.isRecycled()) {
							return Optional.of("This solver associated with config " + configId +
													   " has been deleted or recycled, solverId: " + s.getId());
						}

						if (!Permissions.canUserSeeSolver(con, s.getId(), userId)) {
							return Optional.of("You do not have permission to see the solver " + s.getId());
						}

						s.addConfiguration(Solvers.getConfiguration(con, configId));
						configIdsToSolvers.put(configId, s);
					}
					Solver s = configIdsToSolvers.get(configId);

					//JobPair elements are for pairs with exactly one stage, so we create a stage
					//to house the solver and benchmark
					JoblineStage stage = new JoblineStage();
					stage.setStageNumber(1);
					stage.setSolver(s);
					stage.setConfiguration(s.getConfigurations().get(0));

					jobPair.addStage(stage);
					//the primary stage is the one we just added
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

	public static Integer getConfigIdFromElement(Element element, ConfigAttrMapPair configAttrMapPair) {
		String attribute = element.getAttribute(configAttrMapPair.attribute.attribute);
		if (configAttrMapPair.attribute == ConfigXmlAttribute.NAME) {
			// The attribute should be the config name.
			Map<String, Integer> configNameToId = configAttrMapPair.configNameToId;
			if (configNameToId.containsKey(attribute)) {
				return configNameToId.get(attribute);
			} else {
				throw new IllegalStateException(
						"There is no config with the name, " + attribute + ", in the uploaded solver.");
			}
		} else {
			// The attribute should be the id of the config.
			return Integer.parseInt(attribute);
		}
	}

	/**
	 * Retrieves all the inputs to the given pair from the jobpair_inputs table. Inputs will be ordered by their input
	 * numbers (in other words, first input, second input, and so on)
	 *
	 * @param pairId
	 * @param con An open database connection to make calls on
	 * @return A list of strings pointing to the inputs for this pair, or null on error.
	 */
	public static List<String> getJobPairInputPaths(int pairId, Connection con) {
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			ps = con.prepareStatement("SELECT * FROM starexec.GetJobPairInputPaths(?)");
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
	 * @param con The open connection to make the call on
	 */
	private static void addJobPairStages(List<JobPair> pairs, Connection con) {
		final String methodName = "addJobPairStages";
		PreparedStatement ps = null;
		int totalPairsSubmitted = 0;
		try {
			int batchCounter = 0;
			ps = con.prepareStatement("SELECT starexec.AddJobPairStage(?, ?, ?, ?, ?, ?, ?, ?, ?)");

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
					ps.setBoolean(4, Objects.equals(pair.getPrimaryStageNumber(), stage.getStageNumber()));
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
								"Submitting batch of " + batchSize + ", total pairs submitted: " + totalPairsSubmitted
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
						"Submitting batch of " + batchCounter + ", total pairs submitted: " + totalPairsSubmitted
				);
					ps.executeBatch();
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(ps);
		}
	}

	public static void addJobPairs(int jobId, List<JobPair> pairs) {
		final String methodName = "addJobPairs";

		Connection con = null;
		try {
			con = Common.getConnection();
			Common.beginTransaction(con);
			log.entry(methodName);
			boolean success = incrementTotalJobPairsForJob(jobId, pairs.size(), con);
			if (!success) {
				log.error(methodName, "Failed to increment total job pairs for jobId: " + jobId);
				return;
			}
			log.debug(methodName, "Successfully incremented total job pairs for jobId: " + jobId);

			addJobPairs(con, jobId, pairs);
			log.exit(methodName);
		} catch (SQLException e) {
			log.error(methodName, "SQL Exception occurred while adding job pairs for jobId: " + jobId, e);
		} finally {
			Common.endTransaction(con);
			Common.safeClose(con);
		}
	}

	/**
	 * Adds a job pair record to the database. This is a helper method for the Jobs.add method
	 *
	 * @param con The connection the update will take place on
	 * @return True if the operation was successful
	 */
	protected static boolean addJobPairs(Connection con, int jobId, List<JobPair> pairs) {
		final String methodName = "addJobPairs";
		log.entry(methodName);
		PreparedStatement stmt = null;
		ResultSet rs = null;
		try {
			stmt = con.prepareStatement("SELECT AddJobPair(?, ?, ?, ?, ?, ?, ?)");
			int pairsProcessed = 0;
			for (JobPair pair : pairs) {
				pair.setJobId(jobId);
				stmt.setInt(1, jobId);
				stmt.setInt(2, pair.getBench().getId());
				stmt.setShort(3, (short) StatusCode.STATUS_PENDING_SUBMIT.getVal());

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

			log.debug(methodName, "Adding job pair stages.");
			addJobPairStages(pairs, con);
			log.debug(methodName, "Adding job pair inputs.");
			addJobPairInputs(pairs, con);
			return true;
		} catch (Exception e) {
			log.error(methodName, "Exception occurred while adding job pairs to the database.", e);
		} finally {
			Common.safeClose(rs);
			Common.safeClose(stmt);
		}
		return false;
	}

	/**
	 * Finds the standard output of a job pair and returns it as a string. Null is returned if the output doesn't exist
	 * or cannot be found
	 *
	 * @param pairId The pair to get output for
	 * @param stageNumber The stage to get pair info for
	 * @param limit The maximum number of lines to return
	 * @return All console output from a job pair run for the given pair
	 */
	public static Optional<String> getStdOut(int pairId, int stageNumber, int limit) throws IOException {
		String stdoutPath = JobPairs.getStdout(pairId, stageNumber);
		return Util.readFileLimited(new File(stdoutPath), limit);
	}

	/**
	 * Returns all pairs that are waiting on post processing. Returns a hashmap mapping job pair IDs to post processors
	 *
	 * @return A list of triples containing pair id, stage number, post processor id that represents all stages that
	 * need to be processed.
	 */
	public static List<PairStageProcessorTriple> getAllPairsForProcessing() {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetPairsToBeProcessed(?)");
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
	 * Updates the total_pairs column for the given job by summing it with the given increment
	 *
	 * @param jobId The ID of the job to update
	 * @param increment The amount to change total_pairs by. Note that if this is negative it means the total_pairs
	 * column will decrease
	 * @param con The open connection to make the call on
	 * @return true on success and false otherwise
	 */
	public static boolean incrementTotalJobPairsForJob(int jobId, int increment, Connection con) {
		PreparedStatement ps = null;
		try {
			ps = con.prepareStatement("SELECT starexec.IncrementTotalJobPairsForJob(?, ?)");
			ps.setInt(1, jobId);
			ps.setInt(2, increment);
			ps.execute();
			return true;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
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
	public static void deleteJobPairs(List<JobPair> jobPairs) throws SQLException {
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
	private static void deleteJobPair(Connection con, JobPair pairToDelete) throws SQLException {
		if (pairToDelete == null) {
			throw new NullPointerException("Input JobPair was null.");
		}

		PreparedStatement ps = null;
		try {
			ps = con.prepareStatement("SELECT starexec.DeleteJobPair(?)");
			ps.setInt(1, pairToDelete.getId());
			ps.execute();
		} finally {
			Common.safeClose(ps);
		}
	}

	/**
	 * Post processes the given pair with the given processor ID, add the properties to the pair attributes table, and
	 * removes the pair from the processing job pairs table
	 *
	 * @param pairId The ID of the pair to process
	 * @param stageNumber
	 * @param processorId The ID of the processor to use
	 */
	public static void postProcessPair(int pairId, int stageNumber, int processorId) {
		Connection con = null;
		try {
			Properties props = runPostProcessorOnPair(pairId, stageNumber, processorId);
			con = Common.getConnection();
			Common.beginTransaction(con);
			JobPairs.addJobPairAttributes(pairId, stageNumber, props, con);
			JobPairs.setPairStatus(pairId, StatusCode.STATUS_COMPLETE.getVal(), con);
			JobPairs.setPairStageStatus(pairId, StatusCode.STATUS_COMPLETE.getVal(), stageNumber, con);
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
	 * Runs the given post processor on the given pair stage and returns the properties that were obtained
	 *
	 * @param pairId The ID of the pair in question
	 * @param processorId The ID of the processor in question
	 * @return The properties on success, or null otherwise
	 */
	private static Properties runPostProcessorOnPair(int pairId, int stageNumber, int processorId) {
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
			String benchPath = new File(sandbox, new File(pair.getBench().getPath()).getName()).getAbsolutePath();
			String outputPath = new File(sandbox, output.getName()).getAbsolutePath();
			File working = new File(sandbox, new File(p.getFilePath()).getName());


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
	 * @param con The connection to make the update on
	 * @param pairId The id of the job pair the attribute is for
	 * @param key The key of the attribute
	 * @param val The value of the attribute
	 * @author Tyler Jensen
	 */
	protected static void addJobPairAttr(Connection con, int pairId, int stageId, String key, String val) {
		PreparedStatement ps = null;
		try {
			ps = con.prepareStatement("SELECT starexec.AddJobAttr(?, ?, ?, ?)");
			ps.setInt(1, pairId);

			ps.setString(2, key);
			ps.setString(3, val);
			ps.setInt(4, stageId);
			ps.execute();
		} catch (Exception e) {
			log.error("addJobPairAttr", e);
		} finally {
			Common.safeClose(ps);
		}
	}

	/**
	 * Adds the list of attributes to the given job pair. If old attributes have the same keys as new ones, the old
	 * ones
	 * are replaced
	 *
	 * @param pairId The ID of the pair to add attributes to
	 * @param stageId The ID of the stage to add attributes for.
	 * @param attributes The key/value attributes
	 * @param con The open connection to make the call on
	 * @return True on success, false on error
	 */
	public static boolean addJobPairAttributes(int pairId, int stageId, Properties attributes, Connection con) {
		try {
			// For each attribute (key, value)...
			log.info("Adding " + attributes.entrySet().size() + " attributes to job pair " + pairId);
			for (Entry<Object, Object> keyVal : attributes.entrySet()) {
				// Add the attribute to the database
				JobPairs.addJobPairAttr(con, pairId, stageId, (String) keyVal.getKey(), (String) keyVal.getValue());
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
	 * @param pairId The id of the job pair the attribute is for
	 * @param stageId the ID of the stage to add attributes to
	 * @param attributes The attributes to add to the job pair
	 * @return True if the operation was a success, false otherwise
	 * @author Tyler Jensen
	 */
	public static boolean addJobPairAttributes(int pairId, int stageId, Properties attributes) {
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
	protected static List<JobPair> filterPairsByType(List<JobPair> pairs, String type, int stageNumber) {

		log.debug("filtering pairs by type with type = " + type);
		List<JobPair> filteredPairs = new ArrayList<>();

		switch (type) {
		case "incomplete":
			for (JobPair jp : pairs) {
				if (jp.getStageFromNumber(stageNumber).getStatus().getCode().statIncomplete()) {
					filteredPairs.add(jp);
				}
			}
			break;
		case "resource":
			for (JobPair jp : pairs) {
				if (jp.getStageFromNumber(stageNumber).getStatus().getCode().resource()) {
					filteredPairs.add(jp);
				}
			}
			break;
		case "failed":
			for (JobPair jp : pairs) {
				if (jp.getStageFromNumber(stageNumber).getStatus().getCode().failed()) {
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
				if (jp.getStageFromNumber(stageNumber).getStatus().getCode().statComplete()) {
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
	 * @return -1 == pair is not complete (as in, does not have STATUS_COMPLETE) 0 == pair is correct 1 == pair is
	 * incorrect 2 == pair is unknown
	 */
	public static int isPairCorrect(JoblineStage stage) {
		StatusCode statusCode = stage.getStatus().getCode();

		if (statusCode.getVal() == StatusCode.STATUS_COMPLETE.getVal()) {
			if (stage.getAttributes() != null) {
				Properties attrs = stage.getAttributes();
				//log.debug("expected = "+attrs.get(R.EXPECTED_RESULT));
				//log.debug("actual = "+attrs.get(R.STAREXEC_RESULT));
				if (attrs.containsKey(R.STAREXEC_RESULT) && attrs.get(R.STAREXEC_RESULT).equals(R.STAREXEC_UNKNOWN)) {
					//don't know the result, so don't mark as correct or incorrect.
					return 2;
				} else if (attrs.containsKey(R.EXPECTED_RESULT) &&
						!attrs.get(R.EXPECTED_RESULT).equals(R.STAREXEC_UNKNOWN)) {
					if (!attrs.containsKey(R.STAREXEC_RESULT) ||
							!attrs.get(R.STAREXEC_RESULT).equals(attrs.get(R.EXPECTED_RESULT))) {
						//the absence of a result, or a nonmatching result, is counted as wrong
						return 1;
					} else {
						return 0;
					}
				} else {
					//if the attributes don't have an expected result, we will mark as unknown
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
	protected static List<SolverComparison> filterComparisons(List<SolverComparison> comparisons, String searchQuery) {
		//no filtering is necessary if there's no query
		if (Util.isNullOrEmpty(searchQuery)) {
			return comparisons;
		}

		searchQuery = searchQuery.toLowerCase();
		List<SolverComparison> filteredComparisons = new ArrayList<>();
		for (SolverComparison c : comparisons) {
			try {
				if (c.getBenchmark().getName().toLowerCase().contains(searchQuery)) {
					filteredComparisons.add(c);
				}
			} catch (Exception e) {
				log.error(e.getMessage(), e);
			}
		}

		return filteredComparisons;
	}

	/**
	 * Filters a list of job pairs against some search query. The query is compared to solver, benchmark, and config
	 * names, as well as integer status code and result. The job pair is not filtered if the query is a
	 * case-insensitive
	 * substring of any of those names
	 *
	 * @param pairs The pairs to filter
	 * @param searchQuery The query
	 * @return A filtered list of job pairs
	 * @author Eric Burns
	 */

	protected static List<JobPair> filterPairs(List<JobPair> pairs, String searchQuery, int stageNumber) {
		//no filtering is necessary if there's no query
		if (Util.isNullOrEmpty(searchQuery)) {
			return pairs;
		}

		searchQuery = searchQuery.toLowerCase();
		List<JobPair> filteredPairs = new ArrayList<>();
		for (JobPair jp : pairs) {
			JoblineStage stage = jp.getStageFromNumber(stageNumber);
			try {
				if (jp.getBench().getName().toLowerCase().contains(searchQuery) ||
						String.valueOf(stage.getStatus().getCode().getVal()).equals(searchQuery) ||
						stage.getSolver().getName().toLowerCase().contains(searchQuery) ||
						stage.getConfiguration().getName().toLowerCase().contains(searchQuery) ||
						stage.getStarexecResult().contains(searchQuery)) {

					filteredPairs.add(jp);
				}
			} catch (Exception e) {
				log.warn("filterPairs", "JobPair: " +  jp.getId(), e);
			}
		}

		return filteredPairs;
	}

	/**
	 * Retrieves all attributes (key/value) of the given job pair. Returns a mapping of those attributes to stages
	 * based
	 * on the jobpair_stage_data.stage_number
	 *
	 * @param con The connection to make the query on
	 * @param pairId The id of the pair to get the attributes of
	 * @return The properties object which holds all the pair's attributes
	 * @author Tyler Jensen
	 */
	protected static HashMap<Integer, Properties> getAttributes(Connection con, int pairId) {
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
				props.get(joblineStageNumber).put(results.getString("attr_key"), results.getString("attr_value"));
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
	 * Gets the path to the output file  for this pair.
	 *
	 * @param pairId The id of the pair to get the filepath for
	 * @return The string path, or null on failure
	 * @author Eric Burns
	 */

	public static String getLogPath(int pairId) {
		return getLogFilePath(getFilePathInfo(pairId));
	}

	/**
	 * Populates a job pair with just enough information to find the file path. The pair will be returned with a single
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
			ps = con.prepareStatement("SELECT * FROM starexec.getJobPairFilePathInfo(?)");
			ps.setInt(1, pairId);
			results = ps.executeQuery();
			if (results.next()) {
				JobPair pair = new JobPair();

				pair.addStage(new JoblineStage());

				pair.setPrimaryStageNumber(results.getInt("stage_number"));
				pair.getStages().get(0).setStageNumber(pair.getPrimaryStageNumber());
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
	 * Gets the path to the directory containing all output files for this job. For jobs created before solver
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
	 * Returns a list of files representing paths to both a pair's standard output and additional output directories
	 * . If
	 * the given file is a directory, it is returned alone. Otherwise, it is interpreted as the single stdout file and
	 * the additional directory is returned as well if it exists.
	 *
	 * @param pairId
	 * @param stdout
	 * @return
	 */
	private static List<File> getOutputPathsFromStdout(int pairId, File stdout) {
		List<File> files = new ArrayList<>();
		files.add(stdout);
		// if we need the other directory
		if (!stdout.isDirectory()) {
			File otherOutput = new File(stdout.getParentFile(), pairId + "_output");
			if (otherOutput.exists()) {
				files.add(otherOutput);
			}
		}
		return files;
	}

	/**
	 * Returns a list of files representing paths to both a pair's standard output and additional output directories
	 * . If
	 * these two things are contained in a single top level directory, as they are when joblines are used, only that
	 * directory is returned. No extra output dir is returned if extra outputs are not used.
	 *
	 * @param pairId The pair to get output for
	 * @return Paths to all output for this job. has no output yet
	 */
	public static List<File> getOutputPaths(int pairId) {
		File stdout = new File(getStdout(pairId));
		return getOutputPathsFromStdout(pairId, stdout);
	}

	/**
	 * Same as getOutputPaths(pairId), except the given pair is expected to have all relevant fields populated and will
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
	 * @return The absolute file path to the output for the given stage of the given pair
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
			ps = con.prepareStatement("SELECT starexec.RemovePairFromCompletedTable(?)");
			ps.setInt(1, pairId);
			ps.execute();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
	}

	/**
	 * Returns the log of a job pair by reading in the physical log file into a string.
	 *
	 * @param pairId The id of the pair to get the log for
	 * @return The log of the job run
	 */
	public static String getJobLog(int pairId) {
		try {

			String logPath = JobPairs.getLogPath(pairId);

			File logFile = new File(logPath);

			if (logFile.exists()) {
				return FileUtils.readFileToString(logFile, StandardCharsets.UTF_8);
			}
		} catch (Exception e) {
			log.warn(e.getMessage(), e);
		}

		return null;
	}

	/**
	 * Returns the absolute path to where the log for a pair is stored given the pair.
	 *
	 * @param pair
	 * @return The absolute path to the log file for the given pair, or null if it could not be found
	 */
	public static String getLogFilePath(JobPair pair) {
		try {

			File file = new File(Jobs.getLogDirectory(pair.getJobId()));
			file = new File(file, String.valueOf(pair.getJobSpaceId()));
			file = new File(file, pair.getId() + ".txt");
			log.trace("found this log path " + file.getAbsolutePath());
			return file.getAbsolutePath();
		} catch (Exception e) {
			log.error("getLogFilePath", e);
		}
		return null;
	}

	/**
	 * Retrieves the output of a single stage of the given job pair. Requires that the jobId, path, solver name, config
	 * name, and bench names of the PRIMARY STAGE be populated. The fields do NOT need to be populated for given stage,
	 * ONLY the primary stage
	 *
	 * @param pair
	 * @param stageNumber A number >=1 representing the stage of this pair
	 * @return The absolute file path to the output file for the given stage of the given pair
	 */
	public static String getFilePath(JobPair pair, int stageNumber) {
		String path = getPairStdout(pair); //this is the path to the top level directory of the pair

		File f = new File(path);
		if (f.isDirectory()) {
			//means this is a job created after stages were implemented
			return new File(f, stageNumber + ".txt").getAbsolutePath();
		} else {
			//if we get down here, it means that this pair did NOT use stages.
			return path;
		}
	}

	/**
	 * Gets the path to the directory that contains all the output files for every stage in this pair. For old pairs
	 * that do not have stages, simply returns the path to the single output file for this pair. Requires that the
	 * jobId, path, solver name, config name, and bench names be populated for the PRIMARY STAGES
	 *
	 * @param pair The pair to get the filepath for
	 * @return The string path, or null on failure
	 * @author Eric Burns
	 */

	//Note that this function tries several things due to supporting several layers of backwards compatibility
	public static String getPairStdout(JobPair pair) {
		try {
			final String path = Util.normalizeFilePath(pair.getBenchPath());
			File file = new File(Jobs.getDirectory(pair.getJobId()), path);

			if (!file.exists()) {        // if the job output could not be found
				File testFile = new File(file, pair.getPrimarySolver().getName());
				testFile = new File(testFile, pair.getPrimaryConfiguration().getName());
				testFile = new File(testFile, pair.getBench().getName());
				if (testFile.exists()) {  //check the alternate path some pairs are still stored at
					FileUtils.copyFile(testFile, file);
					if (file.exists()) {
						testFile.delete();
					}
				}
			} else if (file.isFile()) { // if it is a file, we have already got the full path
				return file.getAbsolutePath();
			}

			//before solver pipelines, pairs were stored as a single file titled <pairid>.txt . If that file exists,
			// returns it
			File testFile = new File(file, pair.getId() + ".txt");

			if (testFile.exists()) {
				return testFile.getAbsolutePath();
			}

			//otherwise, this is a modern job, and we return a directory with the name of the pair id

			file = new File(file, String.valueOf(pair.getId()));

			return file.getAbsolutePath();
		} catch (Exception e) {
			log.error("getPairStdout", e);
		}
		return null;
	}

	/**
	 * Gets the job pair with the given id non-recursively (Worker node, status, benchmark and solver will NOT be
	 * populated). Only the primary stage is created! To get all the stages, you need to call getPairDetailed
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
			ps = con.prepareStatement("SELECT * FROM starexec.GetJobPairById(?)");
			ps.setInt(1, pairId);
			results = ps.executeQuery();

			if (results.next()) {
				JobPair jp = JobPairs.resultToPair(results);
				jp.addStage(new JoblineStage()); // just add an empty stage that we can populate below
				jp.getStages().get(0).setStageNumber(jp.getPrimaryStageNumber());

				jp.getNode().setId(results.getInt("node_id"));
				jp.getStatus().setCode(results.getInt("status_code"));
				jp.getBench().setId(results.getInt("bench_id"));
				jp.getBench().setName(results.getString("bench_name"));
				jp.getPrimarySolver().getConfigurations().add(new Configuration(results.getInt("config_id")));
				jp.getPrimarySolver().setId(results.getInt("solver_id"));
				jp.getPrimarySolver().setName(results.getString("solver_name"));
				jp.getPrimarySolver().getConfigurations().get(0).setName(results.getString("config_name"));
				jp.getStages().get(0).setConfiguration(jp.getPrimarySolver().getConfigurations().get(0));
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
	 * Gets the job pair with the given id recursively (Worker node, status, benchmark and solver WILL be populated)
	 *
	 * @param con The connection to make the query on
	 * @param pairId The id of the pair to get
	 * @return The job pair object with the given id.
	 * @author Tyler Jensen
	 */
	protected static JobPair getPairDetailed(Connection con, int pairId) {
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			ps = con.prepareStatement("SELECT * FROM starexec.GetJobPairById(?)");
			ps.setInt(1, pairId);
			results = ps.executeQuery();

			JobPair jp = null;
			// first, we get the top level info from the job_pairs table
			if (results.next()) {
				jp = JobPairs.resultToPair(results);
				jp.setCompletionId(results.getInt("completion_id"));
				jp.setNode(Cluster.getNodeDetails(con, results.getInt("node_id")));
				jp.setBench(Benchmarks.get(con, results.getInt("bench_id"), true));

				Status s = new Status();
				s.setCode(results.getInt("status_code"));
				jp.setStatus(s);
				jp.setJobSpaceName(results.getString("job_space_name"));
			} else {
				//couldn't find the pair for some reason
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

	private static void populateJobPairStagesDetailed(JobPair jp, Connection con) throws SQLException {
		PreparedStatement ps = null;
		ResultSet results = null;

		try {
			ps = con.prepareStatement("SELECT * FROM starexec.GetJobPairStagesById(?)");
			ps.setInt(1, jp.getId());
			results = ps.executeQuery();
			//next, we get data at the stage level
			while (results.next()) {
				JoblineStage stage = resultToStage(results);
				int configId = results.getInt("config_id");
				int solverId = results.getInt("solver_id");
				String configName = results.getString("config_name");
				String solverName = results.getString("solver_name");
				//means this stage has no configuration
				if (configId == -1) {
					stage.setNoOp(true);
				} else if (configId > 0) {
					Solver solver = Solvers.getSolverByConfig(con, configId, true);
					Configuration c = Solvers.getConfiguration(configId);

					//this can happen if the pair references a deleted solver
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
			//last, we get attributes for everything
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
	 * Gets the job pair with the given id recursively (Worker node, status, benchmark and solver WILL be populated)
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

	public static List<JobPair> getPairsInJobContainingBenchmark(int jobId, int benchmarkId) throws SQLException {
		java.sql.Connection con = null;
		java.sql.PreparedStatement ps = null;
		java.sql.ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetJobPairsInJobContainingBenchmark(?, ?)");
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

	public static List<JobPair> getPairsInJobContainingSolver(int jobId, int solverId) throws SQLException {
		java.sql.Connection con = null;
		java.sql.PreparedStatement ps = null;
		java.sql.ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetJobPairsInJobContainingSolver(?, ?)");
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
	 * Extracts query information into a JoblineStage. Does NOT get deep information like solver and configuration
	 *
	 * @param result
	 * @return
	 * @throws Exception
	 */
	protected static JoblineStage resultToStage(ResultSet result) throws SQLException {
		JoblineStage stage = new JoblineStage();

		stage.setStageNumber(getIntSafe(result, "jobpair_stage_data.stage_number", "stage_number"));
		stage.setWallclockTime(getDoubleSafe(result, "jobpair_stage_data.wallclock", "wallclock"));
		stage.setCpuUsage(getDoubleSafe(result, "jobpair_stage_data.cpu", "cpu"));
		stage.setUserTime(getDoubleSafe(result, "jobpair_stage_data.user_time", "user_time"));
		stage.setSystemTime(getDoubleSafe(result, "jobpair_stage_data.system_time", "system_time"));
		stage.setMaxVirtualMemory(getDoubleSafe(result, "jobpair_stage_data.max_vmem", "max_vmem"));
		stage.setMaxResidenceSetSize(getDoubleSafe(result, "jobpair_stage_data.max_res_set", "max_res_set"));
		stage.setStageId(getIntSafe(result, "jobpair_stage_data.stage_id", "stage_id"));
		stage.getStatus().setCode(getIntSafe(result, "jobpair_stage_data.status_code", "status_code"));
		return stage;
	}

	/**
	 * Helper method to extract information from a query for job pairs
	 *
	 * @param result The resultset that is the results from querying for job pairs
	 * @return A job pair object populated with data from the result set
	 */
	protected static JobPair resultToPair(ResultSet result) throws SQLException {

		JobPair jp = new JobPair();

		jp.setId(getIntSafe(result, "job_pairs.id", "id"));
		jp.setJobId(getIntSafe(result, "job_pairs.job_id", "job_id"));
		jp.setBackendExecId(getIntSafe(result, "job_pairs.sge_id", "sge_id"));
		jp.setQueueSubmitTime(getTimestampSafe(result, "job_pairs.queuesub_time", "queuesub_time"));
		jp.setStartTime(getTimestampSafe(result, "job_pairs.start_time", "start_time"));
		jp.setEndTime(getTimestampSafe(result, "job_pairs.end_time", "end_time"));
		// Populate basic benchmark info.
		jp.getBench().setId(getIntSafe(result, "bench_id", "bench_id"));
		jp.getBench().setName(getStringSafe(result, "bench_name", "bench_name"));

		jp.getNode().setId(getIntSafe(result, "job_pairs.node_id", "node_id"));
		jp.getStatus().setCode(getIntSafe(result, "job_pairs.status_code", "status_code"));

		jp.setPath(getStringSafe(result, "job_pairs.path", "path"));
		jp.setJobSpaceId(getIntSafe(result, "job_pairs.job_space_id", "job_space_id"));
		jp.setPrimaryStageNumber(getIntSafe(result, "job_pairs.primary_jobpair_data", "primary_jobpair_data"));
		jp.setSandboxNum(getIntSafe(result, "job_pairs.sandbox_num", "sandbox_num"));
		//log.debug("getting job pair from result set for id " + jp.getId());
		return jp;
	}

	/** Helper getters that try multiple column labels (prefixed and unprefixed) and do safe conversions. */
	private static boolean columnExists(ResultSet rs, String col) throws SQLException {
		ResultSetMetaData md = rs.getMetaData();
		int cols = md.getColumnCount();
		for (int i = 1; i <= cols; i++) {
			if (col.equalsIgnoreCase(md.getColumnLabel(i)) || col.equalsIgnoreCase(md.getColumnName(i))) return true;
		}
		return false;
	}

	private static int getIntSafe(ResultSet rs, String... cols) throws SQLException {
		for (String c : cols) {
			if (columnExists(rs, c)) return rs.getInt(c);
		}
		throw new SQLException("None of the columns found: " + Arrays.toString(cols));
	}

	private static long getLongSafe(ResultSet rs, String... cols) throws SQLException {
		for (String c : cols) {
			if (columnExists(rs, c)) return rs.getLong(c);
		}
		throw new SQLException("None of the columns found: " + Arrays.toString(cols));
	}

	private static String getStringSafe(ResultSet rs, String... cols) throws SQLException {
		for (String c : cols) {
			if (columnExists(rs, c)) return rs.getString(c);
		}
		return null;
	}

	private static Timestamp getTimestampSafe(ResultSet rs, String... cols) throws SQLException {
		for (String c : cols) {
			if (columnExists(rs, c)) return rs.getTimestamp(c);
		}
		return null;
	}

	private static double getDoubleSafe(ResultSet rs, String... cols) throws SQLException {
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
		throw new SQLException("None of the columns found: " + Arrays.toString(cols));
	}

	/**
	 * Sets the status of a given job pair stage to the given status
	 *
	 * @param pairId The ID of the pair to update
	 * @param stageNumber The number of the stage to update
	 * @param statusCode The code to give the stage
	 * @param con An open database connection to make the call on
	 * @return True on success and false on error
	 */
	public static boolean setPairStageStatus(int pairId, int statusCode, int stageNumber, Connection con) {
		PreparedStatement ps = null;
		try {
			ps = con.prepareStatement("SELECT starexec.UpdatePairStageStatus(?, ?, ?)");
			ps.setInt(1, pairId);
			ps.setInt(2, stageNumber);
			ps.setShort(3, (short) statusCode);

			ps.execute();
			try { Common.safeClose(ps.getResultSet()); } catch (SQLException ignore) {}

			return true;
		} catch (Exception e) {
			log.debug("setPairStageStatus", e);
		} finally {
			Common.safeClose(ps);
		}
		return false;
	}

	/**
	 * Sets the status code of every stage that comes after the given stage to the given value
	 *
	 * @param pairId
	 * @param statusCode
	 * @param stageNumber
	 * @param con
	 * @return True on success and false otherwise
	 */
	public static boolean setLaterPairStageStatus(int pairId, int statusCode, int stageNumber, Connection con) {
		PreparedStatement ps = null;
		try {
			ps = con.prepareStatement("SELECT starexec.UpdateLaterStageStatuses(?, ?, ?)");
			ps.setInt(1, pairId);
			ps.setInt(2, stageNumber);
			ps.setShort(3, (short) statusCode);

			ps.execute();
			try { Common.safeClose(ps.getResultSet()); } catch (SQLException ignore) {}

			return true;
		} catch (Exception e) {
			log.debug(e.getMessage(), e);
		} finally {
			Common.safeClose(ps);
		}
		return false;
	}

	/**
	 * Sets the status code of every stage that comes after the given stage to the given value
	 *
	 * @param pairId
	 * @param statusCode
	 * @param stageNumber
	 * @return True on success and false on error
	 */

	public static boolean setLaterPairStageStatus(int pairId, int statusCode, int stageNumber) {
		Connection con = null;
		try {
			con = Common.getConnection();

			return setLaterPairStageStatus(pairId, statusCode, stageNumber, con);
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
	public static boolean setStatusForPairAndStages(int pairId, int statusCode) {
		Connection con = null;
		try {
			con = Common.getConnection();
			return setPairStatus(pairId, statusCode, con) && setAllPairStageStatus(pairId, statusCode, con);
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
	 * @param con An open database connection to make calls on
	 * @return True on success and false on error
	 */
	public static boolean setAllPairStageStatus(int pairId, int statusCode, Connection con) {
		return setLaterPairStageStatus(pairId, statusCode, -1, con);
	}

	/**
	 * Sets the disk_size for the given job pair to 0, updating the jobpair_stage_data, jobs, and users tables
	 *
	 * @param jobPairId
	 */
	public static void setJobPairDiskSizeToZero(int jobPairId) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.RemoveJobPairDiskSize(?)");
			ps.setInt(1, jobPairId);
			ps.execute();
			try { Common.safeClose(ps.getResultSet()); } catch (SQLException ignore) {}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
	}

	/**
	 * Gets pair ids, node ids, and job ids, that have been enqueued longer than a given amount of time.
	 *
	 * @param minutes if a pair has been enqueued longer than this number of minutes it will be returned with its node
	 * and job ids.
	 * @return the pair ids and their node ids and jobs ids that have been enqueued longer than the given amount of
	 * time.
	 * @throws SQLException if something goes wrong in the database.
	 */
	public static ImmutableSet<PairIdJobId> getPairsEnqueuedLongerThan(int minutes) throws SQLException {
		java.sql.Connection con = null;
		java.sql.PreparedStatement ps = null;
		java.sql.ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetPairsEnqueuedLongerThan(?)");
			ps.setInt(1, minutes);
			results = ps.executeQuery();
			Set<PairIdJobId> brokenPairs = new HashSet<>();
			while (results.next()) {
				brokenPairs.add(new PairIdJobId(results.getInt("pair_id"), results.getInt("job_id")));
			}
			return ImmutableSet.copyOf(brokenPairs);
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Gets nodes that may have had pairs enqueued longer than the given amount of time without setting them to
	 * "running". The SQL procedure gets the queues for pairs that have been enqueued for the amount of time without
	 * being set to running and then gets the nodes from that queue that haven't been running jobs in that time.
	 *
	 * @param minutes the time that nodes must have been idle for to qualify as broken.
	 * @return the ids of the identified nodes.
	 * @throws SQLException if there is a database error.
	 */
	public static ImmutableSet<Integer> getNodesThatMayHavePairsEnqueuedLongerThan(int minutes) throws SQLException {
		java.sql.Connection con = null;
		java.sql.PreparedStatement ps = null;
		java.sql.ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetNodesThatMayHavePairsEnqueuedLongerThan(?)");
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
	public static boolean setPairStatus(int pairId, int statusCode, Connection con) {
		PreparedStatement ps = null;
		try {
			ps = con.prepareStatement("SELECT starexec.UpdatePairStatus(?, ?)");
			ps.setInt(1, pairId);
			ps.setShort(2, (short) statusCode);

			ps.executeUpdate();

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
	 * @param pairId the id of the pair to update the status of
	 * @param stageNumber The stage to update
	 * @param statusCode the status code to set for the pair
	 * @return True if the operation was a success, false otherwise
	 */
	public static boolean setPairStatus(int pairId, int stageNumber, int statusCode) {
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
	 * Updates the status_code for a pair. If the pair is being set to enqueued, also sets the pair's queue_sub_time
	 * . If
	 * the pair is being set to a completed status, the pair's completion entry is updated.
	 *
	 * @param pairId the id of the pair to update the status of
	 * @param statusCode the status code to set for the pair
	 * @return True if the operation was a success, false otherwise
	 */
	public static boolean setPairStatus(int pairId, int statusCode) {
		Connection con = null;

		try {
			con = Common.getConnection();
			return setPairStatus(pairId, statusCode, con);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
		}

		return false;
	}

	/**
	 * Reads all data for a specific queue from the jobpair_time_delta table and then clears the data from that queue
	 * all inside a single transaction.
	 *
	 * @param queueID The ID of the queue to get data for. If this is -1, gets and clears all data
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

			ps = con.prepareStatement("SELECT * FROM starexec.GetJobpairTimeDeltaData(?)");
			ps.setInt(1, queueID);
			results = ps.executeQuery();
			HashMap<Integer, Integer> data = new HashMap<>();
			while (results.next()) {
				data.put(results.getInt("user_id"), results.getInt("time_delta"));
			}
			Common.safeClose(ps);
			psClear = con.prepareStatement("SELECT starexec.ClearJobpairTimeDeltaData(?)");
			psClear.setInt(1, queueID);
			psClear.executeUpdate();

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
			ps.executeUpdate();

			return true;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}

		return false;
	}

	/**
	 * Updates the database to give the job pair with the given ID the given job space.
	 *
	 * @param jobPairId The ID of the job pair in question
	 * @param jobSpaceId The job space ID of the pair
	 * @param con The open connection to perform the update on
	 * @throws Exception
	 * @author Eric Burns
	 */

	public static void UpdateJobSpaces(int jobPairId, int jobSpaceId, Connection con) {
		PreparedStatement ps = null;
		try {
			ps = con.prepareStatement("SELECT starexec.UpdateJobSpaceId(?, ?)");
			ps.setInt(1, jobPairId);
			ps.setInt(2, jobSpaceId);
			ps.executeUpdate();
		} catch (Exception e) {
			log.error("UpdateJobSpaces", e);
		} finally {
			Common.safeClose(ps);
		}
	}

	/**
	 * Calls buildJobSpaceIdtoJobPairMapForJob, and then rounds all cpu and wallclock times before returning results
	 *
	 * @param job
	 * @return Identical to buildJobSpaceIdtoJobPairMapForJob, but with rounded times
	 */
	public static Map<Integer, List<JobPair>> buildJobSpaceIdToJobPairMapWithWallCpuTimesRounded(Job job) {
		Map<Integer, List<JobPair>> outputMap = buildJobSpaceIdToJobPairMapForJob(job);
		roundWallclockAndCpuTimesInJobSpaceIdToJobPairMap(outputMap);
		return outputMap;
	}

	private static void roundWallclockAndCpuTimesInJobSpaceIdToJobPairMap(
			Map<Integer, List<JobPair>> jobSpaceIdToJobPairMap
	) {
		for (Integer jobSpaceId : jobSpaceIdToJobPairMap.keySet()) {
			List<JobPair> jobPairs = jobSpaceIdToJobPairMap.get(jobSpaceId);
			for (JobPair jp : jobPairs) {
				jp.setPrimaryWallclockTime(Math.round(jp.getPrimaryWallclockTime() * 100) / 100.0);
				jp.setPrimaryCpuTime(Math.round(jp.getPrimaryCpuTime() * 100) / 100.0);
			}
		}
	}

	/**
	 * Builds a mapping of job space IDs to JobPairs in that JobSpace given the JobSpaces and JobPairs
	 *
	 * @param job The job to work on
	 * @return The mapping of job space IDs to in that job space
	 * @author Albert Giegerich
	 */
	public static Map<Integer, List<JobPair>> buildJobSpaceIdToJobPairMapForJob(Job job) {
		int jobId = job.getId();
		int primaryJobSpaceId = job.getPrimarySpace();
		List<JobSpace> jobSpaces = Spaces.getSubSpacesForJob(primaryJobSpaceId, true);
		jobSpaces.add(Spaces.getJobSpace(job.getPrimarySpace()));
		List<JobPair> allJobPairsInJob = Jobs.getDetailed(jobId, 0).getJobPairs();
		Map<Integer, List<JobPair>> jobSpaceIdToJobPairMap = new HashMap<>();
		for (JobSpace js : jobSpaces) {
			List<JobPair> jobPairsAssociatedWithJs = new ArrayList<>();
			for (JobPair jp : allJobPairsInJob) {
				if (jp.getJobSpaceId() == js.getId()) {
					jobPairsAssociatedWithJs.add(jp);
				}
			}
			if (!jobPairsAssociatedWithJs.isEmpty()) {
				jobSpaceIdToJobPairMap.put(js.getId(), jobPairsAssociatedWithJs);
			}
		}
		return jobSpaceIdToJobPairMap;
	}

	/**
	 * Given a list of JobPair objects that have their jobSpaceIds set, updates the database to reflect these new job
	 * space ids
	 *
	 * @param jobPairs The pairs to update
	 * @param con An open connection to make calls on
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
			JobPairs.UpdateStatus(pairId, Status.StatusCode.STATUS_KILLED.getVal());
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	/**
	 * Updates the status of the given job pair, replacing its current status code with the given one
	 *
	 * @param jobPairId The ID of the job pair in question
	 * @param status_code The new status code to assign to the job pair
	 */
	public static void UpdateStatus(int jobPairId, int status_code) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.UpdateJobPairStatus(?, ?)");
			ps.setInt(1, jobPairId);
			ps.setInt(2, status_code);
			ps.executeUpdate();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
	}

	/**
	 * Gets all job pairs in the database that have the given status code. This should really only be called for rare
	 * codes like 2-5, as otherwise it may read a very large number of pairs and be very slow.
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
			ps = con.prepareStatement("SELECT * FROM starexec.GetJobPairsWithStatus(?)");
			ps.setInt(1, statusCode);
			results = ps.executeQuery();
			if (results.next()) {
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
	 * Gets all job pairs in the database that have the given status code. This should really only be called for rare
	 * codes like 2-5, as otherwise it may read a very large number of pairs and be very slow.
	 *
	 * @param statusCode
	 * @return A list of all pairs with the given status code
	 */
	public static List<Integer> getPairIdsByStatusNotRerunAfterDate(StatusCode statusCode, Timestamp timestamp)
			throws SQLException {
		final String methodName = "getPairIdsByStatusNotRerunAfterDate";
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetJobPairIdsWithStatusNotRerunAfterDate(?, ?)");
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
	 * Sets the status code for a given pair and all of its stages to ERROR_SUBMIT_FAIL if and only if the pair's
	 * status
	 * code in the database matches the status code set in the given pair. This check is done to prevent a race
	 * condition in which a pair that is actually not stuck (hence its status code has changed since the pair was read)
	 * but is still set to the error status code.
	 *
	 * @param p The JobPair to affect. Must have ID and status code set
	 */
	public static void setBrokenPairStatus(JobPair p) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.SetBrokenPairStatus(?, ?, ?)");
			ps.setInt(1, p.getId());
			ps.setInt(2, p.getStatus().getCode().getVal());
			ps.setInt(3, Status.StatusCode.ERROR_SUBMIT_FAIL.getVal());
			ps.executeUpdate();
		} catch (Exception e) {
			log.error("setBrokenPairStatus", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
	}

	/**
	 * Gets all job pairs are currently under the jurisdiction of the backend. These are the pairs with status codes
	 * 2-5.
	 *
	 * @return The list of job pairs. Stages will not be populated
	 */
	public static List<JobPair> getPairsInBackend() {
		List<JobPair> pairs = new ArrayList<>();
		List<JobPair> enqueuedPairs = getPairsByStatus(Status.StatusCode.STATUS_ENQUEUED.getVal());
		if (enqueuedPairs != null) {
			pairs.addAll(enqueuedPairs);
		}
		List<JobPair> runningPairs = getPairsByStatus(Status.StatusCode.STATUS_RUNNING.getVal());
		if (runningPairs != null) {
			pairs.addAll(runningPairs);
		}
		return pairs;
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
			ps.executeUpdate();
			return true;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
		return false;
	}
}
