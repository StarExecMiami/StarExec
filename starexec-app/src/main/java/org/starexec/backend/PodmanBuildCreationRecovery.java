package org.starexec.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.MountType;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildCreations;

/** Recovers a consumed create as cancellation only; catalog readback never authorizes launch. */
final class PodmanBuildCreationRecovery {
    private PodmanBuildCreationRecovery() { }

    static Map<String,String> labels(SolverBuildCreations.Creation creation) throws IOException {
        var node=new ObjectMapper().readTree(creation.payload()).path("Labels");
        var labels=new java.util.LinkedHashMap<String,String>();
        node.properties().forEach(e->labels.put(e.getKey(),e.getValue().textValue()));
        require(labels.values().stream().allMatch(Objects::nonNull),"Conserved creation labels are invalid");
        return Map.copyOf(labels);
    }

    static boolean cancel(DockerClient client,String socket,InspectContainerResponse inspection,String container,
                          SolverBuildCreations.Creation creation) throws IOException,SQLException {
        PodmanPrivateBuild.requireLocalRootlessProvider(client,socket);
        requireCreated(inspection,container,creation);
        var registration=ExecutionStorageLeases.registeredResource(creation.build()).orElseThrow(
                ()->new IOException("Pending creation resource is unregistered"));
        require(creation.resourceKey().equals(registration.key()) && creation.resourceIdentity().equals(registration.identity()),
                "Pending creation lease differs from its resource");
        var resource=registration.inspect(List.of(Path.of(R.STAREXEC_DATA_DIR),Path.of(EnvironmentConfig.getJobOutputDirectory()),
                Path.of(EnvironmentConfig.getJobLogDirectory())));
        var receipt=PodmanBuildStopObserver.unacknowledgedCreationReceipt(inspection,container,creation);
        return SolverBuildCreations.cancel(creation,resource,container,receipt);
    }

    /** Revalidate the conserved policy without accessing a possibly reclaimed staging tree. */
    static void requireCreated(InspectContainerResponse inspection,String container,SolverBuildCreations.Creation creation)
            throws IOException {
        require(inspection!=null && container!=null && container.matches("[0-9a-f]{64}")
                && container.equals(inspection.getId()) && (creation.name().equals(inspection.getName())
                    || ("/"+creation.name()).equals(inspection.getName()))
                && creation.image().equals(inspection.getImageId()),"Pending creation resource identity differs");
        PodmanBuildStopObserver.requireNeverStarted(inspection,container,creation.build(),creation.execution());
        var mapper=new ObjectMapper();var request=mapper.readTree(creation.payload());
        var host=mapper.treeToValue(request.get("HostConfig"),HostConfig.class);
        var actual=inspection.getHostConfig();var config=inspection.getConfig();
        require(config!=null && config.getLabels()!=null && labels(creation).entrySet().stream()
                .allMatch(e->e.getValue().equals(config.getLabels().get(e.getKey()))),"Pending creation ownership differs");
        require("0:0".equals(request.path("User").textValue()) && "0:0".equals(config.getUser())
                && Arrays.equals(mapper.treeToValue(request.get("Entrypoint"),String[].class),config.getEntrypoint())
                && Arrays.equals(mapper.treeToValue(request.get("Cmd"),String[].class),config.getCmd())
                && Objects.equals(request.path("WorkingDir").textValue(),config.getWorkingDir())
                && "SIGTERM".equals(request.path("StopSignal").textValue())
                && request.path("Healthcheck").path("Test").equals(mapper.valueToTree(List.of("NONE")))
                && config.getHealthcheck()!=null && List.of("NONE").equals(config.getHealthcheck().getTest()),
                "Pending creation launcher differs");
        PodmanPrivateBuild.requireStopPolicy(inspection);
        require(actual!=null && Boolean.TRUE.equals(host.getReadonlyRootfs()) && Boolean.TRUE.equals(actual.getReadonlyRootfs())
                && Objects.equals(host.getMemory(),actual.getMemory()) && Objects.equals(host.getMemorySwap(),actual.getMemorySwap())
                && host.getMemory()!=null && host.getMemory()>0 && host.getMemory().equals(host.getMemorySwap())
                && Objects.equals(host.getCpusetCpus(),actual.getCpusetCpus()) && host.getCpusetCpus()!=null && !host.getCpusetCpus().isBlank()
                && quota(host.getCpuQuota())==quota(actual.getCpuQuota())
                && (host.getCpuPeriod()==null || host.getCpuPeriod()==0 || Objects.equals(host.getCpuPeriod(),actual.getCpuPeriod()))
                && actual.getPidsLimit()!=null && actual.getPidsLimit()>0
                && (host.getPidsLimit()==null || host.getPidsLimit().equals(actual.getPidsLimit()))
                && Objects.equals(host.getNetworkMode(),actual.getNetworkMode()),"Pending creation host policy differs");
        var declared=host.getMounts();var observed=inspection.getMounts();
        require(declared!=null && !declared.isEmpty() && observed!=null && declared.size()==observed.size(),
                "Pending creation mount set differs");
        var targets=new java.util.HashSet<String>();
        for (var mount:declared) {
            require(mount.getType()==MountType.BIND && mount.getSource()!=null && mount.getTarget()!=null
                    && mount.getReadOnly()!=null && targets.add(mount.getTarget()),"Conserved creation mount is invalid");
            var matches=observed.stream().filter(m->m.getDestination()!=null
                    && mount.getTarget().equals(m.getDestination().getPath())).toList();
            require(matches.size()==1 && mount.getSource().equals(matches.get(0).getSource())
                    && Boolean.valueOf(!mount.getReadOnly()).equals(matches.get(0).getRW())
                    && (matches.get(0).getName()==null || matches.get(0).getName().isEmpty()),
                    "Pending creation effective mount differs");
        }
    }

    private static long quota(Long value) { return value==null || value==0?-1:value; }
    private static void require(boolean value,String message) throws IOException { if(!value) throw new IOException(message); }
}
