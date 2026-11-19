package org.starexec.backend.storage;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.starexec.jobs.JobExecutionPreparation;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates trusted pre-execution kernel observations; transport ownership and admission remain separate. */
public final class BuildExecutionRuntime {
    private static final int MAX_RESPONSE=2*1024*1024;
    private static final Set<String> NAMESPACES=Set.of("pid","ipc","net","mnt","user","cgroup");
    private static final Set<String> FIELDS=Set.of("mounts","uidmap","gidmap","status","pidstat","memory",
            "devices","cgroup","selfgroup","pids","cpu","cpuset","nodes","cmdline","release",
            "ns-pid","ns-ipc","ns-net","ns-mnt","ns-user","ns-cgroup","selfmnt");
    private static final Set<Path> METADATA=Set.of(Path.of("/etc/hosts"),Path.of("/etc/hostname"),Path.of("/etc/resolv.conf"));
    private static final Set<Path> MASKS=Set.of(Path.of("/proc/kcore"),Path.of("/proc/keys"),Path.of("/proc/latency_stats"),
            Path.of("/proc/timer_list"),Path.of("/proc/interrupts"));
    private static final Map<Path,String> DEVICES=Map.of(Path.of("/dev/null"),"1:3",Path.of("/dev/zero"),"1:5",
            Path.of("/dev/full"),"1:7",Path.of("/dev/random"),"1:8",Path.of("/dev/urandom"),"1:9",Path.of("/dev/tty"),"5:0");
    private static final String COLLECTOR="""
            set -euo pipefail
            export PATH=/usr/bin:/bin
            devices() {
              while read -r mount_id parent_id device mount_root point remaining; do
                case "$point" in
                  /dev/null|/dev/zero|/dev/full|/dev/random|/dev/urandom|/dev/tty|/proc/kcore|/proc/keys|/proc/latency_stats|/proc/timer_list|/proc/interrupts)
                    stat --printf='%n|%f|%t|%T\\n' -- "$point" ;;
                esac
              done < /proc/1/mountinfo
            }
            nodes() {
              for point in "$@"; do
                printf 'b64:%s|' "$(printf '%s' "$point" | base64 -w0)"
                stat --printf='%d|%i|%f|%s|%h\\n' -- "$point"
              done
              while read -r mount_id parent_id device mount_root point remaining; do
                case "$point" in
                  /etc/hosts|/etc/hostname|/etc/resolv.conf)
                    stat --printf='%n|%d|%i|%f|%s|%h\\n' -- "$point" ;;
                esac
              done < /proc/1/mountinfo
            }
            mounts=$(base64 -w0 < /proc/1/mountinfo)
            uidmap=$(base64 -w0 < /proc/1/uid_map)
            gidmap=$(base64 -w0 < /proc/1/gid_map)
            status=$(base64 -w0 < /proc/1/status)
            pidstat=$(base64 -w0 < /proc/1/stat)
            memory=$(base64 -w0 < /sys/fs/cgroup/memory.max)
            pids=$(base64 -w0 < /sys/fs/cgroup/pids.max)
            cpu=$(base64 -w0 < /sys/fs/cgroup/cpu.max)
            cpuset=$(base64 -w0 < /sys/fs/cgroup/cpuset.cpus.effective)
            device_nodes=$(devices | base64 -w0)
            mount_nodes=$(nodes "$@" | base64 -w0)
            cmdline=$(base64 -w0 < /proc/1/cmdline)
            cgroup=$(base64 -w0 < /proc/1/cgroup)
            selfgroup=$(base64 -w0 < /proc/self/cgroup)
            release=$(kill -l USR2 | base64 -w0)
            printf '{"mounts":"%s","uidmap":"%s","gidmap":"%s","status":"%s","pidstat":"%s","memory":"%s","pids":"%s","cpu":"%s","cpuset":"%s","devices":"%s","nodes":"%s","cmdline":"%s","cgroup":"%s","selfgroup":"%s","release":"%s"' "$mounts" "$uidmap" "$gidmap" "$status" "$pidstat" "$memory" "$pids" "$cpu" "$cpuset" "$device_nodes" "$mount_nodes" "$cmdline" "$cgroup" "$selfgroup" "$release"
            for namespace in pid ipc user net mnt cgroup; do
              identity=$(readlink "/proc/1/ns/$namespace")
              printf ',"ns-%s":"%s"' "$namespace" "$identity"
            done
            selfmnt=$(readlink /proc/self/ns/mnt)
            printf ',"selfmnt":"%s"}\\n' "$selfmnt"
            """;

    /** Collect only through the independently verified exact held resource; do not execute against a host process. */
    public static List<String> observationCommand() {
        List<Path> targets=new ArrayList<>();
        targets.add(BuildExecutionMounts.SOURCE);targets.add(BuildExecutionMounts.HELPER);
        for (String name:List.of("jobscript","functions.bash","status_codes.bash")) targets.add(BuildExecutionMounts.CONTROL.resolve(name));
        targets.addAll(List.of(BuildExecutionMounts.EXECUTION,Path.of("/tmp"),Path.of("/run"),Path.of("/var/tmp")));
        return observationCommand(targets);
    }

    /** Positional arguments preserve the exact captured targets, including shell and record delimiters. */
    public List<String> observationCommandForBindings() {
        return observationCommand(expected.stream().map(Expected::target).toList());
    }

    private static List<String> observationCommand(List<Path> targets) {
        List<String> command=new ArrayList<>(List.of("/bin/bash","--noprofile","--norc","-p","-c",COLLECTOR,"--"));
        command.addAll(targets.stream().map(Path::toString).sorted().toList());
        return List.copyOf(command);
    }

    public record Identity(long insideUid,long insideGid,long outsideUid,long outsideGid) {
        public Identity {
            if (insideUid<0 || insideGid<0 || outsideUid<1 || outsideGid<1
                    || insideUid>=0xffffffffL || insideGid>=0xffffffffL || outsideUid>=0xffffffffL || outsideGid>=0xffffffffL) {
                throw new IllegalArgumentException("Build identity requires known unprivileged outer IDs");
            }
        }
    }
    public record Limits(long memoryBytes,long pids,String cpus,long cpuQuota,long cpuPeriod) {
        public Limits {
            boolean cpuPolicy=cpuQuota>0 && cpuPeriod>0 || cpuQuota==-1 && cpuPeriod>=0;
            if (memoryBytes<1 || pids<1 || !cpuPolicy || cpus==null || cpus.isBlank()) {
                throw new IllegalArgumentException("Build runtime requires positive limits and an explicit CPU policy");
            }
        }
        /** A quota of -1 preserves cpuset-only scheduling; period zero means no period was requested. */
        public boolean cpuSetOnly() { return cpuQuota==-1; }
    }
    public record Observation(long startTicks,long memoryBytes,long pids,String cpus,Map<String,String> namespaces) {
        public Observation { namespaces=Map.copyOf(namespaces); }
    }
    record Expected(Path target,String device,Path root,String type,long nodeDevice,long inode,int mode,boolean readOnly) { }
    private record Node(long device,long inode,int mode,long size,long links) { }
    private record RuntimeMount(DedicatedStorageResource.Mount mount,List<String> propagation) { }

    private final List<Expected> expected;
    private final Identity identity;
    private final Limits limits;
    private final Map<String,String> forbiddenNamespaces;
    private final byte[] heldCommand;

    private BuildExecutionRuntime(List<Expected> expected,Identity identity,Limits limits,
                                  Map<String,String> forbiddenNamespaces,byte[] heldCommand) {
        this.expected=List.copyOf(expected);this.identity=identity;this.limits=limits;
        this.forbiddenNamespaces=Map.copyOf(forbiddenNamespaces);this.heldCommand=heldCommand.clone();
    }

    /** Expected inputs must already be authorized and protected; capture itself does not permit a launch. */
    public static BuildExecutionRuntime capture(List<BuildExecutionMounts.Binding> bindings,Identity identity,
                                                 Limits limits,Map<String,String> forbiddenNamespaces,int holdSeconds)
            throws IOException {
        Map<Path,Boolean> required=new HashMap<>();
        required.put(BuildExecutionMounts.SOURCE,true);required.put(BuildExecutionMounts.HELPER,true);
        for (String file:List.of("jobscript","functions.bash","status_codes.bash")) required.put(BuildExecutionMounts.CONTROL.resolve(file),true);
        for (Path path:List.of(BuildExecutionMounts.EXECUTION,Path.of("/tmp"),Path.of("/run"),Path.of("/var/tmp"))) required.put(path,false);
        return capture(bindings,identity,limits,forbiddenNamespaces,holdSeconds,required,BuildExecutionMounts.CONTROL.resolve("jobscript"));
    }

    /** Uses only the current authorized preparation; the returned policy is not runtime attestation. */
    public static BuildExecutionRuntime captureJob(Connection connection,JobExecutionPreparation preparation,
            Identity identity,Limits limits,Map<String,String> forbiddenNamespaces,int holdSeconds) throws SQLException,IOException {
        if (preparation==null) throw new IOException("Ordinary runtime preparation is unavailable");
        preparation.requireCurrent(connection);
        Map<Path,Boolean> required=new HashMap<>();
        for (Path path:List.of(BuildExecutionMounts.EXECUTION,Path.of("/tmp"),Path.of("/run"),Path.of("/var/tmp"))) required.put(path,false);
        List<BuildExecutionMounts.Binding> bindings=new ArrayList<>();
        for (var binding:preparation.bindings()) {
            if (binding.readOnly()) {
                if (required.putIfAbsent(binding.target(),true)!=null) throw new IOException("Ordinary input overlaps a reserved mount target");
            } else if (!Boolean.FALSE.equals(required.get(binding.target()))) throw new IOException("Unexpected ordinary writable mount");
            bindings.add(new BuildExecutionMounts.Binding(binding.source(),binding.target(),binding.readOnly()));
        }
        if (!Boolean.TRUE.equals(required.get(preparation.script()))) throw new IOException("Trusted ordinary script mount is unavailable");
        var captured=capture(bindings,identity,limits,forbiddenNamespaces,holdSeconds,required,preparation.script());
        preparation.requireCurrent(connection);
        return captured;
    }

    private static BuildExecutionRuntime capture(List<BuildExecutionMounts.Binding> bindings,Identity identity,
            Limits limits,Map<String,String> forbiddenNamespaces,int holdSeconds,Map<Path,Boolean> required,Path script) throws IOException {
        if (identity==null || limits==null || forbiddenNamespaces==null
                || !forbiddenNamespaces.keySet().containsAll(Set.of("pid","ipc","net","mnt","cgroup"))
                || !NAMESPACES.containsAll(forbiddenNamespaces.keySet())) throw new IOException("Incomplete runtime identity policy");
        cpuSet(limits.cpus());
        for (var entry:forbiddenNamespaces.entrySet()) namespace(entry.getKey(),entry.getValue());
        if (bindings==null || bindings.size()!=required.size()) throw new IOException("Incomplete private runtime mount policy");
        List<Expected> expected=new ArrayList<>();Set<Path> seen=new HashSet<>();
        for (var binding:bindings) {
            Path target=binding.target();
            if (target==null || !target.isAbsolute() || target.getParent()==null || !target.equals(target.normalize()))
                throw new IOException("Invalid runtime target path");
            if (!seen.add(binding.target()) || !required.containsKey(binding.target())
                    || required.get(binding.target())!=binding.readOnly()) throw new IOException("Unexpected private runtime mount role");
            Path source=binding.source();
            if (source==null || !source.isAbsolute() || source.getParent()==null || !source.equals(source.normalize())) throw new IOException("Invalid runtime source path");
            try (DescriptorDirectory ignored=DescriptorDirectory.open(source.getParent())) {
                var before=Files.readAttributes(source,"unix:dev,ino,mode,nlink",LinkOption.NOFOLLOW_LINKS);
                int mode=((Number)before.get("mode")).intValue()&0170000;
                if (mode!=0100000 && mode!=0040000 || mode==0100000 && ((Number)before.get("nlink")).longValue()!=1) {
                    throw new IOException("Runtime source is not an unaliased file or directory");
                }
                var mounts=DedicatedStorageResource.mounts();
                var mount=sourceMount(mounts,source);
                Path root=mount.root().resolve(mount.point().relativize(source));
                if (!root.isAbsolute() || !root.equals(root.normalize())) throw new IOException("Source mount has no absolute kernel root");
                var after=Files.readAttributes(source,"unix:dev,ino,mode,nlink",LinkOption.NOFOLLOW_LINKS);
                if (!before.equals(after) || !mount.equals(sourceMount(DedicatedStorageResource.mounts(),source))) {
                    throw new IOException("Runtime source changed during capture");
                }
                expected.add(new Expected(binding.target(),mount.device(),root,mount.type(),
                        ((Number)before.get("dev")).longValue(),((Number)before.get("ino")).longValue(),mode,binding.readOnly()));
            }
        }
        List<String> command=new ArrayList<>(BuildExecutionHold.entrypoint(holdSeconds));
        command.addAll(BuildExecutionHold.command(script));
        return new BuildExecutionRuntime(expected,identity,limits,forbiddenNamespaces,
                (String.join("\0",command)+"\0").getBytes(StandardCharsets.UTF_8));
    }

    static DedicatedStorageResource.Mount sourceMount(List<DedicatedStorageResource.Mount> mounts,Path source) throws IOException {
        List<DedicatedStorageResource.Mount> ancestors=mounts.stream().filter(m->source.startsWith(m.point())).toList();
        int depth=ancestors.stream().mapToInt(m->m.point().getNameCount()).max().orElse(-1);
        var deepest=ancestors.stream().filter(m->m.point().getNameCount()==depth).toList();
        if (deepest.size()!=1) throw new IOException("Ambiguous source mount topology");
        return deepest.get(0);
    }

    List<Expected> expectedMounts() { return expected; }

    /** Call only on bytes collected through the verified exact held resource, before enabling untrusted code. */
    public Observation verify(String response) throws IOException {
        Map<String,String> raw=read(response);
        if (!Arrays.equals(heldCommand,decoded(raw.get("cmdline")))) throw new IOException("Resource is not running the trusted hold command");
        Map<String,String> status=properties(text(raw,"status"));
        require("1".equals(status.get("Pid")) && "1".equals(status.get("Tgid")) && "1".equals(status.get("Threads"))
                && "0".equals(status.get("TracerPid")),"Unexpected held process scope");
        String state=required(status,"State").split("\\s+")[0];
        require(state.equals("S") || state.equals("R"),"Held process is not runnable");
        for (String cap:List.of("CapEff","CapPrm","CapInh","CapBnd","CapAmb")) {
            require(required(status,cap).matches("0{1,16}"),"Held process retains capabilities");
        }
        require("1".equals(status.get("NoNewPrivs")) && "2".equals(status.get("Seccomp")),"Runtime privilege controls differ");
        long releaseNumber=positive(text(raw,"release").trim());
        require(releaseNumber<=64,"Invalid release signal number");
        long release=1L<<(releaseNumber-1);
        require((hex(required(status,"SigCgt"))&release)!=0 && (hex(required(status,"SigBlk"))&release)==0
                && (hex(required(status,"SigIgn"))&release)==0,"Release handler is missing or blocked");
        ids(required(status,"Uid"),identity.insideUid());ids(required(status,"Gid"),identity.insideGid());
        require(mapped(identity.insideUid(),text(raw,"uidmap"))==identity.outsideUid()
                && mapped(identity.insideGid(),text(raw,"gidmap"))==identity.outsideGid(),"Runtime user mapping differs");
        String stat=text(raw,"pidstat");int end=stat.lastIndexOf(')');int open=stat.indexOf('(');
        require(open>0 && end>open && stat.substring(0,open).trim().equals("1"),"Invalid held process identity");
        String[] fields=stat.substring(end+1).trim().split("\\s+");
        require(fields.length>=20 && List.of("R","S").contains(fields[0]) && fields[17].equals("1"),"Held process status observations differ");
        long start=positive(fields[19]);
        Map<String,String> namespaces=new LinkedHashMap<>();
        for (String name:NAMESPACES) {
            String observed=raw.get("ns-"+name);namespace(name,observed);
            require(!observed.equals(forbiddenNamespaces.get(name)),"Runtime shares a forbidden namespace");namespaces.put(name,observed);
        }
        require(raw.get("selfmnt").equals(namespaces.get("mnt")),"Observer has another mount namespace");
        String cgroup=text(raw,"cgroup");
        require(cgroup.equals(text(raw,"selfgroup")) && cgroup.trim().equals("0::/"),"Observer has another cgroup scope");
        long memory=positive(text(raw,"memory").trim()),pids=positive(text(raw,"pids").trim());
        require(memory<=limits.memoryBytes() && pids<=limits.pids(),"Effective runtime limit is excessive");
        BitSet cpus=cpuSet(text(raw,"cpuset").trim()),allowed=cpuSet(required(status,"Cpus_allowed_list"));
        require(cpus.equals(cpuSet(limits.cpus())) && cpus.equals(allowed),"Effective CPU affinity differs");
        String[] cpu=text(raw,"cpu").trim().split("\\s+");
        require(cpu.length==2,"Effective CPU bandwidth policy is unavailable");
        if (limits.cpuSetOnly()) {
            require("max".equals(cpu[0]) && cpu[1].matches("[1-9][0-9]*") && positive(cpu[1])>0
                    && (limits.cpuPeriod()==0 || positive(cpu[1])==limits.cpuPeriod()),"Effective cpuset-only CPU policy differs");
        } else require(positive(cpu[0])==limits.cpuQuota() && positive(cpu[1])==limits.cpuPeriod(),"Effective CPU quota differs");
        verifyMounts(text(raw,"mounts"),text(raw,"nodes"),text(raw,"devices"),memory);
        return new Observation(start,memory,pids,canonical(cpus),namespaces);
    }

    private void verifyMounts(String text,String nodesText,String devicesText,long memory) throws IOException {
        var parsed=DedicatedStorageResource.parseMounts(text);String[] lines=text.lines().toArray(String[]::new);
        require(!parsed.isEmpty() && parsed.size()<=1024,"Invalid runtime mount count");
        Map<Path,RuntimeMount> mounts=new HashMap<>();
        for (int i=0;i<parsed.size();i++) {
            var m=parsed.get(i);String[] parts=lines[i].split(" ");int end=Arrays.asList(parts).indexOf("-");
            require(mounts.put(m.point(),new RuntimeMount(m,List.of(Arrays.copyOfRange(parts,6,end))))==null,"Ambiguous runtime mount point");
            require(m.options().contains("ro")!=m.options().contains("rw"),"Unknown runtime mount access mode");
        }
        Map<Path,Node> nodes=nodes(nodesText);Set<Path> expectedNodes=new HashSet<>();
        for (Path path:METADATA) if (mounts.containsKey(path)) expectedNodes.add(path);
        for (Expected e:expected) expectedNodes.add(e.target());
        require(nodes.keySet().equals(expectedNodes),"Unexpected runtime mount node observation");
        Set<Path> known=new HashSet<>();
        for (Expected e:expected) {
            RuntimeMount observed=mounts.get(e.target());require(observed!=null,"Private runtime mount is missing");
            var m=observed.mount();Node node=nodes.get(e.target());
            require(m.device().equals(e.device()) && m.root().equals(e.root()) && m.type().equals(e.type())
                    && node.device()==e.nodeDevice() && node.inode()==e.inode() && node.mode()==e.mode(),"Runtime mount physical identity differs");
            require(m.options().contains(e.readOnly()?"ro":"rw") && observed.propagation().isEmpty(),"Runtime mount access or propagation differs");
            if (e.mode()==0100000) require(node.links()==1,"Runtime input acquired an alias");
            known.add(e.target());
        }
        Map<Path,String> devices=devices(devicesText);long tmpfs=0;
        for (RuntimeMount value:mounts.values()) {
            var mount=value.mount();Path point=mount.point();if (known.contains(point)) continue;
            boolean ro=mount.options().contains("ro");String type=mount.type();
            if (point.equals(Path.of("/"))) { require(ro,"Container image root is writable");continue; }
            if (METADATA.contains(point)) {
                Node node=nodes.get(point);require(ro && node.mode()==0100000 && node.size()<=65536,"Provider metadata mount is writable or unbounded");continue;
            }
            if (point.equals(Path.of("/proc")) && type.equals("proc")
                    && mount.root().equals(Path.of("/"))
                    && mount.options().containsAll(List.of("nosuid","nodev","noexec"))) continue;
            if (Set.of("/proc/asound","/proc/bus","/proc/fs","/proc/irq","/proc/sys","/proc/sysrq-trigger").contains(point.toString())) {
                var parent=mounts.get(Path.of("/proc"));
                require(type.equals("proc") && ro && mount.options().containsAll(List.of("nosuid","nodev","noexec"))
                        && mount.root().equals(Path.of(point.toString().substring(5)))
                        && parent!=null && parent.mount().type().equals("proc") && parent.mount().root().equals(Path.of("/"))
                        && mount.device().equals(parent.mount().device()),"Readonly proc subtree has another filesystem or access policy");
                continue;
            }
            if (point.equals(Path.of("/sys")) && type.equals("sysfs") && ro) continue;
            if (point.equals(Path.of("/sys/fs/cgroup")) && type.equals("cgroup2") && ro) continue;
            if (point.equals(Path.of("/dev/pts")) && type.equals("devpts") && mount.options().containsAll(List.of("nosuid","noexec"))) continue;
            if (point.equals(Path.of("/dev/mqueue")) && type.equals("mqueue") && mount.options().containsAll(List.of("nosuid","nodev","noexec"))) continue;
            if (Set.of("/proc/acpi","/proc/scsi","/sys/firmware","/sys/devices/virtual/powercap").contains(point.toString()) && type.equals("tmpfs") && ro) continue;
            String device=DEVICES.get(point);if (device==null && MASKS.contains(point)) device="1:3";
            if (device!=null && type.equals("devtmpfs") && device.equals(devices.get(point))
                    && mount.root().equals(Path.of(MASKS.contains(point)?"/null":point.toString().substring(4)))) continue;
            if (Set.of(Path.of("/dev"),Path.of("/dev/shm")).contains(point) && type.equals("tmpfs")) {
                long size=tmpfsSize(mount.superOptions());require(size<=memory,"Device tmpfs exceeds memory budget");
                try { tmpfs=Math.addExact(tmpfs,size); } catch (ArithmeticException overflow) { throw new IOException("Device tmpfs size overflows",overflow); }
                continue;
            }
            throw new IOException("Unregistered runtime mount");
        }
        require(mounts.containsKey(Path.of("/")) && tmpfs<=memory,"Missing image root or excessive device memory");
    }

    private static Map<String,String> read(String response) throws IOException {
        require(response!=null && response.length()<=MAX_RESPONSE,"Runtime response exceeds its bound");
        Map<String,String> result=new HashMap<>();
        try (JsonReader reader=new JsonReader(new StringReader(response))) {
            reader.setLenient(false);reader.beginObject();
            while (reader.hasNext()) {
                String key=reader.nextName();require(FIELDS.contains(key) && !result.containsKey(key),"Unexpected or duplicate runtime field");
                require(reader.peek()==JsonToken.STRING,"Runtime fields must be strings");result.put(key,reader.nextString());
            }
            reader.endObject();require(reader.peek()==JsonToken.END_DOCUMENT && result.keySet().equals(FIELDS),"Incomplete or trailing runtime response");
        } catch (IllegalStateException malformed) { throw new IOException("Malformed runtime response",malformed); }
        return result;
    }
    private static byte[] decoded(String encoded) throws IOException {
        try { byte[] bytes=Base64.getDecoder().decode(encoded);require(bytes.length<=1024*1024,"Runtime field exceeds its bound");return bytes; }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid runtime field encoding",invalid); }
    }
    private static String text(Map<String,String> raw,String key) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded(raw.get(key)))).toString();
    }
    private static Map<String,String> properties(String text) throws IOException {
        Map<String,String> result=new HashMap<>();
        for (String line:text.lines().toList()) {
            int split=line.indexOf(':');require(split>0,"Malformed process status field");
            require(result.put(line.substring(0,split),line.substring(split+1).trim())==null,"Duplicate process status field");
        }
        return result;
    }
    private static String required(Map<String,String> map,String key) throws IOException {
        String value=map.get(key);require(value!=null,"Missing process status field");return value;
    }
    private static long positive(String value) throws IOException { long n=number(value,10);require(n>0,"Runtime counter is not finite positive");return n; }
    private static long number(String value,int radix) throws IOException {
        try { require(value!=null && value.matches(radix==16?"[0-9a-fA-F]{1,16}":"[0-9]{1,19}"),"Malformed runtime number");
            long n=Long.parseLong(value,radix);require(n>=0,"Negative runtime number");return n; }
        catch (NumberFormatException invalid) { throw new IOException("Runtime number is out of range",invalid); }
    }
    private static long hex(String value) throws IOException { return number(value,16); }
    private static void ids(String value,long expected) throws IOException {
        String[] fields=value.split("\\s+");require(fields.length==4,"Incomplete runtime IDs");
        for (String field:fields) require(number(field,10)==expected,"Runtime process IDs differ");
    }
    private static long mapped(long id,String text) throws IOException {
        List<long[]> ranges=new ArrayList<>();Long found=null;
        for (String line:text.lines().toList()) {
            String[] values=line.trim().split("\\s+");require(values.length==3 && ranges.size()<512,"Invalid user mapping range");
            long inner=number(values[0],10),outer=number(values[1],10),length=positive(values[2]);
            require(inner<0xffffffffL && outer<0xffffffffL && length<=0xffffffffL-inner && length<=0xffffffffL-outer,"User mapping overflows");
            for (long[] prior:ranges) require(!(inner<prior[0]+prior[2] && prior[0]<inner+length)
                    && !(outer<prior[1]+prior[2] && prior[1]<outer+length),"Overlapping user mapping");
            ranges.add(new long[]{inner,outer,length});if (id>=inner && id<inner+length) found=outer+id-inner;
        }
        require(found!=null,"Runtime ID has no mapping");return found;
    }
    private static void namespace(String name,String value) throws IOException {
        require(value!=null && value.matches(name+":\\[[1-9][0-9]{0,19}\\]"),"Invalid namespace identity");
    }
    private static BitSet cpuSet(String value) throws IOException {
        require(value!=null && !value.isBlank() && value.length()<=65536,"Missing or excessive CPU set");BitSet result=new BitSet();
        for (String range:value.split(",",-1)) {
            String[] values=range.split("-",-1);require(values.length>=1 && values.length<=2,"Invalid CPU range");
            long first=number(values[0],10),last=values.length==2?number(values[1],10):first;
            require(last>=first && last<=65535,"CPU range exceeds its bound");
            int previous=result.nextSetBit((int)first);require(previous<0 || previous>last,"Overlapping CPU ranges");result.set((int)first,(int)last+1);
        }
        return result;
    }
    private static String canonical(BitSet cpus) { return String.join(",",cpus.stream().mapToObj(Integer::toString).toList()); }
    private static Map<Path,Node> nodes(String text) throws IOException {
        Map<Path,Node> result=new HashMap<>();
        for (String line:text.lines().toList()) {
            String[] f=line.split("\\|",-1);require(f.length==6,"Malformed runtime node");
            Path point=nodePath(f[0]);Node node=new Node(number(f[1],10),positive(f[2]),(int)(hex(f[3])&0170000),number(f[4],10),number(f[5],10));
            require(result.put(point,node)==null,"Duplicate runtime node");
        }
        return result;
    }
    private static Path nodePath(String field) throws IOException {
        if (!field.startsWith("b64:")) return kernelPath(field);
        String path=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded(field.substring(4)))).toString();
        return kernelPath(path);
    }
    private static Map<Path,String> devices(String text) throws IOException {
        Map<Path,String> result=new HashMap<>();
        for (String line:text.lines().toList()) {
            String[] f=line.split("\\|",-1);require(f.length==4,"Malformed device observation");Path path=kernelPath(f[0]);
            require((DEVICES.containsKey(path) || MASKS.contains(path)) && (hex(f[1])&0170000)==0020000,"Unexpected device node");
            require(result.put(path,hex(f[2])+":"+hex(f[3]))==null,"Duplicate device observation");
        }
        return result;
    }
    private static long tmpfsSize(List<String> options) throws IOException {
        var matches=options.stream().filter(s->s.startsWith("size=")).toList();require(matches.size()==1,"Device tmpfs has no unique size");
        String value=matches.get(0).substring(5);require(value.matches("[0-9]+[kKmMgG]?"),"Invalid device tmpfs size");
        char last=value.charAt(value.length()-1);long multiplier=1;
        if (!Character.isDigit(last)) { multiplier=switch(Character.toLowerCase(last)) { case 'k'->1024L;case 'm'->1024L*1024;case 'g'->1024L*1024*1024;default->throw new IOException("Invalid tmpfs unit"); };value=value.substring(0,value.length()-1); }
        try { return Math.multiplyExact(positive(value),multiplier); } catch (ArithmeticException invalid) { throw new IOException("Device tmpfs size overflows",invalid); }
    }
    private static Path kernelPath(String value) throws IOException {
        try { Path path=Path.of(value);require(path.isAbsolute() && path.equals(path.normalize()),"Invalid runtime node path");return path; }
        catch (java.nio.file.InvalidPathException invalid) { throw new IOException("Invalid runtime node path",invalid); }
    }
    private static void require(boolean ok,String message) throws IOException { if (!ok) throw new IOException(message); }
}
