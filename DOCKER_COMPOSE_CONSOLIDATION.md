# Docker Compose Consolidation Decision

**Author:** Dr. Alexandria Reeves  
**Date:** December 10, 2024  
**Status:** Implementation Complete  
**Classification:** Architecture Decision Record (ADR)

---

## Executive Summary

The original architectural approach used separate Docker Compose files (`docker-compose.yml` and `docker-compose.migrations.yml`) connected via override mechanism. This has been consolidated into a **single authoritative `docker-compose.yml` file** that includes all services (PostgreSQL, migrations, application) in one place.

**Rationale:** Following the DRY (Don't Repeat Yourself) principle and operational clarity—a single source of truth is superior to split definitions.

---

## The Problem with Override Files

### Operational Complexity
```bash
# OLD APPROACH: Users must know to use the override file
docker compose -f docker-compose.yml -f docker-compose.migrations.yml up -d

# NEW APPROACH: Simple, obvious
docker compose up -d
```

Users who forget the `-f docker-compose.migrations.yml` flag will:
- Get an incomplete deployment
- Miss the migration service entirely
- Have the application try to run migrations itself (defeating the decoupling)
- Encounter confusing behavior

### Documentation Burden
- Override files require additional documentation
- New team members must learn about the two-file pattern
- Risk of configuration drift between files
- Maintenance requires updating two places

### Single Point of Failure
If a user runs only the base file:
```bash
docker compose up -d  # Forgot the override!
```

They get:
- Application starts immediately (before migrations)
- No separate migration service
- Potential data inconsistency if migrations fail
- Harder to debug (mixed logs)

---

## The Solution: Single File Consolidation

### Architecture in One File

The new `docker-compose.yml` explicitly defines three services with clear dependency ordering:

```yaml
services:
  postgres:          # Starts first
    healthcheck: ... # Ensures readiness

  migrations:        # Starts after postgres is healthy
    depends_on:
      postgres:
        condition: service_healthy
    restart: "no"    # Exits cleanly

  starexec:          # Starts only after migrations complete
    depends_on:
      postgres:
        condition: service_healthy
      migrations:
        condition: service_completed_successfully
```

### Benefits of Consolidation

| Aspect | Override Files | Single File |
|--------|---|---|
| **Usability** | Must remember special flags | Works with `docker compose up -d` |
| **Clarity** | Split across two files | Complete picture in one place |
| **Maintenance** | Update two files | Update one file |
| **Error Risk** | Easy to forget override flag | Impossible to deploy incompletely |
| **Onboarding** | Requires explanation | Self-documenting |
| **Documentation** | Additional pages needed | Inline comments sufficient |

---

## What Changed

### Removed
- `docker-compose.migrations.yml` (no longer needed)
- Redundant documentation for override pattern
- Need to explain multi-file deployment

### Updated
- `docker-compose.yml` expanded with:
  - Comprehensive section headers and comments
  - Embedded `migrations` service definition
  - Clear dependency chain with explanations
  - Inline troubleshooting guidance

### Preserved
- All three services (postgres, migrations, starexec)
- All environment variables and configurations
- All volumes and networks
- All health checks and dependencies
- Complete decoupling of migration concerns

---

## Deployment Commands

### Before (Old Approach)
```bash
# Users had to know about and use the override file
docker compose -f docker-compose.yml -f docker-compose.migrations.yml up -d

# What if they forgot?
docker compose up -d  # ❌ Incomplete, confusing behavior
```

### After (New Approach)
```bash
# Simple, obvious, complete
docker compose up -d  # ✅ All services start in correct order

# Monitor migrations
docker compose logs -f migrations

# View app logs
docker compose logs starexec

# Check status
docker compose ps
```

---

## Verification

### Deployment Order Verification
```bash
# Watch services start in order
docker compose up -d

# 1. PostgreSQL starts and healthcheck runs
# 2. Migrations service starts (after postgres healthy)
# 3. Application starts (after migrations complete)

# Verify order in logs
docker compose logs --timestamps | grep -E "postgres|migrations|starexec"
```

### Migration Success Verification
```bash
# Check migration service exited cleanly
docker compose ps migrations
# Expected output: starexec-migrations  Exited (0)

# View migration logs
docker compose logs migrations | grep -E "SUCCESS|ERROR"

# Confirm no password leaks
docker compose logs migrations | grep password
# Expected: Only "***REDACTED***" visible
```

### Application Readiness Verification
```bash
# Check app is running
docker compose ps starexec
# Expected: Up

# Test application
curl http://localhost:8080/starexec/

# Check database connectivity
docker compose exec starexec psql -U starexec -d starexec \
  -c "SELECT COUNT(*) FROM flyway_schema_history;"
```

---

## Architectural Principles Applied

### 1. DRY (Don't Repeat Yourself)
- **Old:** Configuration repeated across two files
- **New:** Single source of truth, no duplication

### 2. Principle of Least Surprise
- **Old:** Users surprised when migrations don't run (forgot override)
- **New:** Expected behavior matches obvious deployment command

### 3. Operational Clarity
- **Old:** Must explain two-file pattern to every new operator
- **New:** Self-documenting; one file tells complete story

### 4. Separation of Concerns
- **Old:** Concerns separated across files (still applies)
- **New:** Concerns separated within one file with clear sections

### 5. Simplicity
- **Old:** Extra flag and documentation overhead
- **New:** Simple command, comprehensive inline documentation

---

## Impact on Other Deployment Targets

### Docker Compose ✅
- Single command: `docker compose up -d`
- Complete behavior obvious from file
- No special flags or documentation needed

### Podman ✅
- Makefile still supports override if needed: `docker-compose.podman.yml`
- Base Podman deployment uses consolidated file
- No changes required to existing Makefile targets

### Kubernetes/Helm ✅
- Not affected (separate Helm chart structure)
- Kubernetes patterns already consolidate all resources in templates
- Helm chart remains unchanged

---

## Migration Path

### For Existing Deployments

**Step 1:** Update to new `docker-compose.yml`
```bash
git pull origin main
# Old docker-compose.migrations.yml is deleted
```

**Step 2:** Deploy using simple command
```bash
docker compose up -d
```

**Step 3:** Verify behavior is identical
```bash
docker compose logs migrations | grep SUCCESS
docker compose ps  # All three services should be up/exited as expected
```

### Rollback (If Needed)

If any issue occurs, no special action needed:
```bash
docker compose down  # Stops all services normally
# Volumes preserved automatically
```

---

## Known Edge Cases

### What if PostgreSQL takes longer to start?
- ✅ Health check handles this automatically
- Migrations wait until `service_healthy`
- No arbitrary timing; actual readiness condition

### What if migrations fail?
- ✅ Application doesn't start (depends on migration exit code)
- Database remains in consistent state
- Operator can investigate and retry

### What if someone wants to run without migrations?
- ✅ Can temporarily set `SKIP_MIGRATIONS=true` in starexec service
- Must be intentional (explicit env var change)
- Discourages accidental misconfiguration

### What if someone forgets to set database password?
- ✅ Uses secure default for development
- Override via `STAREXEC_DB_PASSWORD` environment variable
- Clear in file that defaults are development-only

---

## Documentation Changes Required

### Before
- `README.md` explained override file pattern
- Separate docs for migrations
- Multiple examples with different flags

### After
- `README.md` simplified to: `docker compose up -d`
- `docker-compose.yml` itself fully documented with inline comments
- Single example, standard Docker Compose behavior
- Inline comments explain each service's purpose

### Supporting Documentation
- `OPERATIONAL_RUNBOOK.md` - Deployment procedures
- `ARCHITECTURE_IMPROVEMENTS.md` - Design decisions
- `PODMAN_HELM_COMPATIBILITY.md` - Multi-platform guidance

---

## Comparison with Industry Standards

### Docker Compose Best Practices
- ✅ Single authoritative compose file for standard deployment
- ✅ Override files for environment-specific variations (not core functionality)
- ✅ Inline comments for self-documentation
- ✅ Clear service dependencies and health checks

### Why Not Keep Separate Files?

**Docker Compose Intended Use of Override Files:**
- Override files are for environment-specific variations
- Example: `docker-compose.prod.yml` for production credentials
- NOT for core architectural concerns (migrations)

**Our Use Case:**
- Migrations are core architectural requirement, not optional variation
- All deployments need migrations (dev, prod, test)
- Should not be "optional" or "override-able"

---

## Testing Verification

### Test Scenario 1: Fresh Deployment
```bash
docker compose down -v  # Clean slate
docker compose up -d
# Expected: All services start, migrations run, app becomes ready
```

### Test Scenario 2: Restart (Volumes Preserved)
```bash
docker compose down      # Stops containers, keeps volumes
docker compose up -d
# Expected: App starts quickly, no migrations re-run (schema already up-to-date)
```

### Test Scenario 3: Explicit Credential Override
```bash
export STAREXEC_DB_PASSWORD="my-secure-password"
docker compose up -d
# Expected: Uses provided password, all services start correctly
```

### Test Scenario 4: Service Restart
```bash
docker compose ps  # All healthy
docker compose restart starexec
# Expected: App restarts, database connection re-established
```

---

## Sign-Off

**Architectural Decision:** Consolidate docker-compose files  
**Status:** ✅ Implementation Complete  
**Approval:** Dr. Alexandria Reeves, Chief Architect  

**Rationale Summary:**
- Single source of truth (DRY principle)
- Impossible to deploy incompletely
- Operational clarity for team
- Aligns with Docker Compose best practices
- No loss of functionality or flexibility
- Simplified documentation and onboarding

**Effective Date:** December 10, 2024  
**Impact:** Development, staging, and production deployments  
**Risk Level:** Low (behavioral equivalence maintained)

---

## Quick Reference

| Question | Answer |
|----------|--------|
| How do I deploy? | `docker compose up -d` |
| How do I check migrations? | `docker compose logs migrations` |
| How do I set the password? | `export STAREXEC_DB_PASSWORD="..."`  then deploy |
| How do I use a specific image version? | `docker compose up -d` (uses `STAREXEC_VERSION` env var) |
| What if I need environment-specific config? | Use `docker-compose.ENVIRONMENT.yml` override (e.g., prod values) |
| Where do I find documentation? | Inline comments in `docker-compose.yml` + `OPERATIONAL_RUNBOOK.md` |

---

**End of Consolidation Decision Record**

This consolidation represents a maturation of the deployment approach from a "clever override pattern" to a "clear, maintainable, self-documenting architecture."