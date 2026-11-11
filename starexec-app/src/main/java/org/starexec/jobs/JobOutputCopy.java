package org.starexec.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.starexec.data.database.JobExecutionProtocol;
import org.starexec.data.database.JobOutputReservations;
import org.starexec.data.database.JobStorageLeases;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** Bounded immutable retention; the caller verifies the exact stopped provider and protected input layout. */
public final class JobOutputCopy {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<PosixFilePermission> PRIVATE=PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FROZEN=PosixFilePermissions.fromString("r-xr-xr-x");
    private JobOutputCopy() { }

    public record Verified(String payload,String sha256,long byteSize,int entryCount,String directoryIdentity) { }
    private record Source(String payload,String sha256,long bytes,int entries,
            SolverArtifactTree.Snapshot monitor,SolverArtifactTree.Snapshot saved) { }

    /** The application must exclude this parent and every mutable ancestor from runner writes. */
    public static String parentIdentity(Path parent) throws IOException {
        return identity(parent,PRIVATE);
    }

    /** Claims once before create-new writes; partial or unbound copies remain registered for trusted cleanup. */
    public static Verified retain(Connection connection,JobOutputReservations.Reservation expected,Path monitor,Path saved)
            throws SQLException,IOException {
        if(!connection.getAutoCommit()) throw new SQLException("Ordinary output copy must not use a foreign transaction");
        require(expected!=null,"Ordinary output allocation is unavailable");
        var actual=JobOutputReservations.find(connection,expected.request().token())
                .orElseThrow(()->new IOException("Ordinary output allocation disappeared"));
        require(actual.request().equals(expected.request()) && actual.parent().equals(expected.parent())
                && actual.target().equals(expected.target()) && actual.limitBytes()==expected.limitBytes()
                && actual.entryLimit()==expected.entryLimit(),"Ordinary output allocation changed");
        var lease=JobStorageLeases.find(connection,actual.request().token()).orElseThrow(()->new IOException("Ordinary output staging is unavailable"));
        require(lease.useStartedAt()!=null && lease.releasedAt()==null
                && lease.staging().resolve("execution/monitor").equals(monitor)
                && lease.staging().resolve("execution/savedoutput").equals(saved),"Ordinary output source scopes differ");
        Source source=source(actual);
        require(parentIdentity(actual.parent()).equals(actual.parentIdentity()),"Ordinary output parent incarnation changed");
        requireSource(monitor,source.monitor());requireSource(saved,source.saved());
        if(actual.copyStartedAt()==null) {
            require(!Files.exists(actual.target(),LinkOption.NOFOLLOW_LINKS),"Unvalidated ordinary output destination already exists");
            if(JobOutputReservations.claimCopy(connection,actual)) {
                try(var parent=DescriptorDirectory.open(actual.parent())) {
                    require(parentIdentity(actual.parent()).equals(actual.parentIdentity()),"Ordinary output parent changed before creation");
                    Files.createDirectory(actual.target(),PosixFilePermissions.asFileAttribute(PRIVATE));parent.force();
                    String created=identity(actual.target(),PRIVATE);
                    require(JobOutputReservations.bindCopy(connection,actual,created),"Ordinary output directory binding was not acknowledged");
                    var first=SolverArtifactTree.copyOptionalOutput(monitor,actual.target().resolve("monitor"),actual.limitBytes(),actual.entryLimit()-2);
                    require(first.equals(source.monitor()),"Copied ordinary monitor differs from its reservation");
                    var second=SolverArtifactTree.copyOptionalOutput(saved,actual.target().resolve("savedoutput"),
                            actual.limitBytes()-first.byteSize(),actual.entryLimit()-2-first.entryCount());
                    require(second.equals(source.saved()),"Copied ordinary saved output differs from its reservation");
                    requireSource(monitor,source.monitor());requireSource(saved,source.saved());
                    require(identity(actual.target(),PRIVATE).equals(created),"Ordinary output root changed during copying");
                    try(var root=DescriptorDirectory.open(actual.target())) {
                        require(new HashSet<>(root.childNames(2)).equals(Set.of("monitor","savedoutput")),"Ordinary output copy has unexpected scopes");
                        SolverArtifactTree.verifyOptionalOutput(actual.target().resolve("monitor"),source.monitor());
                        SolverArtifactTree.verifyOptionalOutput(actual.target().resolve("savedoutput"),source.saved());
                        Files.setPosixFilePermissions(actual.target(),FROZEN);root.force();parent.force();
                    }
                }
            }
            actual=JobOutputReservations.find(connection,actual.request().token()).orElseThrow(()->new IOException("Ordinary output copy claim disappeared"));
        }
        return verify(actual);
    }

    /** Observes only a complete frozen copy at the conserved incarnation; it cannot authorize more writes. */
    public static Verified verify(JobOutputReservations.Reservation allocation) throws IOException {
        require(allocation!=null && allocation.copyStartedAt()!=null && allocation.directoryIdentity()!=null
                && allocation.releasedAt()==null,"Ordinary output copy ownership is incomplete");
        require(parentIdentity(allocation.parent()).equals(allocation.parentIdentity()),"Ordinary output parent incarnation changed");
        Source source=source(allocation);
        try(var root=DescriptorDirectory.open(allocation.target())) {
            require(identity(allocation.target(),FROZEN).equals(allocation.directoryIdentity()),"Ordinary output copy incarnation changed");
            require(new HashSet<>(root.childNames(2)).equals(Set.of("monitor","savedoutput")),"Ordinary output copy has unexpected scopes");
            SolverArtifactTree.verifyOptionalOutput(allocation.target().resolve("monitor"),source.monitor());
            SolverArtifactTree.verifyOptionalOutput(allocation.target().resolve("savedoutput"),source.saved());
            root.force();
            require(identity(allocation.target(),FROZEN).equals(allocation.directoryIdentity()),"Ordinary output root changed during verification");
        }
        return new Verified(source.payload(),source.sha256(),source.bytes(),source.entries(),allocation.directoryIdentity());
    }

    private static Source source(JobOutputReservations.Reservation allocation) throws IOException {
        try {
            JsonNode facts=JSON.readTree(allocation.request().payload()).path("facts");
            String payload=facts.path("source_manifest_payload").textValue(),sha=facts.path("source_manifest_sha256").textValue();
            require(payload!=null && JobExecutionProtocol.digest(payload).equals(sha),"Ordinary output source manifest changed");
            JsonNode manifest=JSON.readTree(payload);
            require(manifest.path("version").asInt()==1 && "ordinary-output".equals(manifest.path("kind").textValue())
                    && allocation.request().token().toString().equals(manifest.path("execution_token").textValue()),"Ordinary output source identity differs");
            var first=scope(manifest.path("monitor"));var second=scope(manifest.path("savedoutput"));
            long bytes=Math.addExact(first.byteSize(),second.byteSize());int entries=Math.addExact(2,Math.addExact(first.entryCount(),second.entryCount()));
            require(manifest.path("byte_size").isIntegralNumber() && manifest.path("byte_size").longValue()==bytes
                    && manifest.path("entry_count").isIntegralNumber() && manifest.path("entry_count").intValue()==entries
                    && bytes<=allocation.limitBytes() && entries<=allocation.entryLimit(),"Ordinary output aggregate bounds differ");
            return new Source(payload,sha,bytes,entries,first,second);
        } catch(IllegalArgumentException | ArithmeticException invalid) {throw new IOException("Invalid ordinary output source manifest",invalid);}
    }

    private static SolverArtifactTree.Snapshot scope(JsonNode value) throws IOException {
        String payload=value.path("manifest_payload").textValue(),sha=value.path("manifest_sha256").textValue();
        require(payload!=null && JobExecutionProtocol.digest(payload).equals(sha) && value.path("byte_size").isIntegralNumber()
                && value.path("byte_size").canConvertToLong() && value.path("byte_size").longValue()>=0
                && value.path("entry_count").isIntegralNumber() && value.path("entry_count").canConvertToInt()
                && value.path("entry_count").intValue()>=0,"Ordinary output scope bounds differ");
        var tree=JSON.readTree(payload);require(tree.path("version").asInt()==1 && tree.path("entries").isArray(),"Ordinary output scope manifest is invalid");
        for(var entry:tree.path("entries")) require(Set.of("file","directory").contains(entry.path("kind").asText()),"Ordinary output scope contains a link or special entry");
        return new SolverArtifactTree.Snapshot(payload,sha,value.path("byte_size").longValue(),value.path("entry_count").intValue());
    }

    private static void requireSource(Path path,SolverArtifactTree.Snapshot expected) throws IOException {
        require(SolverArtifactTree.inspectOutputDirectory(path,expected.byteSize(),expected.entryCount()).equals(expected),"Stopped ordinary output bytes changed");
    }

    private static String identity(Path path,Set<PosixFilePermission> permissions) throws IOException {
        require(path!=null && path.isAbsolute() && path.getParent()!=null && path.equals(path.normalize()),"Ordinary output path is not absolute and normalized");
        try(var directory=DescriptorDirectory.open(path)) {
            var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(attrs.isDirectory() && attrs.fileKey()!=null && Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(permissions),"Ordinary output directory identity or permissions differ");
            directory.force();return attrs.fileKey().toString();
        }
    }
    private static void require(boolean value,String message) throws IOException {if(!value) throw new IOException(message);}
}
