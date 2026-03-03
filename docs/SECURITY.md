# Security Guide

Security best practices and considerations for StarExec deployments.

## Critical Security Actions

### ⚠️ 1. Change Default Credentials IMMEDIATELY

The database is seeded with **insecure default credentials**:

| User | Password | Purpose |
|------|----------|--------|
| `admin` | `admin` | Administrator account |
| `public` | `public` | Public/anonymous access |

**These credentials are baked into database migrations and WILL persist if not changed.**

**How to change:**

1. Log in at `/starexec` with `admin:admin`
2. Navigate to **Account Settings**
3. Change password to a strong password (20+ characters)
4. Repeat for `public` user if enabled

**Production deployment check:**

The Makefile includes a safety check that blocks production deployment if `STAREXEC_DB_PASSWORD` is still set to the default:

```bash
# This will FAIL if using default password
make deploy-podman ENV=prod

# Override (NOT RECOMMENDED)
FORCE=1 make deploy-podman ENV=prod
```

### ⚠️ 2. Secure Database Password

**Never use default database passwords in production.**

**Bad practice:**
```bash
export STAREXEC_DB_PASSWORD=admin  # ❌ Visible in process list
```

**Good practice:**
```bash
# Use password file (recommended)
echo "$(generate-secure-password)" > /run/secrets/db-password
chmod 600 /run/secrets/db-password
export STAREXEC_DB_PASSWORD_FILE=/run/secrets/db-password

# Or use secret manager
export STAREXEC_DB_PASSWORD="$(vault kv get -field=password secret/starexec/db)"
```

### ⚠️ 3. Never Commit Secrets

**Principles:**

- Never commit passwords, API keys, or tokens to the repository
- Use `.gitignore` to exclude sensitive files
- Use environment variables or external secret managers
- Rotate secrets regularly

**Check for leaked secrets:**

```bash
# Scan repository for secrets
git secrets --scan

# Check current configuration
make config-show ENV=prod | grep -i password
# Should show: ***REDACTED***
```

## Credential Management

### Development

For local development, defaults are acceptable:

```bash
export STAREXEC_DB_PASSWORD=starexec_dev_password
```

### Production

Use a secrets management solution:

#### Option 1: Kubernetes Secrets

```bash
# Create secret
kubectl create secret generic starexec-db-secret \
  --from-literal=password="$(openssl rand -base64 32)" \
  -n starexec

# Reference in Helm values
postgres:
  existingSecret: starexec-db-secret
```

#### Option 2: HashiCorp Vault

```bash
# Store secret
vault kv put secret/starexec/db password="$(openssl rand -base64 32)"

# Retrieve at runtime
export STAREXEC_DB_PASSWORD="$(vault kv get -field=password secret/starexec/db)"
```

#### Option 3: File-based Secrets

```bash
# Store in secure file
echo "$(openssl rand -base64 32)" > /etc/starexec/db-password
chmod 600 /etc/starexec/db-password
chown starexec:starexec /etc/starexec/db-password

# Reference file
export STAREXEC_DB_PASSWORD_FILE=/etc/starexec/db-password
```

### Secret Rotation

Rotate secrets regularly (every 90 days):

```bash
# 1. Generate new password
NEW_PASSWORD="$(openssl rand -base64 32)"

# 2. Update database
make db-shell
ALTER USER starexec WITH PASSWORD 'new-password';

# 3. Update secret
kubectl create secret generic starexec-db-secret \
  --from-literal=password="$NEW_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

# 4. Restart application
kubectl rollout restart deployment/starexec -n starexec
```

## Network Security

### Restrict Database Access

**PostgreSQL should NOT be accessible from the internet.**

#### Docker Compose

```yaml
services:
  postgres:
    networks:
      - internal  # Not exposed to external
```

#### Podman

```bash
# Do NOT publish port 5432
# Use internal network only
podman network create starexec-net --internal
```

#### Kubernetes

```yaml
# Use NetworkPolicy
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: starexec-db-policy
spec:
  podSelector:
    matchLabels:
      app: postgres
  policyTypes:
  - Ingress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: starexec
    ports:
    - protocol: TCP
      port: 5432
```

### TLS/SSL Configuration

#### Application

Enable HTTPS in production:

```yaml
# Helm values
ingress:
  enabled: true
  tls:
    - secretName: starexec-tls
      hosts:
        - starexec.example.com
```

#### Database

Enable SSL for PostgreSQL connections:

```bash
# PostgreSQL configuration
ssl = on
ssl_cert_file = '/var/lib/postgresql/server.crt'
ssl_key_file = '/var/lib/postgresql/server.key'
```

Application connection string:

```bash
export STAREXEC_DB_URL="jdbc:postgresql://postgres:5432/starexec?ssl=true&sslmode=require"
```

### Firewall Rules

Restrict access to StarExec ports:

```bash
# Allow only from trusted networks
sudo ufw allow from 10.0.0.0/8 to any port 7827 proto tcp
sudo ufw deny 7827/tcp

# PostgreSQL - internal only
sudo ufw deny 5432/tcp
```

## Container Security

### Rootless Podman (Recommended)

**Rootless mode provides significant security benefits:**

- Containers run without root privileges
- Reduced attack surface
- Better isolation

**Enable rootless mode:**

```bash
# Verify rootless
podman system info | grep rootless
# Should show: rootless: true

# If rootful, migrate to rootless
podman system migrate --new-runtime
```

### Container Isolation

**Local backend provides NO isolation** - jobs run as the application user.

**Use Podman or Kubernetes backends for production:**

```bash
# Podman backend (recommended)
export STAREXEC_BACKEND_TYPE=podman

# Kubernetes backend
export STAREXEC_BACKEND_TYPE=kubernetes
```

### Resource Limits

Enforce container resource limits:

```yaml
# Helm values
resources:
  limits:
    memory: 4Gi
    cpu: 2
    ephemeral-storage: 10Gi
  requests:
    memory: 2Gi
    cpu: 1
```

### Image Security

**Use trusted images:**

```bash
# Official images from GHCR
podman pull ghcr.io/starexecmiami/starexec:latest

# Verify image signatures (if available)
podman image trust show
```

**Scan images for vulnerabilities:**

```bash
# Using Trivy
trivy image ghcr.io/starexecmiami/starexec:latest

# Using Clair
clairctl analyze ghcr.io/starexecmiami/starexec:latest
```

## File System Security

### Permissions

Set restrictive permissions on data directories:

```bash
# Application data
sudo chown -R starexec:starexec /var/lib/starexec
sudo chmod 750 /var/lib/starexec

# Database data
sudo chown -R postgres:postgres /var/lib/postgresql
sudo chmod 700 /var/lib/postgresql
```

### SELinux / AppArmor

Enable mandatory access control:

```bash
# SELinux
sudo setenforce 1
sudo setsebool -P container_manage_cgroup on

# AppArmor (loaded automatically by Podman)
sudo aa-status | grep podman
```

### Disk Quotas

Prevent disk exhaustion:

```bash
# Set quota for starexec user
sudo setquota -u starexec 50G 60G 0 0 /var

# Verify
sudo quota -vs starexec
```

## Application Security

### Input Validation

**StarExec validates:**

- File uploads (size, type)
- SQL injection prevention (prepared statements)
- Path traversal prevention

**Additional hardening:**

```yaml
# Limit upload size
server:
  max-file-size: 100MB
  max-request-size: 105MB
```

### Authentication

**Current authentication:**

- Database-backed user accounts
- Session-based authentication
- LDAP/AD integration (optional)

**Hardening recommendations:**

1. **Enable HTTPS** - Protect credentials in transit
2. **Set session timeout**
3. **Implement 2FA** (future enhancement)
4. **Rate limit login attempts**

### CSRF Protection

StarExec enforces Cross-Site Request Forgery (CSRF) protection via a centralized
`CsrfFilter` registered in `WEB-INF/web.xml`.

**Coverage:** All `POST` requests to `/secure/*` and `/public/registration/*`.

**How it works:**

1. On page load, every JSP embeds a per-session token in a `<meta name="csrf-token">` tag.
2. The global `master.js` reads that token and automatically:
   - Injects an `X-CSRF-Token` header on all jQuery AJAX POST requests.
   - Appends a hidden `csrfToken` field to every HTML form submission (including multipart).
3. `CsrfFilter` validates the token using a constant-time comparison. Requests with a
   missing or mismatched token receive **HTTP 403 Forbidden**.

**API client exemption:**

Non-browser clients (StarExecCommand CLI, scripts) are exempt from CSRF validation
provided they include the custom header:

```
StarExecCommand: StarExecCommand
```

This header is automatically sent by `Connection.setHeaders()` in the Java CLI client.
For `curl` or Python scripts, add it explicitly:

```bash
curl -H "StarExecCommand: StarExecCommand" -X POST ...
```

Browsers cannot include arbitrary custom headers in cross-site requests without a CORS
preflight, making this a reliable, non-spoofable exemption signal.

**Verification:**

```bash
# Confirm CsrfFilter is registered (should print the filter mapping)
grep -A5 "CsrfFilter" starexec-app/src/main/webapp/WEB-INF/web.xml
```

### Authorization

**Role-based access control:**

- Admin - Full system access
- User - Job submission and management
- Public - Read-only access (if enabled)

**Review permissions:**

```sql
-- Check user roles
SELECT id, first_name, last_name, role FROM users;

-- Disable public access if not needed
UPDATE users SET enabled = false WHERE email = 'public@starexec.org';
```

## Audit and Monitoring

### Logging

**Enable comprehensive logging:**

```xml
<!-- logback.xml -->
<logger name="org.starexec.security" level="INFO"/>
<logger name="org.starexec.data" level="INFO"/>
```

**Log important events:**

- Login attempts (success and failure)
- Job submissions
- File uploads
- Configuration changes

### Log Retention

```bash
# Rotate logs
# /etc/logrotate.d/starexec
/var/log/starexec/*.log {
    daily
    rotate 30
    compress
    missingok
    notifempty
    create 0640 starexec starexec
}
```

### Monitoring

**Monitor for suspicious activity:**

- Failed login attempts
- Unusual job patterns
- Resource exhaustion
- Database query anomalies

**Set up alerts:**

```bash
# Monitor failed logins
tail -f /var/log/starexec/starexec.log | grep "Authentication failed"

# Monitor resource usage
podman stats starexec-app --no-stream
```

## Backup Security

### Encrypted Backups

```bash
# Encrypt backup with GPG
make volumes-backup ENV=prod
gpg --encrypt --recipient admin@example.com backups/starexec-prod-*.tar.gz

# Decrypt when restoring
gpg --decrypt backup.tar.gz.gpg | tar xzf -
```

### Secure Backup Storage

**Never store backups on the same server:**

```bash
# Upload to remote storage
aws s3 cp backups/starexec-prod-*.tar.gz s3://backups/starexec/ --sse AES256

# Or use rsync over SSH
rsync -avz --delete backups/ backup-server:/backups/starexec/
```

### Backup Access Control

```bash
# Restrict backup directory
sudo chown root:backup /var/backups/starexec
sudo chmod 750 /var/backups/starexec

# Add starexec user to backup group
sudo usermod -aG backup starexec
```

## Incident Response

### Breach Detection

**Signs of compromise:**

- Unexpected user accounts
- Unknown jobs running
- Unusual network traffic
- Modified configuration files
- Unexpected file uploads

### Response Plan

1. **Isolate the system**
   ```bash
   # Stop all services
   make stop
   
   # Block network access
   sudo iptables -A INPUT -j DROP
   sudo iptables -A OUTPUT -j DROP
   ```

2. **Preserve evidence**
   ```bash
   # Snapshot volumes
   make volumes-backup ENV=prod
   
   # Copy logs
   cp -r /var/log/starexec /forensics/logs-$(date +%Y%m%d)
   ```

3. **Investigate**
   - Review logs for unauthorized access
   - Check user accounts and permissions
   - Examine file modifications
   - Analyze network connections

4. **Remediate**
   - Rotate all credentials
   - Patch vulnerabilities
   - Restore from clean backup if needed
   - Update security policies

5. **Document**
   - Timeline of events
   - Actions taken
   - Lessons learned
   - Updated procedures

## Compliance Considerations

### Data Protection

**If handling sensitive data:**

- Encrypt data at rest
- Encrypt data in transit
- Implement data retention policies
- Provide data deletion capabilities

### Regulatory Requirements

**Consider applicable regulations:**

- GDPR (EU)
- CCPA (California)
- FERPA (education)
- Export control (solver distribution)

## Security Checklist

### Pre-Production

- [ ] Change default `admin:admin` credentials
- [ ] Set secure database password
- [ ] Enable HTTPS/TLS
- [ ] Configure firewall rules
- [ ] Enable rootless Podman
- [ ] Set resource limits
- [ ] Configure backup encryption
- [ ] Review user permissions
- [ ] Enable audit logging
- [ ] Scan container images
- [ ] Verify CSRF filter is active: `grep CsrfFilter starexec-app/src/main/webapp/WEB-INF/web.xml`

### Post-Production

- [ ] Monitor logs regularly
- [ ] Rotate secrets every 90 days
- [ ] Apply security updates
- [ ] Test backup restore
- [ ] Review access logs
- [ ] Audit user accounts
- [ ] Check for CVEs
- [ ] Perform security scans

## Security Resources

- **CVE Database:** https://cve.mitre.org/
- **Podman Security:** https://docs.podman.io/en/latest/markdown/podman-run.1.html#security-options
- **Kubernetes Security:** https://kubernetes.io/docs/concepts/security/
- **OWASP Top 10:** https://owasp.org/www-project-top-ten/

## Reporting Security Issues

**Do not report security vulnerabilities in public issues.**

Email security concerns to: security@starexec.org

Include:

- Description of vulnerability
- Steps to reproduce
- Potential impact
- Suggested remediation

## Next Steps

- **Deployment hardening:** [DEPLOYMENT.md](DEPLOYMENT.md)
- **Monitoring setup:** [OBSERVABILITY.md](OBSERVABILITY.md)
- **Disaster recovery:** [VOLUMES.md](VOLUMES.md)