package org.starexec.test.junit.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.data.database.Common;
import org.starexec.data.database.Queues;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.test.util.DatabaseTestSupport;

/**
 * {@link Queues#countPendingPairs} against the real SQL.
 *
 * <p>The dispatcher's line for a queue with no nodes gave the number of enqueued pairs as the
 * number waiting, and a queue with no nodes never enqueues one: it said "0 pair(s) waiting"
 * while 24 pairs waited. The pairs that wait for a queue are the pairs of its jobs that wait to
 * be submitted, the same ones the dispatcher reads for the queue (GetPendingJobs).
 *
 * <p>Skips unless a PostgreSQL instance is configured. Point {@code STAREXEC_DB_URL} /
 * {@code STAREXEC_DB_USER} / {@code STAREXEC_DB_PASSWORD} at a <strong>disposable</strong>
 * database: these tests insert and delete rows.
 */
public class PendingPairsCountSqlTest extends Common {

    private final List<Integer> jobIds = new ArrayList<>();
    private final List<Integer> queueIds = new ArrayList<>();
    private int userId;
    private int queue;
    private int otherQueue;

    @BeforeAll
    public static void requireDatabase() {
        DatabaseTestSupport.assumeDatabaseAvailable("PendingPairsCountSqlTest");
        Common.initialize();
    }

    @BeforeEach
    public void createQueues() throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT min(id) FROM starexec.users");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            userId = rs.getInt(1);
        }
        queue = insertQueue();
        otherQueue = insertQueue();
    }

    @AfterEach
    public void dropFixture() throws SQLException {
        try (Connection con = Common.getConnection()) {
            for (int job : jobIds) {
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM starexec.job_pairs WHERE job_id = ?")) {
                    ps.setInt(1, job);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM starexec.jobs WHERE id = ?")) {
                    ps.setInt(1, job);
                    ps.executeUpdate();
                }
            }
            for (int q : queueIds) {
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM starexec.queues WHERE id = ?")) {
                    ps.setInt(1, q);
                    ps.executeUpdate();
                }
            }
        }
        jobIds.clear();
        queueIds.clear();
    }

    /** Every job of the queue counts, and only its pairs that wait to be submitted. */
    @Test
    public void countsThePairsOfTheQueuesJobsThatWaitToBeSubmitted() throws SQLException {
        int first = insertJob(queue);
        int second = insertJob(queue);
        insertPairs(first, StatusCode.STATUS_PENDING_SUBMIT, 3);
        insertPairs(second, StatusCode.STATUS_PENDING_SUBMIT, 2);
        for (StatusCode other : new StatusCode[] {
                StatusCode.STATUS_ENQUEUED, StatusCode.STATUS_RUNNING, StatusCode.STATUS_COMPLETE,
                StatusCode.STATUS_PAUSED, StatusCode.STATUS_KILLED}) {
            insertPairs(first, other, 1);
        }
        insertPairs(insertJob(otherQueue), StatusCode.STATUS_PENDING_SUBMIT, 4);

        assertEquals(5, Queues.countPendingPairs(queue));
        assertEquals(4, Queues.countPendingPairs(otherQueue));
    }

    /** Enqueued pairs are what the full-queue check counts; they are not waiting to be submitted. */
    @Test
    public void enqueuedPairsAreNotWaitingToBeSubmitted() throws SQLException {
        insertPairs(insertJob(queue), StatusCode.STATUS_ENQUEUED, 2);
        assertEquals(0, Queues.countPendingPairs(queue));
    }

    @Test
    public void aQueueWithNoJobsHasNothingWaiting() {
        assertEquals(0, Queues.countPendingPairs(queue));
    }

    private int insertQueue() throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "INSERT INTO starexec.queues (name, status, global_access) VALUES (?, 'ACTIVE', true)"
                     + " RETURNING id")) {
            ps.setString(1, "pending-pairs-test-" + UUID.randomUUID());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                int id = rs.getInt(1);
                queueIds.add(id);
                return id;
            }
        }
    }

    private int insertJob(int onQueue) throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "INSERT INTO starexec.jobs"
                     + " (user_id, name, queue_id, total_pairs, disk_size, created, clocktimeout,"
                     + " paused)"
                     + " VALUES (?, 'pending-pairs-test', ?, 1, 0, now(), 300, false)"
                     + " RETURNING id")) {
            ps.setInt(1, userId);
            ps.setInt(2, onQueue);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                int id = rs.getInt(1);
                jobIds.add(id);
                return id;
            }
        }
    }

    private void insertPairs(int job, StatusCode status, int count) throws SQLException {
        try (Connection con = Common.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "INSERT INTO starexec.job_pairs (job_id, status_code, primary_jobpair_data)"
                     + " VALUES (?, ?, 1)")) {
            for (int i = 0; i < count; i++) {
                ps.setInt(1, job);
                ps.setInt(2, status.getVal());
                ps.executeUpdate();
            }
        }
    }
}
