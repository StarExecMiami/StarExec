package org.starexec.test.junit.database;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.Common;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;
import org.starexec.jobs.SolverBuildCleanup;
import org.starexec.test.util.DatabaseTestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real database exclusivity with explicitly synthetic provider capacity and stop observations. */
class ExecutionStorageLeaseSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private DedicatedStorageResource resource;
    private Path slot;
    private Path outputs;
    private BuildExecutionContext build;
    private String key;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("ExecutionStorageLeaseSqlTest");
        Common.initialize();
    }

    private static long number(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }

    private static void execute(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.execute();
        }
    }

    private Path directory(String name) throws IOException {
        return Files.createDirectory(files.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private BuildExecutionContext plan(String name, Path root, long budget) throws Exception {
        int user = (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Lease','synthetic',now(),'synthetic',10000,100,20) RETURNING id",
                "lease-" + UUID.randomUUID() + "@example.invalid");
        Path source = directory(name + "_src");
        Files.writeString(source.resolve("starexec_build"), "original");
        int solver = (int) number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?, ?,now(),?,20,0) RETURNING id", user, name, source.toString());
        int job = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                + " VALUES (?,'build',1,0,true) RETURNING id", user);
        int pair = (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id", job);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", pair, solver);
        var artifact = SolverBuildArtifacts.find(connection, pair, 1).orElseThrow();
        UUID token = UUID.randomUUID();
        var context = new BuildExecutionContext(artifact.id(), pair, 1, token, source, root.resolve("build-" + token));
        Common.runTransactional(connection, tx -> {
            assertTrue(SolverBuildArtifacts.prepare(tx, pair, 1, token, context.staging()));
            assertTrue(SolverBuildArtifacts.reserveStorageBytes(tx, context, "STAGING", context.staging(), budget));
            return true;
        });
        return context;
    }

    @BeforeEach void seed() throws Exception {
        connection = Common.getConnection();
        slot = directory("slot");
        outputs = directory("outputs");
        key = UUID.randomUUID().toString().replace("-", "");
        resource = mock(DedicatedStorageResource.class);
        when(resource.root()).thenReturn(slot);
        when(resource.filesystemKey()).thenReturn(key);
        when(resource.identity()).thenReturn("observed-device-and-mount");
        when(resource.capacityBytes()).thenReturn(64L);
        when(resource.inodeLimit()).thenReturn(16L);
        build = plan("first", slot, 128);
        assertTrue(ExecutionStorageLeases.register(connection, resource));
    }

    @AfterEach void closeConnection() throws Exception { if (connection != null) connection.close(); }

    private long leases() throws SQLException {
        return number(connection, "SELECT count(*) FROM starexec.solver_build_storage_leases WHERE resource_key=? AND released_at IS NULL", key);
    }

    private String engine() { return "podman:7:owned-storage-build"; }

    private void stopped() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection, build, resource, engine()));
        Files.createDirectory(build.staging());
        Files.writeString(build.staging().resolve("build.log"), "saved");
        JsonObject facts = new JsonObject(); facts.addProperty("status", "exited");
        var receipt = SolverBuildStopReceipt.observed("podman", build.artifactId(), build.pairId(), 1,
                engine(), "owned-storage-build", Instant.now(), facts);
        assertTrue(SolverBuildArtifacts.recordStopReceipt(connection, build.pairId(), 1, build.token(), engine(), receipt));
    }

    private void cleaned() throws Exception {
        stopped();
        var artifact = SolverBuildArtifacts.find(connection, build.pairId(), 1).orElseThrow();
        execute(connection, "DELETE FROM starexec.jobs WHERE id=(SELECT job_id FROM starexec.job_pairs WHERE id=?)", build.pairId());
        execute(connection, "UPDATE starexec.solvers SET deleted=true WHERE id=?", artifact.solverId());
        assertTrue(SolverBuildCleanup.purge(connection, build.artifactId(), slot, outputs, files, 100));
        assertFalse(Files.exists(build.staging()));
    }

    @Test void resourceRegistrationAndLeaseRetriesPreserveHistoryAndBookedUsage() throws Exception {
        long created = number(connection, "SELECT (extract(epoch from created_at)*1000000)::bigint FROM starexec.execution_storage_resources WHERE resource_key=?", key);
        assertTrue(ExecutionStorageLeases.register(connection, resource));
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        long leased = number(connection, "SELECT (extract(epoch from created_at)*1000000)::bigint FROM starexec.solver_build_storage_leases WHERE artifact_id=?", build.artifactId());
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        assertEquals(1, leases());
        assertEquals(148, number(connection, "SELECT disk_size FROM starexec.users WHERE id=(SELECT origin_owner_id FROM starexec.solver_build_artifacts WHERE id=?)", build.artifactId()));
        assertEquals(created, number(connection, "SELECT (extract(epoch from created_at)*1000000)::bigint FROM starexec.execution_storage_resources WHERE resource_key=?", key));
        assertEquals(leased, number(connection, "SELECT (extract(epoch from created_at)*1000000)::bigint FROM starexec.solver_build_storage_leases WHERE artifact_id=?", build.artifactId()));
    }

    @ParameterizedTest @ValueSource(strings = {"root", "identity", "bytes", "inodes", "key"})
    void registrationCannotRedirectOrResizeAnExistingResource(String changed) throws Exception {
        switch (changed) {
            case "root" -> when(resource.root()).thenReturn(directory("other"));
            case "identity" -> when(resource.identity()).thenReturn("different-mount");
            case "bytes" -> when(resource.capacityBytes()).thenReturn(32L);
            case "inodes" -> when(resource.inodeLimit()).thenReturn(8L);
            case "key" -> when(resource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-", ""));
            default -> throw new AssertionError(changed);
        }
        assertFalse(ExecutionStorageLeases.register(connection, resource));
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.execution_storage_resources WHERE resource_key=? AND root_path=? AND capacity_bytes=64 AND inode_limit=16", key, slot.toString()));
        assertEquals(0, leases());
    }

    @ParameterizedTest @ValueSource(strings = {"artifact", "pair", "attempt", "token", "source", "staging"})
    void aLeaseCannotBorrowAnotherBuildsIdentityOrAllowance(String changed) throws Exception {
        var context = new BuildExecutionContext(changed.equals("artifact") ? build.artifactId()+1 : build.artifactId(),
                changed.equals("pair") ? build.pairId()+1 : build.pairId(), changed.equals("attempt") ? 2 : 1,
                changed.equals("token") ? UUID.randomUUID() : build.token(),
                changed.equals("source") ? files.resolve("other_src") : build.source(),
                changed.equals("staging") ? slot.resolve("other") : build.staging());
        assertFalse(ExecutionStorageLeases.reserve(connection, context, resource));
        assertEquals(0, leases());
    }

    @ParameterizedTest @ValueSource(longs = {0, 32})
    void aResourceLargerThanTheBookedBudgetIsRefused(long budget) throws Exception {
        BuildExecutionContext small = plan("small", slot, budget);
        assertFalse(ExecutionStorageLeases.reserve(connection, small, resource));
        assertEquals(0, leases());
    }

    @Test void aResourceCannotEncloseItsBuildSource() throws Exception {
        var enclosed = plan("enclosed", files, 128);
        var observation = mock(DedicatedStorageResource.class);
        when(observation.root()).thenReturn(files);
        when(observation.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-", ""));
        when(observation.identity()).thenReturn("other-observed-filesystem");
        when(observation.capacityBytes()).thenReturn(64L);
        when(observation.inodeLimit()).thenReturn(16L);
        SQLException refused=assertThrows(SQLException.class,()->ExecutionStorageLeases.register(connection, observation));
        assertEquals("23514",refused.getSQLState());
        assertFalse(ExecutionStorageLeases.reserve(connection, enclosed, observation));
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solver_build_storage_leases WHERE artifact_id=?", enclosed.artifactId()));
    }

    @Test void aChangedObservationIsNotLaunchPermission() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        doThrow(new IOException("INJECTED RESOURCE CHANGE")).when(resource).requireUnchanged();
        SQLException refused = assertThrows(SQLException.class, () -> ExecutionStorageLeases.admit(connection, build, resource, engine()));
        assertInstanceOf(IOException.class, refused.getCause());
        assertEquals(1, leases());
        assertNull(SolverBuildArtifacts.find(connection, build.pairId(), 1).orElseThrow().engineIdentity());
        assertTrue(connection.getAutoCommit());
    }

    @Test void aCurrentAttemptChangeCannotReuseTheLease() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        execute(connection, "UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?", build.pairId());
        assertFalse(ExecutionStorageLeases.admit(connection, build, resource, engine()));
        assertEquals(1, leases());
    }

    @Test void anActiveResourceCannotBeLeasedByAnotherBuildEvenAfterConnectionClose() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        BuildExecutionContext other = plan("other", slot, 128);
        connection.close(); connection = Common.getConnection();
        assertFalse(ExecutionStorageLeases.reserve(connection, other, resource));
        assertEquals(1, leases());
        assertTrue(ExecutionStorageLeases.admit(connection, build, resource, engine()));
        assertTrue(ExecutionStorageLeases.admit(connection, build, resource, engine()));
        assertFalse(ExecutionStorageLeases.admit(connection, build, resource, engine()+"-other"));
    }

    @Test void concurrentBuildsCannotBothLeaseTheSameFilesystem() throws Exception {
        BuildExecutionContext other = plan("other", slot, 128);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try {
            var tasks = java.util.List.of(build, other).stream().map(context -> pool.submit(() -> {
                try (Connection observer = Common.getConnection()) {
                    ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                    try { return ExecutionStorageLeases.reserve(observer, context, resource); }
                    catch (SQLException busy) { assertEquals("55P03", busy.getSQLState()); return false; }
                }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            int accepted=0; for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted); assertEquals(1, leases());
        } finally { go.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void resourceContentionRefusesWithoutPartialLeaseOrEngineBinding() throws Exception {
        try (Connection competing = Common.getConnection()) {
            competing.setAutoCommit(false);
            try {
                number(competing, "SELECT 1 FROM starexec.execution_storage_resources WHERE resource_key=? FOR UPDATE", key);
                SQLException busy = assertThrows(SQLException.class, () -> ExecutionStorageLeases.admit(connection, build, resource, engine()));
                assertEquals("55P03", busy.getSQLState()); assertEquals(0, leases());
                assertNull(SolverBuildArtifacts.find(connection, build.pairId(), 1).orElseThrow().engineIdentity());
            } finally { competing.rollback(); competing.setAutoCommit(true); }
        }
        assertTrue(ExecutionStorageLeases.admit(connection, build, resource, engine()));
    }

    @Test void failedEngineAdmissionRollsBackANewLease() throws Exception {
        assertFalse(ExecutionStorageLeases.admit(connection, build, resource, ""));
        assertEquals(0, leases()); assertTrue(connection.getAutoCommit());
        assertNull(SolverBuildArtifacts.find(connection, build.pairId(), 1).orElseThrow().engineIdentity());
    }

    @Test void aFailedCommitRollsBackBothTheNewLeaseAndEngineBinding() throws Exception {
        String trigger = "lease_commit_failure_" + UUID.randomUUID().toString().replace("-", "");
        execute(connection, "CREATE FUNCTION starexec." + trigger + "() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'INJECTED LEASE COMMIT FAILURE'; END; $$ LANGUAGE plpgsql");
        execute(connection, "CREATE CONSTRAINT TRIGGER " + trigger + " AFTER INSERT ON starexec.solver_build_storage_leases DEFERRABLE INITIALLY DEFERRED FOR EACH ROW"
                + " WHEN (NEW.artifact_id=" + build.artifactId() + ") EXECUTE FUNCTION starexec." + trigger + "()");
        try {
            SQLException failure = assertThrows(SQLException.class, () -> ExecutionStorageLeases.admit(connection, build, resource, engine()));
            assertTrue(failure.getMessage().contains("INJECTED LEASE COMMIT FAILURE"));
            assertEquals(0, leases()); assertTrue(connection.getAutoCommit());
            assertNull(SolverBuildArtifacts.find(connection, build.pairId(), 1).orElseThrow().engineIdentity());
            assertEquals(148, number(connection, "SELECT disk_size FROM starexec.users WHERE id=(SELECT origin_owner_id FROM starexec.solver_build_artifacts WHERE id=?)", build.artifactId()));
        } finally {
            execute(connection, "DROP TRIGGER " + trigger + " ON starexec.solver_build_storage_leases");
            execute(connection, "DROP FUNCTION starexec." + trigger + "()");
        }
        assertTrue(ExecutionStorageLeases.admit(connection, build, resource, engine()));
    }

    @Test void anExistingUnleasedEngineCannotAcquireAPostHocLease() throws Exception {
        assertTrue(SolverBuildArtifacts.bindExecution(connection, build.pairId(), 1, build.token(), engine()));
        assertFalse(ExecutionStorageLeases.reserve(connection, build, resource)); assertEquals(0, leases());
    }

    @Test void releaseRequiresRegisteredCleanupEvenWhenTheFilesystemLooksEmpty() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        assertFalse(ExecutionStorageLeases.release(connection, build, resource)); assertEquals(1, leases());
        assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE starexec.solver_build_storage_leases SET released_at=clock_timestamp() WHERE artifact_id=?", build.artifactId()));
    }

    @Test void settledCleanupAndPositiveEmptyObservationReleaseOnceAndPermitReuse() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource)); cleaned();
        assertEquals(1, leases());
        assertTrue(ExecutionStorageLeases.release(connection, build, resource)); assertEquals(0, leases());
        long released = number(connection, "SELECT (extract(epoch from released_at)*1000000)::bigint FROM starexec.solver_build_storage_leases WHERE artifact_id=?", build.artifactId());
        assertTrue(ExecutionStorageLeases.release(connection, build, resource));
        assertEquals(released, number(connection, "SELECT (extract(epoch from released_at)*1000000)::bigint FROM starexec.solver_build_storage_leases WHERE artifact_id=?", build.artifactId()));
        var other = plan("other", slot, 128);
        assertTrue(ExecutionStorageLeases.reserve(connection, other, resource)); assertEquals(1, leases());
        assertFalse(ExecutionStorageLeases.admit(connection, build, resource, engine()));
    }

    @Test void residualBytesKeepTheResourceUnavailableAfterAccountingSettlement() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource)); cleaned();
        Path leftover = Files.writeString(slot.resolve("leftover"), "owned-test-byte");
        assertThrows(SQLException.class, () -> ExecutionStorageLeases.release(connection, build, resource));
        assertEquals(1, leases()); assertTrue(Files.exists(leftover));
        Files.delete(leftover);
        assertTrue(ExecutionStorageLeases.release(connection, build, resource));
    }

    @ParameterizedTest @ValueSource(strings = {"resource-update", "resource-delete", "lease-update", "lease-delete"})
    void resourceAndLeaseHistoryCannotBeRewritten(String operation) throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        String sql = switch (operation) {
            case "resource-update" -> "UPDATE starexec.execution_storage_resources SET capacity_bytes=32 WHERE resource_key=?";
            case "resource-delete" -> "DELETE FROM starexec.execution_storage_resources WHERE resource_key=?";
            case "lease-update" -> "UPDATE starexec.solver_build_storage_leases SET execution_token='00000000-0000-0000-0000-000000000001' WHERE resource_key=?";
            case "lease-delete" -> "DELETE FROM starexec.solver_build_storage_leases WHERE resource_key=?";
            default -> throw new AssertionError(operation);
        };
        assertThrows(SQLException.class, () -> execute(connection, sql, key)); assertEquals(1, leases());
    }

    @Test void aForeignTransactionIsNotCommittedOrRolledBack() throws Exception {
        connection.setAutoCommit(false);
        try {
            assertThrows(SQLException.class, () -> ExecutionStorageLeases.reserve(connection, build, resource));
            assertFalse(connection.getAutoCommit()); assertEquals(0, leases());
        } finally { connection.rollback(); connection.setAutoCommit(true); }
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
    }
}
