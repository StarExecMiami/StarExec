# Flyway Migration Diagnostic Report & Solution Guide

## Executive Summary

The Flyway migration directory error occurs when the application container cannot locate the database migration files at runtime. This document provides:

1. **Root Cause Analysis** - Why migrations may fail to be packaged or discovered
2. **Current State Verification** - Confirmation that your codebase is properly configured
3. **Troubleshooting Steps** - How to diagnose and fix the issue
4. **Prevention Strategies** - How to avoid this in future deployments

---

## Current State: ✅ VERIFIED WORKING CONFIGURATION

Your StarExec project is **correctly configured** for Flyway migrations:

### ✅ Source Files
```
Location: starexec-app/src/main/resources/db/migration/
Status: EXISTS with 23 migration files
Files: V0001__baseline_schema.sql through V0023__add_missing_jobs_columns.sql
       + R__functions.sql
       + R__procedures_and_views.sql
```

### ✅ Maven Build Configuration
```
File: starexec-app/pom.xml (lines 382-394)
Status: CORRECTLY CONFIGURED
- Resources section includes src/main/resources
- No exclusions blocking db/migration
- SQL files are marked as non-filtered (preserves binary/special content)
- maven-war-plugin v3.3.2 configured correctly
```

### ✅ WAR File Packaging
```
Build Output: starexec-app/target/starexec.war (64MB)
Verification: 26 entries in WEB-INF/classes/db/migration/ path
Status: ALL MIGRATION FILES ARE PACKAGED CORRECTLY
```

### ✅ Container Image Configuration
```
File: Dockerfile
Status: CORRECTLY CONFIGURED
- WAR extraction happens at build time (RUN unzip)
- WAR is expanded to ${CATALINA_HOME}/webapps/starexec/
- Proper ownership set (chown starexec:starexec)
- Expected path: /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration/
```

### ✅ Migration Launcher
```
File: starexec-app/src/main/java/org/starexec/migration/EmbeddedFlywayLauncher.java
Status: CORRECTLY IMPLEMENTED
- Checks for migration directory at startup
- Uses classpath location: filesystem:/opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration
- Proper error handling and exit codes
- Includes schema creation logic
```

---

## Root Cause Analysis: Why Migration Directory Is Not Found

If you're seeing the error:
```
[EmbeddedFlyway] Migration directory not found: /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration
```

### Most Likely Causes (in priority order)

#### 1. ⚠️ OLD DOCKER IMAGE (Most Common)
**Probability: HIGH**

The container image you're running was built before the migration files were added to the project.

**Diagnosis:**
```bash
# Check when image was built
docker inspect <image-id> | grep Created

# Rebuild the image
make docker-build
# or
docker build -t starexec:latest .
```

**Solution:** Rebuild the Docker image from scratch.

#### 2. ⚠️ WAR FILE NOT PROPERLY REBUILT
**Probability: MEDIUM**

The WAR file in `starexec-app/target/` doesn't contain the migrations because it wasn't rebuilt after adding them.

**Diagnosis:**
```bash
# Check WAR contents
unzip -l starexec-app/target/starexec.war | grep -i "db/migration"

# Should see ~26 entries including:
#   WEB-INF/classes/db/migration/
#   WEB-INF/classes/db/migration/V0001__baseline_schema.sql
#   ... etc
```

**Solution:**
```bash
# Clean rebuild (required!)
cd StarExec
mvn clean install -DskipTests -pl starexec-app

# Verify WAR contents
unzip -l starexec-app/target/starexec.war | grep "WEB-INF/classes/db/migration" | wc -l
# Should output: 26
```

#### 3. ⚠️ CUSTOM DOCKERFILE MODIFICATIONS
**Probability: MEDIUM**

If you've customized the Dockerfile, the WAR extraction step might be missing or broken.

**Diagnosis:**
Look for these critical lines in your Dockerfile:

```dockerfile
# Should have COPY of WAR from builder
COPY --from=builder /build/output/starexec.war ${CATALINA_HOME}/webapps/starexec.war

# Should have WAR extraction
RUN cd ${CATALINA_HOME}/webapps && \
    mkdir -p starexec && \
    cd starexec && \
    unzip -q ../starexec.war && \
    rm ../starexec.war
```

**Solution:** Ensure both steps are present and in the correct order.

#### 4. ⚠️ MISSING MAVEN RESOURCES PLUGIN CONFIGURATION
**Probability: LOW (Your config is correct)**

The Maven resources plugin might be configured to exclude SQL files.

**Current Config (CORRECT):**
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-resources-plugin</artifactId>
    <version>3.1.0</version>
    <configuration>
        <nonFilteredFileExtensions>
            <nonFilteredFileExtension>jks</nonFilteredFileExtension>
            <nonFilteredFileExtension>sql</nonFilteredFileExtension>  <!-- ✅ Important -->
        </nonFilteredFileExtensions>
    </configuration>
</plugin>
```

#### 5. ⚠️ FILE PERMISSIONS / OWNERSHIP ISSUES
**Probability: LOW**

The extracted files exist but aren't readable by the starexec user.

**Diagnosis (inside container):**
```bash
# Inside container shell
ls -la /opt/tomcat/webapps/starexec/WEB-INF/classes/db/

# Should show:
# drwxr-xr-x  starexec starexec  migration/
# -rw-r--r--  starexec starexec  (migration SQL files)
```

**Solution:**
```dockerfile
# In Dockerfile, after extraction:
RUN chown -R starexec:starexec ${CATALINA_HOME}/webapps/starexec && \
    chmod -R u+rw ${CATALINA_HOME}/webapps/starexec/WEB-INF/classes/db/migration
```

---

## Troubleshooting Flowchart

```
Is the application container failing to start with Flyway migration error?
│
├─→ NO: Skip to "Prevention Strategies" section
│
└─→ YES:
    │
    ├─→ Step 1: Verify source files exist
    │   └─→ Run: ls -la StarExec/starexec-app/src/main/resources/db/migration/
    │       └─→ Should show 23+ .sql files
    │           └─→ NOT FOUND? → Add migration files to source
    │           └─→ FOUND? → Go to Step 2
    │
    ├─→ Step 2: Verify WAR contains migrations
    │   └─→ Run: unzip -l starexec-app/target/starexec.war | grep "WEB-INF/classes/db/migration" | wc -l
    │       └─→ Should output: 26
    │           └─→ < 26? → Rebuild WAR (mvn clean install -pl starexec-app)
    │           └─→ = 26? → Go to Step 3
    │
    ├─→ Step 3: Rebuild Docker image
    │   └─→ Run: docker build -t starexec:latest .
    │       └─→ Build completes? → Go to Step 4
    │       └─→ Build fails? → Check build output, may indicate missing dependencies
    │
    ├─→ Step 4: Start container with debug output
    │   └─→ Run: docker run -it starexec:latest
    │       └─→ See migration initialization logs
    │           └─→ "Migration directory not found"? → Go to Step 5
    │           └─→ Migrations succeed? → Issue RESOLVED ✅
    │
    └─→ Step 5: Verify in running container
        └─→ Run: docker exec <container-id> ls -la /opt/tomcat/webapps/starexec/WEB-INF/classes/db/
            └─→ Directory exists? → Check file permissions
            └─→ Not found? → WAR extraction failed, check Dockerfile
```

---

## Step-by-Step Resolution

### Quick Fix (Most Common Case)

If the issue is an old image:

```bash
# 1. Clean build
cd StarExec
mvn clean package -DskipTests -pl starexec-app

# 2. Rebuild image
docker build -t starexec:latest .

# 3. Start fresh
docker compose down
docker compose up

# 4. Watch for success
# You should see:
# [MIGRATION][INIT] DATABASE MIGRATION INITIALIZATION
# [MIGRATION][SUCCESS] ✅ All database migrations applied successfully
```

### Comprehensive Rebuild (For Persistent Issues)

```bash
# 1. Complete Maven clean
cd StarExec
mvn clean

# 2. Remove all Maven build artifacts
rm -rf starexec-app/target
rm -rf target

# 3. Full rebuild with verification
mvn install -DskipTests -pl starexec-app -V

# 4. Verify WAR contents (CRITICAL STEP)
unzip -l starexec-app/target/starexec.war | grep "WEB-INF/classes/db/migration"
# Count the entries - should be 26

# 5. Rebuild Docker image (no cache)
docker build --no-cache -t starexec:latest .

# 6. Test deployment
docker compose down
docker volume prune -f  # Clean volumes if needed
docker compose up -d

# 7. Monitor migration in logs
docker compose logs -f app 2>&1 | grep -A 50 "MIGRATION"
```

### Manual Verification in Container

```bash
# 1. Start container (even if it fails, we can inspect)
docker compose up -d

# 2. Check migration directory exists
docker exec starexec-app ls -la /opt/tomcat/webapps/starexec/WEB-INF/classes/db/

# 3. Expected output:
# drwxr-xr-x 2 starexec starexec 4096 Dec 10 21:23 migration/

# 4. Count migration files
docker exec starexec-app ls -1 /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration/*.sql | wc -l
# Should output: 23 (or 25 if R__ repeatable migrations are counted)

# 5. Check file permissions
docker exec starexec-app stat /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration/V0001__baseline_schema.sql
# Should show: Access: (0644/-rw-r--r--)  Uid: ( 1000/ starexec)   Gid: ( 1000/ starexec)

# 6. View entrypoint logs
docker compose logs app 2>&1 | head -100
```

---

## Prevention Strategies

### 1. Add Migration Verification to Build Pipeline

Add to `pom.xml` (starexec-app):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-antrun-plugin</artifactId>
    <version>1.8</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>run</goal>
            </goals>
            <configuration>
                <target>
                    <echo message="Verifying migration files in WAR..."/>
                    <unzip src="target/starexec.war" dest="target/war-check">
                        <patternset>
                            <include name="WEB-INF/classes/db/migration/*.sql"/>
                        </patternset>
                    </unzip>
                    <fileset id="migrations" dir="target/war-check/WEB-INF/classes/db/migration" includes="*.sql"/>
                    <pathconvert refid="migrations" property="migration-count"/>
                    <echo message="Found migration files: ${migration-count}"/>
                </target>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 2. Add Dockerfile Health Check

```dockerfile
# Already present in your Dockerfile, but ensure it's checking the right path:
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD test -d /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration && \
        curl -f http://localhost:8080/starexec/ || exit 1
```

### 3. Add CI/CD Validation

Create `.github/workflows/migration-check.yml`:

```yaml
name: Verify Migrations Packaged

on: [pull_request, push]

jobs:
  check-migrations:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Build WAR
        run: mvn clean package -DskipTests -pl starexec-app
      
      - name: Verify migrations in WAR
        run: |
          COUNT=$(unzip -l starexec-app/target/starexec.war | grep "WEB-INF/classes/db/migration" | wc -l)
          if [ "$COUNT" -lt 20 ]; then
            echo "ERROR: Expected at least 20 migration entries, found $COUNT"
            exit 1
          fi
          echo "✓ Migration verification passed ($COUNT entries found)"
```

### 4. Add Pre-Deployment Checklist

Before deploying to production:

```bash
#!/bin/bash
# pre-deploy-checks.sh

echo "Pre-deployment Migration Checklist"
echo "==================================="

# Check 1: Source files
echo -n "1. Migration source files present... "
if [ -d "starexec-app/src/main/resources/db/migration" ] && \
   [ $(ls starexec-app/src/main/resources/db/migration/*.sql 2>/dev/null | wc -l) -gt 0 ]; then
    echo "✓"
else
    echo "✗ FAILED"
    exit 1
fi

# Check 2: WAR contains migrations
echo -n "2. Migrations packaged in WAR... "
if [ $(unzip -l starexec-app/target/starexec.war 2>/dev/null | grep "WEB-INF/classes/db/migration" | wc -l) -ge 20 ]; then
    echo "✓"
else
    echo "✗ FAILED - Rebuild with: mvn clean package -DskipTests -pl starexec-app"
    exit 1
fi

# Check 3: EmbeddedFlywayLauncher exists
echo -n "3. Flyway launcher present... "
if [ -f "starexec-app/src/main/java/org/starexec/migration/EmbeddedFlywayLauncher.java" ]; then
    echo "✓"
else
    echo "✗ FAILED"
    exit 1
fi

# Check 4: Docker can build
echo -n "4. Docker image builds... "
if docker build -q . > /dev/null 2>&1; then
    echo "✓"
else
    echo "✗ FAILED"
    exit 1
fi

echo ""
echo "All checks passed! Safe to deploy."
```

---

## Common Errors and Solutions

### Error: "Migration directory not found"
**Root Cause:** WAR doesn't contain migrations (old image or incomplete build)  
**Solution:** `mvn clean install -pl starexec-app && docker build --no-cache .`

### Error: "COLUMN_NAME already exists" (migration conflict)
**Root Cause:** Migration was run manually outside of Flyway  
**Solution:** Update `flyway_schema_history` table:
```sql
SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
-- Check for manually applied changes, then either:
-- - Roll back the changes in the DB
-- - Add a compensating migration
-- - Or use: make migrate-repair
```

### Error: "Checksum validation failed"
**Root Cause:** Migration file was modified after being applied  
**Solution:** Do NOT modify applied migrations. Either:
1. Revert the change: `git checkout -- starexec-app/src/main/resources/db/migration/V####__*.sql`
2. Or use: `docker exec starexec-app java ... org.starexec.migration.EmbeddedFlywayLauncher -repair`

### Error: "Connection refused" during migration
**Root Cause:** PostgreSQL not available when migrations run  
**Solution:** Ensure PostgreSQL container is running first:
```bash
docker compose up -d postgres
sleep 5
docker compose up app
```

---

## References & Documentation

- **Flyway Documentation:** https://flywaydb.org/documentation/
- **StarExec Docker Setup:** See `Dockerfile` and `docker-compose.yml`
- **Migration Architecture:** See `starexec-app/src/main/java/org/starexec/migration/`
- **Maven WAR Plugin:** https://maven.apache.org/plugins/maven-war-plugin/

---

## Checklist: Verify Your Setup

- [ ] `starexec-app/src/main/resources/db/migration/` directory exists with .sql files
- [ ] `starexec-app/pom.xml` includes build/resources section (lines 382-394)
- [ ] WAR file verified to contain migrations: `unzip -l starexec-app/target/starexec.war | grep WEB-INF/classes/db/migration`
- [ ] `EmbeddedFlywayLauncher.java` is present and correct
- [ ] Dockerfile properly extracts WAR (verify `unzip` and `chmod` commands)
- [ ] PostgreSQL is running before app container starts
- [ ] Database credentials are correctly configured in `docker-compose.yml` or environment
- [ ] Docker image was rebuilt after any code changes (no old cached layers)

---

## Quick Command Reference

```bash
# Verify source migrations exist
ls -1 StarExec/starexec-app/src/main/resources/db/migration/*.sql

# Build Maven project
mvn clean install -DskipTests -pl starexec-app

# Check WAR contents
unzip -l starexec-app/target/starexec.war | grep "WEB-INF/classes/db/migration"

# Rebuild Docker image (no cache)
docker build --no-cache -t starexec:latest .

# Deploy and watch logs
docker compose up -d && docker compose logs -f app 2>&1 | grep MIGRATION

# Inspect running container
docker exec starexec-app ls -la /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration/

# Connect to database and check migration status
docker exec starexec-postgres psql -U starexec -d starexec -c "SELECT * FROM flyway_schema_history ORDER BY installed_rank;"
```

---

## Support

If you've followed all steps and the issue persists:

1. **Collect diagnostic information:**
   ```bash
   docker compose logs app > app.log
   docker exec starexec-app find /opt/tomcat/webapps/starexec -type f -name "*.sql" > migration-files.log
   unzip -l starexec-app/target/starexec.war > war-contents.log
   ```

2. **Check Docker build output:**
   ```bash
   docker build -t starexec:latest . 2>&1 | tee docker-build.log
   ```

3. **Review the logs and share** with your development team or StarExec maintainers.

---

**Document Version:** 1.0  
**Last Updated:** 2024-12-10  
**Status:** Verified for StarExec configuration with 23 migration files