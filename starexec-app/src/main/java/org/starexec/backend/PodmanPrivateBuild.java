package org.starexec.backend;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.model.Frame;
import jdk.net.ExtendedSocketOptions;
import org.starexec.backend.storage.BuildExecutionHold;
import org.starexec.backend.storage.BuildExecutionMounts;
import org.starexec.backend.storage.BuildExecutionRuntime;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.util.DescriptorDirectory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** One leased private execution; only a newly confirmed release decision enables its payload. */
public final class PodmanPrivateBuild {
    public static final String LAYOUT_LABEL = "starexec.build.layout";
    public static final String LAYOUT = "private-v1";
    public static final int HOLD_SECONDS = 60;
    private final BuildExecutionContext build;
    private final DedicatedStorageResource resource;
    private final BuildExecutionMounts mounts;
    private final BuildExecutionRuntime.Identity identity;
    private final Map<String,String> parentNamespaces;
    private final String image;
    private final UUID releaseRequest = UUID.randomUUID();
    private HostConfig requested;
    private BuildExecutionRuntime runtime;

    private PodmanPrivateBuild(BuildExecutionContext build, DedicatedStorageResource resource,
                               BuildExecutionMounts mounts, BuildExecutionRuntime.Identity identity,
                               Map<String,String> namespaces, String image) {
        this.build=build;this.resource=resource;this.mounts=mounts;this.identity=identity;
        this.parentNamespaces=Map.copyOf(namespaces);this.image=image;
    }

    public static PodmanPrivateBuild prepare(DockerClient client, BuildExecutionContext build,
                                             String image, String socket, BuildExecutionMounts.Controls controls)
            throws IOException, SQLException {
        var identity = requireLocalRootlessProvider(client,socket);
        var inspected = client.inspectImageCmd(image).exec();
        require(inspected!=null && inspected.getId()!=null && inspected.getId().matches("sha256:[0-9a-f]{64}"),
                "Private build image identity is unavailable");
        require(inspected.getConfig()!=null && (inspected.getConfig().getVolumes()==null
                || inspected.getConfig().getVolumes().isEmpty()),"Private build image declares unreserved volumes");
        var registration = ExecutionStorageLeases.registeredResource(build)
                .orElseThrow(() -> new IOException("Private build resource registration is unavailable"));
        var resource = registration.inspect(protectedRoots());
        var mounts = ExecutionStorageLeases.prepareMounts(build,resource,controls);
        Map<String,String> namespaces = new LinkedHashMap<>();
        for (String name:List.of("pid","ipc","user","net","mnt","cgroup")) {
            namespaces.put(name,Files.readSymbolicLink(Path.of("/proc/self/ns/"+name)).toString());
        }
        return new PodmanPrivateBuild(build,resource,mounts,identity,namespaces,inspected.getId());
    }

    private static List<Path> protectedRoots() {
        return List.of(Path.of(R.STAREXEC_DATA_DIR),Path.of(EnvironmentConfig.getJobOutputDirectory()),
                Path.of(EnvironmentConfig.getJobLogDirectory()));
    }

    /** Locate output only from the conserved private layout after a verified provider stop. */
    public static Path stoppedOutputDirectory(InspectContainerResponse inspection, String container)
            throws SQLException, IOException {
        require(PodmanBuildStopObserver.isBuild(inspection)
                && LAYOUT.equals(inspection.getConfig().getLabels().get(LAYOUT_LABEL)),
                "Stopped build layout is unsupported");
        int execution=PodmanBuildStopObserver.buildExecution(inspection,container);
        var labels=inspection.getConfig().getLabels();
        int pair=Integer.parseInt(labels.get("starexec.pair.id"));
        int attempt=Integer.parseInt(labels.get("starexec.attempt"));
        long id=Long.parseLong(labels.get(PodmanBuildStopObserver.ARTIFACT_LABEL));
        String engine="podman:"+execution+":"+container;
        var artifact=SolverBuildArtifacts.find(pair,attempt)
                .orElseThrow(() -> new IOException("Stopped build registration is unavailable"));
        require(artifact.id()==id && artifact.executionToken()!=null && artifact.sourcePath()!=null
                && artifact.stagingPath()!=null && engine.equals(artifact.engineIdentity())
                && artifact.stoppedAt()!=null,"Stopped build differs from the admitted execution");
        var stored=SolverBuildArtifacts.findStopReceipt(pair,attempt)
                .orElseThrow(() -> new IOException("Stopped build receipt is unavailable"));
        var receipt=stored.receipt();
        var facts=receipt.facts();
        require(receipt.matches(id,pair,attempt,engine) && "podman".equals(receipt.backend())
                && container.equals(receipt.resourceId()) && !facts.has("disposition")
                && facts.has("image_id") && Objects.equals(inspection.getImageId(),facts.get("image_id").getAsString()),
                "Stopped build receipt differs from its output owner");
        var build=new BuildExecutionContext(id,pair,attempt,artifact.executionToken(),
                Path.of(artifact.sourcePath()),Path.of(artifact.stagingPath()));
        var registration=ExecutionStorageLeases.registeredResource(build)
                .orElseThrow(() -> new IOException("Stopped build storage registration is unavailable"));
        var resource=registration.inspect(protectedRoots());
        var cleanup=ExecutionStorageLeases.findStagingCleanup(build,resource)
                .orElseThrow(() -> new IOException("Stopped build lease is unavailable"));
        require(cleanup.token()==null && cleanup.startedAt()==null && cleanup.finishedAt()==null
                && cleanup.releasedAt()==null,"Stopped build storage cleanup has begun");
        var release=ExecutionStorageLeases.findReleaseDecision(build,resource)
                .orElseThrow(() -> new IOException("Stopped build has no conserved payload release"));
        require(engine.equals(release.engineIdentity()),"Stopped build release owner differs");
        Path output=BuildExecutionMounts.hostOutputDirectory(build);
        try (DescriptorDirectory ignored=DescriptorDirectory.open(output)) {
            resource.requireUnchanged();
        }
        return output;
    }

    /** Socket path digits are never evidence of provider ownership or user mapping. */
    static BuildExecutionRuntime.Identity requireLocalRootlessProvider(DockerClient client, String socket)
            throws IOException {
        require(socket!=null,"Private build provider socket is unavailable");
        Path endpoint=Path.of(socket.startsWith("unix://")?socket.substring(7):socket);
        require(endpoint.isAbsolute() && endpoint.equals(endpoint.normalize()),"Private builds require a local Unix provider");
        Path process=Path.of("/proc/self");
        long uid=((Number)Files.getAttribute(process,"unix:uid")).longValue();
        long gid=((Number)Files.getAttribute(process,"unix:gid")).longValue();
        require(uid>0 && gid>0,"Private builds require a known unprivileged application identity");
        try (DescriptorDirectory parent=DescriptorDirectory.open(endpoint.getParent())) {
            var node=Files.readAttributes(endpoint,"unix:uid,mode",LinkOption.NOFOLLOW_LINKS);
            require(((Number)node.get("uid")).longValue()==uid
                    && (((Number)node.get("mode")).intValue()&0170000)==0140000,
                    "Private build provider socket ownership is not verified");
            try (SocketChannel channel=SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.configureBlocking(false);
                if (!channel.connect(UnixDomainSocketAddress.of(endpoint))) {
                    try (Selector selector=Selector.open()) {
                        channel.register(selector,SelectionKey.OP_CONNECT);
                        require(selector.select(3000)>0 && channel.finishConnect(),"Private provider peer observation timed out");
                    }
                }
                var peer=channel.getOption(ExtendedSocketOptions.SO_PEERCRED);
                require(peer!=null && peer.user().equals(Files.getOwner(process)),
                        "Private build provider peer differs from the application owner");
            }
        }
        var info=client.infoCmd().exec();
        require(info!=null && info.getSecurityOptions()!=null && info.getSecurityOptions().stream()
                .anyMatch(option -> "name=rootless".equals(option) || "rootless".equals(option)),
                "Private build provider is not verified rootless");
        return new BuildExecutionRuntime.Identity(0,0,uid,gid);
    }

    public String image() { return image; }
    public String creationName() { return org.starexec.data.database.SolverBuildCreations.containerName(build); }

    /** A pre-send failure or historical claim never permits a provider request. */
    public boolean claimCreation(int execution,org.starexec.data.database.SolverBuildCreations.Transport transport,
                                 String payload) throws IOException,SQLException {
        require(requested!=null,"Private build creation policy is unavailable");
        mounts.requireUnchangedInputs();
        return org.starexec.data.database.SolverBuildCreations.claim(build,resource,execution,image,transport,payload);
    }

    public BuildExecutionMounts mounts() { return mounts; }
    public List<String> entrypoint() { return BuildExecutionHold.entrypoint(HOLD_SECONDS); }
    public List<String> command() { return BuildExecutionHold.command(mounts.script()); }
    public List<String> environment() {
        List<String> result=new ArrayList<>();
        mounts.environment().forEach((name,value) -> result.add(name+"="+value));
        result.add("CONTAINER_MODE=true");
        return List.copyOf(result);
    }

    public HostConfig hostConfig(HostConfig limits, Map<Path,Path> providerSources) throws IOException {
        require(limits.getMemory()!=null && Objects.equals(limits.getMemory(),limits.getMemorySwap()),
                "Private build memory and swap policy is unavailable");
        require(limits.getCpusetCpus()!=null && !limits.getCpusetCpus().isBlank()
                && (quota(limits.getCpuQuota())>0 && period(limits.getCpuPeriod())>0
                    || quota(limits.getCpuQuota())==-1 && period(limits.getCpuPeriod())>=0),
                "Private build CPU policy is unavailable");
        requested=mounts.toPodmanHostConfig(limits,providerSources);
        return requested;
    }

    public void requireCreated(InspectContainerResponse inspection, String container, int execution) throws IOException {
        requireMetadata(inspection,container,execution);
        PodmanBuildStopObserver.requireNeverStarted(inspection,container,build,execution);
        require(requested!=null,"Private build creation policy is unavailable");
        var actual=inspection.getHostConfig();
        require(Objects.equals(requested.getMemory(),actual.getMemory())
                && Objects.equals(requested.getMemorySwap(),actual.getMemorySwap())
                && Objects.equals(requested.getCpusetCpus(),actual.getCpusetCpus())
                && (period(requested.getCpuPeriod())==0 || period(requested.getCpuPeriod())==period(actual.getCpuPeriod()))
                && quota(requested.getCpuQuota())==quota(actual.getCpuQuota()),"Created build limits differ from the requested policy");
        require(actual.getPidsLimit()!=null && actual.getPidsLimit()>0
                && (requested.getPidsLimit()==null || requested.getPidsLimit().equals(actual.getPidsLimit())),
                "Created build has no conserved finite process limit");
        mounts.requireUnchangedInputs();
        runtime=BuildExecutionRuntime.capture(mounts.bindings(),identity,new BuildExecutionRuntime.Limits(
                requested.getMemory(),actual.getPidsLimit(),requested.getCpusetCpus(),
                quota(requested.getCpuQuota()),period(requested.getCpuPeriod())),parentNamespaces,HOLD_SECONDS);
    }

    private static long quota(Long value) { return value==null || value==0 ? -1 : value; }
    private static long period(Long value) { return value==null ? 0 : value; }

    public boolean admit(int execution, String container) throws IOException, SQLException {
        require(runtime!=null,"Private build runtime policy has not been captured");
        mounts.requireUnchangedInputs();
        return ExecutionStorageLeases.admitPodman(build,resource,execution,container);
    }

    /** A known refusal can cancel only the same fully verified, never-started resource. */
    public boolean cancelRefusedCreation(InspectContainerResponse inspection,String container,int execution) throws IOException,SQLException {
        requireCreated(inspection,container,execution);
        var receipt=PodmanBuildStopObserver.cancelledCreationReceipt(inspection,container,build,execution);
        return ExecutionStorageLeases.cancelRefusedPodmanCreation(build,resource,execution,container,receipt);
    }

    /** Provider absence must precede retrying the same catalogued non-execution. */
    public void retryRefusedCreation(String container,int execution) throws SQLException,IOException {
        var artifact=org.starexec.data.database.SolverBuildArtifacts.find(build.pairId(),build.attemptNo()).orElseThrow(
                ()->new SQLException("Cancelled build history is unavailable"));
        var restored=PodmanBuildStopObserver.restoreRefusedCreation(artifact);
        require(restored.execution()==execution && restored.container().equals(container),"Refused build recovery identity differs");
        org.starexec.data.database.SolverBuildArtifacts.retryRefusedPodmanCreation(build,execution,container,restored.receipt());
    }

    /** Execute only against the exact verified held resource; zero swap is independently required. */
    public static List<String> observationCommand() {
        List<String> command=new ArrayList<>(BuildExecutionRuntime.observationCommand());
        int program=command.size()-1;
        command.set(program,"IFS= read -r starexec_swap_limit < /sys/fs/cgroup/memory.swap.max || exit 76\n"
                + "[[ \"$starexec_swap_limit\" == 0 ]] || exit 76\n"+command.get(program));
        return List.copyOf(command);
    }

    public void release(DockerClient client, int execution, String container) throws IOException, SQLException, InterruptedException {
        require(runtime!=null,"Private build runtime observation is unavailable");
        var before=client.inspectContainerCmd(container).exec();
        requireHeld(before,container,execution);
        String response=collect(client,container);
        var observation=runtime.verify(response);
        mounts.requireUnchangedInputs();
        var after=client.inspectContainerCmd(container).exec();
        requireHeld(after,container,execution);
        require(Objects.equals(before.getState().getStartedAt(),after.getState().getStartedAt()),
                "Held build changed during runtime observation");
        require(ExecutionStorageLeases.claimPodmanRelease(build,resource,execution,container,releaseRequest,observation),
                "No newly confirmed release decision is available");
        // A failed response remains unresolved. Neither this object nor a replay may send another release.
        client.killContainerCmd(container).withSignal(BuildExecutionHold.RELEASE_SIGNAL).exec();
    }

    private void requireMetadata(InspectContainerResponse inspection, String container, int execution) throws IOException {
        require(inspection!=null && container.equals(inspection.getId()) && image.equals(inspection.getImageId()),
                "Private build resource or image identity differs");
        var config=inspection.getConfig();
        require(config!=null && config.getLabels()!=null && LAYOUT.equals(config.getLabels().get(LAYOUT_LABEL))
                && Long.toString(build.artifactId()).equals(config.getLabels().get(PodmanBuildStopObserver.ARTIFACT_LABEL))
                && Objects.equals(execution,PodmanBuildStopObserver.buildExecution(inspection,container)),
                "Private build ownership differs");
        require("0:0".equals(config.getUser()) && Arrays.equals(config.getEntrypoint(),entrypoint().toArray(String[]::new))
                && Arrays.equals(config.getCmd(),command().toArray(String[]::new)),"Private build launcher differs");
        require(config.getHealthcheck()!=null && List.of("NONE").equals(config.getHealthcheck().getTest()),
                "Private build image healthcheck is not disabled");
        requireStopPolicy(inspection);
        var host=inspection.getHostConfig();
        require(host!=null && Boolean.FALSE.equals(host.getPrivileged()) && Boolean.TRUE.equals(host.getReadonlyRootfs())
                && host.getRestartPolicy()!=null && "no".equals(host.getRestartPolicy().getName()),
                "Private build isolation metadata differs");
    }

    private void requireHeld(InspectContainerResponse inspection, String container, int execution) throws IOException {
        requireMetadata(inspection,container,execution);
        var state=inspection.getState();
        require(state!=null && "running".equals(state.getStatus()) && Boolean.TRUE.equals(state.getRunning())
                && Boolean.FALSE.equals(state.getPaused()) && Boolean.FALSE.equals(state.getRestarting())
                && state.getPidLong()!=null && state.getPidLong()>0,"Private build is not held in a live process scope");
    }

    private static String collect(DockerClient client, String container) throws IOException, InterruptedException {
        String execution=client.execCreateCmd(container).withAttachStdout(true).withAttachStderr(true)
                .withAttachStdin(false).withUser("0:0").withCmd(observationCommand().toArray(String[]::new)).exec().getId();
        require(execution!=null && !execution.isBlank(),"Kernel observation execution identity is unavailable");
        var stdout=new LimitedOutput(1024*1024);
        var stderr=new LimitedOutput(64*1024);
        try (var callback=new ObservationCollector(stdout,stderr)) {
            require(client.execStartCmd(execution).withDetach(false).withTty(false).exec(callback)
                    .awaitCompletion(10,TimeUnit.SECONDS),"Kernel observation timed out");
        }
        var observed=client.inspectExecCmd(execution).exec();
        require(observed!=null && container.equals(observed.getContainerID()) && Boolean.FALSE.equals(observed.isRunning())
                && Long.valueOf(0).equals(observed.getExitCodeLong()),"Kernel observation did not complete successfully");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(stdout.toByteArray())).toString();
    }

    private static final class ObservationCollector extends ResultCallbackTemplate<ObservationCollector,Frame> {
        private final LimitedOutput stdout;
        private final LimitedOutput stderr;
        private ObservationCollector(LimitedOutput stdout, LimitedOutput stderr) {
            this.stdout=stdout;this.stderr=stderr;
        }
        @Override public void onNext(Frame frame) {
            if (frame==null || frame.getStreamType()==null || frame.getPayload()==null) {
                throw new IllegalStateException("Kernel observation frame is incomplete");
            }
            var destination=switch (frame.getStreamType()) {
                case STDOUT, RAW -> stdout;
                case STDERR -> stderr;
                default -> throw new IllegalStateException("Kernel observation stream type is unsupported");
            };
            destination.write(frame.getPayload(),0,frame.getPayload().length);
        }
    }

    private static final class LimitedOutput extends ByteArrayOutputStream {
        private final int maximum;
        private LimitedOutput(int maximum) { this.maximum=maximum; }
        @Override public synchronized void write(int value) {
            if (count>=maximum) throw new IllegalStateException("Kernel observation exceeds its output bound");
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length>maximum-count) throw new IllegalStateException("Kernel observation exceeds its output bound");
            super.write(bytes,offset,length);
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    /** Linux providers can normalize the requested SIGTERM name to signal number 15. */
    static void requireStopPolicy(Object signal) throws IOException {
        require("SIGTERM".equals(signal) || "TERM".equals(signal) || "15".equals(signal)
                || Integer.valueOf(15).equals(signal),"Private build stop policy is unavailable");
    }

    /** The SDK preserves unmapped nested fields in the original inspection envelope. */
    static void requireStopPolicy(InspectContainerResponse inspection) throws IOException {
        Object config=inspection.getRawValues().get("Config");
        requireStopPolicy(config instanceof Map<?,?> fields?fields.get("StopSignal"):null);
    }
}
