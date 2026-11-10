package org.starexec.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.starexec.data.database.JobExecutionProtocol;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/** Conserves exact stopped provider facts; it performs no retirement, result ingestion or accounting. */
final class PodmanJobStopObservation {
    private static final ObjectMapper JSON=new ObjectMapper();
    private PodmanJobStopObservation() { }

    static JobExecutionProtocol.Receipt observe(JobExecutionProtocol.Creation request,String container,
            InspectContainerResponse inspection,UUID operation,boolean cancelled) throws IOException {
        PodmanJobNonExecution.requireIdentity(request,container,inspection);
        var state=inspection.getState();
        require(state!=null && ("exited".equals(state.getStatus()) || "dead".equals(state.getStatus()))
                && Long.valueOf(0).equals(state.getPidLong()) && Boolean.FALSE.equals(state.getRunning())
                && Boolean.FALSE.equals(state.getPaused()) && Boolean.FALSE.equals(state.getRestarting())
                && state.getExitCodeLong()!=null,"Ordinary provider process scope is not positively stopped");
        try {
            Instant started=Instant.parse(state.getStartedAt()),finished=Instant.parse(state.getFinishedAt());
            require(finished.isAfter(Instant.EPOCH) && !started.isAfter(finished),"Ordinary stop time observations are invalid");
        } catch(java.time.DateTimeException | NullPointerException invalid) {
            throw new IOException("Ordinary stop time observations are unavailable",invalid);
        }
        var facts=JSON.createObjectNode();facts.put("resource_id",container);facts.put("image_id",inspection.getImageId());
        facts.put("status",state.getStatus());facts.put("pid",state.getPidLong());facts.put("running",state.getRunning());
        facts.put("paused",state.getPaused());facts.put("restarting",state.getRestarting());facts.put("privileged",false);
        facts.put("private_pid_namespace",true);facts.put("restart_policy",inspection.getHostConfig().getRestartPolicy().getName());
        facts.put("started_at",state.getStartedAt());facts.put("finished_at",state.getFinishedAt());facts.put("exit_code",state.getExitCodeLong());
        if(cancelled) facts.put("disposition","cancelled");facts.put("observed_at",Instant.now().toString());
        return PodmanJobReceipts.observed(request,JobExecutionProtocol.Phase.STOP,container,operation,facts);
    }

    /** A conserved stop cannot replace reinspection of the same retained private provider. */
    static void requireSameStopped(JobExecutionProtocol.Creation request,String container,InspectContainerResponse inspection,
            JobExecutionProtocol.Receipt saved) throws IOException {
        require(saved!=null && saved.phase()==JobExecutionProtocol.Phase.STOP,"Ordinary stop history is unavailable");
        var original=JSON.readTree(saved.payload());var facts=(ObjectNode)original.get("facts");
        require(!facts.has("disposition") || "cancelled".equals(facts.path("disposition").textValue()),"Ordinary stop disposition differs");
        var current=observe(request,container,inspection,saved.operation(),facts.has("disposition"));
        var actual=JSON.readTree(current.payload());
        facts.remove("observed_at");((ObjectNode)actual.get("facts")).remove("observed_at");
        require(original.equals(actual),"Ordinary stopped provider differs from its conserved observation");
    }

    private static void require(boolean condition,String message) throws IOException {if(!condition) throw new IOException(message);}
}
