package org.starexec.test.junit.database;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.backend.storage.BuildExecutionRuntime;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.Common;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;
import org.starexec.jobs.SolverBuildCleanup;
import org.starexec.test.util.DatabaseTestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real database exclusivity with explicitly synthetic provider capacity and stop observations. */
class ExecutionStorageLeaseSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private DedicatedStorageResource resource;
    private Path slot;
    private Path outputs;
    private BuildExecutionContext build;
    private String key;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("ExecutionStorageLeaseSqlTest");
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

    private Path directory(String name) throws IOException {
        return Files.createDirectory(files.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private BuildExecutionContext plan(String name, Path root, long budget) throws Exception {
        int user = (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Lease','synthetic',now(),'synthetic',10000,100,20) RETURNING id",
                "lease-" + UUID.randomUUID() + "@example.invalid");
        Path source = directory(name + "_src");
        Files.writeString(source.resolve("starexec_build"), "original");
        int solver = (int) number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?, ?,now(),?,20,0) RETURNING id", user, name, source.toString());
        int job = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                + " VALUES (?,'build',1,0,true) RETURNING id", user);
        int pair = (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id", job);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", pair, solver);
        var artifact = SolverBuildArtifacts.find(connection, pair, 1).orElseThrow();
        UUID token = UUID.randomUUID();
        var context = new BuildExecutionContext(artifact.id(), pair, 1, token, source, root.resolve("build-" + token));
        Common.runTransactional(connection, tx -> {
            assertTrue(SolverBuildArtifacts.prepare(tx, pair, 1, token, context.staging()));
            assertTrue(SolverBuildArtifacts.reserveStorageBytes(tx, context, "STAGING", context.staging(), budget));
            return true;
        });
        return context;
    }

    @BeforeEach void seed() throws Exception {
        connection = Common.getConnection();
        slot = directory("slot");
        outputs = directory("outputs");
        key = UUID.randomUUID().toString().replace("-", "");
        resource = mock(DedicatedStorageResource.class);
        when(resource.root()).thenReturn(slot);
        when(resource.filesystemKey()).thenReturn(key);
        when(resource.identity()).thenReturn("observed-device-and-mount");
        when(resource.capacityBytes()).thenReturn(64L);
        when(resource.inodeLimit()).thenReturn(16L);
        build = plan("first", slot, 128);
        assertTrue(ExecutionStorageLeases.register(connection, resource));
    }

    @AfterEach void closeConnection() throws Exception { if (connection != null) connection.close(); }

    private long leases() throws SQLException {
        return number(connection, "SELECT count(*) FROM starexec.solver_build_storage_leases WHERE resource_key=? AND released_at IS NULL", key);
    }

    private String engine() { return "podman:7:owned-storage-build"; }

    private BuildExecutionRuntime.Observation runtime() {
        Map<String,String> namespaces=new LinkedHashMap<>();
        for (String name:java.util.List.of("pid","ipc","net","mnt","user","cgroup")) namespaces.put(name,name+":[123]");
        return new BuildExecutionRuntime.Observation(456,64,16,"0",namespaces);
    }

    private String runtimePayload() {
        return new Gson().toJson(Map.of("version",1,"observation",runtime()));
    }

    private boolean claimRelease(Connection con, BuildExecutionContext context, UUID request, String payload) throws SQLException {
        return number(con,"SELECT starexec.claim_solver_build_release(?,?,?,?,?,?,?,?,?,?,?)::integer",
                context.artifactId(),context.pairId(),context.attemptNo(),context.token(),context.source().toString(),
                context.staging().toString(),key,resource.identity(),engine(),request,payload)==1;
    }

    @Test void heldBuildReleaseCanBeConsumedOnlyOnce() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        UUID request=UUID.randomUUID();
        assertTrue(claimRelease(connection,build,request,runtimePayload()));
        assertFalse(claimRelease(connection,build,request,runtimePayload()));
        assertFalse(claimRelease(connection,build,UUID.randomUUID(),runtimePayload()));
    }

    private long decisions() throws SQLException {
        return number(connection,"SELECT count(*) FROM starexec.solver_build_release_decisions WHERE artifact_id=?",build.artifactId());
    }

    @Test void aCommittedDecisionSurvivesConnectionCloseWithoutGrantingAnotherPermit() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        UUID request=UUID.randomUUID();
        assertTrue(ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),request,runtime()));
        assertTrue(connection.getAutoCommit());
        try (Connection observer=Common.getConnection()) {
            var receipt=ExecutionStorageLeases.findReleaseDecision(observer,build,resource).orElseThrow();
            assertEquals(request,receipt.requestToken());assertEquals(engine(),receipt.engineIdentity());
            assertEquals(runtimePayload(),receipt.runtimePayload());
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(receipt.runtimePayload().getBytes(java.nio.charset.StandardCharsets.UTF_8))),receipt.runtimeSha256());
            assertFalse(ExecutionStorageLeases.claimRelease(observer,build,resource,engine(),request,runtime()));
        }
        assertEquals(1,decisions());assertEquals(1,leases());
        assertFalse(ExecutionStorageLeases.release(connection,build,resource));
    }

    @Test void releaseCannotManufactureALeaseOrAnEngineBinding() throws Exception {
        assertFalse(claimRelease(connection,build,UUID.randomUUID(),runtimePayload()));
        assertEquals(0,leases());
        assertTrue(SolverBuildArtifacts.bindExecution(connection,build.pairId(),1,build.token(),engine()));
        assertFalse(claimRelease(connection,build,UUID.randomUUID(),runtimePayload()));
        assertEquals(0,leases());assertEquals(0,decisions());
    }

    @Test void anUnboundLeaseCannotBeMadeExecutableByAReleaseClaim() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection,build,resource));
        assertFalse(ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),UUID.randomUUID(),runtime()));
        assertNull(SolverBuildArtifacts.find(connection,build.pairId(),1).orElseThrow().engineIdentity());
        assertEquals(0,decisions());
    }

    @ParameterizedTest @ValueSource(strings={"artifact","pair","attempt","token","source","staging"})
    void aReleaseDecisionCannotBorrowAnotherExecutionContext(String changed) throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        var context=new BuildExecutionContext(changed.equals("artifact")?build.artifactId()+1:build.artifactId(),
                changed.equals("pair")?build.pairId()+1:build.pairId(),changed.equals("attempt")?2:1,
                changed.equals("token")?UUID.randomUUID():build.token(),
                changed.equals("source")?files.resolve("other_src"):build.source(),
                changed.equals("staging")?slot.resolve("other"):build.staging());
        assertFalse(ExecutionStorageLeases.claimRelease(connection,context,resource,engine(),UUID.randomUUID(),runtime()));
        assertTrue(ExecutionStorageLeases.findReleaseDecision(connection,context,resource).isEmpty());
        assertEquals(0,decisions());assertTrue(connection.getAutoCommit());
    }

    @ParameterizedTest @ValueSource(strings={"cancelled","deleted-job","deleted-solver","recycled","generation","attempt","stopped"})
    void revokedAdmissionCannotAcquireAReleaseDecision(String changed) throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        switch (changed) {
            case "cancelled" -> execute(connection,"UPDATE starexec.jobs SET killed=true WHERE id=(SELECT job_id FROM starexec.job_pairs WHERE id=?)",build.pairId());
            case "deleted-job" -> execute(connection,"UPDATE starexec.jobs SET deleted=true WHERE id=(SELECT job_id FROM starexec.job_pairs WHERE id=?)",build.pairId());
            case "deleted-solver" -> execute(connection,"UPDATE starexec.solvers SET deleted=true WHERE id=(SELECT solver_id FROM starexec.solver_build_artifacts WHERE id=?)",build.artifactId());
            case "recycled" -> execute(connection,"UPDATE starexec.solvers SET recycled=true WHERE id=(SELECT solver_id FROM starexec.solver_build_artifacts WHERE id=?)",build.artifactId());
            case "generation" -> execute(connection,"UPDATE starexec.solvers SET build_generation=build_generation+1 WHERE id=(SELECT solver_id FROM starexec.solver_build_artifacts WHERE id=?)",build.artifactId());
            case "attempt" -> execute(connection,"UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?",build.pairId());
            case "stopped" -> assertTrue(SolverBuildArtifacts.recordStopped(connection,build.pairId(),1,build.token(),engine()));
        }
        assertFalse(ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),UUID.randomUUID(),runtime()));
        assertEquals(0,decisions());assertEquals(1,leases());assertTrue(connection.getAutoCommit());
    }

    @ParameterizedTest @ValueSource(strings={"null","json","extra","duplicate","version","ticks","coerced","cpu","namespace","large"})
    void malformedRuntimeReceiptsCannotConsumeRelease(String changed) throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        JsonObject object=new Gson().fromJson(runtimePayload(),JsonObject.class);
        JsonObject observed=object.getAsJsonObject("observation");
        String payload=null;
        switch (changed) {
            case "null" -> { }
            case "json" -> payload="[]";
            case "extra" -> object.addProperty("foreign",1);
            case "duplicate" -> payload=runtimePayload().substring(0,runtimePayload().length()-1)+",\"version\":1}";
            case "version" -> object.addProperty("version",2);
            case "ticks" -> observed.addProperty("startTicks",-1);
            case "coerced" -> observed.addProperty("pids","16");
            case "cpu" -> observed.addProperty("cpus","0,0");
            case "namespace" -> observed.getAsJsonObject("namespaces").addProperty("pid","mnt:[123]");
            case "large" -> payload=" ".repeat(8193);
        }
        if (payload==null && !changed.equals("null")) payload=object.toString();
        assertFalse(claimRelease(connection,build,UUID.randomUUID(),payload));
        assertEquals(0,decisions());
        assertTrue(ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),UUID.randomUUID(),runtime()));
    }

    @ParameterizedTest @ValueSource(strings={"podman:7:foreign","local:7:unisolated",""})
    void releaseCannotSubstituteAnotherOrUnisolatedEngine(String engine) throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        assertFalse(ExecutionStorageLeases.claimRelease(connection,build,resource,engine,UUID.randomUUID(),runtime()));
        assertEquals(0,decisions());
    }

    @Test void twoControllersHaveOnlyOneCommittedReleaseWinner() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try {
            var tasks=java.util.stream.IntStream.range(0,2).mapToObj(i->pool.submit(()->{
                try (Connection observer=Common.getConnection()) {
                    ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));
                    try { return ExecutionStorageLeases.claimRelease(observer,build,resource,engine(),UUID.randomUUID(),runtime()); }
                    catch (SQLException busy) { assertEquals("55P03",busy.getSQLState());return false; }
                }
            })).toList();
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            int winners=0;for (var task:tasks) if (task.get(10,TimeUnit.SECONDS)) winners++;
            assertEquals(1,winners);assertEquals(1,decisions());
        } finally { go.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
    }

    @Test void capacityChangeAfterInsertRollsBackTheReleaseDecision() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        doNothing().doThrow(new IOException("INJECTED POST-CLAIM CAPACITY CHANGE")).when(resource).requireUnchanged();
        assertThrows(SQLException.class,()->ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),UUID.randomUUID(),runtime()));
        assertEquals(0,decisions());assertEquals(1,leases());assertTrue(connection.getAutoCommit());
    }

    @Test void aFailedReleaseCommitDoesNotConsumeItsPermit() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        String trigger="release_commit_failure_"+UUID.randomUUID().toString().replace("-","");
        execute(connection,"CREATE FUNCTION starexec."+trigger+"() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'INJECTED RELEASE COMMIT FAILURE'; END; $$ LANGUAGE plpgsql");
        execute(connection,"CREATE CONSTRAINT TRIGGER "+trigger+" AFTER INSERT ON starexec.solver_build_release_decisions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW"
                +" WHEN (NEW.artifact_id="+build.artifactId()+") EXECUTE FUNCTION starexec."+trigger+"()");
        UUID request=UUID.randomUUID();
        try {
            SQLException failure=assertThrows(SQLException.class,()->ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),request,runtime()));
            assertTrue(failure.getMessage().contains("INJECTED RELEASE COMMIT FAILURE"));
            assertEquals(0,decisions());assertTrue(connection.getAutoCommit());
        } finally {
            execute(connection,"DROP TRIGGER "+trigger+" ON starexec.solver_build_release_decisions");
            execute(connection,"DROP FUNCTION starexec."+trigger+"()");
        }
        assertTrue(ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),request,runtime()));
    }

    @Test void aLostCommitReplyCannotGrantAnotherReleaseAfterRecovery() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        Connection fault=mock(Connection.class,AdditionalAnswers.delegatesTo(connection));
        doAnswer(call->{connection.commit();throw new SQLException("INJECTED LOST COMMIT REPLY");}).when(fault).commit();
        UUID request=UUID.randomUUID();
        SQLException failure=assertThrows(SQLException.class,()->ExecutionStorageLeases.claimRelease(fault,build,resource,engine(),request,runtime()));
        assertTrue(failure.getMessage().contains("INJECTED LOST COMMIT REPLY"));assertEquals(1,decisions());
        assertTrue(connection.getAutoCommit());
        try (Connection observer=Common.getConnection()) {
            assertEquals(request,ExecutionStorageLeases.findReleaseDecision(observer,build,resource).orElseThrow().requestToken());
            assertFalse(ExecutionStorageLeases.claimRelease(observer,build,resource,engine(),request,runtime()));
            assertFalse(ExecutionStorageLeases.claimRelease(observer,build,resource,engine(),UUID.randomUUID(),runtime()));
        }
    }

    @Test void refusedReleaseDoesNotHideARollbackFailure() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        Connection fault=mock(Connection.class,AdditionalAnswers.delegatesTo(connection));
        doAnswer(call->{connection.rollback();throw new SQLException("INJECTED RELEASE ROLLBACK FAILURE");}).when(fault).rollback();
        SQLException failure=assertThrows(SQLException.class,()->ExecutionStorageLeases.claimRelease(fault,build,resource,"local:7:refused",UUID.randomUUID(),runtime()));
        assertTrue(java.util.Arrays.stream(failure.getSuppressed()).anyMatch(e->e.getMessage().contains("INJECTED RELEASE ROLLBACK FAILURE")));
        assertEquals(0,decisions());assertTrue(connection.getAutoCommit());
    }

    @ParameterizedTest @ValueSource(strings={"delete","token","engine","runtime","time"})
    void releaseConsumptionHistoryCannotBeErasedOrRedirected(String operation) throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        assertTrue(ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),UUID.randomUUID(),runtime()));
        String sql=switch (operation) {
            case "delete" -> "DELETE FROM starexec.solver_build_release_decisions WHERE artifact_id=?";
            case "token" -> "UPDATE starexec.solver_build_release_decisions SET request_token='00000000-0000-0000-0000-000000000001' WHERE artifact_id=?";
            case "engine" -> "UPDATE starexec.solver_build_release_decisions SET engine_identity='podman:7:other' WHERE artifact_id=?";
            case "runtime" -> "UPDATE starexec.solver_build_release_decisions SET runtime_payload='{}' WHERE artifact_id=?";
            default -> "UPDATE starexec.solver_build_release_decisions SET requested_at=requested_at+interval '1 second' WHERE artifact_id=?";
        };
        assertThrows(SQLException.class,()->execute(connection,sql,build.artifactId()));
        assertEquals(1,decisions());
    }

    @Test void aReleaseCannotCommitOrRollbackAForeignTransaction() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        connection.setAutoCommit(false);
        try {
            assertThrows(SQLException.class,()->ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),UUID.randomUUID(),runtime()));
            assertEquals(0,decisions());assertFalse(connection.getAutoCommit());
        } finally { connection.rollback();connection.setAutoCommit(true); }
    }

    @Test void aConfirmedCommitKeepsItsOnePermitWhenTheConnectionIsDiscarded() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        Connection fault=mock(Connection.class,AdditionalAnswers.delegatesTo(connection));
        doAnswer(call->{connection.setAutoCommit(true);throw new SQLException("INJECTED RELEASE RESTORE FAILURE");})
                .when(fault).setAutoCommit(true);
        doThrow(new SQLException("SYNTHETIC POOL WRAPPER")).when(fault).unwrap(org.apache.tomcat.jdbc.pool.PooledConnection.class);
        doNothing().when(fault).abort(any());
        UUID request=UUID.randomUUID();
        assertTrue(ExecutionStorageLeases.claimRelease(fault,build,resource,engine(),request,runtime()));
        verify(fault).abort(any());assertEquals(1,decisions());
        assertFalse(ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),request,runtime()));
        assertEquals(request,ExecutionStorageLeases.findReleaseDecision(connection,build,resource).orElseThrow().requestToken());
    }

    @Test void theSameRequestTokenCannotAuthorizeAnotherResource() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        UUID request=UUID.randomUUID();
        assertTrue(ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),request,runtime()));
        Path otherRoot=directory("second-slot");
        var otherResource=mock(DedicatedStorageResource.class);
        when(otherResource.root()).thenReturn(otherRoot);
        when(otherResource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-",""));
        when(otherResource.identity()).thenReturn("other-private-resource");
        when(otherResource.capacityBytes()).thenReturn(64L);when(otherResource.inodeLimit()).thenReturn(16L);
        var other=plan("second",otherRoot,128);
        assertTrue(ExecutionStorageLeases.register(connection,otherResource));
        assertTrue(ExecutionStorageLeases.admit(connection,other,otherResource,engine()));
        assertFalse(ExecutionStorageLeases.claimRelease(connection,other,otherResource,engine(),request,runtime()));
        assertTrue(ExecutionStorageLeases.findReleaseDecision(connection,other,otherResource).isEmpty());
        assertTrue(ExecutionStorageLeases.claimRelease(connection,other,otherResource,engine(),UUID.randomUUID(),runtime()));
        assertEquals(1,decisions());
    }

    @Test void settledCleanupAndALaterLeaseCannotResurrectAnOldRelease() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        UUID request=UUID.randomUUID();
        assertTrue(ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),request,runtime()));
        cleaned();assertTrue(ExecutionStorageLeases.release(connection,build,resource));
        var later=plan("later",slot,128);
        assertTrue(ExecutionStorageLeases.reserve(connection,later,resource));
        assertFalse(ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),UUID.randomUUID(),runtime()));
        assertEquals(1,decisions());assertEquals(1,leases());
        assertEquals(request,ExecutionStorageLeases.findReleaseDecision(connection,build,resource).orElseThrow().requestToken());
    }

    @ParameterizedTest @ValueSource(strings={"request","observation","key","identity"})
    void anIncompleteOrChangedResourceCannotAcquireRelease(String changed) throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine()));
        if (changed.equals("key")) when(resource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-",""));
        if (changed.equals("identity")) when(resource.identity()).thenReturn("changed-physical-resource");
        assertFalse(ExecutionStorageLeases.claimRelease(connection,build,resource,engine(),
                changed.equals("request")?null:UUID.randomUUID(),changed.equals("observation")?null:runtime()));
        assertEquals(0,decisions());assertTrue(connection.getAutoCommit());
    }

    private void stopped() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection, build, resource, engine()));
        Files.createDirectory(build.staging());
        Files.writeString(build.staging().resolve("build.log"), "saved");
        JsonObject facts = new JsonObject(); facts.addProperty("status", "exited");
        var receipt = SolverBuildStopReceipt.observed("podman", build.artifactId(), build.pairId(), 1,
                engine(), "owned-storage-build", Instant.now(), facts);
        assertTrue(SolverBuildArtifacts.recordStopReceipt(connection, build.pairId(), 1, build.token(), engine(), receipt));
    }

    private void cleaned() throws Exception {
        stopped();
        var artifact = SolverBuildArtifacts.find(connection, build.pairId(), 1).orElseThrow();
        execute(connection, "DELETE FROM starexec.jobs WHERE id=(SELECT job_id FROM starexec.job_pairs WHERE id=?)", build.pairId());
        execute(connection, "UPDATE starexec.solvers SET deleted=true WHERE id=?", artifact.solverId());
        assertTrue(SolverBuildCleanup.purge(connection, build.artifactId(), slot, outputs, files, 100));
        assertFalse(Files.exists(build.staging()));
    }

    @Test void resourceRegistrationAndLeaseRetriesPreserveHistoryAndBookedUsage() throws Exception {
        long created = number(connection, "SELECT (extract(epoch from created_at)*1000000)::bigint FROM starexec.execution_storage_resources WHERE resource_key=?", key);
        assertTrue(ExecutionStorageLeases.register(connection, resource));
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        long leased = number(connection, "SELECT (extract(epoch from created_at)*1000000)::bigint FROM starexec.solver_build_storage_leases WHERE artifact_id=?", build.artifactId());
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        assertEquals(1, leases());
        assertEquals(148, number(connection, "SELECT disk_size FROM starexec.users WHERE id=(SELECT origin_owner_id FROM starexec.solver_build_artifacts WHERE id=?)", build.artifactId()));
        assertEquals(created, number(connection, "SELECT (extract(epoch from created_at)*1000000)::bigint FROM starexec.execution_storage_resources WHERE resource_key=?", key));
        assertEquals(leased, number(connection, "SELECT (extract(epoch from created_at)*1000000)::bigint FROM starexec.solver_build_storage_leases WHERE artifact_id=?", build.artifactId()));
    }

    @ParameterizedTest @ValueSource(strings = {"root", "identity", "bytes", "inodes", "key"})
    void registrationCannotRedirectOrResizeAnExistingResource(String changed) throws Exception {
        switch (changed) {
            case "root" -> when(resource.root()).thenReturn(directory("other"));
            case "identity" -> when(resource.identity()).thenReturn("different-mount");
            case "bytes" -> when(resource.capacityBytes()).thenReturn(32L);
            case "inodes" -> when(resource.inodeLimit()).thenReturn(8L);
            case "key" -> when(resource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-", ""));
            default -> throw new AssertionError(changed);
        }
        assertFalse(ExecutionStorageLeases.register(connection, resource));
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.execution_storage_resources WHERE resource_key=? AND root_path=? AND capacity_bytes=64 AND inode_limit=16", key, slot.toString()));
        assertEquals(0, leases());
    }

    @ParameterizedTest @ValueSource(strings = {"artifact", "pair", "attempt", "token", "source", "staging"})
    void aLeaseCannotBorrowAnotherBuildsIdentityOrAllowance(String changed) throws Exception {
        var context = new BuildExecutionContext(changed.equals("artifact") ? build.artifactId()+1 : build.artifactId(),
                changed.equals("pair") ? build.pairId()+1 : build.pairId(), changed.equals("attempt") ? 2 : 1,
                changed.equals("token") ? UUID.randomUUID() : build.token(),
                changed.equals("source") ? files.resolve("other_src") : build.source(),
                changed.equals("staging") ? slot.resolve("other") : build.staging());
        assertFalse(ExecutionStorageLeases.reserve(connection, context, resource));
        assertEquals(0, leases());
    }

    @ParameterizedTest @ValueSource(longs = {0, 32})
    void aResourceLargerThanTheBookedBudgetIsRefused(long budget) throws Exception {
        BuildExecutionContext small = plan("small", slot, budget);
        assertFalse(ExecutionStorageLeases.reserve(connection, small, resource));
        assertEquals(0, leases());
    }

    @Test void aResourceCannotEncloseItsBuildSource() throws Exception {
        var enclosed = plan("enclosed", files, 128);
        var observation = mock(DedicatedStorageResource.class);
        when(observation.root()).thenReturn(files);
        when(observation.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-", ""));
        when(observation.identity()).thenReturn("other-observed-filesystem");
        when(observation.capacityBytes()).thenReturn(64L);
        when(observation.inodeLimit()).thenReturn(16L);
        SQLException refused=assertThrows(SQLException.class,()->ExecutionStorageLeases.register(connection, observation));
        assertEquals("23514",refused.getSQLState());
        assertFalse(ExecutionStorageLeases.reserve(connection, enclosed, observation));
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.solver_build_storage_leases WHERE artifact_id=?", enclosed.artifactId()));
    }

    @Test void aChangedObservationIsNotLaunchPermission() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        doThrow(new IOException("INJECTED RESOURCE CHANGE")).when(resource).requireUnchanged();
        SQLException refused = assertThrows(SQLException.class, () -> ExecutionStorageLeases.admit(connection, build, resource, engine()));
        assertInstanceOf(IOException.class, refused.getCause());
        assertEquals(1, leases());
        assertNull(SolverBuildArtifacts.find(connection, build.pairId(), 1).orElseThrow().engineIdentity());
        assertTrue(connection.getAutoCommit());
    }

    @Test void aCurrentAttemptChangeCannotReuseTheLease() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        execute(connection, "UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?", build.pairId());
        assertFalse(ExecutionStorageLeases.admit(connection, build, resource, engine()));
        assertEquals(1, leases());
    }

    @Test void anActiveResourceCannotBeLeasedByAnotherBuildEvenAfterConnectionClose() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        BuildExecutionContext other = plan("other", slot, 128);
        connection.close(); connection = Common.getConnection();
        assertFalse(ExecutionStorageLeases.reserve(connection, other, resource));
        assertEquals(1, leases());
        assertTrue(ExecutionStorageLeases.admit(connection, build, resource, engine()));
        assertTrue(ExecutionStorageLeases.admit(connection, build, resource, engine()));
        assertFalse(ExecutionStorageLeases.admit(connection, build, resource, engine()+"-other"));
    }

    @Test void concurrentBuildsCannotBothLeaseTheSameFilesystem() throws Exception {
        BuildExecutionContext other = plan("other", slot, 128);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try {
            var tasks = java.util.List.of(build, other).stream().map(context -> pool.submit(() -> {
                try (Connection observer = Common.getConnection()) {
                    ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                    try { return ExecutionStorageLeases.reserve(observer, context, resource); }
                    catch (SQLException busy) {
                        assertEquals("55P03", busy.getSQLState(), () -> busy+"; cause="+busy.getCause()
                                +"; suppressed="+java.util.Arrays.toString(busy.getSuppressed()));
                        return false;
                    }
                }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            int accepted=0; for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted); assertEquals(1, leases());
        } finally { go.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void resourceContentionRefusesWithoutPartialLeaseOrEngineBinding() throws Exception {
        try (Connection competing = Common.getConnection()) {
            competing.setAutoCommit(false);
            try {
                number(competing, "SELECT 1 FROM starexec.execution_storage_resources WHERE resource_key=? FOR UPDATE", key);
                SQLException busy = assertThrows(SQLException.class, () -> ExecutionStorageLeases.admit(connection, build, resource, engine()));
                assertEquals("55P03", busy.getSQLState()); assertEquals(0, leases());
                assertNull(SolverBuildArtifacts.find(connection, build.pairId(), 1).orElseThrow().engineIdentity());
            } finally { competing.rollback(); competing.setAutoCommit(true); }
        }
        assertTrue(ExecutionStorageLeases.admit(connection, build, resource, engine()));
    }

    @Test void failedEngineAdmissionRollsBackANewLease() throws Exception {
        assertFalse(ExecutionStorageLeases.admit(connection, build, resource, ""));
        assertEquals(0, leases()); assertTrue(connection.getAutoCommit());
        assertNull(SolverBuildArtifacts.find(connection, build.pairId(), 1).orElseThrow().engineIdentity());
    }

    @Test void aFailedCommitRollsBackBothTheNewLeaseAndEngineBinding() throws Exception {
        String trigger = "lease_commit_failure_" + UUID.randomUUID().toString().replace("-", "");
        execute(connection, "CREATE FUNCTION starexec." + trigger + "() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'INJECTED LEASE COMMIT FAILURE'; END; $$ LANGUAGE plpgsql");
        execute(connection, "CREATE CONSTRAINT TRIGGER " + trigger + " AFTER INSERT ON starexec.solver_build_storage_leases DEFERRABLE INITIALLY DEFERRED FOR EACH ROW"
                + " WHEN (NEW.artifact_id=" + build.artifactId() + ") EXECUTE FUNCTION starexec." + trigger + "()");
        try {
            SQLException failure = assertThrows(SQLException.class, () -> ExecutionStorageLeases.admit(connection, build, resource, engine()));
            assertTrue(failure.getMessage().contains("INJECTED LEASE COMMIT FAILURE"));
            assertEquals(0, leases()); assertTrue(connection.getAutoCommit());
            assertNull(SolverBuildArtifacts.find(connection, build.pairId(), 1).orElseThrow().engineIdentity());
            assertEquals(148, number(connection, "SELECT disk_size FROM starexec.users WHERE id=(SELECT origin_owner_id FROM starexec.solver_build_artifacts WHERE id=?)", build.artifactId()));
        } finally {
            execute(connection, "DROP TRIGGER " + trigger + " ON starexec.solver_build_storage_leases");
            execute(connection, "DROP FUNCTION starexec." + trigger + "()");
        }
        assertTrue(ExecutionStorageLeases.admit(connection, build, resource, engine()));
    }

    @Test void anExistingUnleasedEngineCannotAcquireAPostHocLease() throws Exception {
        assertTrue(SolverBuildArtifacts.bindExecution(connection, build.pairId(), 1, build.token(), engine()));
        assertFalse(ExecutionStorageLeases.reserve(connection, build, resource)); assertEquals(0, leases());
    }

    @Test void releaseRequiresRegisteredCleanupEvenWhenTheFilesystemLooksEmpty() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        assertFalse(ExecutionStorageLeases.release(connection, build, resource)); assertEquals(1, leases());
        assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE starexec.solver_build_storage_leases SET released_at=clock_timestamp() WHERE artifact_id=?", build.artifactId()));
    }

    @Test void settledCleanupAndPositiveEmptyObservationReleaseOnceAndPermitReuse() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource)); cleaned();
        assertEquals(1, leases());
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.execution_storage_claims WHERE resource_key=? AND released_at IS NULL", key));
        assertTrue(ExecutionStorageLeases.release(connection, build, resource)); assertEquals(0, leases());
        assertEquals(0, number(connection, "SELECT count(*) FROM starexec.execution_storage_claims WHERE resource_key=? AND released_at IS NULL", key));
        assertEquals(1, number(connection, "SELECT count(*) FROM starexec.execution_storage_claims c JOIN starexec.solver_build_storage_leases l"
                + " ON l.artifact_id=c.build_artifact_id WHERE l.artifact_id=? AND c.released_at=l.released_at", build.artifactId()));
        long released = number(connection, "SELECT (extract(epoch from released_at)*1000000)::bigint FROM starexec.solver_build_storage_leases WHERE artifact_id=?", build.artifactId());
        assertTrue(ExecutionStorageLeases.release(connection, build, resource));
        assertEquals(released, number(connection, "SELECT (extract(epoch from released_at)*1000000)::bigint FROM starexec.solver_build_storage_leases WHERE artifact_id=?", build.artifactId()));
        var other = plan("other", slot, 128);
        assertTrue(ExecutionStorageLeases.reserve(connection, other, resource)); assertEquals(1, leases());
        assertFalse(ExecutionStorageLeases.admit(connection, build, resource, engine()));
    }

    @Test void residualBytesKeepTheResourceUnavailableAfterAccountingSettlement() throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource)); cleaned();
        Path leftover = Files.writeString(slot.resolve("leftover"), "owned-test-byte");
        assertThrows(SQLException.class, () -> ExecutionStorageLeases.release(connection, build, resource));
        assertEquals(1, leases()); assertTrue(Files.exists(leftover));
        Files.delete(leftover);
        assertTrue(ExecutionStorageLeases.release(connection, build, resource));
    }

    @ParameterizedTest @ValueSource(strings = {"resource-update", "resource-delete", "lease-update", "lease-delete"})
    void resourceAndLeaseHistoryCannotBeRewritten(String operation) throws Exception {
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        String sql = switch (operation) {
            case "resource-update" -> "UPDATE starexec.execution_storage_resources SET capacity_bytes=32 WHERE resource_key=?";
            case "resource-delete" -> "DELETE FROM starexec.execution_storage_resources WHERE resource_key=?";
            case "lease-update" -> "UPDATE starexec.solver_build_storage_leases SET execution_token='00000000-0000-0000-0000-000000000001' WHERE resource_key=?";
            case "lease-delete" -> "DELETE FROM starexec.solver_build_storage_leases WHERE resource_key=?";
            default -> throw new AssertionError(operation);
        };
        assertThrows(SQLException.class, () -> execute(connection, sql, key)); assertEquals(1, leases());
    }

    @Test void aForeignTransactionIsNotCommittedOrRolledBack() throws Exception {
        connection.setAutoCommit(false);
        try {
            assertThrows(SQLException.class, () -> ExecutionStorageLeases.reserve(connection, build, resource));
            assertFalse(connection.getAutoCommit()); assertEquals(0, leases());
        } finally { connection.rollback(); connection.setAutoCommit(true); }
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
    }
}
