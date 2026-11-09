package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.Common;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.JobStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.test.util.DatabaseTestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL ownership and accounting, with explicitly synthetic filesystem capacity. */
class JobStorageLeaseSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private DedicatedStorageResource resource;
    private Path slot;
    private String key;
    private int owner;
    private int pair;
    private UUID token;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("JobStorageLeaseSqlTest");
        Common.initialize();
    }

    private static long number(Connection con, String sql, Object... arguments) throws SQLException {
        try (var query = con.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) query.setObject(i + 1, arguments[i]);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getLong(1); }
        }
    }

    private static void execute(Connection con, String sql, Object... arguments) throws SQLException {
        try (var query = con.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) query.setObject(i + 1, arguments[i]);
            query.execute();
        }
    }

    private int user() throws SQLException {
        return (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Storage','synthetic',now(),'synthetic',10000,100,20) RETURNING id",
                "job-storage-" + UUID.randomUUID() + "@example.invalid");
    }

    private int jobPair(int user) throws SQLException {
        int job = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                + " VALUES (?,'ordinary',1,0,false) RETURNING id", user);
        return (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data)"
                + " VALUES (?,1,1) RETURNING id", job);
    }

    private DedicatedStorageResource resource(String name) throws Exception {
        Path path = Files.createDirectory(files.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var observed = mock(DedicatedStorageResource.class);
        when(observed.root()).thenReturn(path);
        when(observed.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-", ""));
        when(observed.identity()).thenReturn("observed-mount-" + name);
        when(observed.capacityBytes()).thenReturn(64L);
        when(observed.inodeLimit()).thenReturn(16L);
        assertTrue(ExecutionStorageLeases.register(connection, observed));
        return observed;
    }

    @BeforeEach void seed() throws Exception {
        connection = Common.getConnection();
        owner = user(); pair = jobPair(owner); token = UUID.randomUUID();
        resource = resource("slot"); slot = resource.root(); key = resource.filesystemKey();
    }

    @AfterEach void closeConnection() throws Exception { if (connection != null) connection.close(); }

    private boolean reserve() throws SQLException { return JobStorageLeases.reserve(connection, pair, 1, token, resource); }
    private long usage() throws SQLException { return number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", owner); }
    private long active() throws SQLException {
        return number(connection, "SELECT count(*) FROM starexec.execution_storage_claims WHERE resource_key=? AND released_at IS NULL", key);
    }

    @Test void buildAndJobStorageShareOneOwnershipLedger() throws Exception {
        assertEquals(1, number(connection, "SELECT (to_regclass('starexec.execution_storage_claims') IS NOT NULL)::integer"));
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solver_build_storage_leases l"
                + " LEFT JOIN starexec.execution_storage_claims c ON c.execution_token=l.execution_token"
                + " WHERE c.execution_token IS NULL OR c.lease_kind<>'BUILD' OR c.build_artifact_id<>l.artifact_id"
                + " OR c.resource_key<>l.resource_key OR c.created_at<>l.created_at"
                + " OR c.released_at IS DISTINCT FROM l.released_at"));
    }

    @Test void aCommittedLeaseSurvivesConnectionCloseAndBooksCapacityOnlyOnce() throws Exception {
        assertTrue(reserve()); assertEquals(84, usage()); assertEquals(1, active());
        assertFalse(Files.exists(slot.resolve("pair-" + token)));
        var initial = JobStorageLeases.find(connection, token).orElseThrow();
        assertEquals(owner, initial.ownerId()); assertNull(initial.priorExecutionId());
        assertEquals(slot.resolve("pair-" + token), initial.staging());
        connection.close(); connection = Common.getConnection();
        assertEquals(initial, JobStorageLeases.find(connection, token).orElseThrow());
        assertTrue(reserve()); assertEquals(84, usage());
        assertTrue(ExecutionStorageLeases.availableResources(connection, owner).stream().noneMatch(r -> r.key().equals(key)));
    }

    @Test void insufficientQuotaCannotCreateOwnershipOrChangeAccounting() throws Exception {
        execute(connection, "UPDATE starexec.users SET disk_quota=83 WHERE id=?", owner);
        assertFalse(reserve()); assertEquals(20, usage()); assertEquals(0, active());
    }

    @Test void aResourceMustBeFullyFundedEvenAtTheLargestQuota() throws Exception {
        execute(connection, "UPDATE starexec.users SET disk_quota=?,disk_size=? WHERE id=?", Long.MAX_VALUE, Long.MAX_VALUE - 63, owner);
        assertFalse(reserve()); assertEquals(Long.MAX_VALUE - 63, usage()); assertEquals(0, active());
    }

    @Test void reservationsCannotShareOneFilesystemAcrossDifferentOwners() throws Exception {
        assertTrue(reserve()); int otherOwner = user(); int otherPair = jobPair(otherOwner);
        assertFalse(JobStorageLeases.reserve(connection, otherPair, 1, UUID.randomUUID(), resource));
        assertEquals(20, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", otherOwner));
        assertEquals(1, active());
    }

    @Test void aPairCannotAcquireAnotherResourceOrTokenForTheSameAttempt() throws Exception {
        assertTrue(reserve());
        assertFalse(JobStorageLeases.reserve(connection, pair, 1, UUID.randomUUID(), resource));
        assertFalse(JobStorageLeases.reserve(connection, pair, 1, token, resource("other")));
        assertEquals(84, usage());
    }

    @Test void aStaleAttemptKeepsItsResourceUntilExplicitReconciliation() throws Exception {
        assertTrue(reserve());
        execute(connection, "UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?", pair);
        assertFalse(reserve());
        assertThrows(SQLException.class, () -> JobStorageLeases.beginUse(connection, token, resource));
        assertThrows(SQLException.class, () -> JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertFalse(JobStorageLeases.reserve(connection, pair, 2, UUID.randomUUID(), resource("other")));
        assertEquals(84, usage()); assertEquals(1, active());
    }

    @ParameterizedTest @ValueSource(ints = {4,7,21})
    void runningOrTerminalPairsCannotReserve(int status) throws Exception {
        execute(connection, "UPDATE starexec.job_pairs SET status_code=? WHERE id=?", status, pair);
        assertFalse(reserve()); assertEquals(20, usage());
    }

    @ParameterizedTest @ValueSource(strings = {"deleted", "killed", "paused", "buildjob"})
    void unavailableOrBuildJobsCannotReserve(String flag) throws Exception {
        assertTrue(java.util.Set.of("deleted", "killed", "paused", "buildjob").contains(flag));
        execute(connection, "UPDATE starexec.jobs SET " + flag + "=true WHERE id=(SELECT job_id FROM starexec.job_pairs WHERE id=?)", pair);
        assertFalse(reserve()); assertEquals(20, usage());
    }

    @Test void cancellationCanReclaimEmptyStorageOnlyBeforeUseBegins() throws Exception {
        assertTrue(reserve());
        execute(connection, "UPDATE starexec.job_pairs SET status_code=21 WHERE id=?", pair);
        assertThrows(SQLException.class, () -> JobStorageLeases.beginUse(connection, token, resource));
        assertTrue(JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertEquals(20, usage()); assertEquals(0, active());
        var settled = JobStorageLeases.find(connection, token).orElseThrow();
        assertNotNull(settled.releasedAt()); assertEquals(64, settled.limitBytes());
        assertTrue(JobStorageLeases.releaseUnstarted(connection, token, resource)); assertEquals(20, usage());
        assertFalse(reserve());
    }

    @Test void useIsConsumedOnceAndCancellationCannotPretendToProveTermination() throws Exception {
        assertTrue(reserve()); assertTrue(JobStorageLeases.beginUse(connection, token, resource));
        assertFalse(JobStorageLeases.beginUse(connection, token, resource));
        execute(connection, "UPDATE starexec.job_pairs SET status_code=21 WHERE id=?", pair);
        assertFalse(JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE starexec.execution_storage_claims SET released_at=clock_timestamp() WHERE execution_token=?", token));
        assertEquals(84, usage()); assertEquals(1, active());
    }

    @Test void unexpectedFilesRefuseReservationAndRollBackItsCharge() throws Exception {
        Files.writeString(slot.resolve("unknown"), "preserve");
        assertThrows(SQLException.class, this::reserve);
        assertEquals(20, usage()); assertEquals(0, active());
        assertEquals("preserve", Files.readString(slot.resolve("unknown")));
    }

    @Test void residualFilesCannotBeReleasedOrClaimedAsUnused() throws Exception {
        assertTrue(reserve()); Files.writeString(slot.resolve("unknown"), "preserve");
        assertThrows(SQLException.class, () -> JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertThrows(SQLException.class, () -> JobStorageLeases.beginUse(connection, token, resource));
        assertEquals(84, usage()); assertEquals(1, active());
    }

    @Test void changedCapacityOrMountIdentityRollsBackAdmissionAndSettlement() throws Exception {
        when(resource.capacityBytes()).thenReturn(65L);
        assertFalse(reserve()); assertEquals(20, usage());
        when(resource.capacityBytes()).thenReturn(64L); assertTrue(reserve());
        doThrow(new IOException("mount identity changed")).when(resource).requireUnchanged();
        assertThrows(SQLException.class, () -> JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertThrows(SQLException.class, () -> JobStorageLeases.beginUse(connection, token, resource));
        assertEquals(84, usage()); assertEquals(1, active());
    }

    @Test void changedExecutionIdentityCannotUseOrSettleTheOldLease() throws Exception {
        assertTrue(reserve()); execute(connection, "UPDATE starexec.job_pairs SET sge_id=5 WHERE id=?", pair);
        assertFalse(reserve());
        assertThrows(SQLException.class, () -> JobStorageLeases.beginUse(connection, token, resource));
        assertThrows(SQLException.class, () -> JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertEquals(84, usage());
    }

    @Test void activeOwnershipGuardsDeletionAndSettledHistorySurvivesIt() throws Exception {
        assertTrue(reserve());
        assertThrows(SQLException.class, () -> execute(connection, "DELETE FROM starexec.job_pairs WHERE id=?", pair));
        assertThrows(SQLException.class, () -> execute(connection, "DELETE FROM starexec.users WHERE id=?", owner));
        assertTrue(JobStorageLeases.releaseUnstarted(connection, token, resource));
        execute(connection, "DELETE FROM starexec.job_pairs WHERE id=?", pair);
        assertNotNull(JobStorageLeases.find(connection, token).orElseThrow().releasedAt());
        assertThrows(SQLException.class, () -> execute(connection,
                "DELETE FROM starexec.execution_storage_claims WHERE execution_token=?", token));
    }

    @Test void quotaCorruptionKeepsEveryExistingReservationUnavailable() throws Exception {
        assertTrue(reserve()); execute(connection, "UPDATE starexec.users SET disk_size=63 WHERE id=?", owner);
        assertThrows(SQLException.class, () -> JobStorageLeases.beginUse(connection, token, resource));
        assertThrows(SQLException.class, () -> JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertFalse(JobStorageLeases.reserve(connection, jobPair(owner), 1, UUID.randomUUID(), resource("other")));
        assertEquals(63, usage()); assertEquals(1, active());
    }

    @Test void aConflictDiscardedInsertDoesNotChargeTheOwnerAgain() throws Exception {
        assertTrue(reserve());
        execute(connection, "INSERT INTO starexec.execution_storage_claims"
                + "(execution_token,resource_key,lease_kind,origin_pair_id,attempt_no,origin_owner_id,prior_execution_id,limit_bytes)"
                + " SELECT execution_token,resource_key,lease_kind,origin_pair_id,attempt_no,origin_owner_id,prior_execution_id,limit_bytes"
                + " FROM starexec.execution_storage_claims WHERE execution_token=? ON CONFLICT DO NOTHING", token);
        assertEquals(84, usage()); assertEquals(1, active());
    }

    @Test void ownerChangeCannotTransferOrSettleTheOriginalReservation() throws Exception {
        assertTrue(reserve()); int otherOwner = user();
        execute(connection, "UPDATE starexec.jobs SET user_id=? WHERE id=(SELECT job_id FROM starexec.job_pairs WHERE id=?)", otherOwner, pair);
        assertFalse(reserve());
        assertThrows(SQLException.class, () -> JobStorageLeases.beginUse(connection, token, resource));
        assertThrows(SQLException.class, () -> JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertEquals(84, usage()); assertEquals(20, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", otherOwner));
    }

    @Test void historicalExecutionIdentityIsCapturedWithoutResettingIt() throws Exception {
        execute(connection, "UPDATE starexec.job_pairs SET sge_id=42 WHERE id=?", pair);
        assertTrue(reserve()); assertEquals(42, JobStorageLeases.find(connection, token).orElseThrow().priorExecutionId());
        assertTrue(JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertEquals(42, number(connection, "SELECT sge_id FROM starexec.job_pairs WHERE id=?", pair));
    }

    @Test void ownershipHistoryCannotBeReboundOrResurrected() throws Exception {
        assertTrue(reserve());
        assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE starexec.execution_storage_claims SET limit_bytes=1 WHERE execution_token=?", token));
        assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE starexec.execution_storage_claims SET origin_pair_id=? WHERE execution_token=?", jobPair(owner), token));
        assertTrue(JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE starexec.execution_storage_claims SET released_at=NULL WHERE execution_token=?", token));
        assertFalse(reserve()); assertEquals(20, usage()); assertEquals(0, active());
    }

    @Test void aLostSettlementReplyDoesNotSubtractTheAllowanceAgain() throws Exception {
        assertTrue(reserve()); var proxy = mock(Connection.class, AdditionalAnswers.delegatesTo(connection));
        doAnswer(invocation -> { connection.commit(); throw new SQLException("settlement reply lost"); }).when(proxy).commit();
        assertThrows(SQLException.class, () -> JobStorageLeases.releaseUnstarted(proxy, token, resource));
        assertNotNull(JobStorageLeases.find(connection, token).orElseThrow().releasedAt());
        assertTrue(JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertEquals(20, usage()); assertEquals(0, active());
    }

    @Test void reclamationAndFirstUseCannotBothWin() throws Exception {
        assertTrue(reserve()); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var use = pool.submit(() -> {
                start.await();
                try (var con = Common.getConnection()) { return JobStorageLeases.beginUse(con, token, resource); }
                catch (SQLException busy) { assertEquals("55P03", busy.getSQLState()); return false; }
            });
            var release = pool.submit(() -> {
                start.await();
                try (var con = Common.getConnection()) { return JobStorageLeases.releaseUnstarted(con, token, resource); }
                catch (SQLException busy) { assertEquals("55P03", busy.getSQLState()); return false; }
            });
            start.countDown();
            boolean began = use.get(10, TimeUnit.SECONDS);
            boolean settled = release.get(10, TimeUnit.SECONDS);
            assertNotEquals(began, settled);
            assertEquals(began ? 84 : 20, usage()); assertEquals(began ? 1 : 0, active());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void concurrentPairsCannotOverbookTheSameOwnerAcrossTwoResources() throws Exception {
        execute(connection, "UPDATE starexec.users SET disk_quota=100 WHERE id=?", owner);
        int otherPair = jobPair(owner); var otherResource = resource("other");
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                try (var con = Common.getConnection()) { return JobStorageLeases.reserve(con, pair, 1, token, resource); }
                catch (SQLException busy) { assertEquals("55P03", busy.getSQLState()); return false; }
            });
            var second = pool.submit(() -> {
                start.await();
                try (var con = Common.getConnection()) { return JobStorageLeases.reserve(con, otherPair, 1, UUID.randomUUID(), otherResource); }
                catch (SQLException busy) { assertEquals("55P03", busy.getSQLState()); return false; }
            });
            start.countDown();
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(84, usage());
            assertEquals(1, number(connection, "SELECT count(*) FROM starexec.execution_storage_claims"
                    + " WHERE lease_kind='JOB' AND origin_owner_id=? AND released_at IS NULL", owner));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private BuildExecutionContext build() throws Exception {
        Path source = Files.createDirectory(files.resolve("solver_src"));
        Files.writeString(source.resolve("starexec_build"), "original");
        int user = user();
        int solver = (int) number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'solver',now(),?,20,0) RETURNING id", user, source.toString());
        int job = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                + " VALUES (?,'build',1,0,true) RETURNING id", user);
        int buildPair = (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id", job);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", buildPair, solver);
        var artifact = SolverBuildArtifacts.find(connection, buildPair, 1).orElseThrow();
        UUID buildToken = UUID.randomUUID();
        var context = new BuildExecutionContext(artifact.id(), buildPair, 1, buildToken, source, slot.resolve("build-" + buildToken));
        Common.runTransactional(connection, tx -> {
            assertTrue(SolverBuildArtifacts.prepare(tx, buildPair, 1, buildToken, context.staging()));
            assertTrue(SolverBuildArtifacts.reserveStorageBytes(tx, context, "STAGING", context.staging(), 128));
            return true;
        });
        return context;
    }

    @Test void aBuildLeasePreventsAnOrdinaryJobFromTakingItsResource() throws Exception {
        var build = build(); assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        assertFalse(reserve()); assertEquals(20, usage()); assertEquals(1, active());
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.execution_storage_claims WHERE build_artifact_id=?", build.artifactId()));
    }

    @Test void aJobLeaseExcludesBuildsAndItsSettledRetryCannotAffectTheNextOwner() throws Exception {
        assertTrue(reserve()); var build = build();
        assertFalse(ExecutionStorageLeases.reserve(connection, build, resource));
        assertTrue(JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        Files.writeString(slot.resolve("next-owner-output"), "keep");
        doThrow(new IOException("do not inspect the next owner")).when(resource).requireUnchanged();
        assertTrue(JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertEquals("keep", Files.readString(slot.resolve("next-owner-output")));
        assertEquals(1, active()); assertEquals(20, usage());
    }

    @Test void aFailedCommitRollsBackBothReservationAndCharge() throws Exception {
        var proxy = mock(Connection.class, AdditionalAnswers.delegatesTo(connection));
        doThrow(new SQLException("commit failed")).when(proxy).commit();
        assertThrows(SQLException.class, () -> JobStorageLeases.reserve(proxy, pair, 1, token, resource));
        assertTrue(connection.getAutoCommit()); assertEquals(0, active()); assertEquals(20, usage());
    }

    @Test void aLostCommitReplyIsRecoveredWithTheOriginalTokenWithoutDoubleCharging() throws Exception {
        var proxy = mock(Connection.class, AdditionalAnswers.delegatesTo(connection));
        doAnswer(invocation -> { connection.commit(); throw new SQLException("commit reply lost"); }).when(proxy).commit();
        assertThrows(SQLException.class, () -> JobStorageLeases.reserve(proxy, pair, 1, token, resource));
        assertTrue(connection.getAutoCommit()); assertEquals(84, usage()); assertEquals(1, active());
        try (var recovered = Common.getConnection()) {
            assertTrue(JobStorageLeases.reserve(recovered, pair, 1, token, resource));
            assertFalse(JobStorageLeases.reserve(recovered, pair, 1, UUID.randomUUID(), resource));
        }
        assertEquals(84, usage()); assertEquals(1, active());
    }

    @Test void aLostUseCommitReplyCannotIssueAnotherStorageUsePermit() throws Exception {
        assertTrue(reserve()); var proxy = mock(Connection.class, AdditionalAnswers.delegatesTo(connection));
        doAnswer(invocation -> { connection.commit(); throw new SQLException("use reply lost"); }).when(proxy).commit();
        assertThrows(SQLException.class, () -> JobStorageLeases.beginUse(proxy, token, resource));
        assertNotNull(JobStorageLeases.find(connection, token).orElseThrow().useStartedAt());
        assertFalse(JobStorageLeases.beginUse(connection, token, resource));
        assertFalse(JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertEquals(84, usage());
    }

    @Test void aRefusalMustNotHideRollbackFailure() throws Exception {
        var proxy = mock(Connection.class, AdditionalAnswers.delegatesTo(connection));
        doAnswer(invocation -> { connection.rollback(); throw new SQLException("rollback reply lost"); }).when(proxy).rollback();
        var error = assertThrows(SQLException.class, () -> JobStorageLeases.reserve(proxy, -1, 1, token, resource));
        assertEquals(1, error.getSuppressed().length); assertEquals(20, usage());
    }

    @Test void foreignTransactionsRemainOwnedByTheirCaller() throws Exception {
        connection.setAutoCommit(false);
        assertThrows(SQLException.class, this::reserve);
        assertThrows(SQLException.class, () -> JobStorageLeases.beginUse(connection, token, resource));
        assertThrows(SQLException.class, () -> JobStorageLeases.releaseUnstarted(connection, token, resource));
        assertFalse(connection.getAutoCommit()); connection.rollback(); connection.setAutoCommit(true);
        assertEquals(20, usage()); assertEquals(0, active());
    }

    @Test void contendedResourceRefusesWithoutLeavingAJobCharge() throws Exception {
        try (var holder = Common.getConnection()) {
            holder.setAutoCommit(false);
            number(holder, "SELECT capacity_bytes FROM starexec.execution_storage_resources WHERE resource_key=? FOR UPDATE", key);
            assertThrows(SQLException.class, this::reserve);
            assertEquals(20, usage()); assertEquals(0, active()); holder.rollback();
        }
    }

    @Test void concurrentJobAndBuildClaimsHaveExactlyOneWinner() throws Exception {
        var build = build(); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var jobResult = pool.submit(() -> {
                start.await();
                try (var con = Common.getConnection()) { return JobStorageLeases.reserve(con, pair, 1, token, resource); }
                catch (SQLException busy) { assertEquals("55P03", busy.getSQLState()); return false; }
            });
            var buildResult = pool.submit(() -> {
                start.await();
                try (var con = Common.getConnection()) { return ExecutionStorageLeases.reserve(con, build, resource); }
                catch (SQLException busy) { assertEquals("55P03", busy.getSQLState()); return false; }
            });
            start.countDown();
            assertEquals(1, (jobResult.get(10, TimeUnit.SECONDS) ? 1 : 0) + (buildResult.get(10, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, active());
            assertEquals(JobStorageLeases.find(connection, token).isPresent() ? 84 : 20, usage());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
