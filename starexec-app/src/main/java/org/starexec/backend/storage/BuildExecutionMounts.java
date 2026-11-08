package org.starexec.backend.storage;

import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.BindOptions;
import com.github.dockerjava.api.model.BindPropagation;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.RestartPolicy;
import org.starexec.backend.BuildExecutionContext;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.jobs.SolverArtifactTree;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Private build mappings; the created provider resource still requires independent mount observation. */
public final class BuildExecutionMounts {
    public static final String PRIVATE_VARIABLE = "STAREXEC_BUILD_PRIVATE_MOUNTS";
    public static final Path EXECUTION = Path.of("/starexec/execution");
    public static final Path SOURCE = Path.of("/starexec/inputs/source_src");
    public static final Path HELPER = Path.of("/starexec/inputs/starexec_build");
    public static final Path CONTROL = Path.of("/starexec/control");
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rwx------");
    private static final List<String> DIRECTORIES = List.of("work","tmp","home","monitor","run","var-tmp");

    public record Controls(Path script, Path functions, Path statusCodes) { }
    public record Binding(Path source, Path target, boolean readOnly) { }
    private record DirectoryIdentity(Object key, FileTime created) { }
    private record FileObservation(Object key, long size, FileTime modified,
                                   Set<PosixFilePermission> permissions, String sha256) { }

    private final BuildExecutionContext build;
    private final DedicatedStorageResource resource;
    private final ExecutionStorageLeases.DispatchInputs inputs;
    private final List<Binding> bindings;
    private final SolverArtifactTree.Snapshot source;
    private final Map<Path,FileObservation> files;
    private final Map<Path,DirectoryIdentity> directories;
    private final long maxInputBytes;
    private final int maxInputEntries;

    private BuildExecutionMounts(BuildExecutionContext build, DedicatedStorageResource resource,
                                  ExecutionStorageLeases.DispatchInputs inputs, List<Binding> bindings,
                                  SolverArtifactTree.Snapshot source, Map<Path,FileObservation> files,
                                  Map<Path,DirectoryIdentity> directories, long maxInputBytes, int maxInputEntries) {
        this.build=build; this.resource=resource; this.inputs=inputs; this.bindings=List.copyOf(bindings);
        this.source=source; this.files=Map.copyOf(files); this.directories=Map.copyOf(directories);
        this.maxInputBytes=maxInputBytes; this.maxInputEntries=maxInputEntries;
    }

    /** Host input and control ancestors must remain application-owned and protected from other writers. */
    public static BuildExecutionMounts prepare(Connection connection, BuildExecutionContext build,
                                                DedicatedStorageResource resource, Controls controls,
                                                long maxInputBytes, int maxInputEntries) throws SQLException, IOException {
        if (maxInputBytes < 1 || maxInputEntries < 1) throw new IllegalArgumentException("Input observation requires positive limits");
        Objects.requireNonNull(controls,"controls");
        var inputs=ExecutionStorageLeases.findDispatchInputs(connection,build,resource)
                .orElseThrow(()->new SQLException("Current leased build inputs are unavailable"));
        if (!inputs.source().equals(build.source()) || inputs.helper().ownerId()!=inputs.ownerId()) {
            throw new IOException("Build input ownership differs from the conserved context");
        }
        resource.requireUnchanged();
        if (!resource.root().equals(build.staging().getParent())
                || !build.staging().getFileName().toString().equals("build-"+build.token())) {
            throw new IOException("Build staging is not the registered resource child");
        }
        requireOnlyStaging(resource.root(),build.staging());
        Path helper=Path.of(inputs.helper().path());
        List<Binding> bindings=new ArrayList<>(List.of(new Binding(build.source(),SOURCE,true),
                new Binding(helper,HELPER,true),new Binding(controls.script(),CONTROL.resolve("jobscript"),true),
                new Binding(controls.functions(),CONTROL.resolve("functions.bash"),true),
                new Binding(controls.statusCodes(),CONTROL.resolve("status_codes.bash"),true),
                new Binding(build.staging(),EXECUTION,false),
                new Binding(build.staging().resolve("tmp"),Path.of("/tmp"),false),
                new Binding(build.staging().resolve("run"),Path.of("/run"),false),
                new Binding(build.staging().resolve("var-tmp"),Path.of("/var/tmp"),false)));
        for (Binding binding:bindings) {
            normalized(binding.source());
            if (binding.readOnly() && overlap(binding.source(),resource.root())) {
                throw new IOException("Read-only input has a writable resource alias");
            }
        }
        for (Path control:List.of(controls.script(),controls.functions(),controls.statusCodes())) {
            if (control.startsWith(build.source()) || control.equals(helper)) {
                throw new IOException("Build controls cannot come from untrusted input paths");
            }
        }
        requireNoNestedMounts(build.source());
        var source=SolverArtifactTree.inspectInputDirectory(build.source(),maxInputBytes,maxInputEntries);
        long remaining=maxInputBytes-source.byteSize();
        int entries=source.entryCount();
        Map<Path,FileObservation> files=new LinkedHashMap<>();
        for (Path input:List.of(helper,controls.script(),controls.functions(),controls.statusCodes())) {
            if (++entries>maxInputEntries) throw new IOException("Build inputs exceed their entry limit");
            FileObservation observed=observeFile(input,remaining);
            remaining-=observed.size();
            if (files.putIfAbsent(input,observed)!=null) throw new IOException("Build input roles share a control file");
        }
        if (files.get(helper).size()!=inputs.helper().byteSize()) throw new IOException("Helper bytes differ from conserved usage");
        Map<Path,DirectoryIdentity> directories=new LinkedHashMap<>();
        try (DescriptorDirectory staging=DescriptorDirectory.open(build.staging())) {
            directories.put(build.staging(),privateDirectory(build.staging()));
            try (var contents=Files.newDirectoryStream(build.staging())) {
                for (Path entry:contents) {
                    if (!DIRECTORIES.contains(entry.getFileName().toString())) {
                        throw new IOException("Build staging contains unregistered contents before launch");
                    }
                    privateDirectory(entry); requireEmpty(entry);
                }
            }
            for (String name:DIRECTORIES) {
                Path directory=build.staging().resolve(name);
                try { Files.createDirectory(directory,PosixFilePermissions.asFileAttribute(PRIVATE)); }
                catch (FileAlreadyExistsException existing) { }
                directories.put(directory,privateDirectory(directory)); requireEmpty(directory);
            }
            staging.force();
        }
        var result=new BuildExecutionMounts(build,resource,inputs,bindings,source,files,directories,maxInputBytes,maxInputEntries);
        result.requireCurrent(connection);
        return result;
    }

    /** Recheck immediately before creation/admission; this snapshot never authorizes launch by itself. */
    public void requireCurrent(Connection connection) throws SQLException, IOException {
        if (!ExecutionStorageLeases.findDispatchInputs(connection,build,resource).filter(inputs::equals).isPresent()) {
            throw new SQLException("Build input authorization changed before provider admission");
        }
        requireUnchangedInputs();
        for (var entry:directories.entrySet()) {
            if (!entry.getKey().equals(build.staging())) requireEmpty(entry.getKey());
        }
    }

    /** Physical input checks during an independently verified hold; this does not authorize release. */
    public void requireUnchangedInputs() throws IOException {
        resource.requireUnchanged(); requireOnlyStaging(resource.root(),build.staging()); requireNoNestedMounts(build.source());
        if (!source.equals(SolverArtifactTree.inspectInputDirectory(build.source(),maxInputBytes,maxInputEntries))) {
            throw new IOException("Build source bytes changed before provider admission");
        }
        for (var entry:files.entrySet()) {
            if (!entry.getValue().equals(observeFile(entry.getKey(),entry.getValue().size()))) {
                throw new IOException("Build input or control identity changed before provider admission");
            }
        }
        try (var contents=Files.newDirectoryStream(build.staging())) {
            for (Path entry:contents) {
                if (!DIRECTORIES.contains(entry.getFileName().toString())) {
                    throw new IOException("Build staging gained unregistered contents before launch");
                }
            }
        }
        for (var entry:directories.entrySet()) {
            if (!entry.getValue().equals(privateDirectory(entry.getKey()))) {
                throw new IOException("Private build directory identity changed before provider admission");
            }
        }
        resource.requireUnchanged();
    }

    public BuildExecutionContext build() { return build; }
    public List<Binding> bindings() { return bindings; }
    public Path script() { return CONTROL.resolve("jobscript"); }
    public Path workingDirectory() { return EXECUTION.resolve("work"); }
    public Path outputDirectory() { return EXECUTION.resolve("monitor"); }
    public Path hostOutputDirectory() { return hostOutputDirectory(build); }

    /** Resolve only after independently matching the provider's private-layout profile and execution identity. */
    public static Path hostOutputDirectory(BuildExecutionContext build) { return build.staging().resolve("monitor"); }

    public Map<String,String> environment() {
        return Map.ofEntries(Map.entry(PRIVATE_VARIABLE,"1"),
                Map.entry(BuildExecutionContext.OUTPUT_VARIABLE,Base64.getEncoder().encodeToString(EXECUTION.toString().getBytes(StandardCharsets.UTF_8))),
                Map.entry("STAREXEC_BUILD_SOURCE_DIR",SOURCE.toString()),Map.entry("STAREXEC_BUILD_HELPER_FILE",HELPER.toString()),
                Map.entry("STAREXEC_BUILD_CONTROL_DIR",CONTROL.toString()),Map.entry("STAREXEC_BUILD_WORK_DIR",workingDirectory().toString()),
                Map.entry("STAREXEC_OUTPUT_DIR",outputDirectory().toString()),Map.entry("HOME",EXECUTION.resolve("home").toString()),
                Map.entry("TMPDIR","/tmp"));
    }

    /** Uses identical source path names in the application and provider namespaces. */
    public HostConfig toPodmanHostConfig(HostConfig limits) {
        Map<Path,Path> sources=new LinkedHashMap<>();
        for (Binding binding:bindings) sources.put(binding.source(),binding.source());
        return toPodmanHostConfig(limits,sources);
    }

    /**
     * Declares every provider-visible source without changing application or runner locators.
     * Capture expected physical identity from the original bindings and verify the effective
     * mounts on the held resource before release; these path declarations grant no launch permission.
     */
    public HostConfig toPodmanHostConfig(HostConfig limits, Map<Path,Path> providerSources) {
        Objects.requireNonNull(limits,"limits");
        String network=limits.getNetworkMode();
        if (network!=null && (network.equals("host") || network.startsWith("container:") || network.startsWith("ns:"))) {
            throw new IllegalArgumentException("Private builds cannot share another execution's network namespace");
        }
        if (limits.getMemory()==null || limits.getMemory()<=0) {
            throw new IllegalArgumentException("Private builds require an explicit positive memory limit");
        }
        List<Binding> rendered=providerBindings(providerSources);
        return new HostConfig()
                .withMemory(limits.getMemory()).withMemorySwap(limits.getMemorySwap())
                .withMemoryReservation(limits.getMemoryReservation()).withMemorySwappiness(limits.getMemorySwappiness())
                .withKernelMemory(limits.getKernelMemory()).withShmSize(limits.getShmSize())
                .withCpusetCpus(limits.getCpusetCpus()).withCpusetMems(limits.getCpusetMems())
                .withCpuPeriod(limits.getCpuPeriod()).withCpuQuota(limits.getCpuQuota()).withCpuShares(limits.getCpuShares())
                .withNanoCPUs(limits.getNanoCPUs()).withCpuCount(limits.getCpuCount()).withCpuPercent(limits.getCpuPercent())
                .withCpuRealtimePeriod(limits.getCpuRealtimePeriod()).withCpuRealtimeRuntime(limits.getCpuRealtimeRuntime())
                .withPidsLimit(limits.getPidsLimit())
                .withNetworkMode(network).withReadonlyRootfs(true).withPrivileged(false)
                .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges"))
                .withRestartPolicy(RestartPolicy.noRestart()).withAutoRemove(false)
                .withMounts(rendered.stream().map(binding->new Mount().withType(MountType.BIND)
                        .withSource(binding.source().toString()).withTarget(binding.target().toString())
                        .withReadOnly(binding.readOnly()).withBindOptions(new BindOptions()
                                .withPropagation(BindPropagation.R_PRIVATE))).toList());
    }

    private List<Binding> providerBindings(Map<Path,Path> providerSources) {
        final Map<Path,Path> declared;
        try { declared=Map.copyOf(Objects.requireNonNull(providerSources,"providerSources")); }
        catch (NullPointerException invalid) {
            throw new IllegalArgumentException("Provider paths require a complete non-null declaration",invalid);
        }
        Set<Path> required=new HashSet<>();
        for (Binding binding:bindings) required.add(binding.source());
        if (required.size()!=bindings.size() || !declared.keySet().equals(required)) {
            throw new IllegalArgumentException("Provider paths must name exactly the conserved application sources");
        }
        Set<Path> distinct=new HashSet<>();List<Binding> rendered=new ArrayList<>();
        for (Binding binding:bindings) {
            Path path=declared.get(binding.source());
            if (!path.isAbsolute() || path.getParent()==null || !path.equals(path.normalize())
                    || !path.getFileSystem().equals(binding.source().getFileSystem()) || !distinct.add(path)) {
                throw new IllegalArgumentException("Provider sources must be distinct normalized absolute non-root paths");
            }
            if (!binding.readOnly() && (!binding.source().startsWith(build.staging())
                    || !declared.get(build.staging()).resolve(build.staging().relativize(binding.source())).equals(path))) {
                throw new IllegalArgumentException("Provider scratch paths must preserve the leased staging tree");
            }
            rendered.add(new Binding(path,binding.target(),binding.readOnly()));
        }
        for (Binding input:rendered) {
            if (!input.readOnly()) continue;
            for (Binding writable:rendered) {
                if (!writable.readOnly() && overlap(input.source(),writable.source())) {
                    throw new IllegalArgumentException("Provider read-only inputs overlap private writable storage");
                }
            }
        }
        return List.copyOf(rendered);
    }

    private static boolean overlap(Path left, Path right) { return left.startsWith(right)||right.startsWith(left); }
    private static void normalized(Path path) throws IOException {
        if (path==null || !path.isAbsolute() || path.getParent()==null || !path.equals(path.normalize())) {
            throw new IOException("Build mounts require absolute normalized non-root paths");
        }
    }

    private static void requireNoNestedMounts(Path source) throws IOException {
        for (var mount:DedicatedStorageResource.mounts()) {
            if (!mount.point().equals(source) && mount.point().startsWith(source)) {
                throw new IOException("Read-only input contains an unsupported nested mount");
            }
        }
    }

    private static DirectoryIdentity privateDirectory(Path path) throws IOException {
        var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.fileKey()==null
                || attrs.creationTime().toMillis()<=0 || !Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(PRIVATE)) {
            throw new IOException("Private build storage requires an identifiable owner-only directory");
        }
        return new DirectoryIdentity(attrs.fileKey(),attrs.creationTime());
    }

    private static void requireEmpty(Path path) throws IOException {
        try (var contents=Files.newDirectoryStream(path)) {
            if (contents.iterator().hasNext()) throw new IOException("Private build directory has unexpected prelaunch contents");
        }
    }

    private static void requireOnlyStaging(Path root, Path staging) throws IOException {
        try (var contents=Files.newDirectoryStream(root)) {
            for (Path entry:contents) {
                if (!entry.equals(staging)) throw new IOException("Execution resource contains unregistered sibling data");
            }
        }
    }

    private static FileObservation observeFile(Path path, long maxBytes) throws IOException {
        normalized(path);
        try (DescriptorDirectory parent=DescriptorDirectory.open(path.getParent())) {
            var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.isSymbolicLink() || before.fileKey()==null
                    || ((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1
                    || before.size()>maxBytes) throw new IOException("Build input is not a bounded unaliased regular file");
            Set<PosixFilePermission> modes=Set.copyOf(Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS));
            MessageDigest digest;
            try { digest=MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException unavailable) { throw new IOException("SHA-256 is unavailable",unavailable); }
            long size=0;
            try (FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer=ByteBuffer.allocate(65536);
                for (int count;(count=channel.read(buffer))!=-1;) {
                    if (count>maxBytes-size) throw new IOException("Build input exceeds its byte limit");
                    size+=count; buffer.flip(); digest.update(buffer); buffer.clear();
                }
            }
            var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || size!=before.size() || size!=after.size()
                    || !Objects.equals(before.fileKey(),after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !modes.equals(Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Build input changed during its observation");
            }
            return new FileObservation(before.fileKey(),size,before.lastModifiedTime(),modes,HexFormat.of().formatHex(digest.digest()));
        }
    }
}
