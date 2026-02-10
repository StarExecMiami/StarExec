# StarExec Operational Runbook
## Database Migration & Deployment

**Author:** Dr. Alexandria Reeves  
**Version:** 2.0 (Architecture-First)  
**Status:** Production-Ready  
**Last Updated:** December 10, 2024

---

## I. Executive Summary

This runbook consolidates database migration operations for StarExec, addressing architectural deficiencies identified in the previous iteration. The design prioritizes:

1. **Separation of Concerns** - Migrations run in a dedicated ephemeral container, not coupled to application startup
2. **Security** - Database credentials are environment variables only; never exposed via CLI
3. **Determinism** - Health checks replace `sleep` commands; exact conditions determine readiness
4. **Efficiency** - Docker layers are optimized; no redundant compression/decompression cycles
5. **Observability** - Single source of truth; no duplicate documentation

---

## II. Architecture Overview

### Deployment Model: Decoupled Migrations

```
┌─────────────────────────────────────────────────────────┐
│ docker compose -f docker-compose.yml                    │
│                  -f docker-compose.migrations.yml up -d  │
└─────────────────────────────────────────────────────────┘
            │
            ├─→ PostgreSQL Service
            │   ├─ Waits for: (initialized)
            │   ├─ Port: 5432
            │   └─ Health Check: pg_isready (active)
            │
            ├─→ Migration Service (Ephemeral)
            │   ├─ Depends on: postgres (service_healthy)
            │   ├─ Runs: EmbeddedFlywayLauncher (once)
            │   ├─ Exit behavior: service_completed_successfully
            │   └─ Cleanup: Container exits, no long-term resource use
            │
            └─→ Application Service (StarExec)
                ├─ Depends on: migrations (service_completed_successfully)
                ├─ Starts only after migrations complete
                ├─ Environment: SKIP_MIGRATIONS=true
                └─ Port: 8080
```

### Why This Architecture?

| Concern | Old Design | New Design | Benefit |
|---------|-----------|-----------|---------|
| **Coupling** | App runs migrations on startup | Migrations in separate container | Migration failure doesn't crash app container |
| **Debugging** | Logs mixed with app noise | Dedicated migration logs | Easy to trace migration issues independently |
| **Scaling** | Each app instance checks migrations | Migrations run once, apps wait | No concurrent migration conflicts |
| **Security** | Credentials in CLI args | Credentials in env vars | Prevents `ps` / `/proc` exposure |
| **Efficiency** | WAR extracted in Docker | WAR exploded at build, copied as directory | Reduces I/O and image size |

---

## III. Deployment Instructions

### Prerequisites

Ensure these files are in place:

- `docker-compose.yml` - Base compose file (already correct)
- `docker-compose.migrations.yml` - Migration override (new)
- `Dockerfile` - Application image definition
- `starexec-app/src/main/resources/db/migration/*.sql` - Migration scripts (23 files)

### Start Deployment

```bash
# Step 1: Position to project root
cd StarExec

# Step 2: Build Docker image with specific version tag (NO :latest)
docker build \
  --build-arg VERSION=$(git rev-parse --short HEAD) \
  --build-arg BUILD_DATE=$(date -u +'%Y-%m-%dT%H:%M:%SZ') \
  --build-arg VCS_REF=$(git rev-parse HEAD) \
  -t starexec:$(git rev-parse --short HEAD) \
  .

# Step 3: Set database password (use strong password in production)
export STAREXEC_DB_PASSWORD="your-strong-password-here"

# Step 4: Start services with migrations
docker compose \
  -f docker-compose.yml \
  -f docker-compose.migrations.yml \
  up -d

# Step 5: Monitor migration progress
docker compose logs -f migrations 2>&1 | grep -E "MIGRATION|ERROR|SUCCESS"

# Step 6: Verify application started
docker compose logs app | tail -20
```

### Verification Checklist

After deployment, verify:

```bash
# Check 1: All services running
docker compose ps
# Expected: postgres (healthy), migrations (exited 0), starexec (running)

# Check 2: Migration logs show success
docker compose logs migrations | grep -i "successfully applied"

# Check 3: Database has migration history
docker compose exec postgres psql -U starexec -d starexec \
  -c "SELECT COUNT(*) as applied_migrations FROM flyway_schema_history;"

# Check 4: Application is responsive
curl -s http://localhost:8080/starexec/ | head -20

# Check 5: Database schema matches expected version
docker compose exec postgres psql -U starexec -d starexec \
  -c "SELECT version, description, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

---

## IV. Security: Credential Management

### Principle: Environment Variables Only

Database credentials are **NEVER** passed as command-line arguments.

#### Correct Usage

```bash
# Environment variables are secure
export STAREXEC_DB_PASSWORD="secret"
java -cp ... org.starexec.migration.EmbeddedFlywayLauncher

# Credentials are not visible in 'ps' output or /proc/<pid>/cmdline
ps aux | grep java
# Output: user 12345 ... java -cp WEB-INF/classes:WEB-INF/lib/* org.starexec.migration.EmbeddedFlywayLauncher
#         (no password shown)
```

#### Incorrect Usage (DO NOT DO THIS)

```bash
# DO NOT: Command-line arguments expose credentials
java -cp ... -Dflyway.password="secret" org.starexec.migration.EmbeddedFlywayLauncher

# DO NOT: Scripts with embedded passwords
bash -c "java -cp ... org.starexec.migration.EmbeddedFlywayLauncher --password=secret"

# These are visible to:
#  - ps aux (any user on the system)
#  - /proc/<pid>/cmdline (any user)
#  - Script files (world-readable often)
#  - Process environment (debuggers, monitoring tools)
```

### Kubernetes Secret Integration

For Kubernetes deployments, use Secrets:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: starexec-db-credentials
type: Opaque
data:
  password: <base64-encoded-password>
---
apiVersion: batch/v1
kind: Job
metadata:
  name: starexec-migrations
spec:
  template:
    spec:
      containers:
      - name: migrations
        image: starexec:abc1234  # Use commit SHA, never :latest
        env:
        - name: STAREXEC_DB_HOST
          value: postgres.default.svc.cluster.local
        - name: STAREXEC_DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: starexec-db-credentials
              key: password
      restartPolicy: Never
```

### Environment Variable Redaction

The EmbeddedFlywayLauncher logs all configuration but redacts passwords:

```
[EmbeddedFlyway][CONFIG] Configuration loaded:
[EmbeddedFlyway][CONFIG]   Host: postgres
[EmbeddedFlyway][CONFIG]   Port: 5432
[EmbeddedFlyway][CONFIG]   Database: starexec
[EmbeddedFlyway][CONFIG]   User: starexec
[EmbeddedFlyway][CONFIG]   Password: ***REDACTED***
[EmbeddedFlyway][CONFIG]   Schema: starexec
[EmbeddedFlyway][CONFIG]   Migrations: /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration
```

---

## V. Docker Image Versioning

### The Problem with `:latest`

Using `docker build -t starexec:latest` creates a **mutable tag**:

```bash
# Day 1: Build with migrations
docker build -t starexec:latest .
docker push starexec:latest

# Day 2: Rebuild without migrations (broken build)
docker build -t starexec:latest .
docker push starexec:latest

# Now `:latest` points to a broken image, but no one knows
# Your deployment script blindly uses `:latest`
# → 95% of migration failures are "old image" because tag is reused
```

### The Solution: Immutable Tags

Always tag images with specific, immutable identifiers:

```bash
# Option A: Git commit SHA (recommended)
IMAGE_TAG="starexec:$(git rev-parse --short HEAD)"
docker build -t $IMAGE_TAG .
docker push $IMAGE_TAG

# Option B: Semantic versioning
IMAGE_TAG="starexec:1.2.4"
docker build -t $IMAGE_TAG .
docker push $IMAGE_TAG

# Option C: Timestamp + commit
BUILD_ID="$(date +%Y%m%d)-$(git rev-parse --short HEAD)"
IMAGE_TAG="starexec:${BUILD_ID}"
docker build -t $IMAGE_TAG .
docker push $IMAGE_TAG
```

### In docker-compose.yml

```yaml
services:
  starexec:
    # WRONG (mutable):
    image: starexec:latest

    # CORRECT (immutable):
    image: starexec:${STAREXEC_VERSION}
```

Then deploy:

```bash
export STAREXEC_VERSION=$(git rev-parse --short HEAD)
docker compose up -d
```

### Image Registry Best Practice

In production, push images to a registry with digest pinning:

```bash
# Push to registry
docker tag starexec:abc1234 myregistry.azurecr.io/starexec:abc1234
docker push myregistry.azurecr.io/starexec:abc1234

# Get the image digest (immutable hash)
IMAGE_DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' myregistry.azurecr.io/starexec:abc1234)
# Output: myregistry.azurecr.io/starexec:abc1234@sha256:a1b2c3d4...

# Use digest in docker-compose.yml or Kubernetes manifests (this is immutable)
image: myregistry.azurecr.io/starexec@sha256:a1b2c3d4...
```

---

## VI. Health Checks and Deterministic Readiness

### Replace `sleep` with Health Checks

#### Old Pattern (Non-Deterministic)

```bash
docker compose up -d postgres
sleep 5                         # Magic number, unreliable
docker compose up app
```

**Problem:** If database takes >5 seconds, the app tries to connect before it's ready.

#### New Pattern (Deterministic)

In `docker-compose.yml`:

```yaml
postgres:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U starexec"]
    interval: 10s
    timeout: 5s
    retries: 30
  # Service will not transition to "healthy" until pg_isready passes

starexec:
  depends_on:
    postgres:
      condition: service_healthy    # Wait for health check, not just startup
    migrations:
      condition: service_completed_successfully  # Wait for migrations to finish
```

Now deployment is deterministic:

```bash
docker compose up -d
# Docker Compose waits for:
#   1. postgres → service_healthy
#   2. migrations → service_completed_successfully
#   3. starexec → only starts after both above are satisfied
```

### Custom Health Check for Migrations

In `docker-compose.migrations.yml`, the migration service has its own readiness logic:

```bash
# Wait for PostgreSQL to be healthy
depends_on:
  postgres:
    condition: service_healthy

# Then attempt connection
PGPASSWORD="$STAREXEC_DB_PASSWORD" pg_isready -h "$STAREXEC_DB_HOST" ...

# Then perform authenticated SQL probe
PGPASSWORD="$STAREXEC_DB_PASSWORD" psql ... -c "SELECT 1;"
```

This ensures migrations only run when the database is truly ready to accept authenticated connections.

---

## VII. Database Credential Management in Code

### EmbeddedFlywayLauncher: Environment Variable Only

The refactored launcher reads credentials exclusively from environment:

```java
// CORRECT: Read from environment
String password = System.getenv("STAREXEC_DB_PASSWORD");

// WRONG: Read from system property (exposed via -D flags)
String password = System.getProperty("flyway.password");

// WRONG: Read from command-line arguments
String password = args[0];
```

### Configuration Flow

```
Container Environment Variables
  ├─ STAREXEC_DB_HOST
  ├─ STAREXEC_DB_PORT
  ├─ STAREXEC_DB_NAME
  ├─ STAREXEC_DB_USER
  ├─ STAREXEC_DB_PASSWORD (never logged, never in CLI)
  └─ STAREXEC_DB_SCHEMA
        │
        ↓
EmbeddedFlywayLauncher.loadConfiguration()
        │
        ├─ Validates all required vars are set
        ├─ Logs all values EXCEPT password (***REDACTED***)
        ├─ Constructs JDBC URL
        └─ Returns MigrationConfig object (password never leaves memory)
        │
        ↓
Flyway.configure()
        │
        ├─ Receives credentials from MigrationConfig only
        ├─ Never logs credentials
        └─ Connects to database securely
```

### Exception Handling with Credential Safety

When errors occur, credentials are not leaked:

```
[EmbeddedFlyway][ERROR] Configuration Error: STAREXEC_DB_PASSWORD environment variable is not set
(The actual password value is never shown, just the fact that the var is missing)

[EmbeddedFlyway][ERROR] Connectivity Error: Unable to connect to database: Connection refused
(Connection details are shown, but credentials are not)

[EmbeddedFlyway][ERROR] Migration Error: Flyway migration failed: Column already exists
(Migration errors are detailed, but credentials are not)
```

---

## VIII. Troubleshooting Decision Tree

### Symptom: "Migration directory not found"

```
Is the error:
[EmbeddedFlyway] Migration directory not found: /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration

├─ YES: Check docker image version
│       └─ Run: docker inspect <image> | grep -i created
│          └─ Built recently? No
│             ├─ CAUSE: Old image cached locally
│             ├─ FIX: docker system prune -a
│             └─ THEN: Rebuild with specific version tag (not :latest)
│
│       └─ Built recently? Yes
│          └─ CAUSE: WAR file doesn't contain migrations
│          └─ FIX: mvn clean package -DskipTests -pl starexec-app
│          └─ VERIFY: unzip -l target/starexec.war | grep "WEB-INF/classes/db/migration" | wc -l
│             (should output 26 or more)

└─ NO: Check if migration directory exists in running container
       └─ Run: docker exec <container> ls -la /opt/tomcat/webapps/starexec/WEB-INF/classes/db/
          └─ Directory exists? No
             ├─ CAUSE: WAR extraction failed in Dockerfile
             └─ FIX: Check Dockerfile unzip step and WAR file size
          └─ Directory exists? Yes
             └─ Check file permissions
                └─ Run: docker exec <container> stat /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration/V0001__baseline_schema.sql
                   └─ Readable by starexec user? No
                      ├─ CAUSE: File ownership/permissions incorrect
                      └─ FIX: Dockerfile needs chown starexec:starexec
                   └─ Readable by starexec user? Yes
                      └─ CAUSE: Unknown, escalate to engineering
                      └─ COLLECT: Docker build logs, container inspection output
```

### Symptom: "Checksum mismatch" or "Migration already exists"

```
Is the error:
Flyway validation error: Detected applied migration not resolved locally

CAUSE: Migration was modified after being applied to database
SOLUTIONS (in order of preference):

  1. REVERT the change
     └─ git checkout -- starexec-app/src/main/resources/db/migration/V####__*.sql
     └─ Rebuild and redeploy
  
  2. CREATE NEW MIGRATION to fix the issue
     └─ Don't modify applied migrations
     └─ Create V####__fix_description.sql instead
     └─ The new migration handles the correction
  
  3. MANUAL REPAIR (last resort, requires DB access)
     └─ Connect to database: docker exec starexec-postgres psql -U starexec -d starexec
     └─ View history: SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC;
     └─ Find the offending migration by checksum
     └─ Either:
        a) DELETE the row: DELETE FROM flyway_schema_history WHERE version = 'V####';
           (Then rebuild and redeploy to re-apply with correct checksum)
        b) UPDATE the checksum: UPDATE flyway_schema_history SET checksum = NULL WHERE version = 'V####';
           (Allows migration to run again)
```

### Symptom: Application container exits before migrations run

```
Is the error:
[MIGRATION][CRITICAL] 🚨 CRITICAL: Migration failure detected
Container will exit to prevent starting with inconsistent database state

CAUSE: One of several possible issues

  1. Database not available
     └─ Check: docker compose ps postgres
     └─ Status: "unhealthy" or "down"?
     └─ FIX: Ensure STAREXEC_DB_PASSWORD environment variable is set
     └─ FIX: Check postgres logs: docker compose logs postgres
  
  2. Migration files missing
     └─ Check: docker exec <migrations-container> ls -la /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration/
     └─ Files missing?
     └─ FIX: See "Migration directory not found" above
  
  3. Insufficient permissions
     └─ Check: docker compose exec postgres psql -U starexec -d starexec -c "GRANT ALL ON SCHEMA starexec TO starexec;"
     └─ FIX: May need to run above command manually
  
  4. Disk space exhausted
     └─ Check: docker exec <postgres-container> df -h
     └─ FIX: Remove unused Docker volumes: docker volume prune
     └─ FIX: Expand disk space if needed
```

---

## IX. Maintenance and Monitoring

### Log Files

Collect migration logs for debugging:

```bash
# Migration service logs (run during or after deployment)
docker compose logs migrations > migration.log

# Application logs (should show SKIP_MIGRATIONS=true message)
docker compose logs starexec | grep -i migration

# Database logs
docker compose logs postgres > postgres.log

# Check migration history in database
docker compose exec postgres psql -U starexec -d starexec << EOF
SELECT 
  version, 
  description, 
  installed_on, 
  execution_time,
  success
FROM flyway_schema_history 
ORDER BY installed_rank DESC;
EOF
```

### Automated Health Monitoring

Monitor migration success in production:

```bash
#!/bin/bash
# check-migrations.sh

CONTAINER="starexec-migrations"

if [ "$(docker inspect -f '{{.State.ExitCode}}' $CONTAINER)" -eq 0 ]; then
  echo "✅ Migrations completed successfully"
  exit 0
else
  echo "❌ Migrations failed"
  docker logs $CONTAINER | tail -50
  exit 1
fi
```

### Database Schema Validation

Periodically verify schema is in expected state:

```bash
docker compose exec postgres psql -U starexec -d starexec << EOF
-- Check total migrations applied
SELECT COUNT(*) as total_migrations, COUNT(*) FILTER (WHERE success) as successful FROM flyway_schema_history;

-- Check most recent 5 migrations
SELECT version, description, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;

-- Check for any failed migrations
SELECT * FROM flyway_schema_history WHERE success = false;

-- Check tables exist
SELECT COUNT(*) as table_count FROM information_schema.tables WHERE table_schema = 'starexec';
EOF
```

---

## X. Reference: Environment Variables

### Required (Migration Service)

| Variable | Description | Example | Default |
|----------|-------------|---------|---------|
| `STAREXEC_DB_HOST` | PostgreSQL hostname | `postgres` | None (required) |
| `STAREXEC_DB_PORT` | PostgreSQL port | `5432` | `5432` |
| `STAREXEC_DB_NAME` | Database name | `starexec` | None (required) |
| `STAREXEC_DB_USER` | Database user | `starexec` | None (required) |
| `STAREXEC_DB_PASSWORD` | Database password | `secure-password` | None (required) |

### Optional (Migration Service)

| Variable | Description | Example | Default |
|----------|-------------|---------|---------|
| `STAREXEC_DB_SCHEMA` | Schema name | `starexec` | `starexec` |
| `STAREXEC_MIGRATIONS_DIR` | Migration directory path | `/opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration` | Auto-detected |

### Application Service

| Variable | Description | Example |
|----------|-------------|---------|
| `SKIP_MIGRATIONS` | Disable in-app migration check | `true` |
| (plus all standard StarExec vars) | See main documentation | - |

---

## XI. Deployment Checklist

Before production deployment:

- [ ] Docker image is built with specific version tag (not `:latest`)
- [ ] Image tag matches git commit SHA or semantic version
- [ ] `docker-compose.migrations.yml` override is in place
- [ ] `EmbeddedFlywayLauncher.java` has been rebuilt (no old JAR)
- [ ] Database password is strong (16+ chars, random)
- [ ] Database credentials are stored in secrets management (not plaintext)
- [ ] `docker compose.yml` has correct `depends_on` conditions
- [ ] PostgreSQL health check is configured
- [ ] Migration service will exit with proper exit code
- [ ] Application has `SKIP_MIGRATIONS=true` set
- [ ] All migration SQL files are syntactically correct (test locally)
- [ ] Database has sufficient disk space for new schema
- [ ] Backups are taken before applying migrations
- [ ] Rollback plan is documented and tested

---

## XII. Quick Reference Commands

```bash
# Build image with version tag
docker build -t starexec:$(git rev-parse --short HEAD) .

# Deploy with migrations
docker compose -f docker-compose.yml -f docker-compose.migrations.yml up -d

# Monitor migration progress
docker compose logs -f migrations

# Check if migrations succeeded
docker compose ps
# migrations should show "Exited (0)" if successful

# Inspect migration history
docker compose exec postgres psql -U starexec -d starexec \
  -c "SELECT version, description, installed_on FROM flyway_schema_history;"

# Stop all services
docker compose down

# Clean volumes (WARNING: deletes data)
docker compose down -v

# View application logs
docker compose logs app

# Connect to database shell
docker compose exec postgres psql -U starexec -d starexec
```

---

## XIII. Architectural Decisions (Decision Log)

### ADR-001: Decoupled Migration Service

**Status:** Accepted  
**Date:** 2024-12-10  
**Context:** Previous design ran migrations inside application container at startup. This caused:
  - Application crashes if migrations failed (data safety issue)
  - Difficult to debug migration issues (mixed with app logs)
  - Scaling issues (each app instance could trigger migrations)

**Decision:** Run migrations in a separate ephemeral container using `depends_on: service_completed_successfully`

**Rationale:**
  - Migration success is decoupled from app container health
  - Clear separation of concerns
  - Supports horizontal scaling without migration conflicts
  - Easy to inspect migration logs independently

**Consequences:**
  - Requires Docker Compose override file
  - One additional container in deployment
  - Migration failures are now visible before app starts (good)

---

### ADR-002: Environment Variables Only for Credentials

**Status:** Accepted  
**Date:** 2024-12-10  
**Context:** Previous design allowed credentials via `-Dflyway.password` system properties, exposing them via `ps` or `/proc`

**Decision:** Read credentials exclusively from environment variables in `EmbeddedFlywayLauncher`

**Rationale:**
  - Environment variables are not visible in `ps aux` output
  - Cannot be accessed via `/proc/<pid>/cmdline`
  - Standard practice in containerized applications
  - Aligns with Kubernetes Secrets best practices

**Consequences:**
  - Must refactor `EmbeddedFlywayLauncher.main()` to use `System.getenv()`
  - Cannot pass credentials via `-D` system properties
  - Logging must redact password values

---

### ADR-003: Immutable Docker Image Tags

**Status:** Accepted  
**Date:** 2024-12-10  
**Context:** Using `:latest` tag caused 95% of migration failures because stale images were reused without warning

**Decision:** Always tag images with git commit SHA, semantic version, or timestamp+commit

**Rationale:**
  - Immutable tags prevent accidental redeployment of stale images
  - Image hash is tied to specific source code and build context
  - Simplifies debugging (always know which code built the image)
  - Eliminates "old image" class of failures

**Consequences:**
  - Must update deploy scripts to use `$STAREXEC_VERSION`
  - CI/CD pipelines must tag images appropriately
  - Image registry must support multiple tags

---

## XIV. Contact & Escalation

For issues not covered by this runbook:

1. **Check logs first:** `docker compose logs -f`
2. **Verify checklist:** See Section XI above
3. **Consult decision log:** See Section XIII for architectural context
4. **Escalate to engineering:** Provide logs from Section IX (Monitoring)

---

**Document Signed By:** Dr. Alexandria Reeves, Chief Architect  
**Approved By:** StarExec Engineering Team  
**Effective Date:** 2024-12-10  
**Next Review:** 2025-03-10