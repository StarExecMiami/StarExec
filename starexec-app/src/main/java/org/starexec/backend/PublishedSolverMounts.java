package org.starexec.backend;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.BindOptions;
import com.github.dockerjava.api.model.BindPropagation;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import org.starexec.backend.storage.BuildArtifactStore;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.PublishedSolverInputs;
import org.starexec.jobs.SolverBuildPublication;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact read-only published inputs, checked in the consumer namespace before its jobscript starts. */
public final class PublishedSolverMounts {
    public static final String LABEL="starexec.inputs.layout";
    public static final String LAYOUT="published-v1";
    private static final String VERIFY="""
            set -euo pipefail
            [[ $$ == 1 ]] || exit 75
            no_new_privileges=0
            capabilities=0
            while IFS=: read -r name value; do
              value=${value//[[:space:]]/}
              case "$name" in
                NoNewPrivs) [[ "$value" == 1 ]] || exit 75; no_new_privileges=1 ;;
                CapEff|CapPrm|CapBnd)
                  [[ "$value" =~ ^[0-9a-fA-F]{1,16}$ ]] || exit 75
                  (( (16#$value & 2097152) == 0 )) || exit 75
                  capabilities=$((capabilities+1)) ;;
              esac
            done < /proc/self/status
            [[ "$no_new_privileges" == 1 && "$capabilities" == 3 ]] || exit 75
            count=$1; shift
            [[ "$count" =~ ^[1-9][0-9]*$ ]] || exit 75
            for ((index=0; index<count; index++)); do
              target=$1; point=$2; device=$3; root=$4; node=$5; shift 5
              [[ -d "$target" && ! -L "$target" ]] || exit 75
              [[ "$(/bin/stat -c '%d:%i:%f' -- "$target")" == "$node" ]] || exit 75
              matches=0
              while IFS=' ' read -r mount parent observed_device observed_root observed_point options remaining; do
                if [[ "$observed_point" == "$point" ]]; then
                  [[ "$observed_device" == "$device" && "$observed_root" == "$root" \
                     && ",$options," == *,ro,* && ",$options," != *,rw,* ]] || exit 75
                  matches=$((matches+1))
                elif [[ "$observed_point" == "$point/"* ]]; then
                  exit 75
                fi
                if [[ "$observed_device" == "$device" && ",$options," == *,rw,* ]]; then
                  if [[ "$observed_root" == / || "$observed_root" == "$root" \
                      || "$observed_root" == "$root/"* || "$root" == "$observed_root/"* ]]; then
                    exit 75
                  fi
                fi
              done < /proc/self/mountinfo
              [[ "$matches" == 1 ]] || exit 75
            done
            exec /bin/bash --noprofile --norc -p "$@"
            """;
    private final BuildArtifactStore store;
    private final List<BuildArtifactStore.ReadOnlyMount> mounts;
    private final List<PublishedSolverInputs.Input> inputs;
    private final String image;

    private PublishedSolverMounts(BuildArtifactStore store,List<BuildArtifactStore.ReadOnlyMount> mounts,
                                   List<PublishedSolverInputs.Input> inputs,String image) {
        this.store=store;this.mounts=List.copyOf(mounts);this.inputs=List.copyOf(inputs);this.image=image;
    }

    public static PublishedSolverMounts prepare(DockerClient client,String socket,String image,int pair,Integer attempt,
                                                 List<java.nio.file.Path> writableRoots) throws SQLException,IOException {
        var inputs=PublishedSolverInputs.forPair(pair,attempt);
        if (inputs.isEmpty()) return null;
        PodmanPrivateBuild.requireLocalRootlessProvider(client,socket);
        var inspected=client.inspectImageCmd(image).exec();
        require(inspected!=null && inspected.getId()!=null && inspected.getId().matches("sha256:[0-9a-f]{64}")
                && inspected.getConfig()!=null && (inspected.getConfig().getVolumes()==null
                || inspected.getConfig().getVolumes().isEmpty()),"Published solver image identity or volume policy is unavailable");
        var excluded=new ArrayList<>(writableRoots);excluded.addAll(ExecutionStorageLeases.registeredRootPaths());
        var store=BuildArtifactStore.configured(excluded);
        var mounts=new ArrayList<BuildArtifactStore.ReadOnlyMount>();
        for (var input:inputs) {
            SolverBuildPublication.verifyPublishedInput(input.artifact(),input.manifest());
            mounts.add(store.readOnlyMount(input.artifact()));
        }
        return new PublishedSolverMounts(store,mounts,inputs,inspected.getId());
    }

    public String image() { return image; }

    public List<String> entrypoint() {
        var command=new ArrayList<>(List.of("/bin/bash","--noprofile","--norc","-p","-c",VERIFY,"starexec-published-inputs",Integer.toString(mounts.size())));
        for (var mount:mounts) command.addAll(List.of(mount.source().toString(),BuildArtifactStore.kernelPath(mount.source()),mount.device(),mount.kernelRoot(),mount.node()));
        return List.copyOf(command);
    }

    public HostConfig hostConfig(HostConfig host) throws IOException {
        store.requireUnchanged();
        var requested=new ArrayList<Mount>();
        if (host.getMounts()!=null) requested.addAll(host.getMounts());
        for (var source:mounts) requested.add(new Mount().withType(MountType.BIND).withSource(source.source().toString())
                .withTarget(source.source().toString()).withReadOnly(true)
                .withBindOptions(new BindOptions().withPropagation(BindPropagation.R_PRIVATE)));
        var options=new ArrayList<String>();
        if (host.getSecurityOpts()!=null) options.addAll(host.getSecurityOpts());
        require(options.stream().noneMatch(v->v.startsWith("no-new-privileges:") && !v.equals("no-new-privileges:true")),
                "Published solver consumers cannot disable no-new-privileges");
        if (!options.contains("no-new-privileges") && !options.contains("no-new-privileges:true")) options.add("no-new-privileges");
        return host.withMounts(requested).withSecurityOpts(options).withPrivileged(false).withAutoRemove(false);
    }

    public void requireCreated(InspectContainerResponse observed,String container,int pair,int attempt,int execution,List<String> command) throws IOException {
        require(observed!=null && container.equals(observed.getId()) && image.equals(observed.getImageId()),"Published input consumer resource differs");
        var config=observed.getConfig();
        require(config!=null && config.getLabels()!=null && LAYOUT.equals(config.getLabels().get(LABEL))
                && "true".equals(config.getLabels().get("starexec.managed"))
                && "2".equals(config.getLabels().get("starexec.label.version"))
                && Integer.toString(pair).equals(config.getLabels().get("starexec.pair.id"))
                && Integer.toString(attempt).equals(config.getLabels().get("starexec.attempt"))
                && Integer.toString(execution).equals(config.getLabels().get("starexec.exec.id")),"Published input consumer ownership differs");
        require(Arrays.equals(config.getEntrypoint(),entrypoint().toArray(String[]::new))
                && Arrays.equals(config.getCmd(),command.toArray(String[]::new)) && "0:0".equals(config.getUser())
                && config.getHealthcheck()!=null && List.of("NONE").equals(config.getHealthcheck().getTest()),"Published input consumer launcher differs");
        PodmanPrivateBuild.requireStopPolicy(observed);
        var state=observed.getState();var host=observed.getHostConfig();
        require(state!=null && "created".equals(state.getStatus()) && Boolean.FALSE.equals(state.getRunning())
                && Boolean.FALSE.equals(state.getRestarting()) && Boolean.FALSE.equals(state.getPaused())
                && Long.valueOf(0).equals(state.getPidLong())
                && java.time.Instant.parse("0001-01-01T00:00:00Z").equals(java.time.Instant.parse(state.getStartedAt())),
                "Published input consumer may already have started");
        require(host!=null && Boolean.FALSE.equals(host.getPrivileged()) && (host.getPidMode()==null
                || host.getPidMode().isEmpty() || host.getPidMode().equals("private"))
                && (host.getDevices()==null || host.getDevices().length==0),"Published input consumer privilege scope differs");
        store.requireUnchanged();
        for (var input:inputs) SolverBuildPublication.verifyPublishedInput(input.artifact(),input.manifest());
    }

    private static void require(boolean value,String why) throws IOException { if (!value) throw new IOException(why); }
}
