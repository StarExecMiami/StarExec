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
import org.starexec.data.database.Common;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.data.to.SolverBuildStopReceipt;
import org.starexec.jobs.SolverBuildCleanup;
import org.starexec.jobs.SolverBuildPublication;
import org.starexec.jobs.SolverBuildStaging;
import org.starexec.test.util.DatabaseTestSupport;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Admission and accounting with synthetic provider observations. */
class SolverBuildStorageQuotaSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private int user;
    private int solver;
    private int job;
    private int pair;
    private Path staging;
    private Path outputs;
    private BuildExecutionContext build;
    private String injected;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("SolverBuildStorageQuotaSqlTest");
        Common.initialize();
    }

    private static long number(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement=con.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            try (var result=statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }

    private static void execute(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement=con.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            statement.execute();
        }
    }

    private Path directory(String name) throws IOException {
        return Files.createDirectory(files.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @BeforeEach void seed() throws Exception {
        connection=Common.getConnection();
        user=(int)number(connection,"INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,"
                +"disk_quota,job_pair_quota,disk_size) VALUES (?,'Test','Quota','synthetic',now(),'synthetic',10000,100,1020) RETURNING id",
                "quota-"+UUID.randomUUID()+"@example.invalid");
        Path source=directory("source_src"); Files.writeString(source.resolve("starexec_build"),"original");
        solver=(int)number(connection,"INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                +" VALUES (?,'quota',now(),?,20,0) RETURNING id",user,source.toString());
        job=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob)"
                +" VALUES (?,'build',1,0,true) RETURNING id",user);
        pair=(int)number(connection,"INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id",job);
        execute(connection,"INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)",pair,solver);
        staging=directory("staging"); outputs=directory("outputs");
    }

    private long usage() throws SQLException { return number(connection,"SELECT disk_size FROM starexec.users WHERE id=?",user); }
    private Path root() { return outputs.resolve("build-"+build.artifactId()+"-"+build.token()); }

    private void prepare(long bytes) throws Exception {
        build=SolverBuildStaging.prepare(connection,pair,1,staging,bytes);
    }

    private void stopped() throws Exception {
        stopped(build);
    }

    private void stopped(BuildExecutionContext context) throws Exception {
        Files.writeString(Files.createDirectory(context.staging().resolve("solver")).resolve("program"),"program");
        String engine="podman:7:owned-quota-build";
        assertTrue(SolverBuildArtifacts.bindExecution(connection,context.pairId(),1,context.token(),engine));
        JsonObject facts=new JsonObject(); facts.addProperty("status","exited");
        var receipt=SolverBuildStopReceipt.observed("podman",context.artifactId(),context.pairId(),1,engine,
                "owned-quota-build",Instant.now(),facts);
        assertTrue(SolverBuildArtifacts.recordStopReceipt(connection,context.pairId(),1,context.token(),engine,receipt));
    }

    private boolean publish(long bytes) throws Exception {
        return SolverBuildPublication.publish(connection,build,outputs,bytes,32);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void launchAdmissionRequiresAPositiveStagingAllowance(boolean zeroBudget) throws Exception {
        if (zeroBudget) prepare(0);
        else build=SolverBuildStaging.prepare(connection,pair,1,staging);
        boolean admitted=Common.runTransactional(connection,
                tx->SolverBuildArtifacts.admitExecution(tx,build,"podman:7:owned-quota-build"));
        assertAll(() -> assertFalse(admitted),
                () -> assertEquals(0,number(connection,"SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND engine_identity IS NOT NULL",build.artifactId())),
                () -> assertEquals(1020,usage()));
    }

    @ParameterizedTest @ValueSource(strings={"local","podman","kubernetes"})
    void matchingBudgetAdmitsOnlyItsEngineWithoutBookingMoreBytes(String backend) throws Exception {
        prepare(100); String engine=backend+":7:owned-quota-build";
        assertTrue(SolverBuildArtifacts.admitExecution(build,engine));
        assertTrue(SolverBuildArtifacts.admitExecution(build,engine));
        assertFalse(SolverBuildArtifacts.admitExecution(build,engine+"-other"));
        assertEquals(1120,usage());
        assertEquals(1,number(connection,"SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND engine_identity=?",build.artifactId(),engine));
        assertEquals(1,number(connection,"SELECT count(*) FROM starexec.solver_build_storage_charges WHERE artifact_id=? AND storage_kind='STAGING' AND limit_bytes=100 AND released_at IS NULL",build.artifactId()));
    }

    @ParameterizedTest @ValueSource(strings={"artifact","pair","attempt","token","source","staging","blank-engine","null-engine"})
    void directSqlAdmissionCannotBorrowAnAllowancesIdentity(String changed) throws Exception {
        prepare(100);
        Object[] args={build.artifactId(),pair,1,build.token(),build.source().toString(),build.staging().toString(),"podman:7:owned-quota-build"};
        switch (changed) {
            case "artifact" -> args[0]=build.artifactId()+1;
            case "pair" -> args[1]=pair+1;
            case "attempt" -> args[2]=2;
            case "token" -> args[3]=UUID.randomUUID();
            case "source" -> args[4]=files.resolve("other-source").toString();
            case "staging" -> args[5]=files.resolve("other-staging").toString();
            case "blank-engine" -> args[6]="   ";
            case "null-engine" -> args[6]=null;
            default -> throw new AssertionError(changed);
        }
        boolean admitted=Common.runTransactional(connection,tx->number(tx,
                "SELECT starexec.admit_solver_build_execution(?,?,?,?,?,?,?)::integer",args)==1);
        assertFalse(admitted); assertEquals(1120,usage());
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND engine_identity IS NOT NULL",build.artifactId()));
    }

    @Test void aNewAttemptCannotReuseTheOldStagingAllowance() throws Exception {
        prepare(100); BuildExecutionContext older=build;
        execute(connection,"UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=?",pair);
        build=SolverBuildStaging.prepare(connection,pair,2,staging);
        assertFalse(SolverBuildArtifacts.admitExecution(older,"podman:7:older-quota-build"));
        assertFalse(SolverBuildArtifacts.admitExecution(build,"podman:8:new-quota-build"));
        assertEquals(1120,usage());
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.solver_build_artifacts WHERE origin_pair_id=? AND engine_identity IS NOT NULL",pair));
    }

    @Test void admissionRefusesAChargeNoLongerCoveredByOwnerUsage() throws Exception {
        prepare(100); long booked=usage();
        execute(connection,"UPDATE starexec.users SET disk_size=99 WHERE id=?",user);
        assertFalse(SolverBuildArtifacts.admitExecution(build,"podman:7:owned-quota-build"));
        assertEquals(99,usage());
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND engine_identity IS NOT NULL",build.artifactId()));
        execute(connection,"UPDATE starexec.users SET disk_size=? WHERE id=?",booked,user);
        assertTrue(SolverBuildArtifacts.admitExecution(build,"podman:7:owned-quota-build"));
        assertEquals(booked,usage());
    }

    @Test void admissionOwnerContentionCannotBindOrLoseItsAllowance() throws Exception {
        prepare(100);
        try (Connection competing=Common.getConnection()) {
            competing.setAutoCommit(false);
            try {
                number(competing,"SELECT id FROM starexec.users WHERE id=? FOR UPDATE",user);
                SQLException refused=assertThrows(SQLException.class,
                        ()->SolverBuildArtifacts.admitExecution(build,"podman:7:owned-quota-build"));
                assertEquals("55P03",refused.getSQLState()); assertEquals(1120,usage());
                assertEquals(0,number(connection,"SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND engine_identity IS NOT NULL",build.artifactId()));
            } finally { competing.rollback(); competing.setAutoCommit(true); }
        }
        assertTrue(SolverBuildArtifacts.admitExecution(build,"podman:7:owned-quota-build"));
        assertEquals(1120,usage());
    }

    @Test void failedAdmissionCommitRollsBackBindingWithoutReleasingItsBudget() throws Exception {
        prepare(100);
        String trigger="admission_fail_"+UUID.randomUUID().toString().replace("-","");
        execute(connection,"CREATE FUNCTION starexec."+trigger+"() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'INJECTED ADMISSION COMMIT FAILURE'; END; $$ LANGUAGE plpgsql");
        execute(connection,"CREATE CONSTRAINT TRIGGER "+trigger+" AFTER UPDATE ON starexec.solver_build_artifacts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW"
                +" WHEN (NEW.id="+build.artifactId()+" AND NEW.engine_identity IS NOT NULL) EXECUTE FUNCTION starexec."+trigger+"()");
        try {
            assertThrows(SQLException.class,()->SolverBuildArtifacts.admitExecution(build,"podman:7:owned-quota-build"));
            assertEquals(1120,usage());
            assertEquals(0,number(connection,"SELECT count(*) FROM starexec.solver_build_artifacts WHERE id=? AND engine_identity IS NOT NULL",build.artifactId()));
        } finally {
            execute(connection,"DROP TRIGGER "+trigger+" ON starexec.solver_build_artifacts");
            execute(connection,"DROP FUNCTION starexec."+trigger+"()");
        }
        assertTrue(SolverBuildArtifacts.admitExecution(build,"podman:7:owned-quota-build"));
        assertEquals(1120,usage());
    }

    @Test void refusedUnbudgetedLaunchStillAllowsItsLateNonExecutionReceipt() throws Exception {
        build=SolverBuildStaging.prepare(connection,pair,1,staging);
        String engine="kubernetes:7:owned-quota-build";
        assertFalse(SolverBuildArtifacts.admitExecution(build,engine));
        assertTrue(SolverBuildArtifacts.bindExecution(connection,pair,1,build.token(),engine));
        JsonObject facts=new JsonObject(); facts.addProperty("disposition","never_started");
        facts.addProperty("suspended",true); facts.addProperty("pod_count",0);
        var receipt=SolverBuildStopReceipt.observed("kubernetes",build.artifactId(),pair,1,engine,"owned-quota-build",Instant.now(),facts);
        assertTrue(SolverBuildArtifacts.recordUnstarted(connection,pair,1,build.token(),engine,receipt));
        assertEquals("REJECTED",SolverBuildArtifacts.find(connection,pair,1).orElseThrow().state());
        assertEquals(receipt.payload(),SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt().payload());
        assertFalse(SolverBuildArtifacts.admitExecution(build,engine)); assertEquals(1020,usage());
    }

    private boolean purge() throws SQLException {
        return SolverBuildCleanup.purge(connection,build.artifactId(),staging,outputs,files,100);
    }

    private void retire() throws SQLException {
        execute(connection,"DELETE FROM starexec.jobs WHERE id=?",job);
        execute(connection,"UPDATE starexec.solvers SET deleted=true WHERE id=?",solver);
    }

    @Test void stagingBudgetIsDurableAndRetryDoesNotBookItTwice() throws Exception {
        prepare(100);
        try (Connection observer=Common.getConnection()) {
            assertEquals(1120,number(observer,"SELECT disk_size FROM starexec.users WHERE id=?",user));
        }
        assertEquals(build,SolverBuildStaging.prepare(connection,pair,1,staging,100));
        assertThrows(SQLException.class,()->SolverBuildStaging.prepare(connection,pair,1,staging,101));
        assertEquals(1120,usage());
        assertEquals(100,number(connection,"SELECT limit_bytes FROM starexec.solver_build_storage_charges WHERE artifact_id=?",build.artifactId()));
    }

    @Test void insufficientStagingQuotaRollsBackIdentityBeforeCreatingFiles() throws Exception {
        assertThrows(SQLException.class,()->prepare(8981));
        try (var children=Files.list(staging)) { assertEquals(0,children.count()); }
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.solver_build_artifacts WHERE origin_pair_id=? AND execution_token IS NOT NULL",pair));
        assertEquals(1020,usage());
        prepare(8980); assertEquals(10000,usage());
    }

    @Test void budgetArithmeticCannotOverflowAtTheBigintBoundary() throws Exception {
        execute(connection,"UPDATE starexec.users SET disk_quota=?,disk_size=? WHERE id=?",Long.MAX_VALUE,Long.MAX_VALUE-10,user);
        assertThrows(SQLException.class,()->prepare(11));
        assertEquals(Long.MAX_VALUE-10,usage());
        prepare(10); assertEquals(Long.MAX_VALUE,usage());
    }

    @Test void foreignTransactionsCannotAllocateStagingOrOutput() throws Exception {
        connection.setAutoCommit(false);
        assertThrows(SQLException.class,()->prepare(100)); assertFalse(connection.getAutoCommit());
        connection.rollback(); connection.setAutoCommit(true);
        prepare(100); stopped(); connection.setAutoCommit(false);
        assertThrows(SQLException.class,()->publish(100)); assertFalse(connection.getAutoCommit());
        connection.rollback(); connection.setAutoCommit(true); assertEquals(1120,usage());
    }

    @Test void ownerContentionRefusesWithoutAllocatingOrCharging() throws Exception {
        try (Connection competing=Common.getConnection()) {
            competing.setAutoCommit(false);
            number(competing,"SELECT id FROM starexec.users WHERE id=? FOR UPDATE",user);
            assertEquals("55P03",assertThrows(SQLException.class,()->prepare(100)).getSQLState());
            assertEquals(1020,usage());
            try (var children=Files.list(staging)) { assertEquals(0,children.count()); }
            competing.rollback(); competing.setAutoCommit(true);
        }
        prepare(100); assertEquals(1120,usage());
    }

    @Test void separateAttemptsCannotOversubscribeTheSameOwner() throws Exception {
        prepare(5000);
        int nextJob=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'second',1,0,true) RETURNING id",user);
        int nextPair=(int)number(connection,"INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id",nextJob);
        int second=(int)number(connection,"INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status) VALUES (?,'second',now(),?,0,0) RETURNING id",user,files.resolve("second_src").toString());
        execute(connection,"INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)",nextPair,second);
        try {
            assertThrows(SQLException.class,()->SolverBuildStaging.prepare(connection,nextPair,1,staging,5000));
            assertEquals(6020,usage());
        } finally { execute(connection,"DELETE FROM starexec.jobs WHERE id=?",nextJob); }
    }

    @Test void outputReservationIsCommittedAndWrongReceiptRollsItBack() throws Exception {
        prepare(100); stopped();
        assertFalse(SolverBuildArtifacts.reserveOutput(connection,build,"podman:7:owned-quota-build","0".repeat(64),root(),100));
        assertEquals(1120,usage());
        assertEquals(0,number(connection,"SELECT count(*) FROM starexec.solver_build_storage_charges WHERE artifact_id=? AND storage_kind='OUTPUT'",build.artifactId()));
        var receipt=SolverBuildArtifacts.findStopReceipt(connection,pair,1).orElseThrow().receipt();
        assertTrue(SolverBuildArtifacts.reserveOutput(connection,build,"podman:7:owned-quota-build",receipt.sha256(),root(),100));
        try (Connection observer=Common.getConnection()) {
            assertEquals(1220,number(observer,"SELECT disk_size FROM starexec.users WHERE id=?",user));
        }
        assertFalse(Files.exists(root()));
        assertTrue(publish(100)); assertEquals(1127,usage());
        assertTrue(publish(100)); assertEquals(1127,usage());
    }

    @Test void concurrentReservationsCannotSpendTheSameRemainingBytes() throws Exception {
        build=SolverBuildStaging.prepare(connection,pair,1,staging); stopped();
        int second=(int)number(connection,"INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status) VALUES (?,'second',now(),?,0,0) RETURNING id",user,files.resolve("second_src").toString());
        int secondJob=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'second',1,0,true) RETURNING id",user);
        int secondPair=(int)number(connection,"INSERT INTO starexec.job_pairs(job_id,status_code,primary_jobpair_data) VALUES (?,7,1) RETURNING id",secondJob);
        execute(connection,"INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,disk_size) VALUES (?,1,7,?,0)",secondPair,second);
        var otherBuild=SolverBuildStaging.prepare(connection,secondPair,1,directory("second-staging")); stopped(otherBuild);
        Path otherOutput=outputs.resolve("build-"+otherBuild.artifactId()+"-"+otherBuild.token());
        execute(connection,"UPDATE starexec.users SET disk_quota=2520 WHERE id=?",user);
        var executor=Executors.newSingleThreadExecutor();
        try (Connection competing=Common.getConnection()) {
            connection.setAutoCommit(false);
            assertTrue(SolverBuildArtifacts.reserveStorageBytes(connection,build,"OUTPUT",root(),1000));
            var refused=executor.submit(()-> {
                try {
                    return Common.runTransactional(competing,tx->SolverBuildArtifacts.reserveStorageBytes(tx,otherBuild,"OUTPUT",otherOutput,1000))?"accepted":"quota";
                } catch (SQLException error) { return error.getSQLState(); }
            });
            assertEquals("55P03",refused.get(5,TimeUnit.SECONDS));
            connection.commit(); connection.setAutoCommit(true);
            boolean accepted=Common.runTransactional(competing,tx->SolverBuildArtifacts.reserveStorageBytes(tx,otherBuild,"OUTPUT",otherOutput,1000));
            assertFalse(accepted);
            assertEquals(2020,usage());
        } finally {
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));
            if (!connection.getAutoCommit()) { connection.rollback(); connection.setAutoCommit(true); }
            execute(connection,"DELETE FROM starexec.jobs WHERE id=?",secondJob);
        }
    }

    @Test void failedCopyKeepsTheBudgetUntilVerifiedCleanup() throws Exception {
        prepare(100); stopped();
        assertThrows(IOException.class,()->publish(1));
        assertTrue(Files.isDirectory(root())); assertEquals(1121,usage());
        assertThrows(IOException.class,()->publish(1)); assertEquals(1121,usage());
        assertThrows(IOException.class,()->publish(2)); assertEquals(1121,usage());
        retire(); assertTrue(purge()); assertEquals(1000,usage());
        assertTrue(purge()); assertEquals(1000,usage());
        assertEquals(2,number(connection,"SELECT count(*) FROM starexec.solver_build_storage_charges WHERE artifact_id=? AND released_at IS NOT NULL",build.artifactId()));
    }

    @Test void parentForceFailureRetainsBothChargesAndRetriesTheSameClaim() throws Exception {
        prepare(100); stopped(); assertTrue(publish(100)); retire();
        try (var directories=mockStatic(DescriptorDirectory.class,invocation -> {
            if (invocation.getMethod().getName().equals("open") && staging.equals(invocation.getArgument(0))) {
                DescriptorDirectory observed=spy((DescriptorDirectory)invocation.callRealMethod());
                doThrow(new IOException("INJECTED QUOTA FORCE FAILURE")).when(observed).force(); return observed;
            }
            return invocation.callRealMethod();
        })) {
            assertThrows(SQLException.class,this::purge); assertEquals(1127,usage());
        }
        UUID token=SolverBuildArtifacts.findCleanup(connection,build.artifactId()).orElseThrow().token();
        assertTrue(purge()); assertEquals(1000,usage());
        assertEquals(token,SolverBuildArtifacts.findCleanup(connection,build.artifactId()).orElseThrow().token());
    }

    @Test void deferredSettlementFailureRetainsChargeAfterPhysicalRemoval() throws Exception {
        prepare(100); stopped(); assertTrue(publish(100)); retire();
        injected="quota_fail_"+UUID.randomUUID().toString().replace("-","");
        execute(connection,"CREATE FUNCTION starexec."+injected+"() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'INJECTED QUOTA COMMIT FAILURE'; END; $$ LANGUAGE plpgsql");
        execute(connection,"CREATE CONSTRAINT TRIGGER "+injected+" AFTER UPDATE ON starexec.solver_build_storage_charges DEFERRABLE INITIALLY DEFERRED FOR EACH ROW"
                +" WHEN (NEW.artifact_id="+build.artifactId()+" AND NEW.released_at IS NOT NULL) EXECUTE FUNCTION starexec."+injected+"()");
        assertThrows(SQLException.class,this::purge); assertEquals(1127,usage());
        assertFalse(Files.exists(root())); assertFalse(Files.exists(build.staging()));
        dropTrigger(); assertTrue(purge()); assertEquals(1000,usage());
    }

    @Test void chargeIdentityAndVerifiedAmountsCannotBeRewritten() throws Exception {
        prepare(100); stopped(); assertTrue(publish(100));
        assertThrows(SQLException.class,()->execute(connection,"DELETE FROM starexec.solver_build_storage_charges WHERE artifact_id=?",build.artifactId()));
        assertThrows(SQLException.class,()->execute(connection,"UPDATE starexec.solver_build_storage_charges SET limit_bytes=101 WHERE artifact_id=?",build.artifactId()));
        assertThrows(SQLException.class,()->execute(connection,"UPDATE starexec.solver_build_storage_charges SET charged_bytes=0,verified_bytes=0 WHERE artifact_id=? AND storage_kind='OUTPUT'",build.artifactId()));
        assertThrows(SQLException.class,()->execute(connection,"SELECT starexec.release_solver_build_storage_bytes(?,?)",build.artifactId(),UUID.randomUUID()));
        assertEquals(1127,usage());
    }

    private void dropTrigger() throws SQLException {
        if (injected!=null) {
            execute(connection,"DROP TRIGGER IF EXISTS "+injected+" ON starexec.solver_build_storage_charges");
            execute(connection,"DROP FUNCTION IF EXISTS starexec."+injected+"()"); injected=null;
        }
    }

    @AfterEach void cleanup() throws Exception {
        if (connection!=null) {
            try {
                if (!connection.getAutoCommit()) connection.rollback(); connection.setAutoCommit(true);
                dropTrigger(); execute(connection,"DELETE FROM starexec.users WHERE id=?",user);
            } finally { connection.close(); }
        }
    }
}
