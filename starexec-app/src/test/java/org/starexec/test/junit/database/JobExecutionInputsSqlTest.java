package org.starexec.test.junit.database;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.Common;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.JobExecutionInputs;
import org.starexec.data.database.JobStorageLeases;
import org.starexec.test.util.DatabaseTestSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Actual PostgreSQL permissions and snapshots; filesystem capacity is explicitly synthetic. */
class JobExecutionInputsSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private DedicatedStorageResource resource;
    private JobStorageLeases.Lease lease;
    private int owner, job, pair, bench, solver, config;
    private int initialIsolation;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("JobExecutionInputsSqlTest");
        Common.initialize();
    }

    private static long number(Connection con, String sql, Object... args) throws SQLException {
        try (var query = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) query.setObject(i + 1, args[i]);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getLong(1); }
        }
    }

    private static void execute(Connection con, String sql, Object... args) throws SQLException {
        try (var query = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) query.setObject(i + 1, args[i]);
            query.execute();
        }
    }

    private int user() throws SQLException {
        return (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Inputs','synthetic',now(),'synthetic',4194304,100,0) RETURNING id",
                "job-input-" + UUID.randomUUID() + "@example.invalid");
    }

    private int benchmark(int user, String name) throws Exception {
        var path = Files.writeString(files.resolve(name), "synthetic input");
        return (int) number(connection, "INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size) VALUES (?,?,now(),?,15) RETURNING id",
                user, name, path.toString());
    }

    private void parameters(int number) throws SQLException {
        execute(connection, "INSERT INTO starexec.job_stage_params(job_id,stage_number,cputimeout,clocktimeout,maximum_memory,stdout_save_option,extra_output_save_option)"
                + " VALUES (?,?,10,20,1048576,2,2)", job, number);
    }

    @BeforeEach void seed() throws Exception {
        connection = Common.getConnection(); initialIsolation = connection.getTransactionIsolation();
        owner = user(); bench = benchmark(owner, "primary");
        var source = Files.createDirectory(files.resolve("solver"));
        solver = (int) number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'solver',now(),?,20,1) RETURNING id", owner, source.toString());
        config = (int) number(connection, "INSERT INTO starexec.configurations(solver_id,name,updated) VALUES (?,'default',now()) RETURNING id", solver);
        job = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'ordinary',1,0,false) RETURNING id", owner);
        pair = (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,bench_id,status_code,primary_jobpair_data) VALUES (?,?,1,1) RETURNING id", job, bench);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,config_id,disk_size) VALUES (?,1,1,?,?,0)", pair, solver, config);
        parameters(1);
        Path slot = Files.createDirectory(files.resolve("slot"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        resource = mock(DedicatedStorageResource.class);
        when(resource.root()).thenReturn(slot); when(resource.identity()).thenReturn("observed-resource");
        when(resource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-", ""));
        when(resource.capacityBytes()).thenReturn(1048576L); when(resource.inodeLimit()).thenReturn(128L);
        assertTrue(ExecutionStorageLeases.register(connection, resource));
        UUID token = UUID.randomUUID();
        assertTrue(JobStorageLeases.reserve(connection, pair, 1, token, resource));
        lease = JobStorageLeases.find(connection, token).orElseThrow();
    }

    @AfterEach void closeConnection() throws Exception {
        if (connection == null) return;
        try {
            if (!connection.getAutoCommit()) { connection.rollback(); connection.setAutoCommit(true); }
            connection.setTransactionIsolation(initialIsolation);
        } finally { connection.close(); }
    }
    private JobExecutionInputs.Snapshot capture() throws SQLException { return JobExecutionInputs.capture(connection, lease, resource).orElseThrow(); }
    private JsonObject payload() throws SQLException { return JsonParser.parseString(capture().payload()).getAsJsonObject(); }
    private void refused() throws SQLException { assertTrue(JobExecutionInputs.capture(connection, lease, resource).isEmpty()); }

    @Test void recoveryCanLocateTheConservedTokenFromDurablePairAndAttempt() throws Exception {
        connection.close(); connection = Common.getConnection();
        assertEquals(lease, JobStorageLeases.find(connection, pair, 1).orElseThrow());
        assertTrue(JobStorageLeases.find(connection, pair, 2).isEmpty());
        assertTrue(JobStorageLeases.find(connection, -1, 1).isEmpty());
        assertTrue(JobStorageLeases.find(connection, pair, 0).isEmpty());
    }

    @Test void aSnapshotPreservesEveryInputAndDoesNotConsumeUseOrWriteFiles() throws Exception {
        int isolation = connection.getTransactionIsolation();
        var input = capture(); var data = JsonParser.parseString(input.payload()).getAsJsonObject();
        assertEquals(2, input.files().size()); assertEquals(1, data.getAsJsonArray("stages").size());
        assertEquals(bench, data.getAsJsonArray("benchmarks").get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals("default", data.getAsJsonArray("stages").get(0).getAsJsonObject().getAsJsonObject("configuration").get("name").getAsString());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.payload().getBytes(StandardCharsets.UTF_8))), input.sha256());
        assertEquals(input, capture());
        assertThrows(UnsupportedOperationException.class, () -> input.files().clear());
        assertEquals(lease, JobStorageLeases.find(connection, pair, 1).orElseThrow());
        assertTrue(connection.getAutoCommit()); assertEquals(isolation, connection.getTransactionIsolation());
        try (var children = Files.list(resource.root())) { assertTrue(children.findAny().isEmpty()); }
    }

    @Test void transitiveBenchmarkDependenciesAreAuthorizedAndCyclesTerminate() throws Exception {
        int first = benchmark(owner, "axiom-one"), second = benchmark(owner, "axiom-two");
        execute(connection, "INSERT INTO starexec.bench_dependency(primary_bench_id,secondary_bench_id,include_path) VALUES (?,?,?)", bench, first, "../Axioms/one.ax");
        execute(connection, "INSERT INTO starexec.bench_dependency(primary_bench_id,secondary_bench_id,include_path) VALUES (?,?,?)", first, second, "two.ax");
        execute(connection, "INSERT INTO starexec.bench_dependency(primary_bench_id,secondary_bench_id,include_path) VALUES (?,?,?)", second, first, "one.ax");
        var input = capture(); var data = JsonParser.parseString(input.payload()).getAsJsonObject();
        assertEquals(4, input.files().size()); assertEquals(3, data.getAsJsonArray("benchmarks").size());
        assertEquals(3, data.getAsJsonArray("benchmarkDependencies").size());
        assertEquals("../Axioms/one.ax", data.getAsJsonArray("benchmarkDependencies").get(0).getAsJsonObject().get("include_path").getAsString());
    }

    @Test void aHiddenSecondaryBenchmarkIsNotImplicitlyAuthorizedByThePrimary() throws Exception {
        int hidden = benchmark(user(), "private-axiom");
        execute(connection, "INSERT INTO starexec.bench_dependency(primary_bench_id,secondary_bench_id,include_path) VALUES (?,?,?)", bench, hidden, "hidden.ax");
        refused();
    }

    @Test void hiddenPrimaryBenchmarksAndSolversAreRefused() throws Exception {
        int other = user();
        execute(connection, "UPDATE starexec.benchmarks SET user_id=? WHERE id=?", other, bench); refused();
        execute(connection, "UPDATE starexec.benchmarks SET user_id=? WHERE id=?", owner, bench);
        execute(connection, "UPDATE starexec.solvers SET user_id=? WHERE id=?", other, solver); refused();
    }

    @ParameterizedTest @ValueSource(strings = {"deleted", "recycled"})
    void removedBenchmarksAndSolversAreNotOmittedSilently(String column) throws Exception {
        assertTrue(java.util.Set.of("deleted", "recycled").contains(column));
        execute(connection, "UPDATE starexec.benchmarks SET " + column + "=true WHERE id=?", bench); refused();
        execute(connection, "UPDATE starexec.benchmarks SET " + column + "=false WHERE id=?", bench);
        execute(connection, "UPDATE starexec.solvers SET " + column + "=true WHERE id=?", solver); refused();
    }

    @ParameterizedTest @ValueSource(ints = {0,3,4})
    void unbuiltFailedAndUnknownSolversCannotBecomeInputs(int status) throws Exception {
        execute(connection, "UPDATE starexec.solvers SET build_status=? WHERE id=?", status, solver); refused();
    }

    @Test void deletedMissingOrMismatchedConfigurationsRefuseCapture() throws Exception {
        execute(connection, "UPDATE starexec.configurations SET deleted=true WHERE id=?", config); refused();
        execute(connection, "UPDATE starexec.configurations SET deleted=false,solver_id=NULL WHERE id=?", config); refused();
        execute(connection, "DELETE FROM starexec.configurations WHERE id=?", config); refused();
    }

    @Test void missingStageParametersAndPrimaryStagesAreRefused() throws Exception {
        execute(connection, "DELETE FROM starexec.job_stage_params WHERE job_id=?", job); refused();
        parameters(1); execute(connection, "UPDATE starexec.job_pairs SET primary_jobpair_data=2 WHERE id=?", pair); refused();
    }

    @Test void aMissingSolverCannotBeReinterpretedAsAnUnrecordedNoop() throws Exception {
        execute(connection, "UPDATE starexec.jobpair_stage_data SET solver_id=NULL,config_id=NULL WHERE jobpair_id=?", pair); refused();
    }

    @Test void extraInputsKeepTheirDeclaredOrderAndAreAllAuthorized() throws Exception {
        int extra = benchmark(owner, "extra");
        execute(connection, "INSERT INTO starexec.jobpair_inputs(jobpair_id,input_number,bench_id) VALUES (?,1,?)", pair, extra);
        var input = capture(); assertEquals(3, input.files().size());
        assertEquals(extra, JsonParser.parseString(input.payload()).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject().get("bench_id").getAsInt());
        execute(connection, "UPDATE starexec.benchmarks SET user_id=? WHERE id=?", user(), extra); refused();
    }

    @Test void missingInputPositionsAreNotCompactedIntoDifferentArguments() throws Exception {
        execute(connection, "INSERT INTO starexec.jobpair_inputs(jobpair_id,input_number,bench_id) VALUES (?,2,?)", pair, benchmark(owner, "extra")); refused();
    }

    private int pipeline() throws SQLException {
        int id = (int) number(connection, "INSERT INTO starexec.solver_pipelines(user_id,name,uploaded) VALUES (?,'pipeline',now()) RETURNING id", owner);
        int stage = (int) number(connection, "INSERT INTO starexec.pipeline_stages(pipeline_id,config_id,is_noop) VALUES (?,?,false) RETURNING stage_id", id, config);
        execute(connection, "UPDATE starexec.jobpair_stage_data SET stage_id=? WHERE jobpair_id=? AND stage_number=1", stage, pair);
        return id;
    }

    @Test void explicitNoopsAndPipelineOutputReferencesArePreserved() throws Exception {
        int pipeline = pipeline();
        int stage = (int) number(connection, "INSERT INTO starexec.pipeline_stages(pipeline_id,is_noop) VALUES (?,true) RETURNING stage_id", pipeline);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,stage_id,disk_size) VALUES (?,2,?,0)", pair, stage);
        parameters(2);
        execute(connection, "INSERT INTO starexec.pipeline_dependencies(stage_id,input_type,input_id,input_number) VALUES (?,2,1,1)", stage);
        execute(connection, "UPDATE starexec.job_stage_params SET stdout_save_option=3,extra_output_save_option=3 WHERE job_id=? AND stage_number=2", job);
        var data = payload(); assertEquals(2, data.getAsJsonArray("stages").size());
        var second = data.getAsJsonArray("stages").get(1).getAsJsonObject();
        assertTrue(second.get("is_noop").getAsBoolean()); assertEquals(1, second.getAsJsonArray("dependencies").size());
        assertEquals(3, second.getAsJsonObject("parameters").get("stdout_save_option").getAsInt());
    }

    @ParameterizedTest @ValueSource(ints = {0,1,2})
    void invalidPipelineInputsCannotBeRewrittenIntoDifferentDependencies(int kind) throws Exception {
        pipeline();
        int stage = (int) number(connection, "SELECT stage_id FROM starexec.jobpair_stage_data WHERE jobpair_id=? AND stage_number=1", pair);
        execute(connection, "INSERT INTO starexec.pipeline_dependencies(stage_id,input_type,input_id,input_number) VALUES (?,?,99,1)", stage, kind); refused();
    }

    @Test void aChangedPipelineConfigurationCannotReuseTheStageAssociation() throws Exception {
        pipeline();
        int other = (int) number(connection, "INSERT INTO starexec.configurations(solver_id,name,updated) VALUES (?,'other',now()) RETURNING id", solver);
        execute(connection, "UPDATE starexec.jobpair_stage_data SET config_id=? WHERE jobpair_id=?", other, pair); refused();
    }

    private int processor(int type) throws Exception {
        int space = (int) number(connection, "INSERT INTO starexec.spaces(name) VALUES ('processor-community') RETURNING id");
        execute(connection, "INSERT INTO starexec.closure(ancestor,descendant) VALUES (?,?) ON CONFLICT DO NOTHING", space, space);
        execute(connection, "INSERT INTO starexec.user_assoc(user_id,space_id) VALUES (?,?)", owner, space);
        var path = Files.createDirectory(files.resolve("processor-" + type));
        return (int) number(connection, "INSERT INTO starexec.processors(name,path,community,processor_type,disk_size) VALUES ('processor',?,?,?,15) RETURNING id", path.toString(), space, type);
    }

    @Test void preAndPostProcessorsUseExistingCommunityPermissions() throws Exception {
        int pre = processor(1), post = processor(2);
        execute(connection, "UPDATE starexec.job_stage_params SET pre_processor=?,post_processor=? WHERE job_id=?", pre, post, job);
        assertEquals(4, capture().files().size()); assertEquals(2, payload().getAsJsonArray("processors").size());
        execute(connection, "DELETE FROM starexec.user_assoc WHERE user_id=?", owner); refused();
    }

    @Test void theWrongProcessorTypeCannotBeSmuggledIntoAStage() throws Exception {
        execute(connection, "UPDATE starexec.job_stage_params SET pre_processor=? WHERE job_id=?", processor(2), job); refused();
    }

    @ParameterizedTest @ValueSource(strings = {"relative/input", "/", "/a/../b"})
    void unconfinedSourcePathsAreRefused(String path) throws Exception {
        if ("/".equals(path)) {
            assertCatalogPathRejected(path);
            return;
        }
        execute(connection, "UPDATE starexec.benchmarks SET path=? WHERE id=?", path, bench); refused();
    }

    @Test void anInputCannotAliasTheClaimedWritableResource() throws Exception {
        assertCatalogPathRejected(resource.root().resolve("input").toString());
    }

    private String benchmarkPath() throws SQLException {
        try (var query = connection.prepareStatement("SELECT path FROM starexec.benchmarks WHERE id=?")) {
            query.setInt(1, bench);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private void assertCatalogPathRejected(String path) throws SQLException {
        String original = benchmarkPath();
        var failure = assertThrows(SQLException.class,
                () -> execute(connection, "UPDATE starexec.benchmarks SET path=? WHERE id=?", path, bench));
        assertEquals("23514", failure.getSQLState());
        assertTrue(failure.getMessage().contains("Application assets cannot reference private execution storage"));
        assertEquals(original, benchmarkPath());
        assertEquals(lease, JobStorageLeases.find(connection, pair, 1).orElseThrow());
    }

    @Test void cancelledStaleUsedOrReleasedReservationsCannotAuthorizeCapture() throws Exception {
        execute(connection, "UPDATE starexec.job_pairs SET status_code=21 WHERE id=?", pair); refused();
        execute(connection, "UPDATE starexec.job_pairs SET status_code=1 WHERE id=?", pair);
        assertTrue(JobStorageLeases.beginUse(connection, lease.token(), resource)); refused();
    }

    @Test void historicalSettledLookupNeverBecomesCapturePermission() throws Exception {
        assertTrue(JobStorageLeases.releaseUnstarted(connection, lease.token(), resource));
        var old = JobStorageLeases.find(connection, pair, 1).orElseThrow(); assertNotNull(old.releasedAt());
        assertTrue(JobExecutionInputs.capture(connection, old, resource).isEmpty()); refused();
    }

    @Test void anAttemptOrResourceIdentityChangeRefusesMetadataCapture() throws Exception {
        when(resource.identity()).thenReturn("different"); refused();
        when(resource.identity()).thenReturn(lease.resourceIdentity());
        execute(connection, "UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?", pair); refused();
    }

    @Test void foreignTransactionsRemainUntouched() throws Exception {
        int isolation = connection.getTransactionIsolation(); connection.setAutoCommit(false);
        assertThrows(SQLException.class, this::capture); assertFalse(connection.getAutoCommit());
        assertEquals(isolation, connection.getTransactionIsolation()); connection.rollback(); connection.setAutoCommit(true);
    }

    @Test void aFailedCommitCannotReturnASnapshotAsVerified() throws Exception {
        var proxy = mock(Connection.class, AdditionalAnswers.delegatesTo(connection));
        doThrow(new SQLException("commit failed")).when(proxy).commit();
        assertThrows(SQLException.class, () -> JobExecutionInputs.capture(proxy, lease, resource));
        assertTrue(connection.getAutoCommit()); assertEquals(lease, JobStorageLeases.find(connection, pair, 1).orElseThrow());
    }

    @Test void captureFailuresRestoreConnectionIsolationAndKeepOwnership() throws Exception {
        int isolation = connection.getTransactionIsolation();
        doThrow(new java.io.IOException("resource incarnation changed")).when(resource).requireUnchanged();
        assertThrows(SQLException.class, this::capture);
        assertTrue(connection.getAutoCommit()); assertEquals(isolation, connection.getTransactionIsolation());
        assertEquals(lease, JobStorageLeases.find(connection, pair, 1).orElseThrow());
    }

    @Test void rollbackFailureIsNeverConvertedToAnOrdinaryRefusal() throws Exception {
        var proxy = mock(Connection.class, AdditionalAnswers.delegatesTo(connection));
        execute(connection, "UPDATE starexec.job_pairs SET status_code=21 WHERE id=?", pair);
        doAnswer(call -> { connection.rollback(); throw new SQLException("rollback reply lost"); }).when(proxy).rollback();
        var failure = assertThrows(SQLException.class, () -> JobExecutionInputs.capture(proxy, lease, resource));
        assertEquals(1, failure.getSuppressed().length);
    }

    @Test void aSerializableCallerKeepsItsStrongerDefault() throws Exception {
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        assertNotNull(capture()); assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection.getTransactionIsolation());
    }

    @Test void concurrentMetadataChangesDoNotMixOldAndNewInputs() throws Exception {
        var changed = new AtomicBoolean();
        var proxy = mock(Connection.class, AdditionalAnswers.delegatesTo(connection));
        doAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("FROM starexec.configurations") && changed.compareAndSet(false, true)) {
                try (var other = Common.getConnection()) {
                    execute(other, "UPDATE starexec.benchmarks SET name='new benchmark' WHERE id=?", bench);
                    execute(other, "UPDATE starexec.configurations SET name='new config' WHERE id=?", config);
                }
            }
            return connection.prepareStatement(sql);
        }).when(proxy).prepareStatement(anyString());
        var snapshot = JobExecutionInputs.capture(proxy, lease, resource).orElseThrow();
        var data = JsonParser.parseString(snapshot.payload()).getAsJsonObject();
        assertTrue(changed.get());
        assertEquals("primary", data.getAsJsonArray("benchmarks").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("default", data.getAsJsonArray("stages").get(0).getAsJsonObject().getAsJsonObject("configuration").get("name").getAsString());
        var current = payload();
        assertEquals("new benchmark", current.getAsJsonArray("benchmarks").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("new config", current.getAsJsonArray("stages").get(0).getAsJsonObject().getAsJsonObject("configuration").get("name").getAsString());
    }
}
