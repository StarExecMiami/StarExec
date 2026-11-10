package org.starexec.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.MountType;
import org.starexec.data.database.JobExecutionProtocol;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Conserves configured identity only; held-kernel verification and action authority remain separate. */
final class PodmanJobObservation {
    private static final ObjectMapper JSON=new ObjectMapper();
    private PodmanJobObservation() { }

    static JobExecutionProtocol.Receipt created(JobExecutionProtocol.Creation request,String container,
            InspectContainerResponse inspection,UUID operation) throws IOException {
        return created(request,container,inspection,operation,false);
    }

    static JobExecutionProtocol.Receipt startClaim(JobExecutionProtocol.Creation request,String container,
            InspectContainerResponse inspection,UUID operation) throws IOException {
        return created(request,container,inspection,operation,true);
    }

    private static JobExecutionProtocol.Receipt created(JobExecutionProtocol.Creation request,String container,
            InspectContainerResponse inspection,UUID operation,boolean start) throws IOException {
        PodmanJobNonExecution.requireCreated(request,container,inspection);
        var declared=JSON.readTree(request.payload());var expected=JSON.treeToValue(declared.path("HostConfig"),HostConfig.class);
        var actual=inspection.getHostConfig();
        require(expected.getMemory()!=null && expected.getMemory()>0 && Objects.equals(expected.getMemory(),actual.getMemory())
                && Objects.equals(expected.getMemory(),expected.getMemorySwap()) && Objects.equals(expected.getMemorySwap(),actual.getMemorySwap())
                && actual.getPidsLimit()!=null && actual.getPidsLimit()>0
                && (expected.getPidsLimit()==null || Objects.equals(expected.getPidsLimit(),actual.getPidsLimit()))
                && expected.getCpusetCpus()!=null && !expected.getCpusetCpus().isBlank() && Objects.equals(expected.getCpusetCpus(),actual.getCpusetCpus())
                && quota(expected.getCpuQuota())==quota(actual.getCpuQuota())
                && (expected.getCpuPeriod()==null || expected.getCpuPeriod()==0 || Objects.equals(expected.getCpuPeriod(),actual.getCpuPeriod()))
                && Objects.equals(expected.getNetworkMode(),actual.getNetworkMode()),"Ordinary created limits differ from their request");
        var wanted=environment(JSON.treeToValue(declared.path("Env"),String[].class));
        var observed=environment(inspection.getConfig().getEnv());
        for(var entry:wanted.entrySet()) require(entry.getValue().equals(observed.get(entry.getKey())),"Ordinary created environment differs");
        var bindings=expected.getMounts();var mounts=inspection.getMounts();
        Object raw=inspection.getRawValues().get("Mounts");
        require(bindings!=null && !bindings.isEmpty() && mounts!=null && mounts.size()==bindings.size()
                && raw instanceof List<?> && ((List<?>)raw).size()==mounts.size(),"Ordinary created mount set differs");
        var targets=new HashSet<String>();var matched=new HashSet<String>();var configuration=JSON.createObjectNode();
        var captured=configuration.putArray("mounts");
        for(var binding:bindings) {
            require(binding.getType()==MountType.BIND && binding.getSource()!=null && binding.getTarget()!=null
                    && binding.getReadOnly()!=null && targets.add(binding.getTarget()),"Ordinary conserved mount declaration is invalid");
            int index=-1;
            for(int i=0;i<mounts.size();i++) if(mounts.get(i).getDestination()!=null && binding.getTarget().equals(mounts.get(i).getDestination().getPath())) {
                require(index<0,"Ordinary created mount target is duplicated");index=i;
            }
            require(index>=0 && matched.add(binding.getTarget()),"Ordinary created mount is unavailable");
            var mount=mounts.get(index);Object fields=((List<?>)raw).get(index);
            require(fields instanceof Map<?,?> && "bind".equals(((Map<?,?>)fields).get("Type"))
                    && binding.getSource().equals(mount.getSource()) && Boolean.valueOf(!binding.getReadOnly()).equals(mount.getRW())
                    && (mount.getName()==null || mount.getName().isEmpty()),"Ordinary created mount identity or access differs");
            var value=captured.addObject();value.put("source",mount.getSource());value.put("target",mount.getDestination().getPath());
            value.put("read_only",!mount.getRW());
        }
        configuration.put("memory",actual.getMemory());configuration.put("memory_swap",actual.getMemorySwap());
        configuration.put("pids",actual.getPidsLimit());configuration.put("cpus",actual.getCpusetCpus());
        configuration.put("cpu_quota",quota(actual.getCpuQuota()));configuration.put("cpu_period",actual.getCpuPeriod());
        configuration.put("network",actual.getNetworkMode());configuration.put("user",inspection.getConfig().getUser());
        configuration.set("entrypoint",JSON.valueToTree(inspection.getConfig().getEntrypoint()));
        configuration.set("command",JSON.valueToTree(inspection.getConfig().getCmd()));
        configuration.put("working_directory",inspection.getConfig().getWorkingDir());
        var env=configuration.putObject("requested_environment");wanted.forEach((key,value)->env.put(key,observed.get(key)));
        String payload=configuration.toString();var facts=JSON.createObjectNode();
        facts.put("resource_id",container);facts.put("container_name",request.name());facts.put("image_id",inspection.getImageId());
        facts.put("configuration_payload",payload);facts.put("configuration_sha256",JobExecutionProtocol.digest(payload));
        if(start) facts.put("start_requested",true);
        facts.put("observed_at",Instant.now().toString());
        return PodmanJobReceipts.observed(request,JobExecutionProtocol.Phase.CREATED,container,operation,facts);
    }

    private static Map<String,String> environment(String[] values) throws IOException {
        require(values!=null,"Ordinary created environment is unavailable");var result=new TreeMap<String,String>();
        for(String value:values) {
            int separator=value==null?-1:value.indexOf('=');
            require(separator>0,"Ordinary created environment entry is invalid");
            require(result.putIfAbsent(value.substring(0,separator),value.substring(separator+1))==null,"Ordinary created environment has duplicate keys");
        }
        return result;
    }
    private static long quota(Long value) {return value==null || value==0?-1:value;}
    private static void require(boolean condition,String message) throws IOException {if(!condition) throw new IOException(message);}
}
