# StarExec

> StarExec is an open-source platform for managing solver benchmarks and compute
> jobs across compute nodes. Originally developed for academic SAT/SMT solver
> competitions, it provides a web UI, job submission, multiple scheduling
> backends, and integrated data and user management.

[![Build Status](https://img.shields.io/github/actions/workflow/status/StarExecMiami/StarExec/maven-test.yml?branch=containerised)](https://github.com/StarExecMiami/StarExec/actions/workflows/maven-test.yml)
[![License](https://img.shields.io/github/license/StarExecMiami/StarExec)](LICENSE)
[![Release](https://img.shields.io/github/v/release/StarExecMiami/StarExec?label=repo%20version)](https://github.com/StarExecMiami/StarExec/releases)

## Quick Start

Choose your deployment method:

| Use Case | Method | Time | Guide |
|----------|--------|------|-------|
| **Local testing** | Docker Compose | ~10 min | [Quick Start](docs/QUICKSTART.md#method-1-docker-compose-recommended-for-testing) |
| **Single-node production** | Podman + Makefile | ~20 min | [Quick Start](docs/QUICKSTART.md#method-2-podman--makefile-recommended-for-production) |
| **Kubernetes cluster** | Helm | ~30 min | [Quick Start](docs/QUICKSTART.md#method-3-kubernetes-with-helm) |

```bash
# Fastest path - Docker Compose
docker compose up --build

# Production path - Podman (create the values file first: docs/DEPLOYMENT.md)
make deploy-podman ENV=prod VALS=charts/starexec/values-podman-prod.yaml

# Kubernetes - Helm
helm repo add starexec https://starexecmiami.github.io/StarExec
helm install starexec starexec/starexec -n starexec --create-namespace
```

**⚠️ Default credentials:** `admin:admin` - Change immediately after first login!

## Documentation

Documentation is organized using the [Diátaxis](https://diataxis.fr) framework.
The list below is the canonical public documentation set.

### Tutorial — Learning by doing
- **[Quick Start Guide](docs/QUICKSTART.md)** - Get running in 10-30 minutes (Docker Compose, Podman, or Kubernetes)

### How-to — Accomplish specific goals
- **[Deployment Guide](docs/DEPLOYMENT.md)** - Detailed deployment instructions across Docker Compose, Podman, and Kubernetes
- **[Kubernetes Deployment Automation](docs/KUBERNETES_AUTO_DEPLOYMENT.md)** - Automated Kubernetes setup and deployment workflow
- **[MicroK8s Single-Node Deployment](docs/MICROK8S_SINGLE_NODE.md)** - Single-node MicroK8s runbook with Kubernetes-native backend
- **[Legacy Data Migration](docs/LEGACY_DATA_MIGRATION.md)** - Migrating data from legacy MariaDB/MySQL to Flyway PostgreSQL (`scripts/mysql2pg`)
- **[Volume Management](docs/VOLUMES.md)** - Backup, restore, and volume operations
- **[Database Management](docs/DATABASE.md)** - PostgreSQL administration, Flyway migrations, backups, and troubleshooting
- **[Troubleshooting Guide](docs/TROUBLESHOOTING.md)** - Common issues and solutions
- **[Security Guide](docs/SECURITY.md)** - Operational security hardening, CSRF protection, and incident response
- **[Security Policy](SECURITY.md)** - Supported versions and private vulnerability reporting
- **[Performance Tuning](docs/PERFORMANCE.md)** - Capacity planning and optimization
- **[Observability](docs/OBSERVABILITY.md)** - Logging, health probes, and debugging
- **[Developer Guide](docs/DEVELOPER.md)** - Setting up a development environment and running tests
- **[Contributing Guide](CONTRIBUTING.md)** - Contribution rules, style, and pre-PR verification

### Reference — Consult while working
- **[Configuration Reference](docs/CONFIGURATION.md)** - All environment variables and Helm values
- **[Backend Configuration](docs/BACKENDS.md)** - Local, Podman, Kubernetes, SGE, and OAR reference
- **[API Guide](docs/API_GUIDE.md)** - REST/servlet API endpoints, session auth, SSE streaming, and CLI header contract
- **[Database Standards](docs/DATABASE_STANDARDS.md)** - Foreign key conventions and Flyway migration rules
- **[Helm Chart Notes](charts/starexec/README.md)** - Credential guards and chart linting profiles
- **[Test Resources](docs/test-resources.md)** - Maven test profiles and test-data fixtures
- **[Release Notes (v2.6.0)](docs/release-notes/v2.6.0.md)** - Cumulative v2.6.0 release notes and upgrade guidance

### Explanation — Understand why
- **[Architecture Overview](docs/ARCHITECTURE.md)** - System design, components, and data flow
- **[Execution Storage Isolation](docs/EXECUTION_STORAGE.md)** - Bounded ext4 execution storage, leases, and runtime boundary
- **[Solver Build Artifacts](docs/SOLVER_ARTIFACTS.md)** - Managed solver build publication and cleanup lifecycle
- **[Reproducibility Manifest Contract](docs/REPRO_MANIFEST_CONTRACT.md)** - Per-attempt pair reproducibility manifest state model
- **[CPU Partition Scheduling](docs/cpu-partition-scheduling.md)** - SMT-aware Podman CPU pinning and NUMA partitioning
- **[Kubernetes Execution Identity](docs/k8s-execution-identity.md)** - Per-attempt Kubernetes job identity and fencing
- **[Legacy Documentation](docs/LEGACY.md)** - Historical Ant/MySQL/Tomcat 7 background and migration notes

## Features

- Web UI for job submission, user and data management
- **Asynchronous Benchmark Uploads** with real-time progress and heartbeat
- Multiple backend implementations (Local/Podman/SGE/OAR/Kubernetes)
- Container-friendly deployment (Podman/Docker Compose)
- Flyway-based database migrations
- Comprehensive volume management and backup system

## Architecture

**Stack:** Java 17 web application (Servlet/JSP on Tomcat 9) → PostgreSQL database → Backend scheduler → Compute nodes

See [Architecture Overview](docs/ARCHITECTURE.md) for detailed system design.

## System Requirements

- **Java:** 17+ (for local builds; runtime container uses `eclipse-temurin:17-jre-noble` / `glibc`)
- **Maven:** 3.8+
- **PostgreSQL:** 15+
- **Container runtime:** Podman (recommended) or Docker
- **Optional:** Node.js 24 LTS (SCSS compilation and frontend regression tests; handled automatically inside Docker/Maven builds), Helm 3.8+ (Kubernetes)

See [Developer Guide](docs/DEVELOPER.md#prerequisites) for complete requirements.

## Support

- **Issues:** [GitHub Issues](https://github.com/StarExecMiami/StarExec/issues)
- **Releases & Changelog:** [CHANGELOG.md](CHANGELOG.md) and [v2.6.0 Release Notes](docs/release-notes/v2.6.0.md)
- **User Manual:** [StarExec Help](https://starexec.ccs.miami.edu/starexec/public/help.jsp) (also served at `/starexec/public/help.jsp` on your local instance)

## License

This project is licensed under the terms in the [LICENSE](LICENSE) file.

## Maintainers

See the [GitHub repository](https://github.com/StarExecMiami/StarExec) for up-to-date list of maintainers.

---

**Reality Check:** This is a research-grade system being modernized from HPC roots toward cloud-native deployment. Expect some rough edges. See [Known Issues](docs/TROUBLESHOOTING.md#known-issues) for details.
