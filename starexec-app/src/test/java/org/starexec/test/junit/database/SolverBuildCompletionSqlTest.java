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
import org.starexec.backend.storage.BuildExecutionRuntime;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.PublishedSolverInputs;
import org.starexec.data.to.SolverBuildStopReceipt;
import org.starexec.jobs.SolverBuildRecorder;
import org.starexec.jobs.SolverBuildStaging;
import org.starexec.test.util.DatabaseTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual periodic caller, PostgreSQL and files; physical capacity and provider receipts are synthetic. */
class SolverBuildCompletionSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private int owner,solver,pair;
    private Path source,slot,store;
    private DedicatedStorageResource resource;
    private BuildExecutionContext build;
    private long originalBytes;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("SolverBuildCompletionSqlTest");Common.initialize();
    }

    private static long number(Connection con,String sql,Object...args) throws SQLException {
        try (var query=con.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) query.setObject(i+1,args[i]);
            try (var row=query.executeQuery()) { assertTrue(row.next());return row.getLong(1); }
        }
    }

    private Path directory(String name) throws Exception {
        return Files.createDirectory(files.resolve(name),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @BeforeEach void prepare() throws Exception {
        connection=Common.getConnection();source=directory("source_src");slot=directory("slot");store=directory("artifacts");
        Files.writeString(source.resolve("starexec_build"),"original source\n");
        Path helper=Files.writeString(files.resolve("starexec_build"),"original helper\n");
        originalBytes=Files.size(source.resolve("starexec_build"))+Files.size(helper);
        owner=(int)number(connection,"INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,disk_quota,job_pair_quota,disk_size)"
                +" VALUES (?,'Test','Completion','synthetic',now(),'synthetic',16777216,100,?) RETURNING id",
                "completion-"+UUID.randomUUID()+"@example.invalid",originalBytes);
        solver=(int)number(connection,"INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status) VALUES (?,'completion',now(),?,?,0) RETURNING id",
                owner,source.toString(),Files.size(source.resolve("starexec_build")));
        int bench=(int)number(connection,"INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size) VALUES (?,'starexec_build',now(),?,?) RETURNING id",
                owner,helper.toString(),Files.size(helper));
        int job=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'completion',1,0,true) RETURNING id",owner);
        pair=(int)number(connection,"INSERT INTO starexec.job_pairs(job_id,bench_id,status_code,primary_jobpair_data) VALUES (?,?,2,1) RETURNING id",job,bench);
        number(connection,"INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,2,?,0) RETURNING jobpair_id",pair,solver);
        resource=mock(DedicatedStorageResource.class);
        when(resource.root()).thenReturn(slot);when(resource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-",""));
        when(resource.identity()).thenReturn("synthetic-completion-capacity");
        when(resource.capacityBytes()).thenReturn(1048576L);when(resource.inodeLimit()).thenReturn(1024L);
        build=SolverBuildStaging.prepareLeased(connection,pair,1,resource,resource.capacityBytes());
        Path programs=Files.createDirectory(build.staging().resolve("solver"));
        Files.writeString(programs.resolve("program"),"built program\n");
        Files.writeString(Files.createDirectory(build.staging().resolve("logs")).resolve(R.SOLVER_BUILD_OUTPUT),"build log\n");
    }

    @AfterEach void close() throws Exception { if (connection!=null) connection.close(); }

    private void stopped(boolean released,int status) throws Exception {
        assertTrue(ExecutionStorageLeases.admitPodman(connection,build,resource,1001,"synthetic-container"));
        if (released) {
            var namespaces=new java.util.LinkedHashMap<String,String>();
            for (String n:List.of("pid","ipc","net","mnt","user","cgroup")) namespaces.put(n,n+":[123]");
            var observed=new BuildExecutionRuntime.Observation(456,134217728,64,"2",namespaces);
            assertTrue(ExecutionStorageLeases.claimPodmanRelease(connection,build,resource,1001,"synthetic-container",UUID.randomUUID(),observed));
        }
        JsonObject facts=new JsonObject();facts.addProperty("status","exited");
        var receipt=SolverBuildStopReceipt.observed("podman",build.artifactId(),pair,1,"podman:1001:synthetic-container","synthetic-container",Instant.now(),facts);
        assertTrue(SolverBuildArtifacts.recordStopReceipt(connection,pair,1,build.token(),"podman:1001:synthetic-container",receipt));
        number(connection,"UPDATE starexec.job_pairs SET status_code=? WHERE id=? RETURNING id",status,pair);
        number(connection,"UPDATE starexec.jobpair_stage_data SET status_code=? WHERE jobpair_id=? RETURNING jobpair_id",status,pair);
    }

    private void record(Path configured) throws Exception {
        try (var config=mockStatic(EnvironmentConfig.class,CALLS_REAL_METHODS);
             var observations=mockStatic(DedicatedStorageResource.class,CALLS_REAL_METHODS);
             var legacy=mockStatic(Solvers.class,CALLS_REAL_METHODS)) {
            config.when(EnvironmentConfig::getBuildArtifactDirectory).thenReturn(configured==null?"":configured.toString());
            observations.when(()->DedicatedStorageResource.inspect(any(Path.class),any(DedicatedStorageResource.Limits.class),anyList()))
                    .thenThrow(new java.io.IOException("Not this owned synthetic capacity"));
            observations.when(()->DedicatedStorageResource.inspect(eq(slot),any(DedicatedStorageResource.Limits.class),anyList())).thenReturn(resource);
            legacy.when(Solvers::getFinishedBuildsToRecord).thenReturn(List.of());
            SolverBuildRecorder.recordFinishedBuilds();
        }
    }

    private Path output() { return store.resolve("build-"+build.artifactId()+"-"+build.token()); }
    private long usage() throws Exception { return number(connection,"SELECT disk_size FROM starexec.users WHERE id=?",owner); }
    private long activeLeases() throws Exception {
        return number(connection,"SELECT count(*) FROM starexec.solver_build_storage_leases WHERE artifact_id=? AND released_at IS NULL",build.artifactId());
    }

    @Test void periodicRecorderPublishesAndSettlesAReleasedStoppedBuild() throws Exception {
        stopped(true,7);record(store);
        var artifact=SolverBuildArtifacts.find(connection,pair,1).orElseThrow();
        assertEquals("PUBLISHED",artifact.state());assertEquals(output().resolve("solver").toString(),artifact.solverPath());
        assertTrue(Files.isRegularFile(output().resolve("solver/program")));
        assertEquals("original source\n",Files.readString(source.resolve("starexec_build")));
        assertFalse(Files.exists(build.staging()));assertEquals(0,activeLeases());
        long outputBytes=number(connection,"SELECT byte_size FROM starexec.solver_build_artifacts WHERE id=?",build.artifactId());
        assertEquals(originalBytes+outputBytes,usage());
        record(store);assertEquals(originalBytes+outputBytes,usage());
        assertTrue(SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).isPresent());
    }

    @Test void periodicRecorderConservesFailureLogsAndPreservesTheSource() throws Exception {
        stopped(true,11);record(store);
        var artifact=SolverBuildArtifacts.find(connection,pair,1).orElseThrow();
        assertEquals("REJECTED",artifact.state());assertEquals(output().resolve("solver").toString(),artifact.solverPath());
        try (var query=connection.prepareStatement("SELECT path,build_status FROM starexec.solvers WHERE id=?")) {
            query.setInt(1,solver);
            try (var row=query.executeQuery()) {
                assertTrue(row.next());assertEquals(source.toString(),row.getString(1));assertEquals(3,row.getInt(2));
            }
        }
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND published_at IS NOT NULL",build.artifactId()));
        assertTrue(Files.isRegularFile(output().resolve("logs/"+R.SOLVER_BUILD_OUTPUT)));
        assertFalse(Files.exists(output().resolve("solver")));assertEquals(0,activeLeases());
        assertEquals("original source\n",Files.readString(source.resolve("starexec_build")));
        assertFalse(Files.exists(build.staging()));
    }

    @ParameterizedTest @ValueSource(strings={"pair","job"})
    void cancellationAfterOrdinaryStopConservesLogsWithoutRewritingReceipt(String scope) throws Exception {
        stopped(true,21);
        var receipt=SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt();
        assertFalse(receipt.facts().has("disposition"));
        if(scope.equals("job")) number(connection,"UPDATE starexec.jobs SET killed=true WHERE id="
                +"(SELECT job_id FROM starexec.job_pairs WHERE id=?) RETURNING id",pair);
        record(store);record(store);
        var artifact=SolverBuildArtifacts.find(connection,pair,1).orElseThrow();
        assertEquals("REJECTED",artifact.state());
        assertEquals(receipt.payload(),SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt().payload());
        assertEquals(receipt.sha256(),SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt().sha256());
        assertTrue(Files.isRegularFile(output().resolve("logs/"+R.SOLVER_BUILD_OUTPUT)));
        assertEquals("build log\n",Files.readString(output().resolve("logs/"+R.SOLVER_BUILD_OUTPUT)));
        assertFalse(Files.exists(output().resolve("solver")));assertFalse(Files.exists(build.staging()));
        assertEquals(0,activeLeases());assertEquals(originalBytes+"build log\n".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,usage());
        assertEquals(21,number(connection,"SELECT status_code FROM starexec.job_pairs WHERE id=?",pair));
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND published_at IS NOT NULL",build.artifactId()));
        assertEquals("original source\n",Files.readString(source.resolve("starexec_build")));
        assertEquals(source.toString(),artifact.sourcePath());
        assertEquals(0,number(connection,"SELECT build_status FROM starexec.solvers WHERE id=?",solver));
        assertEquals("build log\n",Solvers.readSolverBuildLog(solver,owner).orElseThrow());
        int other=(int)number(connection,"INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,disk_quota,job_pair_quota,disk_size)"
                +" VALUES (?,'Other','Reader','synthetic',now(),'synthetic',16777216,100,0) RETURNING id",
                "other-"+UUID.randomUUID()+"@example.invalid");
        assertTrue(Solvers.readSolverBuildLog(solver,other).isEmpty());
        assertTrue(PublishedSolverInputs.forPair(consumer(),1).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={"job-killed-failed","job-killed-success","unreleased-killed"})
    void cancellationCannotBypassReleaseOrCreateAnotherTerminalOutcome(String condition) throws Exception {
        stopped(!condition.equals("unreleased-killed"),condition.equals("job-killed-success")?7
                :condition.equals("job-killed-failed")?11:21);
        number(connection,"UPDATE starexec.jobs SET killed=true WHERE id="
                +"(SELECT job_id FROM starexec.job_pairs WHERE id=?) RETURNING id",pair);
        var receipt=SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt();long before=usage();
        record(store);
        assertEquals("PLANNED",SolverBuildArtifacts.find(connection,pair,1).orElseThrow().state());
        assertEquals(receipt.payload(),SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt().payload());
        assertEquals(before,usage());assertEquals(1,activeLeases());assertTrue(Files.exists(build.staging()));
        assertFalse(Files.exists(output()));
        assertEquals("original source\n",Files.readString(source.resolve("starexec_build")));
    }

    @ParameterizedTest @ValueSource(strings={"status","attempt"})
    void cancelledLogReadRequiresTheCurrentCancelledAttempt(String change) throws Exception {
        stopped(true,21);record(store);
        assertEquals("build log\n",Solvers.readSolverBuildLog(solver,owner).orElseThrow());
        if(change.equals("status")) number(connection,"UPDATE starexec.job_pairs SET status_code=7 WHERE id=? RETURNING id",pair);
        else try(var statement=connection.prepareStatement("SELECT starexec.RerunJobPairsBatchCore(ARRAY[?]::INTEGER[])")) {
            statement.setInt(1,pair);Common.executeAndDrain(statement);
        }
        assertThrows(SQLException.class,()->Solvers.readSolverBuildLog(solver,owner));
        assertEquals("original source\n",Files.readString(source.resolve("starexec_build")));
    }

    private int consumer() throws Exception {
        int bench=(int)number(connection,"SELECT bench_id FROM starexec.job_pairs WHERE id=?",pair);
        int job=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'consumer',1,0,false) RETURNING id",owner);
        int consumer=(int)number(connection,"INSERT INTO starexec.job_pairs(job_id,bench_id,status_code,primary_jobpair_data) VALUES (?,?,2,1) RETURNING id",job,bench);
        for (int stage:List.of(1,2)) number(connection,"INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,?,2,?,0) RETURNING jobpair_id",consumer,stage,solver);
        return consumer;
    }

    @Test void publishedInputsAreDeduplicatedAndBoundToTheCurrentAttempt() throws Exception {
        stopped(true,7);record(store);int consumer=consumer();
        var inputs=PublishedSolverInputs.forPair(consumer,1);assertEquals(1,inputs.size());
        assertEquals(SolverBuildArtifacts.find(connection,pair,1).orElseThrow(),inputs.get(0).artifact());
        assertEquals(SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).orElseThrow(),inputs.get(0).manifest());
        assertThrows(SQLException.class,()->PublishedSolverInputs.forPair(consumer,2));
        assertEquals(1,PublishedSolverInputs.forPair(consumer,1).size());
    }

    @ParameterizedTest @ValueSource(strings={"deleted","recycled"})
    void unavailablePublishedSolversCannotBecomeRunnerInputs(String column) throws Exception {
        stopped(true,7);record(store);int consumer=consumer();
        assertEquals(1,PublishedSolverInputs.forPair(consumer,1).size());
        number(connection,"UPDATE starexec.solvers SET "+column+"=true WHERE id=? RETURNING id",solver);
        assertThrows(SQLException.class,()->PublishedSolverInputs.forPair(consumer,1));
        assertEquals("PUBLISHED",SolverBuildArtifacts.find(connection,pair,1).orElseThrow().state());
        assertTrue(Files.isRegularFile(output().resolve("solver/program")));
    }

    @Test void refusedAdmissionCanCancelItsKnownCreatedResourceWithoutLaunchPermission() throws Exception {
        int job=(int)number(connection,"SELECT job_id FROM starexec.job_pairs WHERE id=?",pair);
        number(connection,"UPDATE starexec.jobs SET paused=true WHERE id=? RETURNING id",job);
        assertFalse(ExecutionStorageLeases.admitPodman(connection,build,resource,1001,"synthetic-created"));
        assertNull(SolverBuildArtifacts.find(connection,pair,1).orElseThrow().engineIdentity());
        String container="c".repeat(64),engine="podman:1001:"+container;
        JsonObject facts=new JsonObject();facts.addProperty("disposition","cancelled");facts.addProperty("reason","admission_refused");
        facts.addProperty("status","created");facts.addProperty("pid",0);facts.addProperty("running",false);
        facts.addProperty("paused",false);facts.addProperty("restarting",false);facts.addProperty("started_at","0001-01-01T00:00:00Z");
        facts.addProperty("privileged",false);facts.addProperty("private_pid_namespace",true);facts.addProperty("restart_policy","no");
        facts.addProperty("image_id","sha256:"+"a".repeat(64));
        var receipt=SolverBuildStopReceipt.observed("podman",build.artifactId(),pair,1,engine,container,Instant.now(),facts);
        assertTrue(ExecutionStorageLeases.cancelRefusedPodmanCreation(connection,build,resource,1001,container,receipt));
        assertTrue(ExecutionStorageLeases.cancelRefusedPodmanCreation(connection,build,resource,1001,container,receipt));
        number(connection,"UPDATE starexec.jobs SET paused=false WHERE id=? RETURNING id",job);
        assertFalse(ExecutionStorageLeases.admitPodman(connection,build,resource,1001,container));
        assertEquals("REJECTED",SolverBuildArtifacts.find(connection,pair,1).orElseThrow().state());
        assertEquals(receipt.payload(),SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt().payload());
        assertTrue(ExecutionStorageLeases.findReleaseDecision(connection,build,resource).isEmpty());
        record(store);record(store);
        assertEquals(originalBytes,usage());assertEquals(0,activeLeases());assertFalse(Files.exists(build.staging()));
        assertTrue(SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).isEmpty());
        assertEquals(2,number(connection,"SELECT status_code FROM starexec.job_pairs WHERE id=?",pair));
    }

    private JsonObject createdFacts() {
        JsonObject facts=new JsonObject();facts.addProperty("disposition","cancelled");facts.addProperty("reason","admission_refused");
        facts.addProperty("status","created");facts.addProperty("pid",0);facts.addProperty("running",false);facts.addProperty("paused",false);
        facts.addProperty("restarting",false);facts.addProperty("started_at","0001-01-01T00:00:00Z");facts.addProperty("privileged",false);
        facts.addProperty("private_pid_namespace",true);facts.addProperty("restart_policy","no");facts.addProperty("image_id","sha256:"+"a".repeat(64));
        return facts;
    }

    private SolverBuildStopReceipt createdReceipt(String container,JsonObject facts) {
        return SolverBuildStopReceipt.observed("podman",build.artifactId(),pair,1,"podman:1001:"+container,container,Instant.now(),facts);
    }

    @ParameterizedTest @ValueSource(strings={"reason","status","pid","pid-type","running","flag-type","started","privileged","namespace","restart","image"})
    void unverifiedCreatedCancellationCannotBindOrRefund(String condition) throws Exception {
        String container="c".repeat(64);var facts=createdFacts();
        switch(condition) {
            case "reason" -> facts.remove("reason");
            case "status" -> facts.addProperty("status","exited");
            case "pid" -> facts.addProperty("pid",44);
            case "pid-type" -> facts.addProperty("pid","0");
            case "running" -> facts.addProperty("running",true);
            case "flag-type" -> facts.addProperty("running","false");
            case "started" -> facts.addProperty("started_at","2026-01-01T00:00:00Z");
            case "privileged" -> facts.addProperty("privileged",true);
            case "namespace" -> facts.addProperty("private_pid_namespace",false);
            case "restart" -> facts.addProperty("restart_policy","always");
            case "image" -> facts.remove("image_id");
            default -> throw new AssertionError(condition);
        }
        long before=usage();
        assertFalse(ExecutionStorageLeases.cancelRefusedPodmanCreation(connection,build,resource,1001,container,createdReceipt(container,facts)));
        assertNull(SolverBuildArtifacts.find(connection,pair,1).orElseThrow().engineIdentity());
        assertTrue(SolverBuildArtifacts.findStopReceipt(connection,pair,1).isEmpty());
        assertEquals(before,usage());assertEquals(1,activeLeases());assertTrue(Files.exists(build.staging()));
    }

    @Test void refusedCancellationCannotTakeAnAlreadyAdmittedEngine() throws Exception {
        String container="c".repeat(64);
        assertTrue(ExecutionStorageLeases.admitPodman(connection,build,resource,1001,container));
        assertFalse(ExecutionStorageLeases.cancelRefusedPodmanCreation(connection,build,resource,1001,container,createdReceipt(container,createdFacts())));
        assertNull(SolverBuildArtifacts.find(connection,pair,1).orElseThrow().stoppedAt());
        assertTrue(SolverBuildArtifacts.findStopReceipt(connection,pair,1).isEmpty());assertEquals(1,activeLeases());
    }

    @Test void cancellationRefusalRollsBackItsNewBinding() throws Exception {
        String container="c".repeat(64);var receipt=createdReceipt(container,createdFacts());long before=usage();
        try (var dao=mockStatic(SolverBuildArtifacts.class,CALLS_REAL_METHODS)) {
            dao.when(()->SolverBuildArtifacts.recordCancelled(any(Connection.class),eq(pair),eq(1),eq(build.token()),anyString(),any())).thenReturn(false);
            assertFalse(ExecutionStorageLeases.cancelRefusedPodmanCreation(connection,build,resource,1001,container,receipt));
        }
        var current=SolverBuildArtifacts.find(connection,pair,1).orElseThrow();
        assertNull(current.engineIdentity());assertNull(current.stoppedAt());
        assertEquals(before,usage());assertEquals(1,activeLeases());
    }

    @Test void lostCreatedCancellationCommitIsAcknowledgedWithoutASecondWrite() throws Exception {
        String container="c".repeat(64);var receipt=createdReceipt(container,createdFacts());long before=usage();
        var uncertain=spy(connection);
        doAnswer(call->{call.callRealMethod();throw new SQLException("Lost cancellation commit response");}).when(uncertain).commit();
        assertThrows(SQLException.class,()->ExecutionStorageLeases.cancelRefusedPodmanCreation(uncertain,build,resource,1001,container,receipt));
        try (var observed=Common.getConnection()) {
            assertEquals("REJECTED",SolverBuildArtifacts.find(observed,pair,1).orElseThrow().state());
            assertEquals(receipt.payload(),SolverBuildArtifacts.findStopReceipt(observed,pair,1).orElseThrow().receipt().payload());
        }
        assertTrue(ExecutionStorageLeases.cancelRefusedPodmanCreation(connection,build,resource,1001,container,receipt));
        assertEquals(before,usage());assertEquals(1,activeLeases());
        record(store);assertEquals(originalBytes,usage());assertEquals(0,activeLeases());
        assertFalse(ExecutionStorageLeases.cancelRefusedPodmanCreation(connection,build,resource,1001,container,receipt));
        assertEquals(originalBytes,usage());assertEquals(0,activeLeases());
    }

    private SolverBuildStopReceipt conserveRefusedCreation() throws Exception {
        String container="c".repeat(64);var receipt=createdReceipt(container,createdFacts());
        assertTrue(ExecutionStorageLeases.cancelRefusedPodmanCreation(connection,build,resource,1001,container,receipt));
        return receipt;
    }

    private JobPairs.NeverStartedRetryResult retryRefused(SolverBuildStopReceipt receipt) throws Exception {
        return SolverBuildArtifacts.retryRefusedPodmanCreation(connection,build,1001,"c".repeat(64),receipt);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void refusedRecoveryCreatesOneFreshAttemptWithoutReacquiringOldStorage(boolean alreadyPurged) throws Exception {
        var receipt=conserveRefusedCreation();
        number(connection,"UPDATE starexec.jobs SET paused=true WHERE id=(SELECT job_id FROM starexec.job_pairs WHERE id=?) RETURNING id",pair);
        if (alreadyPurged) record(store);
        long before=usage();
        assertEquals(JobPairs.NeverStartedRetryResult.REQUEUED,retryRefused(receipt));
        var successor=SolverBuildArtifacts.findCurrent(connection,pair).orElseThrow();
        assertNotEquals(build.artifactId(),successor.id());assertEquals(2,successor.attemptNo());
        assertEquals("PLANNED",successor.state());assertNull(successor.executionToken());assertNull(successor.stagingPath());
        assertEquals(1,number(connection,"SELECT status_code FROM starexec.job_pairs WHERE id=?",pair));
        assertEquals(1,number(connection,"SELECT count(*) FROM starexec.pairs_rerun WHERE pair_id=?",pair));
        assertEquals(before,usage());
        assertEquals(JobPairs.NeverStartedRetryResult.STALE,retryRefused(receipt));
        assertEquals(2,SolverBuildArtifacts.findCurrent(connection,pair).orElseThrow().attemptNo());
        assertEquals(receipt.payload(),SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt().payload());
        record(store);record(store);
        assertEquals(originalBytes,usage());assertEquals(0,activeLeases());assertFalse(Files.exists(build.staging()));
        assertTrue(SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).isEmpty());
        assertTrue(ExecutionStorageLeases.findReleaseDecision(connection,build,resource).isEmpty());
        assertEquals("original source\n",Files.readString(source.resolve("starexec_build")));
    }

    @ParameterizedTest @ValueSource(strings={"killed","deleted","terminal","running","rerun","solver-deleted","solver-recycled"})
    void refusedRecoveryCannotResurrectARevokedOrNewerAttempt(String changed) throws Exception {
        var receipt=conserveRefusedCreation();
        switch(changed) {
            case "killed","deleted" -> number(connection,"UPDATE starexec.jobs SET "+changed+"=true WHERE id=(SELECT job_id FROM starexec.job_pairs WHERE id=?) RETURNING id",pair);
            case "terminal" -> number(connection,"UPDATE starexec.job_pairs SET status_code=21 WHERE id=? RETURNING id",pair);
            case "running" -> number(connection,"UPDATE starexec.job_pairs SET status_code=4 WHERE id=? RETURNING id",pair);
            case "solver-deleted","solver-recycled" -> number(connection,"UPDATE starexec.solvers SET "+changed.substring(7)+"=true WHERE id=? RETURNING id",solver);
            case "rerun" -> { try(var query=connection.prepareStatement("SELECT starexec.RerunJobPairsBatchCore(ARRAY[?]::INTEGER[])")) {
                query.setInt(1,pair);Common.executeAndDrain(query);
            } }
            default -> throw new AssertionError(changed);
        }
        long attempt=number(connection,"SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id=?",pair);
        long status=number(connection,"SELECT status_code FROM starexec.job_pairs WHERE id=?",pair);
        long before=usage();
        assertEquals(JobPairs.NeverStartedRetryResult.STALE,retryRefused(receipt));
        assertEquals(attempt,number(connection,"SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id=?",pair));
        assertEquals(status,number(connection,"SELECT status_code FROM starexec.job_pairs WHERE id=?",pair));
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.pairs_rerun WHERE pair_id=?",pair));
        assertEquals(before,usage());
    }

    @Test void refusedRecoveryCannotSubstituteAReceiptOrRetryWithoutCancellation() throws Exception {
        var uncommitted=createdReceipt("c".repeat(64),createdFacts());
        assertThrows(SQLException.class,()->retryRefused(uncommitted));
        var receipt=conserveRefusedCreation();var changed=receipt.facts();changed.addProperty("image_id","sha256:"+"e".repeat(64));
        var foreign=createdReceipt("c".repeat(64),changed);
        assertThrows(SQLException.class,()->retryRefused(foreign));
        assertEquals(1,number(connection,"SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id=?",pair));
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.pairs_rerun WHERE pair_id=?",pair));
    }

    @Test void alreadyDeferredRecoveryCannotAdoptOrSettleTheNextAttemptsLease() throws Exception {
        var receipt=conserveRefusedCreation();record(store);
        assertEquals(0,activeLeases());
        number(connection,"UPDATE starexec.job_pairs SET status_code=1 WHERE id=? RETURNING id",pair);
        assertEquals(JobPairs.NeverStartedRetryResult.REQUEUED,retryRefused(receipt));
        var successor=SolverBuildStaging.prepareLeased(connection,pair,2,resource,resource.capacityBytes());
        assertNotEquals(build.token(),successor.token());assertNotEquals(build.staging(),successor.staging());
        long before=usage();
        assertEquals(JobPairs.NeverStartedRetryResult.STALE,retryRefused(receipt));record(store);
        assertEquals(before,usage());assertTrue(Files.isDirectory(successor.staging()));
        assertEquals(1,number(connection,"SELECT count(*) FROM starexec.solver_build_storage_leases WHERE artifact_id=? AND released_at IS NULL",successor.artifactId()));
        assertEquals(0,activeLeases());assertFalse(Files.exists(build.staging()));
        assertEquals(receipt.payload(),SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt().payload());
    }

    @Test void refusedRecoveryUsesTheExistingSingleAutomaticRetryLimit() throws Exception {
        var receipt=conserveRefusedCreation();
        number(connection,"INSERT INTO starexec.pairs_rerun(pair_id) VALUES (?) RETURNING pair_id",pair);
        assertEquals(JobPairs.NeverStartedRetryResult.FILED,retryRefused(receipt));
        assertEquals(9,number(connection,"SELECT status_code FROM starexec.job_pairs WHERE id=?",pair));
        assertEquals(1,number(connection,"SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id=?",pair));
        assertTrue(SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).isEmpty());
    }

    @Test void refusedRecoveryRollbackDoesNotSpendItsRetryOrAdvanceTheArtifact() throws Exception {
        var receipt=conserveRefusedCreation();long before=usage();var failing=spy(connection);
        doThrow(new SQLException("Synthetic retry marker write failure")).when(failing).prepareStatement("INSERT INTO starexec.pairs_rerun(pair_id) VALUES (?)");
        assertThrows(SQLException.class,()->SolverBuildArtifacts.retryRefusedPodmanCreation(failing,build,1001,"c".repeat(64),receipt));
        assertTrue(connection.getAutoCommit());assertEquals(before,usage());
        assertEquals(1,number(connection,"SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id=?",pair));
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.pairs_rerun WHERE pair_id=?",pair));
        assertEquals(JobPairs.NeverStartedRetryResult.REQUEUED,retryRefused(receipt));
    }

    @Test void refusedRecoveryReadbackCannotRepeatACommitWhoseResponseWasLost() throws Exception {
        var receipt=conserveRefusedCreation();var uncertain=spy(connection);
        doAnswer(call->{call.callRealMethod();throw new SQLException("Synthetic lost retry commit response");}).when(uncertain).commit();
        assertThrows(SQLException.class,()->SolverBuildArtifacts.retryRefusedPodmanCreation(uncertain,build,1001,"c".repeat(64),receipt));
        assertTrue(connection.getAutoCommit());
        assertEquals(JobPairs.NeverStartedRetryResult.STALE,retryRefused(receipt));
        assertEquals(2,number(connection,"SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id=?",pair));
        assertEquals(1,number(connection,"SELECT count(*) FROM starexec.pairs_rerun WHERE pair_id=?",pair));
    }

    @Test void refusedRecoveryCannotApplyTheOriginalJobDecisionToAConcurrentlyMovedPair() throws Exception {
        var receipt=conserveRefusedCreation();var interleaved=spy(connection);
        int replacement=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob,killed)"
                +" VALUES (?,'revoked replacement',1,0,true,true) RETURNING id",owner);
        doAnswer(call->{
            try(var other=Common.getConnection()) {
                number(other,"UPDATE starexec.job_pairs SET job_id=? WHERE id=? RETURNING id",replacement,pair);
            }
            return call.callRealMethod();
        }).when(interleaved).prepareStatement("SELECT status_code,job_id FROM starexec.job_pairs WHERE id=? FOR UPDATE NOWAIT");
        long before=usage();
        assertEquals(JobPairs.NeverStartedRetryResult.STALE,
                SolverBuildArtifacts.retryRefusedPodmanCreation(interleaved,build,1001,"c".repeat(64),receipt));
        assertEquals(replacement,number(connection,"SELECT job_id FROM starexec.job_pairs WHERE id=?",pair));
        assertEquals(1,number(connection,"SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id=?",pair));
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.pairs_rerun WHERE pair_id=?",pair));
        assertEquals(before,usage());
    }

    @ParameterizedTest @ValueSource(strings={"cancelled","never_started"})
    void periodicRecorderSettlesExplicitNonCompletionWithoutPublishing(String disposition) throws Exception {
        String backend=disposition.equals("never_started")?"kubernetes":"podman";
        String engine=backend+":1001:synthetic-container";
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine));
        JsonObject facts=new JsonObject();facts.addProperty("status","exited");facts.addProperty("disposition",disposition);
        if (disposition.equals("never_started")) { facts.addProperty("suspended",true);facts.addProperty("pod_count",0); }
        var receipt=SolverBuildStopReceipt.observed(backend,build.artifactId(),pair,1,engine,"synthetic-container",Instant.now(),facts);
        assertTrue(disposition.equals("never_started")
                ?SolverBuildArtifacts.recordUnstarted(connection,pair,1,build.token(),engine,receipt)
                :SolverBuildArtifacts.recordCancelled(connection,pair,1,build.token(),engine,receipt));
        long status=number(connection,"SELECT status_code FROM starexec.job_pairs WHERE id=?",pair);
        record(store);record(store);
        assertEquals("REJECTED",SolverBuildArtifacts.find(connection,pair,1).orElseThrow().state());
        assertEquals(receipt.payload(),SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt().payload());
        assertEquals(originalBytes,usage());assertEquals(0,activeLeases());assertFalse(Files.exists(build.staging()));
        assertFalse(Files.exists(output()));assertTrue(SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).isEmpty());
        assertEquals(status,number(connection,"SELECT status_code FROM starexec.job_pairs WHERE id=?",pair));
        assertEquals("original source\n",Files.readString(source.resolve("starexec_build")));
    }

    @ParameterizedTest @ValueSource(strings={"missing","shared","permissions","linked","quota","unreleased","nonterminal"})
    void unverifiedPublicationConservesItsStagingAndAccounting(String condition) throws Exception {
        stopped(!condition.equals("unreleased"),condition.equals("nonterminal")?4:7);
        Path configured=store;
        switch(condition) {
            case "missing" -> configured=null;
            case "shared" -> configured=source;
            case "permissions" -> Files.setPosixFilePermissions(store,PosixFilePermissions.fromString("rwxr-xr-x"));
            case "linked" -> configured=Files.createSymbolicLink(files.resolve("alias"),store);
            case "quota" -> number(connection,"UPDATE starexec.users SET disk_quota=disk_size WHERE id=? RETURNING id",owner);
            case "unreleased", "nonterminal" -> { }
            default -> throw new AssertionError(condition);
        }
        long before=usage();record(configured);
        assertNotEquals("PUBLISHED",SolverBuildArtifacts.find(connection,pair,1).orElseThrow().state());
        assertEquals(before,usage());assertEquals(1,activeLeases());assertTrue(Files.exists(build.staging()));
        assertFalse(Files.exists(output()));assertEquals("original source\n",Files.readString(source.resolve("starexec_build")));
    }
}
