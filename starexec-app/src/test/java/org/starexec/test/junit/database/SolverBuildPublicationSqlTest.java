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
import org.starexec.data.to.SolverBuildStopReceipt;
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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Actual catalogue and filesystem coordination; provider observations are synthetic fixtures. */
class SolverBuildPublicationSqlTest {
    @TempDir Path files;
    private Connection connection;
    private int user;
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
