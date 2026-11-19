package org.starexec.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.starexec.data.database.JobExecutionProtocol;

import java.io.IOException;
import java.util.UUID;

/** Shared exact envelope for trusted ordinary observations; it grants no provider action. */
final class PodmanJobReceipts {
    private static final ObjectMapper JSON=new ObjectMapper();
    private PodmanJobReceipts() { }

    static JobExecutionProtocol.Receipt observed(JobExecutionProtocol.Creation request,JobExecutionProtocol.Phase phase,
            String container,UUID operation,ObjectNode facts) throws IOException {
        if(request==null || container==null || !container.matches("[0-9a-f]{64}"))
            throw new IOException("Ordinary observation identity is invalid");
        String engine="podman:"+request.executionId()+":"+container;var value=JSON.createObjectNode();
        value.put("version",1);value.put("kind",phase==null?null:phase.name());value.put("execution_token",request.token().toString());
        value.put("pair_id",request.pairId());value.put("attempt_no",request.attemptNo());value.put("owner_id",request.ownerId());
        value.put("resource_key",request.resourceKey());value.put("resource_identity",request.resourceIdentity());
        value.put("operation_token",operation==null?null:operation.toString());value.put("engine_identity",engine);
        value.put("request_sha256",request.sha256());value.put("preparation_sha256",request.preparationFingerprint());
        value.set("facts",facts);String payload=value.toString();
        try {return new JobExecutionProtocol.Receipt(request.token(),phase,operation,engine,payload,JobExecutionProtocol.digest(payload));}
        catch(IllegalArgumentException invalid) {throw new IOException("Ordinary observation receipt is invalid",invalid);}
    }
}
