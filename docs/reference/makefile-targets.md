# Makefile Targets Reference

> **Auto-generated from Makefile** - Do not edit manually
> Run `make docs` to update this file

## All Available Targets

- `build`
- `build-fresh`
- `build-job-runner`
- `build-prod`
- `clean-all`
- `clean-cache`
- `clean-hard`
- `clean-podman`
- `config-show`
- `db-dump`
- `db-migrate`
- `db-shell`
- `db-status`
- `deploy-k8s`
- `deploy-podman`
- `deploy-podman-cached`
- `deploy-podman-direct`
- `deploy-podman-helm`
- `docs`
- `fix-cgroup-delegation`
- `help`
- `image`
- `lint`
- `logs`
- `logs-app`
- `logs-postgres`
- `migrate-podman`
- `migrate-repair`
- `network-setup`
- `nuke`
- `pull`
- `pull-job-runner`
- `reset`
- `start`
- `status`
- `stop`
- `template`
- `test`
- `test-deps`
- `undeploy-k8s`
- `undeploy-podman`
- `verify-deps`
- `volumes-backup`
- `volumes-cleanup`
- `volumes-create`
- `volumes-delete`
- `volumes-export`
- `volumes-health`
- `volumes-help`
- `volumes-list`
- `volumes-restore`

## Target Categories

### Build & Image Management

- `build` - Build or update container images
- `build-fresh` - Build or update container images
- `build-prod` - Build or update container images
- `image` - Build or update container images
- `pull` - Build or update container images
- `build-job-runner` - Build or update container images

### Deployment Targets

- `start` - Deployment operation
- `stop` - Deployment operation
- `deploy-podman` - Deployment operation
- `deploy-podman-helm` - Deployment operation
- `deploy-podman-direct` - Deployment operation
- `deploy-podman-cached` - Deployment operation
- `undeploy-podman` - Deployment operation
- `deploy-k8s` - Deployment operation
- `undeploy-k8s` - Deployment operation

### Volume Management

- `volumes-create` - Volume operation
- `volumes-list` - Volume operation
- `volumes-backup` - Volume operation
- `volumes-restore` - Volume operation
- `volumes-export` - Volume operation
- `volumes-cleanup` - Volume operation
- `volumes-health` - Volume operation
- `volumes-delete` - Volume operation
- `volumes-help` - Volume operation

### Database Management

- `db-shell` - Database operation
- `db-dump` - Database operation
- `db-migrate` - Database operation
- `db-status` - Database operation
- `migrate-repair` - Database operation
- `migrate-podman` - Database operation

### Maintenance & Cleanup

- `clean-podman` - Maintenance operation
- `clean-cache` - Maintenance operation
- `clean-all` - Maintenance operation
- `clean-hard` - Maintenance operation
- `reset` - Maintenance operation
- `nuke` - Maintenance operation
- `status` - Maintenance operation

### Debugging & Diagnostics

- `logs` - Diagnostic operation
- `logs-app` - Diagnostic operation
- `logs-postgres` - Diagnostic operation
- `test-deps` - Diagnostic operation
- `verify-deps` - Diagnostic operation
- `lint` - Diagnostic operation
- `template` - Diagnostic operation
- `config-show` - Diagnostic operation

---

For detailed usage of each target, run `make help`

