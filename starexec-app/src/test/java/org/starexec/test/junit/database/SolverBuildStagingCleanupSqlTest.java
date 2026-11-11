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
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.Common;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;
import org.starexec.jobs.SolverBuildPublication;
import org.starexec.jobs.SolverBuildStagingCleanup;
import org.starexec.jobs.SolverBuildCleanup;
import org.starexec.jobs.SolverArtifactTree;
import org.starexec.test.util.DatabaseTestSupport;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL and owned files; provider capacity and stop observations are synthetic. */
class SolverBuildStagingCleanupSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private DedicatedStorageResource resource;
    private BuildExecutionContext build;
    private Path slot;
    private Path outputParent;
    private Path source;
    private int user;
    private int solver;
    private int pair;
    private int job;
    private String key;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("SolverBuildStagingCleanupSqlTest");
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

    private static String text(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement=con.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            try (var result=statement.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
        }
    }

    private Path directory(String name) throws IOException {
        return Files.createDirectory(files.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @BeforeEach void seed() throws Exception {
        connection = Common.getConnection();
        slot = directory("slot"); outputParent = directory("outputs"); source = directory("source_src");
        Files.writeString(source.resolve("starexec_build"), "original source");
        user = (int) number(connection, "INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                + "disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Cleanup','synthetic',now(),'synthetic',10000,100,20) RETURNING id",
                "staging-" + UUID.randomUUID() + "@example.invalid");
        solver = (int) number(connection, "INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                + " VALUES (?,'staging',now(),?,20,0) RETURNING id", user, source.toString());
        job = (int) number(connection, "INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                + " VALUES (?,'build',1,0,true) RETURNING id", user);
        pair = (int) number(connection, "INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id", job);
        execute(connection, "INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)", pair, solver);
        var artifact = SolverBuildArtifacts.find(connection, pair, 1).orElseThrow();
        UUID token = UUID.randomUUID();
        build = new BuildExecutionContext(artifact.id(), pair, 1, token, source, slot.resolve("build-" + token));
        Common.runTransactional(connection, tx -> {
            assertTrue(SolverBuildArtifacts.prepare(tx, pair, 1, token, build.staging()));
            assertTrue(SolverBuildArtifacts.reserveStorageBytes(tx, build, "STAGING", build.staging(), 512));
            return true;
        });
        key = UUID.randomUUID().toString().replace("-", "");
        resource = mock(DedicatedStorageResource.class);
        when(resource.root()).thenReturn(slot); when(resource.filesystemKey()).thenReturn(key);
        when(resource.identity()).thenReturn("observed-staging-device-and-mount");
        when(resource.capacityBytes()).thenReturn(256L); when(resource.inodeLimit()).thenReturn(16L);
        assertTrue(ExecutionStorageLeases.register(connection, resource));
        assertTrue(ExecutionStorageLeases.reserve(connection, build, resource));
        Files.createDirectory(build.staging());
        Files.writeString(Files.createDirectory(build.staging().resolve("solver")).resolve("program"), "binary");
        Files.writeString(Files.createDirectory(build.staging().resolve("logs")).resolve("starexec_build_log"), "build log");
    }

    @AfterEach void closeOwnedFiles() throws Exception {
        if (connection != null) connection.close();
        Files.walkFileTree(files, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void stopped() throws Exception {
        assertTrue(ExecutionStorageLeases.admit(connection, build, resource, "podman:7:owned-staging-build"));
        JsonObject facts = new JsonObject(); facts.addProperty("status", "exited");
        var receipt = SolverBuildStopReceipt.observed("podman", build.artifactId(), pair, 1,
                "podman:7:owned-staging-build", "owned-staging-build", Instant.now(), facts);
        assertTrue(SolverBuildArtifacts.recordStopReceipt(connection, pair, 1, build.token(), "podman:7:owned-staging-build", receipt));
    }

    private void publish() throws Exception {
        stopped();
        assertTrue(SolverBuildPublication.publish(connection, build, outputParent, 256, 32));
    }

    private boolean purge() throws SQLException { return SolverBuildStagingCleanup.purge(connection,build,resource,32); }
    private long usage() throws SQLException { return number(connection,"SELECT disk_size FROM starexec.users WHERE id=?",user); }
    private long activeLeases() throws SQLException {
        return number(connection,"SELECT count(*) FROM starexec.solver_build_storage_leases WHERE resource_key=? AND released_at IS NULL",key);
    }
    private ExecutionStorageLeases.StagingCleanup cleanupRecord() throws SQLException {
        return ExecutionStorageLeases.findStagingCleanup(connection,build,resource).orElseThrow();
    }

    @Test void aPublishedBuildCanReturnItsEmptyResourceWithoutDeletingItsExecutable() throws Exception {
        publish();
        Path immutable = Path.of(SolverBuildArtifacts.find(connection, pair, 1).orElseThrow().storageRoot());
        // Model already absent staging after a trusted stopped copy, independently of pool settlement.
        try (DescriptorDirectory parent = DescriptorDirectory.open(slot)) {
            parent.deleteTree(build.staging().getFileName().toString(), 32); parent.force();
        }
        assertTrue(SolverBuildStagingCleanup.purge(connection,build,resource,32));
        assertTrue(ExecutionStorageLeases.release(connection, build, resource));
        assertEquals("binary", Files.readString(immutable.resolve("solver/program")));
        assertEquals("PUBLISHED", SolverBuildArtifacts.find(connection, pair, 1).orElseThrow().state());
        assertEquals(20, number(connection, "SELECT disk_size FROM starexec.solvers WHERE id=?", solver));
        assertTrue(Files.exists(source.resolve("starexec_build")));
    }

    @Test void populatedStagingCleanupPreservesPublishedBytesSourceAndSeparateCharges() throws Exception {
        publish();
        var manifest=SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).orElseThrow();
        Path immutable=Path.of(SolverBuildArtifacts.find(connection,pair,1).orElseThrow().storageRoot());
        long before=usage();
        assertTrue(purge());
        assertFalse(Files.exists(build.staging())); assertEquals(0,activeLeases());
        assertEquals(512,before-usage()); assertEquals(20+manifest.byteSize(),usage());
        assertEquals(manifest,SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).orElseThrow());
        assertEquals("binary",Files.readString(immutable.resolve("solver/program")));
        assertEquals("original source",Files.readString(source.resolve("starexec_build")));
        assertEquals(1,number(connection,"SELECT count(*) FROM starexec.solver_build_storage_charges WHERE artifact_id=? AND storage_kind='OUTPUT' AND released_at IS NULL",build.artifactId()));
        assertEquals(1,number(connection,"SELECT count(*) FROM starexec.solvers WHERE id=? AND path=? AND disk_size=20 AND deleted IS NOT TRUE",solver,immutable.resolve("solver").toString()));
        var completed=cleanupRecord(); assertNotNull(completed.token()); assertNotNull(completed.finishedAt());
        assertTrue(purge()); assertEquals(completed,cleanupRecord()); assertEquals(20+manifest.byteSize(),usage());
        assertThrows(SQLException.class,()->execute(connection,"UPDATE starexec.solver_build_storage_charges SET released_at=clock_timestamp() WHERE artifact_id=? AND storage_kind='OUTPUT'",build.artifactId()));
    }

    @ParameterizedTest @ValueSource(strings={"unbound","unstopped","unpublished","rejected-unconserved"})
    void unknownOrUnconservedOutcomesKeepStagingAndItsAllowance(String state) throws Exception {
        if (state.equals("unstopped")) assertTrue(ExecutionStorageLeases.admit(connection,build,resource,"podman:7:owned-staging-build"));
        if (state.equals("unpublished") || state.equals("rejected-unconserved")) stopped();
        if (state.equals("rejected-unconserved")) execute(connection,"UPDATE starexec.solver_build_artifacts SET state='REJECTED' WHERE id=?",build.artifactId());
        long before=usage(); assertFalse(purge()); assertEquals(before,usage());
        assertTrue(Files.exists(build.staging().resolve("solver/program"))); assertEquals(1,activeLeases());
        assertNull(cleanupRecord().token());
    }

    @Test void corruptedImmutableBytesRefuseStagingRemoval() throws Exception {
        publish();
        Path immutable=Path.of(SolverBuildArtifacts.find(connection,pair,1).orElseThrow().storageRoot());
        Path program=immutable.resolve("solver/program");
        Files.setPosixFilePermissions(program,PosixFilePermissions.fromString("rw-------"));
        Files.writeString(program,"changed");
        long before=usage(); SQLException failure=assertThrows(SQLException.class,this::purge);
        assertInstanceOf(IOException.class,failure.getCause());
        assertEquals(before,usage()); assertEquals(1,activeLeases());
        assertEquals("binary",Files.readString(build.staging().resolve("solver/program")));
        assertEquals("changed",Files.readString(program)); assertNotNull(cleanupRecord().token());
    }

    @Test void aForceFailureRetainsTheSameClaimAndAllowanceForRetry() throws Exception {
        publish(); long before=usage();
        try (var directories=mockStatic(DescriptorDirectory.class,invocation->{
            if (invocation.getMethod().getName().equals("open") && slot.equals(invocation.getArgument(0))) {
                DescriptorDirectory observed=spy((DescriptorDirectory)invocation.callRealMethod());
                doThrow(new IOException("INJECTED STAGING FORCE FAILURE")).when(observed).force(); return observed;
            }
            return invocation.callRealMethod();
        })) {
            SQLException failed=assertThrows(SQLException.class,this::purge);
            assertTrue(failed.getMessage().contains("INJECTED STAGING FORCE FAILURE"));
        }
        UUID token=cleanupRecord().token(); assertNotNull(token);
        assertFalse(Files.exists(build.staging())); assertEquals(before,usage()); assertEquals(1,activeLeases());
        assertTrue(purge()); assertEquals(token,cleanupRecord().token()); assertEquals(before-512,usage());
    }

    @Test void partialDirectoryRemovalCanRetryOnlyTheConservedRoot() throws Exception {
        publish(); long before=usage();
        try (var directories=mockStatic(DescriptorDirectory.class,invocation->{
            if (invocation.getMethod().getName().equals("open") && slot.equals(invocation.getArgument(0))) {
                DescriptorDirectory observed=spy((DescriptorDirectory)invocation.callRealMethod());
                doAnswer(call->{
                    Files.delete(build.staging().resolve("logs/starexec_build_log"));
                    Files.delete(build.staging().resolve("logs"));
                    throw new IOException("INJECTED PARTIAL STAGING CLEANUP");
                }).when(observed).deleteTree(build.staging().getFileName().toString(),32);
                return observed;
            }
            return invocation.callRealMethod();
        })) {
            assertThrows(SQLException.class,this::purge);
        }
        var original=cleanupRecord(); assertNotNull(original.stagingIdentity());
        assertEquals(before,usage()); assertEquals(1,activeLeases());
        assertTrue(Files.exists(build.staging().resolve("solver/program")));
        assertTrue(purge()); assertEquals(original.token(),cleanupRecord().token());
        assertEquals(before-512,usage());
    }

    private String installCommitFailure() throws SQLException {
        String name="staging_commit_failure_"+UUID.randomUUID().toString().replace("-","");
        execute(connection,"CREATE FUNCTION starexec."+name+"() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'INJECTED STAGING COMMIT FAILURE'; END; $$ LANGUAGE plpgsql");
        execute(connection,"CREATE CONSTRAINT TRIGGER "+name+" AFTER UPDATE ON starexec.solver_build_storage_leases DEFERRABLE INITIALLY DEFERRED FOR EACH ROW"
                +" WHEN (NEW.artifact_id="+build.artifactId()+" AND NEW.released_at IS NOT NULL AND OLD.released_at IS NULL) EXECUTE FUNCTION starexec."+name+"()");
        return name;
    }

    private void dropCommitFailure(String name) throws SQLException {
        execute(connection,"DROP TRIGGER "+name+" ON starexec.solver_build_storage_leases");
        execute(connection,"DROP FUNCTION starexec."+name+"()");
    }

    @Test void failedSettlementCommitKeepsTheChargeAfterPhysicalRemoval() throws Exception {
        publish(); long before=usage(); String injected=installCommitFailure();
        try {
            SQLException failed=assertThrows(SQLException.class,this::purge);
            assertTrue(failed.getMessage().contains("INJECTED STAGING COMMIT FAILURE"));
            assertEquals(before,usage()); assertEquals(1,activeLeases()); assertFalse(Files.exists(build.staging()));
        } finally { dropCommitFailure(injected); }
        var original=cleanupRecord(); assertNotNull(original.token()); assertNull(original.finishedAt());
        assertTrue(purge()); assertEquals(original.token(),cleanupRecord().token()); assertEquals(before-512,usage());
        assertTrue(purge()); assertEquals(before-512,usage());
    }

    @Test void aReappearedDirectoryAfterFailedCommitIsNeverAdoptedOrDeleted() throws Exception {
        publish(); long before=usage(); String injected=installCommitFailure();
        try { assertThrows(SQLException.class,this::purge); } finally { dropCommitFailure(injected); }
        var original=cleanupRecord();
        Files.createDirectory(build.staging()); Path foreign=Files.writeString(build.staging().resolve("foreign"),"keep");
        SQLException failed=assertThrows(SQLException.class,this::purge);
        assertTrue(failed.getMessage().contains("identity changed"));
        assertEquals("keep",Files.readString(foreign)); assertEquals(before,usage()); assertEquals(1,activeLeases());
        assertEquals(original,cleanupRecord());
    }

    @Test void anInitiallyAbsentDirectoryCannotBeAdoptedIfItAppearsAfterTheClaim() throws Exception {
        publish();
        try (DescriptorDirectory parent=DescriptorDirectory.open(slot)) { parent.deleteTree(build.staging().getFileName().toString(),32); parent.force(); }
        AtomicInteger opens=new AtomicInteger();
        try (var directories=mockStatic(DescriptorDirectory.class,invocation->{
            if (invocation.getMethod().getName().equals("open") && slot.equals(invocation.getArgument(0)) && opens.incrementAndGet()==2) {
                Files.createDirectory(build.staging()); Files.writeString(build.staging().resolve("foreign"),"keep");
            }
            return invocation.callRealMethod();
        })) { assertThrows(SQLException.class,this::purge); }
        assertEquals("ABSENT",cleanupRecord().stagingIdentity()); assertEquals(1,activeLeases());
        assertEquals("keep",Files.readString(build.staging().resolve("foreign")));
    }

    @Test void unknownResourceContentsStayUntouchedAndKeepTheLease() throws Exception {
        publish(); long before=usage(); Path foreign=Files.writeString(slot.resolve("unknown"),"keep");
        SQLException failed=assertThrows(SQLException.class,this::purge);
        assertTrue(failed.getMessage().contains("unregistered contents"));
        assertEquals("keep",Files.readString(foreign)); assertEquals(before,usage()); assertEquals(1,activeLeases());
        UUID token=cleanupRecord().token();
        Files.delete(foreign);
        assertTrue(purge()); assertEquals(token,cleanupRecord().token()); assertEquals(before-512,usage());
    }

    @Test void aSymbolicStagingRootCannotAuthorizeRemovalOfItsTarget() throws Exception {
        publish();
        try (DescriptorDirectory parent=DescriptorDirectory.open(slot)) { parent.deleteTree(build.staging().getFileName().toString(),32); parent.force(); }
        Path other=directory("other"); Path keep=Files.writeString(other.resolve("keep"),"foreign");
        Files.createSymbolicLink(build.staging(),other);
        assertThrows(SQLException.class,this::purge);
        assertEquals("foreign",Files.readString(keep)); assertEquals(1,activeLeases()); assertNull(cleanupRecord().token());
    }

    @Test void directoryCreationIdentitySurvivesChildChanges() throws Exception {
        var before=Files.readAttributes(build.staging(),BasicFileAttributes.class);
        Path child=Files.writeString(build.staging().resolve("child"),"owned"); Files.delete(child);
        var after=Files.readAttributes(build.staging(),BasicFileAttributes.class);
        assertEquals(before.fileKey(),after.fileKey()); assertEquals(before.creationTime(),after.creationTime());
        assertTrue(before.creationTime().toMillis()>0);
    }

    @Test void failedBuildLogsStayConservedWhileOnlyStagingIsReclaimed() throws Exception {
        stopped(); execute(connection,"UPDATE starexec.job_pairs SET status_code=11 WHERE id=?",pair);
        assertTrue(SolverBuildPublication.recordFailure(connection,build,outputParent,256,32));
        var manifest=SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).orElseThrow();
        Path immutable=Path.of(SolverBuildArtifacts.find(connection,pair,1).orElseThrow().storageRoot());
        long status=number(connection,"SELECT build_status FROM starexec.solvers WHERE id=?",solver);
        assertTrue(purge());
        assertEquals("REJECTED",SolverBuildArtifacts.find(connection,pair,1).orElseThrow().state());
        assertEquals("build log",Files.readString(immutable.resolve("logs/starexec_build_log")));
        assertFalse(Files.exists(immutable.resolve("solver"))); assertEquals(20+manifest.byteSize(),usage());
        assertEquals(status,number(connection,"SELECT build_status FROM starexec.solvers WHERE id=?",solver));
        assertTrue(Files.exists(source.resolve("starexec_build")));
        assertTrue(SolverBuildPublication.recordFailure(connection,build,outputParent,256,32));
        assertEquals(manifest,SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).orElseThrow());
    }

    @ParameterizedTest @ValueSource(strings={"cancelled","never_started"})
    void explicitNonCompletionReclaimsStagingWithoutInventingAScientificOutcome(String kind) throws Exception {
        String backend=kind.equals("never_started") ? "kubernetes" : "podman";
        String engine=backend+":7:owned-staging-build";
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine));
        JsonObject facts=new JsonObject(); facts.addProperty("disposition",kind); facts.addProperty("status","exited");
        if (kind.equals("never_started")) { facts.addProperty("suspended",true); facts.addProperty("pod_count",0); }
        var receipt=SolverBuildStopReceipt.observed(backend,build.artifactId(),pair,1,engine,"owned-staging-build",Instant.now(),facts);
        assertTrue(kind.equals("never_started")
                ? SolverBuildArtifacts.recordUnstarted(connection,pair,1,build.token(),engine,receipt)
                : SolverBuildArtifacts.recordCancelled(connection,pair,1,build.token(),engine,receipt));
        long status=number(connection,"SELECT build_status FROM starexec.solvers WHERE id=?",solver);
        assertTrue(purge()); assertEquals(20,usage()); assertEquals(0,activeLeases());
        assertEquals(status,number(connection,"SELECT build_status FROM starexec.solvers WHERE id=?",solver));
        assertEquals("REJECTED",SolverBuildArtifacts.find(connection,pair,1).orElseThrow().state());
        assertEquals(receipt.payload(),SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt().payload());
        assertTrue(SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).isEmpty());
        assertEquals("original source",Files.readString(source.resolve("starexec_build")));
    }

    @Test void localMetadataCannotSubstituteForACompletePhysicalScope() throws Exception {
        String engine="local:7:owned-staging-build";
        assertTrue(ExecutionStorageLeases.admit(connection,build,resource,engine));
        JsonObject facts=new JsonObject(); facts.addProperty("status","exited");
        var receipt=SolverBuildStopReceipt.observed("local",build.artifactId(),pair,1,engine,"owned-staging-build",Instant.now(),facts);
        assertTrue(SolverBuildArtifacts.recordStopReceipt(connection,pair,1,build.token(),engine,receipt));
        Path copied=outputParent.resolve("local-metadata-only");
        var snapshot=SolverArtifactTree.copy(build.staging(),copied,256,32);
        execute(connection,"UPDATE starexec.solver_build_artifacts SET state='STAGED',storage_root=?,artifact_path=?,"
                +"manifest_payload=?,manifest_sha256=?,byte_size=?,validated_at=clock_timestamp() WHERE id=?",
                copied.toString(),copied.resolve("solver").toString(),snapshot.payload(),snapshot.sha256(),snapshot.byteSize(),build.artifactId());
        execute(connection,"UPDATE starexec.solver_build_artifacts SET state='REJECTED' WHERE id=?",build.artifactId());
        assertEquals(1,number(connection,"SELECT starexec.solver_build_stop_receipt_matches(execution_stop_receipt_payload,execution_stop_receipt_sha256,id,origin_pair_id,attempt_no,engine_identity)::integer FROM starexec.solver_build_artifacts WHERE id=?",build.artifactId()));
        assertFalse(purge()); assertEquals(1,activeLeases()); assertEquals(532,usage());
        assertTrue(Files.exists(build.staging().resolve("solver/program"))); assertNull(cleanupRecord().token());
    }

    @Test void validatedButUnappliedOutputDoesNotAuthorizeStagingCleanup() throws Exception {
        stopped();
        try (var artifacts=mockStatic(SolverBuildArtifacts.class,CALLS_REAL_METHODS)) {
            artifacts.when(()->SolverBuildArtifacts.publishOutput(connection,build.artifactId())).thenReturn(false);
            assertFalse(SolverBuildPublication.publish(connection,build,outputParent,256,32));
        }
        assertEquals("VALIDATED",SolverBuildArtifacts.find(connection,pair,1).orElseThrow().state());
        assertFalse(purge()); assertTrue(Files.exists(build.staging().resolve("solver/program")));
        assertEquals(1,activeLeases()); assertNull(cleanupRecord().token());
    }

    @Test void confinedCleanupUnlinksLinksWithoutChangingTheirForeignTargets() throws Exception {
        publish();
        Path outside=Files.writeString(files.resolve("outside"),"foreign");
        Files.setPosixFilePermissions(outside,PosixFilePermissions.fromString("rw-r-----"));
        var permissions=Files.getPosixFilePermissions(outside);
        Files.createSymbolicLink(build.staging().resolve("symbolic"),outside);
        Files.createLink(build.staging().resolve("shared-inode"),outside);
        assertTrue(purge()); assertEquals("foreign",Files.readString(outside));
        assertEquals(permissions,Files.getPosixFilePermissions(outside));
    }

    private BuildExecutionContext nextBuild() throws Exception {
        Path nextSource=directory("next_src"); Files.writeString(nextSource.resolve("starexec_build"),"next source");
        execute(connection,"UPDATE starexec.users SET disk_size=disk_size+20 WHERE id=?",user);
        int nextSolver=(int)number(connection,"INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status) VALUES (?,'next',now(),?,20,0) RETURNING id",user,nextSource.toString());
        int nextJob=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'next',1,0,true) RETURNING id",user);
        int nextPair=(int)number(connection,"INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id",nextJob);
        execute(connection,"INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)",nextPair,nextSolver);
        UUID token=UUID.randomUUID();
        var context=new BuildExecutionContext(SolverBuildArtifacts.find(connection,nextPair,1).orElseThrow().id(),nextPair,1,token,nextSource,slot.resolve("build-"+token));
        Common.runTransactional(connection,tx->{
            assertTrue(SolverBuildArtifacts.prepare(tx,nextPair,1,token,context.staging()));
            assertTrue(SolverBuildArtifacts.reserveStorageBytes(tx,context,"STAGING",context.staging(),512));
            return true;
        });
        return context;
    }

    @Test void completedCleanupNeverTouchesAReusedResourceOrRefundsStagingTwice() throws Exception {
        publish(); assertTrue(purge());
        var completed=cleanupRecord(); var manifest=SolverBuildArtifacts.findOutputManifest(connection,build.artifactId()).orElseThrow();
        var next=nextBuild(); assertTrue(ExecutionStorageLeases.reserve(connection,next,resource));
        Files.createDirectory(next.staging()); Path marker=Files.writeString(next.staging().resolve("keep"),"new owner");
        long before=usage();
        assertTrue(purge()); assertEquals(completed,cleanupRecord());
        assertEquals("new owner",Files.readString(marker)); assertEquals(before,usage()); assertEquals(1,activeLeases());
        execute(connection,"DELETE FROM starexec.jobs WHERE id=?",job);
        execute(connection,"UPDATE starexec.solvers SET deleted=true WHERE id=?",solver);
        assertTrue(SolverBuildCleanup.purge(connection,build.artifactId(),slot,outputParent,files,64));
        assertEquals(before-20-manifest.byteSize(),usage());
        assertEquals("new owner",Files.readString(marker)); assertEquals(1,activeLeases());
        assertTrue(purge()); assertEquals("new owner",Files.readString(marker));
    }

    @Test void aCompletedCleanupNeverDeletesAReappearedOldStagingPath() throws Exception {
        publish(); assertTrue(purge()); var completed=cleanupRecord();
        Files.createDirectory(build.staging()); Path foreign=Files.writeString(build.staging().resolve("foreign"),"keep");
        assertThrows(SQLException.class,this::purge);
        assertEquals("keep",Files.readString(foreign)); assertEquals(completed,cleanupRecord());
    }

    @Test void fullCleanupCanBeRecoveredAfterItsAccountingOwnerWasDeleted() throws Exception {
        publish();
        execute(connection,"DELETE FROM starexec.jobs WHERE id=?",job);
        execute(connection,"UPDATE starexec.solvers SET deleted=true WHERE id=?",solver);
        assertTrue(SolverBuildCleanup.purge(connection,build.artifactId(),slot,outputParent,files,64));
        execute(connection,"DELETE FROM starexec.users WHERE id=?",user);
        assertTrue(purge()); assertEquals(0,activeLeases());
        assertEquals("DELETED",SolverBuildArtifacts.find(connection,pair,1).orElseThrow().state());
    }

    @ParameterizedTest @ValueSource(strings={"solver","source-hint","benchmark","artifact-source","artifact-root","artifact-output","dotted"})
    void durableAssetsCannotAcquireReferencesIntoPrivateExecutionStorage(String kind) throws Exception {
        String path=slot.resolve("foreign").toString();
        if (kind.equals("dotted")) path=files.resolve("not-there/../slot/foreign").toString();
        String target=path;
        SQLException rejected=assertThrows(SQLException.class,()->{
            switch (kind) {
                case "solver","dotted" -> execute(connection,"UPDATE starexec.solvers SET path=? WHERE id=?",target,solver);
                case "source-hint" -> execute(connection,"UPDATE starexec.solvers SET build_source_path=? WHERE id=?",target,solver);
                case "benchmark" -> execute(connection,"INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size) VALUES (?,'foreign',now(),?,1)",user,target);
                case "artifact-source" -> execute(connection,"UPDATE starexec.solver_build_artifacts SET source_path=? WHERE id=?",target,build.artifactId());
                case "artifact-root" -> execute(connection,"UPDATE starexec.solver_build_artifacts SET storage_root=? WHERE id=?",target,build.artifactId());
                case "artifact-output" -> execute(connection,"UPDATE starexec.solver_build_artifacts SET artifact_path=? WHERE id=?",target,build.artifactId());
                default -> throw new AssertionError(kind);
            }
        });
        assertEquals("23514",rejected.getSQLState());
        assertTrue(rejected.getMessage().contains("Application assets cannot reference private execution storage"));
        assertEquals(source.toString(),text(connection,"SELECT path FROM starexec.solvers WHERE id=?",solver));
    }

    private DedicatedStorageResource observation(Path root) {
        var observed=mock(DedicatedStorageResource.class);
        when(observed.root()).thenReturn(root); when(observed.identity()).thenReturn("other-observed-mount");
        when(observed.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-",""));
        when(observed.capacityBytes()).thenReturn(256L); when(observed.inodeLimit()).thenReturn(16L);
        return observed;
    }

    @Test void resourceRegistrationCannotOverlapPreexistingAssets() throws Exception {
        var observed=observation(source);
        SQLException rejected=assertThrows(SQLException.class,()->ExecutionStorageLeases.register(connection,observed));
        assertEquals("23514",rejected.getSQLState());
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.execution_storage_resources WHERE resource_key=?",observed.filesystemKey()));
        assertEquals("original source",Files.readString(source.resolve("starexec_build")));
    }

    @Test void concurrentAssetReferenceCannotRaceAResourceRegistration() throws Exception {
        Path proposed=directory("proposed"); var observed=observation(proposed);
        String oldHint=text(connection,"SELECT build_source_path FROM starexec.solvers WHERE id=?",solver);
        try (Connection writer=Common.getConnection()) {
            writer.setAutoCommit(false);
            try {
                execute(writer,"UPDATE starexec.solvers SET build_source_path=? WHERE id=?",proposed.resolve("input").toString(),solver);
                SQLException contended=assertThrows(SQLException.class,()->ExecutionStorageLeases.register(connection,observed));
                assertEquals("55P03",contended.getSQLState());
                writer.commit();
                SQLException referenced=assertThrows(SQLException.class,()->ExecutionStorageLeases.register(connection,observed));
                assertEquals("23514",referenced.getSQLState());
                assertEquals(0,number(connection,"SELECT count(*) FROM starexec.execution_storage_resources WHERE resource_key=?",observed.filesystemKey()));
            } finally { writer.rollback(); writer.setAutoCommit(true); }
        } finally { execute(connection,"UPDATE starexec.solvers SET build_source_path=? WHERE id=?",oldHint,solver); }
    }

    @Test void aRepeatableReadWriterCannotMissACommittedResourceRegistration() throws Exception {
        Path proposed=directory("proposed"); var observed=observation(proposed);
        String oldHint=text(connection,"SELECT build_source_path FROM starexec.solvers WHERE id=?",solver);
        try (Connection writer=Common.getConnection()) {
            int isolation=writer.getTransactionIsolation();
            writer.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); writer.setAutoCommit(false);
            try {
                assertEquals(1,number(writer,"SELECT count(*) FROM starexec.execution_storage_policy"));
                assertTrue(ExecutionStorageLeases.register(connection,observed));
                SQLException stale=assertThrows(SQLException.class,()->execute(writer,
                        "UPDATE starexec.solvers SET build_source_path=? WHERE id=?",proposed.resolve("input").toString(),solver));
                assertEquals("40001",stale.getSQLState());
            } finally { writer.rollback(); writer.setAutoCommit(true); writer.setTransactionIsolation(isolation); }
        }
        assertEquals(oldHint,text(connection,"SELECT build_source_path FROM starexec.solvers WHERE id=?",solver));
    }

    @Test void metadataCannotReleaseTheLeaseWithoutSettlingStagingInTheSameCommit() throws Exception {
        publish();
        var attributes=Files.readAttributes(build.staging(),BasicFileAttributes.class);
        String identity=attributes.fileKey()+":"+attributes.creationTime(); UUID token=UUID.randomUUID();
        boolean claimed=Common.runTransactional(connection,tx->ExecutionStorageLeases.claimStagingCleanup(tx,build,resource,token,identity));
        assertTrue(claimed);
        SQLException rejected=assertThrows(SQLException.class,()->Common.runTransactional(connection,tx->{
            execute(tx,"WITH stamp AS MATERIALIZED (SELECT clock_timestamp() t) UPDATE starexec.solver_build_storage_leases"
                    +" SET cleanup_finished_at=stamp.t,released_at=stamp.t FROM stamp WHERE artifact_id=?",build.artifactId());
            return true;
        }));
        assertTrue(rejected.getMessage().contains("unsettled staging charge"));
        assertEquals(1,activeLeases()); assertNull(cleanupRecord().finishedAt());
        assertTrue(Files.exists(build.staging()));
    }

    @Test void cleanupTokenAndDirectoryIdentityCannotBeChangedAfterClaim() throws Exception {
        publish();
        var attributes=Files.readAttributes(build.staging(),BasicFileAttributes.class);
        String identity=attributes.fileKey()+":"+attributes.creationTime(); UUID token=UUID.randomUUID();
        boolean claimed=Common.runTransactional(connection,tx->ExecutionStorageLeases.claimStagingCleanup(tx,build,resource,token,identity));
        assertTrue(claimed);
        var original=cleanupRecord();
        assertThrows(SQLException.class,()->execute(connection,"UPDATE starexec.solver_build_storage_leases SET cleanup_token=? WHERE artifact_id=?",UUID.randomUUID(),build.artifactId()));
        assertThrows(SQLException.class,()->execute(connection,"UPDATE starexec.solver_build_storage_leases SET cleanup_staging_identity='ABSENT' WHERE artifact_id=?",build.artifactId()));
        assertEquals(original,cleanupRecord()); assertTrue(purge());
    }

    @Test void aForeignTransactionOrDifferentContextCannotBeUsedForCleanup() throws Exception {
        publish();
        connection.setAutoCommit(false);
        try { assertThrows(SQLException.class,this::purge); assertFalse(connection.getAutoCommit()); }
        finally { connection.rollback(); connection.setAutoCommit(true); }
        var different=new BuildExecutionContext(build.artifactId(),pair+1,1,build.token(),build.source(),build.staging());
        assertFalse(SolverBuildStagingCleanup.purge(connection,different,resource,32));
        assertTrue(Files.exists(build.staging())); assertNull(cleanupRecord().token());
    }
}
