package org.starexec.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.JobExecutionProtocol;
import org.starexec.data.database.JobStorageLeases;
import org.starexec.jobs.JobExecutionPreparation;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Locates conserved ordinary output after an exact normal stop; it does not ingest results or retire storage. */
final class PodmanJobOutput {
    private static final ObjectMapper JSON=new ObjectMapper();
    private PodmanJobOutput() { }

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
