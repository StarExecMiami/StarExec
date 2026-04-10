# MicroK8s Single-Node Deployment

This runbook deploys StarExec on MicroK8s using the Kubernetes-native backend, with the same machine acting as:

- Kubernetes control plane
- StarExec application node
- StarExec compute node
- PostgreSQL storage node

It is the recommended MicroK8s strategy for a single-host lab or development server because it avoids raw `hostPath` manifests and relies on the MicroK8s storage provisioner instead.

## Why This Strategy

For single-node MicroK8s, use:

- `backend.type: kubernetes-native`
- the `microk8s-hostpath` StorageClass
- one worker label on the local node
- one shared `ReadWriteOnce` data PVC mounted on the same node by the StarExec app pod and job pods
- Helm-managed PVCs and secrets
- an init container that fixes ownership before PostgreSQL starts

Do not use ad hoc `/tmp/...` host paths for PostgreSQL. That is the pattern most likely to produce permission failures and non-reproducible state.

## Prerequisites

- MicroK8s installed and running
- `helm` installed
- The MicroK8s `dns` and `storage` addons enabled
- Access to `microk8s kubectl`

## 1. Prepare MicroK8s

```bash
microk8s status --wait-ready
microk8s enable dns storage
microk8s kubectl get storageclass
```

Verify that `microk8s-hostpath` exists and is the default StorageClass.

## 2. Label This Machine As The Only Compute Node

```bash
NODE_NAME="$(microk8s kubectl get nodes -o jsonpath='{.items[0].metadata.name}')"
microk8s kubectl label node "$NODE_NAME" starexec.org/worker=true --overwrite
microk8s kubectl label node "$NODE_NAME" starexec/queue=default --overwrite
microk8s kubectl get nodes -L starexec.org/worker,starexec/queue
```

This keeps StarExec aligned with the Kubernetes-native backend's worker selection logic.

## 3. Create Namespaces

```bash
microk8s kubectl create namespace starexec --dry-run=client -o yaml | microk8s kubectl apply -f -
microk8s kubectl create namespace starexec-jobs --dry-run=client -o yaml | microk8s kubectl apply -f -
```

## 4. Create The Database Secret

Generate strong credentials and create a Kubernetes secret instead of placing passwords in Helm values:

```bash
DB_PASSWORD="$(openssl rand -base64 32)"
POSTGRES_PASSWORD="$(openssl rand -base64 32)"

microk8s kubectl -n starexec create secret generic starexec-postgres-credentials \
  --from-literal=user=starexec \
  --from-literal=password="$DB_PASSWORD" \
  --from-literal=database=starexec \
  --from-literal=rootPassword="$POSTGRES_PASSWORD" \
  --dry-run=client -o yaml | microk8s kubectl apply -f -
```

## 5. Deploy With The MicroK8s Profile

Use the repository's dedicated MicroK8s values file:

```bash
helm upgrade --install starexec ./charts/starexec \
  --create-namespace \
  --namespace starexec \
  -f ./charts/starexec/values-microk8s.yaml
```

That profile does all of the following:

- enables the Kubernetes-native backend
- uses `microk8s-hostpath` for StarExec and PostgreSQL PVCs
- keeps job execution in `starexec-jobs`
- exposes the web app with `NodePort`
- enables volume ownership repair before PostgreSQL starts

## 6. Verify The Deployment

```bash
microk8s kubectl -n starexec get pods,pvc
microk8s kubectl -n starexec describe pod -l app.kubernetes.io/instance=starexec
microk8s kubectl -n starexec logs deploy/starexec -c postgres --tail=100
microk8s kubectl -n starexec logs deploy/starexec -c app --tail=100
microk8s kubectl -n starexec-jobs get jobs,pods
```

Open the UI through the NodePort:

```bash
microk8s kubectl -n starexec get svc starexec
```

Then browse to `http://<node-ip>:30080/starexec`.

## PostgreSQL Permission Failures

If you previously deployed with raw host paths or with PVCs created under the wrong ownership model, PostgreSQL may continue failing even after chart fixes because the old volume contents are still present.

Typical symptom:

```text
initdb: error: could not create directory ... Permission denied
```

Recommended remediation:

1. Uninstall the failed release.
2. Delete only the StarExec PVCs created by that failed release.
3. Reinstall using `values-microk8s.yaml`.

Example:

```bash
helm uninstall starexec -n starexec
microk8s kubectl -n starexec delete pvc starexec-data starexec-dev-backend starexec-dev-sandbox starexec-dev-work starexec-dev-postgres
helm upgrade --install starexec ./charts/starexec \
  --create-namespace \
  --namespace starexec \
  -f ./charts/starexec/values-microk8s.yaml
```

If you need to inspect the current owner on the mounted PostgreSQL path:

```bash
microk8s kubectl -n starexec exec deploy/starexec -c postgres -- sh -c 'id && ls -ld /var/lib/postgresql /var/lib/postgresql/data'
```

The chart now runs a root init container that creates the directories, applies `chown 999:999` to PostgreSQL data, and sets write permissions before the `postgres` container starts.

## Operational Notes

- This profile is single-node only. If you later move job pods onto additional worker nodes, replace the shared data PVC with an RWX-capable storage backend such as NFS or CephFS.
- Keep `kubernetes.strictOnePairPerCpu: true` unless you have measured evidence that changing it preserves benchmark isolation.
- Keep `kubernetes.nodeManagement.enabled: false` for this setup. Node labels are static and should be managed explicitly.
- Do not place database passwords in Git-tracked values files.

## Health Checks

```bash
microk8s kubectl -n starexec rollout status deploy/starexec
microk8s kubectl -n starexec get pvc
microk8s kubectl -n starexec-jobs get all
```

<!-- ## Live Status Checklist

Status captured on April 8-9, 2026 for the single-node deployment performed on this machine.

Completed:

- StarExec deployed successfully in namespace `starexec`
- Kubernetes-native backend enabled and running
- `starexec-jobs` namespace created for job execution
- local worker node labeled with `starexec.org/worker=true`
- local worker node labeled with `starexec/queue=default`
- static single-node PVs created and bound for `data`, `backend`, `sandbox`, `work`, and `postgres`
- PostgreSQL permission bootstrap fixed in the Helm chart
- stale `NotReady` MicroK8s node removed from the cluster
- kubelet certificate and kubelet config regenerated on disk for the current node identity

Verified:

- `microk8s kubectl get nodes -o wide` shows exactly one `Ready` node
- `microk8s kubectl -n kube-system get pods -o wide` shows only healthy pods on the live node
- `microk8s kubectl -n starexec get pvc` shows all claims `Bound`
- StarExec previously reached `2/2 Running` and served requests successfully on `NodePort 30080`

Current incident state as of April 9, 2026:

- `microk8s kubectl -n starexec get pods -o wide` shows the only StarExec pod in `0/2 CrashLoopBackOff`
- `microk8s kubectl -n starexec logs deploy/starexec -c app --insecure-skip-tls-verify-backend` shows startup waiting for PostgreSQL on `localhost:5432` and exiting after 60 seconds
- `microk8s kubectl -n starexec logs deploy/starexec -c postgres --insecure-skip-tls-verify-backend` shows PostgreSQL checkpoint/WAL corruption (`invalid primary checkpoint record`, `could not locate a valid checkpoint record`)
- `microk8s kubectl -n starexec get deploy starexec -o jsonpath='{.spec.strategy.type}'` confirms the live mitigation is active with `Recreate`
- verified restore candidates exist in `./backups`, with checksum-valid archives at:
  - `backups/starexec-dev-full-20260319-171958-postgres.tar.gz`
  - `backups/starexec-dev-full-20260319-182620-postgres.tar.gz`
  - `backups/starexec-dev-full-20260319-182620-data.tar.gz`

Recommended next action order:

1. Preserve the corrupted PostgreSQL volume before any repair attempt.
2. Restore the latest known-good backup set, preferably `20260319-182620`.
3. Use `pg_resetwal` only if backup restore is impossible and data-loss risk is accepted.

Remaining privileged action:

- Restart MicroK8s services so the running kubelet process reloads the corrected `kubelet.crt` and `kubelet.config`

Why this remains:

- `kubectl logs` without `--insecure-skip-tls-verify-backend` still fails because the running kubelet process is serving the old in-memory certificate
- the corrected certificate is already present on disk, but this host requires interactive `sudo` for `microk8s stop`, `microk8s start`, and `microk8s refresh-certs --check`

Recommended final commands to run with local sudo access:

```bash
sudo microk8s stop
sudo microk8s start
microk8s status --wait-ready
microk8s kubectl get nodes -o wide
microk8s kubectl -n starexec get pods
microk8s kubectl -n starexec logs deploy/starexec -c app --tail=20
```

Expected result after restart:

- normal `kubectl logs` works without `--insecure-skip-tls-verify-backend`
- the live kubelet serves the regenerated certificate for node `ptg3t6gnrduk9ehebibbb7e5uacrlktfa3bzbulefh`

## PostgreSQL Corruption Root Cause

Verified on April 8, 2026:

- the live StarExec pod was managed by a Kubernetes `Deployment`
- the live deployment strategy was `RollingUpdate`
- PostgreSQL was running as an embedded sidecar in that same pod
- PostgreSQL data lived on a single persistent volume
- Helm upgrades produced overlapping old and new pods during rollout
- PostgreSQL later failed with checkpoint/WAL corruption (`could not locate a valid checkpoint record`)

Operational conclusion:

- when PostgreSQL is embedded in the same pod as the app, `RollingUpdate` is unsafe
- overlapping old/new pods can mount and touch the same database volume on the same node
- this is sufficient to corrupt PostgreSQL state

Mitigation now implemented in the chart:

- when `postgres.host=localhost`, the StarExec `Deployment` is rendered with `strategy.type=Recreate`
- this prevents old/new pod overlap during upgrades in the embedded-Postgres mode
- the live deployment has been re-verified to use `Recreate`

Longer-term preferred design:

- run PostgreSQL separately from the app
- use a dedicated `StatefulSet` or external managed PostgreSQL
- keep the StarExec application itself on a normal rolling `Deployment` -->
