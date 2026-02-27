# StarExec

> StarExec is an open-source platform for managing solver benchmarks and compute
> jobs across compute nodes. Originally developed for academic SAT/SMT solver
> competitions, it provides a web UI, job submission, multiple scheduling
> backends, and integrated data and user management.

[![Build Status](https://img.shields.io/github/actions/workflow/status/StarExecMiami/StarExec/build-and-publish.yml?branch=containerised)](https://github.com/StarExecMiami/StarExec/actions)
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

# Production path - Podman
make start

# Kubernetes - Helm
helm repo add starexec https://starexecmiami.github.io/StarExec
helm install starexec starexec/starexec -n starexec --create-namespace
```

**⚠️ Default credentials:** `admin:admin` - Change immediately after first login!

## Documentation

### Core Documentation
- **[Quick Start Guide](docs/QUICKSTART.md)** - Get running in 10 minutes
- **[Deployment Guide](docs/DEPLOYMENT.md)** - Detailed deployment instructions
- **[Configuration Reference](docs/CONFIGURATION.md)** - All configuration options
- **[Volume Management](docs/VOLUMES.md)** - Backup, restore, and volume operations

### Operations
- **[Database Management](docs/DATABASE.md)** - Migrations, backups, and troubleshooting
- **[Troubleshooting Guide](docs/TROUBLESHOOTING.md)** - Common issues and solutions
- **[Security Guide](docs/SECURITY.md)** - Security best practices and considerations

### Advanced Topics
- **[Architecture Overview](docs/ARCHITECTURE.md)** - System design and components
- **[Backend Configuration](docs/BACKENDS.md)** - Local, Podman, Kubernetes, SGE, OAR
- **[Performance Tuning](docs/PERFORMANCE.md)** - Capacity and optimization
- **[Observability](docs/OBSERVABILITY.md)** - Logging and debugging

### Development
- **[Developer Guide](docs/DEVELOPER.md)** - Setting up development environment
- **[Contributing Guide](CONTRIBUTING.md)** - How to contribute
- **[Legacy Documentation](docs/LEGACY.md)** - Ant/Tomcat-based setup

## Features

- Web UI for job submission, user and data management
- **Asynchronous Benchmark Uploads** with real-time progress and heartbeat
- Multiple backend implementations (Local/Podman/SGE/OAR/Kubernetes)
- Container-friendly deployment (Podman/Docker Compose)
- Flyway-based database migrations
- Comprehensive volume management and backup system

## Architecture

**Stack:** Java 17 web application (Spring/Tomcat) → PostgreSQL database → Backend scheduler → Compute nodes

See [Architecture Overview](docs/ARCHITECTURE.md) for detailed system design.

## System Requirements

- **Java:** 17+ (for local builds)
- **Maven:** 3.8+
- **PostgreSQL:** 15+
- **Container runtime:** Podman (recommended) or Docker
- **Optional:** Node.js 20+ (SCSS compilation), Helm (Kubernetes)

See [Developer Guide](docs/DEVELOPER.md#prerequisites) for complete requirements.

## Support

- **Issues:** [GitHub Issues](https://github.com/StarExecMiami/StarExec/issues)
- **Releases:** [Changelog](https://github.com/StarExecMiami/StarExec/releases)
- **User Manual:** [StarExec Help](https://starexec.ccs.miami.edu/starexec/public/help.jsp)

## License

This project is licensed under the terms in the [LICENSE](LICENSE) file.

## Maintainers

See the [GitHub repository](https://github.com/StarExecMiami/StarExec) for up-to-date list of maintainers.

---

**Reality Check:** This is a research-grade system being modernized from HPC roots toward cloud-native deployment. Expect some rough edges. See [Known Issues](docs/TROUBLESHOOTING.md#known-issues) for details.