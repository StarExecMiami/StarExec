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
import org.starexec.constants.R;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Common;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Users;
import org.starexec.data.to.SolverBuildStopReceipt;
import org.starexec.exceptions.UserDeletionBlockedException;
import org.starexec.jobs.SolverBuildCleanup;
import org.starexec.jobs.SolverBuildHelperCleanup;
import org.starexec.jobs.SolverBuildStaging;
import org.starexec.test.util.DatabaseTestSupport;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SolverBuildHelperCleanupSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private int user;
    private int solver;
    private int helper;
    private int job;
    private int pair;
    private Path benchmarkRoot;
    private Path helperFile;
    private Path stagingParent;
    private BuildExecutionContext build;
    private final List<Integer> extraJobs = new ArrayList<>();
    private String injected;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("SolverBuildHelperCleanupSqlTest");
        Common.initialize();
    }

    private static long number(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = con.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            try (var result=statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }

    private static void execute(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement=con.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            statement.execute();
        }
    }

    @BeforeEach void seed() throws Exception {
        connection=Common.getConnection();
        user=(int) number(connection,"INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Helper','synthetic',now(),'synthetic',1000000,100,10025) RETURNING id",
                "helper-"+UUID.randomUUID()+"@example.invalid");
        Path source=Files.createDirectory(files.resolve("solver_src"));
        Files.writeString(source.resolve("starexec_build"),"original source");
        solver=(int) number(connection,"INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'helper-build',now(),?,20,0) RETURNING id",user,source.toString());
        benchmarkRoot=Files.createDirectory(files.resolve("benchmarks"));
        Path parent=Files.createDirectories(benchmarkRoot.resolve(user+"/unique-helper"));
        helperFile=Files.writeString(parent.resolve("starexec_build"),"dummy");
        helper=(int) number(connection,"INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size)"
                + " VALUES (?,'starexec_build',now(),?,5) RETURNING id",user,helperFile.toString());
        job=(int) number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,completed_pairs,disk_size,buildjob)"
                + " VALUES (?,'build',1,1,0,true) RETURNING id",user);
        pair=(int) number(connection,"INSERT INTO starexec.job_pairs(job_id,bench_id,status_code,primary_jobpair_data)"
                + " VALUES (?,?,7,1) RETURNING id",job,helper);
        execute(connection,"INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size)"
                + " VALUES (?,1,7,?,0)",pair,solver);
        stagingParent=Files.createDirectory(files.resolve("staging"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        build=SolverBuildStaging.prepare(connection,pair,1,stagingParent);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void ordinaryDeletionCannotRetireAManagedHelpersInputOrCharge(boolean recycled) throws Exception {
        if (recycled) execute(connection,"UPDATE starexec.benchmarks SET recycled=true WHERE id=?",helper);
        boolean removed;
        try (var roots=mockStatic(R.class,CALLS_REAL_METHODS)) {
            roots.when(R::getBenchmarkPath).thenReturn(benchmarkRoot.toString());
            removed=recycled ? Benchmarks.setRecycledBenchmarksToDeleted(user) : Benchmarks.delete(helper);
        }
        assertAll(() -> assertFalse(removed),
                () -> assertEquals("dummy",Files.readString(helperFile)),
                () -> assertEquals(10025,number(connection,"SELECT disk_size FROM starexec.users WHERE id=?",user)),
                () -> assertEquals(5,number(connection,"SELECT disk_size FROM starexec.benchmarks WHERE id=?",helper)));
    }

    private void stopped(BuildExecutionContext context, String backend, String disposition) throws Exception {
        String engine = backend + ":7:owned-helper-reader-" + context.attemptNo();
        assertTrue(SolverBuildArtifacts.bindExecution(connection, context.pairId(), context.attemptNo(), context.token(), engine));
        JsonObject facts = new JsonObject(); facts.addProperty("status", "exited");
        if (disposition != null) facts.addProperty("disposition", disposition);
        var receipt = SolverBuildStopReceipt.observed(backend, context.artifactId(), context.pairId(), context.attemptNo(),
                engine, "owned-reader-" + context.attemptNo(), Instant.now(), facts);
        boolean stored;
        if ("never_started".equals(disposition)) stored = SolverBuildArtifacts.recordUnstarted(connection, context.pairId(), context.attemptNo(), context.token(), engine, receipt);
        else if ("cancelled".equals(disposition)) stored = SolverBuildArtifacts.recordCancelled(connection, context.pairId(), context.attemptNo(), context.token(), engine, receipt);
        else stored = SolverBuildArtifacts.recordStopReceipt(connection, context.pairId(), context.attemptNo(), context.token(), engine, receipt);
        assertTrue(stored);
    }

    private boolean purge() throws SQLException {
        return SolverBuildHelperCleanup.purge(connection, helper, benchmarkRoot);
    }

    private void charged() throws Exception {
        assertEquals(10025, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        assertEquals(5, number(connection, "SELECT disk_size FROM starexec.benchmarks WHERE id=?", helper));
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solver_build_helpers WHERE benchmark_id=? AND cleanup_finished_at IS NOT NULL", helper));
    }

    private void settled() throws Exception {
        assertFalse(Files.exists(helperFile));
        assertEquals(10020, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        assertEquals(0, number(connection, "SELECT disk_size FROM starexec.benchmarks WHERE id=?", helper));
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.benchmarks WHERE id=? AND deleted", helper));
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.solver_build_helpers WHERE benchmark_id=?"
                + " AND cleanup_token IS NOT NULL AND cleanup_finished_at IS NOT NULL AND byte_size=5", helper));
    }

    @ParameterizedTest @ValueSource(strings={"live-metadata","pair-detached","pair-and-solver-detached"})
    void preservedHelperIdentitySupportsCleanupAfterMetadataDetaches(String mode) throws Exception {
        var captured = SolverBuildArtifacts.findHelper(connection, helper).orElseThrow();
        assertEquals(user, captured.ownerId()); assertEquals(helperFile.toString(), captured.path()); assertEquals(5, captured.byteSize());
        stopped(build, "podman", null);
        var receipt = SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow();
        if (!mode.equals("live-metadata")) execute(connection, "DELETE FROM starexec.jobs WHERE id=?", job);
        if (mode.equals("pair-and-solver-detached")) execute(connection, "DELETE FROM starexec.solvers WHERE id=?", solver);
        Path sibling = Files.writeString(helperFile.resolveSibling("keep"), "unrelated input");
        assertTrue(purge()); settled();
        var finished = SolverBuildArtifacts.findHelper(connection, helper).orElseThrow();
        assertTrue(purge()); settled(); assertEquals(finished, SolverBuildArtifacts.findHelper(connection, helper).orElseThrow());
        assertEquals(receipt.receipt().payload(), SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow().receipt().payload());
        assertEquals("unrelated input", Files.readString(sibling));
    }

    @ParameterizedTest @ValueSource(strings={"podman/normal","kubernetes/cancelled","kubernetes/never_started"})
    void cleanupPreservesTheProofKindWithoutPublishingOutput(String proof) throws Exception {
        String[] parts = proof.split("/");
        stopped(build, parts[0], parts[1].equals("normal") ? null : parts[1]);
        var receipt = SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow();
        assertTrue(purge()); settled();
        assertEquals(receipt.receipt().sha256(), SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow().receipt().sha256());
        assertEquals(0, number(connection, "SELECT build_status FROM starexec.solvers WHERE id=?", solver));
    }

    @Test void allSharedAttemptsMustStopBeforeOneChargeCanBeSettled() throws Exception {
        stopped(build, "podman", null);
        execute(connection, "UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?", pair);
        var next = SolverBuildStaging.prepare(connection, pair, 2, stagingParent);
        assertFalse(purge()); charged();
        assertEquals("dummy", Files.readString(helperFile));
        stopped(next, "kubernetes", null);
        assertTrue(purge()); settled(); assertTrue(purge()); settled();
        assertEquals(2, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts WHERE helper_benchmark_id=?", helper));
    }

    @Test void aMissingStopReceiptKeepsInputAndItsCharge() throws Exception {
        assertFalse(purge()); charged(); assertEquals("dummy", Files.readString(helperFile));
    }

    @Test void aLocalSnapshotCannotAuthorizeHelperCleanup() throws Exception {
        connection.setAutoCommit(false);
        try {
            stopped(build, "local", null);
            assertFalse(SolverBuildArtifacts.claimHelperCleanup(connection, helper, UUID.randomUUID()));
            charged(); assertEquals("dummy", Files.readString(helperFile));
        } finally { connection.rollback(); connection.setAutoCommit(true); }
    }

    private int consumer(boolean buildJob, Integer benchmark) throws Exception {
        int id = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                + " VALUES (?,'helper-consumer',1,0,?) RETURNING id", user, buildJob);
        extraJobs.add(id);
        return (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,bench_id,status_code,primary_jobpair_data) VALUES (?,?,7,1) RETURNING id", id, benchmark);
    }

    @Test void anUnrelatedConsumerKeepsTheSharedHelperAlive() throws Exception {
        stopped(build, "podman", null);
        consumer(false, helper);
        assertFalse(purge()); charged(); assertEquals("dummy", Files.readString(helperFile));
        execute(connection, "DELETE FROM starexec.jobs WHERE id=?", extraJobs.remove(0));
        assertTrue(purge()); settled();
    }

    @Test void admissionCannotGuessAHelperMissingFromTheOriginalSnapshot() throws Exception {
        int unknown = consumer(true, null);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", unknown, solver);
        execute(connection, "UPDATE starexec.job_pairs SET bench_id=? WHERE id=?", helper, unknown);
        assertThrows(SQLException.class, () -> SolverBuildStaging.prepare(connection, unknown, 1, stagingParent));
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts WHERE origin_pair_id=? AND execution_token IS NOT NULL", unknown));
        charged();
    }

    @Test void registeredHelperAndBenchmarkSnapshotsCannotBeRewritten() throws Exception {
        assertThrows(SQLException.class, () -> execute(connection, "UPDATE starexec.solver_build_helpers SET byte_size=0 WHERE benchmark_id=?", helper));
        assertThrows(SQLException.class, () -> execute(connection, "UPDATE starexec.solver_build_artifacts SET helper_benchmark_id=NULL WHERE id=?", build.artifactId()));
        assertThrows(SQLException.class, () -> execute(connection, "UPDATE starexec.benchmarks SET path=? WHERE id=?", helperFile.resolveSibling("other").toString(), helper));
        assertThrows(SQLException.class, () -> execute(connection, "DELETE FROM starexec.benchmarks WHERE id=?", helper));
        charged();
    }

    @Test void anInaccessibleParentRetainsTheClaimAndChargeForRetry() throws Exception {
        stopped(build, "podman", null);
        var original = Files.getPosixFilePermissions(helperFile.getParent());
        try {
            Files.setPosixFilePermissions(helperFile.getParent(), PosixFilePermissions.fromString("---------"));
            assertThrows(SQLException.class, this::purge); charged();
        } finally { Files.setPosixFilePermissions(helperFile.getParent(), original); }
        UUID token = SolverBuildArtifacts.findHelper(connection, helper).orElseThrow().token();
        assertNotNull(token); assertTrue(purge()); settled();
        assertEquals(token, SolverBuildArtifacts.findHelper(connection, helper).orElseThrow().token());
    }

    @Test void failedParentForcingDoesNotReleaseTheCharge() throws Exception {
        stopped(build, "podman", null);
        try (var directories = mockStatic(DescriptorDirectory.class, call -> {
            if (call.getMethod().getName().equals("open") && helperFile.getParent().equals(call.getArgument(0))) {
                DescriptorDirectory observed = spy((DescriptorDirectory) call.callRealMethod());
                doThrow(new IOException("INJECTED HELPER FORCE FAILURE")).when(observed).force();
                return observed;
            }
            return call.callRealMethod();
        })) {
            assertThrows(SQLException.class, this::purge); charged(); assertFalse(Files.exists(helperFile));
        }
        assertTrue(purge()); settled();
    }

    @Test void anIncorrectBenchmarkRootCannotRedirectCleanup() throws Exception {
        stopped(build, "podman", null);
        assertThrows(SQLException.class, () -> SolverBuildHelperCleanup.purge(connection, helper, stagingParent));
        charged(); assertEquals("dummy", Files.readString(helperFile)); assertTrue(purge()); settled();
    }

    @Test void failedCommitAfterUnlinkKeepsTheChargeAndRetryIdentity() throws Exception {
        stopped(build, "podman", null);
        injected = "helper_fail_" + UUID.randomUUID().toString().replace("-", "");
        execute(connection, "CREATE FUNCTION starexec." + injected + "() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'INJECTED HELPER COMMIT FAILURE'; END; $$ LANGUAGE plpgsql");
        execute(connection, "CREATE CONSTRAINT TRIGGER " + injected + " AFTER UPDATE ON starexec.solver_build_helpers DEFERRABLE INITIALLY DEFERRED"
                + " FOR EACH ROW WHEN (NEW.benchmark_id=" + helper + " AND NEW.cleanup_finished_at IS NOT NULL) EXECUTE FUNCTION starexec." + injected + "()");
        try { assertThrows(SQLException.class, this::purge); charged(); assertFalse(Files.exists(helperFile)); }
        finally { dropTrigger(); }
        UUID token = SolverBuildArtifacts.findHelper(connection, helper).orElseThrow().token();
        assertTrue(purge()); settled(); assertEquals(token, SolverBuildArtifacts.findHelper(connection, helper).orElseThrow().token());
    }

    @Test void completedCleanupDoesNotAdoptAReappearedFile() throws Exception {
        stopped(build, "podman", null); assertTrue(purge()); settled();
        Files.writeString(helperFile, "new unregistered input");
        assertThrows(SQLException.class, this::purge);
        assertEquals("new unregistered input", Files.readString(helperFile));
        assertEquals(10020, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
    }

    @Test void refusesForeignTransactionsWithoutChangingThem() throws Exception {
        stopped(build, "podman", null); connection.setAutoCommit(false);
        try { assertThrows(SQLException.class, this::purge); assertFalse(connection.getAutoCommit()); charged(); }
        finally { connection.rollback(); connection.setAutoCommit(true); }
    }

    @Test void aConcurrentConsumerWaitsForTheClaimThenRefusesInput() throws Exception {
        stopped(build, "podman", null);
        int newJob = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size) VALUES (?,'late-helper',1,0) RETURNING id", user);
        extraJobs.add(newJob);
        var executor = Executors.newSingleThreadExecutor();
        try (Connection inserting = Common.getConnection()) {
            long pid = number(inserting, "SELECT pg_backend_pid()");
            connection.setAutoCommit(false);
            assertTrue(SolverBuildArtifacts.claimHelperCleanup(connection, helper, UUID.randomUUID()));
            var result = executor.submit(() -> number(inserting,
                    "INSERT INTO starexec.job_pairs(job_id,bench_id,status_code,primary_jobpair_data) VALUES (?,?,7,1) RETURNING id", newJob, helper));
            try {
                await().atMost(5, TimeUnit.SECONDS).until(() -> {
                    execute(connection, "SELECT pg_stat_clear_snapshot()");
                    return number(connection, "SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock'", pid) == 1;
                });
                connection.commit();
                ExecutionException blocked = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                assertEquals("55006", assertInstanceOf(SQLException.class, blocked.getCause()).getSQLState());
            } finally { connection.rollback(); connection.setAutoCommit(true); }
            assertTrue(purge()); settled();
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void helperChargeKeepsItsOwnerUntilTheOtherBuildStorageIsGone() throws Exception {
        stopped(build, "podman", null);
        execute(connection, "DELETE FROM starexec.jobs WHERE id=?", job);
        assertTrue(Solvers.delete(solver));
        Path outputs = Files.createDirectory(files.resolve("outputs"));
        assertTrue(SolverBuildCleanup.purge(connection, build.artifactId(), stagingParent, outputs, files, 100));
        assertEquals(10005, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        assertThrows(UserDeletionBlockedException.class, () -> Users.deleteUser(user));
        assertTrue(purge());
        assertEquals(10000, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        assertTrue(Users.deleteUser(user));
    }

    private void dropTrigger() throws SQLException {
        if (injected != null) {
            execute(connection, "DROP TRIGGER IF EXISTS " + injected + " ON starexec.solver_build_helpers");
            execute(connection, "DROP FUNCTION IF EXISTS starexec." + injected + "()");
            injected = null;
        }
    }

    @Test void ordinaryRecycledBenchmarksStillDeleteAfterTheDatabaseGuard() throws Exception {
        Path parent = Files.createDirectory(benchmarkRoot.resolve("ordinary"));
        Path ordinary = Files.writeString(parent.resolve("input.p"), "bytes");
        int id = (int) number(connection, "INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size,recycled)"
                + " VALUES (?,'input.p',now(),?,5,true) RETURNING id", user, ordinary.toString());
        execute(connection, "UPDATE starexec.users SET disk_size=disk_size+5 WHERE id=?", user);
        try (var roots = mockStatic(R.class, CALLS_REAL_METHODS)) {
            roots.when(R::getBenchmarkPath).thenReturn(benchmarkRoot.toString());
            assertTrue(Benchmarks.setRecycledBenchmarksToDeleted(user));
        }
        assertFalse(Files.exists(ordinary));
        assertEquals(0, number(connection, "SELECT disk_size FROM starexec.benchmarks WHERE id=?", id));
        charged(); assertEquals("dummy", Files.readString(helperFile));
    }

    @Test void ordinaryConsumersDoNotAcquireManagedHelperLocks() throws Exception {
        int ordinary = (int) number(connection, "INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size)"
                + " VALUES (?,'ordinary-input',now(),?,0) RETURNING id", user, files.resolve("ordinary-input").toString());
        int ordinaryJob = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size) VALUES (?,'ordinary-consumer',1,0) RETURNING id", user);
        extraJobs.add(ordinaryJob);
        connection.setAutoCommit(false);
        try (Connection inserting = java.sql.DriverManager.getConnection(R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD)) {
            number(connection, "SELECT id FROM starexec.benchmarks WHERE id=? FOR UPDATE", ordinary);
            execute(inserting, "SET lock_timeout='400ms'");
            assertDoesNotThrow(() -> number(inserting, "INSERT INTO starexec.job_pairs(job_id,bench_id,status_code,primary_jobpair_data)"
                    + " VALUES (?,?,7,1) RETURNING id", ordinaryJob, ordinary));
        } finally { connection.rollback(); connection.setAutoCommit(true); }
    }

    @Test void cleanupAncestorsRetainAnotherOwnersManagedHelper() throws Exception {
        int other = (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Ancestor','synthetic',now(),'synthetic',1000000,100,0) RETURNING id",
                "ancestor-" + UUID.randomUUID() + "@example.invalid");
        try {
            var roots = connection.createArrayOf("TEXT", new String[]{helperFile.getParent().toString()});
            SQLException blocked = assertThrows(SQLException.class, () -> execute(connection,
                    "SELECT starexec.assert_user_solver_storage_deletable(?,?)", other, roots));
            assertEquals("55006", blocked.getSQLState());
            assertEquals("dummy", Files.readString(helperFile)); charged();
        } finally { execute(connection, "DELETE FROM starexec.users WHERE id=?", other); }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void cleanupUnlinksOnlyTheCapturedEntryWithoutChangingLinkedTargets(boolean symbolic) throws Exception {
        stopped(build, "podman", null);
        Path target = Files.writeString(files.resolve("outside-input"), "outside bytes");
        var mode = PosixFilePermissions.fromString("r--------");
        Files.setPosixFilePermissions(target, mode); Files.delete(helperFile);
        if (symbolic) Files.createSymbolicLink(helperFile, target); else Files.createLink(helperFile, target);
        assertTrue(purge()); settled();
        assertEquals("outside bytes", Files.readString(target)); assertEquals(mode, Files.getPosixFilePermissions(target));
    }

    @AfterEach void cleanup() throws Exception {
        if (connection!=null) {
            try {
                if (!connection.getAutoCommit()) connection.rollback();
                connection.setAutoCommit(true); dropTrigger();
                for (int id : extraJobs) execute(connection, "DELETE FROM starexec.jobs WHERE id=?", id);
                if (build != null && number(connection, "SELECT count(*) FROM starexec.users WHERE id=?", user) > 0) {
                    // These fixtures never launch a provider; conserve synthetic non-execution before teardown.
                    List<int[]> readers = new ArrayList<>();
                    try (PreparedStatement statement = connection.prepareStatement("SELECT origin_pair_id,attempt_no FROM starexec.solver_build_artifacts"
                            + " WHERE helper_benchmark_id=? AND execution_token IS NOT NULL AND execution_stop_receipt_payload IS NULL")) {
                        statement.setInt(1, helper);
                        try (var result = statement.executeQuery()) { while (result.next()) readers.add(new int[]{result.getInt(1),result.getInt(2)}); }
                    }
                    for (int[] reader : readers) {
                        var artifact = SolverBuildArtifacts.find(connection, reader[0], reader[1]).orElseThrow();
                        var context = new BuildExecutionContext(artifact.id(), reader[0], reader[1], artifact.executionToken(),
                                Path.of(artifact.sourcePath()), Path.of(artifact.stagingPath()));
                        stopped(context, "kubernetes", "never_started");
                    }
                    if (SolverBuildArtifacts.findHelper(connection, helper).orElseThrow().finishedAt() == null) {
                        assertTrue(purge(), "Owned helper recovery must settle before removing its synthetic account");
                    }
                }
                execute(connection,"DELETE FROM starexec.users WHERE id=?",user);
            }
            finally { connection.close(); }
        }
    }
}
