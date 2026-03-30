# Backend Configuration Guide

Complete guide to StarExec's execution backends: Local, Podman, Kubernetes, SGE, and OAR.

## Overview

StarExec supports multiple backends for job execution, each with different characteristics:

| Backend | Isolation | Scalability | Use Case |
|---------|-----------|-------------|----------|
| **Local** | ❌ None | Single host, 4-16 jobs | Development, testing |
| **Podman** | ✅ Container | Single host, 64-100 jobs | Production (single-node) |
| **Kubernetes** | ✅ Container | Multi-node, scalable | Production (cluster) |
| **SGE** | ⚠️ Process | Multi-node (HPC) | Legacy HPC clusters |
| **OAR** | ⚠️ Process | Multi-node (HPC) | Legacy HPC clusters |

---

## Quick Start

### Setting the Backend

```bash
# Environment variable
export STAREXEC_BACKEND_TYPE=podman

# Or in Helm values
backend:
  type: podman
```

### Backend Selection Guide

```
┌─────────────────────────────────────────────────────────────┐
│ What are you doing?                                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Development/Testing ──────────────► Local Backend          │
│                                                             │
│  Production (single server) ───────► Podman Backend         │
│                                                             │
│  Production (Kubernetes cluster) ──► Kubernetes Backend     │
│                                                             │
│  Existing HPC cluster (SGE) ───────► SGE Backend (legacy)   │
│                                                             │
│  Existing HPC cluster (OAR) ───────► OAR Backend (legacy)   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Default startup policy (`make start`)

`make start` defaults to the Podman deployment path for better production parity and
safer execution of untrusted solver binaries.

- **Default**: Podman backend (container isolation)
- **Fallback (explicit opt-in)**: Local backend for trusted-only debugging workflows

Use Local backend only when you intentionally need direct host-process debugging and
you trust the executed binaries.

---

## Backend operation profiles

Use profiles to balance reproducibility and throughput:

| Profile | Backend | Key knobs | Use when |
|---|---|---|---|
| `repro-strict` | Podman | `STAREXEC_CONTAINER_MAX_CONCURRENT_JOBS=1` | Benchmark reproducibility and cache-noise reduction are the priority |
| `dev-fast` | Podman | Increase `STAREXEC_CONTAINER_MAX_CONCURRENT_JOBS` + `STAREXEC_NUM_JOB_PAIRS_AT_A_TIME` carefully | Fast local iteration is more important than strict reproducibility |
| `debug-trusted-local` | Local | `STAREXEC_LOCAL_CONCURRENCY` + `STAREXEC_LOCAL_CORE_LIST` | Deep host-level debugging with trusted workloads |

> Keep resource coupling in mind: total memory pressure scales with concurrent jobs.

---

## Podman-default go/no-go checklist

Use this checklist before treating Podman default as healthy in a given environment.

### Go (all must pass)

- [ ] Environment-specific values file exists (`charts/starexec/values-<env>.yaml`)
- [ ] Podman engine reachable (`podman info`)
- [ ] Rootless mode enabled (or explicitly accepted risk for rootful non-prod)
- [ ] Podman socket path exists when container socket mode is enabled
- [ ] `make start` completes and `make wait-postgres` passes
- [ ] Backend resolves to `podman` at runtime (`STAREXEC_BACKEND_TYPE=podman`)

### No-go (any one is enough)

- [ ] Missing env values file with fallback to unrelated defaults
- [ ] Podman engine/socket unavailable or unstable
- [ ] Rootful mode required for production without explicit override
- [ ] Startup succeeds but backend resolves to `local` unexpectedly
- [ ] Reproducibility/performance targets violated for selected profile

### Rollback trigger for default choice

Reconsider Podman as the startup default if onboarding reliability or runtime stability
degrades persistently (for example: repeated startup failures across fresh setups,
or backend mismatch incidents across releases).

---

## Backend Comparison

### Feature Matrix

| Feature | Local | Podman | Kubernetes | SGE | OAR |
|---------|-------|--------|------------|-----|-----|
| Container isolation | ❌ | ✅ | ✅ | ❌ | ❌ |
| Resource limits | ⚠️ | ✅ | ✅ | ✅ | ✅ |
| Job persistence | ❌ | ✅ | ✅ | ✅ | ✅ |
| Horizontal scaling | ❌ | ❌ | ✅ | ✅ | ✅ |
| Untrusted code safe | ❌ | ✅ | ✅ | ⚠️ | ⚠️ |
| Setup complexity | Low | Medium | High | High | High |
| Maintenance | Low | Medium | High | High | High |

### Performance Comparison

| Backend | Max Concurrent | Jobs/Hour (10-min jobs) | Best For |
|---------|----------------|-------------------------|----------|
| Local | 4-16 | 24-96 | < 1,000 jobs |
| Podman | 64-100 | 384-600 | < 100,000 jobs |
| Kubernetes | 500+ | 3,000+ | > 100,000 jobs |
| SGE | Cluster-dependent | Varies | HPC workloads |
| OAR | Cluster-dependent | Varies | HPC workloads |

---

## Local Backend

### Overview

The Local Backend executes jobs as processes on the host system. It's simple and fast but provides no isolation.

**Best for:** Development, testing, small trusted workloads

**Not for:** Production, untrusted code, large-scale jobs

### Configuration

```bash
# Required
export STAREXEC_BACKEND_TYPE=local

# Optional (with defaults)
export STAREXEC_LOCAL_CONCURRENCY=4          # Max parallel jobs
export STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS=3600   # 1 hour timeout
export STAREXEC_LOCAL_USE_RUNSOLVER=false    # Use runsolver wrapper
export STAREXEC_LOCAL_GRACEFUL_SHUTDOWN_SECONDS=30
```

### Concurrency Tuning

| System | Recommended Setting |
|--------|-------------------|
| 4-core laptop | `STAREXEC_LOCAL_CONCURRENCY=2` |
| 8-core workstation | `STAREXEC_LOCAL_CONCURRENCY=4` |
| 16-core server | `STAREXEC_LOCAL_CONCURRENCY=8` |
| 32-core server | `STAREXEC_LOCAL_CONCURRENCY=16` |

### Limitations

- **No isolation**: Jobs run as application user
- **No persistence**: Jobs lost on restart
- **Single host**: Cannot scale horizontally
- **Resource limits**: Only via runsolver (optional)

### Architecture

```
┌─────────────────────────────────────────┐
│ StarExec Application                    │
│                                         │
│   ThreadPoolExecutor (configurable)     │
│   ┌─────┬─────┬─────┬─────┐            │
│   │Job 1│Job 2│Job 3│Job 4│ ← Workers   │
│   └──┬──┴──┬──┴──┬──┴──┬──┘            │
│      │     │     │     │                │
│      ▼     ▼     ▼     ▼                │
│   Process Process Process Process       │
│   (solver) (solver) (solver) (solver)   │
│                                         │
│   ┌─────────────────────────────────┐   │
│   │ In-Memory Job Queue (bounded)   │   │
│   └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

---

## Podman Backend

### Overview

The Podman Backend executes jobs in isolated containers. It provides strong isolation, enforced resource limits, and database-backed job persistence.

**Best for:** Production single-node deployments, untrusted code

**Not for:** Multi-node scaling (use Kubernetes instead)

### Configuration

```bash
# Required
export STAREXEC_BACKEND_TYPE=podman

# Optional (with defaults)
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=4096      # 4GB per job
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=1         # 1 CPU per job
export STAREXEC_CONTAINER_DEFAULT_WALLCLOCK_LIMIT=300 # 5 minutes
export STAREXEC_CONTAINER_MONITOR_POLL_MS=5000        # Poll every 5s
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=5             # Batch size
```

### Resource Limits

| Solver Type | Memory | CPU | Wallclock |
|-------------|--------|-----|-----------|
| Lightweight (SMT, QF_BV) | 2048 MB | 1 | 300s |
| Standard (SAT, LIA) | 4096 MB | 1 | 600s |
| Heavy (QF_NIA) | 8192 MB | 2 | 1200s |
| Industry (Z3, CVC5) | 16384 MB | 4 | 3600s |

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ StarExec Application                                        │
│                                                             │
│   PodmanBackend                 ContainerJobMonitor         │
│   ┌─────────────┐               ┌─────────────────┐        │
│   │Submit Jobs  │───────────────│ Poll Completed  │        │
│   └─────────────┘               └─────────────────┘        │
│         │                              │                    │
│         ▼                              │                    │
│   Create Container                     │                    │
│   with resource limits                 │                    │
│         │                              │                    │
└─────────┼──────────────────────────────┼────────────────────┘
          │                              │
          ▼                              ▼
┌─────────────────┐              ┌─────────────────┐
│  Container 1    │              │  Read Results   │
│  ┌───────────┐  │              │  from Volume    │
│  │  Solver   │  │              └─────────────────┘
│  └───────────┘  │
│  /starexec/out  │──────────────────────────┘
└─────────────────┘         (file-based IPC)
```

### File-Based Communication

Jobs write results to mounted volumes:

| File | Purpose |
|------|---------|
| `status.json` | Job status updates |
| `stats.json` | Runtime statistics |
| `attributes.txt` | Post-processor output |
| `var.out` | Solver stdout |
| `watcher.out` | Runsolver output |

### Tuning for Performance

```bash
# High-throughput cluster (32-core, 256GB RAM)
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=8
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=8192
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=2
export STAREXEC_CONTAINER_MONITOR_POLL_MS=2000

# Memory-constrained (16GB RAM)
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=3
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=2048
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=1
export STAREXEC_CONTAINER_MONITOR_POLL_MS=10000
```

---

## Kubernetes Backend

### Overview

The Kubernetes Backend leverages Kubernetes for container orchestration. It can scale across multiple nodes but currently has architectural limitations.

**Best for:** Cloud deployments, multi-node clusters, auto-scaling

**Current limitation:** 50-job hardcoded limit in hybrid implementation

### Configuration

```yaml
# Helm values
backend:
  type: kubernetes
  dataDir: /var/lib/starexec/data
  
kubernetes:
  namespace: starexec-jobs
  serviceAccount: starexec-runner
  imagePullSecrets:
    - starexec-registry
```

### Architecture

The current implementation is a **hybrid design**:

1. StarExec submits jobs to internal queue
2. Jobs are dispatched to Kubernetes pods
3. ContainerJobMonitor polls for completion
4. Results read from shared storage

```
┌─────────────────────────────────────────────────────────────┐
│ StarExec Pod (Head Node)                                    │
│                                                             │
│   KubernetesBackend                                         │
│   ┌──────────────────────────────────────────────────────┐  │
│   │ Submit jobs to internal queue                        │  │
│   │ Create Kubernetes Job resources                      │  │
│   │ Poll for completion                                  │  │
│   └──────────────────────────────────────────────────────┘  │
│                            │                                │
└────────────────────────────┼────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│ Kubernetes Cluster                                          │
│                                                             │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐                 │
│   │ Job Pod  │  │ Job Pod  │  │ Job Pod  │  ...            │
│   │  Node 1  │  │  Node 2  │  │  Node 3  │                 │
│   └──────────┘  └──────────┘  └──────────┘                 │
│                                                             │
│   Shared Storage (PVC)                                      │
│   ┌─────────────────────────────────────────────────────┐   │
│   │ /starexec/output/                                   │   │
│   └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Limitations

- **50-job limit**: Hardcoded in current implementation
- **Head node bottleneck**: Single StarExec pod manages all jobs
- **Not fully cloud-native**: Still uses polling model

### Future Roadmap

A true Kubernetes-native backend would:
- Create Kubernetes Job resources directly
- Use Kubernetes-native monitoring (not polling)
- Scale StarExec horizontally
- Support 100K+ concurrent jobs

---

## SGE Backend (Legacy)

### Overview

The SGE (Sun Grid Engine) backend integrates with traditional HPC clusters running SGE/Grid Engine.

**Status:** Legacy, tests disabled

**Best for:** Existing HPC environments with SGE

### Configuration

```bash
export STAREXEC_BACKEND_TYPE=sge

# SGE-specific settings
export SGE_ROOT=/opt/sge
export SGE_CELL=default
export SGE_QMASTER_PORT=6444
```

### Requirements

- SGE/Grid Engine installed and configured
- `qsub`, `qstat`, `qdel` commands available
- Shared filesystem between head node and workers

### Job Submission

SGE backend uses `qsub` to submit jobs:

```bash
qsub -N "starexec-job-$PAIR_ID" \
     -l h_rt=$WALLCLOCK_LIMIT \
     -l h_vmem=$MEMORY_LIMIT \
     /path/to/jobscript.sh
```

---

## OAR Backend (Legacy)

### Overview

The OAR backend integrates with OAR-based HPC clusters (common in French research computing).

**Status:** Legacy, tests disabled

**Best for:** Existing HPC environments with OAR

### Configuration

```bash
export STAREXEC_BACKEND_TYPE=oar

# OAR-specific settings
export OAR_SERVER=oar-server.example.com
```

### Requirements

- OAR scheduler installed and configured
- `oarsub`, `oarstat`, `oardel` commands available
- Shared filesystem between head node and workers

---

## Backend Tuning Reference

### Key Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `STAREXEC_NUM_JOB_PAIRS_AT_A_TIME` | 5 | Batch size per submission cycle |
| `STAREXEC_NODE_MULTIPLIER` | 16 | Queue depth = multiplier × nodes |
| `STAREXEC_CONTAINER_MONITOR_POLL_MS` | 5000 | Poll interval for job completion |
| `STAREXEC_CONTAINER_DEFAULT_MEMORY_MB` | 4096 | Memory limit per container |
| `STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT` | 1 | CPU cores per container |
| `STAREXEC_CONTAINER_DEFAULT_WALLCLOCK_LIMIT` | 300 | Wallclock timeout (seconds) |

### Tuning by Workload

#### SAT Competition (Standard)

```bash
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=4096
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=1
export STAREXEC_CONTAINER_DEFAULT_WALLCLOCK_LIMIT=300
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=5
```

#### SMT-LIB Benchmarks

```bash
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=8192
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=1
export STAREXEC_CONTAINER_DEFAULT_WALLCLOCK_LIMIT=1200
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=4
```

#### Parallel Solvers

```bash
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=16384
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=8
export STAREXEC_CONTAINER_DEFAULT_WALLCLOCK_LIMIT=3600
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=2
```

### Monitoring

Track these metrics:

| Metric | Healthy Range | Action if Out of Range |
|--------|---------------|------------------------|
| Queue depth | 50-100 | Adjust `NUM_JOB_PAIRS_AT_A_TIME` |
| CPU utilization | 70-90% | Adjust `CPU_LIMIT` |
| Memory utilization | 60-80% | Adjust `MEMORY_MB` |
| Job timeout rate | < 1% | Increase `WALLCLOCK_LIMIT` |
| OOM kill rate | 0% | Increase `MEMORY_MB` |

---

## Troubleshooting

### Jobs Stuck in ENQUEUED

```bash
# Check backend logs
make logs-app | grep Backend

# Verify backend is initialized
grep "Backend initialized" logs/starexec.log

# Check configuration
echo $STAREXEC_BACKEND_TYPE
```

### Container Jobs Never Complete

```bash
# Check ContainerJobMonitor is running
make logs-app | grep ContainerJobMonitor

# Check for running containers
podman ps | grep starexec-job

# Check job output
ls -la /starexec/output/<job_id>/
```

### Out of Memory Errors

```bash
# Check for OOM kills
podman events | grep oom

# Increase memory limit
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=8192

# Or reduce concurrent jobs
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=3
```

### Backend Type Not Recognized

```bash
# Valid values
export STAREXEC_BACKEND_TYPE=local      # LocalBackend
export STAREXEC_BACKEND_TYPE=podman     # PodmanBackend
export STAREXEC_BACKEND_TYPE=kubernetes # KubernetesBackend
export STAREXEC_BACKEND_TYPE=sge        # SGEBackend
export STAREXEC_BACKEND_TYPE=oar        # OARBackend
```

---

## Migration Paths

### Local → Podman

1. Ensure Podman is installed and configured
2. Change backend type:
   ```bash
   export STAREXEC_BACKEND_TYPE=podman
   ```
3. Restart application
4. Verify containers are created:
   ```bash
   podman ps | grep starexec
   ```

### Podman → Kubernetes

1. Set up Kubernetes cluster with StarExec Helm chart
2. Configure shared storage (PVC)
3. Update backend configuration:
   ```yaml
   backend:
     type: kubernetes
   ```
4. Deploy with Helm
5. Migrate data volumes

### SGE → Podman

1. Document current SGE configuration
2. Set up Podman environment
3. Migrate solver scripts (may need adaptation)
4. Switch backend type
5. Test thoroughly before production cutover

---

## Security Considerations

### Local Backend

⚠️ **NOT SAFE for untrusted code**

Jobs run as the application user with full host access.

### Podman Backend

✅ **Recommended for untrusted code**

- Container isolation via namespaces
- Resource limits enforced by cgroups
- Rootless mode for additional security
- No host access from containers

### Kubernetes Backend

✅ **Safe for untrusted code**

- Pod security policies
- NetworkPolicies for isolation
- Resource quotas
- RBAC for access control

### HPC Backends (SGE/OAR)

⚠️ **Depends on cluster configuration**

- Process isolation only
- Relies on cluster security policies
- Shared filesystem access

---

## Related Documentation

- **[Configuration Reference](CONFIGURATION.md)** - All environment variables
- **[Performance Tuning](PERFORMANCE.md)** - Optimization guide
- **[Deployment Guide](DEPLOYMENT.md)** - Backend setup
- **[Troubleshooting](TROUBLESHOOTING.md)** - Common issues
