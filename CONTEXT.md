# StarExec System Context

## Overview
StarExec is a web-based service for large-scale comparative evaluation of automated theorem provers and SAT solvers. The system runs on a cluster at the University of Miami (`starexec.acorn.miami.edu`).

## Architecture Components

### 1. Frontend Web Interface
- **Technology:** Java EE (JSP/Servlets), Tomcat 7
- **Location:** `/project/apache-tomcat-7/webapps/starexec`
- **Access:** https://starexec.acorn.miami.edu/starexec

### 2. Backend Processing
- **Job Management:** Java services running in Tomcat
- **Database:** MySQL (starexec database)
- **Queue System:** Sun Grid Engine (SGE) 8.1.9
- **Compute Nodes:** 32 nodes (n001–n032) on private network 10.10.2.x

### 3. Storage Hierarchy
```
/home/starexec/
├── Solvers/           # Solver binaries uploaded by users
├── Benchmarks/        # TPTP benchmark files
├── jobin/            # Generated job scripts
└── joboutput/        # Job results and logs

/export/starexec/     # Workspace on compute nodes
└── sandbox[1-2]/     # Isolated execution directories
```

### 4. Job Execution Flow
1. User submits job via web interface
2. StarExec creates job pair records in database (status=1)
3. JobSubmitter (periodic task) picks pending jobs, creates SGE job scripts
4. SGE dispatches jobs to compute nodes
5. Compute nodes run `runsolver` to monitor solver execution
6. Results are reported back to database via internal API
7. Web interface displays results

### 5. Critical Configuration Files
- **Database Connection:** `/home/tomcat/StarExec-Deploy/build/overrides.properties`
  - `Cluster.DB.Url=jdbc:mysql://10.10.1.254:3306/starexec`
  - `Report.Host=10.10.1.254`
- **SGE Scripts:** `/home/tomcat/StarExec-Deploy/src/org/starexec/config/sge/`
  - `jobscript` – SGE job template
  - `functions.bash` – Common job functions
  - `runsolver` – Resource monitoring tool
- **Build Configuration:** `/home/tomcat/StarExec-Deploy/build/build-css.xml`
  - Contains hardcoded sass paths

### 6. Common Failure Points

#### Database Connectivity
- **Symptom:** `ERROR 2003 (HY000): Can't connect to MySQL server`
- **Root Cause:** Compute nodes cannot resolve/access public hostname
- **Fix:** Use internal IP (`10.10.1.254`) in all configurations

#### SGE Queue Deadlock
- **Symptom:** JobSubmitter stops at 256 enqueued jobs
- **Root Cause:** Phantom jobs in database (status=2) not in SGE
- **Fix:** Update database: `UPDATE job_pairs SET status_code=21 WHERE status_code=2;`

#### GLIBC Compatibility
- **Symptom:** `/lib64/libc.so.6: version `GLIBC_2.33' not found`
- **Root Cause:** runsolver compiled on newer system than CentOS 7
- **Fix:** Recompile runsolver from source on head node

#### File Permission Issues
- **Symptom:** `bash: /home/starexec/Solvers/runsolver: Permission denied`
- **Root Cause:** Default permissions (644) prevent group execution
- **Fix:** Set `chmod 775` and `chgrp star-web` on all runsolver copies

#### Queue-Specific Deadlocks
- **Symptom:** `Not adding more job pairs to queue coco.q, which has 0 pairs enqueued`
- **Root Cause:** Queue-specific throttling or configuration limits
- **Fix:** Investigate queue configuration and database job associations

#### Job Completion Deadlock (99% Stuck)
- **Symptom:** Job remains in "RUNNING" state at 99% completion despite all pairs being in terminal states.
- **Root Cause:** Some terminal status updates (like Status 9: Submit Fail) bypassed the `job_pair_completion` table, preventing the `UpdatePairStatus` procedure from triggering the job's `completed` timestamp.
- **Fix:** Manually register missing pairs in the completion table by calling `CALL UpdatePairStatus(pair_id, status_code);`.
- **Code Fix:** Updated `UpdatePairStatus` to include all terminal statuses (21, 23, 24, 25, 26) and handle all code paths correctly.

### 7. Monitoring & Diagnostics


#### Essential Logs
- **Application Logs:** `/project/apache-tomcat-7/logs/starexec.log`
- **Job Outputs:** `/home/starexec/joboutput/logs/[space]/[user]/[job]/`
- **SGE Logs:** `/cluster/gridengine-8.1.9-2/default/spool/`

#### Health Checks
```bash
# Database connectivity
mysql -u se_admin -p'password' starexec -e "SELECT 1"

# SGE status
export SGE_ROOT=/cluster/gridengine-8.1.9-2
export SGE_CELL=default
/cluster/gridengine-8.1.9-2/bin/lx-amd64/qstat -u '*'

# Tomcat status
ps aux | grep -i tomcat | grep -v grep

# Disk space
df -h /home/starexec /export/starexec
```

### 8. Recovery Procedures

#### Complete System Restart
1. Stop all SGE jobs: `qdel -u tomcat`
2. Stop Tomcat: `kill <tomcat_pid>` or `systemctl stop tomcat`
3. Clear phantom jobs from database
4. Start Tomcat: `sudo -u tomcat /project/apache-tomcat-7/bin/startup.sh`
5. Verify job submission resumes

#### runsolver Recompilation
1. Navigate to source: `cd /home/tomcat/StarExec-Deploy/src/org/starexec/config/sge/RunSolverSource`
2. Clean: `make clean`
3. Compile: `make STATIC=`
4. Deploy: `cp runsolver /home/starexec/Solvers/runsolver`
5. Set permissions: `chmod 775 /home/starexec/Solvers/runsolver && chgrp star-web /home/starexec/Solvers/runsolver`
6. Update copies in deploy directories (with same permissions)
7. Verify: `/home/starexec/Solvers/runsolver --help`

### 9. User Management
- **Primary Admin:** `ancaicedou` (SSH key authentication)
- **Service User:** `tomcat` (runs web application)
- **Sandbox Users:** `sandbox`, `sandbox2` (isolated job execution)

### 10. Network Topology
```
Internet
    |
[Public Interface]
    |
starexec.acorn.miami.edu (10.10.1.254)
    | (Private Network 10.10.2.0/24)
    |
Compute Nodes (n001–n032)
```

**Critical:** All internal communication must use the 10.10.x.x addresses, not public hostnames.

---
*Last Updated: February 9, 2026 (23:55 EST)*  
*Maintained by: opencode AI agent*  
*Based on GLIBC compatibility fix and database deadlock resolution*

## Related Documentation
- **[OPERATIONS.md](OPERATIONS.md)** – Quick start guide and system overview
- **[AGENTS.md](AGENTS.md)** – Command reference and operational workflows
- **[SESSION_SUMMARY.md](SESSION_SUMMARY.md)** – GLIBC compatibility fix details
- **[REPORT.md](REPORT.md)** – Previous infrastructure recovery report