package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Common;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Pipelines;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Configuration;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Queue;
import org.starexec.data.to.Solver;
import org.starexec.data.to.pipelines.JoblineStage;
import org.starexec.data.to.pipelines.PipelineStage;
import org.starexec.data.to.pipelines.SolverPipeline;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What the single owned transaction costs in lock time.
 *
 * <p>Atomicity replaced several short transactions with one spanning pipeline and job
 * creation, and the arrangement it replaced was deliberately short precisely to avoid holding
 * locks on the space tables. That trade is defensible only if the transaction is bounded, so
 * this measures it rather than asserting it.
 *
 * <p>Measured from <em>inside</em> the transaction, on its own connection. Sampling
 * {@code pg_stat_activity} from another session recorded nothing: each sample costs a few
 * milliseconds and the transaction is shorter than that, so the window was missed every time.
 * A connection can see its own locks and its own {@code xact_start}, which is exact.
 */
public class JobCreationLockFootprintSqlTest extends Common {

	private static final String TAG = "lock-footprint-probe";
	private static final int USER_ID = 90501;
	private static final int SPACE_ID = 90501;
	private static final int QUEUE_ID = 90501;
	private static final int SOLVER_ID = 90501;
	private static final int CONFIG_ID = 90501;
	private static final int BENCH_ID = 90501;
	private static final int PAIRS = 8;

	/**
	 * Generous on purpose. This is a regression guard against something slow moving inside the
	 * transaction -- a filesystem walk, an archive read, a backend call -- not a benchmark.
	 * A bounded run of pure DB work is orders of magnitude under this.
	 */
	private static final long BUDGET_MS = 2000;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("JobCreationLockFootprintSqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		cleanUp();
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("INSERT INTO starexec.users (id,email,first_name,last_name,institution,created,"
					+ "password,disk_quota,disk_size) VALUES (" + USER_ID + ",'" + TAG
					+ "@test','A','P','I',NOW(),'x',1000000000,0)");
			s.execute("INSERT INTO starexec.spaces (id,name,created,locked) VALUES ("
					+ SPACE_ID + ",'" + TAG + "',NOW(),false)");
			s.execute("INSERT INTO starexec.queues (id,name,status) VALUES ("
					+ QUEUE_ID + ",'" + TAG + "','ACTIVE') ON CONFLICT DO NOTHING");
			s.execute("INSERT INTO starexec.solvers (id,user_id,name,uploaded,path,description,"
					+ "downloadable,disk_size) VALUES (" + SOLVER_ID + "," + USER_ID + ",'" + TAG
					+ "',NOW(),'/tmp/" + TAG + "','d',false,0)");
			s.execute("INSERT INTO starexec.configurations (id,solver_id,name,description,updated)"
					+ " VALUES (" + CONFIG_ID + "," + SOLVER_ID + ",'" + TAG + "','d',NOW())");
			s.execute("INSERT INTO starexec.benchmarks (id,user_id,name,uploaded,path,description,"
					+ "downloadable,disk_size) VALUES (" + BENCH_ID + "," + USER_ID + ",'" + TAG
					+ "',NOW(),'/tmp/" + TAG + "','d',false,0)");
		}
	}

	@After
	public void cleanUp() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("DELETE FROM starexec.jobs WHERE name LIKE '" + TAG + "%'");
			s.execute("DELETE FROM starexec.solver_pipelines WHERE name LIKE '" + TAG + "%'");
			s.execute("DELETE FROM starexec.configurations WHERE id=" + CONFIG_ID);
			s.execute("DELETE FROM starexec.solvers WHERE id=" + SOLVER_ID);
			s.execute("DELETE FROM starexec.benchmarks WHERE id=" + BENCH_ID);
			s.execute("DELETE FROM starexec.queues WHERE id=" + QUEUE_ID);
			s.execute("DELETE FROM starexec.spaces WHERE id=" + SPACE_ID);
			s.execute("DELETE FROM starexec.users WHERE id=" + USER_ID);
		}
	}

	@Test
	public void theOwnedTransactionIsBounded() throws Exception {
		long[] ageMs = {-1};
		int[] locks = {-1};
		int[] relations = {-1};
		StringBuilder relationNames = new StringBuilder();
		StringBuilder modes = new StringBuilder();
		StringBuilder blockingLocks = new StringBuilder();

		long wall = System.nanoTime();
		Common.inTransaction(con -> {
			SolverPipeline pipe = pipeline();
			assertTrue(Pipelines.addPipelineToDatabase(pipe, con) > 0);
			assertTrue(Jobs.add(job(pipe), SPACE_ID, con));

			// Read at the end of the transaction, where the footprint is largest.
			try (Statement s = con.createStatement();
			     ResultSet rs = s.executeQuery(
					     "SELECT round(extract(epoch from (clock_timestamp() - xact_start)) * 1000)"
					     + " FROM pg_stat_activity WHERE pid = pg_backend_pid()")) {
				ageMs[0] = rs.next() ? rs.getLong(1) : -1;
			}
			try (Statement s = con.createStatement();
			     ResultSet rs = s.executeQuery(
					     "SELECT count(*), count(DISTINCT relation),"
					     + " coalesce(string_agg(DISTINCT mode, ','), ''),"
					     + " coalesce((SELECT string_agg(DISTINCT c.relname, ',') FROM pg_locks l"
					     + "   JOIN pg_class c ON c.oid = l.relation WHERE l.pid = pg_backend_pid()"
					     + "   AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname='starexec')), '')"
					     + " FROM pg_locks WHERE pid = pg_backend_pid()")) {
				if (rs.next()) {
					locks[0] = rs.getInt(1);
					relations[0] = rs.getInt(2);
					modes.append(rs.getString(3));
					relationNames.append(rs.getString(4));
				}
			}
			// Anything on a relation that would block another writer.
			try (Statement s = con.createStatement();
			     ResultSet rs = s.executeQuery(
					     "SELECT coalesce(string_agg(DISTINCT c.relname || ':' || l.mode, ','), '')"
					     + " FROM pg_locks l JOIN pg_class c ON c.oid = l.relation"
					     + " WHERE l.pid = pg_backend_pid() AND l.relation IS NOT NULL"
					     + " AND l.mode IN ('ShareLock','ShareRowExclusiveLock','ExclusiveLock',"
					     + "                 'AccessExclusiveLock')")) {
				if (rs.next()) {
					blockingLocks.append(rs.getString(1));
				}
			}
			return null;
		});
		long wallMs = (System.nanoTime() - wall) / 1_000_000;

		System.out.println("LOCK-FOOTPRINT|pairs=" + PAIRS
				+ "|transaction_age_ms=" + ageMs[0]
				+ "|wall_ms=" + wallMs
				+ "|lock_entries=" + locks[0]
				+ "|distinct_relations=" + relations[0]
				+ "|modes=" + modes
				+ "|blocking_relation_locks=" + (blockingLocks.length() == 0 ? "none" : blockingLocks)
				+ "|starexec_relations=" + relationNames);

		assertTrue("the transaction must actually have been measured", ageMs[0] >= 0);
		assertTrue("the owned transaction took " + ageMs[0] + " ms, over the " + BUDGET_MS
				+ " ms budget -- something slow is running inside it", ageMs[0] < BUDGET_MS);
		// The mode that would matter is one taken on a *relation*. RowExclusiveLock is the
		// ordinary INSERT/UPDATE mode and conflicts only with DDL-grade locks, so concurrent
		// writers are unaffected; ExclusiveLock appears on every transaction's own
		// transactionid and says nothing about tables.
		assertEquals("job creation must not take a blocking lock on any table: " + blockingLocks,
				"", blockingLocks.toString());

		// And the work really happened, so the numbers describe a real creation.
		try (Connection con = Common.getConnection();
		     Statement s = con.createStatement();
		     ResultSet rs = s.executeQuery("SELECT count(*) FROM starexec.job_pairs jp"
				     + " JOIN starexec.jobs j ON j.id = jp.job_id WHERE j.name LIKE '" + TAG + "%'")) {
			assertTrue(rs.next());
			assertEquals("the measured transaction must have created every pair", PAIRS, rs.getInt(1));
		}
	}

	// ---------------------------------------------------------------- fixtures

	private SolverPipeline pipeline() {
		SolverPipeline pipe = new SolverPipeline();
		pipe.setName(TAG + "-pipe");
		pipe.setUserId(USER_ID);
		for (int i = 0; i < 2; i++) {
			PipelineStage stage = new PipelineStage();
			stage.setConfigId(CONFIG_ID);
			stage.setPrimary(i == 1);
			pipe.addStage(stage);
		}
		return pipe;
	}

	private Job job(SolverPipeline pipe) {
		Job job = new Job();
		job.setName(TAG + "-job");
		job.setUserId(USER_ID);
		job.setDescription("lock footprint");
		job.setPrimarySpace(SPACE_ID);
		job.setCpuTimeout(10);
		job.setWallclockTimeout(10);
		job.setMaxMemory(1073741824L);
		job.setSeed(0);
		job.setSoftTimeLimit(10);
		job.setKillDelay(0);

		Queue queue = new Queue();
		queue.setId(QUEUE_ID);
		queue.setName(TAG);
		job.setQueue(queue);

		List<JobPair> pairs = new ArrayList<>();
		for (int i = 0; i < PAIRS; i++) {
			Solver solver = new Solver();
			solver.setId(SOLVER_ID);
			solver.setName(TAG);
			Configuration config = new Configuration();
			config.setId(CONFIG_ID);
			config.setName(TAG);
			Benchmark bench = new Benchmark();
			bench.setId(BENCH_ID);
			bench.setName(TAG);

			JobPair pair = new JobPair();
			pair.setBench(bench);
			pair.setPath(TAG + "/sub" + (i % 3));
			pair.setPrimaryStageNumber(1);
			JoblineStage stage = new JoblineStage();
			stage.setStageNumber(1);
			stage.setSolver(solver);
			stage.setConfiguration(config);
			stage.setStageId(pipe.getStages().get(0).getId());
			pair.addStage(stage);
			pairs.add(pair);
		}
		job.setJobPairs(pairs);
		return job;
	}
}
