package org.starexec.data.database;

import org.starexec.constants.PaginationQueries;
import org.starexec.constants.R;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.to.*;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.data.to.pipelines.JoblineStage;
import org.starexec.logger.StarLogger;
import org.starexec.util.DataTablesQuery;
import org.starexec.util.NamedParameterStatement;
import org.starexec.util.PaginationQueryBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all DB interaction for queues
 *
 * @author Tyler Jensen
 */
public class Queues {
	private static final StarLogger log = StarLogger.getLogger(Queues.class);
	// Simple in-memory cache to avoid repeated DB hits for queue lookups.
	// Keyed by queue id. Cleared on any write that may change queue data.
	private static final ConcurrentHashMap<Integer, Queue> queueCache = new ConcurrentHashMap<>();

	private static void invalidateQueueCache() {
		log.debug("invalidateQueueCache", "Invalidating queue cache");
		try {
			queueCache.clear();
		} catch (Exception e) {
			log.error("invalidateQueueCache", e);
		}
	}

	// Defensive copy helper to avoid returning or storing mutable shared instances.
	private static Queue copyQueue(Queue src) {
		if (src == null) return null;
		try {
			Queue dst = new Queue();
			dst.setId(src.getId());
			dst.setName(src.getName());
			// status is a String in resultSetToQueue
			dst.setStatus(src.getStatus());
			dst.setWallTimeout(src.getWallTimeout());
			dst.setCpuTimeout(src.getCpuTimeout());
			dst.setGlobalAccess(src.getGlobalAccess());
			return dst;
		} catch (Exception e) {
			log.error("copyQueue", e);
			return null;
		}
	}

	// Safe cache put that stores a defensive copy.
	private static void cachePutSafe(int id, Queue q) {
		if (q == null || id <= 0) {
			return;
		}
		try {
			Queue stored = copyQueue(q);
			if (stored != null) queueCache.put(id, stored);
		} catch (Exception e) {
			log.error("cachePutSafe", e);
		}
	}

	/**
	 * @return returns the default queue name, default queue should always exist
	 */
	public static String getDefaultQueueName() {
		return "all";
	}

	/**
	 * Removes a queue from the database and calls R.BACKEND.removeQueue
	 *
	 * @param queueId The Id of the queue to remove.
	 * @return True on success and false otherwise
	 */
	public static boolean removeQueue(int queueId) {
		Queue q = Queues.get(queueId);
		if (q == null) {
			return true;
		}
		//Pause jobs that are running on the queue
		List<Job> jobs = Cluster.getJobsRunningOnQueue(queueId);

		if (jobs != null) {
			for (Job j : jobs) {
				Jobs.pause(j.getId());
			}
		}

		//Move associated Nodes back to default queue
		List<WorkerNode> nodes = Queues.getNodes(queueId);

		if (nodes != null) {
			for (WorkerNode n : nodes) {
				R.BACKEND.moveNode(n.getName(), getDefaultQueueName());
			}
		}

		boolean success = true;

		/* DELETE THE QUEUE */

		success = success && Queues.delete(queueId);
		R.BACKEND.deleteQueue(q.getName());

		Cluster.loadWorkerNodes();
		Cluster.loadQueueDetails();
		return success;
	}

	/**
	 * Will pause jobs associated with queues if the number of nodes being removed from them is equal to the number of
	 * nodes they have. used by MoveNodes
	 *
	 * @param queueIdsToNodesRemoved A mapping from queueID to the number of nodes being removed from that queue
	 **/
	public static void pauseJobsIfNoRemainingNodes(Map<Integer, Integer> queueIdsToNodesRemoved) {
		//if this is going to make the queue empty...... need to pause all jobs first
		for (int queueId : queueIdsToNodesRemoved.keySet()) {
			List<WorkerNode> workers = Cluster.getNodesForQueue(queueId);
			if (workers != null && workers.size() <= queueIdsToNodesRemoved.get(queueId)) {
				List<Job> jobs = Cluster.getJobsRunningOnQueue(queueId);
				if (jobs != null) {
					for (Job j : jobs) {
						Jobs.pause(j.getId());
					}
				}
			}
		}
	}

	/**
	 * Adds a new queue to the system. This action adds the queue, and adds a new association to the queue for the
	 * given
	 * list of nodes This is a multi-step process, use transactions to ensure it completes as an atomic unit.
	 *
	 * @param con The connection to perform the operation on
	 * @param queueName The name of the queue to add
	 * @param cpuTimeout the max cpu timeout for the new queue
	 * @param wallTimeout the max wallclock timeout for the new queue
	 * @return The ID of the newly inserted queue, -1 if the operation failed
	 * @throws Exception
	 * @author Tyler Jensen
	 */
	protected static int add(Connection con, String queueName, int cpuTimeout, int wallTimeout) {
		PreparedStatement stmt = null;
		ResultSet rs = null;
		try {

			//Add the queue first
			stmt = con.prepareStatement("SELECT starexec.addqueue(?, ?, ?)");
			stmt.setString(1, queueName);
			stmt.setInt(2, wallTimeout);
			stmt.setInt(3, cpuTimeout);
			rs = stmt.executeQuery();
			rs.next();
			int newQueueId = rs.getInt(1);

			if (newQueueId == 0) {
				return -1;
			} else {
				// Invalidate cache because queue set changed
				invalidateQueueCache();
			}
			return newQueueId;
		} catch (Exception e) {
			log.debug("add", "queueName:\t" + queueName, e);
		} finally {
			Common.safeClose(rs);
			Common.safeClose(stmt);
		}
		return -1;
	}

	/**
	 * Adds a new queue to the system.
	 *
	 * @param queueName The name of the queue to add
	 * @param cpuTimeout the max cpu timeout for the new queue
	 * @param wallTimeout the max wallclock timeout for the new queue
	 * @return The ID of the newly inserted queue, -1 if the operation failed
	 * @author Wyatt Kaiser
	 */
	public static int add(String queueName, int cpuTimeout, int wallTimeout) {
		Connection con = null;

		try {
			con = Common.getConnection();
			int result = Queues.add(con, queueName, cpuTimeout, wallTimeout);
			return result;
		} catch (Exception e) {
			log.error("add", e);
		} finally {
			Common.safeClose(con);
		}

		return -1;
	}

	/**
	 * Associates a queue and a worker node to indicate the node belongs to the queue. If the association already
	 * exists, any errors are ignored.
	 *
	 * @param queueName The FULL name of the owning queue
	 * @param nodeName The FULL name of the worker node that belongs to the queue
	 * @return True if the operation was a success, false otherwise.
	 */
	public static boolean associate(String queueName, String nodeName) {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT starexec.AssociateQueue(?, ?)");
			procedure.setString(1, queueName);
			procedure.setString(2, nodeName);

			procedure.execute();
			// association change can affect queue/node mapping; clear cache
			invalidateQueueCache();
			return true;
		} catch (Exception e) {
			log.error("associate", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}

		return false;
	}

	/**
	 * Removes all associations between queues and nodes in db so that only up to date data will be stored.
	 *
	 * @author Benton McCune
	 */
	public static void clearQueueAssociations() {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT starexec.clearQueueAssociations()");
			procedure.execute();
			invalidateQueueCache();
		} catch (Exception e) {
			log.error("clearQueueAssociations", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
	}

	/**
	 * Gets a queue with very basic information, not including any SGE attributes with the queue
	 *
	 * @param con The connection to make the query with
	 * @param qid The id of the queue to retrieve
	 * @return a queue object representing the queue to retrieve
	 * @throws Exception
	 */
	protected static Queue get(Connection con, int qid) {
		final String methodName = "get";
		ResultSet results = null;
		PreparedStatement procedure = null;

		// Try cache first for positive ids
		try {
			if (qid > 0) {
				Queue cached = queueCache.get(qid);
				if (cached != null) {
					// Return a defensive copy to avoid shared mutable objects
					return copyQueue(cached);
				}
			}
		} catch (Exception e) {
			log.error(methodName + " cache lookup", e);
		}

		try {
			procedure = con.prepareStatement("SELECT * FROM starexec.GetQueue(?)");
			procedure.setInt(1, qid);
			results = procedure.executeQuery();
			if (results.next()) {
				Queue q = resultSetToQueue(results);
				try {
					if (q != null && q.getId() > 0) {
						// Store a defensive copy in the cache
						cachePutSafe(q.getId(), q);
					}
				} catch (Exception e) {
					log.error(methodName + " cache put", e);
				}
				// Return a defensive copy to caller
				return copyQueue(q);
			}
		} catch (Exception e) {
			log.error(methodName, e);
		} finally {
			Common.safeClose(results);
			Common.safeClose(procedure);
		}
		return null;
	}

	/**
	 * Gets a queue with very basic information, not including any SGE attributes with the queue
	 *
	 * @param qid The id of the queue to retrieve
	 * @return a queue object representing the queue to retrieve
	 */
	public static Queue get(int qid) {
		Connection con = null;

		try {
			con = Common.getConnection();
			Queue result = Queues.get(con, qid);
			return result;
		} catch (Exception e) {
			log.error("get", e);
		} finally {
			Common.safeClose(con);
		}

		return null;
	}

	/**
	 * Gets all active queues in the starexec cluster
	 *
	 * @return A list of queues
	 * @author Aaron Stump
	 */
	public static List<Queue> getAllActive() {
		List<Queue> result = getQueues(0);
		return result;
	}

	/**
	 * Gets all queues in the starexec cluster (Including Inactive queues)
	 *
	 * @return A list of queues
	 * @author Wyatt Kaiser
	 */
	public static List<Queue> getAllAdmin() {
		List<Queue> result = getQueues(-2);
		return result;
	}

	protected static int getCountOfEnqueuedPairsByQueue(Connection con, int qId) {
		PreparedStatement procedure = null;
		ResultSet results = null;

		try {
			procedure = con.prepareStatement("SELECT * FROM starexec.GetCountOfEnqueuedJobPairsByQueue(?)");
			procedure.setInt(1, qId);
			results = procedure.executeQuery();

			if (results.next()) {
				int count = results.getInt("count");
				return count;
			}

			return -1;
		} catch (Exception e) {
			log.error("getCountOfEnqueuedPairsByQueue", e);
		} finally {
			Common.safeClose(results);
			Common.safeClose(procedure);
		}
		return -1;
	}

	/**
	 * Gets the number of pairs that are enqueued in the given queue.
	 *
	 * @param qId The id of the queue to get pairs for
	 * @return A list of job pair objects that belong to the given queue.
	 * @author Wyatt Kaiser
	 */
	public static int getCountOfEnqueuedPairsByQueue(int qId) {
		Connection con = null;

		try {
			con = Common.getConnection();
			int result = getCountOfEnqueuedPairsByQueue(con, qId);
			return result;
		} catch (Exception e) {
			log.error("getCountOfEnqueuedPairsShallow", "qid: " + qId, e);
		} finally {
			Common.safeClose(con);
		}

		return -1;
	}

	/**
	 * Gets the ID of a queue given the name of the queue
	 *
	 * @param queueName The exact name of the queue, including .q
	 * @return The name, or null if it was not found.
	 */
	public static int getIdByName(String queueName) {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();

			procedure = con.prepareStatement("SELECT * FROM starexec.GetIdByName(?)");
			procedure.setString(1, queueName);


			results = procedure.executeQuery();

			if (results.next()) {
				int id = results.getInt("id");
				return id;
			}
		} catch (Exception e) {
			log.error("getIdByName", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
		return -1;
	}

	private static String getPairOrderColumnForClusterPage(int indexOrder) {
		switch (indexOrder) {
		case 0:
			return "queuesub_time";
		case 1:
			return "jobs.name";
		case 2:
			return "users.first_name, users.last_name";
		case 3:
			return "bench_name";
		case 4:
			return "solver_name";
		case 5:
			return "config_name";
		case 6:
			return "path";
		}

		return "jobs.name";
	}

	/**
	 * Given a ResultSet containing job pairs with fields set for the cluster DataTable page, returns the list of job
	 * pairs.
	 *
	 * @return
	 * @throws SQLException
	 */
	private static List<JobPair> resultSetToClusterPagePairs(ResultSet results) throws SQLException {
		List<JobPair> returnList = new LinkedList<>();

		while (results.next()) {
			JobPair jp = new JobPair();
			// Column labels returned by JDBC omit table qualifiers unless explicitly aliased.
			jp.setPrimaryStageNumber(results.getInt("primaryJobpairData"));
			jp.setPath(results.getString("path"));
			jp.setJobId(results.getInt("jobId"));
			jp.setId(results.getInt("id"));
			jp.setQueueSubmitTime(results.getTimestamp("queuesubTime"));
			Status stat = new Status();
			//enqueued by definition, so we don't want to retrieve extra data from the db
			stat.setCode(StatusCode.STATUS_ENQUEUED);

			jp.setStatus(stat);

			JoblineStage stage = new JoblineStage();
			stage.setStageNumber(jp.getPrimaryStageNumber());

			jp.addStage(stage);

			Benchmark b = new Benchmark();
			b.setId(results.getInt("benchId"));
			b.setName(results.getString("benchName"));
			jp.setBench(b);

			Solver s = new Solver();
			s.setId(results.getInt("solverId"));
			s.setName(results.getString("solverName"));
			stage.setSolver(s);

			Configuration c = new Configuration();
			c.setId(results.getInt("configId"));
			c.setName(results.getString("configName"));
			stage.setConfiguration(c);
			jp.getPrimarySolver().addConfiguration(c);

			User u = new User();
			u.setId(results.getInt("userId"));
			u.setFirstName(results.getString("firstName"));
			u.setLastName(results.getString("lastName"));
			jp.setOwningUser(u);

			Job j = new Job();
			j.setId(results.getInt("jobIdDup"));
			j.setName(results.getString("jobName"));

			jp.setOwningJob(j);

			returnList.add(jp);
		}
		return returnList;
	}

	/**
	 * Gets the pairs running on the given node. Only the fields required for the DataTables on the explore/cluster
	 * page
	 * are populated
	 *
	 * @param nodeId
	 * @return The list of job pairs
	 */
	public static List<JobPair> getPairsRunningOnNode(int nodeId) {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT * FROM starexec.GetPairsRunningOnNode(?)");
			procedure.setInt(1, nodeId);
			results = procedure.executeQuery();
			List<JobPair> result = resultSetToClusterPagePairs(results);
			return result;
		} catch (Exception e) {
			log.error("getPairsRunningOnNode", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(results);
			Common.safeClose(procedure);
		}
		return null;
	}

	/**
	 * Gets all the necessary job pairs for populating a datatables page on the cluster status page
	 *
	 * @param query A DataTablesQuery object
	 * @param id The ID of the queue or node
	 * @return A list of JobPairs running on the node
	 */

	public static List<JobPair> getJobPairsForNextClusterPage(DataTablesQuery query, int id) {
		PaginationQueryBuilder builder = null;
		builder = new PaginationQueryBuilder(PaginationQueries.GET_PAIRS_ENQUEUED_QUERY,
											 getPairOrderColumnForClusterPage(query.getSortColumn()), query
		);
		Connection con = null;
		NamedParameterStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();

			String sql = builder.getSQL();
			procedure = new NamedParameterStatement(con, sql);

			// Parameter name must match placeholder in EnqueuedPairPagination.sql (currently :id)
			procedure.setInt("id", id);
			results = procedure.executeQuery();

			List<JobPair> result = resultSetToClusterPagePairs(results);
			return result;
		} catch (Exception e) {
			log.error("getJobPairsForNextClusterPage","queue: " + id, e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(results);
			Common.safeClose(procedure);
		}
		return null;
	}

	/**
	 * Gets all nodes in the cluster that belong to the queue
	 *
	 * @param id The id of the queue to get nodes for
	 * @return A list of nodes that belong to the queue
	 * @author Tyler Jensen
	 */
	public static List<WorkerNode> getNodes(int id) {
		List<WorkerNode> result = Cluster.getNodesForQueue(id);
		return result;
	}

	/**
	 * Wrapper method that gets jobs with pending job pairs for the given queue
	 *
	 * @param queueId the id of the queue
	 * @return the list of Jobs for that queue which have pending job pairs
	 */
	public static List<Job> getPendingJobs(int queueId) {
		List<Job> result = getPendingJobsHelper(queueId, false);
		return result;
	}

	/**
	 * Wrapper method that only gets developer or admin jobs with pending job pairs for the given queue
	 *
	 * @param queueId the id of the queue
	 * @return the list of Jobs for that queue which have pending job pairs
	 */
	public static List<Job> getPendingDeveloperJobs(int queueId) {
		List<Job> result = getPendingJobsHelper(queueId, true);
		return result;
	}

	/**
	 * Gets jobs with pending job pairs for the given queue
	 *
	 * @param queueId the id of the queue
	 * @param developerOnly true if only developer jobs will be returned
	 * @return the list of Jobs for that queue which have pending job pairs
	 * @author Ben McCune and Aaron Stump
	 */
	private static List<Job> getPendingJobsHelper(int queueId, Boolean developerOnly) {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;

		try {
			con = Common.getConnection();
			Queue queue = Queues.get(con, queueId);
			if (developerOnly) {
				procedure = con.prepareStatement("SELECT * FROM starexec.GetPendingDeveloperJobs(?)");
			} else {
				procedure = con.prepareStatement("SELECT * FROM starexec.GetPendingJobs(?)");
			}
			procedure.setInt(1, queueId);
			results = procedure.executeQuery();
			List<Job> jobs = new LinkedList<>();

			while (results.next()) {
				Job j = Jobs.resultsToJob(results);
				j.setQueue(queue);
				j.setStageAttributes(Jobs.getStageAttrsForJob(j.getId(), con));
				j.setUser(Users.get(j.getUserId()));
				jobs.add(j);
			}
			return jobs;
		} catch (Exception e) {
			log.error("getPendingJobsHelper", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}

		return null;
	}

	/**
	 * Tests to see if there exist developer jobs in all active queues
	 *
	 * @return true if there are developer jobs, false if none are in any queue
	 */
	public static boolean developerJobsExist() {
		List<Queue> queues = Queues.getAllActive();
		for (Queue q : queues) {
			int queueId = q.getId();
			Connection con = null;
			PreparedStatement procedure = null;
			ResultSet results = null;
			try {
				con = Common.getConnection();
				procedure = con.prepareStatement("SELECT * FROM starexec.GetPendingDeveloperJobs(?)");
				procedure.setInt(1, queueId);
				results = procedure.executeQuery();
				if (results.next()) {
					return true;
				}
			} catch (Exception e) {
				log.error("developerJobsExist", e);
			} finally {
				Common.safeClose(con);
				Common.safeClose(procedure);
				Common.safeClose(results);
			}
		}
		return false;
	}

	private static Queue resultSetToQueue(ResultSet results) throws SQLException {
		Queue q = new Queue();
		q.setName(results.getString("name"));
		q.setId(results.getInt("id"));
		q.setStatus(results.getString("status"));
		q.setWallTimeout(results.getInt("clockTimeout"));
		q.setCpuTimeout(results.getInt("cpuTimeout"));
		q.setGlobalAccess(results.getBoolean("global_access"));
		return q;
	}

	/**
	 * Gets all queues in the starexec cluster (with no detailed information)
	 *
	 * @param userId return the queues accessible by the given user, or all queues if the userId is 0
	 * @return A list of queues
	 * @author Tyler Jensen and Aaron Stump
	 */
	protected static List<Queue> getQueues(int userId) {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			switch (userId) {
			case 0:
				//only gets the queues that have status "ACTIVE"
				// qualify the function with the schema to avoid depending on the search_path
				procedure = con.prepareStatement("SELECT id, name, status, global_access, cputimeout as cpuTimeout, clocktimeout as clockTimeout FROM starexec.GetAllQueues()");
				break;
			case -2:
				//includes inactive queues
				// qualify the function with the schema to avoid depending on the search_path
				procedure = con.prepareStatement("SELECT id, name, status, global_access, cputimeout as cpuTimeout, clocktimeout as clockTimeout FROM starexec.GetAllQueuesAdmin()");
				break;
			default:
				procedure = con.prepareStatement("SELECT * FROM starexec.GetQueuesForUser(?)");
				procedure.setInt(1, userId);
				break;
			}

			results = procedure.executeQuery();
			List<Queue> queues = new LinkedList<>();

			while (results.next()) {
				queues.add(Queues.resultSetToQueue(results));
			}
			return queues;
		} catch (Exception e) {
			log.error("getQueues", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(results);
			Common.safeClose(procedure);
		}

		return null;
	}

	/**
	 * Returns the sum of wallclock timeouts for all pairs that are in the given queue (running or enqueued) that are
	 * owned by the given user.
	 *
	 * @param queueId The queue in question
	 * @param userId The ID of the user who owns the pairs
	 * @return The integer sum of wallclock timeouts, or null on failure
	 */

	public static Long getUserLoadOnQueue(int queueId, int userId) {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT * FROM starexec.GetUserLoadOnQueue(?,?)");
			procedure.setInt(1, queueId);
			procedure.setInt(2, userId);
			results = procedure.executeQuery();

			if (results.next()) {
				Long load = results.getLong("queue_load");
				return load;
			}
		} catch (Exception e) {
			log.error("getUserLoadOnQueue", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}

		return null;
	}

	/**
	 * Returns the number of job pairs enqueued in the given queue
	 *
	 * @param queueId The queue in question
	 * @return The integer number of jobs, or null on failure
	 */

	public static Integer getSizeOfQueue(int queueId) {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT * FROM starexec.GetNumEnqueuedJobs(?)");
			procedure.setInt(1, queueId);
			results = procedure.executeQuery();

			Integer qSize = -1;
			while (results.next()) {
				qSize = results.getInt("count");
			}
			return qSize;
		} catch (Exception e) {
			log.error("getSizeOfQueue", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}

		return null;
	}

	/**
	 * Gets all queues in the starexec cluster accessible by the user with the given id
	 *
	 * @param userId The ID of the user to ge tqueues for
	 * @return A list of queues that are defined the cluster
	 * @author Aaron Stump
	 */
	public static List<Queue> getUserQueues(int userId) {
		if (GeneralSecurity.hasAdminReadPrivileges(userId)) {
			return getAllActive();
		} else {
			return getQueues(userId);
		}
	}

	/**
	 * Updates the status of ALL queues with the given status
	 *
	 * @param status The status to set for all queues
	 */
	public static void setStatus(String status) {
		Queues.setStatus(null, status);
	}

	/**
	 * Updates the status of the given queue with the given status
	 *
	 * @param name the name of the queue to set the status for
	 * @param status the status to set for the queue
	 * @return True if the operation was a success, false otherwise.
	 */
	public static boolean setStatus(String name, String status) {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();

			if (name == null) {
				// If no name was supplied, apply to all queues
				procedure = con.prepareStatement("SELECT starexec.UpdateAllQueueStatus(?)");
				procedure.setString(1, status);
			} else {
				procedure = con.prepareStatement("SELECT starexec.UpdateQueueStatus(?, ?)");
				procedure.setString(1, name);
				procedure.setString(2, status);
			}

			procedure.execute();
			// updates change queue data -> invalidate cache
			invalidateQueueCache();
			return true;
		} catch (Exception e) {
			log.error("setStatus", e);
			Common.doRollback(con);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}

		log.warn("setStatus", String.format("Status for queue [%s] failed to be updated.", (name == null) ? "ALL" : name));
		return false;
	}

	/**
	 * Checks to see whether the given name is already being used by a queue
	 *
	 * @param queueName The name we want to check against all existing queues
	 * @return True if the given name IS used by a given queue. False if the name is NOT used by a queue OR on error
	 */

	public static boolean notUniquePrimitiveName(String queueName) {
		boolean result = Queues.getIdByName(queueName) >= 0;
		return result;
	}

	/**
	 * Gets the name of a queue given its ID
	 *
	 * @param queueId The ID of the queue to retrieve the name of
	 * @return The name of the queue, or null on error
	 */

	public static String getNameById(int queueId) {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();

			procedure = con.prepareStatement("SELECT * FROM starexec.GetNameById(?)");
			procedure.setInt(1, queueId);

			results = procedure.executeQuery();
			if (results.next()) {
				String name = results.getString("name");
				return name;
			}
		} catch (Exception e) {
			log.error("getIdByName", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
		//we couldn't find the name
		return null;
	}

	/**
	 * Updates the cpu timeout for an existing queue
	 *
	 * @param queueId The ID of the queue to update
	 * @param timeout The timeout to set, in seconds
	 * @return True on success and false on error
	 */
	public static boolean updateQueueCpuTimeout(int queueId, int timeout) {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT starexec.UpdateQueueCpuTimeout(?,?)");
			procedure.setInt(1, queueId);
			procedure.setInt(2, timeout);
			procedure.execute();
			invalidateQueueCache();
			return true;
		} catch (Exception e) {
			log.error("updateQueueCpuTimeout" + e.toString());
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
		return false;
	}

	/**
	 * Updates the wallclock timeout for an existing queue
	 *
	 * @param queueId The ID of the queue to update
	 * @param timeout The new timeout, in seconds.
	 * @return True on success and false on error.
	 */
	public static boolean updateQueueWallclockTimeout(int queueId, int timeout) {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT starexec.UpdateQueueClockTimeout(?,?)");
			procedure.setInt(1, queueId);
			procedure.setInt(2, timeout);
			procedure.execute();
			invalidateQueueCache();
			return true;
		} catch (Exception e) {
			log.error("updateQueueWallclockTimeout", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
		return false;
	}

	/**
	 * Checks to see whether the given queue is global or not
	 *
	 * @param queueId The ID of the queue to check
	 * @return True if it is global, and false if it is not OR if there is an error
	 */

	public static boolean isQueueGlobal(int queueId) {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT * FROM starexec.IsQueueGlobal(?)");
			procedure.setInt(1, queueId);

			results = procedure.executeQuery();
			if (results.next()) {
				boolean global = results.getBoolean("global_access");
				return global;
			}
		} catch (Exception e) {
			log.error("isQueueGlobal", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
		return false;
	}

	/**
	 * Deletes a queue from the database
	 *
	 * @param queueId The ID of the queue to delete
	 * @return True on success and false on error
	 */

	public static boolean delete(int queueId) {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();

			procedure = con.prepareStatement("SELECT starexec.RemoveQueue(?)");
			procedure.setInt(1, queueId);
			procedure.execute();
			invalidateQueueCache();
			return true;
		} catch (Exception e) {
			log.error("delete", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}

		return false;
	}

	/**
	 * Sets the global access column of the given queue to true
	 *
	 * @param queueId The ID of the queue to check
	 * @return True on success and false on failure
	 */
	public static boolean makeGlobal(int queueId) {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT starexec.MakeQueueGlobal(?)");
			procedure.setInt(1, queueId);
			procedure.execute();

			invalidateQueueCache();
			return true;
		} catch (Exception e) {
			log.error("makeGlobal", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
		return false;
	}

	/**
	 * Sets the global_access column of the given queue to false
	 *
	 * @param queueId The ID of the queue to remove
	 * @return True on success and false on error
	 */
	public static boolean removeGlobal(int queueId) {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();

			procedure = con.prepareStatement("SELECT starexec.RemoveQueueGlobal(?)");
			procedure.setInt(1, queueId);
			procedure.execute();

			invalidateQueueCache();
			return true;
		} catch (Exception e) {
			log.error("removeGlobal", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
		return false;
	}

	/**
	 * Sets the given queue to be the system test queue.
	 *
	 * @param queueId The ID of the queue to make the test queue.
	 * @return True on success and false on error.
	 */
	public static boolean setTestQueue(int queueId) {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();

			procedure = con.prepareStatement("SELECT starexec.SetTestQueue(?)");
			procedure.setInt(1, queueId);
			procedure.execute();

			invalidateQueueCache();
			return true;
		} catch (Exception e) {
			log.error("setTestQueue", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
		return false;
	}

	/**
	 * Returns all.q, which is the one queue in the system that is always guaranteed to exist
	 *
	 * @return The default queue, or null if it could not be found.
	 */
	public static Queue getAllQ() {
		Queue result = Queues.get(R.DEFAULT_QUEUE_ID);
		return result;
	}

	/**
	 * Gets the queue that should be used for running test jobs. If no such queue is currently set, it will be set to
	 * all.q before returning. This is to ensure a test queue is always set
	 *
	 * @return The id of the queue, or -1 on error
	 */
	public static int getTestQueue() {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT * FROM starexec.GetTestQueue()");
			results = procedure.executeQuery();
			if (results.next()) {
				int id = results.getInt("test_queue");
				if (id <= 0) {
					Queues.setTestQueue(R.DEFAULT_QUEUE_ID);
					return R.DEFAULT_QUEUE_ID;
				}
				return id;
			}
		} catch (Exception e) {
			log.error("getTestQueue", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
		return -1;
	}

	/**
	 * Gives one or more communities access to a queue
	 *
	 * @param community_ids The IDs of the communities to give access to
	 * @param queue_id The ID of the queue
	 * @return True on success and false on error.
	 */
	public static boolean setQueueCommunityAccess(List<Integer> community_ids, int queue_id) {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			Common.beginTransaction(con);

			if (community_ids != null) {
				for (int id : community_ids) {
					procedure = con.prepareStatement("SELECT starexec.SetQueueCommunityAccess(?, ?)");
					procedure.setInt(1, id);
					procedure.setInt(2, queue_id);

					procedure.execute();
				}
			}

			Common.endTransaction(con);
			// community access affects queue visibility -> clear cache
			invalidateQueueCache();

			return true;
		} catch (Exception e) {
			log.error("setQueueCommunityAccess", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
		return false;
	}

	/*
	 * Given a queue id, fetch the description for the queue
	 * @param qid the id of the queue
	 * @author aguo2
	 */
	public static String getDescForQueue(int qid) {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT * FROM starexec.GetDescForQueue(?)");
			procedure.setInt(1, qid);
			results = procedure.executeQuery();
			String result = "";
			if (results.next()) {
				// Move the cursor to the first row and access the data
				result = results.getString("description");
			}
			return result;

		}
		catch (Exception e) {
			log.error("there was an error getting the description for queue " + qid 
			+ ". Exception was: " + e.getMessage());
			return "";
		}
		 finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
	}

	public static Boolean updateQueueDesc(int qid, String desc) {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("SELECT starexec.SetDescForQueue(?,?)");
			procedure.setInt(1, qid);
			procedure.setString(2, desc);
			procedure.execute();
			// A description change affects queue metadata -> clear cache
			invalidateQueueCache();
			return true;
		}
		catch (Exception e) {
			log.error("there was an error setting the description for queue " + qid 
			+ ". Exception was: " + e.getMessage());
			return false;
		}
		 finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}}

	public static void recordQueueSize(int qId, int size) {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("INSERT INTO queue_metrics_history(queue_id, recorded_at, queue_size) VALUES (?, NOW(), ?) ON CONFLICT (queue_id, recorded_at) DO UPDATE SET queue_size = EXCLUDED.queue_size");
			procedure.setInt(1, qId);
			procedure.setInt(2, size);
			procedure.execute();
		} catch (Exception e) {
			log.error("recordQueueSize", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
	}

	public static void pruneOldQueueMetrics(int hours) {
		Connection con = null;
		PreparedStatement procedure = null;
		try {
			con = Common.getConnection();
			procedure = con.prepareStatement("DELETE FROM queue_metrics_history WHERE recorded_at < NOW() - (? * INTERVAL '1 hour')");
			procedure.setInt(1, hours);
			procedure.execute();
		} catch (Exception e) {
			log.error("pruneOldQueueMetrics", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
	}

	public static List<QueueMetric> getQueueMetricsHistory(int queueId, int windowHours) {
		Connection con = null;
		PreparedStatement procedure = null;
		ResultSet results = null;
		List<QueueMetric> metrics = new java.util.LinkedList<>();
		try {
			con = Common.getConnection();
			String query;
			if (windowHours < 1) {
				query = "SELECT EXTRACT(EPOCH FROM recorded_at) * 1000 AS time_ms, queue_size " +
						"FROM queue_metrics_history " +
						"WHERE queue_id = ? AND recorded_at >= NOW() - (? * INTERVAL '1 hour') " +
						"ORDER BY recorded_at ASC";
			} else if (windowHours < 6) {
				query = "SELECT EXTRACT(EPOCH FROM date_trunc('minute', recorded_at)) * 1000 AS time_ms, " +
						"CAST(ROUND(AVG(queue_size)) AS INTEGER) AS queue_size " +
						"FROM queue_metrics_history " +
						"WHERE queue_id = ? AND recorded_at >= NOW() - (? * INTERVAL '1 hour') " +
						"GROUP BY date_trunc('minute', recorded_at) " +
						"ORDER BY 1 ASC";
			} else {
				query = "SELECT FLOOR(EXTRACT(EPOCH FROM recorded_at) / 300) * 300000 AS time_ms, " +
						"CAST(ROUND(AVG(queue_size)) AS INTEGER) AS queue_size " +
						"FROM queue_metrics_history " +
						"WHERE queue_id = ? AND recorded_at >= NOW() - (? * INTERVAL '1 hour') " +
						"GROUP BY 1 " +
						"ORDER BY 1 ASC";
			}
			
			procedure = con.prepareStatement(query);
			procedure.setInt(1, queueId);
			procedure.setInt(2, windowHours);
			results = procedure.executeQuery();
			
			while (results.next()) {
				metrics.add(new QueueMetric(results.getLong("time_ms"), results.getInt("queue_size")));
			}
			return metrics;
		} catch (Exception e) {
			log.error("getQueueMetricsHistory", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
		return metrics;
	}
}

