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
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.database.Common;
import org.starexec.data.database.Solvers;
import org.starexec.data.to.Solver;
import org.starexec.data.to.SolverBuildStopReceipt;
import org.starexec.jobs.SolverBuildPublication;
import org.starexec.jobs.SolverBuildStaging;
import org.starexec.jobs.SolverArtifactTree;
import org.starexec.test.util.DatabaseTestSupport;
import org.starexec.servlets.Download;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual catalogue and filesystem coordination; provider observations are synthetic fixtures. */
class SolverBuildPublicationSqlTest {
    @TempDir Path files;
    private Connection connection;
    private int user;
    private int otherUser;
    private int solver;
    private int pair;
    private Path source;
    private Path parent;
    private BuildExecutionContext build;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("SolverBuildPublicationSqlTest");
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD);
    }

    private static long number(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }

    private static String text(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
        }
    }

    private static void execute(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.execute();
        }
    }

    private Path privateDirectory(Path path) throws IOException {
        return Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @BeforeEach void seed() throws Exception {
        connection = connect();
        user = (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Publication','synthetic',now(),'synthetic',1000000,100,0) RETURNING id",
                "publication-" + UUID.randomUUID() + "@example.invalid");
        source = privateDirectory(files.resolve("source_é'_src"));
        Files.writeString(source.resolve("starexec_build"), "original source");
        solver = (int) number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'publication',now(),?,20,0) RETURNING id", user, source.toString());
        long job = number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'build',1,0,true) RETURNING id", user);
        pair = (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id", job);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", pair, solver);
        build = SolverBuildStaging.prepare(connection, pair, 1, privateDirectory(files.resolve("staging")));
        Path programs = Files.createDirectory(build.staging().resolve("solver"));
        Files.writeString(programs.resolve("program"), "compiled binary α\n");
        Files.setPosixFilePermissions(programs.resolve("program"), PosixFilePermissions.fromString("rwxr-x---"));
        Files.writeString(Files.createDirectory(build.staging().resolve("logs")).resolve("starexec_build_log"), "build log é\n");
        parent = privateDirectory(files.resolve("immutable outputs'"));
    }

    @AfterEach void cleanup() throws Exception {
        if (connection != null) {
            try {
                if (!connection.getAutoCommit()) connection.rollback();
                connection.setAutoCommit(true);
                execute(connection, "DELETE FROM starexec.users WHERE id=?", user);
                if (otherUser != 0) execute(connection, "DELETE FROM starexec.users WHERE id=?", otherUser);
            } finally { connection.close(); }
        }
        Files.walkFileTree(files, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String engine(String backend) { return backend + ":7:owned-resource"; }

    private void stopped(String backend, String disposition) throws Exception {
        assertTrue(SolverBuildArtifacts.bindExecution(connection, pair, 1, build.token(), engine(backend)));
        JsonObject facts = new JsonObject(); facts.addProperty("status", "exited");
        if (disposition != null) facts.addProperty("disposition", disposition);
        var receipt = SolverBuildStopReceipt.observed(backend, build.artifactId(), pair, 1, engine(backend),
                "owned-resource", Instant.now(), facts);
        assertTrue(SolverBuildArtifacts.recordStopReceipt(connection, pair, 1, build.token(), engine(backend), receipt));
    }

    private Path root() { return parent.resolve("build-" + build.artifactId() + "-" + build.token()); }

    private boolean publish() throws Exception { return SolverBuildPublication.publish(connection, build, parent, 4096, 32); }

    private String state() throws SQLException {
        return text(connection, "SELECT state FROM starexec.solver_build_artifacts WHERE id=?", build.artifactId());
    }

    private void unchangedSource() throws Exception {
        assertEquals("original source", Files.readString(source.resolve("starexec_build")));
        assertEquals("compiled binary α\n", Files.readString(build.staging().resolve("solver/program")));
        assertEquals("build log é\n", Files.readString(build.staging().resolve("logs/starexec_build_log")));
    }

    @Test void failedOutcomeRequiresVerifiedLogs() throws Exception {
        stopped("podman", null);
        execute(connection, "UPDATE starexec.job_pairs SET status_code=11 WHERE id=?", pair);
        var receipt = SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow();
        assertEquals("false", text(connection,
                "SELECT starexec.record_failed_solver_build_output(?,?,?,?)::text", build.artifactId(),
                build.token(), engine("podman"), receipt.receipt().sha256()));
        assertEquals("PLANNED", state());
        assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
        assertEquals(0, number(connection, "SELECT build_status FROM starexec.solvers WHERE id=?", solver));
        unchangedSource();
    }

    private void failedStop(String backend) throws Exception {
        stopped(backend, null);
        execute(connection, "UPDATE starexec.job_pairs SET status_code=11 WHERE id=?", pair);
    }

    private boolean recordFailure() throws Exception {
        return SolverBuildPublication.recordFailure(connection, build, parent, 4096, 32);
    }

    private boolean recordFailureMetadata() throws SQLException {
        var receipt = SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow();
        return SolverBuildArtifacts.recordFailedOutput(connection, build, engine(receipt.receipt().backend()), receipt.receipt().sha256());
    }

    private String frozenManifest() throws SQLException {
        return text(connection, "SELECT row(manifest_payload,manifest_sha256,byte_size,validated_at,published_at)::text"
                + " FROM starexec.solver_build_artifacts WHERE id=?", build.artifactId());
    }

    private void freezeFailureBeforeMetadata() throws Exception {
        try (Connection blocker = connect()) {
            blocker.setAutoCommit(false);
            number(blocker, "SELECT id FROM starexec.solvers WHERE id=? FOR UPDATE", solver);
            execute(connection, "SET lock_timeout='200ms'");
            assertEquals("55P03", assertThrows(SQLException.class, this::recordFailure).getSQLState());
            assertEquals("STAGED", state());
            assertNotNull(SolverBuildArtifacts.findOutputManifest(connection, build.artifactId()).orElseThrow().payload());
            assertEquals(0, number(connection, "SELECT build_status FROM starexec.solvers WHERE id=?", solver));
            blocker.rollback();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"podman", "kubernetes"})
    void failedBuildsConserveOnlyLogsAndKeepTheOriginalSource(String backend) throws Exception {
        failedStop(backend);
        assertTrue(recordFailure());
        assertEquals("REJECTED", state());
        assertEquals(3, number(connection, "SELECT build_status FROM starexec.solvers WHERE id=?", solver));
        assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
        assertEquals("build log \u00e9\n", Files.readString(root().resolve("logs/starexec_build_log")));
        assertFalse(Files.exists(root().resolve("solver")));
        assertNotEquals(Files.getAttribute(build.staging().resolve("logs/starexec_build_log"), "unix:ino"),
                Files.getAttribute(root().resolve("logs/starexec_build_log"), "unix:ino"));
        assertEquals(PosixFilePermissions.fromString("r--r--r--"), Files.getPosixFilePermissions(root().resolve("logs/starexec_build_log")));
        assertNull(text(connection, "SELECT published_at::text FROM starexec.solver_build_artifacts WHERE id=?", build.artifactId()));
        assertFalse(SolverBuildArtifacts.publishOutput(connection, build.artifactId()));
        unchangedSource();
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "absent"})
    void positivelyMissingOrEmptyLogsRemainEmptyWithoutInventedContent(String condition) throws Exception {
        failedStop("podman");
        Files.delete(build.staging().resolve("logs/starexec_build_log"));
        if (condition.equals("absent")) Files.delete(build.staging().resolve("logs"));
        assertTrue(recordFailure());
        assertEquals(0, number(connection, "SELECT byte_size FROM starexec.solver_build_artifacts WHERE id=?", build.artifactId()));
        assertEquals(condition.equals("empty"), Files.exists(root().resolve("logs")));
        try (var entries = Files.walk(root())) { assertTrue(entries.noneMatch(Files::isRegularFile)); }
        assertTrue(recordFailure());
        assertFalse(Files.exists(root().resolve("solver")));
    }

    @Test void unavailableStagingCannotBeMisreportedAsAbsentLogs() throws Exception {
        failedStop("podman");
        Files.move(build.staging(), files.resolve("unavailable-staging"));
        assertThrows(IOException.class, this::recordFailure);
        assertEquals("STAGED", state()); assertFalse(Files.exists(root()));
        assertEquals(0, number(connection, "SELECT build_status FROM starexec.solvers WHERE id=?", solver));
    }

    @Test void failedPartialSolverBytesAreNeverCopiedOrPublished() throws Exception {
        failedStop("podman");
        Files.createSymbolicLink(build.staging().resolve("solver/unsafe"), source);
        assertTrue(recordFailure());
        assertFalse(Files.exists(root().resolve("solver")));
        assertTrue(Files.isSymbolicLink(build.staging().resolve("solver/unsafe")));
        unchangedSource();
    }

    @ParameterizedTest @ValueSource(strings = {"file", "link", "escape", "hardlink"})
    void unsafeLogTreesCannotDecideBuildFailure(String condition) throws Exception {
        failedStop("podman");
        Path logs = build.staging().resolve("logs");
        if (condition.equals("file") || condition.equals("link")) {
            Files.delete(logs.resolve("starexec_build_log")); Files.delete(logs);
            if (condition.equals("file")) Files.writeString(logs, "not a directory");
            else Files.createSymbolicLink(logs, source);
        } else if (condition.equals("escape")) Files.createSymbolicLink(logs.resolve("escape"), source.resolve("starexec_build"));
        else Files.createLink(logs.resolve("hardlink"), source.resolve("starexec_build"));
        assertThrows(IOException.class, this::recordFailure);
        assertEquals("STAGED", state());
        assertEquals(0, number(connection, "SELECT build_status FROM starexec.solvers WHERE id=?", solver));
        assertTrue(SolverBuildArtifacts.findOutputManifest(connection, build.artifactId()).isEmpty());
        assertEquals("original source", Files.readString(source.resolve("starexec_build")));
    }

    @ParameterizedTest @ValueSource(ints = {1, 4, 7, 19, 20, 21, 22})
    void unfinishedSuccessfulOrCancelledPairsCannotBeRecordedAsFailure(int status) throws Exception {
        stopped("podman", null);
        execute(connection, "UPDATE starexec.job_pairs SET status_code=? WHERE id=?", status, pair);
        assertFalse(recordFailure()); assertFalse(Files.exists(root())); assertEquals("PLANNED", state());
        assertEquals(0, number(connection, "SELECT build_status FROM starexec.solvers WHERE id=?", solver));
    }

    @ParameterizedTest @ValueSource(strings = {"cancelled", "never_started", "unknown"})
    void interventionReceiptsDoNotAuthorizeScientificFailure(String disposition) throws Exception {
        stopped("kubernetes", disposition);
        execute(connection, "UPDATE starexec.job_pairs SET status_code=11 WHERE id=?", pair);
        assertFalse(recordFailure()); assertFalse(Files.exists(root())); assertEquals("PLANNED", state());
    }

    @Test void failedStatusWithoutStopEvidenceCannotCreateOutput() throws Exception {
        execute(connection, "UPDATE starexec.job_pairs SET status_code=11 WHERE id=?", pair);
        assertFalse(recordFailure()); assertFalse(Files.exists(root())); assertEquals("PLANNED", state());
    }

    @Test void aLocalReceiptCannotDecideAnExecutionBoundBuildFailure() throws Exception {
        failedStop("local"); assertFalse(recordFailure()); assertFalse(Files.exists(root()));
    }

    @Test void failureMetadataCanRetryWithoutReadingMutableExecutionLogs() throws Exception {
        failedStop("podman"); freezeFailureBeforeMetadata();
        String manifest = frozenManifest();
        Files.delete(build.staging().resolve("logs/starexec_build_log")); Files.delete(build.staging().resolve("logs"));
        assertTrue(recordFailure()); assertTrue(recordFailure());
        assertEquals(manifest, frozenManifest());
        assertEquals("build log \u00e9\n", Files.readString(root().resolve("logs/starexec_build_log")));
        assertEquals("REJECTED", state());
    }

    @Test void frozenFailureLogsCannotBecomeValidatedSolverOutput() throws Exception {
        failedStop("podman"); freezeFailureBeforeMetadata();
        String manifest = frozenManifest();
        var receipt = SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow();
        var stored = SolverBuildArtifacts.findOutputManifest(connection, build.artifactId()).orElseThrow();
        assertFalse(SolverBuildArtifacts.validateOutput(connection, build, engine("podman"), receipt.receipt().sha256(), root(), stored));
        execute(connection, "UPDATE starexec.job_pairs SET status_code=7 WHERE id=?", pair);
        assertFalse(publish()); assertFalse(SolverBuildArtifacts.publishOutput(connection, build.artifactId()));
        assertEquals("STAGED", state()); assertEquals(manifest, frozenManifest());
        assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
    }

    @Test void corruptionOfRejectedLogsCannotBeHiddenByAnIdempotentRetry() throws Exception {
        failedStop("podman"); assertTrue(recordFailure());
        String manifest = frozenManifest(); Path log = root().resolve("logs/starexec_build_log");
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(log, "corrupted");
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("r--r--r--"));
        assertThrows(IOException.class, this::recordFailure);
        assertEquals(manifest, frozenManifest()); assertEquals("REJECTED", state());
    }

    @ParameterizedTest @ValueSource(strings = {"generation", "owner", "job-owner", "job-deleted", "job-killed",
            "deleted", "recycled", "newer-job", "attempt", "primary-solver"})
    void failureMetadataRefusesRevokedOwnershipOrExecution(String condition) throws Exception {
        failedStop("podman"); freezeFailureBeforeMetadata(); String manifest = frozenManifest();
        if (condition.equals("owner") || condition.equals("job-owner")) {
            otherUser = (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                    + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Other','Owner','synthetic',now(),'synthetic',1000000,100,0) RETURNING id",
                    "owner-" + UUID.randomUUID() + "@example.invalid");
            if (condition.equals("owner")) execute(connection, "UPDATE starexec.solvers SET user_id=? WHERE id=?", otherUser, solver);
            else execute(connection, "UPDATE starexec.jobs SET user_id=? WHERE id=(SELECT job_id FROM starexec.job_pairs WHERE id=?)", otherUser, pair);
        } else if (condition.equals("generation")) execute(connection, "UPDATE starexec.solvers SET build_generation=build_generation+1 WHERE id=?", solver);
        else if (condition.startsWith("job-")) execute(connection, "UPDATE starexec.jobs SET "
                + (condition.equals("job-deleted") ? "deleted" : "killed") + "=true WHERE id=(SELECT job_id FROM starexec.job_pairs WHERE id=?)", pair);
        else if (condition.equals("deleted") || condition.equals("recycled")) execute(connection,
                "UPDATE starexec.solvers SET " + condition + "=true WHERE id=?", solver);
        else if (condition.equals("attempt")) execute(connection, "UPDATE starexec.job_pair_attempts SET current_attempt_no=current_attempt_no+1 WHERE pair_id=?", pair);
        else if (condition.equals("primary-solver")) execute(connection, "UPDATE starexec.job_pairs SET primary_jobpair_data=2 WHERE id=?", pair);
        else {
            long generation = number(connection, "SELECT build_generation FROM starexec.solvers WHERE id=?", solver);
            long job = number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'newer',1,0,true) RETURNING id", user);
            long newerPair = number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id", job);
            execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", newerPair, solver);
            execute(connection, "UPDATE starexec.solvers SET build_generation=? WHERE id=?", generation, solver);
        }
        assertFalse(recordFailureMetadata()); assertEquals("STAGED", state());
        assertEquals(0, number(connection, "SELECT build_status FROM starexec.solvers WHERE id=?", solver));
        assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
        assertEquals(manifest, frozenManifest()); unchangedSource();
    }

    @ParameterizedTest @ValueSource(strings = {"pair", "solver"})
    void failureMetadataRefusesMissingStatuses(String condition) throws Exception {
        failedStop("podman"); freezeFailureBeforeMetadata();
        if (condition.equals("pair")) execute(connection, "UPDATE starexec.job_pairs SET status_code=NULL WHERE id=?", pair);
        else execute(connection, "UPDATE starexec.solvers SET build_status=NULL WHERE id=?", solver);
        assertFalse(recordFailureMetadata()); assertEquals("STAGED", state());
        assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
    }

    @Test void wrongFailureReceiptOrTokenCannotRecordTheOutcome() throws Exception {
        failedStop("podman"); freezeFailureBeforeMetadata();
        var receipt = SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow();
        var other = new BuildExecutionContext(build.artifactId(), pair, 1, UUID.randomUUID(), source, build.staging());
        assertFalse(SolverBuildArtifacts.recordFailedOutput(connection, other, engine("podman"), receipt.receipt().sha256()));
        assertFalse(SolverBuildArtifacts.recordFailedOutput(connection, build, engine("kubernetes"), receipt.receipt().sha256()));
        assertFalse(SolverBuildArtifacts.recordFailedOutput(connection, build, engine("podman"), "0".repeat(64)));
        assertEquals("STAGED", state());
    }

    @Test void aConcurrentNewBuildCannotBeMarkedFailedByOlderOutput() throws Exception {
        failedStop("podman"); freezeFailureBeforeMetadata();
        var receipt = SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow();
        var executor = Executors.newSingleThreadExecutor();
        try (Connection newer = connect(); Connection recording = connect()) {
            newer.setAutoCommit(false); recording.setAutoCommit(false);
            long pid = number(recording, "SELECT pg_backend_pid()");
            long job = number(newer, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'concurrent',1,0,true) RETURNING id", user);
            long newerPair = number(newer, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id", job);
            execute(newer, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", newerPair, solver);
            var result = executor.submit(() -> SolverBuildArtifacts.recordFailedOutput(recording, build, engine("podman"), receipt.receipt().sha256()));
            await().atMost(5, TimeUnit.SECONDS).until(() -> number(connection,
                    "SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock'", pid) == 1);
            newer.commit();
            assertFalse(result.get(5, TimeUnit.SECONDS)); recording.rollback();
            assertEquals(0, number(connection, "SELECT build_status FROM starexec.solvers WHERE id=?", solver));
            assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
            assertEquals("STAGED", state());
        } finally {
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void failureCopyBudgetErrorsRemainRegisteredForCleanup() throws Exception {
        failedStop("podman");
        assertThrows(IOException.class, () -> SolverBuildPublication.recordFailure(connection, build, parent, 0, 32));
        assertEquals("STAGED", state()); assertTrue(Files.isDirectory(root()));
        assertTrue(SolverBuildArtifacts.findOutputManifest(connection, build.artifactId()).isEmpty());
        assertThrows(IOException.class, this::recordFailure);
        unchangedSource();
    }

    @ParameterizedTest @ValueSource(strings = {"podman", "kubernetes"})
    void publishesExactImmutableSolverAndLogsForTheStoppedExecution(String backend) throws Exception {
        stopped(backend, null);
        assertTrue(publish());
        try (Connection observer = connect()) {
            assertEquals("PUBLISHED", text(observer, "SELECT state FROM starexec.solver_build_artifacts WHERE id=?", build.artifactId()));
            assertEquals(root().resolve("solver").toString(), text(observer, "SELECT path FROM starexec.solvers WHERE id=?", solver));
            assertEquals(source.toString(), text(observer, "SELECT build_source_path FROM starexec.solvers WHERE id=?", solver));
        }
        assertEquals("compiled binary α\n", Files.readString(root().resolve("solver/program")));
        assertEquals("build log é\n", Files.readString(root().resolve("logs/starexec_build_log")));
        assertNotEquals(Files.getAttribute(build.staging().resolve("solver/program"), "unix:ino"),
                Files.getAttribute(root().resolve("solver/program"), "unix:ino"));
        assertEquals(PosixFilePermissions.fromString("r-xr-xr-x"), Files.getPosixFilePermissions(root().resolve("solver/program")));
        unchangedSource();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sourceArchiveUsesTheOriginalSourceBeforeAndAfterPublication(boolean published) throws Exception {
        Common.initialize();
        if (published) { stopped("podman", null); assertTrue(publish()); }
        Solver snapshot = sourceSnapshot(text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(archiveSource(snapshot)))) {
            for (var entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                if (!entry.isDirectory()) entries.put(entry.getName(), new String(archive.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertEquals(Map.of("publication/starexec_build", "original source"), entries);
        unchangedSource();
    }

    private Solver sourceSnapshot(String path) {
        Solver snapshot = new Solver(); snapshot.setId(solver); snapshot.setUserId(user); snapshot.setName("publication");
        snapshot.setPath(path); return snapshot;
    }

    private byte[] archiveSource(Solver snapshot) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ServletOutputStream stream = new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
            @Override public void write(int value) { bytes.write(value); }
        };
        when(response.getOutputStream()).thenReturn(stream);
        var method = Download.class.getDeclaredMethod("handleSolverSource", Solver.class, HttpServletResponse.class);
        method.setAccessible(true);
        try { assertEquals(true, method.invoke(null, snapshot, response)); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
        return bytes.toByteArray();
    }

    private byte[] archiveBinary(Solver snapshot, boolean reupload) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ServletOutputStream stream = new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
            @Override public void write(int value) { bytes.write(value); }
        };
        when(response.getOutputStream()).thenReturn(stream);
        var method = Download.class.getDeclaredMethod("handleSolver", Solver.class, HttpServletResponse.class, boolean.class);
        method.setAccessible(true);
        try { assertEquals(true, method.invoke(null, snapshot, response, reupload)); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
        return bytes.toByteArray();
    }

    private Map<String, String> binaryEntries(Solver snapshot, boolean reupload) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(archiveBinary(snapshot, reupload)))) {
            for (var entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                if (!entry.isDirectory()) entries.put(entry.getName(), new String(archive.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @ParameterizedTest @ValueSource(strings = {"file", "directory"})
    void publicationRefusesExecutableLinksIntoPrivateLogs(String kind) throws Exception {
        stopped("podman", null);
        Files.createSymbolicLink(build.staging().resolve("solver/z_log_link"),
                Path.of(kind.equals("file") ? "../logs/starexec_build_log" : "../logs"));
        assertThrows(IOException.class, this::publish);
        assertEquals("STAGED", state());
        assertEquals(0, number(connection, "SELECT build_status FROM starexec.solvers WHERE id=?", solver));
        assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
        unchangedSource();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void binaryDownloadsRefusePrivateLogLinksInPreviouslyPublishedTrees(boolean reupload) throws Exception {
        stopped("podman", null); assertTrue(publish());
        Path programs = root().resolve("solver");
        Files.setPosixFilePermissions(programs, PosixFilePermissions.fromString("rwx------"));
        Files.createSymbolicLink(programs.resolve("z_log_link"), Path.of("../logs/starexec_build_log"));
        Files.setPosixFilePermissions(programs, PosixFilePermissions.fromString("r-xr-xr-x"));
        Map<String, String> contents;
        try { contents = binaryEntries(sourceSnapshot(programs.toString()), reupload); }
        catch (IOException refused) { unchangedSource(); return; }
        fail("Binary ZIP must refuse private log links; private log bytes present: " + contents.containsValue("build log \u00e9\n"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void validBinaryDownloadsPreserveNormalAndReuploadLayouts(boolean reupload) throws Exception {
        stopped("podman", null); assertTrue(publish());
        var contents = binaryEntries(sourceSnapshot(root().resolve("solver").toString()), reupload);
        assertEquals(Map.of(reupload ? "program" : "publication/program", "compiled binary \u03b1\n"), contents);
        unchangedSource();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void confinedLibraryLinksRemainValidInBothBinaryLayouts(boolean reupload) throws Exception {
        stopped("podman", null);
        Path programs = build.staging().resolve("solver");
        Files.writeString(Files.createDirectory(programs.resolve("lib")).resolve("library"), "LIBRARY");
        Files.createSymbolicLink(programs.resolve("lib64"), Path.of("lib"));
        assertTrue(publish());
        String prefix = reupload ? "" : "publication/";
        assertEquals(Map.of(prefix + "program", "compiled binary \u03b1\n", prefix + "lib/library", "LIBRARY",
                prefix + "lib64", "lib"), binaryEntries(sourceSnapshot(root().resolve("solver").toString()), reupload));
        unchangedSource();
    }

    @Test void validatedLegacyOutputIsRecheckedAgainstTheExecutableBoundary() throws Exception {
        stopped("podman", null);
        Files.createSymbolicLink(build.staging().resolve("solver/z_log_link"), Path.of("../logs/starexec_build_log"));
        var receipt = SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow().receipt();
        assertTrue(SolverBuildArtifacts.reserveOutput(connection, build, engine("podman"), receipt.sha256(), root()));
        var snapshot = SolverArtifactTree.copy(build.staging(), root(), 4096, 32);
        assertTrue(SolverBuildArtifacts.validateOutput(connection, build, engine("podman"), receipt.sha256(), root(),
                new SolverBuildArtifacts.OutputManifest(snapshot.payload(), snapshot.sha256(), snapshot.byteSize())));
        String manifest = frozenManifest();
        assertThrows(IOException.class, this::publish);
        assertEquals("VALIDATED", state()); assertEquals(manifest, frozenManifest());
        assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
        unchangedSource();
    }

    @Test void sourceArchivesNeverFollowLinksOutsideTheSourceTree() throws Exception {
        Common.initialize();
        String legacyPath = source.toString().substring(0, source.toString().length() - 4);
        execute(connection, "UPDATE starexec.solvers SET path=?,build_status=2 WHERE id=?", legacyPath, solver);
        Path outside = Files.writeString(files.resolve("outside-source"), "must not be archived");
        Files.createSymbolicLink(source.resolve("escape"), outside);
        assertThrows(IOException.class, () -> archiveSource(sourceSnapshot(legacyPath)));
        assertEquals("must not be archived", Files.readString(outside));
    }

    @Test void legacyBuiltSourceRemainsAvailableWithoutCataloguePublication() throws Exception {
        Common.initialize();
        String legacy = source.toString().substring(0, source.toString().length() - 4);
        execute(connection, "UPDATE starexec.solvers SET path=?,build_status=2 WHERE id=?", legacy, solver);
        assertEquals(source.toFile(), Solvers.findSolverSource(sourceSnapshot(legacy)).orElseThrow());
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(archiveSource(sourceSnapshot(legacy))))) {
            assertEquals("publication/starexec_build", archive.getNextEntry().getName());
            assertEquals("original source", new String(archive.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "path", "deleted"})
    void sourceResolutionRefusesChangedAssetSnapshots(String changed) throws Exception {
        Common.initialize(); Solver snapshot = sourceSnapshot(source.toString());
        if (changed.equals("owner")) snapshot.setUserId(user + 1);
        else if (changed.equals("path")) snapshot.setPath(files.resolve("different-source_src").toString());
        else execute(connection, "UPDATE starexec.solvers SET deleted=true WHERE id=?", solver);
        assertTrue(Solvers.findSolverSource(snapshot).isEmpty());
        assertThrows(IOException.class, () -> archiveSource(snapshot)); unchangedSource();
    }

    @Test void publishedCatalogueRecoversTheOriginalSourceWithoutASeparateHint() throws Exception {
        Common.initialize(); stopped("podman", null); assertTrue(publish());
        execute(connection, "UPDATE starexec.solvers SET build_source_path=NULL WHERE id=?", solver);
        assertEquals(source.toFile(), Solvers.findSolverSource(sourceSnapshot(root().resolve("solver").toString())).orElseThrow());
    }

    @Test void inconsistentPublishedSourceHintsAreNeverGuessed() throws Exception {
        Common.initialize(); stopped("podman", null); assertTrue(publish());
        execute(connection, "UPDATE starexec.solvers SET build_source_path=? WHERE id=?", files.resolve("other-source_src").toString(), solver);
        Solver snapshot = sourceSnapshot(root().resolve("solver").toString());
        assertThrows(SQLException.class, () -> Solvers.findSolverSource(snapshot));
        assertThrows(SQLException.class, () -> archiveSource(snapshot)); unchangedSource();
    }

    @ParameterizedTest @ValueSource(strings = {"", "/", "relative", "/solver/../source"})
    void invalidSourceMetadataCannotBecomeAnArchiveRoot(String path) throws Exception {
        Common.initialize();
        execute(connection, "UPDATE starexec.solvers SET build_source_path=? WHERE id=?", path, solver);
        assertThrows(SQLException.class, () -> Solvers.findSolverSource(sourceSnapshot(source.toString())));
        unchangedSource();
    }

    @Test void aVerifiedPublicationRetryPreservesExactManifestAndTimestamps() throws Exception {
        stopped("podman", null); assertTrue(publish());
        String recorded = text(connection, "SELECT row(manifest_payload,manifest_sha256,validated_at,published_at)::text"
                + " FROM starexec.solver_build_artifacts WHERE id=?", build.artifactId());
        assertTrue(publish());
        assertEquals(recorded, text(connection, "SELECT row(manifest_payload,manifest_sha256,validated_at,published_at)::text"
                + " FROM starexec.solver_build_artifacts WHERE id=?", build.artifactId()));
    }

    @Test void aMetadataTimeoutCanRetryAlreadyValidatedOutputWithoutRecopying() throws Exception {
        stopped("podman", null);
        try (Connection blocker = connect()) {
            blocker.setAutoCommit(false);
            number(blocker, "SELECT id FROM starexec.solvers WHERE id=? FOR UPDATE", solver);
            execute(connection, "SET lock_timeout='200ms'");
            assertThrows(SQLException.class, this::publish);
            assertEquals("VALIDATED", state());
            String validated = text(connection, "SELECT validated_at::text FROM starexec.solver_build_artifacts WHERE id=?", build.artifactId());
            assertNull(text(connection, "SELECT published_at::text FROM starexec.solver_build_artifacts WHERE id=?", build.artifactId()));
            blocker.rollback();
            Files.delete(build.staging().resolve("solver/program"));
            Files.delete(build.staging().resolve("logs/starexec_build_log"));
            assertTrue(publish(), "retry must use only the conserved immutable output");
            assertEquals(validated, text(connection, "SELECT validated_at::text FROM starexec.solver_build_artifacts WHERE id=?", build.artifactId()));
            assertEquals("compiled binary α\n", Files.readString(root().resolve("solver/program")));
        }
    }

    @Test void aTerminalStatusWithoutAConservedStopCannotCreateOutput() throws Exception {
        assertFalse(publish()); assertEquals("PLANNED", state()); assertFalse(Files.exists(root())); unchangedSource();
    }

    @ParameterizedTest @ValueSource(strings = {"cancelled", "never_started", "unknown"})
    void nonCompletionOrUnknownReceiptsCannotAuthorizePublication(String disposition) throws Exception {
        stopped("kubernetes", disposition);
        assertFalse(publish()); assertEquals("PLANNED", state()); assertFalse(Files.exists(root()));
    }

    @Test void aLocalReceiptCannotSubstituteForACompleteProcessScope() throws Exception {
        stopped("local", null); assertFalse(publish()); assertFalse(Files.exists(root()));
    }

    @Test void nonterminalScientificStatusCannotFreezeOrPublishOutput() throws Exception {
        stopped("podman", null); execute(connection, "UPDATE starexec.job_pairs SET status_code=4 WHERE id=?", pair);
        assertFalse(publish()); assertEquals("PLANNED", state()); assertFalse(Files.exists(root()));
    }

    @Test void missingOptionalLogsDoNotTurnSuccessfulBuildsIntoFailures() throws Exception {
        stopped("podman", null);
        Files.delete(build.staging().resolve("logs/starexec_build_log")); Files.delete(build.staging().resolve("logs"));
        assertTrue(publish()); assertFalse(Files.exists(root().resolve("logs")));
        assertEquals("compiled binary α\n", Files.readString(root().resolve("solver/program")));
    }

    @Test void aCopyFailureRetainsItsCommittedCleanupReservation() throws Exception {
        stopped("podman", null);
        Files.createSymbolicLink(build.staging().resolve("escape"), source.resolve("starexec_build"));
        assertThrows(IOException.class, this::publish);
        try (Connection observer = connect()) {
            assertEquals(root().toString(), SolverBuildArtifacts.find(observer, pair, 1).orElseThrow().storageRoot());
            assertEquals("STAGED", SolverBuildArtifacts.find(observer, pair, 1).orElseThrow().state());
        }
        assertTrue(Files.isDirectory(root()));
        String keep = "owned recovery content"; Files.writeString(root().resolve("keep"), keep);
        assertThrows(IOException.class, this::publish);
        assertEquals(keep, Files.readString(root().resolve("keep"))); unchangedSource();
    }

    @Test void anExistingUnvalidatedDestinationIsNeverReusedOrDeleted() throws Exception {
        stopped("podman", null); Files.createDirectory(root()); Files.writeString(root().resolve("keep"), "untouched");
        assertThrows(IOException.class, this::publish);
        assertEquals("untouched", Files.readString(root().resolve("keep"))); assertEquals("STAGED", state());
    }

    @Test void validatedByteCorruptionCannotBeHiddenByAnIdempotentRetry() throws Exception {
        stopped("podman", null); assertTrue(publish());
        Path program = root().resolve("solver/program");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(program, "CORRUPTED");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("r-xr-xr-x"));
        assertThrows(IOException.class, this::publish); assertEquals("PUBLISHED", state());
        assertEquals("CORRUPTED", Files.readString(program));
    }

    @Test void aRevokedGenerationCannotChangeTheSolverPath() throws Exception {
        stopped("podman", null);
        execute(connection, "UPDATE starexec.solvers SET build_generation=build_generation+1 WHERE id=?", solver);
        assertFalse(publish()); assertEquals("VALIDATED", state());
        assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
        unchangedSource();
    }

    @Test void unknownBuildStatusCannotPublishEvenWithAConservedStop() throws Exception {
        stopped("podman", null);
        execute(connection, "UPDATE starexec.solvers SET build_status=NULL WHERE id=?", solver);
        assertFalse(publish()); assertEquals("VALIDATED", state());
        assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
        unchangedSource();
    }

    @Test void aDifferentTokenCannotReserveOrPublishThisAttempt() throws Exception {
        stopped("podman", null);
        var other = new BuildExecutionContext(build.artifactId(), pair, 1, UUID.randomUUID(), source, build.staging());
        assertFalse(SolverBuildPublication.publish(connection, other, parent, 4096, 32));
        assertEquals("PLANNED", state()); assertFalse(Files.exists(root()));
    }

    @Test void aPublicDestinationParentIsRefusedBeforeReservation() throws Exception {
        stopped("podman", null); Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxrwx---"));
        assertThrows(IOException.class, this::publish); assertEquals("PLANNED", state()); assertFalse(Files.exists(root()));
    }

    @Test void theConnectionMustCommitReservationsBeforeFilesystemWork() throws Exception {
        stopped("podman", null); connection.setAutoCommit(false);
        assertThrows(SQLException.class, this::publish); assertFalse(Files.exists(root()));
    }

    @Test void aByteLimitFailureRetainsOwnershipWithoutPublishing() throws Exception {
        stopped("podman", null);
        assertThrows(IOException.class, () -> SolverBuildPublication.publish(connection, build, parent, 1, 32));
        assertEquals("STAGED", state()); assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
    }

    @Test void aMissingSolverDirectoryCannotBeReplacedByLogs() throws Exception {
        stopped("podman", null); Files.delete(build.staging().resolve("solver/program")); Files.delete(build.staging().resolve("solver"));
        assertThrows(IOException.class, this::publish); assertEquals("PLANNED", state()); assertFalse(Files.exists(root()));
    }

    @Test void anEmptySolverDirectoryCannotPublishOnlyItsLogFiles() throws Exception {
        stopped("podman", null); Files.delete(build.staging().resolve("solver/program"));
        assertThrows(IOException.class, this::publish); assertEquals("STAGED", state());
        assertEquals("build log é\n", Files.readString(root().resolve("logs/starexec_build_log")));
        assertEquals(source.toString(), text(connection, "SELECT path FROM starexec.solvers WHERE id=?", solver));
    }

    @Test void aLogFileCannotReplaceTheSeparateLogDirectory() throws Exception {
        stopped("podman", null);
        Files.delete(build.staging().resolve("logs/starexec_build_log")); Files.delete(build.staging().resolve("logs"));
        Files.writeString(build.staging().resolve("logs"), "not a log directory");
        assertThrows(IOException.class, this::publish); assertEquals("PLANNED", state()); assertFalse(Files.exists(root()));
    }

    @Test void anEntryLimitFailureRetainsItsCleanupRecord() throws Exception {
        stopped("podman", null);
        assertThrows(IOException.class, () -> SolverBuildPublication.publish(connection, build, parent, 4096, 1));
        assertEquals("STAGED", state());
        assertEquals(root().toString(), SolverBuildArtifacts.find(connection, pair, 1).orElseThrow().storageRoot());
    }
}
