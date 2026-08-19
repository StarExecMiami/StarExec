# CPU Partition Scheduling

## Overview

StarExec's container backend (PodmanBackend) can pin each job container to a
dedicated subset of logical CPUs called a _partition_. This prevents competing
containers from sharing CPU scheduler time and L1/L2 cache lines, improves
result reproducibility, and maps naturally to NUMA hardware where partitions
can align with physical memory domains.

When partitioning is disabled (the default on single-NUMA machines with no
environment variables set), all containers float across all available CPUs and
the single `container.q` queue and `container-worker-1` node are used — exactly
as before this feature was introduced.

When partitioning is enabled, StarExec still exposes a single submission queue:
`container.q`. Each CPU partition is represented as a separate virtual worker
node attached to that same queue. This keeps queue selection simple while still
letting `PodmanBackend` pin containers to partition-specific CPU sets.

## Configuration reference

| Variable                       | Default                                                         | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ------------------------------ | --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `STAREXEC_CPU_PARTITION_COUNT` | `auto`                                                          | How many partitions to create. `auto` uses NUMA node count when >1 nodes are visible; falls back to legacy single-partition mode on single-NUMA hardware. `1` explicitly disables partitioning. Any integer ≥2 subdivides available CPUs into that many equal groups. Example: `2`, `4`, `auto`.                                                                                                                                                                                                                                                  |
| `STAREXEC_CPU_PARTITIONS`      | _(unset)_                                                       | Explicit cpuset strings, whitespace- or semicolon-separated. Overrides `STAREXEC_CPU_PARTITION_COUNT`. Each token becomes one partition with `cpusetMems=0`. Example: `"0-7 8-15"` **only if `cpu0`'s `thread_siblings_list` is `0-1`** — see "Choosing cpuset strings" below, because on a split-half machine that same value places every SMT sibling of one partition in the other. Prefer `STAREXEC_CPU_PARTITION_COUNT`, which groups whole physical cores automatically, or `=auto` on multi-NUMA hardware for correct NUMA memory binding. |
| `STAREXEC_PARTITION_MAX_JOBS`  | _(same as `STAREXEC_CONTAINER_MAX_CONCURRENT_JOBS`, default 1)_ | Maximum concurrent job containers per partition. A value of `1` means at most one job runs on each partition at a time. Example: `2`.                                                                                                                                                                                                                                                                                                                                                                                                             |

## Startup log

For `STAREXEC_CPU_PARTITIONS="0-7 8-15"`:

```
CpuPartitionManager: 2 partitions from CPU partition configuration
  partition-0: cpus=0-7 mems=0 node=container-worker-partition-0 queue=container.q
  partition-1: cpus=8-15 mems=0 node=container-worker-partition-1 queue=container.q
Container max concurrent jobs per CPU partition: 1
```

For default configuration (single-NUMA, no env vars):

```
CpuPartitionManager: 1 partition from legacy no-pinning configuration
  partition-0: cpus=all mems=all node=container-worker-1 queue=container.q
Container max concurrent jobs per CPU partition: 1
```

## Behavior on different hardware

- **Single socket, single NUMA node, no env vars set** → Legacy no-pinning mode.
  Single `container.q` queue and `container-worker-1` node. No `withCpusetCpus`
  call is made. Behavior is identical to pre-partition code.

- **Single socket, single NUMA node, `STAREXEC_CPU_PARTITIONS="0-7 8-15"`** →
  Two partitions. Containers for partition 0 are pinned to CPUs 0–7; partition 1
  to CPUs 8–15. Both partitions bind to NUMA memory node 0 (only node available).
  One virtual queue (`container.q`) and two virtual nodes are created in the
  database at startup; both nodes are associated with `container.q`.

- **Dual socket, dual NUMA node, `STAREXEC_CPU_PARTITION_COUNT=auto`** → Two
  partitions, one per NUMA node, using cpulist from
  `/sys/devices/system/node/nodeN/cpulist`. Each partition also binds to its own
  NUMA memory node via `withCpusetMems`, eliminating cross-socket memory traffic.

## Partition assignment

Jobs are assigned to partitions by `selectPartition(pairId)`:

1. The partition with the fewest active slots is chosen (least-loaded balancing).
2. On a tie, `pairId % partitionCount` breaks the tie deterministically.
3. For single-partition setups, partition 0 is always selected.

Note: the `Backend.submitScript()` interface has no queue or node parameter.
Partition assignment is therefore internal to `PodmanBackend` and cannot be
directed from the StarExec UI queue selector. The UI continues to expose the
single `container.q` queue; `PodmanBackend` chooses the concrete partition worker
by current load.

## Choosing cpuset strings: check your SMT enumeration first

**`"0-7 8-15"` is safe on some machines and actively harmful on others.** It depends on
how the kernel numbers hyper-threads, and the two common layouts are opposites:

```
# Which logical CPUs share a physical core?
cat /sys/devices/system/cpu/cpu0/topology/thread_siblings_list
```

- **`0-1`** — adjacent-pair enumeration. `cpu0` and `cpu1` are one core. Here
  `"0-7 8-15"` gives each partition four whole cores. Safe.
- **`0,8`** — split-half enumeration. `cpu0`'s sibling is `cpu8`. Here `"0-7 8-15"` puts
  **every sibling of partition 0 into partition 1**: the two partitions share all eight
  physical cores, so two job pairs contend for the same L1 and L2 caches while appearing
  isolated. On such a machine the equivalent safe value is `"0-3,8-11 4-7,12-15"`.

StarExec refuses to start when an explicit `STAREXEC_CPU_PARTITIONS` splits siblings, so
a wrong value fails loudly rather than quietly producing untrustworthy measurements. It
also warns if a partition includes CPU 0, which handles interrupts and OS work.

`STAREXEC_CPU_PARTITION_COUNT=N` needs none of this care: it groups whole physical cores
before splitting, so it cannot separate siblings whichever enumeration the machine uses.
**Prefer it over an explicit cpuset list.**

## Known limitations

1. **`STAREXEC_CPU_PARTITIONS` always uses `cpusetMems=0`** regardless of the actual
   NUMA topology of the specified CPUs. On multi-NUMA hardware this will bind all
   containers to NUMA node 0's memory even if the CPUs belong to node 1. Use
   `STAREXEC_CPU_PARTITION_COUNT=auto` on multi-NUMA systems for correct memory binding.

2. **L3 cache is shared on single-NUMA hardware.** CPU-set isolation prevents kernel
   scheduler churn and provides fair CPU allocation, but does not eliminate cache
   contention at the L3 level between concurrent jobs on different partitions.

3. **Recovered containers count against `STAREXEC_PARTITION_MAX_JOBS`.** If a crash
   left N containers running and N equals the per-partition limit, new submissions to
   that partition will block until those containers exit. This is intentional — the
   CPUs are genuinely occupied — but may surprise operators after an unexpected restart.

## Rollback procedure

To revert to single-partition legacy behavior without redeploying:

1. Unset `STAREXEC_CPU_PARTITIONS` and `STAREXEC_CPU_PARTITION_COUNT` in the
   application's environment (`.env` file, systemd unit, or container spec).
2. Restart the StarExec application.
3. `PodmanBackend` will call `CpuPartitionManager.discover()`, find no env vars and a
   single NUMA node, and return the legacy no-pinning partition with `container.q` and
   `container-worker-1`.
4. Extra partition worker nodes remain in the database but receive no new job
   host updates once legacy mode is active. Operators may deactivate them via the
   StarExec admin UI if desired.

## Changed files

| File                                                | Change                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| --------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `org/starexec/backend/CpuPartition.java`            | New. Immutable value type representing one CPU partition (index, cpusetCpus, cpusetMems, node name, shared queue name).                                                                                                                                                                                                                                                                                                                                                      |
| `org/starexec/backend/CpuPartitionManager.java`     | New. Discovers partitions from NUMA sysfs, env var override, or equal CPU subdivision. Contains `expandCpuset`, `compressCpuset`, `subdivide` helpers.                                                                                                                                                                                                                                                                                                                       |
| `org/starexec/backend/PodmanBackend.java`           | Replaced single global slot gate with per-partition arrays. Added `selectPartition`, `acquirePartitionSlot`, `releasePartitionSlot`, `getWorkerNodeNameForPartition`. Updated `createHostConfig`, `createContainerLabels`, `createContainerWithCurl`, and all three `Backend` getters. `getWorkerNodes()` returns one worker per partition; `getQueues()` returns the single shared `container.q`; `getNodeQueueAssociations()` maps each partition worker to `container.q`. |
| `org/starexec/backend/ContainerJobMonitor.java`     | `updateDatabase` now takes `partitionIndex`; uses `backend.getWorkerNodeNameForPartition()` instead of the deprecated `CONTAINER_WORKER_NODE` constant.                                                                                                                                                                                                                                                                                                                      |
| `org/starexec/backend/KubernetesNativeBackend.java` | Removed cross-backend reference to `PodmanBackend.CONTAINER_WORKER_NODE`. Added local `DEFAULT_WORKER_NODE_NAME` constant and `resolveStatsNodeName()` helper.                                                                                                                                                                                                                                                                                                               |
| `org/starexec/config/EnvironmentConfig.java`        | Added `getCpuPartitionCount()`, `getCpuPartitionsOverride()`, `getPartitionMaxJobs()`.                                                                                                                                                                                                                                                                                                                                                                                       |
| `test/…/PodmanBackendTests.java`                    | Updated slot-tracking helpers for partition arrays. Added `testGetQueues_*`, `testGetWorkerNodes_*`, `testGetNodeQueueAssociations_*` for two-partition config.                                                                                                                                                                                                                                                                                                              |
