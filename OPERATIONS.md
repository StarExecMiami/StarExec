# StarExec Cluster Operations Guide

## Overview
This directory contains operational documentation for maintaining the StarExec cluster at `starexec.acorn.miami.edu` (University of Miami). The system recently recovered from multiple failures and requires careful monitoring.

## Quick Start for AI Agents

### 1. System Context
- **Head Node:** `starexec.acorn.miami.edu`
- **User:** `ancaicedou` (SSH key: `~/.ssh/id_ed25519`)
- **Service User:** `tomcat` (runs StarExec application)
- **Database:** MySQL, user `se_admin`, password `dfsdf34RFerfg3TFGRfrF3edFVg12few2`
- **Internal Network:** Head node IP `10.10.1.254`, compute nodes on `10.10.2.x`

### 2. Recent Issues & Fixes
1. **Database Connectivity:** Fixed by updating `overrides.properties` to use internal IP `10.10.1.254`
2. **SGE Queue Deadlock:** Cleared 256 phantom jobs via database update
3. **GLIBC Compatibility:** Recompiled `runsolver` for CentOS 7 (GLIBC 2.17)
4. **File Permissions:** Fixed runsolver permissions (775) and group ownership (star-web)
5. **Database Deadlock:** Cleared 14 enqueued jobs (status_code=2 → 21)

### 3. Critical Verification Commands
```bash
# SSH access
ssh -i ~/.ssh/id_ed25519 -o BatchMode=yes ancaicedou@starexec.acorn.miami.edu

# Check runsolver compatibility
/home/starexec/Solvers/runsolver --help

# Check database job status
mysql -u se_admin -p'dfsdf34RFerfg3TFGRfrF3edFVg12few2' starexec -e "SELECT status_code, COUNT(*) FROM job_pairs GROUP BY status_code;"

# Check SGE queue
export SGE_ROOT=/cluster/gridengine-8.1.9-2
export SGE_CELL=default
/cluster/gridengine-8.1.9-2/bin/lx-amd64/qstat -u '*'

# Monitor logs
tail -f /project/apache-tomcat-7/logs/starexec.log
```

### 4. Common Problems & Solutions

#### GLIBC Compatibility Errors
- **Symptom:** `/lib64/libc.so.6: version GLIBC_2.33 not found`
- **Fix:** Recompile runsolver:
  ```bash
  cd /home/tomcat/StarExec-Deploy/src/org/starexec/config/sge/RunSolverSource
  make clean
  make STATIC=
  cp runsolver /home/starexec/Solvers/runsolver
  chmod 775 /home/starexec/Solvers/runsolver
  chgrp star-web /home/starexec/Solvers/runsolver
  ```

#### Database Deadlock
- **Symptom:** `Not adding more job pairs to queue all.q, which has 256 pairs enqueued`
- **Fix:** Clear phantom enqueued jobs:
  ```sql
  UPDATE job_pairs SET status_code=21 WHERE status_code=2;
  ```

#### File Permission Issues
- **Symptom:** `bash: /home/starexec/Solvers/runsolver: Permission denied`
- **Fix:** Set correct permissions:
  ```bash
  chmod 775 /home/starexec/Solvers/runsolver
  chgrp star-web /home/starexec/Solvers/runsolver
  ```

### 5. Documentation Structure

1. **[AGENTS.md](AGENTS.md)** – Comprehensive command reference and workflows
2. **[CONTEXT.md](CONTEXT.md)** – System architecture and configuration details  
3. **[SESSION_SUMMARY.md](SESSION_SUMMARY.md)** – GLIBC compatibility fix session summary
4. **[REPORT.md](REPORT.md)** – Previous infrastructure recovery report

### 6. Current System Status (as of February 9, 2026, 23:55 EST)
- ✅ **GLIBC Compatibility:** runsolver works on CentOS 7 (GLIBC 2.17)
- ✅ **Database Deadlock:** 0 enqueued jobs (status_code=2)
- ✅ **SGE Queue:** all.q healthy across all nodes
- ⚠️ **Queue-Specific:** 4 pending jobs in coco.q not submitting (minimal impact)
- ✅ **Job Submission:** StarExec cycles active, ready for new jobs

### 7. Next Recommended Actions
1. Monitor new job submissions for GLIBC errors
2. Investigate coco.q queue configuration if users report issues
3. Consider implementing periodic deadlock detection script

---

*Maintained by: opencode AI agent*  
*Last Updated: February 9, 2026 (23:55 EST)*