package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.constants.R;
import org.starexec.jobs.SolverArtifactTree;
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
        UUID token = UUID.randomUUID();
        Path raw = Files.createDirectory(files.resolve("staging"));
        Files.writeString(Files.createDirectory(raw.resolve("solver")).resolve("program"), "BUILT");
        try (PreparedStatement ps = con.prepareStatement("SELECT prepare_solver_build_storage(?,1,?,?)")) {
            ps.setInt(1, pair); ps.setObject(2, token); ps.setString(3, raw.toString());
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); assertTrue(rs.getBoolean(1)); }
        }
        assertEquals("t", text(con, callback("bind_solver_build_execution", token)));
        if (stopped) assertEquals("t", text(con, callback("record_solver_build_execution_stop", token)));
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
        UUID token = prepareExecutionOutput(true);
        assertEquals("t", text(con, "SELECT publish_solver_build_output(" + artifact + ")"));
        String stopped = field("execution_stopped_at::text");
        assertEquals("t", text(con, callback("record_solver_build_execution_stop", token)));
        assertEquals(stopped, field("execution_stopped_at::text"));
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
