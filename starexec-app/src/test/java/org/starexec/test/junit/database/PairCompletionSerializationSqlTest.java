package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.starexec.constants.R;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent pair completions must not lose the job-completion decision, and a pair status
 * read must not be stale by the time it is written.
 *
 * Both interleavings are forced with two open transactions rather than timing: the first
 * transaction performs its write and stays uncommitted, the second is started on another
 * thread, and the test waits (bounded) until that backend is either finished or blocked on a
 * lock before letting the first commit.
 */
public class PairCompletionSerializationSqlTest {
    private static final int RUNNING = StatusCode.STATUS_RUNNING.getVal();
    private static final int COMPLETE = StatusCode.STATUS_COMPLETE.getVal();
    private static final int KILLED = 21;
    private static final int PROCESSING = 22;
    private static final long WAIT_MILLIS = 15_000;

    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[]{"legacy"}, new Object[]{"precise"}, new Object[]{"pairLevel"});
    }

    private String kind;
    private int user, job, pairA, pairB;
    private final List<Connection> open = new ArrayList<>();
    private ExecutorService executor;

    public void initPairCompletionSerializationSqlTest(String kind) { this.kind = kind; }

    @BeforeAll public static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("PairCompletionSerializationSqlTest");
    }

    private Connection connection(boolean autoCommit) throws SQLException {
        Connection c = DriverManager.getConnection(R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD);
        open.add(c);
        try (Statement s = c.createStatement()) { s.execute("SET search_path=starexec,public"); }
        c.setAutoCommit(autoCommit);
        return c;
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }

    @BeforeEach public void seed() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        Connection c = connection(true);
        // Own user with absolute values: never the shared lowest-id user.
        user = (int) scalar(c, "INSERT INTO users(email,first_name,last_name,institution,created,password,disk_quota,job_pair_quota,disk_size) VALUES ('pairlock-" + UUID.randomUUID() + "@example.invalid','Test','PairLock','test',now(),'x',10000,100,0) RETURNING id");
        job = (int) scalar(c, "INSERT INTO jobs(user_id,name,total_pairs,disk_size) VALUES (" + user + ",'pair-completion-serialization',2,0) RETURNING id");
        pairA = seedPair(c);
        pairB = seedPair(c);
    }

    private int seedPair(Connection c) throws SQLException {
        int pair = (int) scalar(c, "INSERT INTO job_pairs(job_id,status_code,primary_jobpair_data) VALUES (" + job + "," + RUNNING + ",1) RETURNING id");
        execute(c, "INSERT INTO jobpair_stage_data(jobpair_id,stage_number,status_code,disk_size) VALUES (" + pair + ",1," + RUNNING + ",0)");
        return pair;
    }

    @AfterEach public void cleanup() throws Exception {
        executor.shutdownNow();
        for (Connection c : open) {
            try { if (!c.isClosed() && !c.getAutoCommit()) c.rollback(); } catch (SQLException ignored) { }
        }
        try (Connection c = DriverManager.getConnection(R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD)) {
            execute(c, "SET search_path=starexec,public");
            execute(c, "DELETE FROM job_pairs WHERE job_id=" + job);
            execute(c, "DELETE FROM jobs WHERE id=" + job);
            execute(c, "DELETE FROM users WHERE id=" + user);
        }
        for (Connection c : open) {
            try { c.close(); } catch (SQLException ignored) { }
        }
    }

    /** Records a terminal result for the pair with the routine under test. */
    private void complete(Connection c, int pair, int status) throws SQLException {
        String sql;
        switch (kind) {
            case "legacy":
                sql = "CALL UpdatePairStatus(" + pair + "," + status + ")";
                break;
            case "precise":
                sql = "SELECT UpdatePairStatusPrecise(" + pair + ",1," + status + "," + StatusCode.STATUS_NOT_REACHED.getVal() + ",false)";
                break;
            default:
                sql = "SELECT UpdatePairStatusPairLevel(" + pair + "," + status + "," + StatusCode.STATUS_NOT_REACHED.getVal() + ")";
        }
        execute(c, sql);
    }

    /** Waits until the future is done, or its backend is waiting on a lock. Bounded. */
    private void awaitDoneOrLockWait(Future<?> f, int backendPid) throws Exception {
        Connection observer = connection(true);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (!f.isDone()) {
            long blocked = scalar(observer, "SELECT count(*) FROM pg_stat_activity WHERE pid=" + backendPid + " AND wait_event_type='Lock'");
            if (blocked > 0) return;
            if (System.nanoTime() > deadline) fail("second writer neither finished nor blocked on a lock within " + WAIT_MILLIS + " ms");
            Thread.sleep(5); // poll cadence only; the wait itself is bounded by the deadline
        }
    }

    @MethodSource("cases")
    @ParameterizedTest(name = "{0}")
    @Timeout(value = 60_000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void twoConcurrentLastPairsStillCompleteTheJob(String kind) throws Exception {
        initPairCompletionSerializationSqlTest(kind);
        Connection first = connection(false);
        Connection second = connection(false);
        int secondPid = (int) scalar(second, "SELECT pg_backend_pid()");
        second.commit();

        complete(first, pairA, COMPLETE);          // sees pairB still running; uncommitted
        Future<?> f = executor.submit(() -> {
            complete(second, pairB, COMPLETE);     // must not miss pairA
            return null;
        });
        awaitDoneOrLockWait(f, secondPid);
        first.commit();
        f.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        second.commit();

        Connection check = connection(true);
        assertEquals(2, scalar(check,
            "SELECT count(*) FROM job_pairs WHERE job_id=" + job + " AND status_code=" + COMPLETE), "Both pairs are terminal");
        assertEquals(1, scalar(check, "SELECT (completed IS NOT NULL)::int FROM jobs WHERE id=" + job),
            "jobs.completed must be stamped once the last pair finishes");
    }

    @MethodSource("cases")
    @ParameterizedTest(name = "{0}")
    @Timeout(value = 60_000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void jobStaysOpenWhileAnotherPairIsStillRunning(String kind) throws Exception {
        initPairCompletionSerializationSqlTest(kind);
        Connection first = connection(false);
        complete(first, pairA, COMPLETE);
        first.commit();
        assertEquals(1, scalar(connection(true), "SELECT (completed IS NULL)::int FROM jobs WHERE id=" + job),
            "A running sibling must keep the job open");
    }

    @MethodSource("cases")
    @ParameterizedTest(name = "{0}")
    @Timeout(value = 60_000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void terminalResultIsNotOverwrittenByAConcurrentNonTerminalWrite(String kind) throws Exception {
        initPairCompletionSerializationSqlTest(kind);
        Connection killer = connection(false);
        Connection writer = connection(false);
        int writerPid = (int) scalar(writer, "SELECT pg_backend_pid()");
        writer.commit();

        complete(killer, pairA, KILLED);           // uncommitted terminal write, any routine
        // The competing writer is always UpdatePairStatus: it is the one that read the status unlocked.
        Future<?> f = executor.submit(() -> {
            execute(writer, "CALL UpdatePairStatus(" + pairA + "," + PROCESSING + ")");
            return null;
        });
        awaitDoneOrLockWait(f, writerPid);
        killer.commit();
        try {
            f.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            writer.commit();
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof SQLException
                    && "P0001".equals(((SQLException) expected.getCause()).getSQLState()),
                "A terminal pair moved back to non-terminal is an illegal transition: " + expected.getCause());
            writer.rollback();
        }

        assertEquals(KILLED, scalar(connection(true), "SELECT status_code FROM job_pairs WHERE id=" + pairA),
            "KILLED must survive the concurrent write");
    }

    @MethodSource("cases")
    @ParameterizedTest(name = "{0}")
    @Timeout(value = 60_000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void terminalWriteAfterANonTerminalOneIsApplied(String kind) throws Exception {
        initPairCompletionSerializationSqlTest(kind);
        Connection processing = connection(false);
        Connection killer = connection(false);
        int killerPid = (int) scalar(killer, "SELECT pg_backend_pid()");
        killer.commit();

        execute(processing, "CALL UpdatePairStatus(" + pairA + "," + PROCESSING + ")");
        Future<?> f = executor.submit(() -> {
            complete(killer, pairA, KILLED);
            return null;
        });
        awaitDoneOrLockWait(f, killerPid);
        processing.commit();
        f.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        killer.commit();

        assertEquals(KILLED, scalar(connection(true), "SELECT status_code FROM job_pairs WHERE id=" + pairA));
    }
}
