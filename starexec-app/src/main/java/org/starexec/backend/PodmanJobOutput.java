package org.starexec.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.JobExecutionProtocol;
import org.starexec.data.database.JobStorageLeases;
import org.starexec.data.database.JobOutputReservations;
import org.starexec.jobs.JobExecutionPreparation;
import org.starexec.jobs.JobOutputCopy;
import org.starexec.jobs.SolverArtifactTree;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Locates conserved ordinary output after an exact normal stop; it does not ingest results or retire storage. */
final class PodmanJobOutput {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int MAX_MANIFEST=16*1024*1024;
    private PodmanJobOutput() { }

    record Snapshot(String payload,String sha256,long byteSize,int entryCount) { }

    /** Retains and conserves a verified copy; result ingestion and provider retirement remain separate fenced actions. */
    static boolean retainStoppedOutput(Connection connection,JobExecutionProtocol.Creation request,
            InspectContainerResponse inspection,String container,Path parent,long maxBytes,int maxEntries) throws SQLException,IOException {
        if(!connection.getAutoCommit()) throw new SQLException("Ordinary output retention cannot use a foreign transaction");
        if(request==null || !currentNormalPair(connection,request)) return false;
        Snapshot source=captureStoppedOutput(connection,request,inspection,container,maxBytes,maxEntries);
        String parentIdentity=JobOutputCopy.parentIdentity(parent);
        var stop=JobExecutionProtocol.findReceipt(connection,request.token(),JobExecutionProtocol.Phase.STOP).orElseThrow();
        var allocation=JobOutputReservations.find(connection,request.token()).orElse(null);
        var lease=JobStorageLeases.find(connection,request.token()).orElseThrow();
        var resource=DedicatedStorageResource.inspect(lease.root(),new DedicatedStorageResource.Limits(lease.limitBytes(),lease.inodeLimit()),
                List.of(Path.of(R.STAREXEC_DATA_DIR),Path.of(EnvironmentConfig.getJobOutputDirectory()),Path.of(EnvironmentConfig.getJobLogDirectory())));
        if(allocation==null) {
            var facts=JSON.createObjectNode().put("stop_sha256",stop.sha256())
                    .put("source_manifest_payload",source.payload()).put("source_manifest_sha256",source.sha256())
                    .put("parent_path",parent.toString()).put("parent_identity",parentIdentity)
                    .put("target_path",parent.resolve("job-"+request.token()).toString()).put("limit_bytes",maxBytes).put("entry_limit",maxEntries);
            var intent=phase(request,JobExecutionProtocol.Phase.OUTPUT_REQUEST,UUID.randomUUID(),stop.engine(),facts);
            if(!JobOutputReservations.reserve(connection,lease,resource,intent)) return false;
            allocation=JobOutputReservations.find(connection,request.token()).orElseThrow();
        }
        var original=JSON.readTree(allocation.request().payload()).path("facts");
        require(allocation.parent().equals(parent) && allocation.parentIdentity().equals(parentIdentity)
                && allocation.limitBytes()==maxBytes && allocation.entryLimit()==maxEntries
                && source.payload().equals(original.path("source_manifest_payload").textValue())
                && source.sha256().equals(original.path("source_manifest_sha256").textValue()),"Ordinary output retention differs from its original reservation");
        Path monitor=stoppedOutputDirectory(connection,request,inspection,container);
        var copied=JobOutputCopy.retain(connection,allocation,monitor,monitor.getParent().resolve("savedoutput"));
        require(source.equals(captureStoppedOutput(connection,request,inspection,container,maxBytes,maxEntries)),"Stopped ordinary output changed during retention");
        var facts=JSON.createObjectNode().put("stop_sha256",stop.sha256()).put("reservation_sha256",allocation.request().sha256())
                .put("source_manifest_sha256",source.sha256()).put("parent_path",parent.toString()).put("parent_identity",parentIdentity)
                .put("target_path",allocation.target().toString()).put("directory_identity",copied.directoryIdentity())
                .put("manifest_payload",copied.payload()).put("manifest_sha256",copied.sha256());
        var completed=phase(request,JobExecutionProtocol.Phase.OUTPUT,allocation.request().operation(),stop.engine(),facts);
        var stored=JobExecutionProtocol.findReceipt(connection,request.token(),JobExecutionProtocol.Phase.OUTPUT).orElse(null);
        if(stored==null) {
            JobExecutionProtocol.recordReceipt(connection,JobStorageLeases.find(connection,request.token()).orElseThrow(),resource,completed);
            stored=JobExecutionProtocol.findReceipt(connection,request.token(),JobExecutionProtocol.Phase.OUTPUT).orElse(null);
        }
        if(stored==null || !stored.operation().equals(completed.operation()) || !stored.engine().equals(completed.engine())
                || !JSON.readTree(stored.payload()).path("facts").equals(facts)) return false;
        var conserved=JobOutputReservations.find(connection,request.token()).orElseThrow();
        require(conserved.verifiedAt()!=null && Long.valueOf(copied.byteSize()).equals(conserved.verifiedBytes())
                && conserved.chargedBytes()==copied.byteSize(),"Ordinary output retained-byte settlement is unavailable");
        require(copied.equals(JobOutputCopy.verify(conserved)),"Conserved ordinary output copy changed");
        return currentNormalPair(connection,request);
    }

    private static JobExecutionProtocol.Receipt phase(JobExecutionProtocol.Creation request,JobExecutionProtocol.Phase phase,
            UUID operation,String engine,com.fasterxml.jackson.databind.node.ObjectNode facts) {
        var value=JSON.createObjectNode().put("version",1).put("kind",phase.name()).put("execution_token",request.token().toString())
                .put("pair_id",request.pairId()).put("attempt_no",request.attemptNo()).put("owner_id",request.ownerId())
                .put("resource_key",request.resourceKey()).put("resource_identity",request.resourceIdentity())
                .put("operation_token",operation.toString()).put("engine_identity",engine)
                .put("request_sha256",request.sha256()).put("preparation_sha256",request.preparationFingerprint());
        value.set("facts",facts);String payload=value.toString();
        return new JobExecutionProtocol.Receipt(request.token(),phase,operation,engine,payload,JobExecutionProtocol.digest(payload));
    }

    private static boolean currentNormalPair(Connection connection,JobExecutionProtocol.Creation request) throws SQLException {
        try(var query=connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM starexec.job_pairs p JOIN starexec.jobs j ON j.id=p.job_id"
                +" JOIN starexec.job_pair_attempts a ON a.pair_id=p.id WHERE p.id=? AND p.sge_id=? AND a.current_attempt_no=?"
                +" AND j.user_id=? AND j.buildjob IS FALSE AND j.killed IS NOT TRUE AND j.deleted IS NOT TRUE AND p.status_code IS DISTINCT FROM 21)")) {
            query.setInt(1,request.pairId());query.setInt(2,request.executionId());query.setInt(3,request.attemptNo());query.setInt(4,request.ownerId());
            try(var rows=query.executeQuery()) {return rows.next() && rows.getBoolean(1);}
        }
    }

    /** A source observation is not a retained copy, result ingestion or cleanup permission. */
    static Snapshot captureStoppedOutput(Connection connection,JobExecutionProtocol.Creation request,
            InspectContainerResponse inspection,String container,long maxBytes,int maxEntries) throws SQLException,IOException {
        if(maxBytes<0 || maxEntries<2) throw new IllegalArgumentException("Ordinary output requires bounded bytes and both scope roots");
        Path monitor=stoppedOutputDirectory(connection,request,inspection,container);
        var lease=JobStorageLeases.find(connection,request.token()).orElseThrow(()->new IOException("Ordinary output reservation is unavailable"));
        require(maxBytes<=lease.limitBytes() && maxEntries<=lease.inodeLimit(),"Ordinary output observation exceeds its resource budget");
        var first=SolverArtifactTree.inspectOutputDirectory(monitor,maxBytes,maxEntries-2);
        requireNoLinks(first);
        Path saved=monitor.getParent().resolve("savedoutput");
        var second=SolverArtifactTree.inspectOutputDirectory(saved,maxBytes-first.byteSize(),maxEntries-2-first.entryCount());
        requireNoLinks(second);
        require(first.equals(SolverArtifactTree.inspectOutputDirectory(monitor,first.byteSize(),first.entryCount()))
                && second.equals(SolverArtifactTree.inspectOutputDirectory(saved,second.byteSize(),second.entryCount())),
                "Stopped ordinary output changed during observation");
        require(monitor.equals(stoppedOutputDirectory(connection,request,inspection,container)),"Stopped ordinary output layout changed");
        var stop=JobExecutionProtocol.findReceipt(connection,request.token(),JobExecutionProtocol.Phase.STOP).orElseThrow();
        var manifest=JSON.createObjectNode().put("version",1).put("kind","ordinary-output")
                .put("execution_token",request.token().toString()).put("pair_id",request.pairId()).put("attempt_no",request.attemptNo())
                .put("request_sha256",request.sha256()).put("preparation_sha256",request.preparationFingerprint())
                .put("stop_sha256",stop.sha256()).put("engine_identity",stop.engine());
        manifest.set("monitor",scope(first));manifest.set("savedoutput",scope(second));
        long bytes=Math.addExact(first.byteSize(),second.byteSize());int entries=Math.addExact(2,Math.addExact(first.entryCount(),second.entryCount()));
        manifest.put("byte_size",bytes);manifest.put("entry_count",entries);String payload=manifest.toString();
        require(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=MAX_MANIFEST,"Ordinary output manifest exceeds its bound");
        return new Snapshot(payload,JobExecutionProtocol.digest(payload),bytes,entries);
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode scope(SolverArtifactTree.Snapshot snapshot) {
        return JSON.createObjectNode().put("manifest_payload",snapshot.payload()).put("manifest_sha256",snapshot.sha256())
                .put("byte_size",snapshot.byteSize()).put("entry_count",snapshot.entryCount());
    }
    private static void requireNoLinks(SolverArtifactTree.Snapshot snapshot) throws IOException {
        var manifest=JSON.readTree(snapshot.payload());
        require(manifest.path("version").asInt()==1 && manifest.path("entries").isArray(),"Ordinary output tree manifest is invalid");
        for(var entry:manifest.path("entries")) require(!"link".equals(entry.path("kind").textValue()),"Ordinary output contains a symbolic link");
    }

    static Path stoppedOutputDirectory(Connection connection,JobExecutionProtocol.Creation request,
            InspectContainerResponse inspection,String container) throws SQLException,IOException {
        require(request!=null && request.equals(JobExecutionProtocol.findCreation(connection,request.token()).orElse(null)),
                "Stopped ordinary creation history differs");
        var lease=JobStorageLeases.find(connection,request.token()).orElseThrow(()->new IOException("Stopped ordinary ownership is unavailable"));
        require(lease.useStartedAt()!=null && lease.releasedAt()==null && lease.pairId()==request.pairId()
                && lease.attemptNo()==request.attemptNo() && lease.ownerId()==request.ownerId()
                && lease.resourceKey().equals(request.resourceKey()) && lease.resourceIdentity().equals(request.resourceIdentity()),
                "Stopped ordinary output ownership differs");
        var created=JobExecutionProtocol.findReceipt(connection,request.token(),JobExecutionProtocol.Phase.CREATED)
                .orElseThrow(()->new IOException("Stopped ordinary creation observation is unavailable"));
        var release=JobExecutionProtocol.findReceipt(connection,request.token(),JobExecutionProtocol.Phase.RELEASE)
                .orElseThrow(()->new IOException("Stopped ordinary output has no payload release"));
        var stop=JobExecutionProtocol.findReceipt(connection,request.token(),JobExecutionProtocol.Phase.STOP)
                .orElseThrow(()->new IOException("Stopped ordinary output has no positive stop"));
        String engine="podman:"+request.executionId()+":"+container;
        require(engine.equals(created.engine()) && engine.equals(release.engine()) && engine.equals(stop.engine())
                && !JSON.readTree(stop.payload()).path("facts").has("disposition"),"Stopped ordinary output is not a normal conserved result");
        requireNoCleanup(connection,request);
        PodmanJobStopObservation.requireSameStopped(request,container,inspection,stop);
        require(JobExecutionProtocol.digest(PodmanJobObservation.configuration(request,inspection).toString())
                .equals(JSON.readTree(created.payload()).path("facts").path("configuration_sha256").textValue()),
                "Stopped ordinary output configuration changed");
        var resource=DedicatedStorageResource.inspect(lease.root(),new DedicatedStorageResource.Limits(lease.limitBytes(),lease.inodeLimit()),
                List.of(Path.of(R.STAREXEC_DATA_DIR),Path.of(EnvironmentConfig.getJobOutputDirectory()),Path.of(EnvironmentConfig.getJobLogDirectory())));
        Path output=JobExecutionPreparation.retainedOutputDirectory(connection,lease,resource,request);
        requireNoCleanup(connection,request);
        require(lease.equals(JobStorageLeases.find(connection,request.token()).orElse(null)),"Stopped ordinary output ownership changed during recovery");
        return output;
    }

    private static void requireNoCleanup(Connection connection,JobExecutionProtocol.Creation request) throws SQLException,IOException {
        require(JobExecutionProtocol.findReceipt(connection,request.token(),JobExecutionProtocol.Phase.CLEANUP_REQUEST).isEmpty()
                && JobExecutionProtocol.findReceipt(connection,request.token(),JobExecutionProtocol.Phase.CLEANUP).isEmpty(),
                "Stopped ordinary output cleanup has begun");
    }
    private static void require(boolean value,String message) throws IOException {if(!value) throw new IOException(message);}
}
