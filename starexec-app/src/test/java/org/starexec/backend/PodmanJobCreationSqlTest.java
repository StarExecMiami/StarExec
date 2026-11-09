package org.starexec.backend;

import com.github.dockerjava.api.DockerClient;
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
import org.starexec.data.database.JobExecutionProtocol;
import org.starexec.jobs.JobExecutionPreparation;
import org.starexec.test.util.DatabaseTestSupport;

import java.nio.channels.ServerSocketChannel;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL, files and Unix peer checks; physical capacity and engine facts are synthetic. */
class PodmanJobCreationSqlTest extends Common {
    @TempDir Path peerFiles;
    private Path files,benchmark,script,functions,statuses;
    private Connection connection;
    private DedicatedStorageResource resource;
    private JobStorageLeases.Lease lease;
    private DockerClient client;
    private ServerSocketChannel peer;
    private org.mockito.MockedStatic<DedicatedStorageResource> physical;
    private int owner,pair,job,bench,solver,config;
    private static final String IMAGE="sha256:"+"a".repeat(64);

    @BeforeAll static void database() {
        DatabaseTestSupport.assumeDatabaseAvailable("PodmanJobCreationSqlTest");Common.initialize();
    }
    private long number(String sql,Object...args) throws Exception {
        try (var statement=connection.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            try (var row=statement.executeQuery()) { assertTrue(row.next());return row.getLong(1); }
        }
    }
    @BeforeEach void fixture() throws Exception {
        files=Files.createTempDirectory(Path.of("target").toAbsolutePath(),"ordinary-provider-");
        connection=Common.getConnection();
        owner=(int)number("INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,disk_quota,job_pair_quota,disk_size)"
                +" VALUES (?,'Provider','Fixture','synthetic',now(),'synthetic',4194304,100,0) RETURNING id","preparation-"+UUID.randomUUID()+"@example.invalid");
        benchmark=Files.writeString(files.resolve("primary.p"),"cnf(input,axiom,p).\n");
        bench=(int)number("INSERT INTO starexec.benchmarks(user_id,name,uploaded,path,disk_size) VALUES (?,'primary',now(),?,?) RETURNING id",
                owner,benchmark.toString(),Files.size(benchmark));
        Path source=Files.createDirectory(files.resolve("solver"));Path bin=Files.createDirectory(source.resolve("bin"));
        Files.writeString(bin.resolve("starexec_run_default"),"#!/bin/bash\nprintf 'SZS status Theorem\\n'\n");
        solver=(int)number("INSERT INTO starexec.solvers(user_id,name,uploaded,path,disk_size,build_status) VALUES (?,'solver',now(),?,100,1) RETURNING id",owner,source.toString());
        config=(int)number("INSERT INTO starexec.configurations(solver_id,name,updated) VALUES (?,'default',now()) RETURNING id",solver);
        job=(int)number("INSERT INTO starexec.jobs(user_id,name,total_pairs,disk_size,buildjob) VALUES (?,'private provider',1,0,false) RETURNING id",owner);
        pair=(int)number("INSERT INTO starexec.job_pairs(job_id,bench_id,status_code,primary_jobpair_data) VALUES (?,?,1,1) RETURNING id",job,bench);
        number("INSERT INTO starexec.jobpair_stage_data(jobpair_id,stage_number,status_code,solver_id,config_id,disk_size) VALUES (?,1,1,?,?,0) RETURNING jobpair_id",pair,solver,config);
        number("INSERT INTO starexec.job_stage_params(job_id,stage_number,cputimeout,clocktimeout,maximum_memory,stdout_save_option,extra_output_save_option) VALUES (?,1,10,20,1048576,2,2) RETURNING job_id",job);
        Path controls=Files.createDirectory(files.resolve("controls"));
        script=Files.writeString(controls.resolve("jobscript"),"#!/bin/bash\nprintf 'job\\n'\n");
        functions=Files.writeString(controls.resolve("functions.bash"),":\n");
        statuses=Files.writeString(controls.resolve("status_codes.bash"),"STATUS=0\n");
        Path slot=Files.createDirectory(files.resolve("slot"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        resource=mock(DedicatedStorageResource.class);when(resource.root()).thenReturn(slot);
        when(resource.filesystemKey()).thenReturn(UUID.randomUUID().toString().replace("-",""));
        when(resource.identity()).thenReturn("synthetic-preparation-resource");when(resource.capacityBytes()).thenReturn(1048576L);when(resource.inodeLimit()).thenReturn(128L);
        assertTrue(ExecutionStorageLeases.register(connection,resource));
        assertTrue(JobStorageLeases.reserve(connection,pair,1,UUID.randomUUID(),resource));
        lease=JobStorageLeases.find(connection,pair,1).orElseThrow();
        client=mock(DockerClient.class,RETURNS_DEEP_STUBS);
        when(client.infoCmd().exec().getSecurityOptions()).thenReturn(java.util.List.of("name=rootless"));
        for (String name:List.of("runner",IMAGE)) {
            when(client.inspectImageCmd(name).exec().getId()).thenReturn(IMAGE);
            when(client.inspectImageCmd(name).exec().getConfig().getVolumes()).thenReturn(null);
        }
        peer=ServerSocketChannel.open(StandardProtocolFamily.UNIX);peer.bind(UnixDomainSocketAddress.of(peerFiles.resolve("peer.sock")));
        physical=mockStatic(DedicatedStorageResource.class);
        physical.when(()->DedicatedStorageResource.inspect(eq(lease.root()),eq(new DedicatedStorageResource.Limits(lease.limitBytes(),lease.inodeLimit())),anyList())).thenReturn(resource);
    }
    private JobExecutionPreparation.Controls controls() { return new JobExecutionPreparation.Controls(script,functions,statuses,null); }
    @AfterEach void cleanup() throws Exception {
        try {
            if (physical!=null) physical.close();
            if (peer!=null) peer.close();
            if (connection!=null) connection.close();
        } finally {
            if (files!=null && Files.exists(files)) {
                try (var paths=Files.walk(files)) {
                    for (Path path:paths.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList())
                        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
                }
                try (var paths=Files.walk(files)) {
                    for (Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }
    private PodmanJobCreation prepare() throws Exception {
        return PodmanJobCreation.prepare(client,connection,lease,controls(),32768,100,"runner",peerFiles.resolve("peer.sock").toString());
    }
    private com.github.dockerjava.api.model.HostConfig limits() {
        return new com.github.dockerjava.api.model.HostConfig().withMemory(134217728L).withMemorySwap(134217728L)
                .withPidsLimit(64L).withCpusetCpus("2").withCpuQuota(100000L).withCpuPeriod(100000L).withNetworkMode("none")
                .withBinds(new com.github.dockerjava.api.model.Bind(files.toString(),new com.github.dockerjava.api.model.Volume("/shared")));
    }
    private Map<Path,Path> sources(PodmanJobCreation adapter) throws Exception {
        Map<Path,Path> values=new java.util.LinkedHashMap<>();
        for (var binding:adapter.preparation(connection).bindings()) values.put(binding.source(),binding.source());
        return values;
    }
    private JobExecutionProtocol.Creation plan(PodmanJobCreation adapter,JobExecutionProtocol.Transport transport) throws Exception {
        return adapter.plan(connection,JobExecutionProtocol.maximumExecutionId(connection)+1,limits(),sources(adapter),
                Map.of("starexec.partition.index","0"),transport);
    }
    @ParameterizedTest @ValueSource(strings={"JAVA","CURL"})
    void privateCreationConservesTheFirstAcknowledgementAndRecovers(String transport) throws Exception {
        var adapter=prepare();var prepared=adapter.preparation(connection);
        var saved=plan(adapter,JobExecutionProtocol.Transport.valueOf(transport));
        var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(saved.payload());
        assertEquals(IMAGE,body.path("Image").textValue());assertTrue(body.path("HostConfig").path("ReadonlyRootfs").booleanValue());
        assertTrue(body.path("HostConfig").path("Binds").isMissingNode() || body.path("HostConfig").path("Binds").isNull()
                || body.path("HostConfig").path("Binds").isEmpty());
        assertEquals(prepared.bindings().size(),body.path("HostConfig").path("Mounts").size());
        assertEquals("0:0",body.path("User").textValue());assertEquals("SIGTERM",body.path("StopSignal").textValue());
        assertEquals("NONE",body.path("Healthcheck").path("Test").path(0).textValue());
        List<String> environment=new java.util.ArrayList<>();body.path("Env").forEach(v->environment.add(v.textValue()));
        prepared.environment().forEach((key,value)->assertTrue(environment.contains(key+"="+value)));
        assertTrue(adapter.claimCreation(connection));assertFalse(adapter.claimCreation(connection));
        assertEquals(saved,JobExecutionProtocol.findCreation(connection,lease.token()).orElseThrow());
        var started=JobStorageLeases.find(connection,lease.token()).orElseThrow();assertNotNull(started.useStartedAt());assertNull(started.releasedAt());
        assertEquals(lease.priorExecutionId(),started.priorExecutionId());assertEquals(saved.executionId(),number("SELECT sge_id FROM starexec.job_pairs WHERE id=?",pair));
        assertEquals(saved.preparationFingerprint(),adapter.preparation(connection).fingerprint());
        connection.close();connection=Common.getConnection();
        var restored=PodmanJobCreation.restore(client,connection,saved,peerFiles.resolve("peer.sock").toString());
        assertEquals(saved,restored.creation());assertFalse(restored.claimCreation(connection));
        assertThrows(IOException.class,()->plan(restored,JobExecutionProtocol.Transport.JAVA));
        assertEquals(saved.preparationFingerprint(),restored.preparation(connection).fingerprint());
        assertEquals(1,number("SELECT count(*) FROM starexec.job_execution_requests WHERE execution_token=?",lease.token()));
        verify(client,never()).createContainerCmd(anyString());
    }
    @ParameterizedTest @ValueSource(strings={"non-rootless","missing-image","volumes"})
    void incompatibleProvidersOrImagesFailBeforeStorageUse(String change) throws Exception {
        switch (change) {
            case "non-rootless" -> when(client.infoCmd().exec().getSecurityOptions()).thenReturn(List.of());
            case "missing-image" -> when(client.inspectImageCmd("runner").exec().getId()).thenReturn(null);
            case "volumes" -> when(client.inspectImageCmd("runner").exec().getConfig().getVolumes()).thenAnswer(call->Map.of("/unreserved",Map.of()));
            default -> throw new AssertionError();
        }
        assertThrows(IOException.class,this::prepare);
        assertNull(JobStorageLeases.find(connection,lease.token()).orElseThrow().useStartedAt());
        try (var entries=Files.list(resource.root())) { assertEquals(0,entries.count()); }
        verify(client,never()).createContainerCmd(anyString());
    }
    @ParameterizedTest @ValueSource(strings={"missing","relocated-write","source-overlap","mutable-target"})
    void incompleteOrAliasedMappingsCannotConsumeCreation(String change) throws Exception {
        if (change.equals("mutable-target")) {
            benchmark=Files.move(benchmark,peerFiles.resolve("primary.p"));
            number("UPDATE starexec.benchmarks SET path=? WHERE id=? RETURNING id",benchmark.toString(),bench);
        }
        var adapter=prepare();var values=new java.util.LinkedHashMap<>(sources(adapter));
        var bindings=adapter.preparation(connection).bindings();
        var input=bindings.stream().filter(JobExecutionPreparation.Binding::readOnly).findFirst().orElseThrow();
        Path execution=JobStorageLeases.find(connection,lease.token()).orElseThrow().staging().resolve("execution");
        switch (change) {
            case "missing" -> values.remove(input.source());
            case "relocated-write" -> values.put(execution.resolve("tmp"),Path.of("/provider/foreign-tmp"));
            case "source-overlap" -> values.put(input.source(),execution.resolve("aliased-input"));
            case "mutable-target" -> { }
            default -> throw new AssertionError();
        }
        assertThrows(IOException.class,()->adapter.plan(connection,1001,limits(),values,Map.of(),JobExecutionProtocol.Transport.JAVA));
        assertTrue(JobExecutionProtocol.findCreation(connection,lease.token()).isEmpty());
        verify(client,never()).createContainerCmd(anyString());
    }
    @ParameterizedTest @ValueSource(strings={"swap","host-network","missing-cpus","ownership-label"})
    void policyOrLabelChangesCannotReplaceOwnedCreation(String change) throws Exception {
        var adapter=prepare();var limits=limits();var labels=new java.util.HashMap<String,String>();
        switch (change) {
            case "swap" -> limits.withMemorySwap(268435456L);
            case "host-network" -> limits.withNetworkMode("host");
            case "missing-cpus" -> limits.withCpusetCpus("");
            case "ownership-label" -> labels.put("starexec.exec.id","other");
            default -> throw new AssertionError();
        }
        var sources=sources(adapter);
        assertThrows(IOException.class,()->adapter.plan(connection,1001,limits,sources,labels,JobExecutionProtocol.Transport.JAVA));
        assertTrue(JobExecutionProtocol.findCreation(connection,lease.token()).isEmpty());
    }
    @Test void aLostClaimReplyNeverBecomesAnotherProviderSend() throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);
        var uncertain=mock(Connection.class,org.mockito.AdditionalAnswers.delegatesTo(connection));
        var delivered=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            boolean consumed=number("SELECT count(*) FROM starexec.job_execution_requests WHERE execution_token=?",lease.token())>0;
            connection.commit();if(consumed) { delivered.set(true);throw new SQLException("Synthetic ordinary claim reply loss"); }
            return null;
        }).when(uncertain).commit();
        assertThrows(SQLException.class,()->adapter.claimCreation(uncertain));assertTrue(delivered.get());
        assertEquals(saved,JobExecutionProtocol.findCreation(connection,lease.token()).orElseThrow());
        assertFalse(adapter.claimCreation(connection));verify(client,never()).createContainerCmd(anyString());
    }
    @ParameterizedTest @ValueSource(strings={"history","input"})
    void restorationCannotAdoptChangedHistoryOrInputBytes(String change) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        if (change.equals("input")) Files.writeString(benchmark,"changed scientific bytes\n");
        else {
            var body=(com.fasterxml.jackson.databind.node.ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper().readTree(saved.payload());
            ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("Labels")).put("starexec.job.control.script","/foreign/control");
            String payload=body.toString();saved=new JobExecutionProtocol.Creation(saved.token(),saved.pairId(),saved.attemptNo(),saved.ownerId(),
                    saved.resourceKey(),saved.resourceIdentity(),saved.executionId(),saved.name(),saved.image(),saved.preparationFingerprint(),
                    saved.transport(),payload,JobExecutionProtocol.digest(payload));
        }
        var altered=saved;
        assertThrows(IOException.class,()->PodmanJobCreation.restore(client,connection,altered,peerFiles.resolve("peer.sock").toString()));
        verify(client,never()).createContainerCmd(anyString());
    }
    @Test void translatedSourcesPreservePrivateRelativeWritesAndOriginalInputTargets() throws Exception {
        var adapter=prepare();var prepared=adapter.preparation(connection);
        Path application=JobStorageLeases.find(connection,lease.token()).orElseThrow().staging().resolve("execution");
        Path provider=Path.of("/provider/execution");Map<Path,Path> mapped=new java.util.LinkedHashMap<>();int index=0;
        for (var binding:prepared.bindings()) mapped.put(binding.source(),binding.readOnly()
                ?Path.of("/provider/inputs").resolve(Integer.toString(index++))
                :provider.resolve(application.relativize(binding.source())));
        var saved=adapter.plan(connection,JobExecutionProtocol.maximumExecutionId(connection)+1,limits(),mapped,Map.of(),JobExecutionProtocol.Transport.CURL);
        var mounts=new com.fasterxml.jackson.databind.ObjectMapper().readTree(saved.payload()).path("HostConfig").path("Mounts");
        for (var binding:prepared.bindings()) {
            var matches=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            mounts.forEach(m->{if(binding.target().toString().equals(m.path("Target").textValue())) matches.add(m);});
            assertEquals(1,matches.size());assertEquals(mapped.get(binding.source()).toString(),matches.get(0).path("Source").textValue());
            assertEquals(binding.readOnly(),matches.get(0).path("ReadOnly").booleanValue());
        }
        assertTrue(adapter.claimCreation(connection));assertEquals(saved,JobExecutionProtocol.findCreation(connection,lease.token()).orElseThrow());
        verify(client,never()).createContainerCmd(anyString());
    }
    @Test void simultaneousPlannersCannotReplaceTheFirstImmutableRequest() throws Exception {
        var adapter=prepare();var sources=sources(adapter);int execution=JobExecutionProtocol.maximumExecutionId(connection)+1;
        var firstPaused=new java.util.concurrent.CountDownLatch(1);var secondPaused=new java.util.concurrent.CountDownLatch(1);
        var releaseFirst=new java.util.concurrent.CountDownLatch(1);var releaseSecond=new java.util.concurrent.CountDownLatch(1);
        var firstGate=new java.util.concurrent.atomic.AtomicBoolean();var secondGate=new java.util.concurrent.atomic.AtomicBoolean();
        var firstResult=new java.util.concurrent.atomic.AtomicReference<JobExecutionProtocol.Creation>();
        var firstError=new java.util.concurrent.atomic.AtomicReference<Throwable>();var secondError=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try (var other=Common.getConnection()) {
            try (var statement=other.createStatement();var row=statement.executeQuery("SELECT pg_backend_pid()")) {
                assertTrue(row.next());assertNotEquals(number("SELECT pg_backend_pid()"),row.getLong(1));
            }
            Thread first=new Thread(()->{
                try { firstResult.set(adapter.plan(connection,execution,limits(),sources,Map.of(),JobExecutionProtocol.Transport.JAVA)); }
                catch(Throwable error) { firstError.set(error); }
            },"first-ordinary-planner");
            Thread second=new Thread(()->{
                try { adapter.plan(other,execution+1,limits(),sources,Map.of(),JobExecutionProtocol.Transport.CURL); }
                catch(Throwable error) { secondError.set(error); }
            },"second-ordinary-planner");
            doAnswer(call->{
                if (Thread.currentThread()==first && connection.getAutoCommit() && firstGate.compareAndSet(false,true)) {
                    firstPaused.countDown();assertTrue(releaseFirst.await(10,java.util.concurrent.TimeUnit.SECONDS));
                }
                if (Thread.currentThread()==second && other.getAutoCommit() && secondGate.compareAndSet(false,true)) {
                    secondPaused.countDown();assertTrue(releaseSecond.await(10,java.util.concurrent.TimeUnit.SECONDS));
                }
                return null;
            }).when(resource).requireUnchanged();
            try {
                first.start();assertTrue(firstPaused.await(5,java.util.concurrent.TimeUnit.SECONDS));second.start();
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);boolean observed=false;
                while (System.nanoTime()<deadline) {
                    var info=java.lang.management.ManagementFactory.getThreadMXBean().getThreadInfo(second.getId());
                    if (secondPaused.getCount()==0 || secondError.get()!=null
                            || info!=null && info.getThreadState()==Thread.State.BLOCKED && info.getLockOwnerId()==first.getId()) {
                        observed=true;break;
                    }
                    Thread.sleep(5);
                }
                assertTrue(observed,"second planner must reach its attempt or the first planner's lock");
                releaseFirst.countDown();first.join(10000);assertFalse(first.isAlive());
                releaseSecond.countDown();second.join(10000);assertFalse(second.isAlive());
                assertNull(firstError.get());assertNotNull(firstResult.get());
                assertInstanceOf(IOException.class,secondError.get(),"a second planner must not replace the first request");
                assertEquals(firstResult.get(),adapter.creation());assertTrue(adapter.claimCreation(connection));
                assertEquals(firstResult.get(),JobExecutionProtocol.findCreation(connection,lease.token()).orElseThrow());
            } finally {
                releaseFirst.countDown();releaseSecond.countDown();first.join(10000);second.join(10000);
                assertFalse(first.isAlive());assertFalse(second.isAlive());
            }
        }
        verify(client,never()).createContainerCmd(anyString());
    }
}
