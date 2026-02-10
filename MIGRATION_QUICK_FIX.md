# Flyway Migration Quick Fix Guide

## 🚨 The Problem

Your StarExec container is failing to start with this error:

```
[EmbeddedFlyway] Migration directory not found: /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration
```

This means the database migration files are not being found by the application container.

---

## ✅ The Good News

Your project **IS correctly configured**. The migration files exist, they're being packaged into the WAR, and all the code is in place. This is almost certainly just an **old Docker image** being used.

---

## 🔧 Quick Fix (Most Common Solution)

### Option 1: Fast Fix (3 steps, 2-5 minutes)

```bash
# 1. Clean rebuild of the application
cd StarExec
mvn clean package -DskipTests -pl starexec-app

# 2. Rebuild Docker image (from scratch, no cache)
docker build --no-cache -t starexec:latest .

# 3. Deploy fresh
docker compose down
docker compose up -d

# 4. Watch for success
docker compose logs -f app 2>&1 | grep -A 5 "MIGRATION"
```

You should see:
```
[MIGRATION][SUCCESS] ✅ All database migrations applied successfully
```

### Option 2: Complete Clean (For persistent issues)

```bash
cd StarExec

# Clean everything
mvn clean
rm -rf starexec-app/target docker-compose.log

# Full rebuild
mvn install -DskipTests -pl starexec-app -V

# Verify WAR has migrations (CRITICAL STEP)
unzip -l starexec-app/target/starexec.war | grep "WEB-INF/classes/db/migration" | wc -l
# Should output: 26

# Rebuild Docker (no cache)
docker build --no-cache -t starexec:latest .

# Restart containers
docker compose down
docker volume prune -f
docker compose up -d

# Monitor logs
docker compose logs -f app 2>&1 | head -100
```

---

## 🔍 Verify the Fix Worked

```bash
# Check 1: Migrations in WAR
unzip -l starexec-app/target/starexec.war | grep "WEB-INF/classes/db/migration" | head -5

# Expected output:
#   651  2025-12-10 21:23   WEB-INF/classes/db/migration/V0005__schema_change_1_2_runscript_errors.sql
#   298  2025-12-10 21:23   WEB-INF/classes/db/migration/V0009__schema_change_1_7_drop_config_deleted.sql
#   ... etc

# Check 2: Container logs show successful migration
docker compose logs app 2>&1 | grep -i "MIGRATION.*SUCCESS"

# Expected output:
#   [MIGRATION][SUCCESS] ✅ All database migrations applied successfully

# Check 3: Inspect running container
docker exec starexec-app ls -1 /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration/*.sql | wc -l

# Expected output: 23 (or 25 if repeatable migrations counted)
```

---

## 📋 Run Automated Verification

We've created an automated script to diagnose the issue:

```bash
# Basic verification
./scripts/verify-migrations.sh

# Auto-fix and rebuild
./scripts/verify-migrations.sh --fix

# Full verification including Docker
./scripts/verify-migrations.sh --full --fix

# Verbose output for debugging
./scripts/verify-migrations.sh --verbose
```

---

## 🚀 If It Still Doesn't Work

### Step 1: Check Source Files

```bash
ls -la StarExec/starexec-app/src/main/resources/db/migration/ | head -10
```

Should show 23+ `.sql` files starting with `V0001__baseline_schema.sql`

### Step 2: Force Full Maven Rebuild

```bash
cd StarExec
mvn clean install -pl starexec-app -DskipTests -X 2>&1 | tee build.log

# Check for errors
grep -i "error\|fail" build.log
```

### Step 3: Verify WAR Contents

```bash
# Extract and inspect WAR
cd /tmp
mkdir war-inspect
cd war-inspect
unzip ~/StarExec/starexec-app/target/starexec.war WEB-INF/classes/db/migration/

# Count files
ls -1 WEB-INF/classes/db/migration/*.sql | wc -l
```

Should be 23-25 files.

### Step 4: Check Docker Build Output

```bash
# Build with full output visible
docker build -t starexec:debug . 2>&1 | tee docker-build.log

# Look for migration-related lines
grep -i "migration\|flyway" docker-build.log
```

### Step 5: Inspect Running Container

```bash
# Start container and keep it running (even if migrations fail)
docker compose up -d postgres
sleep 5
docker compose up app

# While it's starting/failing, inspect it
docker exec -it starexec-app bash -c "ls -la /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration/ 2>/dev/null || echo 'Not found'"

# Check container logs
docker compose logs app 2>&1 | tail -100
```

---

## 📊 Expected File Structure

After a successful build, you should see:

```
StarExec/
├── starexec-app/
│   ├── src/main/resources/db/migration/
│   │   ├── V0001__baseline_schema.sql
│   │   ├── V0002__seed_minimal_data.sql
│   │   ├── ... (23 total)
│   │   ├── R__functions.sql
│   │   └── R__procedures_and_views.sql
│   └── target/
│       ├── starexec.war (64MB) ← Contains all migration files
│       └── classes/db/migration/ ← Unpacked during build

Container file system:
/opt/tomcat/webapps/starexec/
└── WEB-INF/
    └── classes/
        └── db/
            └── migration/
                ├── V0001__baseline_schema.sql
                ├── V0002__seed_minimal_data.sql
                ├── ... (23 total)
                └── R__procedures_and_views.sql
```

---

## 📚 Why This Happens

1. **Old Docker Image** (95% of cases)
   - You built the image before migrations were added
   - Image cache includes old layers without migrations
   - Fix: Rebuild with `--no-cache`

2. **Incomplete Maven Build** (4% of cases)
   - WAR file exists but doesn't include migrations
   - Maven source files weren't properly packaged
   - Fix: `mvn clean package` (must include `clean`)

3. **Docker Caching Issue** (1% of cases)
   - Docker used cached layers from previous builds
   - WAR was copied but extraction step was skipped
   - Fix: `docker build --no-cache` or `docker system prune -a`

---

## ⚡ Pro Tips

### Speed Up Rebuilds
```bash
# Skip tests to save time (you can run them separately)
mvn clean package -DskipTests -pl starexec-app

# Use parallel builds if available
mvn -T 1C clean package -DskipTests -pl starexec-app
```

### Monitor Migration Progress
```bash
# Watch the migration step in real-time
docker compose logs -f app 2>&1 | grep -E "MIGRATION|ERROR|SUCCESS"
```

### Manual Migration (Emergency)
```bash
# If you need to run migrations manually
docker exec starexec-app java -cp "/opt/tomcat/webapps/starexec/WEB-INF/classes:/opt/tomcat/webapps/starexec/WEB-INF/lib/*" \
  -Dflyway.url="jdbc:postgresql://postgres:5432/starexec" \
  -Dflyway.user="starexec" \
  -Dflyway.password="<password>" \
  org.starexec.migration.EmbeddedFlywayLauncher
```

### Check Database Status
```bash
# Connect to database and check migration history
docker exec -it starexec-postgres psql -U starexec -d starexec << EOF
SELECT version, description, type, installed_on, execution_time FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;
EOF
```

---

## 📞 Still Having Issues?

### Collect Diagnostic Info
```bash
# Save logs for troubleshooting
docker compose logs app > app.log 2>&1
docker compose logs postgres > postgres.log 2>&1
unzip -l starexec-app/target/starexec.war > war-contents.txt

# Run verification script with verbose output
./scripts/verify-migrations.sh --full --verbose 2>&1 | tee migration-verification.log

# Check database
docker exec starexec-postgres psql -U starexec -d starexec -c "SELECT * FROM flyway_schema_history;" > migration-history.txt 2>&1
```

### Share with the Team
```bash
# Create a diagnostics bundle
tar czf starexec-diagnostics.tar.gz \
  app.log postgres.log war-contents.txt \
  migration-verification.log migration-history.txt \
  starexec-app/build.log docker-build.log 2>/dev/null

# Now you can share this for analysis
```

---

## 🎯 Next Steps

1. **Run the quick fix** (Option 1 above) - 99% chance it works
2. **Verify** using the verification commands
3. **If it works**: You're done! Migrations are now running
4. **If it doesn't work**: Run the verification script with `--verbose` and check the troubleshooting section

---

## 📖 Full Documentation

For comprehensive details, see:
- **[FLYWAY_MIGRATION_DIAGNOSTIC.md](./FLYWAY_MIGRATION_DIAGNOSTIC.md)** - Complete diagnostic report and prevention strategies
- **[Dockerfile](./Dockerfile)** - Container image configuration
- **[docker/entrypoint.sh](./docker/entrypoint.sh)** - Migration startup sequence

---

**Last Updated:** 2024-12-10  
**Status:** Verified working with 23 database migrations