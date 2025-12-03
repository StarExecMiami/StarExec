# StarExec Helm chart notes

This directory contains the Helm chart used for both Podman and Kubernetes deployments. To keep things secure, the default
`values.yaml` intentionally does **not** provide credentials. The chart enforces that either:

1. `postgres.existingSecret` is configured (production mode), **or**
2. Both `postgres.password` and `postgres.rootPassword` are populated (dev/CI/testing).

Because of that, running `helm lint chart` _without_ extra values will fail with:

```text
ERROR: postgres.password and postgres.rootPassword must be set when not using existingSecret
```

## Recommended lint procedure

1. For local development, point `helm lint` at `values-dev.yaml` since it already defines dev passwords:

   ```bash
   helm lint chart -f chart/values-dev.yaml
   ```

   This is what `make lint` does automatically when `values-dev.yaml` exists.

2. In CI or staging pipelines, lint against `values-ci.yaml` to use the ephemeral credentials it defines:

   ```bash
   helm lint chart -f chart/values-ci.yaml
   ```

3. If you must lint `chart/values.yaml` directly, set an external secret or temporarily define both `postgres.password`
   and `postgres.rootPassword` in a values override. **Do not** check those credentials into Git; use
   `helm lint -f overrides.yaml` with a safe file that is ignored by the repo.

This approach keeps the chart secure while letting you verify correctness in each environment.
