#!/usr/bin/env bash
set -e
LOGDIR=logs
mkdir -p "$LOGDIR"
TS=$(date +%Y%m%d%H%M%S)
LOGFILE="$PWD/$LOGDIR/remediation-$TS.log"

echo "Logging remediation run to $LOGFILE"

# Run remediation steps and tee output to logfile
(
  echo "=== Start remediation run: $(date -Iseconds) ==="
  NAMESPACE=starexec
  PVC=starexec-postgres-pvc
  echo "[1/6] Inspecting PVC + PV"
  kubectl -n "$NAMESPACE" get pvc "$PVC" -o yaml || true
  PV=$(kubectl -n "$NAMESPACE" get pvc "$PVC" -o jsonpath='{.spec.volumeName}' 2>/dev/null || true)
  if [ -z "$PV" ]; then
    echo "PVC $PVC not found or has no bound PV"
    PV=$(kubectl get pv -o jsonpath='{range .items[*]}{.metadata.name}:{.spec.claimRef.namespace}/{.spec.claimRef.name}\n{end}' | grep "/$PVC" || true)
    PV=$(echo "$PV" | head -n1 | cut -d: -f1 || true)
  fi
  if [ -n "$PV" ]; then
    echo "Found PV: $PV"
    kubectl get pv "$PV" -o yaml || true
    HOSTPATH=$(kubectl get pv "$PV" -o jsonpath='{.spec.hostPath.path}' 2>/dev/null || true)
  else
    echo "No PV found for PVC $PVC"
    HOSTPATH=""
  fi

  echo "[2/6] Deleting PVC and PV (if present)"
  kubectl -n "$NAMESPACE" delete pvc "$PVC" --wait --ignore-not-found || true
  if [ -n "$PV" ]; then
    kubectl delete pv "$PV" --wait --ignore-not-found || true
  fi

  if [ -n "$HOSTPATH" ] && [ -e "$HOSTPATH" ]; then
    echo "[3/6] Wiping hostPath: $HOSTPATH"
    sudo rm -rf "$HOSTPATH" || true
    echo "HostPath wiped"
  else
    echo "[3/6] No hostPath to wipe: $HOSTPATH"
  fi

  echo "[4/6] Building image and side-loading into MicroK8s"
  TAG="fix-migrations-$(date +%Y%m%d%H%M%S)"
  IMG="starexec:${TAG}"
  echo "Building ${IMG}"
  docker build -t "${IMG}" -f Dockerfile . || true
  OUT=/tmp/starexec_${TAG}.tar
  docker save "${IMG}" -o "$OUT" || true
  microk8s ctr image import "$OUT" || true
  microk8s ctr images ls | grep "starexec" || true

  echo "[5/6] Helm upgrade/install with migration-enabled image: ${IMG}"
  helm upgrade --install starexec ./charts/starexec -n "$NAMESPACE" \
    --create-namespace \
    --set image.repository=starexec \
    --set image.tag="$TAG" \
    --set migrations.enabled=true \
    --set postgres.persistence.enabled=true \
    --set service.type=ClusterIP \
    --wait --timeout 15m || true

  sleep 5
  kubectl -n "$NAMESPACE" get pods -o wide || true
  APP_POD=$(kubectl -n "$NAMESPACE" get pods --no-headers -o custom-columns=":metadata.name" | grep "starexec" | head -n1 || true)
  POSTGRES_POD=$(kubectl -n "$NAMESPACE" get pods --no-headers -o custom-columns=":metadata.name" | grep "postgres" | head -n1 || true)
  echo "APP_POD=$APP_POD"
  echo "POSTGRES_POD=$POSTGRES_POD"

  if [ -n "$APP_POD" ]; then
    echo "--- Last 500 lines of app logs ---"
    kubectl -n "$NAMESPACE" logs "$APP_POD" -c starexec --tail=500 || true
    echo "--- Migration-related lines ---"
    kubectl -n "$NAMESPACE" logs "$APP_POD" -c starexec --tail=1000 2>/dev/null | grep -Ei "flyway|migration|Applying|Successfully applied|EmbeddedFlywayLauncher" || true
  fi

  if [ -n "$POSTGRES_POD" ]; then
    echo "--- Verifying DB tables ---"
    kubectl -n "$NAMESPACE" exec -c postgres "$POSTGRES_POD" -- psql -U postgres -c "\\l" || true
    EXISTS=$(kubectl -n "$NAMESPACE" exec -c postgres "$POSTGRES_POD" -- psql -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname='starexec'" || echo "")
    echo "DB exists? $EXISTS"
    if [ "$EXISTS" = "1" ]; then
      echo "Listing tables (\\dt):"
      kubectl -n "$NAMESPACE" exec -c postgres "$POSTGRES_POD" -- psql -U postgres -d starexec -c "\\\\dt" || true
      for t in users jobs permissions; do
        echo "-- $t --"
        kubectl -n "$NAMESPACE" exec -c postgres "$POSTGRES_POD" -- psql -U postgres -d starexec -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_name='$t';" || true
        kubectl -n "$NAMESPACE" exec -c postgres "$POSTGRES_POD" -- psql -U postgres -d starexec -c "SELECT COUNT(*) FROM $t;" || true
      done
    fi
  fi

  MIG_LINES=$(kubectl -n "$NAMESPACE" logs "$APP_POD" -c starexec --tail=1000 2>/dev/null | grep -Ei "Successfully applied|Migration execution completed|All database migrations applied successfully" || true)
  if [ -n "$POSTGRES_POD" ]; then
    U_EXISTS=$(kubectl -n "$NAMESPACE" exec -c postgres "$POSTGRES_POD" -- psql -U postgres -d starexec -tAc "SELECT 1 FROM information_schema.tables WHERE table_name='users' LIMIT 1;" || echo "0")
    J_EXISTS=$(kubectl -n "$NAMESPACE" exec -c postgres "$POSTGRES_POD" -- psql -U postgres -d starexec -tAc "SELECT 1 FROM information_schema.tables WHERE table_name='jobs' LIMIT 1;" || echo "0")
    P_EXISTS=$(kubectl -n "$NAMESPACE" exec -c postgres "$POSTGRES_POD" -- psql -U postgres -d starexec -tAc "SELECT 1 FROM information_schema.tables WHERE table_name='permissions' LIMIT 1;" || echo "0")
  else
    U_EXISTS=0
    J_EXISTS=0
    P_EXISTS=0
  fi

  if [ -n "$MIG_LINES" ] && [ "$U_EXISTS" = "1" ] && [ "$J_EXISTS" = "1" ] && [ "$P_EXISTS" = "1" ]; then
    echo "Migrations look successful; unsetting SKIP_MIGRATIONS and restarting deployment"
    kubectl -n "$NAMESPACE" set env deployment/starexec SKIP_MIGRATIONS- || true
    kubectl -n "$NAMESPACE" rollout restart deployment/starexec || true
    kubectl -n "$NAMESPACE" rollout status deployment/starexec --timeout=5m || true
    kubectl -n "$NAMESPACE" get pods -o wide || true
  else
    echo "Migrations did not conclusively succeed OR key tables missing. Not unsetting SKIP_MIGRATIONS."
    echo "MIG_LINES="$MIG_LINES" U=$U_EXISTS J=$J_EXISTS P=$P_EXISTS"
  fi

  echo "=== End remediation run: $(date -Iseconds) ==="
) 2>&1 | tee "$LOGFILE"

# Show a short tail
echo "--- Log tail ---"
tail -n 80 "$LOGFILE"

echo "Log saved to: $LOGFILE"
