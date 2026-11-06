package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.constants.R;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;
import com.google.gson.JsonObject;
import java.time.Instant;
import org.starexec.jobs.SolverArtifactTree;
import org.starexec.jobs.SolverBuildStaging;
import org.starexec.test.util.DatabaseTestSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Publication ownership and recovery against a configured disposable PostgreSQL database. */
public class SolverArtifactPublicationSqlTest {
    @TempDir Path files;
    private Connection con;
    private int user;
    private int solver;
    private int pair;
    private long artifact;

    @BeforeAll
    static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("SolverArtifactPublicationSqlTest");
    }

    private static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(R.POSTGRES_URL, R.POSTGRES_USERNAME, R.POSTGRES_PASSWORD);
        try (Statement s = c.createStatement()) {
            s.execute("SET search_path=starexec,public");
            s.execute("SET statement_timeout='10s'");
        }
        return c;
    }

    @BeforeEach
    void seed() throws Exception {
        con = connect();
        con.setAutoCommit(false);
        user = (int) number(con, "INSERT INTO users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES ('artifact-" + UUID.randomUUID()
                + "@example.invalid','Test','Artifact','test',now(),'synthetic',1000000,100,0) RETURNING id");
        solver = (int) number(con, "INSERT INTO solvers(user_id,name,uploaded,path,disk_size,build_status) VALUES ("
                + user + ",'artifact-solver',now(),'/solver/source_src',20,0) RETURNING id");
        pair = build(con, solver, user);
        artifact = number(con, "SELECT id FROM solver_build_artifacts WHERE pair_id=" + pair);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (con != null) {
            try {
                if (!con.getAutoCommit()) con.rollback();
                con.setAutoCommit(true);
                execute(con, "DELETE FROM users WHERE id=" + user);
            } finally {
                con.close();
            }
        }
        Files.walkFileTree(files, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path seedFilesystemBuild() throws Exception {
        Path source = Files.createDirectory(files.resolve("source_src"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(source.resolve("starexec_build"), "source remains unchanged");
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'filesystem-build',now(),?,20,0) RETURNING id")) {
            ps.setInt(1, user); ps.setString(2, source.toString());
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); solver = rs.getInt(1); }
        }
        pair = build(con, solver, user);
        artifact = SolverBuildArtifacts.find(con, pair, 1).orElseThrow().id();
        con.commit(); con.setAutoCommit(true);
        return Files.createDirectory(files.resolve("staged"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @Test void stagingRegistrationCommitsBeforeTheDirectoryCanBeDispatched() throws Exception {
        Path parent = seedFilesystemBuild();
        var context = SolverBuildStaging.prepare(con, pair, 1, parent);
        try (Connection observer = connect()) {
            var observed = SolverBuildArtifacts.find(observer, pair, 1).orElseThrow();
            assertEquals(context.token(), observed.executionToken());
            assertEquals(context.staging().toString(), observed.stagingPath());
        }
        assertTrue(Files.isDirectory(context.staging()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(context.staging()));
        assertEquals("source remains unchanged", Files.readString(context.source().resolve("starexec_build")));
    }

    @Test void aProvenNoStartRetryReusesItsOwnDirectoryWithoutRemovingFiles() throws Exception {
        Path parent = seedFilesystemBuild();
        var context = SolverBuildStaging.prepare(con, pair, 1, parent);
        Files.writeString(context.staging().resolve("keep"), "preserve retry data");
        assertEquals(context, SolverBuildStaging.prepare(con, pair, 1, parent));
        assertEquals("preserve retry data", Files.readString(context.staging().resolve("keep")));
        try (var paths = Files.list(parent)) { assertEquals(1, paths.count()); }
    }

    @Test void aBoundExecutionCannotAcquireAnotherStagingSubmission() throws Exception {
        Path parent = seedFilesystemBuild();
        var context = SolverBuildStaging.prepare(con, pair, 1, parent);
        assertTrue(SolverBuildArtifacts.bindExecution(con, pair, 1, context.token(), "podman:owned-container"));
        assertThrows(IOException.class, () -> SolverBuildStaging.prepare(con, pair, 1, parent));
        try (var paths = Files.list(parent)) { assertEquals(1, paths.count()); }
    }

    @Test void stagingRetryRefusesASymbolicLinkWithoutModifyingItsTarget() throws Exception {
        Path parent = seedFilesystemBuild();
        var context = SolverBuildStaging.prepare(con, pair, 1, parent);
        Path target = Files.createDirectory(files.resolve("other"));
        Files.writeString(target.resolve("keep"), "unrelated");
        Files.delete(context.staging());
        Files.createSymbolicLink(context.staging(), target);
        assertThrows(IOException.class, () -> SolverBuildStaging.prepare(con, pair, 1, parent));
        assertEquals("unrelated", Files.readString(target.resolve("keep")));
        assertEquals(context.staging().toString(), SolverBuildArtifacts.find(con, pair, 1).orElseThrow().stagingPath());
    }

    @Test void stagingRefusesAnUncommittedRegistration() throws Exception {
        Path parent = seedFilesystemBuild();
        con.setAutoCommit(false);
        assertThrows(SQLException.class, () -> SolverBuildStaging.prepare(con, pair, 1, parent));
        assertNull(SolverBuildArtifacts.find(con, pair, 1).orElseThrow().stagingPath());
        try (var paths = Files.list(parent)) { assertEquals(0, paths.count()); }
    }

    @Test void stagingRefusesAnUnprotectedParentBeforeReservingAPath() throws Exception {
        Path parent = seedFilesystemBuild();
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxrwx---"));
        assertThrows(IOException.class, () -> SolverBuildStaging.prepare(con, pair, 1, parent));
        assertNull(SolverBuildArtifacts.find(con, pair, 1).orElseThrow().stagingPath());
        try (var paths = Files.list(parent)) { assertEquals(0, paths.count()); }
    }

    @Test void launchAdmissionCommitsItsMatchingEngineIdentity() throws Exception {
        Path parent = seedFilesystemBuild();
        var context = SolverBuildStaging.prepare(con, pair, 1, parent);
        con.setAutoCommit(false);
        assertTrue(SolverBuildArtifacts.admitExecution(con, context, "podman:owned-container"));
        try (Connection observer = connect()) {
            assertNull(SolverBuildArtifacts.find(observer, pair, 1).orElseThrow().engineIdentity());
        }
        con.commit();
        try (Connection observer = connect()) {
            assertEquals("podman:owned-container", SolverBuildArtifacts.find(observer, pair, 1).orElseThrow().engineIdentity());
        }
        assertTrue(SolverBuildArtifacts.admitExecution(con, context, "podman:owned-container"));
        assertFalse(SolverBuildArtifacts.admitExecution(con, context, "podman:different-container"));
    }

    @Test void launchAdmissionRefusesAnUnpreparedOrMismatchedIdentity() throws Exception {
        Path parent = seedFilesystemBuild();
        var context = SolverBuildStaging.prepare(con, pair, 1, parent);
        var wrong = new org.starexec.backend.BuildExecutionContext(context.artifactId(), pair, 1,
                UUID.randomUUID(), context.source(), context.staging());
        con.setAutoCommit(false);
        assertFalse(SolverBuildArtifacts.admitExecution(con, wrong, "podman:wrong"));
        assertNull(SolverBuildArtifacts.find(con, pair, 1).orElseThrow().engineIdentity());
    }

    @Test void launchAdmissionRefusesAStoppedExecution() throws Exception {
        Path parent = seedFilesystemBuild();
        var context = SolverBuildStaging.prepare(con, pair, 1, parent);
        assertTrue(SolverBuildArtifacts.bindExecution(con, pair, 1, context.token(), "podman:stopped"));
        assertTrue(SolverBuildArtifacts.recordStopped(con, pair, 1, context.token(), "podman:stopped"));
        con.setAutoCommit(false);
        assertFalse(SolverBuildArtifacts.admitExecution(con, context, "podman:stopped"));
    }

    @Test void lateCleanupBindingDoesNotAuthorizeLaunchingADeletedSolver() throws Exception {
        Path parent = seedFilesystemBuild();
        var context = SolverBuildStaging.prepare(con, pair, 1, parent);
        execute(con, "DELETE FROM solvers WHERE id=" + solver);
        assertTrue(SolverBuildArtifacts.bindExecution(con, pair, 1, context.token(), "podman:late"));
        con.setAutoCommit(false);
        assertFalse(SolverBuildArtifacts.admitExecution(con, context, "podman:late"));
        assertEquals("REJECTED", SolverBuildArtifacts.find(con, pair, 1).orElseThrow().state());
    }

    @Test void aRebuildRequestRevokesAnOlderPreparedLaunch() throws Exception {
        Path parent = seedFilesystemBuild();
        var context = SolverBuildStaging.prepare(con, pair, 1, parent);
        execute(con, "UPDATE solvers SET build_generation=build_generation+1 WHERE id=" + solver);
        con.setAutoCommit(false);
        assertFalse(SolverBuildArtifacts.admitExecution(con, context, "podman:older-build"));
        assertNull(SolverBuildArtifacts.find(con, pair, 1).orElseThrow().engineIdentity());
    }

    @Test void launchAdmissionRefusesAutocommitSoLocksSurviveUntilTheDecision() throws Exception {
        Path parent = seedFilesystemBuild();
        var context = SolverBuildStaging.prepare(con, pair, 1, parent);
        assertThrows(SQLException.class, () -> SolverBuildArtifacts.admitExecution(con, context, "podman:wrong"));
        assertNull(SolverBuildArtifacts.find(con, pair, 1).orElseThrow().engineIdentity());
    }

    private static int build(Connection c, int solver, int user) throws SQLException {
        long job = number(c, "INSERT INTO jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES ("
                + user + ",'artifact-build',1,0,true) RETURNING id");
        int pair = (int) number(c, "INSERT INTO job_pairs(job_id,status_code,primary_jobpair_data) VALUES ("
                + job + ",7,1) RETURNING id");
        execute(c, "INSERT INTO jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size)"
                + " VALUES (" + pair + ",1,7," + solver + ",0)");
        return pair;
    }

    private static String text(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    private static long number(Connection c, String sql) throws SQLException {
        return Long.parseLong(text(c, sql));
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }

    private static boolean publish(Connection c, long id) throws SQLException {
        return "t".equals(text(c, "SELECT publish_solver_build_artifact(" + id + ")"));
    }

    private static String path(long id) { return "/solver/artifacts/" + id; }

    private static void validate(Connection c, long id) throws Exception {
        execute(c, "UPDATE solver_build_artifacts SET state='STAGED',artifact_path='" + path(id)
                + "' WHERE id=" + id);
        String payload = "{ \"files\": [{\"path\":\"solver\",\"size\":5}], \"name\":\"é\" }\n";
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        try (PreparedStatement ps = c.prepareStatement("UPDATE solver_build_artifacts SET state='VALIDATED',"
                + "byte_size=5,manifest_payload=?,manifest_sha256=?,validated_at=now() WHERE id=?")) {
            ps.setString(1, payload); ps.setString(2, digest); ps.setLong(3, id); ps.executeUpdate();
        }
    }

    private String field(String field) throws SQLException {
        return text(con, "SELECT " + field + " FROM solver_build_artifacts WHERE id=" + artifact);
    }

    private void refused(String sql) throws Exception {
        Savepoint point = con.setSavepoint();
        assertThrows(SQLException.class, () -> execute(con, sql));
        con.rollback(point);
    }

    @Test
    void planningCapturesOwnerSourceAttemptAndGeneration() throws Exception {
        assertEquals("PLANNED", field("state"));
        assertEquals(Integer.toString(user), field("origin_owner_id"));
        assertEquals("/solver/source_src", field("source_path"));
        assertEquals("1", field("attempt_no"));
        assertEquals(text(con, "SELECT build_generation FROM solvers WHERE id=" + solver), field("build_generation"));
    }

    @Test
    void onlyAValidatedArtifactCanPublishAndRetryIsIdempotent() throws Exception {
        assertFalse(publish(con, artifact));
        validate(con, artifact);
        assertTrue(publish(con, artifact));
        assertEquals(path(artifact), text(con, "SELECT path FROM solvers WHERE id=" + solver));
        assertEquals("2", text(con, "SELECT build_status FROM solvers WHERE id=" + solver));
        assertEquals("/solver/source_src", text(con, "SELECT build_source_path FROM solvers WHERE id=" + solver));
        String published = field("published_at::text");
        assertTrue(publish(con, artifact));
        assertEquals(published, field("published_at::text"));
    }

    @Test
    void anOlderBuildCannotReplaceTheNewerArtifact() throws Exception {
        validate(con, artifact);
        int newerPair = build(con, solver, user);
        long newer = number(con, "SELECT id FROM solver_build_artifacts WHERE pair_id=" + newerPair);
        validate(con, newer);
        assertTrue(publish(con, newer));
        assertFalse(publish(con, artifact));
        assertEquals(path(newer), text(con, "SELECT path FROM solvers WHERE id=" + solver));
        assertEquals("VALIDATED", field("state"));
    }

    @Test
    void trustedTreeCopiesAndPublicationPreserveNewerBytesAfterLateOldCompletion() throws Exception {
        Path oldOutput = Files.createDirectory(files.resolve("old-output"));
        Path newOutput = Files.createDirectory(files.resolve("new-output"));
        Files.writeString(oldOutput.resolve("solver"), "OLDER");
        Files.writeString(newOutput.resolve("solver"), "NEWER");
        Path oldArtifact = files.resolve("old-artifact");
        Path newArtifact = files.resolve("new-artifact");
        reservePath(con, artifact, oldArtifact);
        con.commit();

        int newerPair = build(con, solver, user);
        long newer = number(con, "SELECT id FROM solver_build_artifacts WHERE pair_id=" + newerPair);
        reservePath(con, newer, newArtifact);
        con.commit();
        var newerSnapshot = SolverArtifactTree.copy(newOutput, newArtifact, 1000, 20);
        validateSnapshot(con, newer, newerSnapshot);
        assertTrue(publish(con, newer));
        con.commit();

        var olderSnapshot = SolverArtifactTree.copy(oldOutput, oldArtifact, 1000, 20);
        validateSnapshot(con, artifact, olderSnapshot);
        assertFalse(publish(con, artifact));
        con.commit();
        try (Connection observer = connect()) {
            assertEquals(newArtifact.toString(), text(observer, "SELECT path FROM solvers WHERE id=" + solver));
        }
        assertEquals("NEWER", Files.readString(newArtifact.resolve("solver")));
        SolverArtifactTree.verify(newArtifact, newerSnapshot);
        assertEquals("OLDER", Files.readString(oldArtifact.resolve("solver")));
    }

    private static void reservePath(Connection c, long artifact, Path destination) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE solver_build_artifacts SET state='STAGED',artifact_path=? WHERE id=?")) {
            ps.setString(1, destination.toString()); ps.setLong(2, artifact);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static void validateSnapshot(Connection c, long artifact, SolverArtifactTree.Snapshot snapshot) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE solver_build_artifacts SET state='VALIDATED',"
                + "byte_size=?,manifest_payload=?,manifest_sha256=?,validated_at=now() WHERE id=?")) {
            ps.setLong(1, snapshot.byteSize()); ps.setString(2, snapshot.payload());
            ps.setString(3, snapshot.sha256()); ps.setLong(4, artifact);
            assertEquals(1, ps.executeUpdate());
        }
    }

    @Test
    void anOldAttemptCannotPublishAfterARerun() throws Exception {
        validate(con, artifact);
        execute(con, "UPDATE job_pair_attempts SET current_attempt_no=2 WHERE pair_id=" + pair);
        assertFalse(publish(con, artifact));
        assertEquals(1, number(con, "SELECT count(*) FROM solver_build_artifacts WHERE origin_pair_id="
                + pair + " AND attempt_no=2 AND state='PLANNED'"));
    }

    @Test
    void rebuildingAPublishedSolverUsesItsOriginalSource() throws Exception {
        validate(con, artifact); assertTrue(publish(con, artifact));
        long generation = number(con, "SELECT build_generation FROM solvers WHERE id=" + solver);
        execute(con, "SELECT RebuildSolver(" + solver + ")");
        assertEquals("/solver/source_src", text(con, "SELECT path FROM solvers WHERE id=" + solver));
        assertTrue(number(con, "SELECT build_generation FROM solvers WHERE id=" + solver) > generation);
        assertFalse(publish(con, artifact));
    }

    @Test
    void aFailedRebuildFencesTheOldResultBeforeItsNewJobExists() throws Exception {
        validate(con, artifact);
        execute(con, "UPDATE solvers SET build_status=3 WHERE id=" + solver);
        execute(con, "SELECT RebuildSolver(" + solver + ")");
        assertFalse(publish(con, artifact));
        assertEquals("3", text(con, "SELECT build_status FROM solvers WHERE id=" + solver));
    }

    @Test
    void publishingARebuildRetiresThePreviousVersion() throws Exception {
        validate(con, artifact); assertTrue(publish(con, artifact));
        execute(con, "SELECT RebuildSolver(" + solver + ")");
        int next = build(con, solver, user);
        long replacement = number(con, "SELECT id FROM solver_build_artifacts WHERE pair_id=" + next);
        validate(con, replacement);
        assertTrue(publish(con, replacement));
        assertEquals("RETIRED", field("state"));
        assertEquals(path(replacement), text(con, "SELECT path FROM solvers WHERE id=" + solver));
        assertFalse(publish(con, artifact));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted=true", "recycled=true", "path='/other/source_src'", "build_status=1"})
    void solverChangesPreventPublication(String change) throws Exception {
        validate(con, artifact);
        execute(con, "UPDATE solvers SET " + change + " WHERE id=" + solver);
        assertFalse(publish(con, artifact));
        assertEquals("VALIDATED", field("state"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted=true", "killed=true", "buildjob=false"})
    void jobChangesPreventPublication(String change) throws Exception {
        validate(con, artifact);
        execute(con, "UPDATE jobs SET " + change + " WHERE id=(SELECT job_id FROM job_pairs WHERE id=" + pair + ")");
        assertFalse(publish(con, artifact));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 11, 22, 23})
    void onlySuccessfulTerminalPairsPublish(int status) throws Exception {
        validate(con, artifact);
        execute(con, "UPDATE job_pairs SET status_code=" + status + " WHERE id=" + pair);
        assertFalse(publish(con, artifact));
    }

    @Test
    void originCannotBeReassignedToAnotherOwner() throws Exception {
        refused("UPDATE solver_build_artifacts SET origin_owner_id=origin_owner_id+1 WHERE id=" + artifact);
    }

    private String prepare(UUID token) {
        return "SELECT prepare_solver_build_storage(" + pair + ",1,'" + token + "'::uuid,'/execution/" + token + "')";
    }

    @Test
    void executionPreparationIsIdempotentAndCannotBeReassigned() throws Exception {
        UUID token = UUID.randomUUID();
        assertEquals("t", text(con, prepare(token)));
        assertEquals("t", text(con, prepare(token)));
        assertEquals("f", text(con, prepare(UUID.randomUUID())));
        assertEquals(token.toString(), field("execution_token::text"));
    }

    @Test
    void anOlderAttemptCannotPrepareStorage() throws Exception {
        execute(con, "UPDATE job_pair_attempts SET current_attempt_no=2 WHERE pair_id=" + pair);
        assertEquals("f", text(con, prepare(UUID.randomUUID())));
    }

    @Test
    void engineBindingRequiresPreparedOwnershipAndRejectsReplacement() throws Exception {
        UUID token = UUID.randomUUID();
        String bind = "SELECT bind_solver_build_execution(" + pair + ",1,'" + token + "'::uuid,'engine-a')";
        assertEquals("f", text(con, bind));
        assertEquals("t", text(con, prepare(token)));
        assertEquals("t", text(con, bind));
        assertEquals("t", text(con, bind));
        assertEquals("f", text(con, bind.replace("engine-a", "engine-b")));
    }

    @Test
    void stopObservationMustMatchBothTokenAndEngineIdentity() throws Exception {
        UUID token = UUID.randomUUID();
        assertEquals("t", text(con, prepare(token)));
        String stop = "SELECT record_solver_build_execution_stop(" + pair + ",1,'" + token + "'::uuid,'engine-a')";
        assertEquals("f", text(con, stop));
        assertEquals("t", text(con, "SELECT bind_solver_build_execution(" + pair + ",1,'" + token + "'::uuid,'engine-a')"));
        assertEquals("f", text(con, stop.replace("engine-a", "engine-b")));
        assertEquals("f", text(con, stop.replace(token.toString(), UUID.randomUUID().toString())));
        assertEquals("t", text(con, stop));
        String observed = field("execution_stopped_at::text");
        assertEquals("t", text(con, stop));
        assertEquals(observed, field("execution_stopped_at::text"));
    }

    @Test
    void aTerminalPairStatusDoesNotEstablishStoppedWriters() throws Exception {
        prepareExecutionOutput(false);
        assertEquals("7", text(con, "SELECT status_code FROM job_pairs WHERE id=" + pair));
        assertNull(field("execution_stopped_at"));
        assertEquals("f", text(con, "SELECT publish_solver_build_output(" + artifact + ")"));
    }

    private String callback(String function, UUID token) {
        return "SELECT " + function + "(" + pair + ",1,'" + token + "'::uuid,'engine-a')";
    }

    private UUID prepareExecutionOutput(boolean stopped) throws Exception {
        return prepareExecutionOutput(stopped, false);
    }

    private UUID prepareExecutionOutput(boolean stopped, boolean conservedReceipt) throws Exception {
        UUID token = UUID.randomUUID();
        Path raw = Files.createDirectory(files.resolve("staging"));
        Files.writeString(Files.createDirectory(raw.resolve("solver")).resolve("program"), "BUILT");
        try (PreparedStatement ps = con.prepareStatement("SELECT prepare_solver_build_storage(?,1,?,?)")) {
            ps.setInt(1, pair); ps.setObject(2, token); ps.setString(3, raw.toString());
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); assertTrue(rs.getBoolean(1)); }
        }
        assertEquals("t", text(con, callback("bind_solver_build_execution", token)));
        if (stopped) assertEquals("t", text(con, callback("record_solver_build_execution_stop", token)));
        if (conservedReceipt) assertTrue(SolverBuildArtifacts.recordStopReceipt(con, pair, 1, token,
                "engine-a", syntheticReceipt(Instant.parse("2026-01-01T00:00:00Z"))));
        Path root = files.resolve("published");
        reservePath(con, artifact, root.resolve("solver"));
        try (PreparedStatement ps = con.prepareStatement("UPDATE solver_build_artifacts SET storage_root=? WHERE id=?")) {
            ps.setString(1, root.toString()); ps.setLong(2, artifact); assertEquals(1, ps.executeUpdate());
        }
        validateSnapshot(con, artifact, SolverArtifactTree.copy(raw, root, 1000, 20));
        return token;
    }

    @Test
    void stoppedExecutionCanRetryItsCallbackAfterPublication() throws Exception {
        UUID token = prepareExecutionOutput(true, true);
        assertEquals("t", text(con, "SELECT publish_solver_build_output(" + artifact + ")"));
        String stopped = field("execution_stopped_at::text");
        assertEquals("t", text(con, callback("record_solver_build_execution_stop", token)));
        assertEquals(stopped, field("execution_stopped_at::text"));
    }

    @Test
    void aStopTimestampWithoutAConservedReceiptCannotPublishRuntimeOutput() throws Exception {
        prepareExecutionOutput(true);
        assertNotNull(field("execution_stopped_at"));
        assertEquals("f", text(con, "SELECT publish_solver_build_output(" + artifact + ")"),
                "runtime publication requires conserved positive engine evidence");
    }

    private SolverBuildStopReceipt syntheticReceipt(Instant observed) {
        JsonObject facts = new JsonObject(); facts.addProperty("status", "exited");
        facts.addProperty("note", "Unicode \u03b1 and quoted input");
        return SolverBuildStopReceipt.observed("podman", artifact, pair, 1, "engine-a",
                "owned-container", observed, facts);
    }

    private UUID boundReceiptFixture() throws Exception {
        UUID token = UUID.randomUUID();
        assertEquals("t", text(con, prepare(token)));
        assertTrue(SolverBuildArtifacts.bindExecution(con, pair, 1, token, "engine-a"));
        return token;
    }

    private static String receiptDigest(String payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean rawReceipt(UUID token, String engine, String payload, String digest) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT record_solver_build_stop_receipt(?,1,?,?,?,?)")) {
            ps.setInt(1, pair); ps.setObject(2, token); ps.setString(3, engine);
            ps.setString(4, payload); ps.setString(5, digest);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getBoolean(1); }
        }
    }

    @Test void anIndependentConnectionSeesTheExactReceiptOnlyAfterCommit() throws Exception {
        UUID token = boundReceiptFixture(); var receipt = syntheticReceipt(Instant.parse("2026-01-01T00:00:00Z"));
        String raw = " " + receipt.payload() + "\n";
        assertTrue(rawReceipt(token, "engine-a", raw, receiptDigest(raw)));
        try (Connection observer = connect()) { assertTrue(SolverBuildArtifacts.findStopReceipt(observer, pair, 1).isEmpty()); }
        con.commit();
        try (Connection observer = connect()) {
            var stored = SolverBuildArtifacts.findStopReceipt(observer, pair, 1).orElseThrow();
            assertEquals(raw, stored.receipt().payload()); assertEquals(receiptDigest(raw), stored.receipt().sha256());
            assertNotNull(stored.recordedAt());
        }
    }

    @Test void receiptRetryPreservesTheFirstBytesAndTimestamp() throws Exception {
        UUID token = boundReceiptFixture(); var receipt = syntheticReceipt(Instant.parse("2026-01-01T00:00:00Z"));
        assertTrue(SolverBuildArtifacts.recordStopReceipt(con, pair, 1, token, "engine-a", receipt));
        String recorded = field("execution_stop_receipt_recorded_at::text");
        String stopped = field("execution_stopped_at::text");
        assertTrue(SolverBuildArtifacts.recordStopReceipt(con, pair, 1, token, "engine-a", receipt));
        assertEquals(recorded, field("execution_stop_receipt_recorded_at::text"));
        assertEquals(stopped, field("execution_stopped_at::text"));
        assertFalse(SolverBuildArtifacts.recordStopReceipt(con, pair, 1, token, "engine-a",
                syntheticReceipt(Instant.parse("2026-01-01T00:00:01Z"))));
        assertEquals(receipt.payload(), field("execution_stop_receipt_payload"));
    }

    @Test void anExplicitReceiptTransactionIsVisibleBeforeCleanupCanProceed() throws Exception {
        UUID token = boundReceiptFixture(); var receipt = syntheticReceipt(Instant.now());
        con.commit(); con.setAutoCommit(true);
        boolean recorded = org.starexec.data.database.Common.runTransactional(con,
                transaction -> SolverBuildArtifacts.recordStopReceipt(transaction, pair, 1, token, "engine-a", receipt));
        assertTrue(recorded);
        assertTrue(con.getAutoCommit());
        try (Connection observer = connect()) {
            assertEquals(receipt.payload(), SolverBuildArtifacts.findStopReceipt(observer, pair, 1)
                    .orElseThrow().receipt().payload());
        }
    }

    @Test void aDifferentTokenOrEngineCannotRecordAReceipt() throws Exception {
        UUID token = boundReceiptFixture(); var receipt = syntheticReceipt(Instant.parse("2026-01-01T00:00:00Z"));
        assertFalse(SolverBuildArtifacts.recordStopReceipt(con, pair, 1, UUID.randomUUID(), "engine-a", receipt));
        assertFalse(SolverBuildArtifacts.recordStopReceipt(con, pair, 1, token, "engine-b", receipt));
        assertNull(field("execution_stop_receipt_payload")); assertNull(field("execution_stopped_at"));
    }

    @Test void anUnboundExecutionCannotAcquireStopProofFromAPayload() throws Exception {
        UUID token = UUID.randomUUID(); assertEquals("t", text(con, prepare(token)));
        assertFalse(SolverBuildArtifacts.recordStopReceipt(con, pair, 1, token, "engine-a",
                syntheticReceipt(Instant.parse("2026-01-01T00:00:00Z"))));
        assertNull(field("engine_identity")); assertNull(field("execution_stopped_at"));
    }

    @ParameterizedTest @ValueSource(strings = { "artifact_id", "pair_id", "attempt_no", "engine_identity" })
    void receiptPayloadCannotNameADifferentArtifactOrExecution(String field) throws Exception {
        UUID token = boundReceiptFixture();
        JsonObject payload = com.google.gson.JsonParser.parseString(syntheticReceipt(Instant.now()).payload()).getAsJsonObject();
        if (field.equals("engine_identity")) payload.addProperty(field, "engine-b");
        else payload.addProperty(field, payload.get(field).getAsLong() + 1);
        String raw = payload.toString(); assertFalse(rawReceipt(token, "engine-a", raw, receiptDigest(raw)));
        assertNull(field("execution_stop_receipt_payload")); assertNull(field("execution_stopped_at"));
    }

    @ParameterizedTest @ValueSource(strings = { "{}", "[]", "null", "not JSON" })
    void malformedOrIncompleteReceiptIsRejectedWithoutAStop(String payload) throws Exception {
        UUID token = boundReceiptFixture(); assertFalse(rawReceipt(token, "engine-a", payload, receiptDigest(payload)));
        assertNull(field("execution_stopped_at"));
    }

    @Test void receiptDigestMismatchDoesNotRecordAStop() throws Exception {
        UUID token = boundReceiptFixture(); String payload = syntheticReceipt(Instant.now()).payload();
        assertFalse(rawReceipt(token, "engine-a", payload, "0".repeat(64)));
        assertNull(field("execution_stopped_at"));
    }

    @Test void aReceiptCannotBeRetrofittedAfterValidation() throws Exception {
        UUID token = boundReceiptFixture(); validate(con, artifact);
        assertFalse(SolverBuildArtifacts.recordStopReceipt(con, pair, 1, token, "engine-a", syntheticReceipt(Instant.now())));
        assertNull(field("execution_stop_receipt_payload"));
    }

    @Test void aDeletedSolverKeepsItsActualReceiptForCleanupWithoutPublishing() throws Exception {
        UUID token = boundReceiptFixture(); execute(con, "DELETE FROM solvers WHERE id=" + solver);
        var receipt = syntheticReceipt(Instant.now());
        assertTrue(SolverBuildArtifacts.recordStopReceipt(con, pair, 1, token, "engine-a", receipt));
        assertEquals("REJECTED", field("state"));
        assertTrue(SolverBuildArtifacts.findStopReceipt(con, pair, 1).orElseThrow().receipt()
                .matches(artifact, pair, 1, "engine-a"));
        assertEquals("f", text(con, "SELECT publish_solver_build_output(" + artifact + ")"));
    }

    @Test void aStoredReceiptCannotBeClearedRewrittenOrRedated() throws Exception {
        UUID token = boundReceiptFixture(); var receipt = syntheticReceipt(Instant.now());
        assertTrue(SolverBuildArtifacts.recordStopReceipt(con, pair, 1, token, "engine-a", receipt));
        refused("UPDATE solver_build_artifacts SET execution_stop_receipt_payload=NULL,execution_stop_receipt_sha256=NULL,"
                + "execution_stop_receipt_recorded_at=NULL WHERE id=" + artifact);
        refused("UPDATE solver_build_artifacts SET execution_stop_receipt_recorded_at=now()+interval '1 second' WHERE id=" + artifact);
        refused("UPDATE solver_build_artifacts SET execution_stop_receipt_payload=execution_stop_receipt_payload::jsonb::text,"
                + "execution_stop_receipt_sha256=encode(sha256(convert_to(execution_stop_receipt_payload::jsonb::text,'UTF8')),'hex')"
                + " WHERE id=" + artifact);
        assertEquals(receipt.payload(), field("execution_stop_receipt_payload"));
    }

    @Test void directPartialOrWrongDigestReceiptWritesAreRefused() throws Exception {
        boundReceiptFixture();
        refused("UPDATE solver_build_artifacts SET execution_stop_receipt_payload='{}' WHERE id=" + artifact);
        refused("UPDATE solver_build_artifacts SET execution_stop_receipt_payload='{}',execution_stop_receipt_sha256=repeat('0',64),"
                + "execution_stop_receipt_recorded_at=now() WHERE id=" + artifact);
        assertNull(field("execution_stop_receipt_payload"));
    }

    @Test
    void deletionDoesNotLoseAnAlreadyPreparedExecutionStop() throws Exception {
        UUID token = UUID.randomUUID();
        assertEquals("t", text(con, prepare(token)));
        execute(con, "DELETE FROM solvers WHERE id=" + solver);
        assertEquals("t", text(con, callback("bind_solver_build_execution", token)));
        assertEquals("t", text(con, callback("record_solver_build_execution_stop", token)));
        assertNull(field("solver_id"));
        assertEquals("f", text(con, "SELECT publish_solver_build_output(" + artifact + ")"));
    }

    @Test
    void preparedStagingAndExecutionBindingsCannotBeEdited() throws Exception {
        assertEquals("t", text(con, prepare(UUID.randomUUID())));
        refused("UPDATE solver_build_artifacts SET staging_path='/other/output' WHERE id=" + artifact);
        refused("UPDATE solver_build_artifacts SET execution_token=NULL WHERE id=" + artifact);
    }

    @Test
    void jdbcAdapterBindsUuidAndQuotedPathsAsData() throws Exception {
        UUID token = UUID.randomUUID();
        Path staging = Path.of("/execution/owner's output");
        assertTrue(SolverBuildArtifacts.prepare(con, pair, 1, token, staging));
        var actual = SolverBuildArtifacts.find(con, pair, 1).orElseThrow();
        assertEquals(artifact, actual.id());
        assertEquals(solver, actual.solverId());
        assertEquals(Integer.valueOf(solver), actual.liveSolverId());
        assertEquals(user, actual.ownerId());
        assertEquals(token, actual.executionToken());
        assertEquals(staging.toString(), actual.stagingPath());
        assertFalse(SolverBuildArtifacts.prepare(con, pair, 1, UUID.randomUUID(), staging));
        assertTrue(SolverBuildArtifacts.bindExecution(con, pair, 1, token, "engine:'quoted'"));
        assertTrue(SolverBuildArtifacts.recordStopped(con, pair, 1, token, "engine:'quoted'"));
        assertNotNull(SolverBuildArtifacts.find(con, pair, 1).orElseThrow().stoppedAt());
    }

    @Test
    void jdbcAdapterKeepsHistoricalLookupWithoutTreatingMissingRowsAsSuccess() throws Exception {
        assertTrue(SolverBuildArtifacts.find(con, pair, 2).isEmpty());
        assertFalse(SolverBuildArtifacts.bindExecution(con, pair, 2, UUID.randomUUID(), "engine"));
        assertFalse(SolverBuildArtifacts.recordStopped(con, pair, 2, UUID.randomUUID(), "engine"));
        execute(con, "DELETE FROM solvers WHERE id=" + solver);
        var actual = SolverBuildArtifacts.find(con, pair, 1).orElseThrow();
        assertEquals(solver, actual.solverId());
        assertNull(actual.liveSolverId());
        assertEquals("REJECTED", actual.state());
        assertEquals("/solver/source_src", actual.sourcePath());
    }

    @Test
    void aReservedPathCannotBeReplaced() throws Exception {
        validate(con, artifact);
        refused("UPDATE solver_build_artifacts SET artifact_path='/replacement' WHERE id=" + artifact);
    }

    @Test
    void validatedBytesAndExactPayloadRemainImmutable() throws Exception {
        validate(con, artifact);
        String payload = field("manifest_payload");
        refused("UPDATE solver_build_artifacts SET byte_size=0 WHERE id=" + artifact);
        refused("UPDATE solver_build_artifacts SET manifest_payload=manifest_payload::jsonb::text WHERE id=" + artifact);
        assertEquals(payload, field("manifest_payload"));
    }

    @Test
    void incorrectDigestIsRejectedBeforeValidation() throws Exception {
        execute(con, "UPDATE solver_build_artifacts SET state='STAGED',artifact_path='" + path(artifact)
                + "' WHERE id=" + artifact);
        refused("UPDATE solver_build_artifacts SET state='VALIDATED',byte_size=5,manifest_payload='{}',"
                + "manifest_sha256=repeat('0',64),validated_at=now() WHERE id=" + artifact);
    }

    @Test
    void invalidTransitionsAndDeletionCannotDiscardCleanupRecords() throws Exception {
        refused("UPDATE solver_build_artifacts SET state='PUBLISHED',artifact_path='/invalid' WHERE id=" + artifact);
        refused("DELETE FROM solver_build_artifacts WHERE id=" + artifact);
    }

    @Test
    void publicationRollbackRestoresBothPointerAndCatalog() throws Exception {
        validate(con, artifact);
        Savepoint before = con.setSavepoint();
        assertTrue(publish(con, artifact));
        con.rollback(before);
        assertEquals("/solver/source_src", text(con, "SELECT path FROM solvers WHERE id=" + solver));
        assertEquals("VALIDATED", field("state"));
        assertTrue(publish(con, artifact));
    }

    @Test
    void deletingTheExecutionPreservesItsPublishedSolverAndOrigin() throws Exception {
        validate(con, artifact); assertTrue(publish(con, artifact));
        execute(con, "DELETE FROM job_pairs WHERE id=" + pair);
        assertNull(field("pair_id"));
        assertEquals(Integer.toString(pair), field("origin_pair_id"));
        assertEquals("PUBLISHED", field("state"));
        assertEquals(path(artifact), text(con, "SELECT path FROM solvers WHERE id=" + solver));
    }

    @Test
    void solverDeletionPreservesARecoverableCleanupRecord() throws Exception {
        validate(con, artifact); assertTrue(publish(con, artifact));
        execute(con, "DELETE FROM solvers WHERE id=" + solver);
        assertNull(field("solver_id"));
        assertEquals(Integer.toString(solver), field("origin_solver_id"));
        assertEquals(path(artifact), field("artifact_path"));
        assertEquals("RETIRED", field("state"));
        assertFalse(publish(con, artifact));
    }

    @Test
    void aConcurrentNewBuildSerializesBeforeOlderPublication() throws Exception {
        validate(con, artifact); con.commit(); con.setAutoCommit(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection newer = connect(); Connection publishing = connect()) {
            newer.setAutoCommit(false); publishing.setAutoCommit(false);
            int pid = (int) number(publishing, "SELECT pg_backend_pid()");
            build(newer, solver, user);
            Future<Boolean> result = executor.submit(() -> publish(publishing, artifact));
            await().atMost(5, TimeUnit.SECONDS).until(() -> "t".equals(text(con,
                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=" + pid + " AND wait_event_type='Lock')")));
            newer.commit();
            assertFalse(result.get(5, TimeUnit.SECONDS));
            publishing.rollback();
            assertEquals("/solver/source_src", text(con, "SELECT path FROM solvers WHERE id=" + solver));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
