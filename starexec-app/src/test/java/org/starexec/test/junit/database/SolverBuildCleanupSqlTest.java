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
import org.starexec.data.database.Common;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Users;
import org.starexec.data.to.SolverBuildStopReceipt;
import org.starexec.exceptions.UserDeletionBlockedException;
import org.starexec.jobs.SolverBuildCleanup;
import org.starexec.jobs.SolverBuildPublication;
import org.starexec.jobs.SolverBuildStaging;
import org.starexec.test.util.DatabaseTestSupport;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SolverBuildCleanupSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private int user;
    private int otherUser;
    private int solver;
    private int job;
    private int pair;
    private Path sourceParent;
    private Path source;
    private Path stagingParent;
    private Path outputs;
    private BuildExecutionContext build;
    private String injected;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("SolverBuildCleanupSqlTest");
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

    private Path directory(Path path) throws IOException {
        return Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private int newUser() throws SQLException {
        return (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Cleanup','synthetic',now(),'synthetic',1000000,100,10020) RETURNING id",
                "purge-" + UUID.randomUUID() + "@example.invalid");
    }

    @BeforeEach void seed() throws Exception {
        connection = Common.getConnection();
        user = newUser();
        sourceParent = directory(files.resolve("sources"));
        source = directory(sourceParent.resolve("solver_src"));
        Files.writeString(source.resolve("starexec_build"), "original source");
        solver = (int) number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'cleanup',now(),?,20,0) RETURNING id", user, source.toString());
        job = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,completed_pairs,disk_size,buildjob)"
                + " VALUES (?,'build',1,1,0,true) RETURNING id", user);
        pair = (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data)"
                + " VALUES (?,7,1) RETURNING id", job);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size)"
                + " VALUES (?,1,7,?,0)", pair, solver);
        stagingParent = directory(files.resolve("staging"));
        build = SolverBuildStaging.prepare(connection, pair, 1, stagingParent);
        Files.writeString(directory(build.staging().resolve("solver")).resolve("program"), "compiled binary");
        Files.writeString(directory(build.staging().resolve("logs")).resolve("starexec_build_log"), "saved log");
        outputs = directory(files.resolve("outputs"));
    }

    private Path output() { return outputs.resolve("build-" + build.artifactId() + "-" + build.token()); }

    private void stopped(String backend) throws Exception {
        String engine = backend + ":7:owned-resource";
        assertTrue(SolverBuildArtifacts.bindExecution(connection, pair, 1, build.token(), engine));
        JsonObject facts = new JsonObject(); facts.addProperty("status", "exited");
        var receipt = SolverBuildStopReceipt.observed(backend, build.artifactId(), pair, 1, engine,
                "owned-resource", Instant.now(), facts);
        assertTrue(SolverBuildArtifacts.recordStopReceipt(connection, pair, 1, build.token(), engine, receipt));
    }

    private void ready() throws Exception {
        execute(connection, "DELETE FROM starexec.jobs WHERE id=?", job);
        assertTrue(Solvers.delete(solver));
    }

    private boolean purge(int limit) throws SQLException {
        return SolverBuildCleanup.purge(connection, build.artifactId(), stagingParent, outputs, sourceParent, limit);
    }

    private void charged() throws Exception {
        assertEquals(10020, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        assertEquals(20, number(connection, "SELECT disk_size FROM starexec.solvers WHERE id=?", solver));
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND cleanup_finished_at IS NOT NULL", build.artifactId()));
    }

    private void settled() throws Exception {
        assertFalse(Files.exists(build.staging())); assertFalse(Files.exists(output())); assertFalse(Files.exists(source));
        assertEquals(10000, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        assertEquals(0, number(connection, "SELECT disk_size FROM starexec.solvers WHERE id=?", solver));
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND state='DELETED'"
                + " AND cleanup_token IS NOT NULL AND cleanup_finished_at IS NOT NULL AND cleanup_solver_bytes=20"
                + " AND engine_identity IS NOT NULL AND execution_stop_receipt_payload IS NOT NULL", build.artifactId()));
    }

    @ParameterizedTest @ValueSource(strings = {"partial", "published", "failed"})
    void removesRegisteredTreesAndSettlesOnlyTheCapturedChargeOnce(String outcome) throws Exception {
        stopped("podman");
        if (outcome.equals("published")) assertTrue(SolverBuildPublication.publish(connection, build, outputs, 4096, 32));
        else if (outcome.equals("failed")) {
            execute(connection, "UPDATE starexec.job_pairs SET status_code=11 WHERE id=?", pair);
            assertTrue(SolverBuildPublication.recordFailure(connection, build, outputs, 4096, 32));
        } else {
            var receipt = SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow();
            assertTrue(SolverBuildArtifacts.reserveOutput(connection, build, "podman:7:owned-resource", receipt.receipt().sha256(), output(), 4096));
            Files.writeString(directory(output()).resolve("partial"), "incomplete copy");
        }
        Path sibling = Files.writeString(outputs.resolve("unregistered"), "keep");
        ready(); assertTrue(purge(100)); settled();
        var finished = SolverBuildArtifacts.findCleanup(connection, build.artifactId()).orElseThrow();
        assertTrue(purge(100)); settled();
        assertEquals(finished, SolverBuildArtifacts.findCleanup(connection, build.artifactId()).orElseThrow());
        assertEquals("keep", Files.readString(sibling));
    }

    @ParameterizedTest @ValueSource(strings = {"stage-reference", "applied-output", "no-stop", "local-stop", "changed-owner"})
    void refusesCleanupWithoutSafeLivenessAndOwnership(String condition) throws Exception {
        if (!condition.equals("no-stop")) stopped(condition.equals("local-stop") ? "local" : "podman");
        if (condition.equals("applied-output")) assertTrue(SolverBuildPublication.publish(connection, build, outputs, 4096, 32));
        else assertTrue(Solvers.delete(solver));
        if (!condition.equals("stage-reference")) execute(connection, "DELETE FROM starexec.jobs WHERE id=?", job);
        if (condition.equals("changed-owner")) {
            otherUser = newUser(); execute(connection, "UPDATE starexec.solvers SET user_id=? WHERE id=?", otherUser, solver);
        }
        assertFalse(purge(100));
        assertTrue(Files.isDirectory(source)); assertTrue(Files.isDirectory(build.staging()));
        assertEquals(10020, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND cleanup_token IS NOT NULL", build.artifactId()));
    }

    @Test void aLiveForeignSourceAliasRetainsTheAccountCharge() throws Exception {
        stopped("podman"); ready();
        otherUser = newUser();
        long foreign = number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'foreign-input',now(),?,20,0) RETURNING id", otherUser, source.toString());
        assertFalse(purge(100)); charged();
        assertEquals("original source", Files.readString(source.resolve("starexec_build")));
        execute(connection, "DELETE FROM starexec.solvers WHERE id=?", foreign);
        assertTrue(purge(100)); settled();
    }

    @Test void partialRemovalRetriesTheSameClaimWithoutEarlyCredit() throws Exception {
        stopped("podman"); ready();
        assertThrows(SQLException.class, () -> purge(2)); charged();
        UUID token = SolverBuildArtifacts.findCleanup(connection, build.artifactId()).orElseThrow().token();
        assertNotNull(token);
        assertTrue(purge(100)); settled();
        assertEquals(token, SolverBuildArtifacts.findCleanup(connection, build.artifactId()).orElseThrow().token());
    }

    @Test void cleanupDoesNotFollowOrChmodEscapingAndSharedLinks() throws Exception {
        stopped("podman");
        Path outside = directory(files.resolve("outside"));
        Path target = Files.writeString(outside.resolve("keep"), "outside bytes");
        var mode = PosixFilePermissions.fromString("r--------"); Files.setPosixFilePermissions(target, mode);
        Files.createLink(build.staging().resolve("hard-link"), target);
        Files.createSymbolicLink(build.staging().resolve("file-link"), target);
        Files.createSymbolicLink(build.staging().resolve("directory-link"), outside);
        ready(); assertTrue(purge(100)); settled();
        assertEquals("outside bytes", Files.readString(target));
        assertEquals(mode, Files.getPosixFilePermissions(target));
    }

    @Test void aSymbolicRegisteredRootRetainsTheClaimAndChargeForRetry() throws Exception {
        stopped("podman"); ready();
        Path original = Files.move(build.staging(), files.resolve("saved-staging"));
        Files.createSymbolicLink(build.staging(), original);
        try {
            assertThrows(SQLException.class, () -> purge(100)); charged();
            assertEquals("compiled binary", Files.readString(original.resolve("solver/program")));
        } finally { Files.delete(build.staging()); Files.move(original, build.staging()); }
        assertTrue(purge(100)); settled();
    }

    @Test void anIncorrectTrustedParentCannotRedirectRegisteredCleanup() throws Exception {
        stopped("podman"); ready();
        assertThrows(SQLException.class, () -> SolverBuildCleanup.purge(connection, build.artifactId(),
                outputs, outputs, sourceParent, 100)); charged();
        assertTrue(Files.isDirectory(build.staging()));
        assertTrue(purge(100)); settled();
    }

    @Test void aDurableClaimRejectsIdentityChangesAndLatePublication() throws Exception {
        stopped("podman"); ready();
        UUID token = UUID.randomUUID();
        assertTrue(Common.runTransactional(connection, tx -> SolverBuildArtifacts.claimCleanup(tx, build.artifactId(), token)).booleanValue());
        assertFalse(Common.runTransactional(connection, tx -> SolverBuildArtifacts.claimCleanup(tx, build.artifactId(), UUID.randomUUID())).booleanValue());
        assertThrows(SQLException.class, () -> execute(connection, "UPDATE starexec.solver_build_artifacts SET cleanup_token=? WHERE id=?", UUID.randomUUID(), build.artifactId()));
        assertThrows(SQLException.class, () -> execute(connection, "UPDATE starexec.solver_build_artifacts SET state='VALIDATED' WHERE id=?", build.artifactId()));
        assertThrows(SQLException.class, () -> execute(connection, "UPDATE starexec.solvers SET disk_size=0 WHERE id=?", solver));
        assertThrows(SQLException.class, () -> execute(connection, "DELETE FROM starexec.solvers WHERE id=?", solver));
        assertTrue(purge(100)); settled();
    }

    @Test void aCommitFailureAfterFileRemovalPreservesAccountingAndRetryIdentity() throws Exception {
        stopped("podman"); ready();
        injected = "purge_fail_" + UUID.randomUUID().toString().replace("-", "");
        execute(connection, "CREATE FUNCTION starexec." + injected + "() RETURNS trigger AS $$ BEGIN"
                + " RAISE EXCEPTION 'INJECTED CLEANUP COMMIT FAILURE'; END; $$ LANGUAGE plpgsql");
        execute(connection, "CREATE CONSTRAINT TRIGGER " + injected + " AFTER UPDATE ON starexec.solver_build_artifacts"
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.id=" + build.artifactId()
                + " AND NEW.cleanup_finished_at IS NOT NULL) EXECUTE FUNCTION starexec." + injected + "()");
        try {
            assertThrows(SQLException.class, () -> purge(100)); charged();
            assertFalse(Files.exists(source)); assertFalse(Files.exists(build.staging()));
            assertTrue(connection.getAutoCommit());
        } finally { dropTrigger(); }
        UUID token = SolverBuildArtifacts.findCleanup(connection, build.artifactId()).orElseThrow().token();
        assertTrue(purge(100)); settled();
        assertEquals(token, SolverBuildArtifacts.findCleanup(connection, build.artifactId()).orElseThrow().token());
    }

    @Test void refusesForeignTransactionsWithoutCommittingTheirChanges() throws Exception {
        stopped("podman"); ready(); connection.setAutoCommit(false);
        try {
            execute(connection, "UPDATE starexec.users SET first_name='Uncommitted' WHERE id=?", user);
            assertThrows(SQLException.class, () -> purge(100));
            assertFalse(connection.getAutoCommit());
            assertTrue(Files.isDirectory(source));
        } finally { connection.rollback(); connection.setAutoCommit(true); }
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND cleanup_token IS NOT NULL", build.artifactId()));
    }

    @Test void failureToForceParentStorageDoesNotReleaseTheCharge() throws Exception {
        stopped("podman"); ready();
        try (var directories = mockStatic(DescriptorDirectory.class, call -> {
            if (call.getMethod().getName().equals("open") && stagingParent.equals(call.getArgument(0))) {
                DescriptorDirectory real = (DescriptorDirectory) call.callRealMethod();
                DescriptorDirectory observed = spy(real);
                doThrow(new IOException("INJECTED PARENT FORCE FAILURE")).when(observed).force();
                return observed;
            }
            return call.callRealMethod();
        })) {
            assertThrows(SQLException.class, () -> purge(100)); charged();
            assertFalse(Files.exists(build.staging())); assertTrue(Files.exists(source));
        }
        assertTrue(purge(100)); settled();
    }

    @Test void aReappearedPathIsNotAdoptedByCompletedCleanup() throws Exception {
        stopped("podman"); ready(); assertTrue(purge(100)); settled();
        Files.writeString(directory(build.staging()).resolve("unregistered"), "do not adopt");
        assertThrows(SQLException.class, () -> purge(100));
        assertEquals("do not adopt", Files.readString(build.staging().resolve("unregistered")));
        assertEquals(10000, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
    }

    @Test void cleanupFencesNewConsumersUntilTheRemovedInputIsRetired() throws Exception {
        stopped("podman"); ready();
        otherUser = newUser();
        long consumerJob = number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size)"
                + " VALUES (?,'late-consumer',1,0) RETURNING id", otherUser);
        long consumer = number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data)"
                + " VALUES (?,7,1) RETURNING id", consumerJob);
        try {
            UUID token = UUID.randomUUID();
            assertTrue(Common.runTransactional(connection, tx -> SolverBuildArtifacts.claimCleanup(tx, build.artifactId(), token)).booleanValue());
            SQLException pending = assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", consumer, solver));
            assertEquals("55006", pending.getSQLState());
            assertTrue(purge(100)); settled();
            SQLException removed = assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", consumer, solver));
            assertEquals("55006", removed.getSQLState());
        } finally { execute(connection, "DELETE FROM starexec.jobs WHERE id=?", consumerJob); }
    }

    @Test void completedCleanupAllowsThePreviouslyBlockedAccountDeletion() throws Exception {
        stopped("podman"); ready();
        assertThrows(UserDeletionBlockedException.class, () -> Users.deleteUser(user));
        assertTrue(purge(100)); settled();
        assertTrue(Users.deleteUser(user));
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.users WHERE id=?", user));
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=?"
                + " AND solver_id IS NULL AND cleanup_finished_at IS NOT NULL", build.artifactId()));
    }

    @ParameterizedTest @ValueSource(strings = {"podman/normal", "kubernetes/normal", "podman/cancelled", "kubernetes/cancelled", "kubernetes/never_started"})
    void preservesMatchingCompletionCancellationAndNonExecutionProof(String outcome) throws Exception {
        String backend = outcome.split("/")[0];
        String disposition = outcome.split("/")[1];
        String engine = backend + ":7:owned-resource";
        assertTrue(SolverBuildArtifacts.bindExecution(connection, pair, 1, build.token(), engine));
        JsonObject facts = new JsonObject(); facts.addProperty("status", "exited");
        if (!disposition.equals("normal")) facts.addProperty("disposition", disposition);
        var receipt = SolverBuildStopReceipt.observed(backend, build.artifactId(), pair, 1, engine,
                "owned-resource", Instant.now(), facts);
        boolean recorded;
        if (disposition.equals("cancelled")) recorded = SolverBuildArtifacts.recordCancelled(connection, pair, 1, build.token(), engine, receipt);
        else if (disposition.equals("never_started")) recorded = SolverBuildArtifacts.recordUnstarted(connection, pair, 1, build.token(), engine, receipt);
        else recorded = SolverBuildArtifacts.recordStopReceipt(connection, pair, 1, build.token(), engine, receipt);
        assertTrue(recorded); ready(); assertTrue(purge(100)); settled();
        var restored = SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow().receipt();
        assertEquals(receipt.payload(), restored.payload()); assertEquals(receipt.sha256(), restored.sha256());
    }

    @Test void aConcurrentConsumerWaitsForTheClaimThenRefusesRemovedInput() throws Exception {
        stopped("podman"); ready(); otherUser = newUser();
        long consumerJob = number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size)"
                + " VALUES (?,'concurrent-consumer',1,0) RETURNING id", otherUser);
        long consumer = number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data)"
                + " VALUES (?,7,1) RETURNING id", consumerJob);
        var executor = Executors.newSingleThreadExecutor();
        try (Connection inserting = Common.getConnection()) {
            long pid = number(inserting, "SELECT pg_backend_pid()");
            connection.setAutoCommit(false);
            assertTrue(SolverBuildArtifacts.claimCleanup(connection, build.artifactId(), UUID.randomUUID()));
            var result = executor.submit(() -> {
                execute(inserting, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", consumer, solver);
                return true;
            });
            try {
                await().atMost(5, TimeUnit.SECONDS).until(() -> {
                    execute(connection, "SELECT pg_stat_clear_snapshot()");
                    return number(connection, "SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock'", pid) == 1;
                });
                connection.commit();
                ExecutionException refused = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                assertEquals("55006", assertInstanceOf(SQLException.class, refused.getCause()).getSQLState());
            } finally { connection.rollback(); connection.setAutoCommit(true); }
            assertTrue(purge(100)); settled();
        } finally {
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            execute(connection, "DELETE FROM starexec.jobs WHERE id=?", consumerJob);
        }
    }

    private void dropTrigger() throws SQLException {
        if (injected != null) {
            execute(connection, "DROP TRIGGER IF EXISTS " + injected + " ON starexec.solver_build_artifacts");
            execute(connection, "DROP FUNCTION IF EXISTS starexec." + injected + "()");
            injected = null;
        }
    }

    @AfterEach void cleanup() throws Exception {
        if (connection != null) {
            try {
                if (!connection.getAutoCommit()) connection.rollback();
                connection.setAutoCommit(true); dropTrigger();
                if (otherUser != 0) execute(connection, "DELETE FROM starexec.users WHERE id=?", otherUser);
                if (build != null && number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=?"
                        + " AND cleanup_token IS NOT NULL AND cleanup_finished_at IS NULL", build.artifactId()) > 0) {
                    assertTrue(purge(1000), "Owned pending cleanup must finish before discarding its synthetic owner");
                }
                execute(connection, "DELETE FROM starexec.users WHERE id=?", user);
            } finally { connection.close(); }
        }
        Files.walkFileTree(files, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
