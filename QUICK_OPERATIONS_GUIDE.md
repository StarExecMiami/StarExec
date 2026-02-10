# StarExec Quick Operations Guide

**Version:** 1.0  
**Purpose:** Fast reference for common operations tasks and emergency procedures  
**Audience:** Operations team, on-call engineers, platform support

---

## 🚀 Quick Start: First Time Deployment

### Docker Compose (Recommended for Non-Production)

```bash
# 1. Clone and navigate
git clone https://github.com/starexecmiami/starexec.git
cd starexec

# 2. Set password
export STAREXEC_DB_PASSWORD="your_secure_password_here"

# 3. Deploy
docker compose up -d

# 4. Monitor migrations (wait ~30 seconds)
docker compose logs -f migrations

# 5. Check application (should be ready in 1-2 minutes)
curl http://localhost:8080/starexec/

# 6. Done! Application available at http://localhost:8080/starexec/
```

### Kubernetes/Helm (Production)

```bash
# 1. Create namespace and secret
kubectl create namespace starexec
kubectl create secret generic starexec-db-credentials \
  --from-literal=password="your_secure_password" \
  -n starexec

# 2. Deploy
helm install starexec ./charts/starexec \
  -n starexec \
  --values charts/starexec/values-kubernetes.yaml

# 3. Monitor migrations (watch for 1/1 completions)
kubectl get jobs -n starexec -w

# 4. Wait for app pod
kubectl wait --for=condition=ready pod \
  -l app=starexec,component=app \
  -n starexec \
  --timeout=300s

# 5. Access (use port-forward)
kubectl port-forward -n starexec svc/starexec 8080:8080
# Then: http://localhost:8080/starexec/
```

---

## 🔍 Health Checks: Is Everything Working?

### Quick Health Check (30 seconds)

```bash
# Docker Compose
docker compose ps  # All should show "Up" or "Exited (0)"
curl http://localhost:8080/starexec/  # Should return HTTP 200/302

# Kubernetes
kubectl get pods -n starexec  # All should show "Running" or "Completed"
kubectl port-forward svc/starexec 8080:8080 &
curl http://localhost:8080/starexec/
```

### Database Health Check

```bash
# Docker Compose
docker compose exec -T postgres psql -U starexec -d starexec \
  -c "SELECT COUNT(*) as migrations_applied FROM flyway_schema_history;"

# Kubernetes
kubectl exec -it <postgres-pod> -n starexec -- \
  psql -U starexec -d starexec \
  -c "SELECT COUNT(*) as migrations_applied FROM flyway_schema_history;"
```

**Expected:** Migration count >20

### Application Logs Check

```bash
# Docker Compose - watch in real-time
docker compose logs -f starexec

# Docker Compose - last 50 lines
docker compose logs starexec | tail -50

# Kubernetes
kubectl logs deployment/starexec-app -n starexec --tail=50 -f
```

---

## 🛠️ Common Operations Tasks

### Restart Application

```bash
# Docker Compose
docker compose restart starexec

# Kubernetes
kubectl rollout restart deployment/starexec-app -n starexec
```

### View All Logs

```bash
# Docker Compose (all services)
docker compose logs

# Docker Compose (single service)
docker compose logs migrations
docker compose logs postgres
docker compose logs starexec

# Kubernetes (all pods)
kubectl logs -n starexec --all-containers=true -f

# Kubernetes (single pod)
kubectl logs -n starexec deployment/starexec-app
```

### Check Resource Usage

```bash
# Docker Compose
docker stats

# Kubernetes
kubectl top nodes -n starexec
kubectl top pods -n starexec
```

### Access Database Console

```bash
# Docker Compose
docker compose exec postgres psql -U starexec -d starexec

# Kubernetes
kubectl exec -it <postgres-pod> -n starexec -- psql -U starexec -d starexec
```

### Update Image Version

```bash
# Docker Compose (change tag in .env)
export STAREXEC_VERSION=20241210-abc1234
docker compose pull
docker compose up -d

# Kubernetes (update Helm values)
helm upgrade starexec ./charts/starexec \
  -n starexec \
  --values charts/starexec/values-kubernetes.yaml \
  --set image.tag=20241210-abc1234
```

### Scale Application (Kubernetes Only)

```bash
# Scale to 3 replicas
kubectl scale deployment starexec-app --replicas=3 -n starexec

# Check current replicas
kubectl get deployment starexec-app -n starexec
```

---

## 🚨 Emergency Procedures

### Issue: Application Not Responding (HTTP 500/502)

**Quick Diagnosis (30 seconds):**
```bash
# Is the pod/container running?
docker compose ps starexec              # Should show "Up"
kubectl get pods -n starexec            # Should show "Running"

# Are there obvious error messages?
docker compose logs starexec | tail -20
kubectl logs deployment/starexec-app -n starexec | tail -20

# Is the database accessible?
docker compose exec -T postgres psql -U starexec -d starexec -c "SELECT 1;"
```

**Quick Fix Options:**
1. **Restart application:** `docker compose restart starexec` or `kubectl rollout restart deployment/starexec-app -n starexec`
2. **Check database:** Ensure PostgreSQL pod/container is healthy and accessible
3. **Review logs:** Look for obvious error messages
4. **Last resort:** Redeploy with fresh database: `docker compose down -v && docker compose up -d`

---

### Issue: Migrations Failed (Deployment Won't Complete)

**Quick Diagnosis:**
```bash
# Docker Compose
docker compose ps migrations  # Should show "Exited (0)"
docker compose logs migrations | tail -50

# Kubernetes
kubectl get job -n starexec
kubectl logs job/starexec-migrate -n starexec
```

**Quick Fix Options:**
1. **Check error message:** Review migration logs for specific SQL error
2. **Database connectivity:** Verify PostgreSQL is accessible and healthy
3. **Retry:** Redeploy with clean database: `docker compose down -v && docker compose up -d`
4. **Manual intervention:** Connect to database and check current schema state

---

### Issue: High CPU or Memory Usage

**Quick Diagnosis:**
```bash
# Docker Compose
docker stats

# Kubernetes
kubectl top pods -n starexec
kubectl top nodes

# Check for runaway processes
docker compose exec starexec ps aux
```

**Quick Actions:**
1. **Identify culprit:** Which service is using resources?
2. **Check for leaks:** Are memory numbers growing over time?
3. **Restart:** `docker compose restart <service>` or `kubectl rollout restart deployment/<service>`
4. **Scale:** Add more replicas (Kubernetes): `kubectl scale deployment/starexec-app --replicas=3`
5. **Escalate:** If memory constantly increasing → Memory leak → Contact developers

---

### Issue: Credentials Exposed in Logs

**🚨 CRITICAL - Immediate Action Required:**

```bash
# 1. STOP deployment immediately
docker compose down
helm uninstall starexec -n starexec

# 2. Check what was exposed
docker compose logs migrations | grep -v REDACTED | grep -i password

# 3. Verify issue
# - Check EmbeddedFlywayLauncher.java uses System.getenv() only
# - Check docker-compose.yml doesn't have hardcoded passwords
# - Check Helm templates use secretKeyRef, not plain env values

# 4. Escalate to security team
# - Rotate database password immediately
# - Audit logs for exposure duration
# - Review CI/CD for credential handling

# 5. Deploy with fixed code
# - Ensure credentials redacted in logs
# - Rebuild image
# - Redeploy with confidence
```

---

### Issue: Database Connection Errors

**Quick Diagnosis:**
```bash
# Is PostgreSQL running?
docker compose ps postgres
kubectl get pod -l app.kubernetes.io/name=postgresql -n starexec

# Can we connect?
docker compose exec -T postgres pg_isready -U starexec
kubectl exec -it <postgres-pod> -n starexec -- pg_isready -U starexec

# Is the password correct?
PGPASSWORD="your_password" psql -h localhost -U starexec -d starexec -c "SELECT 1;"
```

**Quick Actions:**
1. **Check password:** Verify STAREXEC_DB_PASSWORD is set correctly
2. **Check connectivity:** Ensure services can communicate (correct hostname, port)
3. **Check network:** Verify firewall/security group allows PostgreSQL port (5432)
4. **Restart PostgreSQL:** `docker compose restart postgres` or `kubectl delete pod <postgres-pod>`

---

## 📊 Monitoring & Alerting Quick Setup

### Simple Health Check Script (Run Every 5 Minutes)

```bash
#!/bin/bash
# save as: /usr/local/bin/starexec-health.sh

set -e

# Check Docker Compose services
if command -v docker-compose &> /dev/null; then
  POSTGRES_STATUS=$(docker compose ps postgres --format "{{.State}}" 2>/dev/null || echo "down")
  APP_STATUS=$(docker compose ps starexec --format "{{.State}}" 2>/dev/null || echo "down")
  
  if [ "$POSTGRES_STATUS" != "running" ] || [ "$APP_STATUS" != "running" ]; then
    echo "⚠️  ALERT: Service down - Postgres: $POSTGRES_STATUS, App: $APP_STATUS"
    exit 1
  fi
  
  # Test HTTP endpoint
  if ! curl -sf http://localhost:8080/starexec/ >/dev/null; then
    echo "⚠️  ALERT: Application not responding"
    exit 1
  fi
fi

echo "✅ All checks passed"
exit 0
```

### Kubernetes Monitoring (Watch Deployments)

```bash
# Watch pods
kubectl get pods -n starexec -w

# Watch events
kubectl get events -n starexec -w

# Check pod logs for errors
kubectl logs -n starexec --all-containers=true | grep -i error

# Setup alerts (requires monitoring stack)
# - Alert on pod CrashLoopBackOff
# - Alert on job failure (starexec-migrate)
# - Alert on high memory/CPU
```

---

## 🔐 Credential Management Checklist

### When Changing Database Password

```bash
# 1. Update Kubernetes Secret
kubectl delete secret starexec-db-credentials -n starexec
kubectl create secret generic starexec-db-credentials \
  --from-literal=password="new_secure_password" \
  -n starexec

# 2. Update environment variable (Docker Compose)
export STAREXEC_DB_PASSWORD="new_secure_password"

# 3. Redeploy application
docker compose up -d  # Docker Compose
helm upgrade starexec ./charts/starexec -n starexec  # Kubernetes

# 4. Verify new password works
docker compose exec -T postgres psql -U starexec -d starexec -c "SELECT 1;"
```

### Password Security Checklist

- [ ] Password is >16 characters (preferably >20)
- [ ] Password contains uppercase, lowercase, numbers, and symbols
- [ ] Password is NOT stored in git (use .env or Kubernetes Secret)
- [ ] Password is NOT in logs (verify with "REDACTED" marker)
- [ ] Password is rotated every 90 days (production)
- [ ] Old passwords revoked after rotation
- [ ] Only authorized personnel have access

---

## 📋 Daily Operations Checklist

**Every Day:**
- [ ] Services are running (docker compose ps / kubectl get pods)
- [ ] No error messages in recent logs
- [ ] HTTP endpoint responding (curl http://localhost:8080/starexec/)
- [ ] Database connectivity confirmed

**Every Week:**
- [ ] Review error logs for patterns
- [ ] Check disk space (df -h)
- [ ] Verify backups are occurring
- [ ] Update image to latest tagged version

**Every Month:**
- [ ] Full health validation (run STAGING_VALIDATION_CHECKLIST.md)
- [ ] Security audit (credential handling, log redaction)
- [ ] Performance baseline (CPU, memory, response times)
- [ ] Disaster recovery test

---

## 🆘 Getting Help

### Where to Find Answers

| Issue | Resource | Time |
|-------|----------|------|
| How do I deploy? | OPERATIONAL_RUNBOOK.md | 5 min |
| What went wrong? | Logs + Section "🚨 Emergency Procedures" | 10 min |
| I need full validation | STAGING_VALIDATION_CHECKLIST.md | 30 min |
| Architecture questions | ARCHITECTURE_IMPROVEMENTS.md | 20 min |
| I need to scale | Contact DevOps team | varies |
| Critical incident | Page on-call engineer | immediate |

### Quick Command Reference

```bash
# Most useful commands
docker compose ps                      # Service status
docker compose logs -f <service>       # Live logs
docker compose exec <svc> bash         # Access container
kubectl get pods -n starexec           # K8s pod status
kubectl logs deployment/starexec-app   # K8s app logs
kubectl exec -it <pod> -- bash         # Access K8s pod
```

### Emergency Contact

**On-Call Engineer:** [Contact info]  
**DevOps Team:** [Contact info]  
**Security Team:** [Contact info] (for credential issues)

---

## 📚 Additional Resources

- **Full Runbook:** See `OPERATIONAL_RUNBOOK.md`
- **Validation Checklist:** See `STAGING_VALIDATION_CHECKLIST.md`
- **Architecture:** See `ARCHITECTURE_IMPROVEMENTS.md`
- **Issues:** Check project GitHub issues or internal wiki

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | Dec 2024 | Initial release |

**Last Updated:** December 2024  
**Maintained By:** StarExec DevOps Team  
**Next Review:** January 2025