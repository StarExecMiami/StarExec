package org.starexec.backend.storage;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildExecutionRuntimeTest {
    @TempDir Path directory;
    private BuildExecutionRuntime runtime;
    private Map<String,String> data;
    private List<BuildExecutionMounts.Binding> bindings;
    private Map<String,String> forbidden;

    private static String encoded(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static String decoded(String text) { return new String(Base64.getDecoder().decode(text),StandardCharsets.UTF_8); }
    private static String escaped(Path path) { return path.toString().replace("\\","\\134").replace(" ","\\040").replace("\t","\\011").replace("\n","\\012"); }

    @BeforeEach void seed() throws Exception {
        bindings=new ArrayList<>();
        Path source=Files.createDirectory(directory.resolve("source"));
        bindings.add(new BuildExecutionMounts.Binding(source,BuildExecutionMounts.SOURCE,true));
        bindings.add(new BuildExecutionMounts.Binding(Files.writeString(directory.resolve("helper"),"helper"),BuildExecutionMounts.HELPER,true));
        for (String name:List.of("jobscript","functions.bash","status_codes.bash")) {
            bindings.add(new BuildExecutionMounts.Binding(Files.writeString(directory.resolve(name),"control"),BuildExecutionMounts.CONTROL.resolve(name),true));
        }
        for (Path target:List.of(BuildExecutionMounts.EXECUTION,Path.of("/tmp"),Path.of("/run"),Path.of("/var/tmp"))) {
            bindings.add(new BuildExecutionMounts.Binding(Files.createDirectory(directory.resolve("write-"+bindings.size())),target,false));
        }
        forbidden=new HashMap<>();
        for (String name:List.of("pid","ipc","net","mnt","user","cgroup")) forbidden.put(name,name+":[10]");
        runtime=BuildExecutionRuntime.capture(bindings,new BuildExecutionRuntime.Identity(0,0,1000,1000),
                new BuildExecutionRuntime.Limits(134217728,64,"2",100000,100000),forbidden,30);
        data=new LinkedHashMap<>();
        StringBuilder mounts=new StringBuilder("1 0 0:99 / / ro - overlay overlay rw\n");
        StringBuilder nodes=new StringBuilder();int id=10;
        for (var expected:runtime.expectedMounts()) {
            mounts.append(id++).append(" 1 ").append(expected.device()).append(' ').append(escaped(expected.root())).append(' ')
                    .append(expected.target()).append(expected.readOnly()?" ro":" rw").append(" - ").append(expected.type()).append(" none rw\n");
            nodes.append(expected.target()).append('|').append(expected.nodeDevice()).append('|').append(expected.inode()).append('|')
                    .append(Integer.toHexString(expected.mode())).append("|1|1\n");
        }
        for (String name:List.of("hosts","hostname","resolv.conf")) {
            mounts.append(id++).append(" 1 0:99 /provider/").append(name).append(" /etc/").append(name).append(" ro - tmpfs tmpfs rw\n");
            nodes.append("/etc/").append(name).append("|99|1|81a4|32|1\n");
        }
        put("mounts",mounts.toString());put("nodes",nodes.toString());put("devices","");
        put("uidmap","0 1000 1\n1 100000 65536\n");put("gidmap","0 1000 1\n1 100000 65536\n");
        put("status","State:\tS (sleeping)\nPid:\t1\nTgid:\t1\nThreads:\t1\nTracerPid:\t0\n"
                +"Uid:\t0 0 0 0\nGid:\t0 0 0 0\nNoNewPrivs:\t1\nSeccomp:\t2\n"
                +"CapEff:\t0000\nCapPrm:\t0000\nCapInh:\t0000\nCapBnd:\t0000\nCapAmb:\t0000\n"
                +"SigCgt:\t0800\nSigBlk:\t0000\nSigIgn:\t0000\nCpus_allowed_list:\t2\n");
        String[] stat=new String[22];java.util.Arrays.fill(stat,"0");stat[0]="S";stat[17]="1";stat[19]="1234567";
        put("pidstat","1 (held process) "+String.join(" ",stat));
        put("memory","134217728\n");put("pids","64\n");put("cpu","100000 100000\n");put("cpuset","2\n");put("release","12\n");
        put("cgroup","0::/\n");put("selfgroup","0::/\n");
        for (String name:forbidden.keySet()) data.put("ns-"+name,name+":[100]");
        data.put("selfmnt","mnt:[100]");
        List<String> command=new ArrayList<>(BuildExecutionHold.entrypoint(30));command.addAll(BuildExecutionHold.command(BuildExecutionMounts.CONTROL.resolve("jobscript")));
        put("cmdline",String.join("\0",command)+"\0");
    }
    private void put(String key,String text) { data.put(key,encoded(text)); }
    private String json() { return new Gson().toJson(data); }
    private void status(String key,String value) {
        String current=decoded(data.get("status"));current=current.replaceAll("(?m)^"+key+":.*$",key+": "+value);put("status",current);
    }

    @Test void exactHeldKernelStateCanBeObserved() throws Exception {
        var result=runtime.verify(json());assertEquals(1234567,result.startTicks());assertEquals(64,result.pids());assertEquals("2",result.cpus());
        assertThrows(UnsupportedOperationException.class,()->result.namespaces().clear());
    }

    @Test void encodedMountTargetsPreserveTheSamePhysicalIdentity() throws Exception {
        StringBuilder nodes=new StringBuilder();
        for (String line:decoded(data.get("nodes")).lines().toList()) {
            int split=line.indexOf('|');
            nodes.append("b64:").append(encoded(line.substring(0,split))).append(line.substring(split)).append('\n');
        }
        put("nodes",nodes.toString());
        assertEquals(1234567,runtime.verify(json()).startTicks());
    }

    @Test void cpuSetOnlyPolicyPreservesTheUnthrottledPartition() throws Exception {
        var pinned=BuildExecutionRuntime.capture(bindings,new BuildExecutionRuntime.Identity(0,0,1000,1000),
                new BuildExecutionRuntime.Limits(134217728,64,"2",-1,0),forbidden,30);
        put("cpu","max 100000\n");
        assertEquals("2",pinned.verify(json()).cpus());
        for (String value:List.of("100000 100000","max 0","max -1","max","max 01","max 1 extra")) {
            put("cpu",value);assertThrows(IOException.class,()->pinned.verify(json()),value);
        }
        put("cpu","max 100000\n");put("cpuset","2-3");
        assertThrows(IOException.class,()->pinned.verify(json()));
    }
    @ParameterizedTest @ValueSource(strings={"memory","pids","cpu","cpuset","cmdline","nodes","ns-pid"})
    void incompleteResponsesCannotPass(String field) { data.remove(field);assertThrows(IOException.class,()->runtime.verify(json())); }

    @Test void duplicateCoercedTrailingAndOversizedResponsesAreRefused() {
        String valid=json();
        assertThrows(IOException.class,()->runtime.verify(valid.substring(0,valid.length()-1)+",\"memory\":\"MQ==\"}"));
        var numeric=new Gson().toJsonTree(data).getAsJsonObject();numeric.addProperty("memory",12);
        assertThrows(IOException.class,()->runtime.verify(numeric.toString()));
        assertThrows(IOException.class,()->runtime.verify(valid+"{}"));
        assertThrows(IOException.class,()->runtime.verify(" ".repeat(2*1024*1024+1)));
    }
    @ParameterizedTest @ValueSource(strings={"memory","pids"})
    void unboundedOrExcessiveCountersAreRefused(String key) {
        for (String value:List.of("max","0","-1","999999999999999999999999999999")) {
            put(key,value);assertThrows(IOException.class,()->runtime.verify(json()),value);
        }
        put(key,key.equals("memory")?"134217729":"65");assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @ParameterizedTest @ValueSource(strings={"CapEff","CapPrm","CapInh","CapBnd","CapAmb"})
    void survivingCapabilitiesAreRefused(String key) { status(key,"1");assertThrows(IOException.class,()->runtime.verify(json())); }

    @ParameterizedTest @ValueSource(strings={"NoNewPrivs:0","Seccomp:0","TracerPid:2","Threads:2","Pid:2","Tgid:2","State:T (stopped)","SigCgt:0","SigBlk:0800","SigIgn:0800","Uid:0 1 0 0","Gid:0 0 0 1"})
    void unsafeProcessStateCannotBecomeAnObservation(String setting) {
        String[] values=setting.split(":",2);status(values[0],values[1]);assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @Test void postExecScriptCannotMasqueradeAsTheHold() {
        put("cmdline","/bin/bash\0--noprofile\0--norc\0-p\0--\0/starexec/control/jobscript\0");
        assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @Test void aLiveSchedulingTransitionDoesNotInvalidateTheSameHeldProcess() throws Exception {
        put("pidstat",decoded(data.get("pidstat")).replace("(held process) S ","(held process) R "));
        assertEquals(1234567,runtime.verify(json()).startTicks());
    }
    @ParameterizedTest @ValueSource(strings={"uidmap","gidmap"})
    void changedOverlappingOrMissingMappingsAreRefused(String key) {
        for (String map:List.of("0 0 1\n","0 1000 2\n1 2000 1\n","0 1000 2\n3 1001 1\n","1 1000 1\n","0 4294967295 1\n")) {
            put(key,map);assertThrows(IOException.class,()->runtime.verify(json()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"pid","ipc","net","mnt","user","cgroup"})
    void sharedOrMalformedNamespacesAreRefused(String name) {
        data.put("ns-"+name,name+":[10]");assertThrows(IOException.class,()->runtime.verify(json()));
        data.put("ns-"+name,"unknown");assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @ParameterizedTest @ValueSource(strings={"selfmnt","selfgroup"})
    void observerInAnotherScopeIsRefused(String name) {
        if (name.equals("selfmnt")) data.put(name,"mnt:[777]");else put(name,"0::/other\n");
        assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @Test void widenedCpuSetsAndQuotasAreRefused() {
        put("cpuset","2-3");assertThrows(IOException.class,()->runtime.verify(json()));put("cpuset","2");
        status("Cpus_allowed_list","2,3");assertThrows(IOException.class,()->runtime.verify(json()));status("Cpus_allowed_list","2");
        for (String value:List.of("max 100000","200000 100000","100000 50000")) { put("cpu",value);assertThrows(IOException.class,()->runtime.verify(json())); }
    }
    @ParameterizedTest @ValueSource(strings={"device","root","inode","mode","access","propagation","missing","nested"})
    void physicalMountChangesAreRefused(String change) {
        var first=runtime.expectedMounts().get(0);String mounts=decoded(data.get("mounts"));String nodes=decoded(data.get("nodes"));
        String line=mounts.lines().filter(v->v.contains(" "+first.target()+" ")).findFirst().orElseThrow();
        switch (change) {
            case "device" -> mounts=mounts.replace(line,line.replace(" "+first.device()+" "," 0:777 "));
            case "root" -> mounts=mounts.replace(line,line.replace(" "+escaped(first.root())+" "," /foreign "));
            case "inode" -> nodes=nodes.replace(first.target()+"|"+first.nodeDevice()+"|"+first.inode()+"|",first.target()+"|"+first.nodeDevice()+"|999999|");
            case "mode" -> nodes=nodes.replaceFirst("\\|4000\\|","|a000|");
            case "access" -> mounts=mounts.replace(line,line.replace(" ro - "," rw - "));
            case "propagation" -> mounts=mounts.replace(line,line.replace(" - "," shared:1 - "));
            case "missing" -> mounts=mounts.replace(line+"\n","");
            case "nested" -> mounts+="99 1 9:9 / /starexec/inputs/source_src/nested ro - ext4 none rw\n";
        }
        put("mounts",mounts);put("nodes",nodes);assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @Test void readonlyForeignMountAndWritableCgroupAreNotExemptions() {
        String valid=decoded(data.get("mounts"));
        for (String extra:List.of("99 1 1:1 /private /foreign ro - ext4 none rw\n","99 1 0:2 / /sys/fs/cgroup rw - cgroup2 none rw\n")) {
            put("mounts",valid+extra);assertThrows(IOException.class,()->runtime.verify(json()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"asound","bus"})
    void defaultReadOnlyProcSubtreesRetainTheirNamespaceFilesystem(String child) throws Exception {
        put("mounts",decoded(data.get("mounts"))+"99 1 0:42 / /proc rw,nosuid,nodev,noexec - proc proc rw\n"
                +"100 99 0:42 /"+child+" /proc/"+child+" ro,nosuid,nodev,noexec - proc proc rw\n");
        assertNotNull(runtime.verify(json()));
    }
    @ParameterizedTest @ValueSource(strings={"writable","device","root","type","options","missing-proc"})
    void foreignOrWritableProcSubtreesAreRefused(String change) {
        String base="99 1 0:42 / /proc rw,nosuid,nodev,noexec - proc proc rw\n";
        String subtree="100 99 0:42 /bus /proc/bus ro,nosuid,nodev,noexec - proc proc rw\n";
        switch (change) {
            case "writable" -> subtree=subtree.replace(" ro,"," rw,");
            case "device" -> subtree=subtree.replace("0:42","0:43");
            case "root" -> subtree=subtree.replace(" /bus "," /sys ");
            case "type" -> subtree=subtree.replace(" - proc "," - ext4 ");
            case "options" -> subtree=subtree.replace(",noexec","");
            case "missing-proc" -> base="";
        }
        put("mounts",decoded(data.get("mounts"))+base+subtree);assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @Test void imageRootAndMountNodesCannotBeRedirected() {
        put("mounts",decoded(data.get("mounts")).replace(" / ro - overlay"," / rw - overlay"));assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @Test void providerMetadataMayBeAbsentFromTheReadonlyImage() throws Exception {
        put("mounts",decoded(data.get("mounts")).replaceAll("(?m)^.* /etc/[^ ]+ .*\\n",""));
        put("nodes",decoded(data.get("nodes")).replaceAll("(?m)^/etc/.*\\n",""));
        assertNotNull(runtime.verify(json()));
    }
    @Test void everyMountedMetadataFileRequiresItsNodeObservation() {
        put("nodes",decoded(data.get("nodes")).replaceAll("(?m)^/etc/resolv\\.conf\\|.*\\n",""));
        assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @Test void unmountedMetadataObservationsAreRefused() {
        put("mounts",decoded(data.get("mounts")).replaceAll("(?m)^.* /etc/resolv\\.conf .*\\n",""));
        assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @ParameterizedTest @ValueSource(strings={"writable","large","symlink","directory"})
    void providerMetadataCannotEscapeItsFilePolicy(String change) {
        String mounts=decoded(data.get("mounts")),nodes=decoded(data.get("nodes"));
        switch (change) {
            case "writable" -> mounts=mounts.replace("/etc/resolv.conf ro -","/etc/resolv.conf rw -");
            case "large" -> nodes=nodes.replace("/etc/resolv.conf|99|1|81a4|32|1","/etc/resolv.conf|99|1|81a4|65537|1");
            case "symlink" -> nodes=nodes.replace("/etc/resolv.conf|99|1|81a4|32|1","/etc/resolv.conf|99|1|a1ff|32|1");
            case "directory" -> nodes=nodes.replace("/etc/resolv.conf|99|1|81a4|32|1","/etc/resolv.conf|99|1|41ed|32|1");
        }
        put("mounts",mounts);put("nodes",nodes);assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @Test void sourceMountResolutionUsesPathComponentsAndBindRoots() throws Exception {
        var root=new DedicatedStorageResource.Mount(1,"0:1",Path.of("/"),Path.of("/"),"overlay",List.of("rw"),List.of("rw"));
        var data=new DedicatedStorageResource.Mount(2,"8:2",Path.of("/physical/data"),Path.of("/app/data"),"ext4",List.of("rw"),List.of("rw"));
        var selected=BuildExecutionRuntime.sourceMount(List.of(root,data),Path.of("/app/data/solver"));
        assertEquals(Path.of("/physical/data/solver"),selected.root().resolve(selected.point().relativize(Path.of("/app/data/solver"))));
        assertEquals(root,BuildExecutionRuntime.sourceMount(List.of(root,data),Path.of("/app/database/solver")));
        assertThrows(IOException.class,()->BuildExecutionRuntime.sourceMount(List.of(root,data,data),Path.of("/app/data/solver")));
    }

    @Test void deviceFilesCannotMasqueradeAsWritableStorageOrConversely() throws Exception {
        String mounts=decoded(data.get("mounts"));
        put("mounts",mounts+"99 1 0:7 /null /dev/null rw,nosuid - devtmpfs udev rw\n");
        put("devices","/dev/null|21b6|1|3\n");assertNotNull(runtime.verify(json()));
        for (String value:List.of("/dev/null|81b6|1|3\n","/dev/null|21b6|1|5\n","/dev/null|61b6|1|3\n")) {
            put("devices",value);assertThrows(IOException.class,()->runtime.verify(json()));
        }
    }
    @Test void deviceTmpfsRequiresAFiniteAggregateMemoryBound() throws Exception {
        String mounts=decoded(data.get("mounts"));
        put("mounts",mounts+"99 1 0:7 / /dev rw,nosuid - tmpfs tmpfs rw,size=64m\n");assertNotNull(runtime.verify(json()));
        put("mounts",mounts+"99 1 0:7 / /dev rw,nosuid - tmpfs tmpfs rw,size=129m\n");assertThrows(IOException.class,()->runtime.verify(json()));
        put("mounts",mounts+"99 1 0:7 / /dev rw,nosuid - tmpfs tmpfs rw\n");assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @Test void runtimeFileAliasesAndDuplicateMountsAreRefused() {
        String mounts=decoded(data.get("mounts"));String line=mounts.lines().skip(1).findFirst().orElseThrow();
        put("mounts",mounts+line+"\n");assertThrows(IOException.class,()->runtime.verify(json()));
    }
    @Test void malformedEncodingAndInvalidNodePathsAreTypedRefusals() {
        String valid=data.get("memory");data.put("memory","not-base64");assertThrows(IOException.class,()->runtime.verify(json()));data.put("memory",valid);
        put("nodes",decoded(data.get("nodes"))+"/bad\0path|0|1|81a4|0|1\n");assertThrows(IOException.class,()->runtime.verify(json()));
    }
}
