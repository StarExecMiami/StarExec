package org.starexec.test.junit.database;

import org.junit.After;
import org.junit.AfterClass;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Does the real Java job-creation path actually roll back its own partial writes?
 *
 * <p>Two other tests look like they answer this and do not. Driving PostgreSQL directly --
 * {@code BEGIN}, force an error, {@code COMMIT} -- establishes that PostgreSQL is atomic,
 * which was never in doubt. {@code BorrowedConnectionPolicyTest} establishes a source-level
 * shape. Neither shows that {@code Common.inTransaction -> Pipelines -> Jobs -> JobPairs}
 * runs on one connection and takes its own writes down with it.
 *
 * <p>So these invoke that call graph and make it fail from underneath, at each of the two
 * boundaries the invariant names. The failure is induced by a trigger that fires only for
 * this test's uniquely named fixture, so no fault-injection seam is added to production code
 * and no other row can be affected. The trigger is dropped in teardown whatever happens.
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point the {@code STAREXEC_DB_*}
 * variables at a <strong>disposable</strong> database.
 */
public class PipelineJobAtomicitySqlTest extends Common {

	/** Every fixture row this test creates carries this, and the triggers key on it. */
	private static final String TAG = "atomicity-probe";

	private static final int USER_ID = 90401;
	private static final int SPACE_ID = 90401;
	private static final int QUEUE_ID = 90401;
	private static final int SOLVER_ID = 90401;
	private static final int CONFIG_ID = 90401;
	private static final int BENCH_ID = 90401;

	@BeforeClass
	public static void requireDatabase() {
		DatabaseTestSupport.assumeDatabaseAvailable("PipelineJobAtomicitySqlTest");
		Common.initialize();
	}

	@Before
	public void seed() throws SQLException {
		cleanUp();
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("INSERT INTO starexec.users (id,email,first_name,last_name,institution,"
					+ "created,password,disk_quota,disk_size) VALUES (" + USER_ID + ",'"
					+ TAG + "@test','A','P','I',NOW(),'x',1000000000,0)");
			s.execute("INSERT INTO starexec.spaces (id,name,created,locked) VALUES ("
					+ SPACE_ID + ",'" + TAG + "',NOW(),false)");
			s.execute("INSERT INTO starexec.queues (id,name,status) VALUES ("
					+ QUEUE_ID + ",'" + TAG + "','ACTIVE') ON CONFLICT DO NOTHING");
			s.execute("INSERT INTO starexec.solvers (id,user_id,name,uploaded,path,description,"
					+ "downloadable,disk_size) VALUES (" + SOLVER_ID + "," + USER_ID + ",'"
					+ TAG + "',NOW(),'/tmp/" + TAG + "','d',false,0)");
			s.execute("INSERT INTO starexec.configurations (id,solver_id,name,description,updated)"
					+ " VALUES (" + CONFIG_ID + "," + SOLVER_ID + ",'" + TAG + "','d',NOW())");
			s.execute("INSERT INTO starexec.benchmarks (id,user_id,name,uploaded,path,description,"
					+ "downloadable,disk_size) VALUES (" + BENCH_ID + "," + USER_ID + ",'"
					+ TAG + "',NOW(),'/tmp/" + TAG + "','d',false,0)");
		}
	}

	@After
	public void cleanUp() throws SQLException {
		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			dropProbeTrigger(s, "pipeline_stages");
			dropProbeTrigger(s, "jobpair_stage_data");
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

	@AfterClass
	public static void noProbeTriggersSurvive() throws SQLException {
		if (!DatabaseTestSupport.isDatabaseConfigured()) {
			return;
		}
		try (Connection con = Common.getConnection()) {
			assertEquals("a fault-injection trigger must never outlive its test", 0,
					count(con, "SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'probe_%'"));
		}
	}

	// ------------------------------------------------------- boundary 1: stages

	/**
	 * Stage insertion fails after the pipeline row is written. Before this change pipeline
	 * creation ran on its own connection in autoCommit, so the pipeline row was already
	 * durable and stayed durable -- which is exactly what the reproduction database holds:
	 * two committed pipelines with zero stages.
	 */
	@Test
	public void aFailureAtStageInsertionLeavesNoPipeline() throws Exception {
		installProbeTrigger("pipeline_stages");

		Counts before = counts();
		int id = Pipelines.addPipelineToDatabase(pipeline(TAG + "-stages"));

		assertEquals("creation must report failure, not an id", -1, id);
		assertNoResidue(before);
	}

	// -------------------------------------------- boundary 2: pairs/stage data

	/**
	 * Pair stage-data insertion fails, deep inside {@code Jobs.add}, after the pipeline, its
	 * stages, the job row and the pair rows have all been written on the same connection.
	 * Everything must go, including the pipeline created earlier in the same transaction --
	 * which is the part that spanned two connections before.
	 */
	@Test
	public void aFailureAtStageDataInsertionLeavesNoJobAndNoPipeline() throws Exception {
		installProbeTrigger("jobpair_stage_data");

		Counts before = counts();
		try {
			// The shape JobUtil uses: one transaction across pipeline creation and job
			// creation both.
			Common.inTransaction(con -> {
				SolverPipeline pipe = pipeline(TAG + "-pairs");
				int pipeId = Pipelines.addPipelineToDatabase(pipe, con);
				assertTrue("the pipeline should persist before the induced failure", pipeId > 0);
				if (!Jobs.add(job(TAG + "-job", pipe), SPACE_ID, con)) {
					throw new SQLException("job creation failed");
				}
				return null;
			});
			fail("the induced stage-data failure must surface");
		} catch (SQLException expected) {
			// intended
		}

		assertNoResidue(before);
	}

	// ------------------------------------------------------------ observations

	/** Everything the invariant says must not be durably committed. */
	private static final class Counts {
		int pipelines, stages, jobs, pairs, stageData, jobSpaces, initiated;
	}

	private Counts counts() throws SQLException {
		Counts c = new Counts();
		try (Connection con = Common.getConnection()) {
			c.pipelines = count(con, "SELECT count(*) FROM starexec.solver_pipelines");
			c.stages = count(con, "SELECT count(*) FROM starexec.pipeline_stages");
			c.jobs = count(con, "SELECT count(*) FROM starexec.jobs");
			c.pairs = count(con, "SELECT count(*) FROM starexec.job_pairs");
			c.stageData = count(con, "SELECT count(*) FROM starexec.jobpair_stage_data");
			c.jobSpaces = count(con, "SELECT count(*) FROM starexec.job_spaces");
			c.initiated = count(con, "SELECT occurrences FROM starexec.report_data"
					+ " WHERE event_name='jobs initiated' AND queue_name IS NULL");
		}
		return c;
	}

	private void assertNoResidue(Counts before) throws SQLException {
		Counts after = counts();
		assertEquals("no pipeline may survive", before.pipelines, after.pipelines);
		assertEquals("no pipeline stage may survive", before.stages, after.stages);
		assertEquals("no job may survive", before.jobs, after.jobs);
		assertEquals("no job pair may survive", before.pairs, after.pairs);
		assertEquals("no stage data may survive", before.stageData, after.stageData);
		assertEquals("no job space may survive", before.jobSpaces, after.jobSpaces);
		assertEquals("the jobs-initiated counter must not drift", before.initiated, after.initiated);

		// And the next borrower must get a connection it can use. The pool applies
		// defaultAutoCommit when a physical connection is created, not on return, and is
		// configured without rollbackOnReturn -- so a transaction left open here would be
		// inherited rather than cleaned up.
		try (Connection con = Common.getConnection()) {
			assertTrue("a failed transaction must not return a connection with autoCommit off",
					con.getAutoCommit());
			assertEquals("the next borrower must be able to query", 1,
					count(con, "SELECT 1"));
		}
	}

	// ---------------------------------------------------------------- fixtures

	private SolverPipeline pipeline(String name) {
		SolverPipeline pipe = new SolverPipeline();
		pipe.setName(name);
		pipe.setUserId(USER_ID);
		PipelineStage stage = new PipelineStage();
		stage.setConfigId(CONFIG_ID);
		stage.setPrimary(true);
		pipe.addStage(stage);
		return pipe;
	}

	private Job job(String name, SolverPipeline pipe) {
		Job job = new Job();
		job.setName(name);
		job.setUserId(USER_ID);
		job.setDescription("atomicity probe");
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
		pair.setPath(TAG);
		pair.setPrimaryStageNumber(1);
		JoblineStage stage = new JoblineStage();
		stage.setStageNumber(1);
		stage.setSolver(solver);
		stage.setConfiguration(config);
		stage.setStageId(pipe.getStages().get(0).getId());
		pair.addStage(stage);
		job.addJobPair(pair);
		return job;
	}

	// ------------------------------------------------------- fault injection

	/**
	 * Fails inserts into {@code table} for this test's fixture only.
	 *
	 * <p>Keyed on the fixture's own user id rather than on the table under test, so it cannot
	 * disturb another row even if this test is run against a populated database.
	 */
	private void installProbeTrigger(String table) throws SQLException {
		String owner = table.equals("pipeline_stages")
				? "(SELECT user_id FROM starexec.solver_pipelines WHERE id = NEW.pipeline_id)"
				: "(SELECT j.user_id FROM starexec.job_pairs p"
				  + " JOIN starexec.jobs j ON j.id = p.job_id WHERE p.id = NEW.jobpair_id)";

		try (Connection con = Common.getConnection(); Statement s = con.createStatement()) {
			s.execute("CREATE OR REPLACE FUNCTION starexec.probe_fail_" + table + "()"
					+ " RETURNS trigger AS $probe$ BEGIN"
					+ "   IF " + owner + " = " + USER_ID + " THEN"
					+ "     RAISE EXCEPTION 'induced failure at " + table + "';"
					+ "   END IF;"
					+ "   RETURN NEW;"
					+ " END; $probe$ LANGUAGE plpgsql;");
			s.execute("CREATE TRIGGER probe_" + table + " BEFORE INSERT ON starexec." + table
					+ " FOR EACH ROW EXECUTE FUNCTION starexec.probe_fail_" + table + "()");
		}
	}

	private static void dropProbeTrigger(Statement s, String table) throws SQLException {
		s.execute("DROP TRIGGER IF EXISTS probe_" + table + " ON starexec." + table);
		s.execute("DROP FUNCTION IF EXISTS starexec.probe_fail_" + table + "()");
	}

	private static int count(Connection con, String sql) throws SQLException {
		try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			return rs.next() ? rs.getInt(1) : 0;
		}
	}
}
