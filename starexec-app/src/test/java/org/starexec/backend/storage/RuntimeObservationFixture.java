package org.starexec.backend.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic kernel packets for backend protocol tests; these do not attest a running provider. */
public final class RuntimeObservationFixture {
    private RuntimeObservationFixture() { }

    public static Map<String,String> fields(BuildExecutionRuntime runtime,BuildExecutionRuntime.Identity identity,
            BuildExecutionRuntime.Limits limits,Map<String,String> forbidden,List<String> command,boolean encodedTargets) {
        var data=new LinkedHashMap<String,String>();
        StringBuilder mounts=new StringBuilder("1 0 0:99 / / ro - overlay overlay rw\n"),nodes=new StringBuilder();int id=10;
        for(var expected:runtime.expectedMounts()) {
            mounts.append(id++).append(" 1 ").append(expected.device()).append(' ').append(escaped(expected.root())).append(' ')
                    .append(escaped(expected.target())).append(expected.readOnly()?" ro":" rw").append(" - ").append(expected.type()).append(" none rw\n");
            nodes.append(encodedTargets?"b64:"+encoded(expected.target().toString()):expected.target().toString())
                    .append('|').append(expected.nodeDevice()).append('|').append(expected.inode()).append('|')
                    .append(Integer.toHexString(expected.mode())).append("|1|1\n");
        }
        for(String name:List.of("hosts","hostname","resolv.conf")) {
            mounts.append(id++).append(" 1 0:99 /provider/").append(name).append(" /etc/").append(name).append(" ro - tmpfs tmpfs rw\n");
            nodes.append(encodedTargets?"b64:"+encoded("/etc/"+name):"/etc/"+name).append("|99|1|81a4|32|1\n");
        }
        put(data,"mounts",mounts.toString());put(data,"nodes",nodes.toString());put(data,"devices","");
        put(data,"uidmap","0 "+identity.outsideUid()+" 1\n1 100000 65536\n");
        put(data,"gidmap","0 "+identity.outsideGid()+" 1\n1 100000 65536\n");
        put(data,"status","State:\tS (sleeping)\nPid:\t1\nTgid:\t1\nThreads:\t1\nTracerPid:\t0\n"
                +"Uid:\t0 0 0 0\nGid:\t0 0 0 0\nNoNewPrivs:\t1\nSeccomp:\t2\n"
                +"CapEff:\t0000\nCapPrm:\t0000\nCapInh:\t0000\nCapBnd:\t0000\nCapAmb:\t0000\n"
                +"SigCgt:\t0800\nSigBlk:\t0000\nSigIgn:\t0000\nCpus_allowed_list:\t"+limits.cpus()+"\n");
        String[] stat=new String[22];java.util.Arrays.fill(stat,"0");stat[0]="S";stat[17]="1";stat[19]="1234567";
        put(data,"pidstat","1 (held process) "+String.join(" ",stat));
        put(data,"memory",limits.memoryBytes()+"\n");put(data,"pids",limits.pids()+"\n");
        put(data,"cpu",(limits.cpuSetOnly()?"max":Long.toString(limits.cpuQuota()))+" "+(limits.cpuPeriod()==0?100000:limits.cpuPeriod())+"\n");
        put(data,"cpuset",limits.cpus()+"\n");put(data,"release","12\n");
        put(data,"cgroup","0::/\n");put(data,"selfgroup","0::/\n");
        for(String name:forbidden.keySet()) data.put("ns-"+name,name+":[100]");
        data.put("selfmnt","mnt:[100]");put(data,"cmdline",String.join("\0",command)+"\0");return data;
    }
    private static void put(Map<String,String> data,String key,String value) {data.put(key,encoded(value));}
    private static String encoded(String value) {return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private static String escaped(Path path) {return path.toString().replace("\\","\\134").replace(" ","\\040").replace("\t","\\011").replace("\n","\\012");}
}
