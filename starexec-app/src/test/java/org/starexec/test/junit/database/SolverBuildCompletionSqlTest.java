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
