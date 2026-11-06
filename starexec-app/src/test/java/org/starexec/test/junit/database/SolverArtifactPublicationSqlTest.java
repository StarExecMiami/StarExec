package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.constants.R;
import org.starexec.test.util.DatabaseTestSupport;

import java.nio.charset.StandardCharsets;
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
