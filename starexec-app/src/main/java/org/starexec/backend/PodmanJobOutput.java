package org.starexec.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.JobExecutionProtocol;
import org.starexec.data.database.JobStorageLeases;
import org.starexec.jobs.JobExecutionPreparation;
import org.starexec.jobs.SolverArtifactTree;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Locates conserved ordinary output after an exact normal stop; it does not ingest results or retire storage. */
final class PodmanJobOutput {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int MAX_MANIFEST=16*1024*1024;
    private PodmanJobOutput() { }

    record Snapshot(String payload,String sha256,long byteSize,int entryCount) { }

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
