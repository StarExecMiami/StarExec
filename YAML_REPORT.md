# StarExec Helm YAML Report (Updated)

This report summarizes the current state of the Helm chart templates and values files in `charts/starexec`, plus related deployment/value files used for Podman/MicroK8s. For each file I list: a short description, purpose in the project, how it relates to other files, and suggestions for improvement. This update reflects fixes implemented post-review (e.g., templated naming, migration job restoration, RBAC addition, conditional secret creation).

---

## charts/starexec/templates/service.yaml

Description:
- Kubernetes `Service` manifest for the `starexec` application.
- Exposes two ports: `http` (default port from `Values.service.port`, fallback 8080) and `postgres` (5432).
- Service `type` is templated from `Values.service.type`. If `NodePort`, a `nodePort` value may be set.
- **Updated**: Name and labels now use `{{ include "chart.fullname" . }}` and `{{ include "chart.labels" . }}` to prevent namespace collisions.

Purpose:
- Provides a stable network endpoint for other components (and external clients when configured) to reach the app and Postgres service inside the pod.

Relations:
- Consumes values from `charts/starexec/values.yaml` (`service.*`).
- Referenced by `ingress.yaml` and other templates.

Suggestions:
- Consider making the postgres port/service conditional if the chart supports an external DB (to avoid exposing DB port unnecessarily).
- Add annotation support (e.g., `metadata.annotations`) to allow service-level annotations like `service.beta.kubernetes.io/aws-load-balancer-internal`.

---

## charts/starexec/templates/deployment.yaml

Description:
- A combined Deployment manifest that runs two containers in a single Pod: the `app` (StarExec Java app) and a bundled `postgres` container.
- Includes init container `fix-permissions` (busybox) to create directories.
- Configures volumes: `data`, `postgres-data`, `tmp`. `postgres-data` becomes `emptyDir` when `.Values.postgres.persistence.enabled` is false.
- Environment variables for DB connection are provided, with DB credentials sourced from the `starexec-postgres-credentials` Secret (conditional).
- Security contexts are applied at both pod and container levels; `fsGroup: 999` is set at pod-level in a `securityContext` block.
- Liveness/readiness probes for both containers configured.
- **Updated**: Metadata, labels, and `serviceAccountName` use templated helpers. Secret references use conditional logic (`{{ .Values.postgres.existingSecret | default (printf "%s-postgres-credentials" (include "chart.fullname" .)) }}`).

Purpose:
- Deploys a self-contained StarExec instance with an embedded Postgres for development/podman use.
- Provides sane defaults for resource requests and limits, probes, and permissions.

Relations:
- Heavily driven by `charts/starexec/values.yaml` and environment-specific values files (`values-dev.yaml`, `values-podman.yaml`, `values-kubernetes.yaml`).
- Uses secret `starexec-postgres-credentials` (created conditionally in `templates/secret-postgres.yaml`) unless `postgres.existingSecret` is configured.
- Volume names reference PVC templates `pvc-data.yaml` and `pvc-postgres.yaml` when persistence is enabled.
- References new `serviceaccount.yaml` and `rbac.yaml`.

Suggestions:
- Split the DB into a separate chart or make running bundled Postgres opt-in via `postgres.bundle.enabled: true` and ensure production `values-kubernetes.yaml` recommends `existingSecret` and external DB configuration.
- The init container logs indicate chown is skipped; ensure this is intentional. When using hostPath volumes (MicroK8s), file ownership may need chown.
- Replace hard-coded `starexec` names with `{{ include "chart.fullname" . }}` in PVC references (currently hardcoded in `pvc-data.yaml` and `pvc-postgres.yaml`).

---

## charts/starexec/templates/ingress.yaml

Description:
- Ingress manifest (Kubernetes networking.k8s.io/v1) templated from `.Values.ingress`.
- Supports annotations, TLS, multiple hosts/paths, and optional `ingressClassName`.
- Backend service references the chart name and `$.Values.service.port`.
- **Updated**: Service name uses `include "chart.name"` (consider updating to `chart.fullname` for consistency).

Purpose:
- Routes external HTTP(S) traffic to the StarExec service when `ingress.enabled` is true.

Relations:
- Controlled by `ingress` section in `values.yaml` or environment-specific values files.

Suggestions:
- Use `{{ include "chart.fullname" . }}` for service name to ensure correct scoping.
- Validate `pathType` handling for older Kubernetes versions or provide a default mapping.
- Consider documenting example ingress annotations for common controllers (NGINX, GCE, Traefik).

---

## charts/starexec/templates/pvc-data.yaml

Description:
- PersistentVolumeClaim manifest named `starexec-data-pvc` requesting `10Gi` with `ReadWriteOnce`.

Purpose:
- Provides persistent storage for the application data volume.

Relations:
- Referenced by `deployment.yaml` as the `data` volume when persistence is in use.
- Values file defines sizes such as `persistence.appDataSize`; mismatch between default 10Gi here and values file may be confusing.

Suggestions:
- Make claim name templated (include release name) and configurable via values (e.g., `dataPvc.name` or `volumePrefix`).
- Use `{{ .Values.persistence.appDataSize }}` and `{{ .Values.persistence.storageClass }}` to derive size and storage class instead of hard-coded `10Gi`.
- Align default size with `values.yaml` which sets `appDataSize: 5Gi` to avoid surprises.

---

## charts/starexec/templates/pvc-postgres.yaml

Description:
- PersistentVolumeClaim for Postgres data named `starexec-postgres-pvc`, namespace `starexec`, `ReadWriteOnce`, requesting `10Gi`.

Purpose:
- Provides persistent storage for the bundled Postgres container.

Relations:
- Referenced by `deployment.yaml` when Postgres persistence is enabled.

Suggestions:
- Remove hard-coded `namespace: starexec` or make it templated; Helm releases may install into different namespaces.
- Make size configurable via `{{ .Values.postgres.persistence.size }}` and storageClass configurable.
- Align default size with `values.yaml` (`postgresDataSize: 20Gi` or `postgres.persistence.size: 20Gi`).

---

## charts/starexec/templates/secret-postgres.yaml

Description:
- Defines a Kubernetes `Secret` named `{{ printf "%s-postgres-credentials" (include "chart.fullname" .) }}` with base64-encoded `user`, `password`, `database`, `rootPassword` sourced from `.Values.postgres.*`.
- **Updated**: Wrapped in `{{- if not .Values.postgres.existingSecret }}` to create only when no external secret is provided. Labels use `chart.labels`.

Purpose:
- Supplies database credentials to both the app container and the embedded Postgres container.

Relations:
- Used by `deployment.yaml` environment variables and Postgres container env.
- `values-kubernetes.yaml` suggests using `existingSecret` in production instead of creating secrets from plaintext values.

Suggestions:
- Avoid default plaintext credentials in `values.yaml` for production; instead require users to provide secrets or use `existingSecret` (values file already documents this but could enforce it).
- Consider using `type: kubernetes.io/basic-auth` or leave as Opaque but document expected keys carefully.

---

## charts/starexec/templates/migrate-job.yaml (New)

Description:
- Kubernetes `Job` manifest as a Helm hook (`pre-install,pre-upgrade`) to run database migrations before app deployment.
- Uses the app image to execute `/opt/starexec/bin/run-migrations.sh` with DB credentials from the secret.
- **Added**: To provide pre-flight schema guarantees and eliminate migration-on-startup race conditions.

Purpose:
- Ensures database schema is migrated before the application starts, preventing startup failures or inconsistent states.

Relations:
- References the same secret as `deployment.yaml` for DB credentials.
- Controlled by `migrations.enabled` in values.

Suggestions:
- Ensure the migration script exists in the image; add error handling if the job fails.
- Consider making the job image configurable if migrations require a different image.

---

## charts/starexec/templates/serviceaccount.yaml (New)

Description:
- Kubernetes `ServiceAccount` with `automountServiceAccountToken: false` for minimal privilege.
- **Added**: To provide a dedicated SA instead of relying on the default, which may have unintended permissions.

Purpose:
- Allows pods to run with least-privilege access, preventing token-based API access unless explicitly granted.

Relations:
- Referenced by `deployment.yaml` and `migrate-job.yaml` as `serviceAccountName`.
- Bound by `rbac.yaml`.

Suggestions:
- If the app requires K8s API access (e.g., for Kubernetes-native backend), add specific rules to the Role in `rbac.yaml`.

---

## charts/starexec/templates/rbac.yaml (New)

Description:
- Kubernetes `Role` (with no rules) and `RoleBinding` to bind the ServiceAccount to the Role.
- **Added**: To enforce RBAC and prevent pods from inheriting default namespace permissions.

Purpose:
- Provides a framework for least-privilege access; currently no permissions granted, which is secure unless API access is needed.

Relations:
- Binds the ServiceAccount from `serviceaccount.yaml` to the Role.

Suggestions:
- Add rules to the Role if the app needs specific K8s API permissions (e.g., for job management in Kubernetes-native backend).

---

## charts/starexec/values.yaml

Description:
- The primary default values file for the Helm chart. Includes configuration for deployment mode, images, persistence, postgres, kubernetes-specific settings, podman, service, resources, migrations, ingress, monitoring, and more.
- Defaults to `deploymentMode: kubernetes`, `backend.type: kubernetes-native`, and `postgres.host: localhost` (contradiction for k8s-native mode).

Purpose:
- Central place to configure templates in `charts/starexec/templates` and to provide environment-specific overrides via `values-dev.yaml`, `values-podman.yaml`, `values-kubernetes.yaml`, etc.

Relations:
- Referenced by all templates in `charts/starexec/templates/*`.
- Overridden by environment-specific files and `override.yaml` during deployment.

Observations & Suggestions:
- Some defaults seem contradictory:
  - `backend.type` defaults to `kubernetes-native` while `postgres.host` defaults to `localhost`. For k8s-native, DB is likely external and not `localhost`.
  - `persistence.appDataSize` is `5Gi`, but `pvc-data.yaml` requests `10Gi`.
  - `postgres.persistence.enabled` defaults to `false`, but `deployment.yaml` includes bundled Postgres unless overridden.
- Security: default `postgres.password` and `rootPassword` are set to dev values — ensure these do not leak to production. Consider removing defaults and requiring explicit secrets for production values.
- Use consistent key names when referencing sizes and storageClass across template files.
- Add clear comments and group related keys for easier consumption by users.

---

## charts/starexec/values-ci.yaml

Description:
- CI/staging values for ephemeral testing: sets `persistence.enabled: false`, `image` to `localhost/local/starexec` and `pullPolicy: Always`, and lighter resource requests.
- Enables CI-friendly env vars like `FLYWAY_BASELINE_ON_MIGRATE` and allows `FLYWAY_CLEAN_DISABLED: false`.

Purpose:
- Provide a predictable, ephemeral environment for continuous integration where state is not persisted between runs.

Relations:
- Overrides defaults from `values.yaml` when used in CI. Useful for `helm template` in CI pipelines or `make deploy-podman ENV=ci`.

Suggestions:
- Ensure CI does not accidentally run destructive Flyway operations against shared databases — having `FLYWAY_CLEAN_DISABLED: false` allows clean; ensure it's only used against ephemeral/test DBs.
- Consider using a random postfix for generated PVC or volumes in CI runs to avoid collisions when parallelizing.

---

## charts/starexec/values-dev.yaml

Description:
- Development values for local Podman development: sets `persistence.usePodmanVolumes: true`, `backend.type: local`, and dev image settings. Enables Postgres persistence by default for dev.

Purpose:
- Simplifies local development by configuring Podman named volumes and local paths.

Relations:
- Used by `make deploy-podman ENV=dev` and referenced by `render.yaml` examples.

Suggestions:
- Document workflow for developers to reset volumes (how to remove named volumes) and how to override DB passwords locally.
- Ensure that Podman named volumes are clearly documented and that the Helm rendering flow creates the correct volume names.
- Keep dev credentials out of checked-in values or clearly mark them as dev-only.

---

## charts/starexec/values-kubernetes.yaml

Description:
- Values targeted at a production-like Kubernetes deployment using the `kubernetes-native` job backend. Configures `kubernetes.enabled: true`, `jobNamespace`, PVC settings (ReadWriteMany), and larger resource targets.
- Sets `postgres.existingSecret` by default to `starexec-postgres-credentials` in examples, encouraging external DB/secret usage.

Purpose:
- Configure the chart for production Kubernetes environments where job execution is implemented using Kubernetes Jobs and an external or cluster-internal Postgres service.

Relations:
- Overrides `values.yaml` defaults for K8s; expects a cluster-provisioned RWX volume and proper RBAC/ClusterRole for node management if enabled.

Suggestions:
- Provide strong guidance on which values MUST be changed for production (like `postgres.host`, `existingSecret`) and add a validation hook or pre-install notes.
- Consider adding an example `Secret` manifest or instructions for creating the `existingSecret` with correct keys and base64-encoded values.

---

## charts/starexec/values-microk8s.yaml

Description:
- MicroK8s-focused values enabling hostPath-based persistence and `backend.type: local` for single-node development. Sets `SKIP_MIGRATIONS: "true"` in env (note: `microk8s-fixed-values.yaml` later warns about SKIP_MIGRATIONS).

Purpose:
- Provide quick configuration for MicroK8s local deployments where a hostPath-backed PVC is convenient.

Relations:
- Similar to `values-dev.yaml`, but tuned for MicroK8s specifics (storageClass set to `microk8s-hostpath`, environment var considerations).

Suggestions:
- Avoid setting `SKIP_MIGRATIONS` to true by default (see `microk8s-fixed-values.yaml` which documents migration issues). Ensure migrations run unless there's a tested reason to skip them.
- Document differences between MicroK8s and Podman paths so users choose the correct file.

---

## charts/starexec/values-podman.yaml

Description:
- Podman environment values similar to `values-dev.yaml`, with `deploymentMode: podman`, `persistence.usePodmanVolumes: true`, and dev image settings. `env` contains `STAREXEC_WEB_ADDRESS` and other Web-related overrides.

Purpose:
- Simplify local Podman-based deployments that mimic Kubernetes resources via Podman.

Relations:
- Used with `make deploy-podman` and `render.yaml` flows.

Suggestions:
- Ensure that Podman named volumes are properly templated and documented.
- Keep dev credentials out of checked-in values or clearly mark them as dev-only.

---

## charts/starexec/override.yaml

Description:
- An overrides file used to set `kubernetes.enabled: false`, default image repo/tag, and `postgres.existingSecret` to `starexec-secret-postgres`. Also sets `podSecurityContext.fsGroup: 999`.

Purpose:
- Provide an easy override used by some deployment workflows to disable Kubernetes-native features or to supply production secrets.

Relations:
- Likely used by makefiles or CI scripts to inject environment-specific overrides.

Suggestions:
- Clarify the intended use of this file in docs and ensure sensitive secret names are not hard-coded for general users.
- Consider removing or gating `podSecurityContext` defaults; let environment-specific values set security context.

---

## charts/starexec/Chart.yaml

Description:
- Helm chart metadata for `starexec` with `apiVersion: v2`, `version: 0.1.0`, and `appVersion: 2025.12`.

Purpose:
- Standard Helm metadata used by Helm tooling and repositories.

Suggestions:
- Keep `appVersion` in sync with project releases. Consider adding `icon:` and `sources` entries if publishing the chart in a Helm repo.

---

## render.yaml

Description:
- Reference Podman deployment manifest template that mirrors what `helm template` would produce for Podman/Dev deployments. Contains a `Pod` manifest combining `app` and `postgres` containers.
- Includes example env vars and volumes matching `values-dev.yaml` and `values-podman.yaml`.

Purpose:
- Serve as a human-readable example and a template for `podman play kube` deployments when Helm is not used directly.

Relations:
- Reflects the rendered output of `charts/starexec/templates/deployment.yaml` for Podman-based flows.

Suggestions:
- Mark clearly that this file is a reference and should not be used directly in production.
- Keep it in sync with `templates/deployment.yaml` or generate it automatically during build to avoid drift.

---

## microk8s-fixed-values.yaml

Description:
- A curated values file with fixes for MicroK8s deployments (storageClass set to `microk8s-hostpath`, corrected migrations settings, security contexts, hostPath volumes and init container to chown hostPath volumes, etc.).

Purpose:
- Provide a ready-to-use MicroK8s values file that addresses common gotchas when deploying StarExec on a single-node MicroK8s cluster.

Relations:
- Should be used with `helm install -f microk8s-fixed-values.yaml` for local MicroK8s deployments.

Suggestions:
- This file contains important notes about ensuring pagination SQL files are included in image builds — ensure Dockerfile/build includes those files.
- Consider merging critical fixes from this file into `values-microk8s.yaml` or documenting when to use the _fixed_ variant.
- The initContainer here includes `chown` which conflicts with `deployment.yaml`'s init container comment about skipping chown; reconcile or document differences based on the target environment (hostPath vs PVC).

---

# Overall Recommendations

- Standardize templating across templates to use `{{ include "chart.fullname" . }}` and `{{ .Release.Namespace }}` to allow safe multi-release installs and namespace portability.
- Remove hard-coded sizes and other values in templates; use `values.yaml` keys consistently (e.g., `persistence.appDataSize`, `postgres.persistence.size`) so that environment overrides work as expected.
- Avoid bundling Postgres for production. Make the bundled DB opt-in via `postgres.bundle.enabled: true` and ensure production `values-kubernetes.yaml` recommends `existingSecret` and external DB configuration.
- Improve Secret handling: if `postgres.existingSecret` is provided, skip creating `secret-postgres.yaml` to avoid accidental credential leakage or conflicts.
- Add documentation snippets in `README.md` or `charts/starexec/README.md` showing recommended `helm install` commands for dev, ci, microk8s, podman, and production, and what values to override for production.
- Add a `values.schema.json` to the chart to provide stronger validation when installing via `helm` (Helm 3 supports values schema).

---

If you want, I can:
- Open a PR that applies the suggested templating improvements (e.g., replace hard-coded names, make PVC sizes configurable, skip secret creation when `existingSecret` is set).
- Add `values.schema.json` and improve docs in `charts/starexec/README.md`.
- Run `helm template` with one of the environment value files to show a rendered manifest sample.

What would you like me to do next? (Updated as of the latest fixes.)