# Legacy Documentation

This document consolidates historical documentation from the original StarExec system, preserved for reference. For current deployment and development, see the main [README](../README.md).

---

## Table of Contents

- [Historical Overview](#historical-overview)
- [Legacy Build System (Ant)](#legacy-build-system-ant)
- [Legacy Dependencies](#legacy-dependencies)
- [Legacy Configuration](#legacy-configuration)
- [Legacy Deployment (Tomcat Standalone)](#legacy-deployment-tomcat-standalone)
- [HPC Backend History](#hpc-backend-history)
- [Migration Notes](#migration-notes)
- [Deprecated Features](#deprecated-features)
- [Historical Analysis Documents](#historical-analysis-documents)
- [Archived Investigation Reports](#archived-investigation-reports)

---

## Historical Overview

StarExec was originally developed at the University of Iowa for managing solver competitions (SAT, SMT, TPTP, etc.) on HPC clusters. The original architecture was:

- **Application Server**: Tomcat 7.x with WAR deployment
- **Build System**: Apache Ant
- **Database**: MySQL (later migrated to PostgreSQL)
- **Job Execution**: Sun Grid Engine (SGE) / OAR
- **Stylesheet Processing**: Ruby Sass
- **Authentication**: LDAP + local database

### Evolution Timeline

| Era | Build | Database | Backend | Container |
|-----|-------|----------|---------|-----------|
| Original (2012-2018) | Ant | MySQL | SGE | None |
| Transition (2018-2023) | Ant → Maven | MySQL → PostgreSQL | SGE/OAR | Docker (experimental) |
| Modern (2023-present) | Maven | PostgreSQL | Local/Podman/K8s | Podman (production) |

---

## Legacy Build System (Ant)

The original build used Apache Ant with a properties-based configuration system.

### Build Files

```
build/
├── build.xml              # Main build file
├── default.properties     # Default configuration
├── overrides.properties   # Local overrides (not committed)
└── example.properties     # Template for local config
```

### Common Ant Targets

```bash
# Build WAR file
ant war

# Clean build
ant clean

# Deploy to Tomcat
ant deploy

# Run tests
ant test

# Generate documentation
ant javadoc
```

### Ant Properties Reference

```properties
# example.properties (historical)
starexec.root=/path/to/starexec
starexec.db.url=jdbc:mysql://localhost:3306/starexec
starexec.db.user=starexec
starexec.db.password=secret
starexec.sge.queue=all.q
tomcat.home=/opt/tomcat
```

### Migration to Maven

The build system was migrated to Maven for:
- Better dependency management
- IDE integration
- Standard project structure
- CI/CD compatibility

**Migration command:**
```bash
# Old Ant build
ant clean war

# New Maven build
mvn clean package
```

---

## Legacy Dependencies

### Historical Dependency Matrix

| Component | Legacy Version | Modern Version | Notes |
|-----------|---------------|----------------|-------|
| Java | 7, 8 | 17+ | Upgraded for security/features |
| Tomcat | 7.0.64 | Embedded (Tomcat 9+) | Now embedded in application |
| MySQL | 5.6, 5.7 | PostgreSQL 15+ | Database engine changed |
| Sass | Ruby Sass | Dart Sass | Ruby Sass deprecated |
| jQuery | 1.x, 2.x | 3.x | Upgraded with compatibility shim |
| DataTables | 1.9.x | 1.13.x | Major upgrade |

### Ruby Sass (Deprecated)

Original SCSS compilation used Ruby Sass:

```bash
# Legacy command
gem install sass
sass --watch src/main/webapp/css:src/main/webapp/css
```

**Modern replacement:**

```bash
# Dart Sass (current)
npm install -g sass
sass src/main/webapp/css/global.scss:src/main/webapp/css/global.css
```

### MySQL to PostgreSQL Migration

The database was migrated from MySQL to PostgreSQL for:
- Better SQL standards compliance
- Superior JSON support
- Better performance for complex queries
- Open source licensing

**Schema differences:**
- `AUTO_INCREMENT` → `SERIAL` / `GENERATED ALWAYS AS IDENTITY`
- `DATETIME` → `TIMESTAMP`
- `TINYINT(1)` → `BOOLEAN`
- `LIMIT` syntax compatible
- String concatenation: `CONCAT()` works in both

---

## Legacy Configuration

### Ant Properties System

```properties
# build/default.properties (historical)

# Application settings
starexec.name=StarExec
starexec.version=1.0

# Database
db.driver=com.mysql.jdbc.Driver
db.url=jdbc:mysql://localhost:3306/starexec
db.user=starexec
db.password=

# SGE settings
sge.root=/opt/sge
sge.cell=default
sge.queue=all.q

# Tomcat
tomcat.home=/opt/tomcat
tomcat.manager.url=http://localhost:8080/manager
```

### Environment Migration

| Legacy Property | Modern Variable | Notes |
|-----------------|-----------------|-------|
| `db.url` | `STAREXEC_DB_HOST/PORT/NAME` | Split into components |
| `db.user` | `STAREXEC_DB_USER` | Direct mapping |
| `db.password` | `STAREXEC_DB_PASSWORD` | Now supports file reference |
| `sge.queue` | `STAREXEC_BACKEND_TYPE` | Backend abstracted |
| `tomcat.home` | N/A | Embedded Tomcat |

---

## Legacy Deployment (Tomcat Standalone)

### Original Deployment Process

```bash
# 1. Build WAR
ant clean war

# 2. Stop Tomcat
sudo systemctl stop tomcat

# 3. Deploy WAR
cp target/starexec.war $TOMCAT_HOME/webapps/

# 4. Configure context
cp context.xml $TOMCAT_HOME/conf/Catalina/localhost/starexec.xml

# 5. Start Tomcat
sudo systemctl start tomcat
```

### Tomcat Context Configuration

```xml
<!-- context.xml (historical) -->
<Context path="/starexec" docBase="starexec">
    <Resource name="jdbc/StarExec"
              auth="Container"
              type="javax.sql.DataSource"
              driverClassName="com.mysql.jdbc.Driver"
              url="jdbc:mysql://localhost:3306/starexec"
              username="starexec"
              password="secret"
              maxTotal="100"
              maxIdle="30"
              maxWaitMillis="10000"/>
</Context>
```

### Modern Equivalent

The containerized deployment replaces standalone Tomcat:

```bash
# Modern deployment
make start

# Or Docker Compose
docker compose up --build
```

---

## HPC Backend History

### Sun Grid Engine (SGE)

SGE was the original job execution backend for HPC clusters.

**Key components:**
- `qsub` - Submit jobs
- `qstat` - Check job status
- `qdel` - Cancel jobs
- `qconf` - Configure queues

**Job submission script (historical):**

```bash
#!/bin/bash
#$ -N starexec-job-${PAIR_ID}
#$ -q all.q
#$ -l h_rt=${WALLCLOCK}
#$ -l h_vmem=${MEMORY}M
#$ -cwd
#$ -o /starexec/output/${JOB_ID}/stdout
#$ -e /starexec/output/${JOB_ID}/stderr

# Run solver
${SOLVER_PATH}/starexec_run ${BENCHMARK_PATH}
```

**Current status:** SGE backend still exists but tests are disabled. Use Podman backend for production.

### OAR Scheduler

OAR was an alternative HPC scheduler common in French research computing.

**Job submission:**

```bash
oarsub -n "starexec-${PAIR_ID}" \
       -l walltime=${WALLCLOCK} \
       --stdout=/starexec/output/${JOB_ID}/stdout \
       --stderr=/starexec/output/${JOB_ID}/stderr \
       ${SCRIPT_PATH}
```

**Current status:** OAR backend exists but is unmaintained. Use Podman backend.

### Backend Evolution

```
SGE (original) → OAR (added) → Local (dev) → Podman (production) → Kubernetes (cloud)
```

---

## Migration Notes

### From Ant to Maven

1. **pom.xml created** from Ant dependencies
2. **Directory structure** reorganized to Maven standard
3. **Build properties** moved to environment variables
4. **Tests** updated to JUnit 5

### From MySQL to PostgreSQL

1. **Schema exported** using mysqldump
2. **SQL converted** using pgloader
3. **Stored procedures** rewritten in PL/pgSQL
4. **Connection strings** updated throughout

### From SGE to Containerized

1. **Job scripts** adapted for container mode
2. **File-based IPC** replaced database calls from containers
3. **Resource limits** now enforced by cgroups instead of SGE
4. **Monitoring** changed from qstat to container inspection

### jQuery Upgrade (2.x to 3.x)

Major upgrade with breaking changes:

- `.load()` callback signature changed
- `$.Deferred` behavior changed
- Some deprecated methods removed
- Added jQuery Migrate plugin for compatibility

---

## Deprecated Features

### Removed Features

| Feature | Removed | Reason |
|---------|---------|--------|
| LDAP-only auth | 2023 | Added local auth as primary |
| MySQL support | 2023 | PostgreSQL only |
| Ant build | 2023 | Maven only |
| Ruby Sass | 2024 | Dart Sass |
| SGE tests | 2024 | Tests disabled (code remains) |

### Deprecated but Present

| Feature | Status | Replacement |
|---------|--------|-------------|
| SGE backend | Code exists, tests disabled | Podman backend |
| OAR backend | Code exists, tests disabled | Podman backend |
| `showMessage()` legacy | Works via wrapper | `Alerts.create()` |
| Legacy CSS classes | Maintained for compatibility | New alert system |

---

## Historical Analysis Documents

The following analysis documents were created during the modernization effort. They are preserved here for historical reference.

### Backend Analysis (2024-2025)

- **LocalBackend Scalability Analysis** - Performance analysis showing 4-16x improvement with concurrent implementation
- **PodmanBackend Design** - Architecture for containerized job execution
- **Kubernetes Backend Analysis** - Review of hybrid K8s implementation limitations
- **Backend Comparison** - Decision matrix for backend selection

**Key findings:**
- LocalBackend: Good for development, 24-96 jobs/hour
- PodmanBackend: Production-ready, 384-600 jobs/hour
- Kubernetes: Limited by 50-job hardcoded cap in hybrid design

### Security Analysis (2024)

- **DOOD Socket Security** - Docker-out-of-Docker security considerations
- **Security Migration** - Password hashing upgrade (bcrypt)
- **Credential Remediation** - Removal of hardcoded defaults

### Frontend Modernization (2024-2025)

- **jQuery Upgrade** - Migration from 2.x to 3.x
- **Alert System Standardization** - Centralized notification system
- **CSS Best Practices Audit** - Stylesheet modernization
- **DataTables Upgrade** - Button rendering fixes

### Concurrency Fixes (2024)

- **Stuck Jobs Analysis** - Root cause of jobs stuck in RUNNING
- **Concurrency Fix** - ThreadPoolExecutor implementation
- **Job 5 Investigation** - Specific stuck job remediation

---

## Archived Investigation Reports

These documents record specific investigations and their resolutions.

### Job Execution Issues

**Problem:** Jobs stuck in ENQUEUED status
**Root cause:** Backend not properly initialized
**Resolution:** Added initialization verification in startup

**Problem:** Jobs stuck in RUNNING indefinitely
**Root cause:** No timeout enforcement in original LocalBackend
**Resolution:** Added configurable timeout with process cleanup

**Problem:** Container jobs never completing
**Root cause:** ContainerJobMonitor not detecting exit
**Resolution:** Fixed container status detection logic

### Performance Issues

**Problem:** Docker image size > 1GB
**Root cause:** Including unnecessary build dependencies
**Resolution:** Multi-stage build, Alpine base (18.7 MB job runner)

**Problem:** Slow Maven builds
**Root cause:** Downloading dependencies each build
**Resolution:** Layer caching in Dockerfile, cached builds

### Database Issues

**Problem:** Flyway migrations failing
**Root cause:** Schema version mismatch after manual changes
**Resolution:** `make migrate-repair` command added

**Problem:** Connection pool exhaustion
**Root cause:** Connections not properly closed
**Resolution:** Connection pool configuration, timeout settings

---

## Reference Links

### Historical External Resources

- [Original StarExec (Iowa)](https://www.starexec.org/) - Original deployment
- [SGE Documentation](https://arc.liv.ac.uk/SGE/htmlman/manuals.html) - Grid Engine manual
- [OAR Documentation](https://oar.imag.fr/) - OAR scheduler

### Modern Resources

- [Current Repository](https://github.com/StarExecMiami/StarExec)
- [Main Documentation](../README.md)
- [Quick Start Guide](QUICKSTART.md)
- [Deployment Guide](DEPLOYMENT.md)

---

## Restoring Legacy Documentation

If you need specific historical documentation that isn't included here:

1. Check git history for the file
2. Open an issue requesting the specific documentation
3. Contact maintainers via GitHub

---

**Note:** This document is for historical reference only. For current usage, see the main [README](../README.md) and [docs/](docs/) directory.