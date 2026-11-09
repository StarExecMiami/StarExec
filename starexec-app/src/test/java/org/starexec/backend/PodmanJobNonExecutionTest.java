package org.starexec.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.data.database.JobExecutionProtocol;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real SDK deserialization of synthetic ownership and Docker-compatible provider fields. */
class PodmanJobNonExecutionTest {
    private static final ObjectMapper JSON=com.github.dockerjava.core.DockerClientConfig.getDefaultObjectMapper();
    private static final String CONTAINER="c".repeat(64),IMAGE="sha256:"+"a".repeat(64);

    private JobExecutionProtocol.Creation creation() throws Exception {
        var token=UUID.randomUUID();String key=UUID.randomUUID().toString().replace("-","");
        var body=JSON.createObjectNode();body.put("Image",IMAGE);body.put("User","0:0");
        body.put("WorkingDir","/starexec/execution/work");body.put("StopSignal","SIGTERM");
        body.putArray("Entrypoint").add("/bin/bash");body.putArray("Cmd").add("/controls/jobscript");body.putArray("Env");
        body.putObject("Healthcheck").putArray("Test").add("NONE");
        var host=body.putObject("HostConfig");host.put("ReadonlyRootfs",true);host.put("Privileged",false);
        host.put("AutoRemove",false);host.put("PidMode","private");host.putObject("RestartPolicy").put("Name","no");
        host.putArray("CapAdd");
        var labels=body.putObject("Labels");
        Map.ofEntries(Map.entry("starexec.managed","true"),Map.entry("starexec.label.version","2"),
                Map.entry("starexec.kind","job-pair"),Map.entry("starexec.job.layout","private-v1"),
                Map.entry("starexec.job.token",token.toString()),Map.entry("starexec.storage.resource",key),
                Map.entry("starexec.job.inputs","b".repeat(64)),Map.entry("starexec.pair.id","42"),
                Map.entry("starexec.attempt","1"),Map.entry("starexec.exec.id","1001")).forEach(labels::put);
        String payload=body.toString();
        return new JobExecutionProtocol.Creation(token,42,1,7,key,"synthetic-resource",1001,
                "starexec-pair-42-attempt-1",IMAGE,"b".repeat(64),JobExecutionProtocol.Transport.JAVA,payload,JobExecutionProtocol.digest(payload));
    }

    static ObjectNode inspectionTree(JobExecutionProtocol.Creation request) throws Exception {
        var declared=JSON.readTree(request.payload());var response=JSON.createObjectNode();
        response.put("Id",CONTAINER);response.put("Name","/"+request.name());response.put("Image",request.image());
        var state=response.putObject("State");state.put("Status","created");state.put("Pid",0);
        state.put("Running",false);state.put("Paused",false);state.put("Restarting",false);state.put("ExitCode",0);
        state.put("StartedAt","0001-01-01T00:00:00Z");state.put("FinishedAt","0001-01-01T00:00:00Z");
        ObjectNode host=declared.path("HostConfig").deepCopy();
        host.putObject("RestartPolicy").put("Name","no");response.set("HostConfig",host);
        var config=response.putObject("Config");
        for (String name:new String[]{"Labels","User","Entrypoint","Cmd","WorkingDir","Healthcheck"})
            config.set(name,declared.path(name));
        config.put("StopSignal","15");
        return response;
    }

    static InspectContainerResponse inspection(JobExecutionProtocol.Creation request) throws Exception {
        var inspection=JSON.treeToValue(inspectionTree(request),InspectContainerResponse.class);
        assertEquals("15",((Map<?,?>)inspection.getRawValues().get("Config")).get("StopSignal"));
        return inspection;
    }

    @ParameterizedTest @ValueSource(strings={"0001-01-01T00:00:00Z","0001-01-01T00:00:00.000000000Z"})
    void compatibleZeroDatesRemainRawInTheStopReceipt(String zero) throws Exception {
        var request=creation();var operation=UUID.randomUUID();var tree=inspectionTree(request);
        ((ObjectNode)tree.get("State")).put("StartedAt",zero).put("FinishedAt",zero);
        var receipt=PodmanJobNonExecution.observe(request,CONTAINER,JSON.treeToValue(tree,InspectContainerResponse.class),operation);
        var payload=JSON.readTree(receipt.payload());var facts=payload.path("facts");
        assertEquals(JobExecutionProtocol.Phase.STOP,receipt.phase());assertEquals(operation,receipt.operation());
        assertEquals("podman:1001:"+CONTAINER,receipt.engine());assertEquals(request.sha256(),payload.path("request_sha256").textValue());
        assertEquals("never_started",facts.path("disposition").textValue());
        assertEquals("created",facts.path("status").textValue());assertEquals(zero,facts.path("started_at").textValue());
        assertEquals(zero,facts.path("finished_at").textValue());assertEquals(0,facts.path("exit_code").intValue());
        assertTrue(Instant.parse(facts.path("observed_at").textValue()).isAfter(Instant.EPOCH));
        assertEquals(JobExecutionProtocol.digest(receipt.payload()),receipt.sha256());
    }

    @ParameterizedTest @ValueSource(strings={"id","name","image","token","resource","inputs","pair","attempt","execution",
            "running","paused","restarting","pid","exit","status","started","finished","missing-time","null-running",
            "privileged","readonly","auto-remove","pid-mode","restart-policy","capabilities","entrypoint","command",
            "user","directory","healthcheck","stop-signal","missing-state","missing-config","missing-host"})
    void foreignIncompleteOrPreviouslyExecutingScopesAreRejected(String change) throws Exception {
        var request=creation();var tree=inspectionTree(request);var state=(ObjectNode)tree.get("State");
        var host=(ObjectNode)tree.get("HostConfig");var config=(ObjectNode)tree.get("Config");var labels=(ObjectNode)config.get("Labels");
        switch(change) {
            case "id" -> tree.put("Id","d".repeat(64));
            case "name" -> tree.put("Name","/other");
            case "image" -> tree.put("Image","sha256:"+"d".repeat(64));
            case "token" -> labels.put("starexec.job.token",UUID.randomUUID().toString());
            case "resource" -> labels.put("starexec.storage.resource","other");
            case "inputs" -> labels.put("starexec.job.inputs","d".repeat(64));
            case "pair" -> labels.put("starexec.pair.id","43");
            case "attempt" -> labels.put("starexec.attempt","2");
            case "execution" -> labels.put("starexec.exec.id","1002");
            case "running" -> state.put("Running",true);
            case "paused" -> state.put("Paused",true);
            case "restarting" -> state.put("Restarting",true);
            case "pid" -> state.put("Pid",123);
            case "exit" -> state.put("ExitCode",1);
            case "status" -> state.put("Status","exited");
            case "started" -> state.put("StartedAt","2026-01-01T00:00:00Z");
            case "finished" -> state.put("FinishedAt","2026-01-01T00:00:00Z");
            case "missing-time" -> state.remove("StartedAt");
            case "null-running" -> state.putNull("Running");
            case "privileged" -> host.put("Privileged",true);
            case "readonly" -> host.put("ReadonlyRootfs",false);
            case "auto-remove" -> host.put("AutoRemove",true);
            case "pid-mode" -> host.put("PidMode","host");
            case "restart-policy" -> ((ObjectNode)host.get("RestartPolicy")).put("Name","always");
            case "capabilities" -> host.putArray("CapAdd").add("SYS_ADMIN");
            case "entrypoint" -> config.putArray("Entrypoint").add("/bin/sh");
            case "command" -> config.putArray("Cmd").add("other");
            case "user" -> config.put("User","1000");
            case "directory" -> config.put("WorkingDir","/other");
            case "healthcheck" -> ((ObjectNode)config.get("Healthcheck")).putArray("Test").add("CMD").add("true");
            case "stop-signal" -> config.put("StopSignal","9");
            case "missing-state" -> tree.remove("State");
            case "missing-config" -> tree.remove("Config");
            case "missing-host" -> tree.remove("HostConfig");
            default -> throw new AssertionError(change);
        }
        var inspected=JSON.treeToValue(tree,InspectContainerResponse.class);
        assertThrows(IOException.class,()->PodmanJobNonExecution.observe(request,CONTAINER,inspected,UUID.randomUUID()));
    }

    @Test void absentSnapshotsAndInvalidOperationsCannotProduceAReceipt() throws Exception {
        var request=creation();var inspection=inspection(request);
        assertThrows(IOException.class,()->PodmanJobNonExecution.observe(request,CONTAINER,null,UUID.randomUUID()));
        assertThrows(IOException.class,()->PodmanJobNonExecution.observe(request,CONTAINER,inspection,null));
        assertThrows(IOException.class,()->PodmanJobNonExecution.observe(request,CONTAINER,inspection,new UUID(0,0)));
    }
}
