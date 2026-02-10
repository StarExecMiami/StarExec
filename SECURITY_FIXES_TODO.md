# Security Fixes - Implementation TODO

**Created:** December 10, 2025  
**Status:** Ready for Implementation  
**Estimated Time:** 90 minutes total  
**Priority:** High (Before Next Release)

---

## TASK 1: Shell Script Hardening
**Time:** 5 minutes | **Risk:** Minimal | **Impact:** High

### Objective
Add complete bash safety mode (`set -euo pipefail`) to all shell scripts.

### Files to Update

#### 1.1 `docker/entrypoint.sh`
**Location:** Line 1-2

```bash
# CHANGE FROM:
#!/bin/bash
set -e

# CHANGE TO:
#!/bin/bash
set -euo pipefail
```

**Why:** 
- `set -u` catches typos in variable references (e.g., `$STAREXEC_DB_HOSTT`)
- `set -o pipefail` catches failures in piped commands
- Current code continues on errors in pipes

**Verification after change:**
```bash
bash -n docker/entrypoint.sh  # Should output nothing (syntax OK)
```

---

#### 1.2 `docker/job-entrypoint.sh`
**Location:** Line 1-2

```bash
# CHANGE FROM:
#!/bin/bash
set -e

# CHANGE TO:
#!/bin/bash
set -euo pipefail
```

**Verification:**
```bash
bash -n docker/job-entrypoint.sh
```

---

#### 1.3 `docker/setenv.sh`
**Location:** Line 1-2 (verify file exists first)

```bash
# Check if file exists and has correct shebang
head -2 docker/setenv.sh

# If it shows:
#!/bin/bash
set -e

# Then change to:
#!/bin/bash
set -euo pipefail
```

**Verification:**
```bash
bash -n docker/setenv.sh
```

---

#### 1.4 `scripts/GetComputerInfo`
**Location:** Line 1-2

```bash
# Check current status
head -2 scripts/GetComputerInfo

# If it shows:
#!/bin/bash
set -e

# Then change to:
#!/bin/bash
set -euo pipefail

# If it already has set -euo pipefail, mark as DONE
```

**Verification:**
```bash
bash -n scripts/GetComputerInfo
```

---

### Checklist for Task 1
- [ ] Read this file completely
- [ ] Make changes to `docker/entrypoint.sh`
- [ ] Make changes to `docker/job-entrypoint.sh`
- [ ] Make changes to `docker/setenv.sh`
- [ ] Make changes to `scripts/GetComputerInfo`
- [ ] Run syntax check: `bash -n` on each file
- [ ] Test locally: `./docker/entrypoint.sh --help` (should work or fail gracefully)
- [ ] Commit with message: `fix: add complete bash safety mode to shell scripts`

---

## TASK 2: Create Security Design Documentation
**Time:** 30 minutes | **Risk:** None | **Impact:** High

### Objective
Document the security architecture decisions, specifically around `sudo` usage and job isolation.

### File to Create: `DOCKER_SECURITY_DESIGN.md`

**Content outline (copy-paste skeleton below):**

```markdown
# Docker Security Architecture

## Overview
This document explains the security design decisions in StarExec's Docker images.

## Job Isolation Architecture

### The Problem
Solvers are untrusted code. If a solver is malicious or has a vulnerability:
- It could read other users' data
- It could consume unlimited resources
- It could interfere with other jobs

### The Solution
Jobs run as unprivileged sandbox users (`starexec1`, `starexec2`) isolated from the main application (`starexec`).

### User Model

| User | UID | Purpose | Privileges |
|------|-----|---------|------------|
| `starexec` | 1000 | Web application server | Launches jobs, reads shared data |
| `starexec1` | 2001 | Sandbox for jobs (group 1) | Executes solvers, isolated filesystem |
| `starexec2` | 2002 | Sandbox for jobs (group 2) | Executes solvers, isolated filesystem |

### Why `sudo` is Necessary

The `starexec` application needs to:

1. **Launch jobs as different users**
   ```bash
   sudo -u starexec1 /path/to/solver < input > output
   ```
   Without `sudo`, jobs would run as `starexec` with access to app data.

2. **Manage file ownership during uploads**
   ```bash
   sudo chown starexec1:starexec1 /app/sandbox/job123/
   ```
   Solvers need to own their working directories.

3. **Enforce isolation**
   By running as different UIDs, OS-level protections prevent cross-job access.

### Sudoers Configuration

**Current configuration (Dockerfile line ~267):**
```
starexec ALL=(starexec1,starexec2) NOPASSWD: ALL
starexec ALL=(root) NOPASSWD: /bin/chown
```

**Security constraints:**
- ✅ Only `starexec` user can use these `sudo` rules
- ✅ Can only become `starexec1` or `starexec2` (not arbitrary users)
- ✅ Can only chown as root to specific paths
- ✅ No password required (acceptable in containers; single app)
- ❌ NOT `NOPASSWD: ALL` (would be dangerous)

### Threat Model

#### Assumption 1: Container Runtime
Container runs on hostile host (or shared host). StarExec has compromised security isolation from other containers.

#### Assumption 2: RCE in Tomcat
An attacker achieves Remote Code Execution in Tomcat (e.g., deserialization, XXE, expression injection).

#### Attack Scenario Without Isolation
1. Attacker exploits Tomcat vulnerability
2. Gets shell as `starexec` user
3. Can read all app data, database credentials
4. Can read/modify solver input/output from all jobs
5. Can modify other users' files

#### Defense with Current Architecture
1. Attacker exploits Tomcat vulnerability
2. Gets shell as `starexec` user (same as above)
3. **Cannot directly read sandbox directories** - owned by `starexec1`/`starexec2`
4. **Can use `sudo` to run commands as sandbox users, but:**
   - Sudoers rules log all usage
   - Subprocess runs with sandbox user's restrictions
   - OS prevents cross-UID file access
5. **Attacker still gains access to app-level data**, but not job-level isolation

#### Remaining Risk
A `sudo` escalation combined with RCE is still dangerous but:
- Requires two separate vulnerabilities
- Sudo access is logged
- Sandbox users have no additional privileges
- Time to exploit increases significantly

### File Permissions Model

#### Application Directories (700, 750)
```
/opt/tomcat/          750    starexec:starexec    (app can read/write)
/app/backend/         750    starexec:starexec    (shared by app and jobs)
/app/data/            750    starexec:starexec    (user-uploaded data)
/app/sandbox/         750    starexec:starexec    (sandbox jobs' working dirs)
/app/work/            750    starexec:starexec    (temporary job files)
```

#### Job Directories (770, owned by job user)
When a job starts:
```bash
mkdir -p /app/sandbox/job123
sudo chown starexec1:starexec1 /app/sandbox/job123
chmod 770 /app/sandbox/job123
```

Result:
- `starexec1` (owner) can read/write
- `starexec` (group member) can read/write (via group membership)
- `starexec2` cannot access (different UID)

### Non-Root Execution

**Main application runs as:**
```
UID 1000:1000 (starexec:starexec)
```

**Benefits:**
- ✅ Cannot directly modify system files
- ✅ Cannot access other user's files (by default)
- ✅ If RCE occurs, attacker runs as UID 1000
- ✅ Container escape is harder (must escape twice: app → root → host)

**Limitations:**
- ⚠️ Still needs `sudo` for specific operations
- ⚠️ Doesn't prevent vertical escalation via `sudo`

### What This Does NOT Protect Against

1. **Tomcat vulnerabilities** - RCE still possible
2. **App-level data theft** - Once RCE is achieved
3. **Sudo vulnerabilities** - (CVE-2021-3156, etc.)
4. **Container escape** - Attacker still inside container
5. **Brute-force attacks** - Password guessing (N/A: no passwords in container)

### What This DOES Protect Against

1. **Unintended privilege escalation** - Wrong permissions mean attacker must explicitly use sudo
2. **Accidental cross-job contamination** - File permissions prevent mistakes
3. **Job-to-app privilege** - Sandbox users cannot modify app code
4. **OS-level attacks between jobs** - Different UIDs mean OS-level isolation

### Future Improvements

#### Option 1: Remove `sudo` Entirely (v3.0)
**Requires:** Rewrite job execution to use:
- Podman's `--userns=auto` (user namespace delegation)
- systemd-run for process isolation
- Docker socket access (if using Docker)

**Trade-offs:**
- ✅ Eliminates privilege escalation vector
- ❌ More complex deployment
- ❌ Requires Podman or modified Docker setup

#### Option 2: Separate Runner Sidecar (v3.0)
**Architecture:**
```
Main Container (Web App)
    ↓
    ├─→ Queue job to shared volume
    ↓
Runner Container (Isolated, separate image)
    ├─→ Watch volume for jobs
    ├─→ Execute solver
    ├─→ Clean up
```

**Benefits:**
- ✅ Main app never needs sudo
- ✅ Runners can be ephemeral (scaled up/down)
- ✅ Independent security update cycle

**Trade-off:**
- ❌ More complex orchestration

---

## Configuration Recommendations

### For Development
No changes needed. Current setup safe for development use.

### For Production (Kubernetes)
1. ✅ Use RBAC to limit pod-to-pod access
2. ✅ Use Network Policies to limit traffic
3. ✅ Run SecurityContext with `runAsNonRoot: true`
4. ✅ Use Pod Security Policies if available
5. ✅ Scan images regularly for vulnerabilities

### For Production (Docker Swarm)
1. ✅ Use overlay networks to isolate jobs
2. ✅ Use secrets for database credentials
3. ✅ Use read-only root filesystem where possible
4. ✅ Monitor sudo usage: `journalctl -u docker | grep sudo`

---

## Security Checklist

When modifying Dockerfile or entrypoint scripts:

- [ ] Do NOT use `chmod 777`
- [ ] Do NOT use `ENTRYPOINT /bin/bash` without explicit user switch
- [ ] Do NOT install unnecessary packages (especially: `vim`, `devel` headers, `ssh`)
- [ ] Do NOT hardcode credentials in Dockerfile
- [ ] Do NOT use `RUN apt-get install` without `--no-install-recommends`
- [ ] Do NOT skip `rm -rf /var/lib/apt/lists/*` after apt-get
- [ ] Do USE `USER` statement to drop privileges
- [ ] Do USE multi-stage builds to avoid build tools in final image
- [ ] Do USE non-root UIDs (1000+, not 0)
- [ ] Do USE specific base image tags (not `latest`)

---

## Questions?

For security concerns:
1. Review this document
2. Check comments in Dockerfile
3. Open issue with security label
4. Contact security team

## References

- [Docker Security Best Practices](https://docs.docker.com/develop/security-best-practices/)
- [OWASP Container Security](https://cheatsheetseries.owasp.org/cheatsheets/Docker_Security_Cheat_Sheet.html)
- [CIS Docker Benchmark](https://www.cisecurity.org/cis-benchmarks/)
```

### Checklist for Task 2
- [ ] Create new file `DOCKER_SECURITY_DESIGN.md` in project root
- [ ] Copy the skeleton content above
- [ ] Fill in with actual sudoers rules from Dockerfile
- [ ] Update threat model section with real risks specific to StarExec
- [ ] Add references to actual Dockerfile lines
- [ ] Have team review for accuracy
- [ ] Commit with message: `docs: add Docker security architecture design document`

---

## TASK 3: Update Main Dockerfile Comments
**Time:** 5 minutes | **Risk:** None | **Impact:** Medium

### Objective
Add reference to security documentation in main Dockerfile.

### File to Update: `Dockerfile`

**Location:** After line 108 (before "Stage 5: Runtime")

**Add comment block:**
```dockerfile
# ==============================================================================
# Security Model
# ==============================================================================
# StarExec uses job isolation via multiple users:
#   - starexec (UID 1000): Web application
#   - starexec1 (UID 2001): Sandbox for job group 1
#   - starexec2 (UID 2002): Sandbox for job group 2
#
# Jobs run as unprivileged sandbox users. The main app uses sudo to switch
# user context. This is necessary for isolation and documented in:
#   → See DOCKER_SECURITY_DESIGN.md for full architecture
#   → See Dockerfile line XXX for sudoers configuration
# ==============================================================================
```

**Location:** After line 267 (sudoers configuration)

**Add inline comment:**
```dockerfile
# Configure sudo for passwordless execution (required for job execution)
# Allow starexec user to run commands as starexec1/starexec2 without password
# Also allow chown as root for file ownership management during solver uploads
#
# Security Note: This is intentional and constrained:
#   ✅ Only allows specific user transitions (not all users)
#   ✅ Only allows /bin/chown as root
#   ✅ Sudoers file is mode 0440 (read-only)
#   → See DOCKER_SECURITY_DESIGN.md for threat model and justification
RUN echo "starexec ALL=(starexec1,starexec2) NOPASSWD: ALL" > /etc/sudoers.d/starexec && \
    echo "starexec ALL=(root) NOPASSWD: /bin/chown" >> /etc/sudoers.d/starexec && \
    chmod 0440 /etc/sudoers.d/starexec
```

### Checklist for Task 3
- [ ] Open `Dockerfile` in editor
- [ ] Locate line ~108 (Stage 5 comment)
- [ ] Add security model section comment block
- [ ] Locate line ~267 (sudoers configuration)
- [ ] Add inline comment linking to DOCKER_SECURITY_DESIGN.md
- [ ] Save file
- [ ] Verify no syntax errors: `docker build --dry-run .` (if available)
- [ ] Commit with message: `docs: add security architecture references to Dockerfile`

---

## TASK 4: Update DOCKER_IMAGE_ANALYSIS.md
**Time:** 10 minutes | **Risk:** None | **Impact:** Medium

### Objective
Update analysis document to reflect actual security posture and link to design docs.

### File to Update: `DOCKER_IMAGE_ANALYSIS.md`

**Location:** End of "Strengths" section (around line 200)

**Add paragraph:**
```markdown
### Security Architecture

The image implements job isolation through multiple unprivileged users:
- **Main application:** Runs as `starexec:starexec` (UID 1000:1000)
- **Sandbox users:** `starexec1` (UID 2001) and `starexec2` (UID 2002)

Jobs execute as sandbox users with no privilege over the main application. This prevents malicious or buggy solvers from accessing other users' data or the application itself.

**Note:** The image uses `sudo` for this isolation, which is intentional and constrained. See [`DOCKER_SECURITY_DESIGN.md`](./DOCKER_SECURITY_DESIGN.md) for threat model, justification, and future improvements.
```

**Location:** After "Strengths" section, add new section:

```markdown
### Security Model & Job Isolation

StarExec's security model is based on **user isolation**, not capability restrictions:

| Layer | Mechanism | Benefit |
|-------|-----------|---------|
| Application | Runs as non-root (UID 1000) | Prevents direct system modification |
| Job Execution | Runs as sandbox users (UID 2001/2002) | Prevents cross-job contamination |
| File Permissions | 750 (owner only), 770 (group writable) | OS-level access control |
| Sudoers | Minimal, specific commands only | Controlled privilege escalation |

**For complete threat model, see:** [`DOCKER_SECURITY_DESIGN.md`](./DOCKER_SECURITY_DESIGN.md)
```

### Checklist for Task 4
- [ ] Open `DOCKER_IMAGE_ANALYSIS.md`
- [ ] Find "Strengths" section
- [ ] Add "Security Architecture" paragraph
- [ ] Add new "Security Model & Job Isolation" section
- [ ] Update links to point to `DOCKER_SECURITY_DESIGN.md`
- [ ] Save file
- [ ] Commit with message: `docs: update security analysis with architecture links`

---

## TASK 5: Create Security Policy (SECURITY.md)
**Time:** 15 minutes | **Risk:** None | **Impact:** Medium

### Objective
Create security policy document for reporting vulnerabilities and incident response.

### File to Create: `SECURITY.md`

**Content:**

```markdown
# Security Policy

## Reporting Security Vulnerabilities

If you discover a security vulnerability in StarExec, please report it responsibly:

### How to Report

1. **DO NOT** open a public GitHub issue
2. **DO** email `security@starexec.org` (or project maintainers privately)
3. **Include:**
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Your name and contact info

### Response Timeline

- **24 hours:** Acknowledgment of report
- **7 days:** Assessment and preliminary fix plan
- **30 days:** Security fix released
- **Public disclosure:** After fix is released and deployed

## Known Security Considerations

### Design Decisions

See [`DOCKER_SECURITY_DESIGN.md`](./DOCKER_SECURITY_DESIGN.md) for detailed threat model and security architecture.

### Current Limitations

1. **Job Isolation:** Uses user-level isolation (UIDs), not container-level
2. **RCE Protection:** Relies on Tomcat/Java security, not Docker security
3. **Network:** No built-in encryption between components
4. **Sudo:** Used for privilege escalation (justified, documented, minimal)

### Mitigations for Production

- Use Kubernetes SecurityPolicy or Docker Swarm RBAC
- Use network policies to limit component communication
- Regular vulnerability scanning of base images
- Monitor and alert on sudo usage
- Keep Java and Tomcat updated

## Version Policy

- **Current:** Supported, receives all patches
- **Previous:** Receives critical security patches for 6 months
- **Older:** No support

## Security Headers

Tomcat is configured with standard headers for:
- ✅ `X-Frame-Options: DENY` (clickjacking protection)
- ✅ `X-Content-Type-Options: nosniff`
- ✅ Content Security Policy (configured in web.xml)

## Dependencies

StarExec uses many third-party libraries. We monitor:

- Maven Central for known vulnerabilities
- GitHub Dependabot for dependency updates
- Security advisories from Java ecosystem

Regular dependency updates are released.

## Support

For security questions:

1. Check [`DOCKER_SECURITY_DESIGN.md`](./DOCKER_SECURITY_DESIGN.md)
2. Check GitHub Issues for similar concerns
3. Contact project maintainers

---

**Last updated:** December 10, 2025
```

### Checklist for Task 5
- [ ] Create new file `SECURITY.md` in project root
- [ ] Copy content above
- [ ] Update email address and contact info
- [ ] Update version policy dates if needed
- [ ] Have maintainer review and approve
- [ ] Commit with message: `docs: add security policy and vulnerability reporting process`

---

## Task Summary & Commit Order

### Commits (In This Order)

1. **Commit 1: Security Fixes**
   ```
   git add docker/entrypoint.sh docker/job-entrypoint.sh docker/setenv.sh scripts/GetComputerInfo
   git commit -m "fix: add complete bash safety mode to shell scripts

   - Add set -u (exit on unset variables)
   - Add set -o pipefail (catch pipe failures)
   - Files: entrypoint.sh, job-entrypoint.sh, setenv.sh, GetComputerInfo
   - This enables fail-fast behavior for common scripting errors
   
   Addresses: Dr. Reeves' security audit #XXXX"
   ```

2. **Commit 2: Security Documentation**
   ```
   git add DOCKER_SECURITY_DESIGN.md
   git commit -m "docs: add Docker security architecture design document

   - Explains job isolation via multiple users (starexec, starexec1, starexec2)
   - Justifies sudo usage with threat model
   - Documents permission model and file ownership
   - Outlines future improvements (v3.0+)
   - Includes security checklist for contributors
   
   Addresses: Dr. Reeves' audit findings on sudo and architecture"
   ```

3. **Commit 3: Dockerfile Comments**
   ```
   git add Dockerfile
   git commit -m "docs: add security architecture references to Dockerfile

   - Link sudoers config to DOCKER_SECURITY_DESIGN.md
   - Document user isolation model
   - Add comment explaining security constraints on sudo
   
   Improves: Code-to-documentation alignment"
   ```

4. **Commit 4: Analysis Update**
   ```
   git add DOCKER_IMAGE_ANALYSIS.md
   git commit -m "docs: update Docker analysis with security architecture

   - Add security model section with layer breakdown
   - Link to DOCKER_SECURITY_DESIGN.md
   - Update threat model section
   - Clarify that sudo usage is intentional
   
   Improves: Documentation accuracy and completeness"
   ```

5. **Commit 5: Security Policy**
   ```
   git add SECURITY.md
   git commit -m "docs: add security policy and vulnerability reporting

   - Responsible disclosure process
   - Response timeline (24h acknowledgment, 30d fix)
   - Known security considerations
   - Version support policy
   - Production deployment recommendations
   
   Follows: Industry-standard security policy templates"
   ```

---

## Verification Checklist

After completing all tasks:

- [ ] All 4 shell scripts have `set -euo pipefail`
- [ ] `DOCKER_SECURITY_DESIGN.md` exists and is comprehensive
- [ ] `Dockerfile` has security references in comments
- [ ] `DOCKER_IMAGE_ANALYSIS.md` updated with security section
- [ ] `SECURITY.md` created with vulnerability reporting process
- [ ] All commits have descriptive messages
- [ ] No files have `chmod 777` or similar dangerous permissions
- [ ] Documentation links are correct (relative paths)
- [ ] No hardcoded credentials in any documentation
- [ ] Files pass syntax checks: `bash -n scriptname`
- [ ] Team has reviewed and approved security documents

---

## Done Criteria

✅ Complete when:
1. All 5 commits are merged to main branch
2. Documentation is published on project wiki/site
3. Team is notified of security improvements
4. Next release notes mention these improvements
5. No outstanding questions from reviewers

---

## Questions During Implementation?

If you get stuck:

1. **For shell script changes:** Run `bash -n filename` to check syntax
2. **For documentation:** Ask: "Does this explain WHY, not just WHAT?"
3. **For commits:** Use template above, reference the audit
4. **For permissions:** Default is conservative (750); be restrictive

**Remember:** These are documentation and safety improvements, not functionality changes. Low risk, high confidence.

---

**Total Estimated Time:** 90 minutes  
**Actual Time May Vary:** ±20 minutes depending on review cycles

**Start Date:** [YOUR DATE]  
**Target Completion:** [YOUR DATE + 2 hours]