package org.starexec.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.starexec.data.database.JobExecutionProtocol;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Validates a trusted provider snapshot; it neither starts nor removes a container. */
final class PodmanJobNonExecution {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Instant ZERO_TIME=Instant.parse("0001-01-01T00:00:00Z");

    private PodmanJobNonExecution() { }

    /** The caller must bind this observation to persisted ownership before any retirement. */
    static JobExecutionProtocol.Receipt observe(JobExecutionProtocol.Creation request,String container,
            InspectContainerResponse inspection,UUID operation) throws IOException {
        require(request!=null && inspection!=null && container!=null && container.matches("[0-9a-f]{64}")
                && container.equals(inspection.getId()) && request.image().equals(inspection.getImageId())
                && (request.name().equals(inspection.getName()) || ("/"+request.name()).equals(inspection.getName())),
                "Ordinary non-execution resource identity differs");
        var state=inspection.getState();var host=inspection.getHostConfig();var config=inspection.getConfig();
        require(state!=null && "created".equals(state.getStatus()) && Long.valueOf(0).equals(state.getPidLong())
                && Boolean.FALSE.equals(state.getRunning()) && Boolean.FALSE.equals(state.getPaused())
                && Boolean.FALSE.equals(state.getRestarting()) && Long.valueOf(0).equals(state.getExitCodeLong()),
                "Ordinary resource may already have executed");
        try {
            require(ZERO_TIME.equals(Instant.parse(state.getStartedAt())) && ZERO_TIME.equals(Instant.parse(state.getFinishedAt())),
                    "Ordinary resource has a previous start or finish");
        } catch (java.time.DateTimeException | NullPointerException invalid) {
            throw new IOException("Ordinary non-execution timestamps are unavailable",invalid);
        }
        require(host!=null && Boolean.FALSE.equals(host.getPrivileged()) && Boolean.TRUE.equals(host.getReadonlyRootfs())
                && Boolean.FALSE.equals(host.getAutoRemove()) && "private".equals(host.getPidMode())
                && host.getRestartPolicy()!=null && "no".equals(host.getRestartPolicy().getName())
                && (host.getCapAdd()==null || host.getCapAdd().length==0),
                "Ordinary non-execution process policy is unavailable");
        var declared=JSON.readTree(request.payload());
        require(config!=null && config.getLabels()!=null && declared.path("Labels").isObject(),
                "Ordinary non-execution ownership is unavailable");
        var labels=declared.path("Labels").fields();
        while (labels.hasNext()) {
            var label=labels.next();
            require(label.getValue().isTextual() && label.getValue().textValue().equals(config.getLabels().get(label.getKey())),
                    "Ordinary non-execution ownership differs");
        }
        require("0:0".equals(config.getUser()) && "0:0".equals(declared.path("User").textValue())
                && Arrays.equals(JSON.treeToValue(declared.get("Entrypoint"),String[].class),config.getEntrypoint())
                && Arrays.equals(JSON.treeToValue(declared.get("Cmd"),String[].class),config.getCmd())
                && declared.path("WorkingDir").textValue().equals(config.getWorkingDir())
                && config.getHealthcheck()!=null && List.of("NONE").equals(config.getHealthcheck().getTest()),
                "Ordinary non-execution launcher differs");
        PodmanPrivateBuild.requireStopPolicy(inspection);
        var facts=JSON.createObjectNode();
        facts.put("disposition","never_started");facts.put("resource_id",container);facts.put("image_id",inspection.getImageId());
        facts.put("status",state.getStatus());facts.put("pid",state.getPidLong());facts.put("running",state.getRunning());
        facts.put("paused",state.getPaused());facts.put("restarting",state.getRestarting());facts.put("privileged",false);
        facts.put("private_pid_namespace",true);facts.put("restart_policy",host.getRestartPolicy().getName());
        facts.put("started_at",state.getStartedAt());facts.put("finished_at",state.getFinishedAt());facts.put("exit_code",state.getExitCodeLong());
        facts.put("observed_at",Instant.now().toString());
        var value=JSON.createObjectNode();
        String engine="podman:"+request.executionId()+":"+container;
        value.put("version",1);value.put("kind","STOP");value.put("execution_token",request.token().toString());
        value.put("pair_id",request.pairId());value.put("attempt_no",request.attemptNo());value.put("owner_id",request.ownerId());
        value.put("resource_key",request.resourceKey());value.put("resource_identity",request.resourceIdentity());
        value.put("operation_token",operation==null?null:operation.toString());value.put("engine_identity",engine);
        value.put("request_sha256",request.sha256());value.put("preparation_sha256",request.preparationFingerprint());
        value.set("facts",facts);String payload=value.toString();
        try { return new JobExecutionProtocol.Receipt(request.token(),JobExecutionProtocol.Phase.STOP,operation,engine,payload,JobExecutionProtocol.digest(payload)); }
        catch (IllegalArgumentException invalid) { throw new IOException("Ordinary non-execution receipt is invalid",invalid); }
    }

    private static void require(boolean condition,String message) throws IOException { if(!condition) throw new IOException(message); }
}
