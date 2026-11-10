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
        // Capacity is synthetic; mount topology must still come from the kernel.
        physical=mockStatic(DedicatedStorageResource.class, invocation ->
                invocation.getMethod().getName().equals("inspect") ? null : invocation.callRealMethod());
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

    private final class CreateServer implements AutoCloseable {
        final java.util.concurrent.atomic.AtomicInteger posts=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<byte[]> bytes=new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean durable=new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger inspections=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger starts=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean startDurable=new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger collectors=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger releases=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean releaseDurable=new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        private final Thread thread;
        private volatile boolean closed;
        private volatile java.nio.channels.SocketChannel current;

        CreateServer(JobExecutionProtocol.Creation expected,String outcome) throws Exception {
            this(expected,outcome,false);
        }
        CreateServer(JobExecutionProtocol.Creation expected,String outcome,boolean lifecycle) throws Exception {
            this(expected,outcome,lifecycle,null);
        }
        CreateServer(JobExecutionProtocol.Creation expected,String outcome,boolean lifecycle,KernelPacket kernel) throws Exception {
            peer.configureBlocking(false);
            thread=new Thread(()->{
                while (!closed) {
                    try {
                        var accepted=peer.accept();
                        if (accepted==null) {Thread.sleep(2);continue;}
                        current=accepted;
                        try (accepted) {
                            accepted.configureBlocking(true);var input=java.nio.channels.Channels.newInputStream(accepted);
                            var header=new java.io.ByteArrayOutputStream();int value;
                            while ((value=input.read())!=-1) {
                                header.write(value);String text=header.toString(java.nio.charset.StandardCharsets.US_ASCII);
                                if (text.endsWith("\r\n\r\n")) break;
                                if (header.size()>65536) throw new IOException("Synthetic HTTP header exceeds its bound");
                            }
                            if (header.size()==0) continue;
                            String text=header.toString(java.nio.charset.StandardCharsets.US_ASCII);
                            boolean creating=text.startsWith("POST /v1.41/containers/create?name="+expected.name()+" HTTP/1.1\r\n");
                            boolean inspecting=lifecycle && text.startsWith("GET /v1.41/containers/"+"c".repeat(64)+"/json HTTP/1.1\r\n");
                            boolean starting=lifecycle && text.startsWith("POST /v1.41/containers/"+"c".repeat(64)+"/start HTTP/1.1\r\n");
                            boolean info=kernel!=null && text.startsWith("GET /v1.41/info HTTP/1.1\r\n");
                            boolean execCreate=kernel!=null && text.startsWith("POST /v1.41/containers/"+"c".repeat(64)+"/exec HTTP/1.1\r\n");
                            boolean execStart=kernel!=null && text.startsWith("POST /v1.41/exec/"+"e".repeat(64)+"/start HTTP/1.1\r\n");
                            boolean execInspect=kernel!=null && text.startsWith("GET /v1.41/exec/"+"e".repeat(64)+"/json HTTP/1.1\r\n");
                            boolean releasing=kernel!=null && text.startsWith("POST /v1.41/containers/"+"c".repeat(64)+"/kill?signal=USR2 HTTP/1.1\r\n");
                            assertTrue(creating || inspecting || starting || info || execCreate || execStart || execInspect || releasing,text.split("\r\n",2)[0]);
                            if(text.toLowerCase(java.util.Locale.ROOT).contains("\r\nexpect: 100-continue\r\n")) {
                                var output=java.nio.channels.Channels.newOutputStream(accepted);
                                output.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));output.flush();
                            }
                            byte[] requestBody;
                            if(text.toLowerCase(java.util.Locale.ROOT).contains("\r\ntransfer-encoding: chunked\r\n")) {
                                var body=new java.io.ByteArrayOutputStream();
                                while(true) {
                                    int length=Integer.parseUnsignedInt(line(input).split(";",2)[0].strip(),16);
                                    assertTrue(length>=0 && body.size()+(long)length<=16777216);
                                    if(length==0) {while(!line(input).isEmpty()) { }break;}
                                    byte[] chunk=input.readNBytes(length);assertEquals(length,chunk.length);body.write(chunk);
                                    assertEquals('\r',input.read());assertEquals('\n',input.read());
                                }
                                requestBody=body.toByteArray();
                            } else {
                                int length=inspecting || info || execInspect?0:-1;
                                for (String line:text.split("\r\n")) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                                    length=Integer.parseInt(line.substring(line.indexOf(':')+1).strip());
                                assertTrue(length>=0 && length<=16777216);requestBody=input.readNBytes(length);
                            }
                            if(creating) bytes.set(requestBody);else if(!execCreate && !execStart) assertEquals(0,requestBody.length);
                            if(info) {reply(accepted,200,"application/json","{\"SecurityOptions\":[\"name=rootless\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));continue;}
                            if(execCreate) {
                                var options=new com.fasterxml.jackson.databind.ObjectMapper().readTree(requestBody);
                                assertEquals(PodmanPrivateBuild.observationCommand(kernel.runtime()),
                                        java.util.Arrays.asList(new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(options.get("Cmd"),String[].class)));
                                assertEquals("0:0",options.path("User").textValue());assertTrue(options.path("AttachStdout").booleanValue());
                                assertFalse(options.path("AttachStdin").booleanValue());collectors.incrementAndGet();
                                reply(accepted,201,"application/json",("{\"Id\":\""+"e".repeat(64)+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));continue;
                            }
                            if(execStart) {
                                var options=new com.fasterxml.jackson.databind.ObjectMapper().readTree(requestBody);
                                assertFalse(options.path("Detach").booleanValue());assertFalse(options.path("Tty").booleanValue());
                                var fields=new java.util.LinkedHashMap<>(kernel.fields());
                                if(outcome.equals("kernel-invalid")) fields.put("memory",java.util.Base64.getEncoder().encodeToString("268435456\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                                byte[] payload=new com.google.gson.Gson().toJson(fields).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                if(outcome.equals("collector-oversize")) payload=new byte[1024*1024+1];
                                byte[] frame=java.nio.ByteBuffer.allocate(8+payload.length).put((byte)1).put(new byte[3]).putInt(payload.length).put(payload).array();
                                reply(accepted,200,"application/vnd.docker.raw-stream",frame);continue;
                            }
                            if(execInspect) {
                                if(outcome.equals("during-cancel")) try(var observer=Common.getConnection();var update=observer.prepareStatement("UPDATE starexec.jobs SET killed=true WHERE id=?")) {
                                    update.setInt(1,job);assertEquals(1,update.executeUpdate());
                                }
                                String owner=outcome.equals("collector-foreign")?"d".repeat(64):"c".repeat(64);
                                String json="{\"ID\":\""+"e".repeat(64)+"\",\"ContainerID\":\""+owner+"\",\"Running\":false,\"ExitCode\":"+(outcome.equals("collector-exit")?1:0)+"}";
                                reply(accepted,200,"application/json",json.getBytes(java.nio.charset.StandardCharsets.UTF_8));continue;
                            }
                            if(creating) posts.incrementAndGet();
                            if(inspecting) inspections.incrementAndGet();
                            if(starting) starts.incrementAndGet();
                            if(releasing) releases.incrementAndGet();
                            try (var observer=Common.getConnection()) {
                                if(creating) durable.set(expected.equals(JobExecutionProtocol.findCreation(observer,expected.token()).orElse(null)));
                                if(starting) {
                                    var saved=JobExecutionProtocol.findReceipt(observer,expected.token(),JobExecutionProtocol.Phase.CREATED).orElseThrow();
                                    startDurable.set(new com.fasterxml.jackson.databind.ObjectMapper().readTree(saved.payload()).path("facts").path("start_requested").booleanValue());
                                }
                                if(releasing) releaseDurable.set(JobExecutionProtocol.findReceipt(observer,expected.token(),JobExecutionProtocol.Phase.RELEASE).isPresent());
                                if(creating && outcome.equals("cancel")) try(var update=observer.prepareStatement("UPDATE starexec.jobs SET killed=true WHERE id=?")) {
                                    update.setInt(1,job);assertEquals(1,update.executeUpdate());
                                }
                            }
                            String responseOutcome=outcome;
                            if(lifecycle) {
                                String prefix=creating?"create-":inspecting?"inspect-":starting?"start-":"signal-";
                                responseOutcome=outcome.startsWith(prefix)?outcome.substring(prefix.length()):"success";
                            }
                            if (responseOutcome.equals("lost")) continue;
                            if (responseOutcome.equals("timeout")) {while(!closed) Thread.sleep(5);continue;}
                            String body="{\"Id\":\""+"c".repeat(64)+"\"}";
                            if(inspecting) {
                                var fields=createdInspectionTree(expected);
                                if(kernel!=null && starts.get()>0) {
                                    ((com.fasterxml.jackson.databind.node.ObjectNode)fields.get("State")).put("Status","running").put("Running",true)
                                            .put("Pid",321).put("StartedAt","2026-01-01T00:00:01Z");
                                    if(inspections.get()>=3 && outcome.equals("after-restart")) ((com.fasterxml.jackson.databind.node.ObjectNode)fields.get("State"))
                                            .put("Pid",322).put("StartedAt","2026-01-01T00:00:02Z");
                                    if(inspections.get()>=3 && outcome.equals("after-memory")) ((com.fasterxml.jackson.databind.node.ObjectNode)fields.get("HostConfig")).put("Memory",268435456L);
                                }
                                if(responseOutcome.equals("wrong")) fields.put("Image","sha256:"+"d".repeat(64));
                                body=fields.toString();
                            }
                            if(starting || releasing) body="";
                            if (responseOutcome.equals("duplicate")) body="{\"Id\":\""+"c".repeat(64)+"\",\"Id\":\""+"d".repeat(64)+"\"}";
                            if (responseOutcome.equals("oversize")) body="x".repeat(inspecting?16777217:20000);
                            int status=responseOutcome.equals("503")?503:responseOutcome.equals("429")?429:responseOutcome.equals("redirect")?302:
                                    responseOutcome.equals("already-running")?304:creating?201:inspecting?200:204;
                            byte[] reply=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            String headers="HTTP/1.1 "+status+" Fixture\r\nContent-Type: application/json\r\nConnection: close\r\n"
                                    +(responseOutcome.equals("redirect")?"Location: /another\r\n":"")+"Content-Length: "+reply.length+"\r\n\r\n";
                            var output=java.nio.channels.Channels.newOutputStream(accepted);
                            output.write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));output.flush();
                            if(responseOutcome.equals("body-timeout")) {
                                output.write('{');output.flush();while(!closed) Thread.sleep(5);
                            } else if(responseOutcome.equals("dribble")) {
                                for(byte part:reply) {output.write(part);output.flush();Thread.sleep(400);}
                            } else {output.write(reply);output.flush();}
                        } finally {current=null;}
                    } catch(Throwable error) {
                        boolean expectedAbort=posts.get()>0 && (outcome.endsWith("body-timeout") || outcome.endsWith("dribble") || outcome.equals("inspect-oversize")) && error instanceof IOException;
                        if(!closed && !expectedAbort) failure.compareAndSet(null,error);
                    }
                }
            },"synthetic-ordinary-create-server");thread.setDaemon(true);thread.start();
        }
        private void reply(java.nio.channels.SocketChannel socket,int status,String type,byte[] body) throws IOException {
            var output=java.nio.channels.Channels.newOutputStream(socket);
            String header="HTTP/1.1 "+status+" Fixture\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
            output.write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII));output.write(body);output.flush();
        }
        private String line(java.io.InputStream input) throws IOException {
            var bytes=new java.io.ByteArrayOutputStream();int value,previous=-1;
            while((value=input.read())!=-1) {
                bytes.write(value);
                if(bytes.size()>65536) throw new IOException("Synthetic HTTP line exceeds its bound");
                if(previous=='\r' && value=='\n') return new String(bytes.toByteArray(),0,bytes.size()-2,java.nio.charset.StandardCharsets.US_ASCII);
                previous=value;
            }
            throw new java.io.EOFException("Incomplete synthetic HTTP line");
        }
        @Override public void close() throws Exception {
            closed=true;var socket=current;if(socket!=null) socket.close();thread.interrupt();thread.join(5000);
            assertFalse(thread.isAlive());assertNull(failure.get());peer.configureBlocking(true);
        }
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> transportOutcomes() {
        return java.util.Arrays.stream(JobExecutionProtocol.Transport.values()).flatMap(transport->
                java.util.stream.Stream.of("success","503","429","lost","duplicate","oversize","redirect","timeout","body-timeout","dribble")
                        .map(outcome->org.junit.jupiter.params.provider.Arguments.of(transport,outcome)));
    }
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> transportClaims() {
        return java.util.Arrays.stream(JobExecutionProtocol.Transport.values()).flatMap(transport->
                java.util.stream.Stream.of("history","reply-loss").map(change->org.junit.jupiter.params.provider.Arguments.of(transport,change)));
    }
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> transportPreconditions() {
        return java.util.Arrays.stream(JobExecutionProtocol.Transport.values()).flatMap(transport->
                java.util.stream.Stream.of("transport","deadline","socket").map(change->org.junit.jupiter.params.provider.Arguments.of(transport,change)));
    }
    private java.util.Optional<String> send(JobExecutionProtocol.Transport transport,Connection con,PodmanJobCreation policy,
            String socket,int connect,int response) throws Exception {
        return transport==JobExecutionProtocol.Transport.CURL?PodmanJobTransport.createOnce(con,policy,client,socket,connect,response)
                :PodmanJobTransport.createJavaOnce(con,policy,client,socket,connect,response);
    }
    private void noTransportWorker() {
        assertFalse(ProcessHandle.current().descendants().anyMatch(h->h.info().command().orElse("").endsWith("/curl")));
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()
                && (t.getName().equals("ordinary-create-response") || t.getName().equals("ordinary-java-create"))));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("transportOutcomes")
    @org.junit.jupiter.api.Timeout(30)
    void creationSendsExactBytesOnlyAfterAcknowledgementAndNeverReplays(JobExecutionProtocol.Transport transport,String outcome) throws Exception {
        var adapter=prepare();var saved=adapter.plan(connection,JobExecutionProtocol.maximumExecutionId(connection)+1,limits(),sources(adapter),
                Map.of("starexec.test.literal","λ\n'quoted'\\value"),transport);
        var staging=transportStaging();
        try (var server=new CreateServer(saved,outcome)) {
            if (outcome.equals("success")) assertEquals(java.util.Optional.of("c".repeat(64)),
                    send(transport,connection,adapter,peerFiles.resolve("peer.sock").toString(),1,2));
            else assertThrows(IOException.class,()->send(transport,connection,adapter,
                    peerFiles.resolve("peer.sock").toString(),1,2));
            assertEquals(1,server.posts.get());assertTrue(server.durable.get());
            assertArrayEquals(saved.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8),server.bytes.get());
            assertEquals(saved,JobExecutionProtocol.findCreation(connection,lease.token()).orElseThrow());
            assertTrue(send(transport,connection,adapter,peerFiles.resolve("peer.sock").toString(),1,2).isEmpty());
            assertEquals(1,server.posts.get());
            assertTrue(JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.RELEASE).isEmpty());
            noTransportWorker();
        }
        assertEquals(staging,transportStaging());
    }

    private java.util.Set<Path> transportStaging() throws Exception {
        try(var children=Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return children.filter(path->path.getFileName().toString().startsWith("starexec-ordinary-create-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private record KernelPacket(org.starexec.backend.storage.BuildExecutionRuntime runtime,Map<String,String> fields,
            org.starexec.backend.storage.BuildExecutionRuntime.Identity identity,Map<String,String> forbidden) { }
    private KernelPacket kernelPacket(PodmanJobCreation adapter,JobExecutionProtocol.Creation request) throws Exception {
        var identity=new org.starexec.backend.storage.BuildExecutionRuntime.Identity(0,0,1000,1000);
        var limits=new org.starexec.backend.storage.BuildExecutionRuntime.Limits(134217728,64,"2",100000,100000);
        var forbidden=new java.util.LinkedHashMap<String,String>();
        for(String name:List.of("pid","ipc","net","mnt","user","cgroup")) forbidden.put(name,name+":[10]");
        var runtime=org.starexec.backend.storage.BuildExecutionRuntime.captureJob(connection,adapter.preparation(connection),identity,limits,forbidden,60);
        var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.payload());var command=new java.util.ArrayList<String>();
        body.path("Entrypoint").forEach(v->command.add(v.textValue()));body.path("Cmd").forEach(v->command.add(v.textValue()));
        var fields=org.starexec.backend.storage.RuntimeObservationFixture.fields(runtime,identity,limits,forbidden,command,true);
        return new KernelPacket(runtime,fields,identity,Map.copyOf(forbidden));
    }
    private DockerClient wireClient() throws Exception {
        var address=new java.net.URI("unix",null,peerFiles.resolve("peer.sock").toString(),null,null);
        var config=mock(com.github.dockerjava.core.DockerClientConfig.class);
        when(config.getDockerHost()).thenReturn(address);when(config.getApiVersion()).thenReturn(com.github.dockerjava.core.RemoteApiVersion.VERSION_1_41);
        when(config.getObjectMapper()).thenReturn(com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper());
        var http=new com.github.dockerjava.httpclient5.ApacheDockerHttpClient.Builder().dockerHost(address).maxConnections(4)
                .connectionTimeout(java.time.Duration.ofSeconds(1)).responseTimeout(java.time.Duration.ofSeconds(2)).build();
        return com.github.dockerjava.core.DockerClientImpl.getInstance(config,new PodmanCreateOnceHttpClient(http));
    }

    @ParameterizedTest @ValueSource(strings={"JAVA","CURL"})
    @org.junit.jupiter.api.Timeout(40)
    void ordinaryPayloadRequiresKernelProofAndFirstDurableRelease(String transport) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.valueOf(transport));var kernel=kernelPacket(adapter,saved);
        var staging=transportStaging();
        try(var server=new CreateServer(saved,"success",true,kernel);var observer=wireClient()) {
            assertEquals(java.util.Optional.of("c".repeat(64)),PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1));
            assertTrue(PodmanJobTransport.releaseHeldOnce(connection,adapter,observer,peerFiles.resolve("peer.sock").toString(),"c".repeat(64),
                    kernel.identity(),kernel.forbidden(),1,1));
            assertEquals(1,server.collectors.get());assertEquals(1,server.releases.get());assertTrue(server.releaseDurable.get());
            var receipt=JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.RELEASE).orElseThrow();
            var facts=new com.fasterxml.jackson.databind.ObjectMapper().readTree(receipt.payload()).path("facts");
            assertTrue(facts.path("runtime_payload").textValue().contains("1234567"));
            assertFalse(PodmanJobTransport.releaseHeldOnce(connection,adapter,observer,peerFiles.resolve("peer.sock").toString(),"c".repeat(64),
                    kernel.identity(),kernel.forbidden(),1,1));
            assertEquals(1,server.collectors.get());assertEquals(1,server.releases.get());assertNull(JobStorageLeases.find(connection,saved.token()).orElseThrow().releasedAt());
            noTransportWorker();
        }
        assertEquals(staging,transportStaging());
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> releaseRefusals() {
        return java.util.Arrays.stream(JobExecutionProtocol.Transport.values()).flatMap(transport->
                java.util.stream.Stream.of("kernel-invalid","collector-exit","collector-foreign","collector-oversize",
                        "after-restart","after-memory","before-cancel","during-cancel","input-changed")
                        .map(outcome->org.junit.jupiter.params.provider.Arguments.of(transport,outcome)));
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> releaseAdmissionStates() {
        return java.util.Arrays.stream(JobExecutionProtocol.Transport.values()).flatMap(transport->
                java.util.stream.Stream.of("unconsumed","requested","observed","stopped")
                        .map(state->org.junit.jupiter.params.provider.Arguments.of(transport,state)));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("releaseAdmissionStates")
    void ordinaryPayloadNeedsAnExplicitUnstoppedHold(JobExecutionProtocol.Transport transport,String state) throws Exception {
        var adapter=prepare();var saved=plan(adapter,transport);var kernel=kernelPacket(adapter,saved);var staging=transportStaging();
        try(var server=new CreateServer(saved,"success",true,kernel);var observer=wireClient()) {
            if(state.equals("stopped")) {
                assertTrue(PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1).isPresent());
                number("UPDATE starexec.jobs SET killed=true WHERE id=? RETURNING id",job);
                var stopped=PodmanJobStopObservation.observe(saved,"c".repeat(64),stoppedInspection(saved,"exited"),UUID.randomUUID(),true);
                assertTrue(JobExecutionProtocol.recordReceipt(connection,JobStorageLeases.find(connection,lease.token()).orElseThrow(),resource,stopped));
            } else if(!state.equals("unconsumed")) {
                assertTrue(adapter.claimCreation(connection));
                if(state.equals("observed")) {
                    var observed=PodmanJobObservation.created(saved,"c".repeat(64),createdInspection(saved),UUID.randomUUID());
                    assertTrue(JobExecutionProtocol.recordReceipt(connection,JobStorageLeases.find(connection,lease.token()).orElseThrow(),resource,observed));
                }
            }
            int inspections=server.inspections.get();
            assertFalse(PodmanJobTransport.releaseHeldOnce(connection,adapter,observer,peerFiles.resolve("peer.sock").toString(),"c".repeat(64),
                    kernel.identity(),kernel.forbidden(),1,1));
            assertEquals(inspections,server.inspections.get());assertEquals(0,server.collectors.get());assertEquals(0,server.releases.get());
            assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.RELEASE).isEmpty());noTransportWorker();
        }
        assertEquals(staging,transportStaging());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("releaseRefusals")
    @org.junit.jupiter.api.Timeout(40)
    void ordinaryPayloadRefusesUntrustedOrRevokedProof(JobExecutionProtocol.Transport transport,String outcome) throws Exception {
        var adapter=prepare();var saved=plan(adapter,transport);var kernel=kernelPacket(adapter,saved);var staging=transportStaging();
        try(var server=new CreateServer(saved,outcome,true,kernel);var observer=wireClient()) {
            assertEquals(java.util.Optional.of("c".repeat(64)),PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1));
            if(outcome.equals("before-cancel")) number("UPDATE starexec.jobs SET killed=true WHERE id=? RETURNING id",job);
            if(outcome.equals("input-changed")) Files.writeString(benchmark,"cnf(changed,axiom,q).\n");
            var error=assertThrows(Exception.class,()->PodmanJobTransport.releaseHeldOnce(connection,adapter,observer,
                    peerFiles.resolve("peer.sock").toString(),"c".repeat(64),kernel.identity(),kernel.forbidden(),1,1));
            assertTrue(error instanceof IOException || error instanceof SQLException,error::toString);
            assertEquals(outcome.equals("before-cancel") || outcome.equals("input-changed")?0:1,server.collectors.get());
            assertEquals(0,server.releases.get());
            assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.RELEASE).isEmpty());
            assertNull(JobStorageLeases.find(connection,saved.token()).orElseThrow().releasedAt());noTransportWorker();
        }
        assertEquals(staging,transportStaging());
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> releaseReplies() {
        return java.util.Arrays.stream(JobExecutionProtocol.Transport.values()).flatMap(transport->
                java.util.stream.Stream.of("signal-lost","signal-503","signal-429","signal-redirect","signal-already-running","signal-timeout")
                        .map(outcome->org.junit.jupiter.params.provider.Arguments.of(transport,outcome)));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("releaseReplies")
    @org.junit.jupiter.api.Timeout(40)
    void uncertainPayloadSignalNeverReplays(JobExecutionProtocol.Transport transport,String outcome) throws Exception {
        var adapter=prepare();var saved=plan(adapter,transport);var kernel=kernelPacket(adapter,saved);var staging=transportStaging();
        try(var server=new CreateServer(saved,outcome,true,kernel);var observer=wireClient()) {
            assertTrue(PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1).isPresent());
            assertThrows(IOException.class,()->PodmanJobTransport.releaseHeldOnce(connection,adapter,observer,
                    peerFiles.resolve("peer.sock").toString(),"c".repeat(64),kernel.identity(),kernel.forbidden(),1,1));
            var first=JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.RELEASE).orElseThrow();
            assertEquals(1,server.releases.get());assertTrue(server.releaseDurable.get());
            assertFalse(PodmanJobTransport.releaseHeldOnce(connection,adapter,observer,peerFiles.resolve("peer.sock").toString(),"c".repeat(64),
                    kernel.identity(),kernel.forbidden(),1,1));
            assertEquals(first,JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.RELEASE).orElseThrow());
            assertEquals(1,server.releases.get());assertEquals(1,server.collectors.get());noTransportWorker();
        }
        assertEquals(staging,transportStaging());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(JobExecutionProtocol.Transport.class)
    void lostPayloadCommitReplyCannotSendOrReplay(JobExecutionProtocol.Transport transport) throws Exception {
        var adapter=prepare();var saved=plan(adapter,transport);var kernel=kernelPacket(adapter,saved);
        var uncertain=mock(Connection.class,org.mockito.AdditionalAnswers.delegatesTo(connection));
        var committed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            boolean released=JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.RELEASE).isPresent();
            connection.commit();if(released) {committed.set(true);throw new SQLException("Synthetic payload-release reply loss");}return null;
        }).when(uncertain).commit();
        var staging=transportStaging();
        try(var server=new CreateServer(saved,"success",true,kernel);var observer=wireClient()) {
            assertTrue(PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1).isPresent());
            assertThrows(SQLException.class,()->PodmanJobTransport.releaseHeldOnce(uncertain,adapter,observer,
                    peerFiles.resolve("peer.sock").toString(),"c".repeat(64),kernel.identity(),kernel.forbidden(),1,1));
            assertTrue(committed.get());assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.RELEASE).isPresent());
            assertFalse(PodmanJobTransport.releaseHeldOnce(connection,adapter,observer,peerFiles.resolve("peer.sock").toString(),"c".repeat(64),
                    kernel.identity(),kernel.forbidden(),1,1));
            assertEquals(0,server.releases.get());assertEquals(1,server.collectors.get());noTransportWorker();
        }
        assertEquals(staging,transportStaging());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(JobExecutionProtocol.Transport.class)
    void coldHeldRecoveryNeedsFreshKernelProofForItsFirstRelease(JobExecutionProtocol.Transport transport) throws Exception {
        var adapter=prepare();var saved=plan(adapter,transport);var kernel=kernelPacket(adapter,saved);var staging=transportStaging();
        try(var server=new CreateServer(saved,"success",true,kernel);var observer=wireClient()) {
            assertTrue(PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1).isPresent());
            var recovered=PodmanJobCreation.restore(client,connection,saved,peerFiles.resolve("peer.sock").toString());
            assertTrue(PodmanJobTransport.releaseHeldOnce(connection,recovered,observer,peerFiles.resolve("peer.sock").toString(),"c".repeat(64),
                    kernel.identity(),kernel.forbidden(),1,1));
            assertEquals(1,server.posts.get());assertEquals(1,server.starts.get());assertEquals(1,server.collectors.get());assertEquals(1,server.releases.get());
            assertFalse(PodmanJobTransport.releaseHeldOnce(connection,recovered,observer,peerFiles.resolve("peer.sock").toString(),"c".repeat(64),
                    kernel.identity(),kernel.forbidden(),1,1));noTransportWorker();
        }
        assertEquals(staging,transportStaging());
    }

    @ParameterizedTest @ValueSource(strings={"JAVA","CURL"})
    @org.junit.jupiter.api.Timeout(40)
    void interruptedPayloadSignalKeepsTheDecisionWithoutReplay(String transport) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.valueOf(transport));var kernel=kernelPacket(adapter,saved);var staging=transportStaging();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        try(var server=new CreateServer(saved,"signal-timeout",true,kernel);var observer=wireClient()) {
            assertTrue(PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1).isPresent());
            Thread operation=new Thread(()->{
                try {PodmanJobTransport.releaseHeldOnce(connection,adapter,observer,peerFiles.resolve("peer.sock").toString(),"c".repeat(64),
                        kernel.identity(),kernel.forbidden(),1,10);}
                catch(Throwable error) {failure.set(error);interrupted.set(Thread.currentThread().isInterrupted());}
            },"synthetic-payload-operation");
            operation.start();
            try {
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while(!server.releaseDurable.get() && operation.isAlive() && System.nanoTime()<deadline) Thread.sleep(5);
                assertTrue(server.releaseDurable.get());operation.interrupt();operation.join(7000);
                assertFalse(operation.isAlive());assertInstanceOf(IOException.class,failure.get());assertTrue(interrupted.get());
                assertFalse(PodmanJobTransport.releaseHeldOnce(connection,adapter,observer,peerFiles.resolve("peer.sock").toString(),"c".repeat(64),
                        kernel.identity(),kernel.forbidden(),1,1));
                assertEquals(1,server.releases.get());assertEquals(1,server.collectors.get());noTransportWorker();
            } finally {operation.interrupt();operation.join(7000);assertFalse(operation.isAlive());}
        }
        assertEquals(staging,transportStaging());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(JobExecutionProtocol.Transport.class)
    @org.junit.jupiter.api.Timeout(40)
    void stoppedOrdinaryOutputRecoversNonemptyConservedFiles(JobExecutionProtocol.Transport transport) throws Exception {
        var adapter=prepare();var saved=plan(adapter,transport);var kernel=kernelPacket(adapter,saved);var staging=transportStaging();
        try(var server=new CreateServer(saved,"success",true,kernel);var observer=wireClient()) {
            assertTrue(PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1).isPresent());
            assertTrue(PodmanJobTransport.releaseHeldOnce(connection,adapter,observer,peerFiles.resolve("peer.sock").toString(),"c".repeat(64),
                    kernel.identity(),kernel.forbidden(),1,1));
            Path output=lease.staging().resolve("execution/monitor");
            Files.writeString(output.resolve("status.json"),"{\"fixture\":true}\n");
            Files.writeString(lease.staging().resolve("execution/work/result.txt"),"SZS status Theorem\n");
            Files.delete(benchmark);Files.writeString(functions,"changed after payload\n");
            var inspection=stoppedInspection(saved,"exited");
            var stopped=PodmanJobStopObservation.observe(saved,"c".repeat(64),inspection,UUID.randomUUID(),false);
            assertTrue(JobExecutionProtocol.recordReceipt(connection,JobStorageLeases.find(connection,lease.token()).orElseThrow(),resource,stopped));
            assertThrows(SQLException.class,()->adapter.preparation(connection));
            assertEquals(output,PodmanJobOutput.stoppedOutputDirectory(connection,saved,inspection,"c".repeat(64)));
            assertEquals("{\"fixture\":true}\n",Files.readString(output.resolve("status.json")));
            assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.OUTPUT).isEmpty());
            assertNull(JobStorageLeases.find(connection,saved.token()).orElseThrow().releasedAt());noTransportWorker();
        }
        assertEquals(staging,transportStaging());
    }

    @ParameterizedTest @ValueSource(strings={"missing-release","missing-stop","cancelled-stop","provider-id","provider-config","provider-state",
            "changed-stop","receipt","control","input","staging","monitor-symlink","monitor-replaced","cleanup-started","input-hardlink","extra-directory"})
    void stoppedOrdinaryOutputRefusesUnverifiedStateOrLayout(String change) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);var kernel=kernelPacket(adapter,saved);
        assertTrue(adapter.claimCreation(connection));assertTrue(adapter.claimStart(connection,"c".repeat(64),createdInspection(saved)));
        var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        if(!change.equals("missing-release")) {
            var runtime=mapper.createObjectNode().put("version",1);
            runtime.set("observation",mapper.valueToTree(kernel.runtime().verify(new com.google.gson.Gson().toJson(kernel.fields()))));
            assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,
                    receipt(saved,JobExecutionProtocol.Phase.RELEASE,mapper.createObjectNode().put("runtime_payload",runtime.toString()))));
        }
        var inspection=stoppedInspection(saved,"exited");
        JobExecutionProtocol.Receipt stopped=null;
        if(!change.equals("missing-release") && !change.equals("missing-stop")) {
            if(change.equals("cancelled-stop")) number("UPDATE starexec.jobs SET killed=true WHERE id=? RETURNING id",job);
            stopped=PodmanJobStopObservation.observe(saved,"c".repeat(64),inspection,UUID.randomUUID(),change.equals("cancelled-stop"));
            assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,stopped));
        }
        if(change.equals("cleanup-started")) {
            String manifest="{\"synthetic\":true}";
            var facts=mapper.createObjectNode().put("stop_sha256",stopped.sha256()).put("manifest_payload",manifest)
                    .put("manifest_sha256",JobExecutionProtocol.digest(manifest));
            assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt(saved,JobExecutionProtocol.Phase.OUTPUT,facts)));
            assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt(saved,JobExecutionProtocol.Phase.CLEANUP_REQUEST,
                    mapper.createObjectNode().put("staging_identity","synthetic-staging").put("stop_sha256",stopped.sha256()))));
        }
        if(change.startsWith("provider-") || change.equals("changed-stop")) {
            var providerMapper=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper();
            var fields=(com.fasterxml.jackson.databind.node.ObjectNode)providerMapper.valueToTree(inspection);
            if(change.equals("provider-id")) fields.put("Id","d".repeat(64));
            if(change.equals("provider-config")) ((com.fasterxml.jackson.databind.node.ObjectNode)fields.get("HostConfig")).put("Memory",268435456L);
            if(change.equals("provider-state")) ((com.fasterxml.jackson.databind.node.ObjectNode)fields.get("State")).put("Status","running").put("Running",true).put("Pid",321);
            if(change.equals("changed-stop")) ((com.fasterxml.jackson.databind.node.ObjectNode)fields.get("State")).put("ExitCode",1);
            inspection=providerMapper.treeToValue(fields,com.github.dockerjava.api.command.InspectContainerResponse.class);
        }
        if(change.equals("staging")) {
            Files.move(lease.staging(),files.resolve("original-staging"));Files.createDirectory(lease.staging());
        }
        if(change.equals("monitor-symlink") || change.equals("monitor-replaced")) {
            Path monitor=lease.staging().resolve("execution/monitor");Files.move(monitor,files.resolve("original-monitor"));
            if(change.equals("monitor-symlink")) Files.createSymbolicLink(monitor,Files.createDirectory(files.resolve("outside-output")));
            else {Files.createDirectory(monitor);Files.setPosixFilePermissions(monitor,PosixFilePermissions.fromString("rwx------"));
                assertFalse(Files.isSameFile(monitor,files.resolve("original-monitor")));}
        }
        if(change.equals("extra-directory")) Files.createDirectory(lease.staging().resolve("execution/unregistered"));
        if(change.equals("receipt") || change.equals("input") || change.equals("control") || change.equals("input-hardlink")) {
            Path selected;
            if(change.equals("receipt")) selected=lease.staging().resolve("inputs.json");
            else try(var paths=Files.walk(lease.staging().resolve(change.equals("control")?"control":"inputs"))) {
                selected=paths.filter(f->Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)).findFirst().orElseThrow();
            }
            if(change.equals("input-hardlink")) Files.createLink(files.resolve("input-alias"),selected);
            else {
                var permissions=Files.getPosixFilePermissions(selected);String before=Files.readString(selected);
                Files.setPosixFilePermissions(selected,PosixFilePermissions.fromString("rw-------"));Files.writeString(selected,before+" ");
                Files.setPosixFilePermissions(selected,permissions);
            }
        }
        var observed=inspection;
        var error=assertThrows(Exception.class,()->PodmanJobOutput.stoppedOutputDirectory(connection,saved,observed,"c".repeat(64)));
        assertTrue(error instanceof IOException || error instanceof SQLException,error::toString);
        assertNull(JobStorageLeases.find(connection,saved.token()).orElseThrow().releasedAt());
        verify(client,never()).removeContainerCmd(anyString());
    }

    private record StoppedOutputFixture(JobExecutionProtocol.Creation request,com.github.dockerjava.api.command.InspectContainerResponse inspection) { }
    private StoppedOutputFixture stoppedOutputFixture() throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);var kernel=kernelPacket(adapter,saved);
        assertTrue(adapter.claimCreation(connection));assertTrue(adapter.claimStart(connection,"c".repeat(64),createdInspection(saved)));
        var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var runtime=mapper.createObjectNode().put("version",1);
        runtime.set("observation",mapper.valueToTree(kernel.runtime().verify(new com.google.gson.Gson().toJson(kernel.fields()))));
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,
                receipt(saved,JobExecutionProtocol.Phase.RELEASE,mapper.createObjectNode().put("runtime_payload",runtime.toString()))));
        var inspection=stoppedInspection(saved,"exited");
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,
                PodmanJobStopObservation.observe(saved,"c".repeat(64),inspection,UUID.randomUUID(),false)));
        return new StoppedOutputFixture(saved,inspection);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void stoppedOutputSnapshotConservesExactBytesWithoutGrantingRetention(boolean empty) throws Exception {
        var fixture=stoppedOutputFixture();
        if(!empty) {
            Files.writeString(lease.staging().resolve("execution/monitor/stdout.txt"),"abc");
            Files.writeString(lease.staging().resolve("execution/savedoutput/answer.p"),"de");
        }
        long usage=number("SELECT disk_size FROM starexec.users WHERE id=?",owner);
        var result=PodmanJobOutput.captureStoppedOutput(connection,fixture.request(),fixture.inspection(),"c".repeat(64),empty?0:5,empty?2:4);
        assertEquals(empty?0:5,result.byteSize());assertEquals(empty?2:4,result.entryCount());
        assertEquals(JobExecutionProtocol.digest(result.payload()),result.sha256());
        var manifest=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.payload());
        assertEquals(fixture.request().sha256(),manifest.path("request_sha256").textValue());
        assertEquals(JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.STOP).orElseThrow().sha256(),
                manifest.path("stop_sha256").textValue());
        for(String name:List.of("monitor","savedoutput")) assertEquals(JobExecutionProtocol.digest(manifest.path(name).path("manifest_payload").textValue()),
                manifest.path(name).path("manifest_sha256").textValue());
        assertEquals(result,PodmanJobOutput.captureStoppedOutput(connection,fixture.request(),fixture.inspection(),"c".repeat(64),empty?0:5,empty?2:4));
        assertEquals(usage,number("SELECT disk_size FROM starexec.users WHERE id=?",owner));
        assertTrue(JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.OUTPUT).isEmpty());
        assertEquals(3,number("SELECT count(*) FROM starexec.job_execution_receipts WHERE execution_token=?",lease.token()));
        assertNull(JobStorageLeases.find(connection,lease.token()).orElseThrow().releasedAt());
    }

    @ParameterizedTest @ValueSource(strings={"aggregate-bytes","aggregate-entries","monitor-link","saved-link","monitor-hardlink","saved-hardlink",
            "fifo","byte-reservation","entry-reservation","missing-scope"})
    @org.junit.jupiter.api.Timeout(20)
    void stoppedOutputSnapshotRefusesUnsafeFilesAndAggregateBudgets(String change) throws Exception {
        var fixture=stoppedOutputFixture();Path monitor=lease.staging().resolve("execution/monitor"),saved=lease.staging().resolve("execution/savedoutput");
        Files.writeString(monitor.resolve("stdout.txt"),"abc");Files.writeString(saved.resolve("answer.p"),"de");
        long maxBytes=64;int maxEntries=8;
        switch(change) {
            case "aggregate-bytes" -> maxBytes=4;
            case "aggregate-entries" -> maxEntries=3;
            case "monitor-link" -> Files.createSymbolicLink(monitor.resolve("alias"),Path.of("stdout.txt"));
            case "saved-link" -> Files.createSymbolicLink(saved.resolve("alias"),Path.of("answer.p"));
            case "monitor-hardlink" -> Files.createLink(files.resolve("output-alias"),monitor.resolve("stdout.txt"));
            case "saved-hardlink" -> Files.createLink(files.resolve("output-alias"),saved.resolve("answer.p"));
            case "fifo" -> {
                var child=new ProcessBuilder("/usr/bin/mkfifo",monitor.resolve("pipe").toString()).start();
                try {assertTrue(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,child.exitValue());}
                finally {if(child.isAlive()) {child.destroyForcibly();assertTrue(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));}}
            }
            case "byte-reservation" -> maxBytes=lease.limitBytes()+1;
            case "entry-reservation" -> maxEntries=Math.toIntExact(lease.inodeLimit()+1);
            case "missing-scope" -> {Files.delete(saved.resolve("answer.p"));Files.delete(saved);}
            default -> throw new AssertionError(change);
        }
        long bytes=maxBytes;int entries=maxEntries;
        assertThrows(IOException.class,()->PodmanJobOutput.captureStoppedOutput(connection,fixture.request(),fixture.inspection(),"c".repeat(64),bytes,entries));
        assertTrue(JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.OUTPUT).isEmpty());
        assertEquals(3,number("SELECT count(*) FROM starexec.job_execution_receipts WHERE execution_token=?",lease.token()));
        assertNull(JobStorageLeases.find(connection,lease.token()).orElseThrow().releasedAt());
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> heldOutcomes() {
        return java.util.Arrays.stream(JobExecutionProtocol.Transport.values()).flatMap(transport->
                java.util.stream.Stream.of("success","create-lost","inspect-lost","inspect-wrong","inspect-oversize","inspect-body-timeout","cancel",
                        "start-lost","start-503","start-429","start-redirect","start-already-running","start-timeout")
                        .map(outcome->org.junit.jupiter.params.provider.Arguments.of(transport,outcome)));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("heldOutcomes")
    @org.junit.jupiter.api.Timeout(30)
    void freshCreationStartsAHoldOnceOnlyAfterBothAcknowledgements(JobExecutionProtocol.Transport transport,String outcome) throws Exception {
        var adapter=prepare();var saved=plan(adapter,transport);var staging=transportStaging();
        try(var server=new CreateServer(saved,outcome,true)) {
            if(outcome.equals("success")) assertEquals(java.util.Optional.of("c".repeat(64)),
                    PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1));
            else if(outcome.equals("cancel")) assertThrows(SQLException.class,
                    ()->PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1));
            else assertThrows(IOException.class,
                    ()->PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1));
            assertEquals(1,server.posts.get());assertTrue(server.durable.get());
            assertArrayEquals(saved.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8),server.bytes.get());
            assertEquals(outcome.equals("create-lost")?0:1,server.inspections.get());
            int starts=outcome.equals("success") || outcome.startsWith("start-")?1:0;
            assertEquals(starts,server.starts.get());assertEquals(starts==1,server.startDurable.get());
            assertTrue(PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1).isEmpty());
            assertEquals(1,server.posts.get());assertEquals(starts,server.starts.get());
            assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.RELEASE).isEmpty());
            assertNull(JobStorageLeases.find(connection,saved.token()).orElseThrow().releasedAt());
            noTransportWorker();
        }
        assertEquals(staging,transportStaging());
    }

    @ParameterizedTest @ValueSource(strings={"JAVA","CURL"})
    void unknownStartClaimCommitCannotSendAStart(String transport) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.valueOf(transport));
        var uncertain=mock(Connection.class,org.mockito.AdditionalAnswers.delegatesTo(connection));
        var committed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            boolean consumed=number("SELECT count(*) FROM starexec.job_execution_receipts WHERE execution_token=? AND receipt_kind='CREATED'",saved.token())>0;
            connection.commit();if(consumed) {committed.set(true);throw new SQLException("Synthetic held-start reply loss");}return null;
        }).when(uncertain).commit();
        try(var server=new CreateServer(saved,"success",true)) {
            assertThrows(SQLException.class,()->PodmanJobTransport.createAndStartHeldOnce(uncertain,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1));
            assertTrue(committed.get());assertEquals(1,server.posts.get());assertEquals(1,server.inspections.get());assertEquals(0,server.starts.get());
            assertTrue(PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1).isEmpty());
            assertEquals(0,server.starts.get());noTransportWorker();
        }
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> heldHistories() {
        return java.util.Arrays.stream(JobExecutionProtocol.Transport.values()).flatMap(transport->java.util.stream.Stream.of(false,true)
                .map(restored->org.junit.jupiter.params.provider.Arguments.of(transport,restored)));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("heldHistories")
    void consumedCreationNeverRestartsFromWarmOrColdHistory(JobExecutionProtocol.Transport transport,boolean restored) throws Exception {
        var adapter=prepare();var saved=plan(adapter,transport);assertTrue(adapter.claimCreation(connection));
        if(restored) adapter=PodmanJobCreation.restore(client,connection,saved,peerFiles.resolve("peer.sock").toString());
        try(var server=new CreateServer(saved,"success",true)) {
            assertTrue(PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1).isEmpty());
            assertEquals(0,server.posts.get());assertEquals(0,server.inspections.get());assertEquals(0,server.starts.get());noTransportWorker();
        }
    }

    @ParameterizedTest @ValueSource(strings={"JAVA","CURL"})
    @org.junit.jupiter.api.Timeout(30)
    void interruptedHeldStartCannotLeaveAWorkerOrReplay(String transport) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.valueOf(transport));var staging=transportStaging();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        try(var server=new CreateServer(saved,"start-timeout",true)) {
            Thread operation=new Thread(()->{
                try {PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,10);}
                catch(Throwable error) {failure.set(error);interrupted.set(Thread.currentThread().isInterrupted());}
            },"synthetic-held-operation");
            operation.start();
            try {
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while(!server.startDurable.get() && operation.isAlive() && System.nanoTime()<deadline) Thread.sleep(5);
                assertTrue(server.startDurable.get());operation.interrupt();operation.join(7000);
                assertFalse(operation.isAlive());assertInstanceOf(IOException.class,failure.get());assertTrue(interrupted.get());
                assertTrue(PodmanJobTransport.createAndStartHeldOnce(connection,adapter,client,peerFiles.resolve("peer.sock").toString(),1,1).isEmpty());
                assertEquals(1,server.starts.get());noTransportWorker();
            } finally {operation.interrupt();operation.join(7000);assertFalse(operation.isAlive());}
        }
        assertEquals(staging,transportStaging());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("transportClaims")
    @org.junit.jupiter.api.Timeout(30)
    void aHistoricalOrUnacknowledgedClaimCannotSend(JobExecutionProtocol.Transport transport,String change) throws Exception {
        var adapter=prepare();var saved=plan(adapter,transport);var staging=transportStaging();
        try(var server=new CreateServer(saved,"success")) {
            if(change.equals("history")) {
                assertTrue(adapter.claimCreation(connection));
                assertTrue(send(transport,connection,adapter,peerFiles.resolve("peer.sock").toString(),1,2).isEmpty());
            } else {
                var uncertain=mock(Connection.class,org.mockito.AdditionalAnswers.delegatesTo(connection));
                var committed=new java.util.concurrent.atomic.AtomicBoolean();
                doAnswer(call->{
                    boolean consumed=number("SELECT count(*) FROM starexec.job_execution_requests WHERE execution_token=?",lease.token())>0;
                    connection.commit();
                    if(consumed) {committed.set(true);throw new SQLException("Synthetic transport claim reply loss");}
                    return null;
                }).when(uncertain).commit();
                assertThrows(SQLException.class,()->send(transport,uncertain,adapter,peerFiles.resolve("peer.sock").toString(),1,2));
                assertTrue(committed.get());
            }
            assertEquals(saved,JobExecutionProtocol.findCreation(connection,lease.token()).orElseThrow());
            assertTrue(send(transport,connection,adapter,peerFiles.resolve("peer.sock").toString(),1,2).isEmpty());
            assertEquals(0,server.posts.get());noTransportWorker();
        }
        assertEquals(staging,transportStaging());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("transportPreconditions")
    void invalidPreconditionsCannotConsumeAnOrdinaryRequest(JobExecutionProtocol.Transport transport,String change) throws Exception {
        var other=transport==JobExecutionProtocol.Transport.JAVA?JobExecutionProtocol.Transport.CURL:JobExecutionProtocol.Transport.JAVA;
        var adapter=prepare();plan(adapter,change.equals("transport")?other:transport);
        var staging=transportStaging();
        String socket=peerFiles.resolve(change.equals("socket")?"absent.sock":"peer.sock").toString();
        assertThrows(IOException.class,()->send(transport,connection,adapter,socket,1,change.equals("deadline")?0:2));
        assertTrue(JobExecutionProtocol.findCreation(connection,lease.token()).isEmpty());assertEquals(staging,transportStaging());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(JobExecutionProtocol.Transport.class)
    @org.junit.jupiter.api.Timeout(30)
    void interruptedTransportIsReapedWithoutAllowingAnotherPost(JobExecutionProtocol.Transport transport) throws Exception {
        var adapter=prepare();var saved=plan(adapter,transport);var staging=transportStaging();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        try(var server=new CreateServer(saved,"timeout")) {
            var worker=new Thread(()->{
                try {send(transport,connection,adapter,peerFiles.resolve("peer.sock").toString(),1,10);}
                catch(Throwable error) {failure.set(error);}
                finally {interrupted.set(Thread.currentThread().isInterrupted());}
            },"interrupted-ordinary-transport");
            try {
                worker.start();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(server.posts.get()==0 && failure.get()==null && System.nanoTime()<deadline) Thread.sleep(5);
                assertEquals(1,server.posts.get());worker.interrupt();worker.join(6000);
                assertFalse(worker.isAlive());assertInstanceOf(IOException.class,failure.get());assertTrue(interrupted.get());
                assertEquals(saved,JobExecutionProtocol.findCreation(connection,lease.token()).orElseThrow());
                assertTrue(send(transport,connection,adapter,peerFiles.resolve("peer.sock").toString(),1,2).isEmpty());
                assertEquals(1,server.posts.get());
                noTransportWorker();
            } finally {if(worker.isAlive()) {worker.interrupt();worker.join(6000);}assertFalse(worker.isAlive());}
        }
        assertEquals(staging,transportStaging());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode createdInspectionTree(JobExecutionProtocol.Creation request) throws Exception {
        var mapper=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode tree=mapper.valueToTree(neverStartedInspection(request).getRawValues());
        var declared=mapper.readTree(request.payload());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("Config")).set("Env",declared.get("Env"));
        var mounts=tree.putArray("Mounts");
        for(var binding:declared.path("HostConfig").path("Mounts")) {
            var mount=mounts.addObject();mount.put("Type","bind");mount.put("Source",binding.path("Source").textValue());
            mount.put("Destination",binding.path("Target").textValue());mount.put("RW",!binding.path("ReadOnly").booleanValue());
        }
        return tree;
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void exactCreatedObservationConservesConfigurationWithoutGrantingStart(boolean cancelled) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        if(cancelled) number("UPDATE starexec.jobs SET killed=true WHERE id=? RETURNING id",job);
        var mapper=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper();
        var inspected=mapper.treeToValue(createdInspectionTree(saved),com.github.dockerjava.api.command.InspectContainerResponse.class);
        var receipt=PodmanJobObservation.created(saved,"c".repeat(64),inspected,UUID.randomUUID());
        var facts=mapper.readTree(receipt.payload()).path("facts");
        assertEquals(JobExecutionProtocol.Phase.CREATED,receipt.phase());assertFalse(facts.has("start_requested"));
        assertEquals(JobExecutionProtocol.digest(facts.path("configuration_payload").textValue()),facts.path("configuration_sha256").textValue());
        var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt));
        assertFalse(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt));
        assertEquals(receipt,JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.CREATED).orElseThrow());
        assertTrue(JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.RELEASE).isEmpty());
        verify(client,never()).startContainerCmd(anyString());
    }

    @ParameterizedTest @ValueSource(strings={"memory","swap","cpu","quota","period","pids","pids-null","network","source","target","writable",
            "extra","missing","duplicate","type","environment","duplicate-environment","image","launcher"})
    void changedCreatedConfigurationCannotProduceAReceipt(String change) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.CURL);assertTrue(adapter.claimCreation(connection));
        var mapper=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper();var tree=createdInspectionTree(saved);
        var host=(com.fasterxml.jackson.databind.node.ObjectNode)tree.get("HostConfig");
        var config=(com.fasterxml.jackson.databind.node.ObjectNode)tree.get("Config");
        var mounts=(com.fasterxml.jackson.databind.node.ArrayNode)tree.get("Mounts");
        var first=(com.fasterxml.jackson.databind.node.ObjectNode)mounts.get(0);
        switch(change) {
            case "memory" -> host.put("Memory",67108864);
            case "swap" -> host.put("MemorySwap",-1);
            case "cpu" -> host.put("CpusetCpus","3");
            case "quota" -> host.put("CpuQuota",-1);
            case "period" -> host.put("CpuPeriod",200000);
            case "pids" -> host.put("PidsLimit",-1);
            case "pids-null" -> host.putNull("PidsLimit");
            case "network" -> host.put("NetworkMode","host");
            case "source" -> first.put("Source","/other");
            case "target" -> first.put("Destination","/other");
            case "writable" -> first.put("RW",!first.path("RW").booleanValue());
            case "extra" -> mounts.add(first.deepCopy().put("Destination","/other"));
            case "missing" -> mounts.remove(0);
            case "duplicate" -> mounts.set(1,first.deepCopy());
            case "type" -> first.put("Type","volume");
            case "environment" -> config.putArray("Env").add("STAREXEC_JOB_PRIVATE_MOUNTS=0");
            case "duplicate-environment" -> ((com.fasterxml.jackson.databind.node.ArrayNode)config.get("Env")).add(config.path("Env").get(0));
            case "image" -> tree.put("Image","sha256:"+"d".repeat(64));
            case "launcher" -> config.putArray("Cmd").add("other");
            default -> throw new AssertionError(change);
        }
        var inspected=mapper.treeToValue(tree,com.github.dockerjava.api.command.InspectContainerResponse.class);
        assertThrows(IOException.class,()->PodmanJobObservation.created(saved,"c".repeat(64),inspected,UUID.randomUUID()));
        assertTrue(JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.CREATED).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={"period","pids"})
    void unspecifiedLimitsMatchBoundedProviderDefaults(String change) throws Exception {
        var adapter=prepare();var limits=limits();
        if(change.equals("period")) limits.withCpuQuota(-1L).withCpuPeriod(0L);
        else limits.withPidsLimit(null);
        var saved=adapter.plan(connection,JobExecutionProtocol.maximumExecutionId(connection)+1,limits,sources(adapter),Map.of(),JobExecutionProtocol.Transport.JAVA);
        assertTrue(adapter.claimCreation(connection));var tree=createdInspectionTree(saved);
        var host=(com.fasterxml.jackson.databind.node.ObjectNode)tree.get("HostConfig");
        if(change.equals("period")) host.put("CpuQuota",0).put("CpuPeriod",100000);
        else host.put("PidsLimit",512);
        var mapper=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper();
        var inspected=mapper.treeToValue(tree,com.github.dockerjava.api.command.InspectContainerResponse.class);
        var receipt=PodmanJobObservation.created(saved,"c".repeat(64),inspected,UUID.randomUUID());
        assertTrue(JobExecutionProtocol.recordReceipt(connection,JobStorageLeases.find(connection,lease.token()).orElseThrow(),resource,receipt));
    }

    private JobExecutionProtocol.Receipt startClaim(JobExecutionProtocol.Creation saved) throws Exception {
        var mapper=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper();
        var observed=mapper.treeToValue(createdInspectionTree(saved),com.github.dockerjava.api.command.InspectContainerResponse.class);
        var original=PodmanJobObservation.created(saved,"c".repeat(64),observed,UUID.randomUUID());
        var value=mapper.readTree(original.payload());
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.get("facts")).put("start_requested",true);
        String payload=value.toString();
        return new JobExecutionProtocol.Receipt(saved.token(),original.phase(),original.operation(),original.engine(),payload,JobExecutionProtocol.digest(payload));
    }

    @Test void startClaimBindsLocatorInItsFirstCommittedReceipt() throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        var receipt=startClaim(saved);var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt));
        assertEquals(saved.executionId(),number("SELECT sge_id FROM starexec.job_pairs WHERE id=?",pair));
        assertFalse(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt));
        assertEquals(receipt,JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.CREATED).orElseThrow());
        assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.RELEASE).isEmpty());
        verify(client,never()).startContainerCmd(anyString());
    }

    @ParameterizedTest @ValueSource(strings={"killed","paused","deleted"})
    void stoppedJobCannotClaimAnOrdinaryStart(String flag) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.CURL);assertTrue(adapter.claimCreation(connection));
        var receipt=startClaim(saved);var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();
        number("UPDATE starexec.jobs SET "+flag+"=true WHERE id=? RETURNING id",job);
        assertThrows(SQLException.class,()->JobExecutionProtocol.recordReceipt(connection,active,resource,receipt));
        assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.CREATED).isEmpty());
        assertEquals(saved.executionId(),number("SELECT sge_id FROM starexec.job_pairs WHERE id=?",pair));
    }

    @Test void textStartFlagCannotClaimAdmission() throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        var original=startClaim(saved);var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var value=mapper.readTree(original.payload());
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.get("facts")).put("start_requested","true");String payload=value.toString();
        var invalid=new JobExecutionProtocol.Receipt(saved.token(),original.phase(),original.operation(),original.engine(),payload,JobExecutionProtocol.digest(payload));
        var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();
        assertThrows(SQLException.class,()->JobExecutionProtocol.recordReceipt(connection,active,resource,invalid));
        assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.CREATED).isEmpty());
    }

    private com.github.dockerjava.api.command.InspectContainerResponse createdInspection(JobExecutionProtocol.Creation saved) throws Exception {
        return com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper().treeToValue(createdInspectionTree(saved),
                com.github.dockerjava.api.command.InspectContainerResponse.class);
    }

    @ParameterizedTest @ValueSource(strings={"JAVA","CURL"})
    void warmPolicyClaimsOnlyOneHoldStart(String transport) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.valueOf(transport));assertTrue(adapter.claimCreation(connection));
        assertEquals(saved.executionId(),number("SELECT sge_id FROM starexec.job_pairs WHERE id=?",pair));
        assertTrue(adapter.claimStart(connection,"c".repeat(64),createdInspection(saved)));
        var first=JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.CREATED).orElseThrow();
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().readTree(first.payload()).path("facts").path("start_requested").booleanValue());
        assertFalse(adapter.claimStart(connection,"c".repeat(64),createdInspection(saved)));
        assertEquals(first,JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.CREATED).orElseThrow());
        assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.RELEASE).isEmpty());
        verify(client,never()).startContainerCmd(anyString());verify(client,never()).killContainerCmd(anyString());
    }

    @ParameterizedTest @ValueSource(strings={"unconsumed","restored","observed"})
    void historyOrMissingCreationCannotClaimAHoldStart(String state) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);
        if(!state.equals("unconsumed")) assertTrue(adapter.claimCreation(connection));
        if(state.equals("observed")) {
            var observation=PodmanJobObservation.created(saved,"c".repeat(64),createdInspection(saved),UUID.randomUUID());
            assertTrue(JobExecutionProtocol.recordReceipt(connection,JobStorageLeases.find(connection,lease.token()).orElseThrow(),resource,observation));
        }
        if(state.equals("restored")) adapter=PodmanJobCreation.restore(client,connection,saved,peerFiles.resolve("peer.sock").toString());
        assertFalse(adapter.claimStart(connection,"c".repeat(64),createdInspection(saved)));
        verify(client,never()).startContainerCmd(anyString());
    }

    @ParameterizedTest @ValueSource(strings={"attempt","owner","locator","running","terminal","funding","resource"})
    void changedAdmissionCannotClaimAHoldStart(String change) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        var inspection=createdInspection(saved);
        switch(change) {
            case "attempt" -> number("UPDATE starexec.job_pair_attempts SET current_attempt_no=2 WHERE pair_id=? RETURNING pair_id",pair);
            case "owner" -> {
                long other=number("INSERT INTO starexec.users(email,first_name,last_name,institution,created,password,disk_quota,job_pair_quota,disk_size)"
                        +" VALUES (?,'Other','Fixture','synthetic',now(),'synthetic',4194304,100,0) RETURNING id","preparation-"+UUID.randomUUID()+"@example.invalid");
                number("UPDATE starexec.jobs SET user_id=? WHERE id=? RETURNING id",other,job);
            }
            case "locator" -> number("UPDATE starexec.job_pairs SET sge_id=? WHERE id=? RETURNING id",saved.executionId()+1,pair);
            case "running" -> number("UPDATE starexec.job_pairs SET status_code=4 WHERE id=? RETURNING id",pair);
            case "terminal" -> number("UPDATE starexec.job_pairs SET status_code=7 WHERE id=? RETURNING id",pair);
            case "funding" -> number("UPDATE starexec.users SET disk_size=0 WHERE id=? RETURNING id",owner);
            case "resource" -> doThrow(new IOException("Synthetic resource replacement")).when(resource).requireUnchanged();
            default -> throw new AssertionError(change);
        }
        try {assertThrows(SQLException.class,()->adapter.claimStart(connection,"c".repeat(64),inspection));}
        finally {if(change.equals("funding")) number("UPDATE starexec.users SET disk_size=1048576 WHERE id=? RETURNING id",owner);}
        assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.CREATED).isEmpty());
        assertNull(JobStorageLeases.find(connection,lease.token()).orElseThrow().releasedAt());
    }

    @Test void lostStartClaimReplyCannotAuthorizeAnotherStart() throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        var inspection=createdInspection(saved);var uncertain=mock(Connection.class,org.mockito.AdditionalAnswers.delegatesTo(connection));
        var committed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            boolean consumed=number("SELECT count(*) FROM starexec.job_execution_receipts WHERE execution_token=? AND receipt_kind='CREATED'",saved.token())>0;
            connection.commit();if(consumed) {committed.set(true);throw new SQLException("Synthetic start claim reply loss");}return null;
        }).when(uncertain).commit();
        assertThrows(SQLException.class,()->adapter.claimStart(uncertain,"c".repeat(64),inspection));assertTrue(committed.get());
        try(var observer=Common.getConnection()) {assertTrue(JobExecutionProtocol.findReceipt(observer,saved.token(),JobExecutionProtocol.Phase.CREATED).isPresent());}
        assertFalse(adapter.claimStart(connection,"c".repeat(64),inspection));verify(client,never()).startContainerCmd(anyString());
    }

    @Test void contendedStartClaimRollsBackBeforeAdmission() throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        var inspection=createdInspection(saved);
        try(var blocker=Common.getConnection()) {
            Common.runTransactional(blocker,tx->{
                try(var statement=tx.prepareStatement("SELECT id FROM starexec.jobs WHERE id=? FOR UPDATE")) {
                    statement.setInt(1,job);try(var row=statement.executeQuery()) {assertTrue(row.next());}
                }
                var failure=assertThrows(SQLException.class,()->adapter.claimStart(connection,"c".repeat(64),inspection));
                assertEquals("55P03",failure.getSQLState());
                assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.CREATED).isEmpty());
                return null;
            });
            assertTrue(blocker.getAutoCommit());
        }
        assertTrue(adapter.claimStart(connection,"c".repeat(64),inspection));
    }

    @Test void parallelWarmClaimsConserveOnlyOneStartDecision() throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        var inspection=createdInspection(saved);var barrier=new java.util.concurrent.CyclicBarrier(2);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> claim=()->{
                try(var contender=Common.getConnection()) {
                    barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);
                    try {return adapter.claimStart(contender,"c".repeat(64),inspection);}
                    catch(SQLException refused) {if("55P03".equals(refused.getSQLState())) return false;throw refused;}
                }
            };
            var first=executor.submit(claim);var second=executor.submit(claim);
            int acknowledged=(first.get(10,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(second.get(10,java.util.concurrent.TimeUnit.SECONDS)?1:0);
            assertEquals(1,acknowledged);
            assertEquals(1,number("SELECT count(*) FROM starexec.job_execution_receipts WHERE execution_token=? AND receipt_kind='CREATED'",saved.token()));
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }

    private JobExecutionProtocol.Receipt receipt(JobExecutionProtocol.Creation saved,JobExecutionProtocol.Phase phase,
            com.fasterxml.jackson.databind.node.ObjectNode facts) {
        var value=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        var operation=UUID.randomUUID();String engine="podman:"+saved.executionId()+":"+"c".repeat(64);
        value.put("version",1);value.put("kind",phase.name());value.put("execution_token",saved.token().toString());
        value.put("pair_id",saved.pairId());value.put("attempt_no",saved.attemptNo());value.put("owner_id",saved.ownerId());
        value.put("resource_key",saved.resourceKey());value.put("resource_identity",saved.resourceIdentity());
        value.put("operation_token",operation.toString());value.put("engine_identity",engine);
        value.put("request_sha256",saved.sha256());value.put("preparation_sha256",saved.preparationFingerprint());
        value.set("facts",facts);String payload=value.toString();
        return new JobExecutionProtocol.Receipt(saved.token(),phase,operation,engine,payload,JobExecutionProtocol.digest(payload));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode neverStartedFacts() {
        var facts=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        facts.put("resource_id","c".repeat(64));facts.put("image_id",IMAGE);
        facts.put("status","created");facts.put("pid",0);facts.put("running",false);facts.put("paused",false);
        facts.put("restarting",false);facts.put("privileged",false);facts.put("private_pid_namespace",true);
        facts.put("restart_policy","no");facts.put("started_at","0001-01-01T00:00:00Z");
        facts.put("finished_at","0001-01-01T00:00:00Z");facts.put("exit_code",0);facts.put("disposition","never_started");
        return facts;
    }

    private com.github.dockerjava.api.command.InspectContainerResponse neverStartedInspection(JobExecutionProtocol.Creation request)
            throws Exception {
        var mapper=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper();
        var declared=mapper.readTree(request.payload());var tree=mapper.createObjectNode();
        tree.put("Id","c".repeat(64));tree.put("Name","/"+request.name());tree.put("Image",request.image());
        var state=tree.putObject("State");state.put("Status","created");state.put("Pid",0);state.put("ExitCode",0);
        state.put("Running",false);state.put("Paused",false);state.put("Restarting",false);
        state.put("StartedAt","0001-01-01T00:00:00Z");state.put("FinishedAt","0001-01-01T00:00:00Z");
        com.fasterxml.jackson.databind.node.ObjectNode host=declared.path("HostConfig").deepCopy();
        // These effective provider facts are not implied by omitted request fields.
        host.put("PidMode","private");
        host.putObject("RestartPolicy").put("Name","no");tree.set("HostConfig",host);
        var config=tree.putObject("Config");
        for (String name:new String[]{"Labels","User","Entrypoint","Cmd","WorkingDir","Healthcheck"})
            config.set(name,declared.path(name));
        config.put("StopSignal","15");
        var result=mapper.treeToValue(tree,com.github.dockerjava.api.command.InspectContainerResponse.class);
        assertEquals("15",((Map<?,?>)result.getRawValues().get("Config")).get("StopSignal"));
        return result;
    }

    @Test void neverStartedCreationConservesRawTimesWithoutReleasingPayload() throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();
        var facts=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        facts.put("resource_id","c".repeat(64));facts.put("image_id",IMAGE);facts.put("container_name",saved.name());
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt(saved,JobExecutionProtocol.Phase.CREATED,facts)));
        var stop=receipt(saved,JobExecutionProtocol.Phase.STOP,neverStartedFacts());
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,stop));
        assertFalse(JobExecutionProtocol.recordReceipt(connection,active,resource,stop));
        assertEquals(stop,JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.STOP).orElseThrow());
        assertTrue(JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.RELEASE).isEmpty());
        assertNull(JobStorageLeases.find(connection,lease.token()).orElseThrow().releasedAt());
        assertFalse(adapter.claimCreation(connection));verify(client,never()).startContainerCmd(anyString());
    }

    @ParameterizedTest @ValueSource(strings={"running","paused","restarting","pid","privileged","private_pid_namespace",
            "restart_policy","image_id","resource_id","started_at","finished_at","exit_code","status","disposition","null_disposition"})
    void incompleteNeverStartedScopesCannotBypassStopGuards(String field) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();
        var facts=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        facts.put("resource_id","c".repeat(64));facts.put("image_id",IMAGE);facts.put("container_name",saved.name());
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt(saved,JobExecutionProtocol.Phase.CREATED,facts)));
        var invalid=neverStartedFacts();
        switch(field) {
            case "running","paused","restarting","privileged" -> invalid.put(field,true);
            case "pid","exit_code" -> invalid.put(field,1);
            case "private_pid_namespace" -> invalid.put(field,false);
            case "restart_policy" -> invalid.put(field,"always");
            case "image_id","resource_id" -> invalid.put(field,"different");
            case "started_at","finished_at" -> invalid.put(field,"2026-01-01T00:00:01Z");
            case "status" -> invalid.put(field,"running");
            case "disposition" -> invalid.remove(field);
            case "null_disposition" -> invalid.putNull("disposition");
            default -> throw new AssertionError(field);
        }
        var stop=receipt(saved,JobExecutionProtocol.Phase.STOP,invalid);
        assertThrows(SQLException.class,()->JobExecutionProtocol.recordReceipt(connection,active,resource,stop));
        assertTrue(JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.STOP).isEmpty());
        assertNull(JobStorageLeases.find(connection,lease.token()).orElseThrow().releasedAt());
    }

    @Test void typedNeverStartedObservationPersistsWithoutGrantingRelease() throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();
        var facts=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        facts.put("resource_id","c".repeat(64));facts.put("image_id",IMAGE);facts.put("container_name",saved.name());
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt(saved,JobExecutionProtocol.Phase.CREATED,facts)));
        var stop=PodmanJobNonExecution.observe(saved,"c".repeat(64),neverStartedInspection(saved),UUID.randomUUID());
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,stop));
        assertEquals(stop,JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.STOP).orElseThrow());
        var release=receipt(saved,JobExecutionProtocol.Phase.RELEASE,new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        assertThrows(SQLException.class,()->JobExecutionProtocol.recordReceipt(connection,active,resource,release));
        assertNull(JobStorageLeases.find(connection,lease.token()).orElseThrow().releasedAt());
    }

    @Test void releasedPayloadCannotBeReclassifiedAsNeverStarted() throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();
        var facts=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        facts.put("resource_id","c".repeat(64));facts.put("image_id",IMAGE);facts.put("container_name",saved.name());
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt(saved,JobExecutionProtocol.Phase.CREATED,facts)));
        var runtime=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        runtime.put("runtime_payload","{\"version\":1,\"observation\":{\"startTicks\":1,\"memoryBytes\":1024,"
                +"\"pids\":16,\"cpus\":\"0\",\"namespaces\":{\"pid\":\"pid:[1]\",\"ipc\":\"ipc:[2]\","
                +"\"net\":\"net:[3]\",\"mnt\":\"mnt:[4]\",\"user\":\"user:[5]\",\"cgroup\":\"cgroup:[6]\"}}}");
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt(saved,JobExecutionProtocol.Phase.RELEASE,runtime)));
        var stop=receipt(saved,JobExecutionProtocol.Phase.STOP,neverStartedFacts());
        assertThrows(SQLException.class,()->JobExecutionProtocol.recordReceipt(connection,active,resource,stop));
        assertTrue(JobExecutionProtocol.findReceipt(connection,lease.token(),JobExecutionProtocol.Phase.STOP).isEmpty());
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> stoppedOutcomes() {
        return java.util.stream.Stream.of("exited","dead").flatMap(state->java.util.stream.Stream.of(false,true)
                .map(cancelled->org.junit.jupiter.params.provider.Arguments.of(state,cancelled)));
    }
    private com.github.dockerjava.api.command.InspectContainerResponse stoppedInspection(JobExecutionProtocol.Creation saved,String status) throws Exception {
        var tree=createdInspectionTree(saved);((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("State"))
                .put("Status",status).put("ExitCode",137).put("StartedAt","2026-01-01T00:00:01Z").put("FinishedAt","2026-01-01T00:00:02Z");
        return com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper().treeToValue(tree,com.github.dockerjava.api.command.InspectContainerResponse.class);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("stoppedOutcomes")
    void stoppedOrdinaryObservationRoundTripsWithoutReleasingStorage(String status,boolean cancelled) throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        assertTrue(adapter.claimStart(connection,"c".repeat(64),createdInspection(saved)));
        var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();
        if(cancelled) number("UPDATE starexec.jobs SET killed=true WHERE id=? RETURNING id",job);
        else {
            var runtime=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            runtime.put("runtime_payload","{\"version\":1,\"observation\":{\"startTicks\":1,\"memoryBytes\":1024,"
                    +"\"pids\":16,\"cpus\":\"0\",\"namespaces\":{\"pid\":\"pid:[1]\",\"ipc\":\"ipc:[2]\","
                    +"\"net\":\"net:[3]\",\"mnt\":\"mnt:[4]\",\"user\":\"user:[5]\",\"cgroup\":\"cgroup:[6]\"}}}");
            assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,receipt(saved,JobExecutionProtocol.Phase.RELEASE,runtime)));
        }
        var inspection=stoppedInspection(saved,status);
        var observed=PodmanJobStopObservation.observe(saved,"c".repeat(64),inspection,UUID.randomUUID(),cancelled);
        long before=number("SELECT disk_size FROM starexec.users WHERE id=?",owner);
        assertTrue(JobExecutionProtocol.recordReceipt(connection,active,resource,observed));
        var stored=JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.STOP).orElseThrow();
        assertEquals(observed,stored);assertFalse(JobExecutionProtocol.recordReceipt(connection,active,resource,observed));
        assertDoesNotThrow(()->PodmanJobStopObservation.requireSameStopped(saved,"c".repeat(64),inspection,stored));
        assertEquals(before,number("SELECT disk_size FROM starexec.users WHERE id=?",owner));
        assertNull(JobStorageLeases.find(connection,saved.token()).orElseThrow().releasedAt());
        assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.OUTPUT).isEmpty());
        assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.CLEANUP).isEmpty());
        verify(client,never()).removeContainerCmd(anyString());
    }

    @Test void anUnreleasedNormalStopCannotBecomeAResult() throws Exception {
        var adapter=prepare();var saved=plan(adapter,JobExecutionProtocol.Transport.JAVA);assertTrue(adapter.claimCreation(connection));
        assertTrue(adapter.claimStart(connection,"c".repeat(64),createdInspection(saved)));
        var observation=PodmanJobStopObservation.observe(saved,"c".repeat(64),stoppedInspection(saved,"exited"),UUID.randomUUID(),false);
        var active=JobStorageLeases.find(connection,lease.token()).orElseThrow();
        assertThrows(SQLException.class,()->JobExecutionProtocol.recordReceipt(connection,active,resource,observation));
        assertTrue(JobExecutionProtocol.findReceipt(connection,saved.token(),JobExecutionProtocol.Phase.STOP).isEmpty());
        assertNull(JobStorageLeases.find(connection,saved.token()).orElseThrow().releasedAt());
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
