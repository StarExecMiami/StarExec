package org.starexec.jobs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.data.database.JobExecutionInputs;
import org.starexec.data.database.JobStorageLeases;
import org.starexec.data.database.PublishedSolverInputs;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Pre-engine ordinary input preparation. Source ancestors and mount configuration
 * must remain application-controlled; modes alone do not isolate another same-UID process.
 */
public final class JobExecutionPreparation {
    public static final String PRIVATE_VARIABLE="STAREXEC_JOB_PRIVATE_MOUNTS";
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_RECEIPT=16*1024*1024;
    private static final String RECEIPT="inputs.json";
    private static final Set<PosixFilePermission> PRIVATE=PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> READ_FILE=PosixFilePermissions.fromString("r--r--r--");
    private static final Set<PosixFilePermission> READ_EXEC=PosixFilePermissions.fromString("r-xr-xr-x");
    private static final List<String> DIRECTORIES=List.of("inputs","control","execution","execution/work",
            "execution/tmp","execution/home","execution/monitor","execution/run","execution/var-tmp","execution/savedoutput");
    private static final Path EXECUTION=Path.of("/starexec/execution");

    public record Controls(Path script,Path functions,Path statusCodes,Path dependencies) { }
    public record Binding(Path source,Path target,boolean readOnly) { }
    private record Source(String role,Path source,String destination,boolean directory) { }
    private record Witness(boolean directory,String sha256,long bytes,int entries,String identity) { }
    private record Copy(String role,String source,String destination,Witness original,Witness materialized) { }
    private record Mapping(String source,String target,boolean readOnly) { }
    private record Receipt(int version,String token,int pair,int attempt,int owner,String started,
                           String resource,String identity,String metadata,String metadataSha,
                           long maxBytes,int maxEntries,List<Copy> copies,Map<String,String> directories,List<Mapping> bindings) { }

    private final JobStorageLeases.Lease lease;
    private final DedicatedStorageResource resource;
    private final Controls controls;
    private final long maxBytes;
    private final int maxEntries;
    private final Receipt receipt;
    private final String text;
    private final List<Binding> bindings;
    private final String boundFingerprint;

    private JobExecutionPreparation(JobStorageLeases.Lease lease,DedicatedStorageResource resource,Controls controls,
                                    long maxBytes,int maxEntries,Receipt receipt,String text) {
        this(lease,resource,controls,maxBytes,maxEntries,receipt,text,null);
    }

    private JobExecutionPreparation(JobStorageLeases.Lease lease,DedicatedStorageResource resource,Controls controls,
                                    long maxBytes,int maxEntries,Receipt receipt,String text,String boundFingerprint) {
        this.lease=lease;this.resource=resource;this.controls=controls;
        this.maxBytes=maxBytes;this.maxEntries=maxEntries;this.receipt=receipt;this.text=text;
        this.boundFingerprint=boundFingerprint;
        this.bindings=receipt.bindings().stream().map(b->new Binding(lease.staging().resolve(b.source()),Path.of(b.target()),b.readOnly())).toList();
    }

    /** Commits first use before any write. A partial or ambiguous preparation keeps its claim and charge. */
    public static JobExecutionPreparation prepare(Connection connection,JobStorageLeases.Lease expected,
            DedicatedStorageResource resource,Controls controls,long maxBytes,int maxEntries) throws SQLException,IOException {
        limits(expected,resource,maxBytes,maxEntries);
        var captured=JobExecutionInputs.capture(connection,expected,resource)
                .orElseThrow(()->new SQLException("Current unused ordinary input reservation is unavailable"));
        List<Source> sources=sources(captured,controls);
        published(connection,expected);
        List<Witness> before=new ArrayList<>();
        long bytes=0;int entries=DIRECTORIES.size()+2;
        for (Source source:sources) {
            var observed=witness(source.source(),source.directory(),maxBytes-bytes,maxEntries-entries,false);
            before.add(observed);bytes=Math.addExact(bytes,observed.bytes());entries=Math.addExact(entries,observed.entries());
        }
        require(entries<=maxEntries,"Input entries exceed their preparation budget");
        try (DescriptorDirectory root=DescriptorDirectory.open(resource.root())) {
            requireEmpty(resource.root());
            if (!JobStorageLeases.beginUse(connection,expected.token(),resource)) throw new SQLException("Ordinary storage use refused");
            var started=JobStorageLeases.find(connection,expected.token()).orElseThrow();
            var current=JobExecutionInputs.capturePrepared(connection,started,resource)
                    .orElseThrow(()->new SQLException("Ordinary inputs changed before preparation"));
            require(sameMetadata(captured,current),"Authorized input metadata changed before preparation");
            Path staging=started.staging();
            Files.createDirectory(staging,PosixFilePermissions.asFileAttribute(PRIVATE));
            try (DescriptorDirectory directory=DescriptorDirectory.open(staging)) {
                for (String name:DIRECTORIES) Files.createDirectory(staging.resolve(name),PosixFilePermissions.asFileAttribute(PRIVATE));
                bytes=0;entries=DIRECTORIES.size()+2;
                for (int i=0;i<sources.size();i++) {
                    Source source=sources.get(i);Path destination=staging.resolve(source.destination());
                    if (source.directory()) SolverArtifactTree.copyReserved(source.source(),destination,maxBytes-bytes,maxEntries-entries-1);
                    else copyFile(source.source(),destination,maxBytes-bytes);
                    Witness unchanged=witness(source.source(),source.directory(),maxBytes-bytes,maxEntries-entries,false);
                    require(before.get(i).equals(unchanged),"Input identity or bytes changed while copying");
                    bytes=Math.addExact(bytes,unchanged.bytes());entries=Math.addExact(entries,unchanged.entries());
                }
                Files.setPosixFilePermissions(staging.resolve("inputs"),READ_EXEC);
                Files.setPosixFilePermissions(staging.resolve("control"),READ_EXEC);
                Receipt receipt=observe(connection,started,resource,controls,maxBytes,maxEntries,false);
                require(captured.payload().equals(receipt.metadata()) && captured.sha256().equals(receipt.metadataSha())
                        && before.size()==receipt.copies().size(),"Authorized input metadata changed while preparing");
                for (int i=0;i<before.size();i++)
                    require(before.get(i).equals(receipt.copies().get(i).original()),"Input identity changed before receipt conservation");
                String text=JSON.toJson(receipt);
                require(text.getBytes(StandardCharsets.UTF_8).length<=MAX_RECEIPT,"Input receipt exceeds its read limit");
                require(receiptBytes(receipt)<=maxBytes-text.getBytes(StandardCharsets.UTF_8).length,"Input receipt exceeds its preparation budget");
                writeReceipt(staging.resolve(RECEIPT),text);
                for (String name:DIRECTORIES) try (DescriptorDirectory child=DescriptorDirectory.open(staging.resolve(name))) { child.force(); }
                directory.force();root.force();
                var prepared=new JobExecutionPreparation(started,resource,controls,maxBytes,maxEntries,receipt,text);
                prepared.requireCurrent(connection);
                return prepared;
            }
        }
    }

    /** A caller must conserve the fingerprint durably before engine creation; recovery never recreates missing state. */
    public static JobExecutionPreparation restore(Connection connection,JobStorageLeases.Lease expected,
            DedicatedStorageResource resource,Controls controls,long maxBytes,int maxEntries,String fingerprint) throws SQLException,IOException {
        return restore(connection,expected,resource,controls,maxBytes,maxEntries,fingerprint,false);
    }

    /** Restores pre-payload inputs through conserved creation; it never creates, starts or releases an engine. */
    public static JobExecutionPreparation restoreBound(Connection connection,JobStorageLeases.Lease expected,
            DedicatedStorageResource resource,Controls controls,long maxBytes,int maxEntries,String fingerprint) throws SQLException,IOException {
        return restore(connection,expected,resource,controls,maxBytes,maxEntries,fingerprint,true);
    }

    private static JobExecutionPreparation restore(Connection connection,JobStorageLeases.Lease expected,
            DedicatedStorageResource resource,Controls controls,long maxBytes,int maxEntries,String fingerprint,boolean bound) throws SQLException,IOException {
        limits(expected,resource,maxBytes,maxEntries);
        require(fingerprint!=null && fingerprint.matches("[0-9a-f]{64}"),"Original preparation fingerprint is unavailable");
        if ((bound ? JobExecutionInputs.captureBound(connection,expected,resource,fingerprint)
                : JobExecutionInputs.capturePrepared(connection,expected,resource)).isEmpty())
            throw new SQLException("Current prepared ordinary inputs are unavailable");
        String text=readReceipt(expected.staging());
        require(digest(text).equals(fingerprint),"Conserved input receipt differs");
        Receipt saved;
        try { saved=JSON.fromJson(text,Receipt.class); }
        catch (RuntimeException invalid) { throw new IOException("Invalid ordinary input receipt",invalid); }
        Receipt actual=observe(connection,expected,resource,controls,maxBytes,maxEntries,true,bound?fingerprint:null);
        require(actual.equals(saved),"Recovered input preparation differs from its original observation");
        require(receiptBytes(actual)<=maxBytes-text.getBytes(StandardCharsets.UTF_8).length,"Recovered receipt exceeds its budget");
        var prepared=new JobExecutionPreparation(expected,resource,controls,maxBytes,maxEntries,actual,text,bound?fingerprint:null);
        prepared.requireCurrent(connection);
        return prepared;
    }

    /** Pre-engine check only; prepared bytes never establish engine admission or positive termination. */
    public void requireCurrent(Connection connection) throws SQLException,IOException {
        require(observe(connection,lease,resource,controls,maxBytes,maxEntries,true,boundFingerprint).equals(receipt),"Prepared input identity changed");
        require(text.equals(readReceipt(lease.staging())),"Prepared receipt changed");
    }

    public List<Binding> bindings() { return bindings; }
    public String fingerprint() { return digest(text); }
    public Path script() { return controls.script(); }
    public Path workingDirectory() { return EXECUTION.resolve("work"); }
    public Path outputDirectory() { return EXECUTION.resolve("monitor"); }
    public Path hostOutputDirectory() { return lease.staging().resolve("execution/monitor"); }

    /** Declares the reserved ordinary namespace; these values do not establish provider admission. */
    public Map<String,String> environment() {
        return Map.ofEntries(Map.entry(PRIVATE_VARIABLE,"1"),Map.entry("CONTAINER_MODE","true"),
                Map.entry("STAREXEC_OUTPUT_DIR",outputDirectory().toString()),
                Map.entry("HOME",EXECUTION.resolve("home").toString()),Map.entry("TMPDIR","/tmp"),
                Map.entry("STAREXEC_JOB_DEPENDENCIES",controls.dependencies()==null?"":controls.dependencies().toString()));
    }

    private static Receipt observe(Connection connection,JobStorageLeases.Lease lease,DedicatedStorageResource resource,
            Controls controls,long maxBytes,int maxEntries,boolean hasReceipt) throws SQLException,IOException {
        return observe(connection,lease,resource,controls,maxBytes,maxEntries,hasReceipt,null);
    }

    private static Receipt observe(Connection connection,JobStorageLeases.Lease lease,DedicatedStorageResource resource,
            Controls controls,long maxBytes,int maxEntries,boolean hasReceipt,String boundFingerprint) throws SQLException,IOException {
        var captured=(boundFingerprint==null ? JobExecutionInputs.capturePrepared(connection,lease,resource)
                : JobExecutionInputs.captureBound(connection,lease,resource,boundFingerprint))
                .orElseThrow(()->new SQLException("Current prepared ordinary inputs are unavailable"));
        published(connection,lease);
        List<Source> sources=sources(captured,controls);
        Path staging=lease.staging();
        resource.requireUnchanged();
        requireNames(resource.root(),List.of(staging.getFileName().toString()));
        requireNames(staging,hasReceipt?List.of("inputs","control","execution",RECEIPT):List.of("inputs","control","execution"));
        Map<String,String> directories=new TreeMap<>();
        directories.put(".",directoryIdentity(staging,PRIVATE));
        for (String name:DIRECTORIES) {
            directories.put(name,directoryIdentity(staging.resolve(name),name.equals("inputs") || name.equals("control")?READ_EXEC:PRIVATE));
            if (name.startsWith("execution/")) requireEmpty(staging.resolve(name));
        }
        requireNames(staging.resolve("execution"),List.of("work","tmp","home","monitor","run","var-tmp","savedoutput"));
        requireNames(staging.resolve("inputs"),sources.stream().filter(s->s.destination().startsWith("inputs/"))
                .map(s->Path.of(s.destination()).getFileName().toString()).toList());
        requireNames(staging.resolve("control"),sources.stream().filter(s->s.destination().startsWith("control/"))
                .map(s->Path.of(s.destination()).getFileName().toString()).toList());
        List<Copy> copies=new ArrayList<>();List<Mapping> mappings=new ArrayList<>();
        long bytes=0;int entries=DIRECTORIES.size()+2;
        for (Source source:sources) {
            Witness original=witness(source.source(),source.directory(),maxBytes-bytes,maxEntries-entries,false);
            Witness copy=witness(staging.resolve(source.destination()),source.directory(),maxBytes-bytes,maxEntries-entries,true);
            require(sameContent(original,copy),"Prepared input differs from authorized source bytes");
            copies.add(new Copy(source.role(),source.source().toString(),source.destination(),original,copy));
            mappings.add(new Mapping(source.destination(),source.source().toString(),true));
            bytes=Math.addExact(bytes,copy.bytes());entries=Math.addExact(entries,copy.entries());
        }
        require(entries<=maxEntries,"Prepared inputs exceed their entry budget");
        mappings.add(new Mapping("execution",EXECUTION.toString(),false));
        mappings.add(new Mapping("execution/tmp","/tmp",false));
        mappings.add(new Mapping("execution/run","/run",false));
        mappings.add(new Mapping("execution/var-tmp","/var/tmp",false));
        resource.requireUnchanged();
        return new Receipt(1,lease.token().toString(),lease.pairId(),lease.attemptNo(),lease.ownerId(),
                lease.useStartedAt().toString(),lease.resourceKey(),lease.resourceIdentity(),captured.payload(),captured.sha256(),
                maxBytes,maxEntries,List.copyOf(copies),Map.copyOf(directories),List.copyOf(mappings));
    }

    private static List<Source> sources(JobExecutionInputs.Snapshot snapshot,Controls controls) throws IOException {
        Objects.requireNonNull(controls,"controls");
        Map<Path,Source> inputs=new LinkedHashMap<>();
        for (var input:snapshot.files()) {
            normalized(input.source());
            require(!reservedTarget(input.source()),"Input overlaps the reserved execution namespace");
            boolean directory=input.kind()!=JobExecutionInputs.Kind.BENCHMARK;
            Source previous=inputs.get(input.source());
            if (previous!=null) require(previous.directory()==directory,"Input roles disagree on source type");
            else inputs.put(input.source(),new Source("input",input.source(),"inputs/"+inputs.size(),directory));
        }
        List<Source> result=new ArrayList<>(inputs.values());
        Map<String,Path> controlPaths=new LinkedHashMap<>();
        controlPaths.put("script",controls.script());controlPaths.put("functions",controls.functions());controlPaths.put("status",controls.statusCodes());
        if (controls.dependencies()!=null) controlPaths.put("dependencies",controls.dependencies());
        Set<Path> distinct=new java.util.HashSet<>();
        for (var entry:controlPaths.entrySet()) {
            Path path=entry.getValue();normalized(path);
            require(!reservedTarget(path) && !overlap(path,snapshot.lease().root()),"Control path overlaps reserved writable storage");
            require(distinct.add(path),"Control roles share one file");
            for (Source input:inputs.values()) require(!overlap(path,input.source()),"Trusted controls cannot come from an input tree");
            result.add(new Source(entry.getKey(),path,"control/"+entry.getKey(),false));
        }
        return List.copyOf(result);
    }

    private static void published(Connection connection,JobStorageLeases.Lease lease) throws SQLException,IOException {
        for (var input:PublishedSolverInputs.forPair(connection,lease.pairId(),lease.attemptNo()))
            SolverBuildPublication.verifyPublishedInput(input.artifact(),input.manifest());
    }
    private static boolean sameMetadata(JobExecutionInputs.Snapshot first,JobExecutionInputs.Snapshot second) {
        return first.files().equals(second.files()) && first.payload().equals(second.payload()) && first.sha256().equals(second.sha256());
    }
    private static boolean sameContent(Witness first,Witness second) {
        return first.directory()==second.directory() && first.bytes()==second.bytes() && first.entries()==second.entries() && first.sha256().equals(second.sha256());
    }
    private static long receiptBytes(Receipt receipt) {
        long bytes=0;for (Copy copy:receipt.copies()) bytes=Math.addExact(bytes,copy.materialized().bytes());return bytes;
    }
    private static void limits(JobStorageLeases.Lease lease,DedicatedStorageResource resource,long bytes,int entries) throws IOException {
        require(lease!=null && resource!=null && bytes>0 && entries>DIRECTORIES.size()+2
                && bytes<=lease.limitBytes() && entries<=lease.inodeLimit(),"Preparation limits must fit the conserved resource");
    }
    private static boolean reservedTarget(Path path) {
        return overlap(path,EXECUTION) || overlap(path,Path.of("/proc")) || overlap(path,Path.of("/sys")) || overlap(path,Path.of("/dev"));
    }
    private static boolean overlap(Path first,Path second) { return first.startsWith(second) || second.startsWith(first); }
    private static void normalized(Path path) throws IOException {
        require(path!=null && path.isAbsolute() && path.getParent()!=null && path.equals(path.normalize()),"Input paths must be normalized absolute non-root paths");
    }
    private static void require(boolean value,String cause) throws IOException { if (!value) throw new IOException(cause); }
    private static void requireEmpty(Path directory) throws IOException { requireNames(directory,List.of()); }
    private static void requireNames(Path directory,List<String> expected) throws IOException {
        try (DescriptorDirectory handle=DescriptorDirectory.open(directory)) {
            var names=handle.childNames(Math.max(1,expected.size()+1));
            require(names.size()==expected.size() && new java.util.HashSet<>(names).equals(new java.util.HashSet<>(expected)),"Preparation has unregistered contents");
        }
    }
    private static String directoryIdentity(Path path,Set<PosixFilePermission> mode) throws IOException {
        try (DescriptorDirectory ignored=DescriptorDirectory.open(path)) {
            var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(attrs.fileKey()!=null && Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(mode),"Private preparation directory identity or mode differs");
            return attrs.fileKey()+":"+attrs.creationTime()+":"+mode.stream().map(Enum::name).sorted().toList();
        }
    }

    private static Witness witness(Path path,boolean directory,long maxBytes,int maxEntries,boolean frozen) throws IOException {
        require(maxBytes>=0 && maxEntries>0,"Input exceeds its aggregate preparation budget");
        normalized(path);requireNoNestedMounts(path,directory);
        try (DescriptorDirectory ignored=DescriptorDirectory.open(directory?path:path.getParent())) {
            var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(attrs.fileKey()!=null && (directory?attrs.isDirectory():attrs.isRegularFile()),"Input is not a regular declared asset");
            if (directory) {
                require(maxEntries>1,"Input directory exceeds its entry budget");
                var tree=SolverArtifactTree.inspectInputDirectory(path,maxBytes,maxEntries-1);
                if (frozen) SolverArtifactTree.verify(path,tree);
                Map<String,Object> identity=new TreeMap<>();identity.put(".",identity(path));
                for (var entry:JsonParser.parseString(tree.payload()).getAsJsonObject().getAsJsonArray("entries")) {
                    String name=entry.getAsJsonObject().get("path").getAsString();
                    identity.put(name,identity(path.resolve(name)));
                }
                return new Witness(true,tree.sha256(),tree.byteSize(),Math.addExact(tree.entryCount(),1),digest(JSON.toJson(identity)));
            }
            require(((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()==1,"Input is hard linked");
            var permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
            if (frozen) require(permissions.equals(executable(permissions)?READ_EXEC:READ_FILE),"Copied file permissions differ");
            String before=identity(path);MessageDigest digest=hash();
            long bytes=readFile(path,null,maxBytes,digest);
            require(before.equals(identity(path)),"Input changed during observation");
            return new Witness(false,HexFormat.of().formatHex(digest.digest()),bytes,1,before);
        }
    }
    private static String identity(Path path) throws IOException {
        var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(attrs.fileKey()!=null,"Input Unix identity unavailable");
        return attrs.fileKey()+":"+attrs.creationTime()+":"+attrs.lastModifiedTime()+":"+attrs.size()+":"
                +Files.getAttribute(path,"unix:mode",LinkOption.NOFOLLOW_LINKS)+":"+Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS);
    }
    private static void copyFile(Path source,Path destination,long maxBytes) throws IOException {
        try (DescriptorDirectory input=DescriptorDirectory.open(source.getParent());
             DescriptorDirectory parent=DescriptorDirectory.open(destination.getParent())) {
            var attrs=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(attrs.isRegularFile() && attrs.fileKey()!=null
                    && ((Number)Files.getAttribute(source,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()==1,"Control or benchmark is not a unique regular file");
            String before=identity(source);MessageDigest digest=hash();
            readFile(source,destination,maxBytes,digest);
            require(before.equals(identity(source)),"Source identity changed while copying");
            Files.setPosixFilePermissions(destination,executable(Files.getPosixFilePermissions(source,LinkOption.NOFOLLOW_LINKS))?READ_EXEC:READ_FILE);
            try (var file=FileChannel.open(destination,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) { file.force(true); }
            parent.force();
        }
    }
    private static long readFile(Path source,Path destination,long limit,MessageDigest digest) throws IOException {
        try (var input=FileChannel.open(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
             var output=destination==null?null:FileChannel.open(destination,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes=ByteBuffer.allocate(65536);long count=0;
            for (int read;(read=input.read(bytes))!=-1;) {
                require(read>0 && read<=limit-count,"Input exceeds its byte limit or made no progress");
                count+=read;bytes.flip();digest.update(bytes.asReadOnlyBuffer());
                if (output!=null) while (bytes.hasRemaining()) require(output.write(bytes)>0,"Copy made no progress");
                bytes.clear();
            }
            if (output!=null) output.force(true);
            return count;
        }
    }
    private static boolean executable(Set<PosixFilePermission> mode) {
        return mode.contains(PosixFilePermission.OWNER_EXECUTE) || mode.contains(PosixFilePermission.GROUP_EXECUTE) || mode.contains(PosixFilePermission.OTHERS_EXECUTE);
    }
    private static void writeReceipt(Path path,String text) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        try (var file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) require(file.write(buffer)>0,"Receipt write made no progress");
            file.force(true);
        }
        Files.setPosixFilePermissions(path,READ_FILE);
        try (var file=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) { file.force(true); }
    }
    private static String readReceipt(Path staging) throws IOException {
        try (DescriptorDirectory directory=DescriptorDirectory.open(staging)) {
            Path path=staging.resolve(RECEIPT);
            require(Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(READ_FILE)
                    && ((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()==1,
                    "Prepared receipt is not a unique read-only file");
            return directory.readPublishedText(RECEIPT,MAX_RECEIPT);
        }
    }
    private static void requireNoNestedMounts(Path root,boolean directory) throws IOException {
        byte[] bytes;
        try (var input=Files.newInputStream(Path.of("/proc/self/mountinfo"))) { bytes=input.readNBytes(1024*1024+1); }
        require(bytes.length<=1024*1024,"Mount observation exceeds its input limit");
        var escaped=Pattern.compile("\\\\([0-7]{3})");
        for (String line:new String(bytes,StandardCharsets.UTF_8).split("\n")) {
            String[] parts=line.split(" ");require(parts.length>=6,"Invalid mount observation");
            var matcher=escaped.matcher(parts[4]);StringBuilder decoded=new StringBuilder();
            while (matcher.find()) matcher.appendReplacement(decoded,java.util.regex.Matcher.quoteReplacement(Character.toString((char)Integer.parseInt(matcher.group(1),8))));
            matcher.appendTail(decoded);Path point=Path.of(decoded.toString());
            require(!(point.startsWith(root) && (!directory || !point.equals(root))),"Input contains an unsupported nested mount");
        }
    }
    private static MessageDigest hash() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
    }
    private static String digest(String text) { return HexFormat.of().formatHex(hash().digest(text.getBytes(StandardCharsets.UTF_8))); }
}

