# StarExec Agent Guide

This document provides essential context, commands, and workflows for maintaining the StarExec cluster at `starexec.acorn.miami.edu`.

## System Context

### Access & Authentication
- **Head Node:** `starexec.acorn.miami.edu`
- **User:** `ancaicedou` (primary), `tomcat` (service user)
- **SSH Key:** `~/.ssh/id_ed25519` (used for access)
- **sudo Password:** `ILoveTPTP!`
- **Database Credentials:**
  - User: `se_admin`
  - Password: `dfsdf34RFerfg3TFGRfrF3edFVg12few2`

### Key Directories
- **Deployment:** `/home/tomcat/StarExec-Deploy`
- **Logs:** `/project/apache-tomcat-7/logs/starexec.log`
- **SGE Root:** `/cluster/gridengine-8.1.9-2`
- **Shared Storage:**
  - `/home/starexec/Solvers` – solver binaries
  - `/home/starexec/Benchmarks` – benchmark files
  - `/home/starexec/jobin` – job input scripts
  - `/home/starexec/joboutput` – job output files
  - `/export/starexec` – compute node workspace

### Network Configuration
- **Internal IP:** `10.10.1.254` (head node internal address)
- **Compute Nodes:** `n001.cluster.edu` to `n032.cluster.edu` (private network `10.10.2.x`)
- **Critical:** All cluster-internal communication must use internal IPs (not public hostnames)

## Common Commands

### SSH Access
```bash
ssh -i ~/.ssh/id_ed25519 ancaicedou@starexec.acorn.miami.edu
```

### SGE Queue Management
```bash
# Check queue status
export SGE_ROOT=/cluster/gridengine-8.1.9-2
export SGE_CELL=default
/cluster/gridengine-8.1.9-2/bin/lx-amd64/qstat -u '*'

# Delete all tomcat jobs (requires sudo)
echo 'ILoveTPTP!' | sudo -S bash -c 'export SGE_ROOT=/cluster/gridengine-8.1.9-2; export SGE_CELL=default; /cluster/gridengine-8.1.9-2/bin/lx-amd64/qdel -u tomcat'

# Force delete specific jobs
echo 'ILoveTPTP!' | sudo -S bash -c 'export SGE_ROOT=/cluster/gridengine-8.1.9-2; export SGE_CELL=default; /cluster/gridengine-8.1.9-2/bin/lx-amd64/qdel -f 7871234'
```

### Database Operations
```bash
# Connect to MySQL
mysql -u se_admin -p'dfsdf34RFerfg3TFGRfrF3edFVg12few2' starexec

# Check job pair status distribution
SELECT status_code, COUNT(*) FROM job_pairs GROUP BY status_code;

# Find enqueued jobs (status_code=2) - these can cause deadlocks
SELECT COUNT(*) FROM job_pairs WHERE status_code = 2;

# Kill phantom enqueued jobs (deadlock resolution)
UPDATE job_pairs SET status_code=21 WHERE status_code=2;

# Reset enqueued jobs to pending
UPDATE job_pairs SET status_code=1 WHERE status_code=2;
```

### Tomcat Management
```bash
# Check Tomcat process
ps aux | grep -i tomcat | grep -v grep

# Stop Tomcat (if needed)
echo 'ILoveTPTP!' | sudo -S kill <tomcat_pid>

# Start Tomcat via startup script
echo 'ILoveTPTP!' | sudo -S su - tomcat -c '/project/apache-tomcat-7/bin/startup.sh'
```

### Log Monitoring
```bash
# Tail StarExec logs
tail -f /project/apache-tomcat-7/logs/starexec.log

# Check recent job output
find /home/starexec/joboutput -name '*.txt' -mmin -5 | head -5
tail -30 /home/starexec/joboutput/logs/6426/89498/137268008.txt
```

### Build & Deployment
```bash
# Deploy changes (as tomcat user)
cd /home/tomcat/StarExec-Deploy
sudo -u tomcat ant
sudo -u tomcat ./script/soft-deploy.sh

# Check runsolver compatibility
/home/starexec/Solvers/runsolver --help

# Recompile runsolver if GLIBC issues arise
cd /home/tomcat/StarExec-Deploy/src/org/starexec/config/sge/RunSolverSource
make clean
make STATIC=
cp runsolver /home/starexec/Solvers/runsolver
cp runsolver /home/tomcat/StarExec-Deploy/src/org/starexec/config/sge/runsolver
cp runsolver /home/tomcat/StarExec-Deploy/bin/classes/org/starexec/config/sge/runsolver

# Fix permissions after deployment
chmod 775 /home/starexec/Solvers/runsolver
chgrp star-web /home/starexec/Solvers/runsolver
chmod 775 /home/tomcat/StarExec-Deploy/src/org/starexec/config/sge/runsolver
chgrp star-web /home/tomcat/StarExec-Deploy/src/org/starexec/config/sge/runsolver
chmod 775 /home/tomcat/StarExec-Deploy/bin/classes/org/starexec/config/sge/runsolver
chgrp star-web /home/tomcat/StarExec-Deploy/bin/classes/org/starexec/config/sge/runsolver
```

## Troubleshooting Workflows

### Database Connectivity Issues
1. **Symptom:** `ERROR 2003 (HY000): Can't connect to MySQL server`
2. **Check:** `/home/tomcat/StarExec-Deploy/build/overrides.properties`
3. **Fix:** Ensure `Cluster.DB.Url` and `Report.Host` point to `10.10.1.254` (not `starexec.miami`)

### Job Completion Deadlock (99% Stuck)
1. **Symptom:** Job stuck at 99% despite all pairs appearing finished.
2. **Check:** `SELECT COUNT(*) FROM job_pair_completion WHERE pair_id IN (SELECT id FROM job_pairs WHERE job_id = <ID>);`
3. **Compare:** compare count to `total_pairs` in `jobs` table.
4. **Fix:** For missing pairs, run `CALL UpdatePairStatus(pair_id, status_code);`. This registers the pair in the completion table and triggers job completion logic.

## Status Codes Reference
- `1`: PENDING
- `2`: ENQUEUED
- `4`: RUNNING
- `7`: COMPLETE
- `8`: SOLVER ERROR
- `9`: SUBMIT FAIL
- `10`: RESULTS ERROR
- `13`: ENVIRONMENT ERROR
- `14`: TIMEOUT (WALL)
- `15`: TIMEOUT (CPU)
- `16`: FILE WRITE EXCEEDED
- `17`: MEMOUT
- `18`: GENERAL ERROR
- `21`: KILLED
- `25`: PRE-PROCESSOR ERROR
- `26`: POST-PROCESSOR ERROR

## Recent Fixes (February 2026)
1. **Database Connectivity:** Updated `overrides.properties` to use internal IP `10.10.1.254` (Feb 9)
2. **Build Fix:** Hardcoded sass paths in `build-css.xml` (Feb 9)
3. **Queue Deadlock:** Killed 256 phantom enqueued jobs via database update (Feb 9)
4. **runsolver GLIBC:** Recompiled runsolver for CentOS 7 compatibility (Feb 9)
5. **Job Completion Bug:** Fixed `UpdatePairStatus` SQL procedure to handle terminal statuses (21, 23-26) and correctly identify active pairs (Feb 10)
6. **Phantom Pairs:** Manually cleared stuck jobs 6428, 6413, 6416 by triggering `UpdatePairStatus` (Feb 10)

## Important Notes
- Always use internal IPs (`10.10.1.254`) for cluster communications
- The `JobSubmitter` throttles at 256 enqueued jobs – monitor status_code=2
- GLIBC version mismatch is common with pre‑compiled runsolver binaries
- Tomcat runs as user `tomcat` – use `sudo -u tomcat` for deployment operations
- **File Permissions:** runsolver requires 775 permissions and star-web group ownership
- **Queue-Specific Limits:** Different queues (all.q, coco.q, casc.q) have independent throttling
- **SSH Authentication:** Use `ssh -o BatchMode=yes` for non-interactive commands

## Related Documentation
- **[OPERATIONS.md](OPERATIONS.md)** – Quick start guide and system overview
- **[CONTEXT.md](CONTEXT.md)** – System architecture and configuration details
- **[SESSION_SUMMARY.md](SESSION_SUMMARY.md)** – GLIBC compatibility fix session summary  
- **[REPORT.md](REPORT.md)** – Previous infrastructure recovery report
- **SSH Configuration:** `~/.ssh/id_ed25519` key for `ancaicedou@starexec.acorn.miami.edu`