package org.starexec.test.junit.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.Common;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.JobStorageLeases;
import org.starexec.data.database.JobExecutionInputs;
import org.starexec.jobs.JobExecutionPreparation;
import org.starexec.test.util.DatabaseTestSupport;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL and file copies; filesystem capacity is explicitly synthetic. */
class JobExecutionPreparationSqlTest extends Common {
    @TempDir Path files;
    private Connection connection;
    private DedicatedStorageResource resource;
    private JobStorageLeases.Lease lease;
    private Path benchmark, solverRoot, script, functions, statuses;
    private int owner, pair, job, solver, config, bench;

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("JobExecutionPreparationSqlTest");
        Common.initialize();
    }
    private static long number(Connection con, String sql, Object... args) throws Exception {
        try (var query=con.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) query.setObject(i+1,args[i]);
            try (var row=query.executeQuery()) { assertTrue(row.next()); return row.getLong(1); }
        }
    }
    @BeforeEach void fixture() throws Exception {
        connection=Common.getConnection();
        owner=(int)number(connection,"INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,disk_quota,job_pair_quota,disk_size)"
                +" VALUES (?,'Preparation','Fixture','synthetic',now(),'synthetic',4194304,100,0) RETURNING id","preparation-"+UUID.randomUUID()+"@example.invalid");
        benchmark=Files.writeString(files.resolve("primary.p"),"cnf(input,axiom,p).\n");
        bench=(int)number(connection,"INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size)"
                +" VALUES (?,'primary',now(),?,?) RETURNING id",owner,benchmark.toString(),Files.size(benchmark));
        solverRoot=Files.createDirectory(files.resolve("solver"));
        Path bin=Files.createDirectory(solverRoot.resolve("bin"));
        Files.writeString(bin.resolve("starexec_run_default"),"#!/bin/bash\nprintf 'SZS status Theorem\\n'\n");
        solver=(int)number(connection,"INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status)"
                +" VALUES (?,'solver',now(),?,100,1) RETURNING id",owner,solverRoot.toString());
        config=(int)number(connection,"INSERT INTO starexec.configurations(solver_id,name,updated) VALUES (?,'default',now()) RETURNING id",solver);
        job=(int)number(connection,"INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'preparation',1,0,false) RETURNING id",owner);
        pair=(int)number(connection,"INSERT INTO starexec.job_pairs(job_id,bench_id,status_code,primary_jobpair_data) VALUES (?,?,1,1) RETURNING id",job,bench);
        number(connection,"INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,config_id,disk_size)"
                +" VALUES (?,1,1,?,?,0) RETURNING jobpair_id",pair,solver,config);
        number(connection,"INSERT INTO starexec.job_stage_params(job_id,stage_number,cputimeout,clocktimeout,maximum_memory,stdout_save_option,extra_output_save_option)"
                +" VALUES (?,1,10,20,1048576,2,2) RETURNING job_id",job);
        Path controls=Files.createDirectory(files.resolve("controls"));
        script=Files.writeString(controls.resolve("jobscript"),"#!/bin/bash\nprintf 'job\\n'\n");
        functions=Files.writeString(controls.resolve("functions.bash"),":\n");
        statuses=Files.writeString(controls.resolve("status_codes.bash"),"STATUS=0\n");
        Path slot=Files.createDirectory(files.resolve("slot"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        resource=mock(DedicatedStorageResource.class);
        when(resource.root()).thenReturn(slot); when(resource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-",""));
        when(resource.identity()).thenReturn("synthetic-preparation-resource");
        when(resource.capacityBytes()).thenReturn(1048576L); when(resource.inodeLimit()).thenReturn(128L);
        assertTrue(ExecutionStorageLeases.register(connection,resource));
        UUID token=UUID.randomUUID();
        assertTrue(JobStorageLeases.reserve(connection,pair,1,token,resource));
        lease=JobStorageLeases.find(connection,pair,1).orElseThrow();
    }
    @AfterEach void cleanup() throws Exception {
        try {
            if (connection!=null) {
                if (!connection.getAutoCommit()) { connection.rollback(); connection.setAutoCommit(true); }
                connection.close();
            }
        } finally {
            try (var paths=Files.walk(files)) {
                for (var path:paths.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList())
                    Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
            }
        }
    }
    private boolean copiesReady() throws Exception {
        return Files.isRegularFile(lease.staging().resolve("control/status"))
                && Files.getPosixFilePermissions(lease.staging().resolve("inputs")).equals(PosixFilePermissions.fromString("r-xr-xr-x"));
    }
    @Test void aReplacementAfterCopyCannotBecomeANewAcceptedBaseline() throws Exception {
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            if (copiesReady() && changed.compareAndSet(false,true)) {
                String text=Files.readString(benchmark);Files.delete(benchmark);Files.writeString(benchmark,text);
            }
            return null;
        }).when(resource).requireUnchanged();
        assertThrows(IOException.class,this::prepare);
        assertTrue(changed.get());
        assertFalse(Files.exists(lease.staging().resolve("inputs.json")));
        held();
    }
    @Test void metadataChangedAfterCopyCannotBecomeANewAcceptedBaseline() throws Exception {
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        var proxy=mock(Connection.class,org.mockito.AdditionalAnswers.delegatesTo(connection));
        doAnswer(call->{
            String sql=call.getArgument(0);
            if (sql.contains("FROM starexec.execution_storage_claims c") && copiesReady() && changed.compareAndSet(false,true)) {
                try (var other=Common.getConnection()) {
                    assertTrue(other.getAutoCommit());
                    number(other,"UPDATE starexec.configurations SET name='changed after copy' WHERE id=? RETURNING id",config);
                }
            }
            return connection.prepareStatement(sql);
        }).when(proxy).prepareStatement(org.mockito.ArgumentMatchers.anyString());
        assertThrows(IOException.class,()->JobExecutionPreparation.prepare(proxy,lease,resource,controls(),32768,100));
        assertTrue(changed.get());
        assertFalse(Files.exists(lease.staging().resolve("inputs.json")));
        held();
    }
    private JobExecutionPreparation.Controls controls() {
        return new JobExecutionPreparation.Controls(script,functions,statuses,null);
    }
    private JobStorageLeases.Lease currentLease() throws Exception {
        return JobStorageLeases.find(connection,pair,1).orElseThrow();
    }
    private JobExecutionPreparation prepare() throws Exception {
        return JobExecutionPreparation.prepare(connection,lease,resource,controls(),32768,100);
    }
    private JobExecutionPreparation restore(JobExecutionPreparation prepared) throws Exception {
        return JobExecutionPreparation.restore(connection,currentLease(),resource,controls(),32768,100,prepared.fingerprint());
    }
    private JobExecutionPreparation.Binding binding(JobExecutionPreparation prepared,Path target) {
        return prepared.bindings().stream().filter(b->b.target().equals(target)).findFirst().orElseThrow();
    }
    private void unused() throws Exception {
        assertNull(currentLease().useStartedAt());
        assertFalse(Files.exists(lease.staging(),LinkOption.NOFOLLOW_LINKS));
        assertEquals(resource.capacityBytes(),number(connection,"SELECT disk_size FROM starexec.users WHERE id=?",owner));
    }
    private void held() throws Exception {
        assertNotNull(currentLease().useStartedAt());
        assertNull(currentLease().releasedAt());
        assertEquals(resource.capacityBytes(),number(connection,"SELECT disk_size FROM starexec.users WHERE id=?",owner));
        assertFalse(JobStorageLeases.releaseUnstarted(connection,lease.token(),resource));
    }
    @Test void materializesAuthorizedBytesAtTheirOriginalLogicalTargets() throws Exception {
        var prepared=prepare();
        var binding=binding(prepared,benchmark);
        assertTrue(binding.source().startsWith(lease.staging()));
        assertNotEquals(benchmark,binding.source());
        assertEquals(Files.readString(benchmark),Files.readString(binding.source()));
        assertTrue(binding.readOnly());
        assertEquals(Files.readString(script),Files.readString(binding(prepared,script).source()));
        assertEquals(5,prepared.bindings().stream().filter(JobExecutionPreparation.Binding::readOnly).count());
        for (var writable:prepared.bindings().stream().filter(b->!b.readOnly()).toList()) {
            assertTrue(writable.source().startsWith(lease.staging().resolve("execution")));
            for (var input:prepared.bindings().stream().filter(JobExecutionPreparation.Binding::readOnly).toList()) {
                assertFalse(input.source().startsWith(writable.source()));
                assertFalse(writable.source().startsWith(input.source()));
            }
        }
        assertFalse(prepared.bindings().stream().anyMatch(b->b.source().equals(lease.staging())));
        assertEquals(prepared.fingerprint(),restore(prepared).fingerprint());
        assertEquals(prepared.bindings(),restore(prepared).bindings());
        assertEquals(Path.of("/starexec/execution/work"),prepared.workingDirectory());
        held();
    }
    @Test void preparedRecaptureDoesNotWeakenUnusedCaptureOrRebookTheAllowance() throws Exception {
        assertTrue(JobExecutionInputs.capturePrepared(connection,lease,resource).isEmpty());
        var prepared=prepare();
        var started=currentLease();
        assertTrue(JobExecutionInputs.capture(connection,started,resource).isEmpty());
        assertTrue(JobExecutionInputs.capturePrepared(connection,lease,resource).isEmpty());
        assertTrue(JobExecutionInputs.capturePrepared(connection,started,resource).isPresent());
        assertEquals(prepared.fingerprint(),restore(prepared).fingerprint());
        assertThrows(SQLException.class,this::prepare);
        held();
    }
    @Test void legitimateDependencyTextAndConfinedSolverLinksRemainUnchanged() throws Exception {
        Path axiom=Files.writeString(files.resolve("axiom with spaces.ax"),"fof(ax,axiom,p).\n");
        int dependency=(int)number(connection,"INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size)"
                +" VALUES (?,'axiom',now(),?,?) RETURNING id",owner,axiom.toString(),Files.size(axiom));
        number(connection,"INSERT INTO starexec.bench_dependency(primary_bench_id,secondary_bench_id,include_path)"
                +" VALUES (?,?,?) RETURNING primary_bench_id",bench,dependency,"../Axioms/theory.ax");
        Files.createSymbolicLink(solverRoot.resolve("bin/default-link"),Path.of("starexec_run_default"));
        Path dependencies=Files.writeString(files.resolve("dependencies"),axiom+",,,../Axioms/theory.ax\n");
        var controls=new JobExecutionPreparation.Controls(script,functions,statuses,dependencies);
        var prepared=JobExecutionPreparation.prepare(connection,lease,resource,controls,32768,100);
        assertEquals(Files.readString(dependencies),Files.readString(binding(prepared,dependencies).source()));
        assertEquals(Files.readString(axiom),Files.readString(binding(prepared,axiom).source()));
        Path copiedSolver=binding(prepared,solverRoot).source();
        assertTrue(Files.isSymbolicLink(copiedSolver.resolve("bin/default-link")));
        assertEquals(Path.of("starexec_run_default"),Files.readSymbolicLink(copiedSolver.resolve("bin/default-link")));
        assertEquals(prepared.fingerprint(),JobExecutionPreparation.restore(connection,currentLease(),resource,controls,32768,100,prepared.fingerprint()).fingerprint());
    }
    @Test void twoAuthorizedBenchmarkIdsCanShareOneLogicalPathWithoutDuplicateMounts() throws Exception {
        int alias=(int)number(connection,"INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size)"
                +" VALUES (?,'alias',now(),?,?) RETURNING id",owner,benchmark.toString(),Files.size(benchmark));
        number(connection,"INSERT INTO starexec.jobpair_inputs(jobpair_id,input_number,bench_id) VALUES (?,1,?) RETURNING jobpair_id",pair,alias);
        var prepared=prepare();
        assertEquals(1,prepared.bindings().stream().filter(b->b.target().equals(benchmark)).count());
        assertEquals(prepared.fingerprint(),restore(prepared).fingerprint());
    }
    @ParameterizedTest @ValueSource(ints={1,2})
    void processorCopiesPreserveBytesAndCurrentCommunityPermissions(int type) throws Exception {
        int space=(int)number(connection,"INSERT INTO starexec.spaces(name) VALUES ('preparation-processors') RETURNING id");
        number(connection,"INSERT INTO starexec.closure(ancestor,descendant) VALUES (?,?) RETURNING ancestor",space,space);
        number(connection,"INSERT INTO starexec.user_assoc(user_id,space_id) VALUES (?,?) RETURNING user_id",owner,space);
        Path root=Files.createDirectory(files.resolve("processor"));
        Path process=Files.writeString(root.resolve("process"),"#!/bin/bash\ncat\n");
        int processor=(int)number(connection,"INSERT INTO starexec.processors(name,path,community,processor_type,disk_size)"
                +" VALUES ('processor',?,?,?,?) RETURNING id",root.toString(),space,type,Files.size(process));
        String field=type==1?"pre_processor":"post_processor";
        number(connection,"UPDATE starexec.job_stage_params SET "+field+"=? WHERE job_id=? RETURNING job_id",processor,job);
        var prepared=prepare();
        assertEquals(Files.readString(process),Files.readString(binding(prepared,root).source().resolve("process")));
        assertEquals(prepared.fingerprint(),restore(prepared).fingerprint());
        number(connection,"DELETE FROM starexec.user_assoc WHERE user_id=? AND space_id=? RETURNING user_id",owner,space);
        assertThrows(SQLException.class,()->restore(prepared));
        held();
    }
    @Test void aNewDatabaseConnectionRestoresOnlyTheConservedPreparation() throws Exception {
        var prepared=prepare();
        try (var other=Common.getConnection()) {
            var recovered=JobStorageLeases.find(other,pair,1).orElseThrow();
            var restored=JobExecutionPreparation.restore(other,recovered,resource,controls(),32768,100,prepared.fingerprint());
            assertEquals(prepared.bindings(),restored.bindings());
            assertEquals(prepared.fingerprint(),restored.fingerprint());
        }
        held();
    }
    @ParameterizedTest
    @ValueSource(strings={"source-bytes","source-identity","control-bytes","control-identity","copy-bytes","copy-identity",
            "receipt","receipt-mode","receipt-hardlink","missing-receipt","unknown-file","work-file","directory-mode","source-link","control-ancestor-link"})
    void changedOrUnregisteredFilesystemStateCannotRestore(String condition) throws Exception {
        var prepared=prepare();
        Path copy=binding(prepared,benchmark).source();
        Path receipt=lease.staging().resolve("inputs.json");
        switch (condition) {
            case "source-bytes" -> Files.writeString(benchmark,"changed input\n");
            case "source-identity" -> { String text=Files.readString(benchmark); Files.delete(benchmark); Files.writeString(benchmark,text); }
            case "control-bytes" -> Files.writeString(script,"different script\n");
            case "control-identity" -> { String text=Files.readString(script); Files.delete(script); Files.writeString(script,text); }
            case "copy-bytes" -> { Files.setPosixFilePermissions(copy,PosixFilePermissions.fromString("rw-------")); Files.writeString(copy,"changed copy\n"); Files.setPosixFilePermissions(copy,PosixFilePermissions.fromString("r--r--r--")); }
            case "copy-identity" -> {
                String text=Files.readString(copy);
                Files.setPosixFilePermissions(copy.getParent(),PosixFilePermissions.fromString("rwx------"));
                Files.delete(copy); Files.writeString(copy,text);
                Files.setPosixFilePermissions(copy,PosixFilePermissions.fromString("r--r--r--"));
                Files.setPosixFilePermissions(copy.getParent(),PosixFilePermissions.fromString("r-xr-xr-x"));
            }
            case "receipt" -> { Files.setPosixFilePermissions(receipt,PosixFilePermissions.fromString("rw-------")); Files.writeString(receipt,"{}"); Files.setPosixFilePermissions(receipt,PosixFilePermissions.fromString("r--r--r--")); }
            case "receipt-mode" -> Files.setPosixFilePermissions(receipt,PosixFilePermissions.fromString("rw-------"));
            case "receipt-hardlink" -> Files.createLink(files.resolve("receipt-alias"),receipt);
            case "missing-receipt" -> Files.delete(receipt);
            case "unknown-file" -> Files.writeString(lease.staging().resolve("unknown"),"unregistered");
            case "work-file" -> Files.writeString(lease.staging().resolve("execution/work/unknown"),"unregistered");
            case "directory-mode" -> Files.setPosixFilePermissions(lease.staging().resolve("inputs"),PosixFilePermissions.fromString("rwx------"));
            case "source-link" -> { Files.delete(benchmark); Files.createSymbolicLink(benchmark,Files.writeString(files.resolve("outside.p"),"outside")); }
            case "control-ancestor-link" -> {
                Path parent=script.getParent(),moved=files.resolve("moved-controls");
                Files.move(parent,moved); Files.createSymbolicLink(parent,moved);
            }
            default -> throw new AssertionError(condition);
        }
        assertThrows(IOException.class,()->restore(prepared));
        held();
    }
    @ParameterizedTest
    @ValueSource(strings={"cancelled","paused","benchmark-deleted","solver-recycled","config-deleted","attempt","identity","funding"})
    void changedAuthorityCannotAuthorizePreparedInputs(String condition) throws Exception {
        var prepared=prepare();
        try {
            switch (condition) {
                case "cancelled" -> number(connection,"UPDATE starexec.job_pairs SET status_code=21 WHERE id=? RETURNING id",pair);
                case "paused" -> number(connection,"UPDATE starexec.jobs SET paused=true WHERE id=? RETURNING id",job);
                case "benchmark-deleted" -> number(connection,"UPDATE starexec.benchmarks SET deleted=true WHERE id=? RETURNING id",bench);
                case "solver-recycled" -> number(connection,"UPDATE starexec.solvers SET recycled=true WHERE id=? RETURNING id",solver);
                case "config-deleted" -> number(connection,"UPDATE starexec.configurations SET deleted=true WHERE id=? RETURNING id",config);
                case "attempt" -> number(connection,"UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=? RETURNING pair_id",pair);
                case "identity" -> when(resource.identity()).thenReturn("replaced-physical-incarnation");
                case "funding" -> number(connection,"UPDATE starexec.users SET disk_size=0 WHERE id=? RETURNING id",owner);
                default -> throw new AssertionError(condition);
            }
            assertThrows(SQLException.class,()->restore(prepared));
            assertNotNull(currentLease().useStartedAt());
            assertNull(currentLease().releasedAt());
        } finally {
            if (condition.equals("funding")) number(connection,"UPDATE starexec.users SET disk_size=? WHERE id=? RETURNING id",resource.capacityBytes(),owner);
        }
    }
    @ParameterizedTest @ValueSource(strings={"source-link","tree-escape","hardlink","controls-from-solver","special-file","dirty-resource","reserved-target"})
    void invalidSourcesAreRejectedBeforeStorageUse(String condition) throws Exception {
        java.nio.channels.ServerSocketChannel socket=null;
        var selected=controls();
        try {
            switch (condition) {
                case "source-link" -> { Files.delete(benchmark); Files.createSymbolicLink(benchmark,Files.writeString(files.resolve("outside.p"),"outside")); }
                case "tree-escape" -> Files.createSymbolicLink(solverRoot.resolve("escape"),Files.writeString(files.resolve("outside"),"outside"));
                case "hardlink" -> Files.createLink(files.resolve("benchmark-alias"),benchmark);
                case "controls-from-solver" -> selected=new JobExecutionPreparation.Controls(solverRoot.resolve("bin/starexec_run_default"),functions,statuses,null);
                case "special-file" -> { Files.delete(benchmark); socket=java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX); socket.bind(java.net.UnixDomainSocketAddress.of(benchmark)); }
                case "dirty-resource" -> Files.writeString(resource.root().resolve("unknown"),"keep");
                case "reserved-target" -> number(connection,"UPDATE starexec.benchmarks SET path='/proc/self/status' WHERE id=? RETURNING id",bench);
                default -> throw new AssertionError(condition);
            }
            var controls=selected;
            assertThrows(IOException.class,()->JobExecutionPreparation.prepare(connection,lease,resource,controls,32768,100));
            unused();
            if (condition.equals("dirty-resource")) assertEquals("keep",Files.readString(resource.root().resolve("unknown")));
        } finally { if (socket!=null) socket.close(); }
    }
    @ParameterizedTest @ValueSource(strings={"bytes","entries","zero-bytes","too-many-inodes","resource-bytes"})
    void aggregateInputLimitsAreEnforcedBeforeStorageUse(String condition) throws Exception {
        long bytes=32768;int entries=100;
        switch (condition) {
            case "bytes" -> bytes=5;
            case "entries" -> entries=18;
            case "zero-bytes" -> bytes=0;
            case "too-many-inodes" -> entries=129;
            case "resource-bytes" -> bytes=resource.capacityBytes()+1;
            default -> throw new AssertionError(condition);
        }
        long limit=bytes;int count=entries;
        assertThrows(IOException.class,()->JobExecutionPreparation.prepare(connection,lease,resource,controls(),limit,count));
        unused();
    }
    @Test void receiptBudgetFailureRetainsTheUsedReservationAndPartialState() throws Exception {
        assertThrows(IOException.class,()->JobExecutionPreparation.prepare(connection,lease,resource,controls(),500,100));
        assertTrue(Files.isDirectory(lease.staging()));
        assertFalse(Files.exists(lease.staging().resolve("inputs.json")));
        held();
        assertThrows(SQLException.class,this::prepare);
    }
    @Test void aLostUseCommitReplyRetainsTheConservedMarkerWithoutWriting() throws Exception {
        var proxy=mock(Connection.class,org.mockito.AdditionalAnswers.delegatesTo(connection));
        var lost=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            connection.commit();
            if (currentLease().useStartedAt()!=null && lost.compareAndSet(false,true)) throw new SQLException("Use commit reply lost");
            return null;
        }).when(proxy).commit();
        assertThrows(SQLException.class,()->JobExecutionPreparation.prepare(proxy,lease,resource,controls(),32768,100));
        assertTrue(lost.get());
        assertFalse(Files.exists(lease.staging()));
        held();
    }
    @Test void anInputChangedAfterFirstUseKeepsTheFailedPreparationOwned() throws Exception {
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            if (currentLease().useStartedAt()!=null && changed.compareAndSet(false,true)) Files.writeString(benchmark,"changed after marker\n");
            return null;
        }).when(resource).requireUnchanged();
        assertThrows(IOException.class,this::prepare);
        assertTrue(changed.get());
        held();
    }
    @Test void restorationRequiresTheConservedFingerprint() throws Exception {
        var prepared=prepare();
        assertThrows(IOException.class,()->JobExecutionPreparation.restore(connection,currentLease(),resource,controls(),32768,100,null));
        assertThrows(IOException.class,()->JobExecutionPreparation.restore(connection,currentLease(),resource,controls(),32768,100,"0".repeat(64)));
        assertEquals(prepared.fingerprint(),restore(prepared).fingerprint());
    }
    @Test void foreignTransactionsRemainOwnedByTheirCaller() throws Exception {
        var prepared=prepare();
        connection.setAutoCommit(false);
        try {
            assertThrows(SQLException.class,()->restore(prepared));
            assertFalse(connection.getAutoCommit());
        } finally { connection.rollback();connection.setAutoCommit(true); }
        assertEquals(prepared.fingerprint(),restore(prepared).fingerprint());
    }
    @Test void accountLockContentionRefusesWithoutReleasingTheClaim() throws Exception {
        var prepared=prepare();
        try (var other=Common.getConnection()) {
            other.setAutoCommit(false);
            number(other,"SELECT id FROM starexec.users WHERE id=? FOR UPDATE",owner);
            assertThrows(SQLException.class,()->restore(prepared));
            other.rollback();
            other.setAutoCommit(true);
        }
        assertEquals(prepared.fingerprint(),restore(prepared).fingerprint());
        held();
    }

}

