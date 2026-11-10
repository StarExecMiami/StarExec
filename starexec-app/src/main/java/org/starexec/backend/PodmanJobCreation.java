package org.starexec.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.HostConfig;
import org.starexec.backend.storage.BuildExecutionHold;
import org.starexec.backend.storage.BuildExecutionMounts;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.JobExecutionProtocol;
import org.starexec.data.database.JobStorageLeases;
import org.starexec.jobs.JobExecutionPreparation;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Private ordinary creation policy; it never sends, starts or releases a provider resource. */
public final class PodmanJobCreation {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String SCRIPT="starexec.job.control.script",FUNCTIONS="starexec.job.control.functions";
    private static final String STATUS="starexec.job.control.status",DEPENDENCIES="starexec.job.control.dependencies";
    private static final String BYTES="starexec.job.prepare.bytes",ENTRIES="starexec.job.prepare.entries";
    private static final int HOLD_SECONDS=60;
    private final JobStorageLeases.Lease lease;
    private final DedicatedStorageResource resource;
    private final JobExecutionPreparation prepared;
    private final JobExecutionPreparation.Controls controls;
    private final long maxBytes;
    private final int maxEntries;
    private final String image;
    private final boolean recovered;
    private volatile JobExecutionProtocol.Creation planned;

    private PodmanJobCreation(JobStorageLeases.Lease lease,DedicatedStorageResource resource,
            JobExecutionPreparation prepared,JobExecutionPreparation.Controls controls,long maxBytes,int maxEntries,
            String image,boolean recovered) {
        this.lease=lease;this.resource=resource;this.prepared=prepared;this.controls=controls;
        this.maxBytes=maxBytes;this.maxEntries=maxEntries;this.image=image;this.recovered=recovered;
    }

    public static PodmanJobCreation prepare(DockerClient client,Connection connection,JobStorageLeases.Lease expected,
            JobExecutionPreparation.Controls controls,long maxBytes,int maxEntries,String image,String socket)
            throws IOException,SQLException {
        PodmanPrivateBuild.requireLocalRootlessProvider(client,socket);
        String pinned=image(client,image);
        require(expected!=null && expected.equals(JobStorageLeases.find(connection,expected.token()).orElse(null))
                && expected.releasedAt()==null,"Ordinary creation lease is unavailable");
        var resource=resource(expected);
        var prepared=JobExecutionPreparation.prepare(connection,expected,resource,controls,maxBytes,maxEntries);
        var started=JobStorageLeases.find(connection,expected.token()).orElseThrow(()->new IOException("Ordinary used lease is unavailable"));
        require(started.useStartedAt()!=null && started.releasedAt()==null,"Ordinary preparation ownership changed");
        return new PodmanJobCreation(started,resource,prepared,controls,maxBytes,maxEntries,pinned,false);
    }

    /** Restores the exact pre-payload request and inputs; historical readback cannot consume another create. */
    public static PodmanJobCreation restore(DockerClient client,Connection connection,JobExecutionProtocol.Creation saved,
            String socket) throws IOException,SQLException {
        require(saved!=null && saved.equals(JobExecutionProtocol.findCreation(connection,saved.token()).orElse(null)),
                "Ordinary creation history differs");
        PodmanPrivateBuild.requireLocalRootlessProvider(client,socket);
        require(saved.image().equals(image(client,saved.image())),"Ordinary image recovery differs");
        var lease=JobStorageLeases.find(connection,saved.token()).orElseThrow(()->new IOException("Ordinary ownership history is unavailable"));
        require(lease.pairId()==saved.pairId() && lease.attemptNo()==saved.attemptNo() && lease.ownerId()==saved.ownerId()
                && lease.resourceKey().equals(saved.resourceKey()) && lease.resourceIdentity().equals(saved.resourceIdentity())
                && lease.useStartedAt()!=null && lease.releasedAt()==null,"Ordinary recovered ownership differs");
        var labels=labels(saved);
        final JobExecutionPreparation.Controls controls;
        final long maxBytes;
        final int maxEntries;
        try {
            String dependency=Objects.requireNonNull(labels.get(DEPENDENCIES));
            controls=new JobExecutionPreparation.Controls(Path.of(labels.get(SCRIPT)),Path.of(labels.get(FUNCTIONS)),
                    Path.of(labels.get(STATUS)),dependency.isEmpty()?null:Path.of(dependency));
            maxBytes=positive(labels.get(BYTES));maxEntries=Math.toIntExact(positive(labels.get(ENTRIES)));
        } catch (IllegalArgumentException | NullPointerException | ArithmeticException invalid) {
            throw new IOException("Ordinary conserved controls or budgets are invalid",invalid);
        }
        var resource=resource(lease);
        var prepared=JobExecutionPreparation.restoreBound(connection,lease,resource,controls,maxBytes,maxEntries,saved.preparationFingerprint());
        var restored=new PodmanJobCreation(lease,resource,prepared,controls,maxBytes,maxEntries,saved.image(),true);
        restored.planned=saved;
        return restored;
    }

    /** All provider source paths are declarations; physical mount identity must be checked before payload release. */
    public synchronized JobExecutionProtocol.Creation plan(Connection connection,int execution,HostConfig limits,
            Map<Path,Path> providerSources,Map<String,String> extraLabels,JobExecutionProtocol.Transport transport)
            throws IOException,SQLException {
        require(!recovered && planned==null,"Ordinary creation policy cannot be replaced");
        prepared.requireCurrent(connection);
        require(limits!=null && limits.getMemory()!=null && limits.getMemory()>0
                && Objects.equals(limits.getMemory(),limits.getMemorySwap())
                && limits.getCpusetCpus()!=null && !limits.getCpusetCpus().isBlank()
                && (quota(limits.getCpuQuota())>0 && period(limits.getCpuPeriod())>0
                    || quota(limits.getCpuQuota())==-1 && period(limits.getCpuPeriod())>=0),
                "Ordinary memory, swap or CPU policy is unavailable");
        final HostConfig host;
        try { host=BuildExecutionMounts.privatePodmanHostConfig(limits,providerBindings(providerSources)); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw new IOException("Ordinary private mappings are invalid",invalid); }
        Map<String,String> labels=new TreeMap<>(Map.ofEntries(
                Map.entry("starexec.managed","true"),Map.entry("starexec.label.version","2"),Map.entry("starexec.kind","job-pair"),
                Map.entry("starexec.job.layout","private-v1"),Map.entry("starexec.job.token",lease.token().toString()),
                Map.entry("starexec.storage.resource",lease.resourceKey()),Map.entry("starexec.job.inputs",prepared.fingerprint()),
                Map.entry("starexec.pair.id",Integer.toString(lease.pairId())),Map.entry("starexec.attempt",Integer.toString(lease.attemptNo())),
                Map.entry("starexec.exec.id",Integer.toString(execution)),Map.entry(SCRIPT,controls.script().toString()),
                Map.entry(FUNCTIONS,controls.functions().toString()),Map.entry(STATUS,controls.statusCodes().toString()),
                Map.entry(DEPENDENCIES,controls.dependencies()==null?"":controls.dependencies().toString()),
                Map.entry(BYTES,Long.toString(maxBytes)),Map.entry(ENTRIES,Integer.toString(maxEntries))));
        require(extraLabels!=null,"Ordinary supplementary labels are unavailable");
        for (var e:extraLabels.entrySet()) {
            require(e.getKey()!=null && e.getValue()!=null && (!labels.containsKey(e.getKey()) || labels.get(e.getKey()).equals(e.getValue())),
                    "Ordinary supplementary labels replace conserved ownership");
            labels.put(e.getKey(),e.getValue());
        }
        Map<String,String> environment=new TreeMap<>(prepared.environment());
        environment.put("STAREXEC_BUILD_PRIVATE_MOUNTS","");environment.put("STAREXEC_FORCE_SANDBOX","false");
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("Image",image);body.put("HostConfig",host);body.put("Labels",labels);
        body.put("Entrypoint",BuildExecutionHold.entrypoint(HOLD_SECONDS));body.put("Cmd",BuildExecutionHold.command(prepared.script()));
        body.put("WorkingDir",prepared.workingDirectory().toString());body.put("User","0:0");body.put("StopSignal","SIGTERM");
        body.put("Healthcheck",Map.of("Test",List.of("NONE")));
        body.put("Env",environment.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList());
        String payload=JSON.writeValueAsString(body);
        JobExecutionProtocol.Creation candidate;
        try {
            candidate=new JobExecutionProtocol.Creation(lease.token(),lease.pairId(),lease.attemptNo(),lease.ownerId(),
                    lease.resourceKey(),lease.resourceIdentity(),execution,"starexec-pair-"+lease.pairId()+"-attempt-"+lease.attemptNo(),
                    image,prepared.fingerprint(),transport,payload,JobExecutionProtocol.digest(payload));
        } catch (IllegalArgumentException invalid) { throw new IOException("Ordinary creation identity is invalid",invalid); }
        prepared.requireCurrent(connection);
        planned=candidate;
        return candidate;
    }

    /** A first acknowledged TRUE permits one later transport send of creation().payload(); this method sends nothing. */
    public boolean claimCreation(Connection connection) throws SQLException {
        var request=planned;
        return !recovered && request!=null && JobExecutionProtocol.consumeCreation(connection,lease,resource,prepared,
                request.executionId(),image,request.transport(),request.payload());
    }

    public JobExecutionProtocol.Creation creation() {
        if (planned==null) throw new IllegalStateException("Ordinary creation has not been planned");
        return planned;
    }

    /** Only the first acknowledged claim permits a later trusted hold start; readback never does. */
    boolean claimStart(Connection connection,String container,com.github.dockerjava.api.command.InspectContainerResponse inspection)
            throws IOException,SQLException {
        var request=planned;
        if(recovered || request==null || !request.equals(JobExecutionProtocol.findCreation(connection,lease.token()).orElse(null))) return false;
        var receipt=PodmanJobObservation.startClaim(request,container,inspection,java.util.UUID.randomUUID());
        return JobExecutionProtocol.recordReceipt(connection,lease,resource,receipt);
    }

    public JobExecutionPreparation preparation(Connection connection) throws IOException,SQLException {
        if (JobExecutionProtocol.findCreation(connection,lease.token()).isEmpty()) {
            prepared.requireCurrent(connection);return prepared;
        }
        require(planned!=null && planned.equals(JobExecutionProtocol.findCreation(connection,lease.token()).orElse(null)),
                "Ordinary preparation request differs");
        return JobExecutionPreparation.restoreBound(connection,lease,resource,controls,maxBytes,maxEntries,planned.preparationFingerprint());
    }

    private List<BuildExecutionMounts.Binding> providerBindings(Map<Path,Path> declared) throws IOException {
        require(declared!=null && declared.keySet().equals(prepared.bindings().stream().map(JobExecutionPreparation.Binding::source)
                .collect(java.util.stream.Collectors.toSet())),"Ordinary provider sources are incomplete");
        declared=Map.copyOf(declared);
        Path application=lease.staging().resolve("execution"),provider=declared.get(application);
        require(provider!=null,"Ordinary private provider root is unavailable");
        List<BuildExecutionMounts.Binding> result=new ArrayList<>();var distinct=new HashSet<Path>();
        for (var binding:prepared.bindings()) {
            Path mapped=declared.get(binding.source());
            require(mapped!=null && mapped.isAbsolute() && mapped.getParent()!=null && mapped.equals(mapped.normalize())
                    && mapped.getFileSystem().equals(binding.source().getFileSystem()) && distinct.add(mapped),
                    "Ordinary provider source is invalid or aliased");
            require(binding.readOnly() || binding.source().startsWith(application)
                    && provider.resolve(application.relativize(binding.source())).equals(mapped),
                    "Ordinary writable provider sources do not preserve their lease");
            result.add(new BuildExecutionMounts.Binding(mapped,binding.target(),binding.readOnly()));
        }
        for (var input:result) if (input.readOnly()) for (var writable:result) if (!writable.readOnly()) {
            require(!overlap(input.source(),writable.source()),"Ordinary provider input overlaps writable storage");
            require(!overlap(input.target(),writable.target()),"Ordinary input has a writable target ancestor or alias");
        }
        return List.copyOf(result);
    }

    private static String image(DockerClient client,String name) throws IOException {
        var value=client.inspectImageCmd(name).exec();
        require(value!=null && value.getId()!=null && value.getId().matches("sha256:[0-9a-f]{64}")
                && value.getConfig()!=null && (value.getConfig().getVolumes()==null || value.getConfig().getVolumes().isEmpty()),
                "Ordinary private image identity or volume policy is unavailable");
        return value.getId();
    }
    private static DedicatedStorageResource resource(JobStorageLeases.Lease lease) throws IOException {
        return new ExecutionStorageLeases.RegisteredResource(lease.resourceKey(),lease.root(),lease.resourceIdentity(),
                lease.limitBytes(),lease.inodeLimit()).inspect(List.of(Path.of(R.STAREXEC_DATA_DIR),
                        Path.of(EnvironmentConfig.getJobOutputDirectory()),Path.of(EnvironmentConfig.getJobLogDirectory())));
    }
    private static Map<String,String> labels(JobExecutionProtocol.Creation saved) throws IOException {
        var fields=JSON.readTree(saved.payload()).path("Labels");Map<String,String> labels=new LinkedHashMap<>();
        var entries=fields.fields();
        while (entries.hasNext()) { var e=entries.next();require(e.getValue().isTextual(),"Ordinary recovery label is invalid");labels.put(e.getKey(),e.getValue().textValue()); }
        return Map.copyOf(labels);
    }
    private static long positive(String text) {
        if (text==null || !text.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid ordinary preparation bound");
        return Long.parseLong(text);
    }
    private static boolean overlap(Path left,Path right) { return left.startsWith(right)||right.startsWith(left); }
    private static long quota(Long value) { return value==null || value==0?-1:value; }
    private static long period(Long value) { return value==null?0:value; }
    private static void require(boolean ok,String message) throws IOException { if(!ok) throw new IOException(message); }
}
