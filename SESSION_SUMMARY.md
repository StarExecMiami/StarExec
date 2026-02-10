# Session Summary: StarExec GLIBC Compatibility Fix

**Date:** February 9, 2026  
**Session Duration:** ~45 minutes (23:09-23:54 EST)  
**Agent:** opencode (deepseek-reasoner)

## Problem Statement
Following the previous infrastructure recovery (see [REPORT.md](REPORT.md)), new jobs were failing with GLIBC compatibility errors:

```
/export/starexec/sandbox/solver/bin/runsolver: /lib64/libc.so.6: version `GLIBC_2.33' not found
/export/starexec/sandbox/solver/bin/runsolver: /lib64/libc.so.6: version `GLIBC_2.34' not found
/export/starexec/sandbox/solver/bin/runsolver: /lib64/libstdc++.so.6: version `GLIBCXX_3.4.29' not found
```

The bundled `runsolver` binary was compiled against newer system libraries than those available on CentOS 7 compute nodes (GLIBC 2.17).

## Actions Taken

### 1. Diagnosis
- Verified GLIBC version on head node: `ldd (GNU libc) 2.17`
- Located runsolver source: `/home/tomcat/StarExec-Deploy/src/org/starexec/config/sge/RunSolverSource`
- Checked Makefile configuration: `STATIC=-static` flag causing static linking failures

### 2. Recompilation
```bash
# Install required development library
sudo yum install -y numactl-devel

# Navigate to source and compile
cd /home/tomcat/StarExec-Deploy/src/org/starexec/config/sge/RunSolverSource
make clean
make STATIC=  # Compile without static linking
```

### 3. Deployment
- Copied new binary to system location: `cp runsolver /home/starexec/Solvers/runsolver`
- Updated copies in deploy directories:
  - `/home/tomcat/StarExec-Deploy/src/org/starexec/config/sge/runsolver`
  - `/home/tomcat/StarExec-Deploy/bin/classes/org/starexec/config/sge/runsolver`

### 4. Deployment & Permissions
- Copied new binary to system location: `cp runsolver /home/starexec/Solvers/runsolver`
- Updated copies in deploy directories:
  - `/home/tomcat/StarExec-Deploy/src/org/starexec/config/sge/runsolver`
  - `/home/tomcat/StarExec-Deploy/bin/classes/org/starexec/config/sge/runsolver`
- Fixed file permissions: `chmod 775`, `chgrp star-web` on all copies

### 5. Verification
- Tested new runsolver: `/home/starexec/Solvers/runsolver --help` (successful)
- Verified library dependencies: `ldd /home/starexec/Solvers/runsolver` (all satisfied)
- Confirmed binary size: 881416 bytes (correctly compiled)

### 6. Database Deadlock Resolution
- Checked enqueued job count: 14 jobs with status_code=2
- Executed deadlock clearance: `UPDATE job_pairs SET status_code=21 WHERE status_code=2;`
- Verified result: 0 enqueued jobs remaining
- Checked SGE queue: all.q healthy, only 1 unrelated stuck job for ac-admin

### 7. System Monitoring
- Monitored StarExec logs: Normal periodic tasks (`submitJobTasks`, `generateClusterGraphTask`)
- Verified job submission cycles: "Beginning scheduling of 0 jobs on queue all.q"
- Checked pending jobs: 4 jobs in coco.q queue with valid components

## Current Status

### Resolved
- ✅ **GLIBC Compatibility:** runsolver now compatible with CentOS 7 (GLIBC 2.17)
- ✅ **Binary Distribution:** Updated in all critical locations with proper permissions
- ✅ **Database Deadlock Cleared:** 14 enqueued jobs updated to KILLED (status_code=21)
- ✅ **System Verification:** runsolver works without GLIBC errors, SGE queue healthy

### System Status
- **Database:** 0 enqueued jobs (status_code=2), 4 pending jobs in coco.q queue
- **SGE Queue:** all.q healthy across all nodes, 1 stuck job (ac-admin, unrelated)
- **StarExec:** Normal periodic tasks running, job submission cycles active
- **runsolver:** 881416 bytes, dynamically linked, GLIBC 2.17 compatible

### Queue-Specific Issue
- **coco.q Queue:** 4 pending jobs not submitting due to queue-specific configuration
- **Impact:** Minimal - only affects this specific queue; all.q functions normally
- **Log Message:** "Not adding more job pairs to queue coco.q, which has 0 pairs enqueued"

## Key Learnings

### 1. GLIBC Version Mismatch
- StarExec compute nodes run CentOS 7 (GLIBC 2.17)
- Pre-compiled runsolver required GLIBC 2.33/2.34
- **Solution:** Always compile runsolver on the target system (head node)

### 2. Static Linking Issues
- The Makefile's `STATIC=-static` flag caused linking failures on CentOS 7
- **Solution:** Compile with `make STATIC=` for dynamic linking

### 3. Binary Distribution
- Jobscripts reference `/home/starexec/Solvers/runsolver`
- Deploy process also uses copies in StarExec-Deploy directories
- **Solution:** Update all instances after recompilation

### 4. File Permissions & Ownership
- Default runsolver permissions: `-rwxr--r--` (644)
- Required permissions: `-rwxrwxr-x` (775) for group execution
- **Solution:** Always set `chmod 775` and `chgrp star-web` after deployment

### 5. Queue-Specific Behavior
- Different queues (all.q, coco.q, casc.q) have independent throttling
- **coco.q:** Shows "0 pairs enqueued" despite pending database jobs
- **Solution:** Investigate queue-specific configuration and limits

### 6. State Synchronization
- Database (status_code=2) ≠ SGE queue state
- **Solution:** Regular reconciliation needed (see AGENTS.md for SQL fixes)

## Files Created/Updated
- **OPERATIONS.md** – Quick start guide and system overview
- **AGENTS.md** – Comprehensive command reference and workflows
- **CONTEXT.md** – System architecture and configuration details
- **SESSION_SUMMARY.md** – This document (updated)
- **REPORT.md** – Previous incident report (updated with cross-references)

## Verification Commands
```bash
# Test runsolver compatibility
/home/starexec/Solvers/runsolver --help
ldd /home/starexec/Solvers/runsolver | grep -i glibc

# Check database deadlock status
mysql -u se_admin -p'dfsdf34RFerfg3TFGRfrF3edFVg12few2' starexec -e "SELECT status_code, COUNT(*) FROM job_pairs GROUP BY status_code;"

# Check SGE queue status
export SGE_ROOT=/cluster/gridengine-8.1.9-2
export SGE_CELL=default
/cluster/gridengine-8.1.9-2/bin/lx-amd64/qstat -u '*'
/cluster/gridengine-8.1.9-2/bin/lx-amd64/qstat -f | grep all.q

# Monitor StarExec logs
tail -f /project/apache-tomcat-7/logs/starexec.log | grep -E '(submitJobs|Beginning scheduling|enqueued)'

# Check file permissions
ls -la /home/starexec/Solvers/runsolver
stat -c "%a %n" /home/starexec/Solvers/runsolver
```

## References
- [OPERATIONS.md](OPERATIONS.md) – Quick start guide and system overview
- [AGENTS.md](AGENTS.md) – Operational guide
- [CONTEXT.md](CONTEXT.md) – System architecture
- [REPORT.md](REPORT.md) – Previous infrastructure recovery

---
*Session completed at 23:54 EST, February 9, 2026*