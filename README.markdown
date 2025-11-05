# StarExec

> StarExec is an open-source platform for managing and running large-scale solver
> benchmarks and compute jobs across clusters and compute nodes. It provides a
> web UI, job submission, scheduling backends (local/SGE/OAR), and integrated
> data and user management.

<!-- Badges -->
[![Build Status](https://img.shields.io/github/actions/workflow/status/StarExecMiami/StarExec/build-and-publish.yml?branch=containerised)](https://github.com/StarExecMiami/StarExec/actions)
[![License](https://img.shields.io/github/license/StarExecMiami/StarExec)](LICENSE)
[![Release](https://img.shields.io/github/v/release/StarExecMiami/StarExec?label=repo%20version)](https://github.com/StarExecMiami/StarExec/releases)

<!-- Table of contents -->
## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Quick Start](#quick-start)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
  - [Podman / Makefile (recommended)](#podman--makefile-recommended)
  - [Docker Compose (simple alternative)](#docker-compose-simple-alternative)
  - [Manual / Raw containers (advanced)](#manual--raw-containers-advanced)
- [Configuration](#configuration)
  - [Quick configuration](#quick-configuration)
  - [Environment variables reference](#environment-variables-reference)
  - [Advanced configuration](#advanced-configuration)
- [Security Considerations](#security-considerations)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [Support & Changelog](#support--changelog)
- [License](#license)
- [Authors & Acknowledgments](#authors--acknowledgments)
- [Legacy Documentation](#legacy-documentation)

## Overview

StarExec runs solver benchmarks and user-submitted compute jobs across a pool
of compute nodes. The project includes a Java web application, optional
backend components for job distribution, and tooling to run in containers or
on a traditional Tomcat/Postgres stack.

## Features

- Web UI for job submission, user and data management.
- Multiple backend implementations: local, SGE, and OAR.
- Container-friendly deployment (Podman/Docker Compose).
- Flyway-based database migrations and safe, layered configuration.

<!-- Quick Start -->
## Quick Start

Use Quick Start to get a development instance running locally in ~10-20 minutes.

1. Copy the repo and change to its root:

    ```bash
    # Clone the repository and enter it
    git clone https://github.com/StarExecMiami/StarExec.git
    cd StarExec
    ```

2. Start development stack (recommended):

```bash
# First-time (builds images and deploys stack)
make start
```

Expected outcome: containers built and started. The web UI is usually available
at `http://localhost:7827/starexec` (see the `make` output for exact address).

Estimated time: 10–20 minutes (depending on network and local build cache).

## Prerequisites

Before installing, ensure the host meets the following requirements.

Required:

- Java 17+ (for local manual builds).
- Maven 3.8+ (for manual builds and packaging).
- PostgreSQL 15+ (production DB).
- Container runtime: Podman (recommended) or Docker.

Optional (for development):

- Node.js 20+ (SCSS compilation), `postgresql-client` for DB access.

Version matrix (high level):

| Component | Recommended version |
|-----------|---------------------|
| Java | 17 |
| Maven | 3.8+ |
| Node.js | 20+ (dev only) |
| PostgreSQL | 15 |

Notes:

- Required vs optional dependencies are separated above. When using containers
  (Makefile or Docker Compose), most host dependencies are optional because
  builds run inside the container images.

<!-- Installation -->
## Installation

For each method below: a short note on when to use it, numbered steps, expected
output, and an estimated time to complete.

### Podman / Makefile (recommended)

When to use: development and CI-friendly deployments on Linux systems where
`podman` rootless mode is supported. Prefer this if you want reproducible
container-based builds without Docker daemon.

Estimated time: 10–30 minutes.

Steps:

1. Install required packages (example for Ubuntu/Debian):

    ```bash
    # Install podman and helper tools (run as root)
    sudo apt-get update
    sudo apt-get install -y podman catatonit passt fuse-overlayfs
    ```

    Expected output: package manager confirms installation; `podman --version`
    prints the version.

2. Verify rootless operation (recommended):

    ```bash
    # Show whether podman is running rootless
    podman system info | grep rootless
    ```

    Expected output (example):

    ```text
    rootless: true
    ```

3. Start the stack using the Makefile:

    ```bash
    # Build and start the development environment
    make start
    ```

Success indicator: `make` completes without error and prints service endpoints.

Notes and common fixes:

- If you see permission issues with rootless networking, install `passt` and
  run `podman system migrate`. If the error persists, follow the troubleshooting
  section below.

### Docker Compose (simple alternative)

When to use: quick local testing or if you prefer Docker Compose workflows.

Estimated time: 5–20 minutes.

Steps:

1. Build and run with Docker Compose:

    ```bash
    # Build images and start containers
    docker-compose up --build
    ```

    Expected outcome: containers are built and started.
    Logs show Flyway migrations.
    The web app is typically available on `http://localhost:8080/`.

2. Stop and remove containers:

```bash
docker-compose down
```

Pros/Cons comparison

| Method | Pros | Cons |
|---|---:|---|
| Podman + Makefile | Rootless-friendly, works well on CI, reproducible images | Requires podman/tools on host |
| Docker Compose | Widely used, simple to run | Requires Docker daemon, less rootless-friendly |
| Manual (Tomcat) | Full control of runtime, good for production Tomcat deployments | More manual steps, more host deps |

### Manual / Raw containers (advanced)

When to use: you need fine-grained control of the runtime and want to run
containers without the Makefile orchestration.

Estimated time: 10–30 minutes.

Steps (brief):

1. Build image locally:

    ```bash
    # Build the container image (example using podman)
    podman build -t localhost/local/starexec:dev .
    ```

2. Create network and volumes, then start database and app containers (example):

```bash
podman network create starexec-net
podman volume create starexec-app-data
podman volume create starexec-postgres-data

# Start Postgres
podman run -d --name starexec-postgres \
  --network starexec-net \
  -e POSTGRES_PASSWORD=admin \
  -e POSTGRES_DB=starexec \
  -e POSTGRES_USER=starexec \
  -v starexec-postgres-data:/var/lib/postgresql/data \
  -p 5432:5432 \
  docker.io/library/postgres:15

# Start application (replace env values for production)
podman run -d --name starexec-app \
  --network starexec-net \
  -e STAREXEC_DB_HOST=starexec-postgres \
  -e STAREXEC_DB_PASSWORD=admin \
  -e STAREXEC_DB_USER=starexec \
  -e STAREXEC_DB_DATABASE=starexec \
  -v starexec-app-data:/app/data \
  -p 8080:8080 \
  localhost/local/starexec:dev
```

Success indicator: both containers run (check with `podman ps`) and web UI
reachable at the expected port.

<!-- Configuration -->
## Configuration

Configuration is layered and validated at startup. Use the following sections to
locate specific keys and quick-start the minimal settings required.

### Quick configuration

Minimal environment variables to bring up a local development instance:

```bash
export STAREXEC_DB_HOST=localhost
export STAREXEC_DB_PORT=5432
export STAREXEC_DB_NAME=starexec
export STAREXEC_DB_USER=starexec
export STAREXEC_DB_PASSWORD=admin # sensitive
```

Validate configuration rendering (example):

```bash
make config-show ENV=dev
```

### Environment variables reference

Use the anchors below for direct linking.

- [Database configuration](#database-configuration)
- [Cluster Compute configuration](#cluster-compute-configuration)
- [Email configuration](#email-configuration)
- [Backend / System configuration](#backend--system-configuration)

<!-- headings below provide the canonical anchors for direct linking -->

#### Database configuration

| Variable | Default | Example | Notes |
|---|---:|---|---|
| `STAREXEC_DB_HOST` | `localhost` | `db.local` | Required |
| `STAREXEC_DB_PORT` | `5432` | `5432` | Required |
| `STAREXEC_DB_NAME` | `starexec` | `starexec` | Required |
| `STAREXEC_DB_USER` | `starexec` | `starexec` | Required |
| `STAREXEC_DB_PASSWORD` | *(empty)* | `s3cr3t` | Required — sensitive |

Marking sensitive variables: variables that contain credentials or secrets are
marked as **sensitive** in their Notes column.

#### Cluster Compute configuration

| Variable | Default | Example |
|---|---:|---|
| `STAREXEC_CLUSTER_DB_USER` | `starexec` | `cluster_user` |
| `STAREXEC_CLUSTER_DB_PASSWORD` | *(empty)* | `cluster_pass` (sensitive) |

#### Email configuration

| Variable | Default | Example |
|---|---:|---|
| `STAREXEC_EMAIL_SMTP` | `localhost` | `smtp.example.org` |
| `STAREXEC_EMAIL_PORT` | `25` | `587` |
| `STAREXEC_EMAIL_USER` | *(empty)* | `mailer@example.org` (sensitive) |
| `STAREXEC_EMAIL_PASSWORD` | *(empty)* | `...` (sensitive) |

#### Backend / System configuration

| Variable | Default | Example |
|---|---:|---|
| `STAREXEC_BACKEND_TYPE` | `local` | `sge` |
| `STAREXEC_DATA_DIR` | `/tmp/starexec/data` | `/var/lib/starexec/data` |

### Advanced configuration

Advanced settings live in Helm values files or in the `EnvironmentConfig.java`
fallbacks. For production deployments, prefer runtime environment variables or
Kubernetes secrets (never commit secrets to the repo).

Validation command (development):

```bash
make config-show ENV=dev
```

<!-- Security -->
## Security Considerations

- Change seed/default credentials (e.g., `admin/admin`, `public/public`) on
  first deployment. **Do not** use default passwords in production.
- Mark all credentials as sensitive and manage them with a secrets engine
  (Vault, Kubernetes Secrets, or external secret manager).
- Recommended permissions: run the application user with least privilege and
  ensure backend sandbox directories have restricted group permissions.
- Network security: restrict database access to trusted networks, use TLS for
  external services, and configure firewalls/security groups.

Warning: The examples in this README include simple passwords for demo
purposes. Replace them in real deployments.

<!-- Troubleshooting -->
## Troubleshooting

Format for entries:

**Problem:** symptom

**Cause:** likely reason

**Solution:** steps to fix

- **Problem:** `podman` rootless networking fails with permission denied.

  **Cause:** missing `passt` / network helpers on host.

  **Solution:**

  ```bash
  # Install helper and migrate podman data
  sudo apt-get install -y passt
  podman system migrate
  ```

  Expected result: `podman` commands operate in rootless mode and `podman
  system info | grep rootless` shows `rootless: true`.

- **Problem:** slow Maven builds.

  **Cause:** missing local Maven cache.

  **Solution:** use cached builds:

  ```bash
  make build-cached
  ```

  Or pull a prebuilt image:

  ```bash
  podman pull ghcr.io/starexecmiami/starexec:latest
  ```

Add links to GitHub issues or documentation for common problems where
appropriate.

<!-- Contributing -->
## Contributing

Please read `CONTRIBUTING.md` for guidelines on submitting issues and
pull requests. Quick notes:

- Open issues for feature requests and bugs.
- Follow the repository coding style and include tests where possible.

<!-- Support & Changelog -->
## Support & Changelog

- Report issues at the GitHub issue tracker: [Issues](https://github.com/StarExecMiami/StarExec/issues)
- Changelog and release notes can be found under the [Releases](https://github.com/StarExecMiami/StarExec/releases) tab.

## License

This project is licensed under the terms in the `LICENSE` file.

## Authors & Acknowledgments

- Maintainers: see the GitHub repository for up-to-date list of maintainers.

## Legacy documentation

Legacy Ant/Tomcat-based documentation has been moved to `LEGACY.md`.
Refer to that file for historical build instructions and notes.

<!-- End of README -->
