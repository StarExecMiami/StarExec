package org.starexec.data.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.ResultSetMetaData;
import org.starexec.constants.R;
import org.starexec.data.database.AnonymousLinks.PrimitivesToAnonymize;
import org.starexec.data.to.*;
import org.starexec.data.to.pipelines.JoblineStage;
import org.starexec.logger.StarLogger;
import org.starexec.data.to.Queue;
import org.starexec.data.to.QueueGraphData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

/**
 * Handles all statistics related database interaction
 *
 * @author Tyler Jensen
 */
public class Statistics {
	private static final StarLogger log = StarLogger.getLogger(Statistics.class);
	/**
	 * This string is returned in place of a file path whenever there are too many
	 * pairs to render a graph.
	 */
	public static final String OVERSIZED_GRAPH_ERROR = "big";
	/**
	 * This string is returned by makeSolverComparisonChart in place of a file path
	 * when one of the two configurations has no pair that the graph plots. That is
	 * not a failure: there is nothing to draw.
	 */
	public static final String NO_COMPARABLE_PAIRS_ERROR = "none";
	private static ConcurrentHashMap<Integer, QueueGraphData> queueGraphDataHashMap = new ConcurrentHashMap<>();

	/**
	 * @param jobId The job to get the pair times from
	 * @return A list of end times for the job pairs
	 */
	public static List<Date> getPairTimes(int jobId) {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetPairTimes(?)");
			ps.setInt(1, jobId);
			results = ps.executeQuery();
			List<Date> list = new ArrayList<>();
			while (results.next()) {
				list.add(results.getTimestamp("end_time"));
			}
			return list;
		} catch (Exception e) {
			log.error("getPairTimes", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
			Common.safeClose(results);
		}
		return null;
	}

	/**
	 * @param con   The connection to make the query on
	 * @param jobId The job to get the pair overview for
	 * @return A hashmap that contains the statistic's name to value mapping (this
	 *         method includes completePairs,
	 *         pendingPairs, errorPairs, totalPairs and runtime)
	 */
	protected static HashMap<String, String> getJobPairOverview(Connection con, int jobId) {
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			ps = con.prepareStatement("SELECT * FROM starexec.GetJobPairOverview(?)");
			ps.setInt(1, jobId);
			results = ps.executeQuery();

			if (results.next()) {
				return Statistics.getMapFromResult(results);
			}

			return null;
		} catch (Exception e) {
			log.error("getJobPairOverview", e);
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
		}
		return null;
	}

	/**
	 * @param jobId The job to get the pair overview for
	 * @return A hashmap that contains the statistic's name to value mapping (this
	 *         method includes completePairs,
	 *         pendingPairs, errorPairs, totalPairs and runtime)
	 */
	public static HashMap<String, String> getJobPairOverview(int jobId) {
		Connection con = null;

		try {
			con = Common.getConnection();
			return Statistics.getJobPairOverview(con, jobId);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
		}

		return null;
	}

	/**
	 * @param jobs The list of jobs to get overviews for (id required for each job)
	 * @return A hashmap where each key is the job ID and each value is a hashmap
	 *         that contains the statistic's name to
	 *         value mapping (includes completePairs, pendingPairs, errorPairs,
	 *         totalPairs and runtime)
	 */
	public static HashMap<Integer, HashMap<String, String>> getJobPairOverviews(List<Job> jobs) {
		Connection con = null;

		try {
			con = Common.getConnection();

			// Create the return map
			HashMap<Integer, HashMap<String, String>> map = new HashMap<>();

			// For each job...
			for (Job j : jobs) {
				// If it has an actual id...
				if (j.getId() > 0) {
					// Put a mapping from the job id to the pair overview for that job into the map
					map.put(j.getId(), Statistics.getJobPairOverview(con, j.getId()));
				}
			}

			return map;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
		}

		return null;
	}

	/**
	 * Takes in a ResultSet with the cursor on the desired row to convert, and
	 * returns a hashmap where each key/value
	 * pair is the column name and the column value for the record the cursor is
	 * pointing to. This DOES NOT iterate
	 * through all records in the resultset
	 *
	 * @param result The ResultSet with the cursor pointing to the desired record
	 * @return A hashmap of <Column name, Column Value> pairs
	 */
	protected static HashMap<String, String> getMapFromResult(ResultSet result) throws Exception {
		// Get resultset's metadata
		ResultSetMetaData meta = (ResultSetMetaData) result.getMetaData();
		// Create the map to return
		HashMap<String, String> map = new HashMap<>();
		// For each column in the record...
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			// Add key=column name, value=column value to the map
			map.put(meta.getColumnName(i), result.getString(i));
		}
		return map;
	}

	private static JsonObject errorChartData(String errorCode) {
		JsonObject error = new JsonObject();
		error.addProperty("error", errorCode);
		return error;
	}

	/**
	 * Builds structured cumulative completed-pairs-over-time data for client-side chart rendering.
	 *
	 * @param jobId The job ID to aggregate completion timestamps for
	 * @return A JSON object containing sorted timestamp/count points and total pair count, or null on failure
	 */
	public static JsonObject makeJobTimeGraphData(int jobId) {
		final String methodName = "makeJobTimeGraphData";
		try {
			log.entry(methodName);
			List<Date> times = getPairTimes(jobId);
			if (times == null) {
				return null;
			}
			times.sort((d1, d2) -> {
				if (d1 == d2) {
					return 0;
				}
				if (d1 == null) {
					return 1;
				}
				if (d2 == null) {
					return -1;
				}
				return Long.compare(d1.getTime(), d2.getTime());
			});

			JsonArray points = new JsonArray();
			for (int i = 0; i < times.size(); i++) {
				Date d = times.get(i);
				if (d != null) {
					JsonObject point = new JsonObject();
					point.addProperty("x", d.getTime());
					point.addProperty("y", i + 1);
					points.add(point);
				}
			}

			JsonObject data = new JsonObject();
			data.addProperty("chartType", "pairTime");
			data.addProperty("title", "# Pairs Completed vs Time");
			data.addProperty("xLabel", "Time");
			data.addProperty("yLabel", "# of Completed Pairs");
			data.addProperty("totalPairs", times.size());
			data.add("points", points);
			return data;
		} catch (Exception e) {
			log.error("Error generating job pair chart data for job_id=" + jobId, e);
		}
		return null;
	}

	/**
	 * Builds structured scatter-plot data comparing the running times of two solver/configuration
	 * pairs on benchmarks that both completed for a given job space and stage.
	 *
	 * @param configId1             The first configuration ID
	 * @param configId2             The second configuration ID
	 * @param jobSpaceId            The ID of the space containing the job pairs
	 * @param stageNumber           The stage to analyze
	 * @param primitivesToAnonymize Which primitives to anonymize in axis labels and points
	 * @return Structured chart data, an error payload holding OVERSIZED_GRAPH_ERROR or
	 *         NO_COMPARABLE_PAIRS_ERROR, or null on failure
	 */
	public static JsonObject makeSolverComparisonChartData(
			int configId1, int configId2, int jobSpaceId, int stageNumber,
			PrimitivesToAnonymize primitivesToAnonymize) {
		final String methodName = "makeSolverComparisonChartData";
		log.entry(methodName);

		try {
			List<Integer> configIds = List.of(configId1, configId2);
			List<List<JobPair>> pairs = Jobs.getJobPairsForSolverComparisonGraph(jobSpaceId, configIds, stageNumber,
					primitivesToAnonymize);
			if (pairs == null || pairs.size() < 2) {
				return null;
			}

			List<JobPair> pairs1 = pairs.get(0);
			List<JobPair> pairs2 = pairs.get(1);
			if (pairs1 == null || pairs2 == null) {
				return null;
			}

			if (pairs1.size() > R.MAXIMUM_DATA_POINTS || pairs2.size() > R.MAXIMUM_DATA_POINTS) {
				return errorChartData(OVERSIZED_GRAPH_ERROR);
			}
			// Both lists hold only the pairs this graph plots. The builder below returns null
			// when one is empty, which callers cannot tell apart from a failure.
			if (pairs1.isEmpty() || pairs2.isEmpty()) {
				return errorChartData(NO_COMPARABLE_PAIRS_ERROR);
			}
			return makeSolverComparisonChartData(pairs1, pairs2, jobSpaceId, stageNumber, primitivesToAnonymize);
		} catch (Exception e) {
			log.error(methodName + e.getMessage(), e);
		}
		return null;
	}

	/**
	 * Builds structured scatter-plot data comparing two solver/configuration pair lists.
	 *
	 * @param pairs1                Job pairs from the first configuration
	 * @param pairs2                Job pairs from the second configuration
	 * @param jobSpaceId            Job space ID used for solver anonymization lookups
	 * @param stageNumber           Stage number to analyze
	 * @param primitivesToAnonymize Which primitives to anonymize
	 * @return Structured JSON chart data, or null if either input list is empty or an error occurs
	 */
	public static JsonObject makeSolverComparisonChartData(
			List<JobPair> pairs1, List<JobPair> pairs2, int jobSpaceId, int stageNumber,
			PrimitivesToAnonymize primitivesToAnonymize) {
		try {
			if (pairs1 == null || pairs2 == null || pairs1.isEmpty() || pairs2.isEmpty()) {
				log.debug("An input list has no jobpairs, returning null");
				return null;
			}

			HashMap<Integer, JobPair> pairs2Map = new HashMap<>();
			for (JobPair jp : pairs2) {
				pairs2Map.put(jp.getBench().getId(), jp);
			}
			JoblineStage stage1 = pairs1.get(0).getStageFromNumber(stageNumber);
			JoblineStage stage2 = pairs2.get(0).getStageFromNumber(stageNumber);

			String xAxisName;
			String yAxisName;
			if (AnonymousLinks.areSolversAnonymized(primitivesToAnonymize)) {
				int jobId = Spaces.getJobSpace(jobSpaceId).getJobId();
				Map<Integer, String> solverIdToAnonymizedName = AnonymousLinks.getAnonymizedSolverNames(jobId,
						stageNumber);
				xAxisName = solverIdToAnonymizedName.get(stage1.getSolver().getId()) + " time(s)";
				yAxisName = solverIdToAnonymizedName.get(stage2.getSolver().getId()) + " time(s)";
			} else {
				xAxisName = stage1.getSolver().getName() + "/" + stage1.getConfiguration().getName() + " time(s)";
				yAxisName = stage2.getSolver().getName() + "/" + stage2.getConfiguration().getName() + " time(s)";
			}

			boolean benchmarksAnonymized = AnonymousLinks.areBenchmarksAnonymized(primitivesToAnonymize);
			Map<Integer, String> benchmarkIdToAnonymizedName = null;
			if (benchmarksAnonymized) {
				List<Benchmark> benchmarks = new ArrayList<>();
				for (JobPair p : pairs1) {
					benchmarks.add(p.getBench());
				}
				benchmarkIdToAnonymizedName = AnonymousLinks.getAnonymizedBenchmarkNames(benchmarks);
			}

			JsonArray points = new JsonArray();
			double maxCoord = 0.0;
			for (JobPair jp : pairs1) {
				JobPair jp2 = pairs2Map.get(jp.getBench().getId());
				if (jp.getStatus().getCode().statComplete() && jp2 != null && jp2.getStatus().getCode().statComplete()) {
					stage1 = jp.getStageFromNumber(stageNumber);
					stage2 = jp2.getStageFromNumber(stageNumber);
					double x = stage1.getWallclockTime();
					double y = stage2.getWallclockTime();
					maxCoord = Math.max(maxCoord, Math.max(x, y));

					JsonObject point = new JsonObject();
					point.addProperty("x", x);
					point.addProperty("y", y);
					if (benchmarksAnonymized && benchmarkIdToAnonymizedName != null) {
						point.addProperty("name", benchmarkIdToAnonymizedName.get(jp.getBench().getId()));
					} else {
						point.addProperty("name", jp.getBench().getName());
						if (!benchmarksAnonymized) {
							point.addProperty("benchmarkId", jp.getBench().getId());
						}
					}
					points.add(point);
				}
			}

			JsonObject data = new JsonObject();
			data.addProperty("chartType", "solverComparison");
			data.addProperty("title", "Solver Comparison Plot");
			data.addProperty("xLabel", xAxisName);
			data.addProperty("yLabel", yAxisName);
			data.addProperty("maxAxis", Math.max(0.1, maxCoord * 1.1));
			data.addProperty("benchmarksAnonymized", benchmarksAnonymized);
			data.add("points", points);
			return data;
		} catch (Exception e) {
			log.error("makeSolverComparisonChartData", e);
		}
		return null;
	}

	/**
	 * Builds structured cactus/overview series comparing solvers operating in a single job space.
	 *
	 * @param jobSpaceId            The space containing the job pairs
	 * @param logX                  Whether to use a log scale on the X axis
	 * @param logY                  Whether to use a log scale on the Y axis
	 * @param configIds             The configuration IDs to include
	 * @param stageNumber           The stage number to analyze
	 * @param primitivesToAnonymize Which primitives to anonymize
	 * @return Structured chart data, an error payload holding OVERSIZED_GRAPH_ERROR, or null on failure
	 */
	public static JsonObject makeSpaceOverviewChartData(
			int jobSpaceId, boolean logX, boolean logY, List<Integer> configIds, int stageNumber,
			PrimitivesToAnonymize primitivesToAnonymize) {
		try {
			if (configIds == null || configIds.isEmpty()) {
				return null;
			}

			List<List<JobPair>> pairLists = Jobs.getJobPairsForSolverComparisonGraph(jobSpaceId, configIds, stageNumber,
					primitivesToAnonymize);
			if (pairLists == null) {
				return null;
			}
			List<JobPair> pairs = new ArrayList<>();
			for (List<JobPair> pairList : pairLists) {
				pairs.addAll(pairList);
			}
			if (pairs.size() > R.MAXIMUM_DATA_POINTS) {
				return errorChartData(OVERSIZED_GRAPH_ERROR);
			}

			log.debug("Number of pairs after add all for primitivesToAnonymize=" +
					AnonymousLinks.getPrimitivesToAnonymizeName(primitivesToAnonymize) + ": " + pairs.size());
			return makeSpaceOverviewChartData(pairs, logX, logY, stageNumber, primitivesToAnonymize);
		} catch (Exception e) {
			log.error("makeSpaceOverviewChartData", e);
		}
		return null;
	}

	/**
	 * Builds structured space overview series from an explicit list of job pairs.
	 *
	 * @param pairs                 The pairs to plot
	 * @param logX                  Whether to use a log scale for the X axis
	 * @param logY                  Whether to use a log scale for the Y axis
	 * @param stageNumber           The stage number to analyze
	 * @param primitivesToAnonymize Which primitives to anonymize
	 * @return Structured JSON chart data, or null on failure
	 */
	public static JsonObject makeSpaceOverviewChartData(
			List<JobPair> pairs, boolean logX, boolean logY, int stageNumber,
			PrimitivesToAnonymize primitivesToAnonymize) {
		try {
			if (pairs == null) {
				return null;
			}
			log.debug("Making space overview chart data with logX = " + logX + " and logY = " + logY + " and pair # = " +
					pairs.size());
			HashMap<Solver, HashMap<Configuration, List<Double>>> data = processJobPairData(pairs, stageNumber);
			TreeMap<String, JsonArray> sortedSeries = new TreeMap<>();
			double maxY = 0.0;
			boolean solversAnonymized = AnonymousLinks.areSolversAnonymized(primitivesToAnonymize);
			for (Solver s : data.keySet()) {
				for (Configuration c : data.get(s).keySet()) {
					String label = solversAnonymized
							? s.getName() + " config = " + c.getName()
							: s.getName() + "(" + s.getId() + ") config = " + c.getName();
					JsonArray points = new JsonArray();
					int counter = 1;
					for (Double time : data.get(s).get(c)) {
						JsonObject point = new JsonObject();
						point.addProperty("x", counter);
						point.addProperty("y", time);
						points.add(point);
						maxY = Math.max(maxY, time);
						counter++;
					}
					sortedSeries.put(label, points);
				}
			}

			JsonArray series = new JsonArray();
			for (Map.Entry<String, JsonArray> entry : sortedSeries.entrySet()) {
				JsonObject item = new JsonObject();
				item.addProperty("label", entry.getKey());
				item.add("points", entry.getValue());
				series.add(item);
			}

			JsonObject result = new JsonObject();
			result.addProperty("chartType", "spaceOverview");
			result.addProperty("title", "Space Overview Plot");
			result.addProperty("xLabel", "# solved");
			result.addProperty("yLabel", "time (s)");
			result.addProperty("logX", logX);
			result.addProperty("logY", logY);
			result.addProperty("maxY", Math.max(0.1, maxY * 1.1));
			result.add("series", series);
			return result;
		} catch (Exception e) {
			log.error("makeSpaceOverviewChartData", e);
		}
		return null;
	}

	/**
	 * Given a list of completed job pairs in a given job, arranges them by solver
	 * and configuration and sorts them by
	 * CPU run time. Used by the space overview graph
	 *
	 * @param pairs
	 * @return A HashMap for which solvers map to another HashMap mapping
	 *         configurations to a sorted list of doubles
	 *         representing the CPU usage of every completed, correct job pair
	 *         produced by that solver/configuration pair
	 * @author Eric Burns
	 */

	private static HashMap<Solver, HashMap<Configuration, List<Double>>> processJobPairData(
			List<JobPair> pairs, int stageNumber) {
		// we need to store solvers and configs by ID and only put items into answer
		// from these two HashMaps.
		// We can't compare to solvers to see if they are equal directly, so we have to
		// compare IDs
		HashMap<Integer, Solver> solvers = new HashMap<>();
		HashMap<Integer, Configuration> configs = new HashMap<>();
		HashMap<Solver, HashMap<Configuration, List<Double>>> answer = new HashMap<>();
		for (JobPair jp : pairs) {
			if (!jp.getStatus().getCode().statComplete()) {
				// we don't want to consider incomplete pairs
				continue;
			}
			// variable that will contain the single relevant stage for this pair,
			// corresponding to stageNumber
			JoblineStage stage = jp.getStageFromNumber(stageNumber);

			Solver s = stage.getSolver();
			if (!solvers.containsKey(s.getId())) {
				solvers.put(s.getId(), s);
				answer.put(solvers.get(s.getId()), new HashMap<>());
			}
			HashMap<Configuration, List<Double>> configMap = answer.get(solvers.get(s.getId()));
			Configuration c = stage.getConfiguration();
			if (!configs.containsKey(c.getId())) {
				configs.put(c.getId(), c);
				configMap.put(configs.get(c.getId()), new ArrayList<>());
			}
			configMap.get(configs.get(c.getId())).add(stage.getWallclockTime());
		}
		for (HashMap<Configuration, List<Double>> h : answer.values()) {
			for (List<Double> l : h.values()) {
				Collections.sort(l);
			}
		}

		return answer;
	}
}
