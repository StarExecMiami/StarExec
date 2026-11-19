package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.Common;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.jobs.SolverBuildStaging;
import org.starexec.test.util.DatabaseTestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real catalog/lease state and owned files; provider capacity is synthetic. */
class SolverBuildDispatchInputsSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private DedicatedStorageResource resource;
    private BuildExecutionContext build;
    private Path slot;
    private Path source;
    private Path helperFile;
    private int user;
    private int solver;
    private int helper;
    private int job;
    private int pair;
    private long sourceBytes;
    private long helperBytes;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("SolverBuildDispatchInputsSqlTest");
        Common.initialize();
    }

    private static long number(Connection con, String sql, Object... args) throws SQLException {
        try (var statement=con.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            try (var rows=statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }

    private static void execute(Connection con, String sql, Object... args) throws SQLException {
        try (var statement=con.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            statement.execute();
        }
    }

    @BeforeEach void seed() throws Exception {
        connection=Common.getConnection();
        source=Files.createDirectory(files.resolve("source_src"));
        Files.writeString(source.resolve("starexec_build"),"#!/bin/sh\nexit 0\n");
        sourceBytes=Files.size(source.resolve("starexec_build"));
        helperFile=Files.writeString(files.resolve("starexec_build"),"helper build input");
        helperBytes=Files.size(helperFile);
        user=(int)number(connection,"INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                +"disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Inputs','synthetic',now(),'synthetic',10000,100,?) RETURNING id",
                "inputs-"+UUID.randomUUID()+"@example.invalid",sourceBytes+helperBytes);
        solver=(int)number(connection,"INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                +" VALUES (?,'inputs',now(),?,?,0) RETURNING id",user,source.toString(),sourceBytes);
        helper=(int)number(connection,"INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size)"
                +" VALUES (?,'starexec_build',now(),?,?) RETURNING id",user,helperFile.toString(),helperBytes);
        job=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                +" VALUES (?,'build',1,0,true) RETURNING id",user);
        pair=(int)number(connection,"INSERT INTO starexec.job_pairs(job_id,bench_id,status_code,primary_jobpair_data) VALUES (?,?,7,1) RETURNING id",job,helper);
        execute(connection,"INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)",pair,solver);
        slot=Files.createDirectory(files.resolve("slot"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        build=SolverBuildStaging.prepare(connection,pair,1,slot,512);
        resource=mock(DedicatedStorageResource.class);
        when(resource.root()).thenReturn(slot);
        when(resource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-",""));
        when(resource.identity()).thenReturn("observed-input-resource");
        when(resource.capacityBytes()).thenReturn(256L);
        when(resource.inodeLimit()).thenReturn(16L);
    }

    @AfterEach void close() throws Exception { if (connection!=null) connection.close(); }

    private void lease() throws Exception {
        assertTrue(ExecutionStorageLeases.register(connection,resource));
        assertTrue(ExecutionStorageLeases.reserve(connection,build,resource));
    }

    private Optional<ExecutionStorageLeases.DispatchInputs> capture() throws SQLException {
        return ExecutionStorageLeases.findDispatchInputs(connection,build,resource);
    }

    private void unchangedAccountingAndFiles() throws Exception {
        assertEquals(sourceBytes+helperBytes+512,number(connection,"SELECT disk_size FROM starexec.users WHERE id=?",user));
        assertEquals(1,number(connection,"SELECT count(*) FROM starexec.solver_build_storage_charges WHERE artifact_id=? AND storage_kind='STAGING' AND released_at IS NULL",build.artifactId()));
        assertEquals("#!/bin/sh\nexit 0\n",Files.readString(source.resolve("starexec_build")));
        assertEquals("helper build input",Files.readString(helperFile));
        assertTrue(connection.getAutoCommit());
    }

    @Test void unleasedStagingCannotExposeDispatchInputs() throws Exception {
        assertTrue(ExecutionStorageLeases.findDispatchInputs(connection,build,resource).isEmpty());
        assertTrue(connection.getAutoCommit());
        assertTrue(Files.exists(source.resolve("starexec_build")));
        assertEquals("helper build input",Files.readString(helperFile));
    }

    @Test void aRegisteredResourceAloneCannotExposeInputs() throws Exception {
        assertTrue(ExecutionStorageLeases.register(connection,resource));
        assertTrue(capture().isEmpty());
        unchangedAccountingAndFiles();
    }

    @Test void currentInputsMatchTheConservedSourceHelperOwnerAndGeneration() throws Exception {
        lease();
        var inputs=capture().orElseThrow();
        var artifact=SolverBuildArtifacts.find(connection,pair,1).orElseThrow();
        assertEquals(user,inputs.ownerId()); assertEquals(artifact.generation(),inputs.generation());
        assertEquals(source,inputs.source());
        assertEquals(helper,inputs.helper().benchmarkId()); assertEquals(user,inputs.helper().ownerId());
        assertEquals(helperFile.toString(),inputs.helper().path()); assertEquals(helperBytes,inputs.helper().byteSize());
        assertNull(inputs.helper().token()); assertNull(inputs.helper().finishedAt());
        assertEquals(inputs,capture().orElseThrow());
        assertNull(SolverBuildArtifacts.find(connection,pair,1).orElseThrow().engineIdentity());
        unchangedAccountingAndFiles();
    }

    @ParameterizedTest @ValueSource(strings={"artifact","pair","attempt","token","source","staging"})
    void aDifferentContextCannotExposeInputs(String changed) throws Exception {
        lease();
        var other=new BuildExecutionContext(changed.equals("artifact")?build.artifactId()+1:build.artifactId(),
                changed.equals("pair")?pair+1:pair,changed.equals("attempt")?2:1,
                changed.equals("token")?UUID.randomUUID():build.token(),
                changed.equals("source")?files.resolve("other_src"):source,
                changed.equals("staging")?slot.resolve("other"):build.staging());
        assertTrue(ExecutionStorageLeases.findDispatchInputs(connection,other,resource).isEmpty());
        unchangedAccountingAndFiles();
    }

    @ParameterizedTest @ValueSource(strings={"key","root","identity","bytes","inodes"})
    void aDifferentResourceObservationCannotExposeInputs(String changed) throws Exception {
        lease();
        String originalKey=resource.filesystemKey(), originalIdentity=resource.identity();
        var observed=mock(DedicatedStorageResource.class);
        when(observed.filesystemKey()).thenReturn(changed.equals("key")?UUID.randomUUID().toString().replace("-",""):originalKey);
        when(observed.root()).thenReturn(changed.equals("root")?files.resolve("other-slot"):slot);
        when(observed.identity()).thenReturn(changed.equals("identity")?"other-observed-resource":originalIdentity);
        when(observed.capacityBytes()).thenReturn(changed.equals("bytes")?255L:256L);
        when(observed.inodeLimit()).thenReturn(changed.equals("inodes")?15L:16L);
        assertTrue(ExecutionStorageLeases.findDispatchInputs(connection,build,observed).isEmpty());
        unchangedAccountingAndFiles();
    }

    @Test void aChangedCurrentAttemptCannotExposeItsPredecessorsInputs() throws Exception {
        lease();
        execute(connection,"UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?",pair);
        assertTrue(capture().isEmpty());
        unchangedAccountingAndFiles();
    }

    @Test void aKilledJobCannotUseACapturedSnapshotForLaunch() throws Exception {
        lease();
        var inputs=capture().orElseThrow();
        execute(connection,"UPDATE starexec.jobs SET killed=true WHERE id=?",job);
        assertTrue(capture().isEmpty());
        assertFalse(ExecutionStorageLeases.admit(connection,build,resource,"podman:guarded-input-attempt"));
        assertEquals(source,inputs.source());
        assertNull(SolverBuildArtifacts.find(connection,pair,1).orElseThrow().engineIdentity());
        unchangedAccountingAndFiles();
    }

    @Test void anAlreadyBoundEngineCannotCaptureANewSubmission() throws Exception {
        lease();
        assertTrue(SolverBuildArtifacts.bindExecution(connection,pair,1,build.token(),"podman:observed-input-build"));
        assertTrue(capture().isEmpty());
        unchangedAccountingAndFiles();
    }

    @Test void pendingHelperCleanupCannotExposeInputReferences() throws Exception {
        lease();
        execute(connection,"UPDATE starexec.solver_build_helpers SET cleanup_token=?,cleanup_started_at=clock_timestamp() WHERE benchmark_id=?",UUID.randomUUID(),helper);
        assertTrue(capture().isEmpty());
        unchangedAccountingAndFiles();
    }

    @Test void unavailableCapacityCannotProduceAnInputSnapshot() throws Exception {
        lease();
        doThrow(new IOException("injected unavailable resource observation")).when(resource).requireUnchanged();
        SQLException failure=assertThrows(SQLException.class,this::capture);
        assertInstanceOf(IOException.class,failure.getCause());
        unchangedAccountingAndFiles();
    }

    @Test void aForeignTransactionCannotBeCommittedByInputCapture() throws Exception {
        lease();
        connection.setAutoCommit(false);
        try {
            execute(connection,"UPDATE starexec.users SET first_name='Uncommitted' WHERE id=?",user);
            assertThrows(SQLException.class,this::capture);
            assertFalse(connection.getAutoCommit());
            try (Connection observer=Common.getConnection()) {
                assertEquals(1,number(observer,"SELECT count(*) FROM starexec.users WHERE id=? AND first_name='Test'",user));
            }
        } finally { connection.rollback(); connection.setAutoCommit(true); }
        unchangedAccountingAndFiles();
    }

    @Test void contendedResourceLocksCannotProduceAStaleInputSnapshot() throws Exception {
        lease();
        try (Connection holder=Common.getConnection()) {
            holder.setAutoCommit(false);
            try {
                number(holder,"SELECT capacity_bytes FROM starexec.execution_storage_resources WHERE resource_key=? FOR UPDATE",resource.filesystemKey());
                SQLException failure=assertThrows(SQLException.class,this::capture);
                assertEquals("55P03",failure.getSQLState());
            } finally { holder.rollback(); holder.setAutoCommit(true); }
        }
        assertTrue(capture().isPresent());
        unchangedAccountingAndFiles();
    }

    @Test void aMissingConservedHelperCannotBeReplacedByALiveBenchmarkGuess() throws Exception {
        int otherJob=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                +" VALUES (?,'missing-helper',1,0,true) RETURNING id",user);
        int otherPair=(int)number(connection,"INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id",otherJob);
        execute(connection,"INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)",otherPair,solver);
        Path otherSlot=Files.createDirectory(files.resolve("missing-helper-slot"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var otherBuild=SolverBuildStaging.prepare(connection,otherPair,1,otherSlot,512);
        var otherResource=mock(DedicatedStorageResource.class);
        when(otherResource.root()).thenReturn(otherSlot);
        when(otherResource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-",""));
        when(otherResource.identity()).thenReturn("observed-missing-helper-resource");
        when(otherResource.capacityBytes()).thenReturn(256L); when(otherResource.inodeLimit()).thenReturn(16L);
        assertTrue(ExecutionStorageLeases.register(connection,otherResource));
        assertTrue(ExecutionStorageLeases.reserve(connection,otherBuild,otherResource));
        assertTrue(ExecutionStorageLeases.findDispatchInputs(connection,otherBuild,otherResource).isEmpty());
        assertEquals(sourceBytes+helperBytes+1024,number(connection,"SELECT disk_size FROM starexec.users WHERE id=?",user));
        assertEquals("helper build input",Files.readString(helperFile));
        assertTrue(connection.getAutoCommit());
    }

    @Test void aFailedRollbackCannotBeHiddenAsAnInputRefusal() throws Exception {
        var failing=spy(connection);
        doThrow(new SQLException("injected input rollback failure")).when(failing).rollback();
        SQLException failure=assertThrows(SQLException.class,()->ExecutionStorageLeases.findDispatchInputs(failing,build,resource));
        assertTrue(java.util.Arrays.stream(failure.getSuppressed())
                .anyMatch(suppressed->suppressed.getMessage().contains("injected input rollback failure")));
        unchangedAccountingAndFiles();
    }
}
