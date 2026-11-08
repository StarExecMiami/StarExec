package org.starexec.test.junit.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.Common;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.database.SolverBuildCreations;
import org.starexec.jobs.SolverBuildStaging;
import org.starexec.test.util.DatabaseTestSupport;

/** PostgreSQL consumption and rollback tests; no provider request is made here. */
class SolverBuildCreationSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private int owner,solver,job,pair;
    private BuildExecutionContext build;
    private DedicatedStorageResource resource;
    private final String image="sha256:"+"a".repeat(64);

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("SolverBuildCreationSqlTest");Common.initialize();
    }

    private static long number(Connection con,String sql,Object...values) throws SQLException {
        try (var query=con.prepareStatement(sql)) {
            for (int i=0;i<values.length;i++) query.setObject(i+1,values[i]);
            try (var rows=query.executeQuery()) { assertTrue(rows.next());return rows.getLong(1); }
        }
    }

    @BeforeEach void prepare() throws Exception {
        connection=Common.getConnection();
        Path source=Files.createDirectory(files.resolve("source_src"));Files.writeString(source.resolve("starexec_build"),"source\n");
        Path helper=Files.writeString(files.resolve("starexec_build"),"helper\n");
        Path slot=Files.createDirectory(files.resolve("slot"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        owner=(int)number(connection,"INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,disk_quota,job_pair_quota,disk_size)"
                +" VALUES (?,'Test','Creation','synthetic',now(),'synthetic',16777216,100,14) RETURNING id","creation-"+UUID.randomUUID()+"@example.invalid");
        solver=(int)number(connection,"INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status) VALUES (?,'creation',now(),?,7,0) RETURNING id",owner,source.toString());
        int benchmark=(int)number(connection,"INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size) VALUES (?,'starexec_build',now(),?,7) RETURNING id",owner,helper.toString());
        job=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'creation',1,0,true) RETURNING id",owner);
        pair=(int)number(connection,"INSERT INTO starexec.job_pairs(job_id,bench_id,status_code,primary_jobpair_data) VALUES (?,?,2,1) RETURNING id",job,benchmark);
        number(connection,"INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,2,?,0) RETURNING jobpair_id",pair,solver);
        resource=mock(DedicatedStorageResource.class);when(resource.root()).thenReturn(slot);
        when(resource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-",""));when(resource.identity()).thenReturn("synthetic-creation-capacity");
        when(resource.capacityBytes()).thenReturn(1048576L);when(resource.inodeLimit()).thenReturn(1024L);
        build=SolverBuildStaging.prepareLeased(connection,pair,1,resource,resource.capacityBytes());clearInvocations(resource);
    }

    @AfterEach void close() throws Exception { if(connection!=null) connection.close(); }

    private String payload(int execution) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("Image",image,"HostConfig",Map.of("ReadonlyRootfs",true),
                "Entrypoint",List.of("/bin/bash"),"Cmd",List.of("true"),"Env",List.of(),"WorkingDir","/starexec/execution",
                "Labels",Map.of("starexec.managed","true","starexec.label.version","2","starexec.kind","job-pair",
                        "starexec.build.layout","private-v1","starexec.build.artifact",Long.toString(build.artifactId()),
                        "starexec.pair.id",Integer.toString(pair),"starexec.attempt","1","starexec.exec.id",Integer.toString(execution))));
    }
    private boolean claim() throws Exception { return SolverBuildCreations.claim(connection,build,resource,1001,image,SolverBuildCreations.Transport.JAVA,payload(1001)); }
    private long count() throws Exception { return number(connection,"SELECT count(*) FROM starexec.solver_build_creation_requests WHERE artifact_id=?",build.artifactId()); }
    private long locator() throws Exception { return number(connection,"SELECT COALESCE(sge_id,0) FROM starexec.job_pairs WHERE id=?",pair); }
    private long usage() throws Exception { return number(connection,"SELECT disk_size FROM starexec.users WHERE id=?",owner); }

    @ParameterizedTest @EnumSource(SolverBuildCreations.Transport.class)
    void firstAcknowledgedConsumptionPreservesIntentAndLocatorWithoutAdmission(SolverBuildCreations.Transport transport) throws Exception {
        String payload=payload(1001);long before=usage();
        assertTrue(SolverBuildCreations.claim(connection,build,resource,1001,image,transport,payload));
        assertEquals(1,count());assertEquals(1001,locator());assertEquals(before,usage());assertTrue(connection.getAutoCommit());
        var saved=SolverBuildCreations.find(connection,build.artifactId()).orElseThrow();
        assertEquals(build,saved.build());assertEquals(transport,saved.transport());assertEquals(payload,saved.payload());
        assertEquals(SolverBuildCreations.digest(payload),saved.sha256());assertEquals(image,saved.image());
        var artifact=SolverBuildArtifacts.find(connection,pair,1).orElseThrow();assertNull(artifact.engineIdentity());assertNull(artifact.stoppedAt());
        assertTrue(SolverBuildArtifacts.findStopReceipt(connection,pair,1).isEmpty());
        assertTrue(ExecutionStorageLeases.findReleaseDecision(connection,build,resource).isEmpty());
        assertTrue(SolverBuildCreations.pending().stream().anyMatch(c->c.build().artifactId()==build.artifactId()));
        assertFalse(SolverBuildCreations.claim(connection,build,resource,1001,image,transport,payload));
        assertFalse(SolverBuildCreations.claim(connection,build,resource,1002,image,transport,payload(1002)));
        assertEquals(1001,locator());assertEquals(1,count());assertEquals(before,usage());
    }

    @ParameterizedTest @ValueSource(strings={"killed","deleted","solver-deleted","solver-recycled","running","terminal","rerun","foreign-token"})
    void staleOrRevokedOwnershipCannotConsumeCreation(String change) throws Exception {
        BuildExecutionContext submitted=build;
        switch(change) {
            case "killed","deleted" -> number(connection,"UPDATE starexec.jobs SET "+change+"=true WHERE id=? RETURNING id",job);
            case "solver-deleted","solver-recycled" -> number(connection,"UPDATE starexec.solvers SET "+change.substring(7)+"=true WHERE id=? RETURNING id",solver);
            case "running" -> number(connection,"UPDATE starexec.job_pairs SET status_code=4 WHERE id=? RETURNING id",pair);
            case "terminal" -> number(connection,"UPDATE starexec.job_pairs SET status_code=7 WHERE id=? RETURNING id",pair);
            case "rerun" -> { try(var query=connection.prepareStatement("SELECT starexec.RerunJobPairsBatchCore(ARRAY[?]::INTEGER[])")) {
                query.setInt(1,pair);Common.executeAndDrain(query);
            } }
            case "foreign-token" -> submitted=new BuildExecutionContext(build.artifactId(),pair,1,UUID.randomUUID(),build.source(),build.staging());
            default -> throw new AssertionError(change);
        }
        long before=usage();
        assertFalse(SolverBuildCreations.claim(connection,submitted,resource,1001,image,SolverBuildCreations.Transport.JAVA,payload(1001)));
        assertEquals(0,count());assertEquals(0,locator());assertEquals(before,usage());assertTrue(connection.getAutoCommit());
    }

    @Test void pauseDoesNotConfuseAnIntentLocatorWithExecutionAdmission() throws Exception {
        number(connection,"UPDATE starexec.jobs SET paused=true WHERE id=? RETURNING id",job);
        assertTrue(claim());assertEquals(1001,locator());assertNull(SolverBuildArtifacts.find(connection,pair,1).orElseThrow().engineIdentity());
        assertFalse(ExecutionStorageLeases.admitPodman(connection,build,resource,1001,"c".repeat(64)));
        assertEquals(1,count());assertTrue(SolverBuildArtifacts.findStopReceipt(connection,pair,1).isEmpty());
    }

    @Test void admissionCannotRebindTheConsumedCreationToAnotherExecution() throws Exception {
        assertTrue(claim());var intent=SolverBuildCreations.find(connection,build.artifactId()).orElseThrow();
        assertFalse(ExecutionStorageLeases.admitPodman(connection,build,resource,1002,"b".repeat(64)));
        assertEquals(1001,locator());assertNull(SolverBuildArtifacts.find(connection,pair,1).orElseThrow().engineIdentity());
        assertTrue(ExecutionStorageLeases.admitPodman(connection,build,resource,1001,"b".repeat(64)));
        assertEquals(1001,locator());assertEquals(intent,SolverBuildCreations.find(connection,build.artifactId()).orElseThrow());
        assertTrue(ExecutionStorageLeases.findReleaseDecision(connection,build,resource).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={"malformed","duplicate","trailing","oversized","foreign-image","foreign-label"})
    void invalidRequestBytesCannotCreateIntentOrChangeLocator(String fault) throws Exception {
        String payload=payload(1001);
        switch(fault) {
            case "malformed" -> payload="{";
            case "duplicate" -> payload="{\"Image\":\"other\","+payload.substring(1);
            case "trailing" -> payload+=" {}";
            case "oversized" -> payload+=" ".repeat(65537);
            case "foreign-image" -> payload=payload.replace(image,"sha256:"+"b".repeat(64));
            case "foreign-label" -> payload=payload.replace("\"starexec.managed\":\"true\"","\"starexec.managed\":\"false\"");
            default -> throw new AssertionError(fault);
        }
        assertFalse(SolverBuildCreations.claim(connection,build,resource,1001,image,SolverBuildCreations.Transport.JAVA,payload));
        assertEquals(0,count());assertEquals(0,locator());verify(resource,never()).requireUnchanged();
    }

    @Test void concurrentConsumersGrantExactlyOneSendAndOneLocator() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        String first=payload(1001),second=payload(1002);
        try {
            var a=pool.submit(()->{try(var con=Common.getConnection()) {ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));
                return SolverBuildCreations.claim(con,build,resource,1001,image,SolverBuildCreations.Transport.JAVA,first);}});
            var b=pool.submit(()->{try(var con=Common.getConnection()) {ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));
                return SolverBuildCreations.claim(con,build,resource,1002,image,SolverBuildCreations.Transport.CURL,second);}});
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();
            boolean one=a.get(20,TimeUnit.SECONDS),two=b.get(20,TimeUnit.SECONDS);assertNotEquals(one,two);
            assertEquals(1,count());assertEquals(one?1001:1002,locator());
        } finally {go.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void lostCommitResponseNeverGrantsHistoricalSendPermission() throws Exception {
        var uncertain=spy(connection);String payload=payload(1001);long before=usage();
        doAnswer(call->{call.callRealMethod();throw new SQLException("Synthetic create-claim response loss after commit");}).when(uncertain).commit();
        assertThrows(SQLException.class,()->SolverBuildCreations.claim(uncertain,build,resource,1001,image,SolverBuildCreations.Transport.JAVA,payload));
        assertTrue(connection.getAutoCommit());assertEquals(1,count());assertEquals(1001,locator());assertEquals(before,usage());
        assertEquals(payload,SolverBuildCreations.find(connection,build.artifactId()).orElseThrow().payload());
        assertFalse(claim());assertEquals(1,count());assertEquals(1001,locator());
    }

    @Test void locatorFailureRollsBackCreationConsumption() throws Exception {
        var failing=spy(connection);String payload=payload(1001);long before=usage();
        doAnswer(call->{
            String sql=call.getArgument(0);
            if(sql.startsWith("UPDATE starexec.job_pairs p SET sge_id=")) throw new SQLException("Synthetic locator write failure");
            return call.callRealMethod();
        }).when(failing).prepareStatement(anyString());
        assertThrows(SQLException.class,()->SolverBuildCreations.claim(failing,build,resource,1001,image,SolverBuildCreations.Transport.JAVA,payload));
        assertEquals(0,count());assertEquals(0,locator());assertEquals(before,usage());assertTrue(connection.getAutoCommit());assertTrue(claim());
    }

    @Test void changedPhysicalResourceRollsBackIntentAndLocator() throws Exception {
        long before=usage();doNothing().doThrow(new IOException("Synthetic resource replacement")).when(resource).requireUnchanged();
        assertThrows(SQLException.class,this::claim);assertEquals(0,count());assertEquals(0,locator());assertEquals(before,usage());assertTrue(connection.getAutoCommit());
    }

    @Test void foreignTransactionCannotBeCommittedOrRestoredByAClaim() throws Exception {
        connection.setAutoCommit(false);
        try {assertThrows(SQLException.class,this::claim);assertFalse(connection.getAutoCommit());verify(resource,never()).requireUnchanged();}
        finally {connection.rollback();connection.setAutoCommit(true);}
        assertEquals(0,count());assertEquals(0,locator());
    }

    @Test void consumedRequestAndDigestCannotBeRewrittenOrRemoved() throws Exception {
        assertTrue(claim());var saved=SolverBuildCreations.find(connection,build.artifactId()).orElseThrow();
        try(var update=connection.prepareStatement("UPDATE starexec.solver_build_creation_requests SET execution_id=2002 WHERE artifact_id=?")) {
            update.setLong(1,build.artifactId());assertThrows(SQLException.class,update::executeUpdate);
        }
        try(var delete=connection.prepareStatement("DELETE FROM starexec.solver_build_creation_requests WHERE artifact_id=?")) {
            delete.setLong(1,build.artifactId());assertThrows(SQLException.class,delete::executeUpdate);
        }
        assertEquals(saved,SolverBuildCreations.find(connection,build.artifactId()).orElseThrow());assertEquals(1001,locator());
    }
}
