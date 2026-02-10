# GitHub Actions Workflow Updates

## Overview
The Docker Compose configuration has been refactored to improve maintainability and clarity. This document describes the updates required to GitHub Actions workflows to accommodate these changes.

## What Changed

### New Files
- **`docker/migrations.sh`** - Standalone database migration script (previously inline in docker-compose.yml)
- **`.env`** - Environment configuration file with default values for local development

### Modified Files
- **`docker-compose.yml`** - Now uses external migration script and builds locally instead of pulling from GHCR
- **`Dockerfile`** - Includes the new migrations.sh script in the Docker image

## GitHub Actions Workflows Updated

### 1. Integration Test Workflow (`.github/workflows/integration-test.yml`)

#### Changes Made
- **Added trigger paths:**
  - `docker/migrations.sh` - Ensures tests run when migration script changes
  - `.env` - Ensures tests run when environment configuration changes

- **Enhanced environment variable setup:**
  ```yaml
  env:
    STAREXEC_DB_PASSWORD: ci_test_password_secure_12345
    STAREXEC_DB_HOST: postgres
    STAREXEC_DB_PORT: 5432
    STAREXEC_DB_NAME: starexec
    STAREXEC_DB_USER: starexec
    STAREXEC_DB_SCHEMA: starexec
    STAREXEC_VERSION: ci-test-latest
    VOLUME_PREFIX: starexec-ci
  ```

#### Why These Changes Matter
- **Path triggers:** Any changes to the migration script or environment configuration will automatically trigger tests
- **Explicit environment variables:** Makes the CI environment match production more closely and documents all required variables
- **Volume prefix:** Prevents conflicts when running multiple CI jobs in parallel

### 2. Container Build Workflow (`.github/workflows/container-build.yml`)

#### Changes Made
- **Added trigger path:** `docker/migrations.sh`

#### Why This Matters
- Ensures the container build job is triggered whenever the migration script is modified
- Validates that the script is correctly included in the Docker image

### 3. Container Publish Workflow (`.github/workflows/container-publish.yml`)

#### Changes Made
- **Added trigger path:** `docker/migrations.sh`
- **Standardized path format** (single vs. double quotes for consistency)

#### Why This Matters
- Ensures published images include any updates to the migration script
- Prevents publishing outdated container images when only the migration script changes

## Testing the Workflows

### Local Testing
To verify the workflows will work correctly:

```bash
# Test that migrations.sh is executable
docker run --rm starexec-starexec test -x /usr/local/bin/migrations.sh && echo "✓ Script is executable"

# Test that docker-compose.yml works with environment variables
export STAREXEC_DB_PASSWORD=test_password
docker compose config > /dev/null && echo "✓ docker-compose.yml is valid"

# Test the full integration
docker compose up -d --build
docker compose ps
docker compose down -v
```

### CI Verification
The workflows will automatically verify:
1. ✅ Migrations packaged correctly in WAR file
2. ✅ Docker Compose builds and starts services
3. ✅ PostgreSQL initializes correctly
4. ✅ Migration script executes and applies 25 migrations
5. ✅ Credentials are properly redacted in logs
6. ✅ StarExec application starts and is healthy
7. ✅ Database schema is up-to-date

## Environment Variables Reference

### Required for CI/CD
- `STAREXEC_DB_PASSWORD` - Database password (use secure value in CI)
- `STAREXEC_VERSION` - Image version tag (use `ci-test-latest` in CI)

### Optional (with defaults)
- `STAREXEC_DB_HOST` - Database hostname (default: `postgres`)
- `STAREXEC_DB_PORT` - Database port (default: `5432`)
- `STAREXEC_DB_NAME` - Database name (default: `starexec`)
- `STAREXEC_DB_USER` - Database user (default: `starexec`)
- `STAREXEC_DB_SCHEMA` - Database schema (default: `starexec`)
- `VOLUME_PREFIX` - Docker volume prefix (default: `starexec`)

## Migration Script Details

### Location
- **In Docker image:** `/usr/local/bin/migrations.sh`
- **In source:** `docker/migrations.sh`

### Functionality
The script:
1. Validates database configuration
2. Waits for PostgreSQL to be ready
3. Verifies migration files exist
4. Executes Flyway migrations
5. Reports results and exits with appropriate code

### Security
- Credentials are passed via environment variables only
- No credentials are exposed in logs
- Password is redacted as `***REDACTED***` in output

## Backward Compatibility

These changes are fully backward compatible:
- The docker-compose.yml still accepts all previous environment variables
- The Dockerfile is a superset of the previous functionality
- All existing deployment scripts continue to work

## Troubleshooting

### If Migrations Don't Trigger
Check that `.github/workflows/integration-test.yml` includes:
```yaml
paths:
  - "docker/migrations.sh"
  - ".env"
```

### If Container Build Fails
Ensure `docker/migrations.sh` is:
- Executable (`chmod +x`)
- Valid bash syntax
- Included in `Dockerfile` via `COPY` command

### If CI Jobs Conflict
Use the `VOLUME_PREFIX` environment variable to give each job a unique volume prefix:
```yaml
env:
  VOLUME_PREFIX: starexec-ci-${{ github.run_id }}
```

## Future Considerations

1. **Migration versioning:** Consider adding version tags to migration scripts
2. **Secrets management:** Use GitHub Secrets for production database passwords
3. **Performance:** Cache Docker layers more aggressively in CI
4. **Monitoring:** Add metrics to track migration execution time

## Related Documentation

- See `DOCKER_COMPOSE_CONSOLIDATION.md` for architectural decisions
- See `Dockerfile` for container image details
- See `docker/migrations.sh` for migration script implementation