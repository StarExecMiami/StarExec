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
import org.starexec.app.RESTServices;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.SolverSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.SessionUtil;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;

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
import java.util.concurrent.CountDownLatch;
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

    @Test void deletingArtifactMetadataRequiresCompletedPhysicalCleanup() throws Exception {
        failedStop("podman");
        assertTrue(recordFailure());
        assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE starexec.solver_build_artifacts SET state='DELETED' WHERE id=?", build.artifactId()));
        assertEquals("REJECTED", state());
        assertTrue(Files.isRegularFile(root().resolve("logs/" + R.SOLVER_BUILD_OUTPUT)));
        unchangedSource();
    }

    private String state() throws SQLException {
        return text(connection, "SELECT state FROM starexec.solver_build_artifacts WHERE id=?", build.artifactId());
    }

    private void unchangedSource() throws Exception {
        assertEquals("original source", Files.readString(source.resolve("starexec_build")));
        assertEquals("compiled binary α\n", Files.readString(build.staging().resolve("solver/program")));
        assertEquals("build log é\n", Files.readString(build.staging().resolve("logs/starexec_build_log")));
    }

    @ParameterizedTest @ValueSource(strings = {"source/direct", "source/recycled", "published/direct", "published/recycled", "failed/direct", "failed/recycled"})
    void deletionRetainsManagedBuildBytesAndTheirExistingCharge(String scenario) throws Exception {
        Common.initialize();
        if (scenario.startsWith("published")) { stopped("podman", null); assertTrue(publish()); }
        if (scenario.startsWith("failed")) { failedStop("podman"); assertTrue(recordFailure()); }
        execute(connection, "UPDATE starexec.users SET disk_size=10020 WHERE id=?", user);
        Path legacyLog = Files.createDirectory(files.resolve("legacy-log-directory")).resolve(R.SOLVER_BUILD_OUTPUT);
        Files.writeString(legacyLog, "previous log");
        boolean recycled = scenario.endsWith("recycled");
        if (recycled) execute(connection, "UPDATE starexec.solvers SET recycled=true WHERE id=?", solver);
        try (var solvers = mockStatic(Solvers.class, CALLS_REAL_METHODS)) {
            solvers.when(() -> Solvers.getSolverBuildOutput(solver)).thenReturn(legacyLog.toFile());
            assertTrue(recycled ? Solvers.setRecycledSolversToDeleted(user) : Solvers.delete(solver));
        }
        assertTrue(Files.isRegularFile(source.resolve("starexec_build")), "Managed source must survive logical deletion");
        if (scenario.startsWith("published")) assertTrue(Files.isRegularFile(root().resolve("solver/program")), "Published executable must survive logical deletion");
        if (!scenario.startsWith("source")) assertTrue(Files.isRegularFile(root().resolve("logs/" + R.SOLVER_BUILD_OUTPUT)), "Conserved log must survive logical deletion");
        assertEquals("previous log", Files.readString(legacyLog));
        assertEquals("true", text(connection, "SELECT deleted::text FROM starexec.solvers WHERE id=?", solver));
        assertEquals(20, number(connection, "SELECT disk_size FROM starexec.solvers WHERE id=?", solver));
        assertEquals(10020, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        unchangedSource();
    }

    private int legacySolver(Path path) throws Exception {
        return (int) number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'legacy-cleanup',now(),?,20,2) RETURNING id", user, path.toString());
    }

    private Path legacyLog(int id) throws Exception {
        return Files.writeString(Files.createDirectory(files.resolve("legacy-log-" + id)).resolve(R.SOLVER_BUILD_OUTPUT), "legacy log");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void catalogueRetainsDeletedBuildsAfterTheirStageReferenceIsGone(boolean recycled) throws Exception {
        Common.initialize(); stopped("podman", null); assertTrue(publish());
        execute(connection, "DELETE FROM starexec.jobpair_stage_data WHERE jobpair_id=?", pair);
        execute(connection, "UPDATE starexec.users SET disk_size=10020 WHERE id=?", user);
        if (recycled) execute(connection, "UPDATE starexec.solvers SET recycled=true WHERE id=?", solver);
        assertTrue(recycled ? Solvers.setRecycledSolversToDeleted(user) : Solvers.delete(solver));
        assertEquals("compiled binary \u03b1\n", Files.readString(root().resolve("solver/program")));
        assertEquals("build log \u00e9\n", Files.readString(root().resolve("logs/" + R.SOLVER_BUILD_OUTPUT)));
        assertEquals(10020, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        unchangedSource();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unreferencedLegacyDeletionSettlesOnlyAfterFilesAreGoneAndOnlyOnce(boolean recycled) throws Exception {
        Common.initialize();
        Path programs = privateDirectory(files.resolve("ordinary-solver")); Files.writeString(programs.resolve("program"), "legacy program");
        Path original = privateDirectory(files.resolve("ordinary-solver_src")); Files.writeString(original.resolve("source"), "legacy source");
        int ordinary = legacySolver(programs); Path log = legacyLog(ordinary);
        execute(connection, "UPDATE starexec.users SET disk_size=10040 WHERE id=?", user);
        if (recycled) execute(connection, "UPDATE starexec.solvers SET recycled=true WHERE id=?", ordinary);
        try (var solvers = mockStatic(Solvers.class, CALLS_REAL_METHODS)) {
            solvers.when(() -> Solvers.getSolverBuildOutput(ordinary)).thenReturn(log.toFile());
            assertTrue(recycled ? Solvers.setRecycledSolversToDeleted(user) : Solvers.delete(ordinary));
            assertThrows(java.nio.file.NoSuchFileException.class, () -> Files.readAttributes(programs, BasicFileAttributes.class));
            assertThrows(java.nio.file.NoSuchFileException.class, () -> Files.readAttributes(original, BasicFileAttributes.class));
            assertThrows(java.nio.file.NoSuchFileException.class, () -> Files.readAttributes(log.getParent(), BasicFileAttributes.class));
            assertEquals(0, number(connection, "SELECT disk_size FROM starexec.solvers WHERE id=?", ordinary));
            assertEquals(10020, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
            assertTrue(recycled ? Solvers.setRecycledSolversToDeleted(user) : Solvers.delete(ordinary));
            assertEquals(10020, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
            assertEquals(1, number(connection, "SELECT count(*) FROM starexec.solvers WHERE deleted"), "The cleanup fixture must own the sole deleted asset");
            assertTrue(Solvers.cleanOrphanedDeletedSolvers());
            assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solvers WHERE id=?", ordinary));
            assertEquals(10020, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        }
        unchangedSource();
    }

    @Test void failedLegacyCleanupRetainsItsChargeAndCanRetryFromTheTombstone() throws Exception {
        Common.initialize();
        Path programs = privateDirectory(files.resolve("retry-solver")); Files.writeString(programs.resolve("program"), "legacy program");
        int ordinary = legacySolver(programs); Path log = legacyLog(ordinary);
        execute(connection, "UPDATE starexec.users SET disk_size=10040 WHERE id=?", user);
        try (var solvers = mockStatic(Solvers.class, CALLS_REAL_METHODS)) {
            solvers.when(() -> Solvers.getSolverBuildOutput(ordinary)).thenReturn(log.toFile());
            Files.setPosixFilePermissions(files, PosixFilePermissions.fromString("---------"));
            try {
                assertFalse(Solvers.delete(ordinary));
            } finally { Files.setPosixFilePermissions(files, PosixFilePermissions.fromString("rwx------")); }
            assertEquals("true", text(connection, "SELECT deleted::text FROM starexec.solvers WHERE id=?", ordinary));
            assertEquals(20, number(connection, "SELECT disk_size FROM starexec.solvers WHERE id=?", ordinary));
            assertEquals(10040, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
            assertEquals("legacy program", Files.readString(programs.resolve("program")));
            assertTrue(Solvers.delete(ordinary));
            assertEquals(10020, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        }
        unchangedSource();
    }

    @Test void recycledBatchSeparatesRetainedAndUnreferencedStorage() throws Exception {
        Common.initialize(); stopped("podman", null); assertTrue(publish());
        Path programs = privateDirectory(files.resolve("batch-ordinary")); Files.writeString(programs.resolve("program"), "legacy program");
        int ordinary = legacySolver(programs); Path log = legacyLog(ordinary);
        execute(connection, "UPDATE starexec.solvers SET recycled=true WHERE id IN (?,?)", solver, ordinary);
        execute(connection, "UPDATE starexec.users SET disk_size=10040 WHERE id=?", user);
        try (var solvers = mockStatic(Solvers.class, CALLS_REAL_METHODS)) {
            solvers.when(() -> Solvers.getSolverBuildOutput(ordinary)).thenReturn(log.toFile());
            assertTrue(Solvers.setRecycledSolversToDeleted(user));
        }
        assertEquals(2, number(connection, "SELECT count(*) FROM starexec.solvers WHERE user_id=? AND deleted", user));
        assertEquals(20, number(connection, "SELECT disk_size FROM starexec.solvers WHERE id=?", solver));
        assertEquals(0, number(connection, "SELECT disk_size FROM starexec.solvers WHERE id=?", ordinary));
        assertEquals(10020, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        assertEquals("compiled binary \u03b1\n", Files.readString(root().resolve("solver/program")));
        unchangedSource();
    }

    @Test void anotherAssetCannotDeleteAConservedSourceThroughAPathAlias() throws Exception {
        Common.initialize(); int alias = legacySolver(source); Path log = legacyLog(alias);
        execute(connection, "UPDATE starexec.users SET disk_size=10040 WHERE id=?", user);
        try (var solvers = mockStatic(Solvers.class, CALLS_REAL_METHODS)) {
            solvers.when(() -> Solvers.getSolverBuildOutput(alias)).thenReturn(log.toFile());
            assertTrue(Solvers.delete(alias));
        }
        unchangedSource();
        assertEquals(10040, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
    }

    @ParameterizedTest @ValueSource(strings = {"symbolic", "hard"})
    void legacyPermissionRepairNeverMutatesLinkedConservedTrees(String kind) throws Exception {
        Common.initialize(); stopped("podman", null); assertTrue(publish());
        Path programs = privateDirectory(files.resolve("readonly-legacy")); Files.writeString(programs.resolve("program"), "legacy program");
        if (kind.equals("symbolic")) Files.createSymbolicLink(programs.resolve("library"), root());
        else Files.createLink(programs.resolve("library"), root().resolve("solver/program"));
        Files.setPosixFilePermissions(programs, PosixFilePermissions.fromString("r-xr-xr-x"));
        int ordinary = legacySolver(programs); Path log = legacyLog(ordinary);
        var originalMode = Files.getPosixFilePermissions(root().resolve("solver/program"));
        execute(connection, "UPDATE starexec.users SET disk_size=10040 WHERE id=?", user);
        try (var solvers = mockStatic(Solvers.class, CALLS_REAL_METHODS)) {
            solvers.when(() -> Solvers.getSolverBuildOutput(ordinary)).thenReturn(log.toFile());
            assertTrue(Solvers.delete(ordinary));
        }
        assertEquals(originalMode, Files.getPosixFilePermissions(root().resolve("solver/program")), "Cleanup must not change a linked artifact's permissions");
        assertTrue(publish());
        unchangedSource();
    }

    @Test void stageReferencesRetainAnUploadedSolverWithoutAnyBuildCatalogueEntry() throws Exception {
        Common.initialize();
        Path programs = privateDirectory(files.resolve("referenced-upload")); Files.writeString(programs.resolve("program"), "uploaded program");
        int ordinary = legacySolver(programs);
        long job = number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'scientific',1,0,false) RETURNING id", user);
        long scientificPair = number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id", job);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", scientificPair, ordinary);
        execute(connection, "UPDATE starexec.users SET disk_size=10040 WHERE id=?", user);
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solver_build_artifacts WHERE origin_solver_id=?", ordinary));
        assertTrue(Solvers.delete(ordinary));
        assertEquals("uploaded program", Files.readString(programs.resolve("program")));
        assertEquals(10040, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "path", "charge"})
    void settlementRefusesAChangedDeletionSnapshot(String changed) throws Exception {
        Path programs = privateDirectory(files.resolve("settlement-snapshot")); Files.writeString(programs.resolve("program"), "legacy program");
        int ordinary = legacySolver(programs);
        execute(connection, "UPDATE starexec.users SET disk_size=10040 WHERE id=?", user);
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.request_solver_storage_deletion(?)", ordinary));
        if (changed.equals("path")) execute(connection, "UPDATE starexec.solvers SET path=? WHERE id=?", files.resolve("different-path").toString(), ordinary);
        if (changed.equals("charge")) execute(connection, "UPDATE starexec.solvers SET disk_size=21 WHERE id=?", ordinary);
        if (changed.equals("owner")) {
            otherUser = (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                    + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Other','Owner','synthetic',now(),'synthetic',1000000,100,0) RETURNING id",
                    "del-" + UUID.randomUUID() + "@example.invalid");
            execute(connection, "UPDATE starexec.solvers SET user_id=? WHERE id=?", otherUser, ordinary);
        }
        assertEquals("false", text(connection, "SELECT starexec.settle_legacy_solver_deletion(?,?,?,?)::text", ordinary, user, programs.toString(), 20));
        assertEquals(10040, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
        assertEquals("legacy program", Files.readString(programs.resolve("program")));
    }

    @Test void recycleAfterTheCommittedSelectionWaitsForTheNextPurge() throws Exception {
        Common.initialize();
        Path programs = privateDirectory(files.resolve("selected-ordinary")); Files.writeString(programs.resolve("program"), "selected program");
        int ordinary = legacySolver(programs); Path log = legacyLog(ordinary);
        Path latePrograms = privateDirectory(files.resolve("late-recycled")); Files.writeString(latePrograms.resolve("program"), "late program");
        int late = legacySolver(latePrograms);
        execute(connection, "UPDATE starexec.solvers SET recycled=true WHERE id=?", ordinary);
        execute(connection, "UPDATE starexec.users SET disk_size=10060 WHERE id=?", user);
        try (var solvers = mockStatic(Solvers.class, CALLS_REAL_METHODS);
             var utility = mockStatic(org.starexec.util.Util.class, call -> {
                 if (call.getMethod().getName().equals("safeDeleteDirectory") && programs.toString().equals(call.getArgument(0))) {
                     execute(connection, "UPDATE starexec.solvers SET recycled=true WHERE id=?", late);
                 }
                 return call.callRealMethod();
             })) {
            solvers.when(() -> Solvers.getSolverBuildOutput(ordinary)).thenReturn(log.toFile());
            assertEquals("selected program", Files.readString(programs.resolve("program")));
            assertTrue(Solvers.setRecycledSolversToDeleted(user));
        }
        assertEquals("false", text(connection, "SELECT deleted::text FROM starexec.solvers WHERE id=?", late));
        assertEquals("true", text(connection, "SELECT recycled::text FROM starexec.solvers WHERE id=?", late));
        assertEquals("late program", Files.readString(latePrograms.resolve("program")));
        assertEquals(10040, number(connection, "SELECT disk_size FROM starexec.users WHERE id=?", user));
    }

    @Test void permanentRemovalCannotDropRetainedCleanupMetadata() throws Exception {
        Common.initialize(); execute(connection, "UPDATE starexec.users SET disk_size=10020 WHERE id=?", user);
        assertTrue(Solvers.delete(solver));
        assertFalse(Solvers.deleteAndRemoveSolver(solver));
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.solvers WHERE id=?", solver));
        assertEquals(20, number(connection, "SELECT disk_size FROM starexec.solvers WHERE id=?", solver));
        unchangedSource();
    }

    @Test void orphanCleanupCannotEraseManagedStorageOrItsCharge() throws Exception {
        Common.initialize(); execute(connection, "UPDATE starexec.users SET disk_size=10020 WHERE id=?", user);
        assertTrue(Solvers.delete(solver));
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.solvers WHERE deleted"), "The global cleanup fixture must own the sole deleted asset");
        assertTrue(Solvers.cleanOrphanedDeletedSolvers());
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.solvers WHERE id=?", solver));
        assertEquals(20, number(connection, "SELECT disk_size FROM starexec.solvers WHERE id=?", solver));
        unchangedSource();
    }

    private String readRestBuildLog(int reader) throws Exception {
        return readRestBuildLog(reader, false);
    }

    private String readRestBuildLog(int reader, boolean administrator) throws Exception {
        Common.initialize();
        Path legacy = files.resolve("legacy-build-log");
        if (!Files.exists(legacy)) Files.writeString(legacy, "stale legacy log");
        HttpServletRequest request = mock(HttpServletRequest.class);
        try (var session = mockStatic(SessionUtil.class);
             var security = mockStatic(SolverSecurity.class);
             var admin = mockStatic(GeneralSecurity.class);
             var solvers = mockStatic(Solvers.class, CALLS_REAL_METHODS)) {
            session.when(() -> SessionUtil.getUserId(request)).thenReturn(reader);
            admin.when(() -> GeneralSecurity.hasAdminReadPrivileges(reader)).thenReturn(administrator);
            security.when(() -> SolverSecurity.canUserSeeBuildLog(solver, reader)).thenReturn(new ValidatorStatusCode(true));
            solvers.when(() -> Solvers.getSolverBuildOutput(solver)).thenReturn(legacy.toFile());
            return new RESTServices().getSolverBuildLog(solver, request);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void appliedManagedBuildLogsReachExistingRestEndpoint(boolean failed) throws Exception {
        if (failed) { failedStop("podman"); assertTrue(recordFailure()); }
        else { stopped("podman", null); assertTrue(publish()); }
        assertEquals(build.artifactId(), number(connection, "SELECT build_output_artifact_id FROM starexec.solvers WHERE id=?", solver));
        assertEquals("build log \u00e9\n", readRestBuildLog(user));
        unchangedSource();
    }

    @ParameterizedTest @ValueSource(strings = {"bytes", "link", "hard-link"})
    void appliedLogsRejectChangedBytesAndFileAliases(String condition) throws Exception {
        stopped("podman", null); assertTrue(publish());
        Path logs = root().resolve("logs");
        Path file = logs.resolve(R.SOLVER_BUILD_OUTPUT);
        Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwx------"));
        if (condition.equals("bytes")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(file, "Build log \u00e9\n");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        } else {
            Path outside = files.resolve("private-other-log");
            Files.writeString(outside, "foreign private contents"); Files.delete(file);
            if (condition.equals("link")) Files.createSymbolicLink(file, outside);
            else Files.createLink(file, outside);
        }
        Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("r-xr-xr-x"));
        assertThrows(javax.ws.rs.WebApplicationException.class, () -> readRestBuildLog(user));
        unchangedSource();
    }

    @ParameterizedTest @ValueSource(strings = {"path", "owner", "deleted", "pointer"})
    void appliedLogsRefuseChangedSolverIdentityOrAnUnappliedReference(String condition) throws Exception {
        stopped("podman", null); assertTrue(publish());
        if (condition.equals("path")) execute(connection, "UPDATE starexec.solvers SET path=? WHERE id=?", source.toString(), solver);
        if (condition.equals("owner")) {
            otherUser = (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                    + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Other','Owner','synthetic',now(),'synthetic',1000000,100,0) RETURNING id",
                    "log-owner-" + UUID.randomUUID() + "@example.invalid");
            execute(connection, "UPDATE starexec.solvers SET user_id=? WHERE id=?", otherUser, solver);
        }
        if (condition.equals("deleted")) execute(connection, "UPDATE starexec.solvers SET deleted=true WHERE id=?", solver);
        if (condition.equals("pointer")) execute(connection, "UPDATE starexec.solvers SET build_output_artifact_id=NULL WHERE id=?", solver);
        var refused = assertThrows(javax.ws.rs.WebApplicationException.class, () -> readRestBuildLog(user));
        assertEquals("not available", refused.getResponse().getEntity());
        unchangedSource();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedLogsKeepEmptyAndAbsentOutcomesDistinct(boolean empty) throws Exception {
        failedStop("podman");
        Path log = build.staging().resolve("logs/" + R.SOLVER_BUILD_OUTPUT);
        if (empty) Files.writeString(log, ""); else Files.delete(log);
        assertTrue(recordFailure());
        if (empty) assertEquals("", readRestBuildLog(user));
        else assertThrows(javax.ws.rs.WebApplicationException.class, () -> readRestBuildLog(user));
        assertEquals("original source", Files.readString(source.resolve("starexec_build")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void verifiedOutcomeReplayRestoresTheExplicitLogReference(boolean failed) throws Exception {
        if (failed) { failedStop("podman"); assertTrue(recordFailure()); }
        else { stopped("podman", null); assertTrue(publish()); }
        String frozen = frozenManifest();
        execute(connection, "UPDATE starexec.solvers SET build_output_artifact_id=NULL WHERE id=?", solver);
        if (failed) assertTrue(recordFailure()); else assertTrue(publish());
        assertEquals(build.artifactId(), number(connection, "SELECT build_output_artifact_id FROM starexec.solvers WHERE id=?", solver));
        assertEquals(frozen, frozenManifest()); assertEquals("build log \u00e9\n", readRestBuildLog(user));
        unchangedSource();
    }

    @Test void legacyLogReadbackKeepsTheExistingFileLocator() throws Exception {
        Common.initialize();
        int legacySolver = (int) number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'legacy-log',now(),?,0,2) RETURNING id", user, source.toString());
        Path legacy = files.resolve("ordinary-legacy-log"); Files.writeString(legacy, "legacy log \u00e9\n");
        try (var solvers = mockStatic(Solvers.class, CALLS_REAL_METHODS);
             var admin = mockStatic(GeneralSecurity.class)) {
            solvers.when(() -> Solvers.getSolverBuildOutput(legacySolver)).thenReturn(legacy.toFile());
            assertEquals("legacy log \u00e9\n", Solvers.readSolverBuildLog(legacySolver, user).orElseThrow());
            solvers.verify(() -> Solvers.getSolverBuildOutput(legacySolver));
        }
    }

    @Test void appliedLogsRemainVisibleToAuthorizedAdministrators() throws Exception {
        stopped("podman", null); assertTrue(publish());
        assertEquals("build log \u00e9\n", readRestBuildLog(user + 1, true));
        unchangedSource();
    }

    @Test void appliedOutputReferenceRollsBackWithItsSolverOutcome() throws Exception {
        stopped("podman", null);
        var receipt = SolverBuildArtifacts.findStopReceipt(connection, pair, 1).orElseThrow().receipt();
        assertTrue(SolverBuildArtifacts.reserveOutput(connection, build, engine("podman"), receipt.sha256(), root()));
        var snapshot = SolverArtifactTree.copy(build.staging(), root(), 4096, 32);
        assertTrue(SolverBuildArtifacts.validateOutput(connection, build, engine("podman"), receipt.sha256(), root(),
                new SolverBuildArtifacts.OutputManifest(snapshot.payload(), snapshot.sha256(), snapshot.byteSize())));
        connection.setAutoCommit(false);
        assertTrue(SolverBuildArtifacts.publishOutput(connection, build.artifactId()));
        assertEquals(build.artifactId(), number(connection, "SELECT build_output_artifact_id FROM starexec.solvers WHERE id=?", solver));
        try (Connection observer = connect()) {
            assertEquals(0, number(observer, "SELECT COALESCE(build_output_artifact_id,0) FROM starexec.solvers WHERE id=?", solver));
            assertEquals(0, number(observer, "SELECT build_status FROM starexec.solvers WHERE id=?", solver));
        }
        connection.rollback(); connection.setAutoCommit(true);
        assertEquals(0, number(connection, "SELECT COALESCE(build_output_artifact_id,0) FROM starexec.solvers WHERE id=?", solver));
        assertEquals("VALIDATED", state());
        assertTrue(publish()); assertEquals("build log \u00e9\n", readRestBuildLog(user));
        unchangedSource();
    }

    @Test void appliedLogReadPinsTheSolverRowUntilVerificationCompletes() throws Exception {
        Common.initialize(); stopped("podman", null); assertTrue(publish());
        CountDownLatch reading = new CountDownLatch(1); CountDownLatch finish = new CountDownLatch(1);
        var workers = Executors.newSingleThreadExecutor();
        try (Connection updater = connect()) {
            int updatingPid = (int) number(updater, "SELECT pg_backend_pid()");
            var read = workers.submit(() -> {
                try (var tree = mockStatic(SolverArtifactTree.class, CALLS_REAL_METHODS);
                     var admin = mockStatic(GeneralSecurity.class)) {
                    tree.when(() -> SolverArtifactTree.readLog(any(), anyString(), anyString(), anyLong())).thenAnswer(call -> {
                        reading.countDown(); assertTrue(finish.await(10, TimeUnit.SECONDS)); return call.callRealMethod();
                    });
                    return Solvers.readSolverBuildLog(solver, user).orElseThrow();
                }
            });
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            var updateWorkers = Executors.newSingleThreadExecutor();
            try {
                var update = updateWorkers.submit(() -> {
                    execute(updater, "UPDATE starexec.solvers SET description='changed during log read' WHERE id=?", solver);
                    return true;
                });
                await().atMost(5, TimeUnit.SECONDS).until(() -> number(connection,
                        "SELECT cardinality(pg_blocking_pids(?))", updatingPid) > 0);
                assertFalse(update.isDone()); finish.countDown();
                assertEquals("build log \u00e9\n", read.get(10, TimeUnit.SECONDS));
                assertTrue(update.get(10, TimeUnit.SECONDS));
            } finally { finish.countDown(); updateWorkers.shutdownNow(); assertTrue(updateWorkers.awaitTermination(10, TimeUnit.SECONDS)); }
        } finally { finish.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        unchangedSource();
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
