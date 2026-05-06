# StarExec Helm chart notes

This directory contains the Helm chart used for both Podman and Kubernetes deployments. To keep the default chart safe, `values.yaml` does **not** permit install-time PostgreSQL credentials unless a profile explicitly opts into them. The chart enforces that either:

1. `postgres.existingSecret` is configured (production mode), **or**
2. Both `postgres.password` and `postgres.rootPassword` are populated (dev/CI/testing).

Because of that, running `helm lint chart` _without_ extra values will fail with:

```text
Error: execution error at (starexec/templates/secret.yaml:...): postgres.existingSecret is required unless postgres.allowInsecureDevCredentials=true is explicitly set for dev/CI profiles
```

## Recommended lint procedure

1. For local development, point `helm lint` at `values-dev.yaml` since it opts into dev credentials explicitly:

   ```bash
   helm lint chart -f chart/values-dev.yaml
   ```

   This is what `make lint` does automatically when `values-dev.yaml` exists.

2. In CI or staging pipelines, lint against `values-ci.yaml` to use the ephemeral credentials it defines:

   ```bash
   helm lint chart -f chart/values-ci.yaml
   ```

3. If you must lint `charts/starexec/values.yaml` directly, set an external secret or temporarily define both `postgres.password`
   and `postgres.rootPassword` in a values override **and** set `postgres.allowInsecureDevCredentials=true`. Do not commit that override file to Git.

This approach keeps the chart secure while letting you verify correctness in each environment.
