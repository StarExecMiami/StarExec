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
import org.starexec.data.database.Common;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.database.Users;
import org.starexec.data.to.SolverBuildStopReceipt;
import org.starexec.exceptions.UserDeletionBlockedException;
import org.starexec.jobs.SolverBuildPublication;
import org.starexec.jobs.SolverBuildStaging;
import org.starexec.test.util.DatabaseTestSupport;

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
import java.util.function.IntFunction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSolverStorageDeletionSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private int user;
    private int otherUser;
    private int solver;
    private int pair;
    private int job;
    private Path source;
    private Path outputs;
    private BuildExecutionContext build;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("UserSolverStorageDeletionSqlTest");
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

    private int newUser() throws SQLException {
        return (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Deletion','synthetic',now(),'synthetic',1000000,100,20) RETURNING id",
                "udel-" + UUID.randomUUID() + "@example.invalid");
    }

    @BeforeEach void seed() throws Exception {
        connection = Common.getConnection();
        user = newUser();
        source = Files.createDirectories(files.resolve("solvers/" + user + "/solver_src"));
        Files.writeString(source.resolve("starexec_build"), "original source");
        solver = (int) number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'user-storage',now(),?,20,0) RETURNING id", user, source.toString());
        job = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,completed_pairs,disk_size,buildjob)"
                + " VALUES (?,'build',1,1,0,true) RETURNING id", user);
        pair = (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data)"
                + " VALUES (?,7,1) RETURNING id", job);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size)"
                + " VALUES (?,1,7,?,0)", pair, solver);
        outputs = Files.createDirectory(files.resolve("outputs"));
    }

    private void prepare() throws Exception {
        build = SolverBuildStaging.prepare(connection, pair, 1, Files.createDirectory(files.resolve("staging")));
        Files.writeString(Files.createDirectory(build.staging().resolve("solver")).resolve("program"), "compiled binary");
        Files.writeString(Files.createDirectory(build.staging().resolve("logs")).resolve(R.SOLVER_BUILD_OUTPUT), "build log");
    }

    private void stop() throws Exception {
        String engine = "podman:7:owned-resource";
        assertTrue(SolverBuildArtifacts.bindExecution(connection, pair, 1, build.token(), engine));
        JsonObject facts = new JsonObject(); facts.addProperty("status", "exited");
        var receipt = SolverBuildStopReceipt.observed("podman", build.artifactId(), pair, 1, engine,
                "owned-resource", Instant.now(), facts);
        assertTrue(SolverBuildArtifacts.recordStopReceipt(connection, pair, 1, build.token(), engine, receipt));
    }

    private boolean deleteUser() throws Exception {
        return deleteUser(id -> files.resolve("jobs/" + id).toString());
    }

    private boolean deleteUser(IntFunction<String> jobDirectory) throws Exception {
        // Only directory resolution is substituted; SQL, lifecycle and file deletion run normally.
        try (var roots = mockStatic(R.class, CALLS_REAL_METHODS);
             var jobs = mockStatic(Jobs.class, call -> {
                 if (call.getMethod().getName().equals("getDirectory")) {
                     return jobDirectory.apply(call.getArgument(0));
                 }
                 return CALLS_REAL_METHODS.answer(call);
             })) {
            roots.when(R::getSolverPath).thenReturn(files.resolve("solvers").toString());
            roots.when(R::getBenchmarkPath).thenReturn(files.resolve("benchmarks").toString());
            roots.when(R::getPicturePath).thenReturn(files.resolve("pictures").toString());
            return Users.deleteUser(user);
        }
    }

    private void retained() throws Exception {
        long copied = build == null ? 0 : number(connection,
                "SELECT COALESCE(sum(charged_bytes),0) FROM starexec.solver_build_storage_charges"
                        + " WHERE artifact_id=? AND released_at IS NULL", build.artifactId());
        assertEquals(20 + copied, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        assertEquals(20, number(connection, "SELECT disk_size FROM starexec.solvers WHERE id=?", solver));
        assertEquals("original source", Files.readString(source.resolve("starexec_build")));
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.jobs WHERE id=?", job));
        if (build != null) {
            assertEquals(1, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts"
                    + " WHERE id=? AND solver_id=? AND pair_id=?", build.artifactId(), solver, pair));
            assertEquals("compiled binary", Files.readString(build.staging().resolve("solver/program")));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"prepared", "published", "failed"})
    void userDeletionRetainsManagedSourceOutputAndAccounting(String scenario) throws Exception {
        prepare();
        if (!scenario.equals("prepared")) {
            stop();
            if (scenario.equals("failed")) {
                execute(connection, "UPDATE starexec.job_pairs SET status_code=11 WHERE id=?", pair);
                assertTrue(SolverBuildPublication.recordFailure(connection, build, outputs, 4096, 32));
            } else assertTrue(SolverBuildPublication.publish(connection, build, outputs, 4096, 32));
        }
        assertThrows(UserDeletionBlockedException.class, this::deleteUser);
        retained();
        if (!scenario.equals("prepared")) {
            Path root = outputs.resolve("build-" + build.artifactId() + "-" + build.token());
            assertEquals("build log", Files.readString(root.resolve("logs/" + R.SOLVER_BUILD_OUTPUT)));
            if (scenario.equals("published")) assertEquals("compiled binary", Files.readString(root.resolve("solver/program")));
        }
    }

    @Test void storedUserDeletionCannotBypassManagedRetention() throws Exception {
        prepare();
        SQLException failure = assertThrows(SQLException.class,
                () -> execute(connection, "SELECT starexec.DeleteUser(?)", user));
        assertEquals("55006", failure.getSQLState());
        retained();
    }

    @Test void anotherUsersHistoricalStageRetainsTheSolverOwner() throws Exception {
        otherUser = newUser();
        long otherJob = number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size)"
                + " VALUES (?,'foreign-reference',1,0) RETURNING id", otherUser);
        long otherPair = number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data)"
                + " VALUES (?,7,1) RETURNING id", otherJob);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size)"
                + " VALUES (?,1,7,?,0)", otherPair, solver);
        assertThrows(UserDeletionBlockedException.class, this::deleteUser);
        retained();
        assertEquals(solver, number(connection, "SELECT solver_id FROM starexec.jobpair_stage_data WHERE jobpair_id=?", otherPair));
    }

    @Test void ordinaryLegacyUserDeletionCanRemoveItsOwnHistoricalStageAndFiles() throws Exception {
        assertTrue(deleteUser());
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.users WHERE id=?", user));
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solvers WHERE id=?", solver));
        assertFalse(Files.exists(source));
    }

    private int foreignSolver(Path path, boolean managed) throws Exception {
        if (otherUser == 0) otherUser = newUser();
        Files.createDirectories(path);
        Files.writeString(path.resolve("starexec_build"), "foreign source");
        int foreign = (int) number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'foreign-storage',now(),?,20,0) RETURNING id", otherUser, path.toString());
        if (managed) {
            long foreignJob = number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,completed_pairs,disk_size,buildjob)"
                    + " VALUES (?,'foreign-build',1,1,0,true) RETURNING id", otherUser);
            int foreignPair = (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data)"
                    + " VALUES (?,7,1) RETURNING id", foreignJob);
            execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size)"
                    + " VALUES (?,1,7,?,0)", foreignPair, foreign);
            SolverBuildStaging.prepare(connection, foreignPair, 1, Files.createDirectory(files.resolve("foreign-staging")));
        }
        return foreign;
    }

    @ParameterizedTest @ValueSource(strings = {"solvers", "benchmarks", "jobs"})
    void cleanupAncestorsCannotEraseDetachedForeignSources(String location) throws Exception {
        int parent = location.equals("jobs") ? job : user;
        Path foreign = files.resolve(location + "/" + parent + "/foreign_src");
        int foreignId = foreignSolver(foreign, true);
        long artifact = number(connection, "SELECT id FROM starexec.solver_build_artifacts WHERE solver_id=?", foreignId);
        execute(connection, "DELETE FROM starexec.users WHERE id=?", otherUser);
        assertThrows(UserDeletionBlockedException.class, this::deleteUser);
        retained();
        assertEquals("foreign source", Files.readString(foreign.resolve("starexec_build")));
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND solver_id IS NULL", artifact));
    }

    @Test void cleanupAncestorsAlsoRetainAnotherUsersOrdinarySolver() throws Exception {
        Path foreign = files.resolve("solvers/" + user + "/foreign_src");
        int foreignId = foreignSolver(foreign, false);
        assertThrows(UserDeletionBlockedException.class, this::deleteUser);
        retained();
        assertEquals(otherUser, number(connection, "SELECT user_id FROM starexec.solvers WHERE id=?", foreignId));
        assertEquals("foreign source", Files.readString(foreign.resolve("starexec_build")));
    }

    @Test void aSimilarSiblingPrefixDoesNotPreventOrdinaryDeletion() throws Exception {
        Path foreign = files.resolve("solvers/" + user + "0/foreign_src");
        foreignSolver(foreign, false);
        assertTrue(deleteUser());
        assertEquals("foreign source", Files.readString(foreign.resolve("starexec_build")));
        assertEquals(20, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", otherUser));
    }

    @Test void symbolicCleanupAncestorsPreventTheDatabaseCascade() throws Exception {
        Path original = files.resolve("solvers");
        Path real = Files.move(original, files.resolve("protected-solvers"));
        Files.createSymbolicLink(original, real);
        assertFalse(deleteUser());
        retained();
    }

    @Test void cleanupUsesTheJobDirectoryCheckedBeforeTheCascade() throws Exception {
        Path ownedJob = Files.createDirectories(files.resolve("jobs/" + job));
        Files.writeString(ownedJob.resolve("output"), "owned output");
        Path foreign = files.resolve("foreign_src");
        foreignSolver(foreign, false);
        AtomicInteger lookups = new AtomicInteger();
        assertTrue(deleteUser(id -> lookups.getAndIncrement() == 0 ? ownedJob.toString() : foreign.toString()));
        assertFalse(Files.exists(ownedJob));
        assertEquals("foreign source", Files.readString(foreign.resolve("starexec_build")));
    }

    @Test void aPreparedWriterPreventsDeletionBeforeItsBindingCommits() throws Exception {
        connection.setAutoCommit(false);
        try {
            UUID token = UUID.randomUUID();
            assertEquals(1, number(connection, "SELECT starexec.prepare_solver_build_storage(?,?,?,?)::int",
                    pair, 1, token, files.resolve("pending-staging").toString()));
            UserDeletionBlockedException blocked = assertThrows(UserDeletionBlockedException.class, this::deleteUser);
            assertEquals(UserDeletionBlockedException.SOLVER_STORAGE_IN_USE, blocked.getReasonCode());
            connection.commit();
            retained();
            assertEquals(1, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts WHERE pair_id=? AND execution_token=?", pair, token));
        } finally { connection.rollback(); connection.setAutoCommit(true); }
    }

    @Test void deletionLocksBarALateBuildPreparationUntilItsIdentityIsGone() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (Connection preparing = Common.getConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "SELECT starexec.assert_user_solver_storage_deletable(?)", user);
            long pid = number(preparing, "SELECT pg_backend_pid()");
            var result = executor.submit(() -> number(preparing,
                    "SELECT starexec.prepare_solver_build_storage(?,?,?,?)::int",
                    pair, 1, UUID.randomUUID(), files.resolve("late-staging").toString()));
            try {
                await().atMost(5, TimeUnit.SECONDS).until(() -> number(connection,
                        "SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock'", pid) == 1);
                execute(connection, "SELECT starexec.DeleteUser(?)", user);
                connection.commit();
                assertEquals(0, result.get(5, TimeUnit.SECONDS));
                assertEquals(0, number(connection, "SELECT count(*) FROM starexec.users WHERE id=?", user));
            } finally { connection.rollback(); connection.setAutoCommit(true); }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @AfterEach void cleanup() throws Exception {
        if (connection != null) {
            try {
                if (!connection.getAutoCommit()) connection.rollback();
                connection.setAutoCommit(true);
                if (otherUser != 0) execute(connection, "DELETE FROM starexec.users WHERE id=?", otherUser);
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
