# Post-Mortem Report: StarExec Infrastructure Recovery
**Date:** February 9, 2026
**Author:** opencode (AI Agent)

## 1. Executive Summary

The StarExec infrastructure experienced a critical outage where compute nodes lost database connectivity, preventing job completion and result reporting. During the remediation process, two secondary blockers were identified: a deployment build failure due to missing environment paths and a Grid Engine (SGE) queue deadlock caused by state desynchronization.

All issues have been resolved. The configuration was updated to use internal networking, the build process was fixed, and the job queue was manually unblocked. System functionality has been verified with successful job executions (e.g., Job Pair ID 137269907).

## 2. Detailed Issue Breakdown

### Issue 1: Compute Node Database Connectivity Failure
*   **Symptom:** Jobs running on compute nodes failed to report results, logging `ERROR 2003 (HY000): Can't connect to MySQL server on 'starexec.miami'`.
*   **Root Cause:** The system configuration (`overrides.properties`) pointed compute nodes to the head node's public hostname (`starexec.miami`). Compute nodes reside on a private internal network (`10.10.x.x`) and likely have firewall rules or routing preventing access to the public interface.
*   **Resolution:**
    *   Modified `/home/tomcat/StarExec-Deploy/build/overrides.properties`.
    *   Changed `Cluster.DB.Url` and `Report.Host` from `starexec.miami` to the internal IP address `10.10.1.254`.

### Issue 2: Deployment Build Failure
*   **Symptom:** During the deployment of the configuration fix, `ant` failed at the CSS compilation step with: `Cannot run program "sass": error=2, No such file or directory`.
*   **Root Cause:** The `ant` build was executed via `sudo`, which often resets the `$PATH` environment variable for security. The `sass` executable, located in `/usr/local/bin`, was not in the secure path visible to the build script.
*   **Resolution:**
    *   Edited `/home/tomcat/StarExec-Deploy/build/build-css.xml`.
    *   Hardcoded the `executable` attribute for `sass` and `sass-convert` to their absolute paths: `/usr/local/bin/sass` and `/usr/local/bin/sass-convert`.

### Issue 3: SGE Queue Deadlock (Phantom Jobs)
*   **Symptom:** Despite the deployment, no new jobs were being submitted to the Grid Engine. The logs showed: `Not adding more job pairs to queue all.q, which has 256 pairs enqueued`.
*   **Root Cause:** A state desynchronization occurred between the StarExec database and the SGE backend. The database listed 256 jobs as `ENQUEUED` (Status Code 2), but these jobs did not exist in the SGE queue (likely lost during previous failures). The `JobSubmitter` logic has a throttle limit (256) and refused to submit new work until the "pending" jobs cleared.
*   **Resolution:**
    *   Identified the stuck jobs using SQL queries.
    *   Executed a manual database update to move these phantom jobs to a terminal state:
        ```sql
        UPDATE job_pairs SET status_code=21 WHERE status_code=2;
        ```
    *   This released the lock, allowing the `JobSubmitter` to immediately resume processing the backlog.

## 3. Verification

Verification was confirmed via:
1.  **Log Analysis:** `starexec.log` showed immediate resumption of `qsub` commands after the database update.
2.  **SGE Status:** `qstat` confirmed jobs transitioning from `qw` (Queue Wait) to `r` (Running).
3.  **Job Execution:** A sample job (Pair ID: **137269907**) executed successfully on node `n025`. The logs confirm:
    *   Successful connection to the database IP (`10.10.1.254`).
    *   Successful file transfers.
    *   Solver execution (`E---3.3.0`) and result parsing (`# SZS status Unsatisfiable`).

## 4. Future Considerations & Recommendations

1.  **Internal Networking:** Ensure all cluster communications (NFS, Database, SGE) strictly use the `10.10.x.x` private network to avoid dependency on public DNS or external firewall traversal.
2.  **State Reconciliation:** Implement a periodic "sanity check" script or cron job that compares the Database's "Enqueued" list against `qstat`. If a job is enqueued in DB but missing from SGE for >1 hour, alert an admin or auto-fail the job.
3.  **Build Robustness:** Update the `build.xml` scripts to either dynamically detect executable paths or define them in a properties file (`build.properties`) rather than relying on the shell environment's `$PATH`, which is unreliable in `sudo` contexts.

## Related Documentation
- **[OPERATIONS.md](OPERATIONS.md)** – Quick start guide and system overview
- **[AGENTS.md](AGENTS.md)** – Command reference and operational workflows
- **[CONTEXT.md](CONTEXT.md)** – System architecture and configuration details
- **[SESSION_SUMMARY.md](SESSION_SUMMARY.md)** – GLIBC compatibility fix (subsequent incident)
