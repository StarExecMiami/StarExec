package org.starexec.test.junit.database;

import org.junit.*;
import org.starexec.data.database.Common;
import org.starexec.test.util.DatabaseTestSupport;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class UserDiskReconciliationSqlTest extends Common {
    private static Connection connection() throws SQLException {
        Connection c = DriverManager.getConnection(org.starexec.constants.R.POSTGRES_URL,
                org.starexec.constants.R.POSTGRES_USERNAME, org.starexec.constants.R.POSTGRES_PASSWORD);
        if (c.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            c.close();
            throw new SQLException("Quota regression requires READ COMMITTED isolation");
        }
        return c;
    }
    private int userId;
    private int jobId;

    @BeforeClass public static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("UserDiskReconciliationSqlTest");
        Common.initialize();
    }

    private static long value(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }
    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }
    @Before public void fixture() throws Exception {
        try (Connection c = connection()) {
            userId = (int) value(c, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,disk_quota,job_pair_quota,disk_size) VALUES ('quota-"
                    + UUID.randomUUID() + "@example.invalid','Test','Quota','test',now(),'x',10000,100,100) RETURNING id");
            jobId = (int) value(c, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size) VALUES (" + userId + ",'quota-test',0,100) RETURNING id");
        }
    }
    @After public void cleanup() throws Exception {
        try (Connection c = connection()) {
            exec(c, "DELETE FROM starexec.benchmarks WHERE user_id=" + userId);
            exec(c, "DELETE FROM starexec.solvers WHERE user_id=" + userId);
            exec(c, "DELETE FROM starexec.jobs WHERE user_id=" + userId);
            exec(c, "DELETE FROM starexec.users WHERE id=" + userId);
        }
    }
    private long reconcile(Connection c) throws SQLException {
        return value(c, "SELECT starexec.UpdateUserDiskUsage(" + userId + ")");
    }
    private void assertConsistent(Connection c) throws SQLException {
        assertEquals(value(c, "SELECT COALESCE(sum(disk_size),0) FROM (SELECT disk_size FROM starexec.jobs WHERE user_id=" + userId
                + " AND NOT deleted UNION ALL SELECT disk_size FROM starexec.solvers WHERE user_id=" + userId
                + " AND NOT deleted UNION ALL SELECT disk_size FROM starexec.benchmarks WHERE user_id=" + userId + " AND NOT deleted) r"),
                value(c, "SELECT disk_size FROM starexec.users WHERE id=" + userId));
    }
    private static void awaitBlock(Connection observer, int waiter, int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (value(observer, "SELECT CASE WHEN " + blocker + "=ANY(pg_blocking_pids(" + waiter + ")) THEN 1 ELSE 0 END") == 1) return;
            Thread.sleep(10);
        }
        fail("Expected observed database lock wait");
    }
    @Test public void concurrentCommittedDeltaSurvivesReconciliation() throws Exception {
        concurrentDelta(false);
    }
    @Test public void userFirstProductionDeltaSurvivesReconciliation() throws Exception {
        concurrentDelta(true);
    }
    private void concurrentDelta(boolean userFirst) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection writer = connection(); Connection reader = connection(); Connection observer = connection()) {
            writer.setAutoCommit(false);
            int writerPid = (int) value(writer, "SELECT pg_backend_pid()");
            int readerPid = (int) value(reader, "SELECT pg_backend_pid()");
            exec(reader, "SET statement_timeout='10s'");
            if (userFirst) {
                exec(writer, "SELECT starexec.UpdateJobDiskSize(" + jobId + ",150)");
            } else {
                value(writer, "SELECT id FROM starexec.jobs WHERE id=" + jobId + " FOR UPDATE");
                exec(writer, "UPDATE starexec.jobs SET disk_size=disk_size+50 WHERE id=" + jobId);
                exec(writer, "UPDATE starexec.users SET disk_size=disk_size+50 WHERE id=" + userId);
            }
            Future<Long> result = worker.submit(() -> reconcile(reader));
            try {
                awaitBlock(observer, readerPid, writerPid);
                writer.commit();
                assertEquals(0L, result.get(10, TimeUnit.SECONDS).longValue());
                assertConsistent(observer);
            } finally { writer.rollback(); }
        } finally { worker.shutdownNow(); }
    }
    @Test public void reconciliationDoesNotAcquireJobLockAfterUserLock() throws Exception {
        reconciliationFirst(false);
    }
    @Test public void reconciliationBeforeUserFirstProductionWriter() throws Exception {
        reconciliationFirst(true);
    }
    private void reconciliationFirst(boolean userFirst) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection reader = connection(); Connection writer = connection(); Connection observer = connection()) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            int readerPid = (int) value(reader, "SELECT pg_backend_pid()");
            int writerPid = (int) value(writer, "SELECT pg_backend_pid()");
            exec(reader, "SET statement_timeout='5s'");
            exec(writer, "SET statement_timeout='10s'");
            reconcile(reader);
            if (!userFirst) value(writer, "SELECT id FROM starexec.jobs WHERE id=" + jobId + " FOR UPDATE");
            Future<?> delta = worker.submit(() -> {
                if (userFirst) exec(writer, "SELECT starexec.UpdateJobDiskSize(" + jobId + ",150)");
                else {
                    exec(writer, "UPDATE starexec.users SET disk_size=disk_size+50 WHERE id=" + userId);
                    exec(writer, "UPDATE starexec.jobs SET disk_size=disk_size+50 WHERE id=" + jobId);
                }
                writer.commit();
                return null;
            });
            try {
                awaitBlock(observer, writerPid, readerPid);
                assertEquals(0L, reconcile(reader));
                reader.commit();
                delta.get(10, TimeUnit.SECONDS);
                assertConsistent(observer);
            } finally { reader.rollback(); writer.rollback(); }
        } finally { worker.shutdownNow(); }
    }
    @Test public void mixedResourcesExcludeDeletedAndAreIdempotent() throws Exception {
        try (Connection c = connection()) {
            exec(c, "INSERT INTO starexec.solvers(user_id,name,path,disk_size,deleted) VALUES (" + userId + ",'live','test',20,false),(" + userId + ",'deleted','test',999,true)");
            exec(c, "INSERT INTO starexec.benchmarks(user_id,name,path,disk_size,deleted) VALUES (" + userId + ",'live','test',30,false),(" + userId + ",'deleted','test',999,true)");
            exec(c, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,deleted) VALUES (" + userId + ",'deleted',0,999,true)");
            assertEquals(-50L, reconcile(c));
            assertConsistent(c);
            assertEquals(0L, reconcile(c));
        }
    }
    @Test public void missingUserStillRaisesNotFound() throws Exception {
        try (Connection c = connection()) {
            try { value(c, "SELECT starexec.UpdateUserDiskUsage(-1)"); fail("Expected missing-user error"); }
            catch (SQLException e) { assertEquals("P0002", e.getSQLState()); }
        }
    }
}
