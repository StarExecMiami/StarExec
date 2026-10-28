package org.starexec.test.junit.database;

import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.starexec.constants.R;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.*;
import java.util.*;

import static org.junit.Assert.*;

/** A duplicate terminal report preserves a known completion time, but repairs NULL. */
@RunWith(Parameterized.class)
public class JobCompletionIdempotenceSqlTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[]{"precise"}, new Object[]{"pairLevel"}, new Object[]{"legacy"});
    }

    private final String kind;
    private int user, job, pair;

    public JobCompletionIdempotenceSqlTest(String kind) { this.kind = kind; }

    @BeforeClass public static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("JobCompletionIdempotenceSqlTest");
    }

    private static Connection connection() throws SQLException {
        Connection c = DriverManager.getConnection(R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD);
        try (Statement s = c.createStatement()) { s.execute("SET search_path=starexec,public"); }
        return c;
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next()); return r.getLong(1);
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }

    @Before public void seed() throws Exception {
        try (Connection c = connection()) {
            user = (int) scalar(c, "INSERT INTO users(email,first_name,last_name,institution,created,password,disk_quota,job_pair_quota,disk_size) VALUES ('completion-" + UUID.randomUUID() + "@example.invalid','Test','Completion','test',now(),'x',10000,100,0) RETURNING id");
            job = (int) scalar(c, "INSERT INTO jobs(user_id,name,total_pairs,disk_size) VALUES (" + user + ",'completion-idempotence',1,0) RETURNING id");
            pair = (int) scalar(c, "INSERT INTO job_pairs(job_id,status_code,primary_jobpair_data) VALUES (" + job + ",4,1) RETURNING id");
            execute(c, "INSERT INTO jobpair_stage_data(jobpair_id,stage_number,status_code,disk_size) VALUES (" + pair + ",1,4,0),(" + pair + ",2,1,0)");
        }
    }

    @After public void cleanup() throws Exception {
        try (Connection c = connection()) {
            execute(c, "DELETE FROM job_pairs WHERE job_id=" + job);
            execute(c, "DELETE FROM jobs WHERE id=" + job);
            execute(c, "DELETE FROM users WHERE id=" + user);
        }
    }

    private void apply(Connection c) throws SQLException {
        if (kind.equals("legacy")) {
            execute(c, "CALL UpdatePairStatus(" + pair + "," + StatusCode.STATUS_COMPLETE.getVal() + ")");
            assertEquals(StatusCode.STATUS_COMPLETE.getVal(),
                scalar(c, "SELECT status_code FROM job_pairs WHERE id=" + pair));
            return;
        }
        String arguments = pair + "," + (kind.equals("precise") ? "1," : "")
            + StatusCode.STATUS_COMPLETE.getVal() + "," + StatusCode.STATUS_NOT_REACHED.getVal()
            + (kind.equals("precise") ? ",false" : "");
        String routine = kind.equals("precise") ? "UpdatePairStatusPrecise" : "UpdatePairStatusPairLevel";
        assertEquals("Both first and duplicate terminal reports must be accepted", 1,
            scalar(c, "SELECT " + routine + "(" + arguments + ")::int"));
    }

    private String scientificState(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
            "SELECT jsonb_build_object('pair',(SELECT to_jsonb(p) FROM job_pairs p WHERE id=" + pair
                + "),'stages',(SELECT jsonb_agg(to_jsonb(d) ORDER BY stage_number) FROM jobpair_stage_data d WHERE jobpair_id=" + pair
                + "),'completion',(SELECT jsonb_agg(to_jsonb(x)) FROM job_pair_completion x WHERE pair_id=" + pair + "))::text")) {
            assertTrue(r.next()); return r.getString(1);
        }
    }

    @Test public void duplicatePreservesKnownJobCompletionTimestamp() throws Exception {
        try (Connection c = connection()) {
            apply(c);
            execute(c, "UPDATE jobs SET completed=TIMESTAMP '2001-02-03 04:05:06' WHERE id=" + job);
            String before = scientificState(c);
            apply(c);
            assertEquals("Duplicate must preserve the known completion timestamp", 1,
                scalar(c, "SELECT (completed=TIMESTAMP '2001-02-03 04:05:06')::int FROM jobs WHERE id=" + job));
            assertEquals(before, scientificState(c));
        }
    }

    @Test public void duplicateRepairsMissingJobCompletionTimestamp() throws Exception {
        try (Connection c = connection()) {
            apply(c);
            execute(c, "UPDATE jobs SET completed=NULL WHERE id=" + job);
            String before = scientificState(c);
            apply(c);
            assertEquals(1, scalar(c, "SELECT (completed IS NOT NULL)::int FROM jobs WHERE id=" + job));
            assertEquals(before, scientificState(c));
            assertEquals(1, scalar(c, "SELECT count(*) FROM job_pair_completion WHERE pair_id=" + pair));
        }
    }
}
