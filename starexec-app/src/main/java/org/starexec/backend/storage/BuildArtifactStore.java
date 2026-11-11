package org.starexec.backend.storage;

import org.starexec.config.EnvironmentConfig;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Controller-owned output storage, excluded from every declared runner-writable root. */
public final class BuildArtifactStore {
    public record ReadOnlyMount(Path source,String device,String kernelRoot,String node) { }
    private record Location(long mount, String device, Path physical) { }
    private final Path root;
    private final List<Path> excluded;
    private final Map<String,Object> identity;
    private final Location location;

    private BuildArtifactStore(Path root,List<Path> excluded,Map<String,Object> identity,Location location) {
        this.root=root;this.excluded=excluded;this.identity=Map.copyOf(identity);this.location=location;
    }

    public static BuildArtifactStore configured(List<Path> runnerRoots) throws IOException {
        String configured=EnvironmentConfig.getBuildArtifactDirectory();
        if (configured==null || configured.isBlank()) throw new IOException("STAREXEC_BUILD_ARTIFACT_DIR is required for managed publication");
        try { return inspect(Path.of(configured),runnerRoots); }
        catch (InvalidPathException invalid) { throw new IOException("Artifact root path is invalid",invalid); }
    }

    /** Observes existing storage; never creates it or changes its permissions. */
    public static BuildArtifactStore inspect(Path root,List<Path> runnerRoots) throws IOException {
        normalized(root);
        if (runnerRoots==null || runnerRoots.isEmpty()) throw new IOException("Artifact storage requires declared runner roots");
        List<Path> excluded=List.copyOf(runnerRoots);
        for (Path runner:excluded) {
            normalized(runner);
            if (overlap(root,runner)) throw new IOException("Artifact storage overlaps runner-writable storage");
        }
        long owner=((Number)Files.getAttribute(Path.of("/proc/self"),"unix:uid")).longValue();
        trustedAncestors(root,owner);
        try (DescriptorDirectory ignored=DescriptorDirectory.open(root)) {
            Map<String,Object> before=identity(root);
            if (((Number)before.get("uid")).longValue()!=owner
                    || (((Number)before.get("mode")).intValue()&07777)!=0700) {
                throw new IOException("Artifact storage requires application ownership and owner-only permissions");
            }
            var mounts=DedicatedStorageResource.mounts();
            Location physical=location(mounts,root);
            for (Path runner:excluded) {
                Path ancestor=runner;
                while (!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS)) ancestor=ancestor.getParent();
                try (DescriptorDirectory checked=DescriptorDirectory.open(ancestor)) {
                    Location other=location(mounts,runner);
                    if (physical.device().equals(other.device()) && overlap(physical.physical(),other.physical())) {
                        throw new IOException("Artifact storage aliases runner-writable storage");
                    }
                }
            }
            if (!before.equals(identity(root)) || !physical.equals(location(DedicatedStorageResource.mounts(),root))) {
                throw new IOException("Artifact storage changed during observation");
            }
            return new BuildArtifactStore(root,excluded,before,physical);
        }
    }

    public Path root() { return root; }

    public void requireUnchanged() throws IOException {
        var current=inspect(root,excluded);
        if (!identity.equals(current.identity) || !location.equals(current.location)) {
            throw new IOException("Artifact storage no longer matches its conserved identity");
        }
    }

    /** The caller must also verify the catalog's exact immutable manifest before consumption. */
    public Path solver(SolverBuildArtifacts.Artifact artifact) throws IOException {
        requireUnchanged();
        if (artifact==null || artifact.executionToken()==null || !"PUBLISHED".equals(artifact.state())) {
            throw new IOException("Solver has no published build identity");
        }
        Path output=root.resolve("build-"+artifact.id()+"-"+artifact.executionToken());
        Path solver=output.resolve("solver");
        if (!Objects.equals(output.toString(),artifact.storageRoot()) || !Objects.equals(solver.toString(),artifact.solverPath())) {
            throw new IOException("Published solver is outside its configured artifact store");
        }
        try (DescriptorDirectory ignored=DescriptorDirectory.open(solver)) { requireUnchanged(); }
        return solver;
    }

    /** Captures the exact source node and kernel mount root for the trusted consumer guard. */
    public ReadOnlyMount readOnlyMount(SolverBuildArtifacts.Artifact artifact) throws IOException {
        Path solver=solver(artifact);
        try (DescriptorDirectory ignored=DescriptorDirectory.open(solver)) {
            var before=identity(solver);
            Location source=location(DedicatedStorageResource.mounts(),solver);
            String node=Long.toUnsignedString(((Number)before.get("dev")).longValue())+":"
                    +Long.toUnsignedString(((Number)before.get("ino")).longValue())+":"
                    +Integer.toHexString(((Number)before.get("mode")).intValue());
            if (!before.equals(identity(solver)) || !source.equals(location(DedicatedStorageResource.mounts(),solver))) {
                throw new IOException("Published solver identity changed during mount capture");
            }
            requireUnchanged();
            return new ReadOnlyMount(solver,source.device(),kernelPath(source.physical()),node);
        }
    }

    public static String kernelPath(Path value) {
        return value.toString().replace("\\","\\134").replace(" ","\\040").replace("\t","\\011").replace("\n","\\012");
    }

    private static Location location(List<DedicatedStorageResource.Mount> mounts,Path path) throws IOException {
        var mount=BuildExecutionRuntime.sourceMount(mounts,path);
        return new Location(mount.id(),mount.device(),mount.root().resolve(mount.point().relativize(path)));
    }

    private static Map<String,Object> identity(Path path) throws IOException {
        return Files.readAttributes(path,"unix:dev,ino,mode,uid",LinkOption.NOFOLLOW_LINKS);
    }

    private static void trustedAncestors(Path path,long owner) throws IOException {
        for (Path parent=path.getParent();parent!=null;parent=parent.getParent()) {
            try (DescriptorDirectory ignored=DescriptorDirectory.open(parent)) {
                var attributes=identity(parent);
                long uid=((Number)attributes.get("uid")).longValue();
                int mode=((Number)attributes.get("mode")).intValue();
                boolean stickyRoot=uid==0 && (mode&01000)!=0;
                if (uid!=0 && uid!=owner || (mode&0022)!=0 && !stickyRoot) {
                    throw new IOException("Artifact storage has an untrusted writable ancestor");
                }
            }
        }
    }

    private static boolean overlap(Path a,Path b) { return a.startsWith(b)||b.startsWith(a); }
    private static void normalized(Path value) throws IOException {
        if (value==null || !value.isAbsolute() || value.getParent()==null || !value.equals(value.normalize())) {
            throw new IOException("Artifact storage paths must be normalized absolute non-root paths");
        }
    }
}
