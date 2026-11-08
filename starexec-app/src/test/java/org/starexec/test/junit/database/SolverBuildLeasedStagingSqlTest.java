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
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL and files; dedicated-resource capacity observations are synthetic. */
class SolverBuildLeasedStagingSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private DedicatedStorageResource resource;
    private Path slot;
    private Path source;
    private int user;
    private int pair;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("SolverBuildLeasedStagingSqlTest");
        Common.initialize();
    }

    private static long number(Connection con, String sql, Object... args) throws SQLException {
        try (var statement = con.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            try (var rows=statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }

    private Path directory(String name) throws Exception {
        return Files.createDirectory(files.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @BeforeEach void seed() throws Exception {
        connection=Common.getConnection();
        slot=directory("slot"); source=directory("source_src");
        Files.writeString(source.resolve("starexec_build"),"original source");
        user=(int)number(connection,"INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                +"disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Leased','synthetic',now(),'synthetic',10000,100,20) RETURNING id",
                "leased-"+UUID.randomUUID()+"@example.invalid");
        pair=createBuildPair();
        resource=mock(DedicatedStorageResource.class);
        when(resource.root()).thenReturn(slot);
        when(resource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-",""));
        when(resource.identity()).thenReturn("observed-preparation-device-and-mount");
        when(resource.capacityBytes()).thenReturn(256L);
        when(resource.inodeLimit()).thenReturn(16L);
    }

    private int createBuildPair() throws Exception {
        int solver=(int)number(connection,"INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                +" VALUES (?,'leased',now(),?,20,0) RETURNING id",user,source.toString());
        int job=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                +" VALUES (?,'build',1,0,true) RETURNING id",user);
        int created=(int)number(connection,"INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id",job);
        try (var statement=connection.prepareStatement("INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)")) {
            statement.setInt(1,created); statement.setInt(2,solver); statement.executeUpdate();
        }
        return created;
    }

    @AfterEach void close() throws Exception { if (connection!=null) connection.close(); }

    private BuildExecutionContext prepare() throws Exception {
        return SolverBuildStaging.prepareLeased(connection,pair,1,resource,512);
    }

    private long usage() throws SQLException {
        return number(connection,"SELECT disk_size FROM starexec.users WHERE id=?",user);
    }

    private long leases() throws SQLException {
        return number(connection,"SELECT count(*) FROM starexec.solver_build_storage_leases WHERE resource_key=? AND released_at IS NULL",resource.filesystemKey());
    }

    private long contents() throws IOException {
        try (var entries=Files.list(slot)) { return entries.count(); }
    }

    private void requireUnallocated(int requestedPair) throws Exception {
        var artifact=SolverBuildArtifacts.find(connection,requestedPair,1).orElseThrow();
        assertNull(artifact.executionToken()); assertNull(artifact.stagingPath());
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.solver_build_storage_charges WHERE artifact_id=?",artifact.id()));
        assertTrue(connection.getAutoCommit());
    }

    @Test void legacyDispatchHistoryAllowsAnUnboundPlannedAttempt() throws Exception {
        assertEquals("PLANNED", SolverBuildArtifacts.findCurrent(connection, pair).orElseThrow().state());
        assertFalse(SolverBuildArtifacts.hasManagedHistory(pair));
        assertFalse(SolverBuildArtifacts.hasManagedHistory(-1));
        assertEquals(20, usage());
        assertEquals(0, leases());
    }

    @Test void legacyDispatchHistoryIncludesCommittedLeasedPreparation() throws Exception {
        var build = prepare();
        assertNull(SolverBuildArtifacts.findCurrent(connection, pair).orElseThrow().engineIdentity());
        assertTrue(SolverBuildArtifacts.hasManagedHistory(pair));
        assertEquals(build.token(), SolverBuildArtifacts.findCurrent(connection, pair).orElseThrow().executionToken());
        assertEquals(532, usage());
        assertEquals(1, leases());
    }

    @Test void legacyDispatchHistoryIncludesRegistryOnlyPreparation() throws Exception {
        UUID token = UUID.randomUUID();
        assertTrue(SolverBuildArtifacts.prepare(connection, pair, 1, token, slot.resolve("build-" + token)));
        assertEquals(0, leases());
        assertEquals(0, contents());
        assertTrue(SolverBuildArtifacts.hasManagedHistory(pair));
    }

    @Test void legacyDispatchHistorySurvivesAChangeOfCurrentAttempt() throws Exception {
        var build = prepare();
        try (var update = connection.prepareStatement(
                "UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?")) {
            update.setInt(1, pair);
            assertEquals(1, update.executeUpdate());
        }
        var current = SolverBuildArtifacts.findCurrent(connection, pair).orElseThrow();
        assertEquals(2, current.attemptNo());
        assertNull(current.executionToken());
        assertEquals("PLANNED", current.state());
        assertTrue(SolverBuildArtifacts.hasManagedHistory(pair));
        assertEquals(build.token(), SolverBuildArtifacts.find(connection, pair, 1).orElseThrow().executionToken());
    }

    @Test void legacyDispatchHistoryDoesNotRejectAnEarlierUnboundLegacyOutcome() throws Exception {
        try (var reject = connection.prepareStatement(
                "UPDATE starexec.solver_build_artifacts SET state='REJECTED' WHERE origin_pair_id=?")) {
            reject.setInt(1, pair);
            assertEquals(1, reject.executeUpdate());
        }
        try (var update = connection.prepareStatement(
                "UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?")) {
            update.setInt(1, pair);
            assertEquals(1, update.executeUpdate());
        }
        assertEquals("PLANNED", SolverBuildArtifacts.findCurrent(connection, pair).orElseThrow().state());
        assertFalse(SolverBuildArtifacts.hasManagedHistory(pair));
    }

    @Test void legacyDispatchHistorySurvivesLivePairDeletion() throws Exception {
        var build = prepare();
        try (var remove = connection.prepareStatement("DELETE FROM starexec.job_pairs WHERE id=?")) {
            remove.setInt(1, pair);
            assertEquals(1, remove.executeUpdate());
        }
        assertTrue(SolverBuildArtifacts.hasManagedHistory(pair));
        assertEquals(build.token(), SolverBuildArtifacts.find(connection, pair, 1).orElseThrow().executionToken());
        assertEquals(532, usage());
        assertEquals(1, leases());
    }

    @Test void leaseAndAllowanceAreCommittedBeforeTheFirstDirectoryWrite() throws Exception {
        AtomicInteger creations=new AtomicInteger();
        try (var physical=mockStatic(Files.class,invocation->{
            if (invocation.getMethod().getName().equals("createDirectory")
                    && slot.equals(((Path)invocation.getArgument(0)).getParent())) {
                try (Connection observer=Common.getConnection()) {
                    assertEquals(1,number(observer,"SELECT count(*) FROM starexec.solver_build_storage_leases l"
                            +" JOIN starexec.solver_build_artifacts a ON a.id=l.artifact_id"
                            +" WHERE a.origin_pair_id=? AND l.released_at IS NULL",pair));
                    assertEquals(512,number(observer,"SELECT c.charged_bytes FROM starexec.solver_build_storage_charges c"
                            +" JOIN starexec.solver_build_artifacts a ON a.id=c.artifact_id"
                            +" WHERE a.origin_pair_id=? AND c.storage_kind='STAGING' AND c.released_at IS NULL",pair));
                    assertEquals(532,number(observer,"SELECT disk_size FROM starexec.users WHERE id=?",user));
                }
                creations.incrementAndGet();
            }
            return invocation.callRealMethod();
        })) {
            var build=prepare();
            assertTrue(Files.isDirectory(build.staging()));
            assertEquals(1,creations.get());
            assertEquals(build.token(),SolverBuildArtifacts.find(connection,pair,1).orElseThrow().executionToken());
        }
    }

    @Test void aRetryPreservesItsOwnDirectoryAndDoesNotBookTwice() throws Exception {
        var first=prepare();
        Files.writeString(first.staging().resolve("keep"),"prepared input");
        assertEquals(first,prepare());
        assertEquals(1,leases()); assertEquals(532,usage()); assertEquals(1,contents());
        assertEquals("prepared input",Files.readString(first.staging().resolve("keep")));
        assertTrue(connection.getAutoCommit());
    }

    @Test void aLeaseRefusalRollsBackIdentityAndBudgetWithoutCreatingFiles() throws Exception {
        assertThrows(SQLException.class,()->SolverBuildStaging.prepareLeased(connection,pair,1,resource,255));
        requireUnallocated(pair);
        assertEquals(0,leases()); assertEquals(20,usage()); assertEquals(0,contents());
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.execution_storage_resources WHERE resource_key=?",resource.filesystemKey()));
    }

    @ParameterizedTest @ValueSource(longs={0,-1})
    void aNonpositiveBudgetCannotPrepareAResource(long limit) throws Exception {
        assertThrows(IllegalArgumentException.class,()->SolverBuildStaging.prepareLeased(connection,pair,1,resource,limit));
        requireUnallocated(pair);
        assertEquals(0,leases()); assertEquals(20,usage()); assertEquals(0,contents());
    }

    @Test void aCommitFailureCannotLeaveALeaseOrDirectory() throws Exception {
        var failing=spy(connection);
        doThrow(new SQLException("injected preparation commit failure")).when(failing).commit();
        SQLException failure=assertThrows(SQLException.class,()->SolverBuildStaging.prepareLeased(failing,pair,1,resource,512));
        assertTrue(failure.getMessage().contains("injected preparation commit failure"));
        requireUnallocated(pair);
        assertEquals(0,leases()); assertEquals(20,usage()); assertEquals(0,contents());
        assertTrue(failing.getAutoCommit());
    }

    @Test void aLostCommittedResponseRetainsTheReservationWithoutWritingOrBookingTwice() throws Exception {
        var uncertain=spy(connection);
        doAnswer(invocation->{
            invocation.callRealMethod();
            throw new SQLException("injected lost committed response");
        }).when(uncertain).commit();
        SQLException failure=assertThrows(SQLException.class,()->SolverBuildStaging.prepareLeased(uncertain,pair,1,resource,512));
        assertTrue(failure.getMessage().contains("injected lost committed response"));
        assertEquals(1,leases()); assertEquals(532,usage()); assertEquals(0,contents());
        assertTrue(connection.getAutoCommit()); assertTrue(uncertain.getAutoCommit());
        var captured=SolverBuildArtifacts.find(connection,pair,1).orElseThrow();
        assertNotNull(captured.executionToken());
        var recovered=prepare();
        assertEquals(captured.executionToken(),recovered.token());
        assertEquals(1,leases()); assertEquals(532,usage()); assertEquals(1,contents());
    }

    @Test void aDirectoryFailureRetainsItsCommittedIdentityForRetry() throws Exception {
        try (var physical=mockStatic(Files.class,invocation->{
            if (invocation.getMethod().getName().equals("createDirectory")
                    && slot.equals(((Path)invocation.getArgument(0)).getParent())) {
                throw new IOException("injected staging directory failure");
            }
            return invocation.callRealMethod();
        })) {
            IOException failure=assertThrows(IOException.class,this::prepare);
            assertTrue(failure.getMessage().contains("injected staging directory failure"));
        }
        var captured=SolverBuildArtifacts.find(connection,pair,1).orElseThrow();
        assertNotNull(captured.executionToken());
        assertEquals(1,leases()); assertEquals(532,usage()); assertEquals(0,contents());
        var recovered=prepare();
        assertEquals(captured.executionToken(),recovered.token());
        assertEquals(captured.stagingPath(),recovered.staging().toString());
        assertTrue(Files.isDirectory(recovered.staging()));
        assertEquals(1,leases()); assertEquals(532,usage());
    }

    @Test void aForceFailureRetainsTheLeaseAndDirectory() throws Exception {
        try (var directories=mockStatic(DescriptorDirectory.class,invocation->{
            DescriptorDirectory opened=(DescriptorDirectory)invocation.callRealMethod();
            if (slot.equals(invocation.getArgument(0))) {
                var failing=spy(opened);
                doThrow(new IOException("injected preparation force failure")).when(failing).force();
                return failing;
            }
            return opened;
        })) {
            IOException failure=assertThrows(IOException.class,this::prepare);
            assertTrue(failure.getMessage().contains("injected preparation force failure"));
        }
        var captured=SolverBuildArtifacts.find(connection,pair,1).orElseThrow();
        assertEquals(1,leases()); assertEquals(532,usage()); assertEquals(1,contents());
        var recovered=prepare();
        assertEquals(captured.executionToken(),recovered.token());
        assertEquals(1,leases()); assertEquals(532,usage());
    }

    @Test void aChangedObservationBeforePreparationCannotAllocateAnything() throws Exception {
        doThrow(new IOException("changed resource observation")).when(resource).requireUnchanged();
        assertThrows(IOException.class,this::prepare);
        requireUnallocated(pair);
        assertEquals(0,leases()); assertEquals(20,usage()); assertEquals(0,contents());
    }

    @Test void aLostObservationAfterCommitCannotWriteOrRefundItsLease() throws Exception {
        AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(invocation->{
            try (Connection observer=Common.getConnection()) {
                if (number(observer,"SELECT count(*) FROM starexec.solver_build_storage_leases WHERE resource_key=?",resource.filesystemKey())>0
                        && fail.getAndSet(false)) throw new IOException("lost committed resource observation");
            }
            return null;
        }).when(resource).requireUnchanged();
        IOException failure=assertThrows(IOException.class,this::prepare);
        assertTrue(failure.getMessage().contains("lost committed resource observation"));
        assertEquals(1,leases()); assertEquals(532,usage()); assertEquals(0,contents());
        var captured=SolverBuildArtifacts.find(connection,pair,1).orElseThrow();
        assertEquals(captured.executionToken(),prepare().token());
        assertEquals(532,usage());
    }

    @Test void aForeignTransactionCannotPrepareOrCommitStaging() throws Exception {
        connection.setAutoCommit(false);
        try {
            assertThrows(SQLException.class,this::prepare);
            assertFalse(connection.getAutoCommit());
            assertNull(SolverBuildArtifacts.find(connection,pair,1).orElseThrow().executionToken());
            assertEquals(0,contents());
        } finally { connection.rollback(); connection.setAutoCommit(true); }
        assertEquals(0,leases()); assertEquals(20,usage());
    }

    @Test void explicitTransactionHelpersRefuseAutocommit() throws Exception {
        assertThrows(SQLException.class,()->ExecutionStorageLeases.registerInTransaction(connection,resource));
        var artifact=SolverBuildArtifacts.find(connection,pair,1).orElseThrow();
        UUID token=UUID.randomUUID();
        var context=new BuildExecutionContext(artifact.id(),pair,1,token,source,slot.resolve("build-"+token));
        assertThrows(SQLException.class,()->ExecutionStorageLeases.reserveInTransaction(connection,context,resource));
        requireUnallocated(pair);
        assertEquals(0,leases()); assertEquals(20,usage()); assertEquals(0,contents());
    }

    @Test void anEmptyButLeasedResourceCannotBeGivenToAnotherAttempt() throws Exception {
        try (var physical=mockStatic(Files.class,invocation->{
            if (invocation.getMethod().getName().equals("createDirectory")
                    && slot.equals(((Path)invocation.getArgument(0)).getParent())) throw new IOException("injected first allocation failure");
            return invocation.callRealMethod();
        })) { assertThrows(IOException.class,this::prepare); }
        int second=createBuildPair();
        assertThrows(SQLException.class,()->SolverBuildStaging.prepareLeased(connection,second,1,resource,512));
        requireUnallocated(second);
        assertEquals(1,leases()); assertEquals(532,usage()); assertEquals(0,contents());
        assertTrue(Files.isDirectory(prepare().staging()));
    }

    @Test void concurrentPreparationsCannotBothWriteIntoOneResource() throws Exception {
        int second=createBuildPair();
        var executor=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<BuildExecutionContext>> futures=List.of(pair,second).stream().map(requested->executor.submit(()->{
                try (Connection own=Common.getConnection()) {
                    ready.countDown();
                    assertTrue(start.await(10,TimeUnit.SECONDS));
                    return SolverBuildStaging.prepareLeased(own,requested,1,resource,512);
                }
            })).toList();
            assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown();
            int prepared=0,refused=0;
            for (var future:futures) {
                try {
                    var result=future.get(15,TimeUnit.SECONDS);
                    assertTrue(Files.isDirectory(result.staging())); prepared++;
                } catch (ExecutionException failure) {
                    assertTrue(failure.getCause() instanceof SQLException || failure.getCause() instanceof IOException);
                    refused++;
                }
            }
            assertEquals(1,prepared); assertEquals(1,refused);
            assertEquals(1,leases()); assertEquals(532,usage()); assertEquals(1,contents());
        } finally {
            start.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));
        }
    }

    @Test void unregisteredResourceContentsCannotBeAdopted() throws Exception {
        Files.writeString(slot.resolve("keep"),"foreign contents");
        assertThrows(IOException.class,this::prepare);
        requireUnallocated(pair);
        assertEquals(0,leases()); assertEquals(20,usage());
        assertEquals("foreign contents",Files.readString(slot.resolve("keep")));
    }

    @Test void aLegacyDirectoryWithoutALeaseCannotBeAdopted() throws Exception {
        var legacy=SolverBuildStaging.prepare(connection,pair,1,slot,512);
        Files.writeString(legacy.staging().resolve("keep"),"unleased data");
        IOException failure=assertThrows(IOException.class,this::prepare);
        assertTrue(failure.getMessage().contains("without a conserved lease"));
        assertEquals(0,leases()); assertEquals(532,usage());
        assertEquals(legacy.token(),SolverBuildArtifacts.find(connection,pair,1).orElseThrow().executionToken());
        assertEquals("unleased data",Files.readString(legacy.staging().resolve("keep")));
    }

    @Test void aRegistryOnlyReservationWithNoFilesCanAcquireItsLease() throws Exception {
        var legacy=SolverBuildStaging.prepare(connection,pair,1,slot,512);
        Files.delete(legacy.staging());
        assertEquals(legacy,prepare());
        assertEquals(1,leases()); assertEquals(532,usage()); assertEquals(1,contents());
    }

    @Test void aRetryCannotReplaceItsBudget() throws Exception {
        var first=prepare();
        Files.writeString(first.staging().resolve("keep"),"prepared input");
        assertThrows(SQLException.class,()->SolverBuildStaging.prepareLeased(connection,pair,1,resource,513));
        assertEquals(1,leases()); assertEquals(532,usage());
        assertEquals("prepared input",Files.readString(first.staging().resolve("keep")));
        assertEquals(first,prepare());
    }

    @Test void aSymlinkedRetryPreservesItsTargetAndRetainsTheLease() throws Exception {
        var first=prepare();
        Path target=directory("foreign");
        Files.writeString(target.resolve("keep"),"foreign contents");
        Files.delete(first.staging()); Files.createSymbolicLink(first.staging(),target);
        assertThrows(IOException.class,this::prepare);
        assertEquals(1,leases()); assertEquals(532,usage());
        assertEquals("foreign contents",Files.readString(target.resolve("keep")));
        assertTrue(Files.isSymbolicLink(first.staging()));
    }

    @Test void aBoundExecutionCannotPrepareAgain() throws Exception {
        var first=prepare();
        assertTrue(SolverBuildArtifacts.bindExecution(connection,pair,1,first.token(),"podman:owned-prepared-build"));
        assertThrows(IOException.class,this::prepare);
        assertEquals(1,leases()); assertEquals(532,usage()); assertEquals(1,contents());
    }

    @Test void anUnprotectedRootCannotAcquireALease() throws Exception {
        Files.setPosixFilePermissions(slot,PosixFilePermissions.fromString("rwxrwx---"));
        assertThrows(IOException.class,this::prepare);
        requireUnallocated(pair);
        assertEquals(0,leases()); assertEquals(20,usage()); assertEquals(0,contents());
    }

    @Test void unavailableStagingMetadataCannotBeTreatedAsAbsence() throws Exception {
        var legacy=SolverBuildStaging.prepare(connection,pair,1,slot,512);
        Files.writeString(legacy.staging().resolve("keep"),"unleased data");
        try (var physical=mockStatic(Files.class,invocation->{
            if (legacy.staging().equals(invocation.getArgument(0))) {
                if (invocation.getMethod().getName().equals("exists")) return false;
                if (invocation.getMethod().getName().equals("readAttributes")) {
                    throw new java.nio.file.AccessDeniedException("injected staging metadata failure");
                }
            }
            return invocation.callRealMethod();
        })) { assertThrows(IOException.class,this::prepare); }
        assertEquals(0,leases()); assertEquals(532,usage());
        assertEquals("unleased data",Files.readString(legacy.staging().resolve("keep")));
    }
}
