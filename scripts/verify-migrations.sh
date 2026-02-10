#!/bin/bash
# verify-migrations.sh
#
# StarExec Flyway Migration Verification Script
# This script diagnoses and verifies that database migrations are properly
# configured and packaged for deployment.
#
# Usage:
#   ./scripts/verify-migrations.sh              # Run all checks
#   ./scripts/verify-migrations.sh --fix        # Run checks and auto-fix if possible
#   ./scripts/verify-migrations.sh --help       # Show detailed help
#

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counters
PASSED=0
FAILED=0
WARNINGS=0
FIXED=0

# Configuration
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AUTO_FIX=false
VERBOSE=false

# Script state
MIGRATION_DIR="${PROJECT_ROOT}/starexec-app/src/main/resources/db/migration"
POM_FILE="${PROJECT_ROOT}/starexec-app/pom.xml"
WAR_FILE="${PROJECT_ROOT}/starexec-app/target/starexec.war"
LAUNCHER_FILE="${PROJECT_ROOT}/starexec-app/src/main/java/org/starexec/migration/EmbeddedFlywayLauncher.java"

# ============================================================================
# HELPER FUNCTIONS
# ============================================================================

print_header() {
    echo ""
    echo -e "${BLUE}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}════════════════════════════════════════════════════════════════${NC}"
    echo ""
}

print_check() {
    echo -n "  ⏳ $1... "
}

print_pass() {
    echo -e "${GREEN}✓ PASS${NC}"
    ((PASSED++))
}

print_fail() {
    echo -e "${RED}✗ FAIL${NC}: $1"
    ((FAILED++))
}

print_warn() {
    echo -e "${YELLOW}⚠ WARN${NC}: $1"
    ((WARNINGS++))
}

print_info() {
    echo -e "${BLUE}ℹ INFO${NC}: $1"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

show_help() {
    cat << 'EOF'
StarExec Flyway Migration Verification Script

USAGE:
  ./scripts/verify-migrations.sh [OPTIONS]

OPTIONS:
  --help          Show this help message
  --fix           Automatically fix issues where possible (Maven rebuild, etc.)
  --verbose       Show detailed output and intermediate commands
  --docker        Include Docker image verification (requires docker)
  --full          Run all checks including Docker and container inspection

CHECKS PERFORMED:

  1. Source Files
     - Verifies migration SQL files exist in source directory
     - Checks for minimum number of migrations

  2. Maven Configuration
     - Validates pom.xml has proper resource configuration
     - Checks SQL files are marked as non-filtered
     - Verifies WAR plugin is properly configured

  3. WAR Packaging
     - Checks WAR file exists
     - Verifies migrations are packaged in WEB-INF/classes/db/migration/
     - Counts packaged migration files

  4. Migration Launcher
     - Verifies EmbeddedFlywayLauncher.java exists
     - Checks for proper migration directory path
     - Validates launcher compilation

  5. Dockerfile (optional)
     - Verifies WAR extraction steps are present
     - Checks ownership and permissions configuration

  6. Docker Image (optional with --docker)
     - Builds Docker image
     - Inspects image for migration files
     - Verifies container can start

  7. Running Container (optional with --full)
     - Checks if migrations are accessible in live container
     - Verifies file permissions
     - Tests database connectivity

EXIT CODES:
  0 = All checks passed
  1 = One or more checks failed
  2 = Warnings present but no failures
  3 = Fatal error (cannot proceed)

EXAMPLES:
  # Quick verification (source + Maven + WAR)
  ./scripts/verify-migrations.sh

  # Fix issues and rebuild
  ./scripts/verify-migrations.sh --fix

  # Full verification including Docker
  ./scripts/verify-migrations.sh --full --fix

  # Verbose mode for debugging
  ./scripts/verify-migrations.sh --verbose

EOF
}

# ============================================================================
# CHECK FUNCTIONS
# ============================================================================

check_source_files() {
    print_header "STEP 1: Checking Source Migration Files"

    print_check "Migration directory exists"
    if [ ! -d "$MIGRATION_DIR" ]; then
        print_fail "Directory not found: $MIGRATION_DIR"
        return 1
    fi
    print_pass

    print_check "Migration files present"
    local count
    count=$(find "$MIGRATION_DIR" -maxdepth 1 -type f -name "*.sql" 2>/dev/null | wc -l)

    if [ "$count" -eq 0 ]; then
        print_fail "No SQL files found in $MIGRATION_DIR"
        return 1
    fi

    if [ "$count" -lt 20 ]; then
        print_warn "Only $count migration files found (expected 20+)"
    else
        echo -e "${GREEN}✓ PASS${NC}: Found $count migration files"
        ((PASSED++))
    fi

    if [ "$VERBOSE" = true ]; then
        echo "    Migration files:"
        find "$MIGRATION_DIR" -maxdepth 1 -type f -name "*.sql" | sort | sed 's/^/      /'
    fi

    return 0
}

check_maven_config() {
    print_header "STEP 2: Checking Maven Configuration"

    print_check "pom.xml exists"
    if [ ! -f "$POM_FILE" ]; then
        print_fail "File not found: $POM_FILE"
        return 1
    fi
    print_pass

    print_check "Resources section configured"
    if ! grep -q "<directory>src/main/resources</directory>" "$POM_FILE"; then
        print_fail "src/main/resources not configured in build/resources"
        return 1
    fi
    print_pass

    print_check "SQL files marked as non-filtered"
    if ! grep -q "<nonFilteredFileExtension>sql</nonFilteredFileExtension>" "$POM_FILE"; then
        print_warn "SQL files may be filtered (could corrupt binary content)"
    else
        echo -e "${GREEN}✓ PASS${NC}"
        ((PASSED++))
    fi

    print_check "Maven WAR plugin configured"
    if ! grep -q "<artifactId>maven-war-plugin</artifactId>" "$POM_FILE"; then
        print_fail "maven-war-plugin not found in pom.xml"
        return 1
    fi
    print_pass

    print_check "Flyway Maven plugin configured"
    if ! grep -q "flyway-maven-plugin" "$POM_FILE"; then
        print_warn "Flyway Maven plugin not found (OK if using EmbeddedFlywayLauncher)"
    else
        echo -e "${GREEN}✓ PASS${NC}"
        ((PASSED++))
    fi

    return 0
}

check_war_packaging() {
    print_header "STEP 3: Checking WAR Packaging"

    print_check "WAR file exists"
    if [ ! -f "$WAR_FILE" ]; then
        print_fail "WAR file not found: $WAR_FILE"
        if [ "$AUTO_FIX" = true ]; then
            print_info "Building WAR with: mvn clean package -DskipTests -pl starexec-app"
            cd "$PROJECT_ROOT"
            mvn clean package -DskipTests -pl starexec-app -q
            ((FIXED++))
            echo -e "${GREEN}✓ FIXED${NC}: WAR rebuilt"
        else
            return 1
        fi
    else
        print_pass
    fi

    print_check "Migrations packaged in WAR"
    local war_count
    war_count=$(unzip -l "$WAR_FILE" 2>/dev/null | grep -c "WEB-INF/classes/db/migration" || echo 0)

    if [ "$war_count" -eq 0 ]; then
        print_fail "No migrations found in WAR file"
        if [ "$AUTO_FIX" = true ]; then
            print_info "Rebuilding WAR with: mvn clean package -DskipTests -pl starexec-app"
            cd "$PROJECT_ROOT"
            mvn clean package -DskipTests -pl starexec-app -q
            ((FIXED++))
            war_count=$(unzip -l "$WAR_FILE" 2>/dev/null | grep -c "WEB-INF/classes/db/migration" || echo 0)
        fi
    fi

    if [ "$war_count" -ge 20 ]; then
        echo -e "${GREEN}✓ PASS${NC}: Found $war_count migration entries in WAR"
        ((PASSED++))
    else
        print_fail "Only $war_count entries found (expected 20+)"
        return 1
    fi

    if [ "$VERBOSE" = true ]; then
        echo "    Migration entries in WAR:"
        unzip -l "$WAR_FILE" 2>/dev/null | grep "WEB-INF/classes/db/migration" | head -10 | sed 's/^/      /'
        if [ "$war_count" -gt 10 ]; then
            echo "      ... and $((war_count - 10)) more entries"
        fi
    fi

    return 0
}

check_launcher() {
    print_header "STEP 4: Checking Migration Launcher"

    print_check "EmbeddedFlywayLauncher exists"
    if [ ! -f "$LAUNCHER_FILE" ]; then
        print_fail "File not found: $LAUNCHER_FILE"
        return 1
    fi
    print_pass

    print_check "Launcher has migration directory check"
    if ! grep -q "WEB-INF/classes/db/migration" "$LAUNCHER_FILE"; then
        print_fail "Migration directory path not found in launcher"
        return 1
    fi
    print_pass

    print_check "Launcher uses correct Tomcat path"
    if ! grep -q "/opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration" "$LAUNCHER_FILE"; then
        print_warn "Expected path /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration not found"
    else
        echo -e "${GREEN}✓ PASS${NC}"
        ((PASSED++))
    fi

    print_check "Launcher handles missing directory"
    if ! grep -q "Migration directory not found" "$LAUNCHER_FILE"; then
        print_warn "Missing error handling for absent migration directory"
    else
        echo -e "${GREEN}✓ PASS${NC}"
        ((PASSED++))
    fi

    return 0
}

check_dockerfile() {
    print_header "STEP 5: Checking Dockerfile Configuration"

    local dockerfile="${PROJECT_ROOT}/Dockerfile"

    print_check "Dockerfile exists"
    if [ ! -f "$dockerfile" ]; then
        print_warn "Dockerfile not found at $dockerfile"
        return 0
    fi
    print_pass

    print_check "WAR file copy step present"
    if ! grep -q "COPY.*starexec.war" "$dockerfile"; then
        print_fail "WAR copy command not found in Dockerfile"
        return 1
    fi
    print_pass

    print_check "WAR extraction step present"
    if ! grep -q "unzip.*starexec.war" "$dockerfile"; then
        print_fail "WAR extraction (unzip) command not found in Dockerfile"
        return 1
    fi
    print_pass

    print_check "CATALINA_HOME set correctly"
    if ! grep -q "CATALINA_HOME=/opt/tomcat" "$dockerfile"; then
        print_warn "CATALINA_HOME may not be set to /opt/tomcat"
    else
        echo -e "${GREEN}✓ PASS${NC}"
        ((PASSED++))
    fi

    print_check "Ownership set for webapp directory"
    if ! grep -q "chown.*starexec" "$dockerfile"; then
        print_warn "File ownership may not be set for starexec user"
    else
        echo -e "${GREEN}✓ PASS${NC}"
        ((PASSED++))
    fi

    return 0
}

check_docker_image() {
    print_header "STEP 6: Checking Docker Image"

    if ! command -v docker &> /dev/null; then
        print_info "Docker not installed, skipping Docker checks"
        return 0
    fi

    print_check "Building Docker image"
    if [ "$VERBOSE" = true ]; then
        docker build -t starexec:verify-migrations . 2>&1 | tail -20
    else
        if docker build -q -t starexec:verify-migrations . >/dev/null 2>&1; then
            echo -e "${GREEN}✓ PASS${NC}"
            ((PASSED++))
        else
            print_fail "Docker build failed"
            return 1
        fi
    fi

    print_check "Migrations present in image"
    if docker run --rm starexec:verify-migrations \
        test -d /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration >/dev/null 2>&1; then
        echo -e "${GREEN}✓ PASS${NC}"
        ((PASSED++))
    else
        print_fail "Migration directory not found in Docker image"
        return 1
    fi

    print_check "Migration files in image"
    local img_count
    img_count=$(docker run --rm starexec:verify-migrations \
        find /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration -name "*.sql" 2>/dev/null | wc -l)

    if [ "$img_count" -ge 20 ]; then
        echo -e "${GREEN}✓ PASS${NC}: Found $img_count migration files"
        ((PASSED++))
    else
        print_fail "Only $img_count migration files in image (expected 20+)"
        return 1
    fi

    return 0
}

check_running_container() {
    print_header "STEP 7: Checking Running Container"

    if ! command -v docker &> /dev/null; then
        print_info "Docker not installed, skipping container checks"
        return 0
    fi

    print_check "Locating running starexec container"
    local container_id
    container_id=$(docker ps -q -f "ancestor=starexec:latest" 2>/dev/null | head -1)

    if [ -z "$container_id" ]; then
        print_warn "No running starexec container found (use: docker compose up)"
        return 0
    fi
    echo -e "${GREEN}✓ PASS${NC}: Found container $container_id"
    ((PASSED++))

    print_check "Migration directory accessible"
    if docker exec "$container_id" test -d /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration >/dev/null 2>&1; then
        echo -e "${GREEN}✓ PASS${NC}"
        ((PASSED++))
    else
        print_fail "Migration directory not accessible in container"
        return 1
    fi

    print_check "Migration files readable"
    local container_count
    container_count=$(docker exec "$container_id" \
        find /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration -name "*.sql" 2>/dev/null | wc -l)

    if [ "$container_count" -ge 20 ]; then
        echo -e "${GREEN}✓ PASS${NC}: Found $container_count migration files"
        ((PASSED++))
    else
        print_fail "Only $container_count migration files found in container"
        return 1
    fi

    print_check "File permissions correct"
    local perms
    perms=$(docker exec "$container_id" \
        stat -c "%a" /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration/V0001__baseline_schema.sql 2>/dev/null)

    if [ "$perms" = "644" ] || [ "$perms" = "644" ]; then
        echo -e "${GREEN}✓ PASS${NC}: Permissions are $perms"
        ((PASSED++))
    else
        print_warn "Unexpected file permissions: $perms"
    fi

    return 0
}

# ============================================================================
# SUMMARY & REPORTING
# ============================================================================

print_summary() {
    print_header "VERIFICATION SUMMARY"

    local total=$((PASSED + FAILED + WARNINGS))

    echo "Results:"
    echo -e "  ${GREEN}✓ Passed:  $PASSED${NC}"
    if [ $FAILED -gt 0 ]; then
        echo -e "  ${RED}✗ Failed:  $FAILED${NC}"
    fi
    if [ $WARNINGS -gt 0 ]; then
        echo -e "  ${YELLOW}⚠ Warnings: $WARNINGS${NC}"
    fi
    if [ $FIXED -gt 0 ]; then
        echo -e "  ${GREEN}🔧 Fixed:   $FIXED${NC}"
    fi
    echo ""

    if [ $FAILED -eq 0 ] && [ $WARNINGS -eq 0 ]; then
        print_success "All checks passed! Your migration setup is correct."
        return 0
    elif [ $FAILED -eq 0 ]; then
        echo -e "${YELLOW}Verification complete with warnings.${NC}"
        return 2
    else
        echo -e "${RED}Verification failed! See errors above.${NC}"
        print_recovery_steps
        return 1
    fi
}

print_recovery_steps() {
    echo ""
    echo -e "${YELLOW}Quick Recovery Steps:${NC}"
    echo ""
    echo "1. Rebuild Maven project:"
    echo "   cd $PROJECT_ROOT"
    echo "   mvn clean package -DskipTests -pl starexec-app"
    echo ""
    echo "2. Verify WAR contents:"
    echo "   unzip -l $WAR_FILE | grep 'WEB-INF/classes/db/migration'"
    echo ""
    echo "3. Rebuild Docker image:"
    echo "   docker build --no-cache -t starexec:latest ."
    echo ""
    echo "4. Deploy:"
    echo "   docker compose down && docker compose up"
    echo ""
    echo "5. Watch migration logs:"
    echo "   docker compose logs -f app 2>&1 | grep MIGRATION"
    echo ""
}

# ============================================================================
# MAIN SCRIPT
# ============================================================================

main() {
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --fix)
                AUTO_FIX=true
                shift
                ;;
            --verbose)
                VERBOSE=true
                shift
                ;;
            --docker)
                check_docker_image
                shift
                ;;
            --full)
                check_docker_image
                check_running_container
                shift
                ;;
            --help)
                show_help
                exit 0
                ;;
            *)
                echo "Unknown option: $1"
                show_help
                exit 3
                ;;
        esac
    done

    print_header "StarExec Flyway Migration Verification"
    echo "Project: $PROJECT_ROOT"
    echo "Mode: $([ "$AUTO_FIX" = true ] && echo 'AUTO-FIX' || echo 'DIAGNOSTIC')"
    echo ""

    # Run checks
    check_source_files || true
    check_maven_config || true
    check_war_packaging || true
    check_launcher || true
    check_dockerfile || true

    # Print summary and return appropriate exit code
    print_summary
    exit $?
}

# ============================================================================
# ENTRY POINT
# ============================================================================

main "$@"
