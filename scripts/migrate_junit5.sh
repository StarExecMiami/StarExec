#!/bin/bash

###############################################################################
# JUnit 5 Migration Script
#
# This script automates the migration of test files from JUnit 4 to JUnit 5.
# It performs the following transformations:
#
# 1. Updates import statements
# 2. Replaces annotation names (@Before -> @BeforeEach, etc.)
# 3. Updates assertion imports
# 4. Makes test methods package-private (optional)
#
# Usage:
#   ./scripts/migrate_junit5.sh <test_file_path>
#   ./scripts/migrate_junit5.sh starexec-app/src/test/java/org/starexec/test/junit/UtilTests.java
#
# To dry-run without changes:
#   DRY_RUN=true ./scripts/migrate_junit5.sh <test_file_path>
###############################################################################

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
DRY_RUN=${DRY_RUN:-false}
VERBOSE=${VERBOSE:-false}

# Counters
CHANGES_MADE=0

# Helper functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

verbose_log() {
    if [ "$VERBOSE" = true ]; then
        echo -e "${BLUE}[DEBUG]${NC} $1"
    fi
}

show_usage() {
    echo "Usage: $0 <test_file_path>"
    echo ""
    echo "Examples:"
    echo "  $0 starexec-app/src/test/java/org/starexec/test/junit/UtilTests.java"
    echo "  DRY_RUN=true $0 starexec-app/src/test/java/org/starexec/test/junit/UtilTests.java"
    echo ""
    echo "Environment Variables:"
    echo "  DRY_RUN=true    - Preview changes without modifying the file"
    echo "  VERBOSE=true    - Show detailed debug information"
}

# Validate input
if [ $# -eq 0 ]; then
    log_error "No file specified"
    show_usage
    exit 1
fi

TEST_FILE="$1"

if [ ! -f "$TEST_FILE" ]; then
    log_error "File not found: $TEST_FILE"
    exit 1
fi

if [ ! -f "$TEST_FILE" ] || [[ ! "$TEST_FILE" =~ \.java$ ]]; then
    log_error "File must be a Java file: $TEST_FILE"
    exit 1
fi

log_info "Starting JUnit 5 migration for: $TEST_FILE"
if [ "$DRY_RUN" = true ]; then
    log_warning "DRY RUN MODE - No changes will be made"
fi

# Create backup
BACKUP_FILE="${TEST_FILE}.backup"
cp "$TEST_FILE" "$BACKUP_FILE"
verbose_log "Created backup: $BACKUP_FILE"

# Create temporary file for modifications
TEMP_FILE="${TEST_FILE}.tmp"
cp "$TEST_FILE" "$TEMP_FILE"

###############################################################################
# Migration Functions
###############################################################################

migrate_import_statements() {
    verbose_log "Migrating import statements..."

    # Replace org.junit.Assert imports
    if grep -q "import org.junit.Assert" "$TEMP_FILE"; then
        sed -i 's/import org\.junit\.Assert;/import static org.junit.jupiter.api.Assertions.*;/' "$TEMP_FILE"
        sed -i 's/import org\.junit\.Assert\.\*;/import static org.junit.jupiter.api.Assertions.*;/' "$TEMP_FILE"
        verbose_log "Updated Assert imports"
        ((CHANGES_MADE++))
    fi

    # Replace org.junit.Test imports
    if grep -q "import org.junit.Test;" "$TEMP_FILE"; then
        sed -i 's/import org\.junit\.Test;/import org.junit.jupiter.api.Test;/' "$TEMP_FILE"
        verbose_log "Updated @Test imports"
        ((CHANGES_MADE++))
    fi

    # Replace org.junit.Before imports
    if grep -q "import org.junit.Before;" "$TEMP_FILE"; then
        sed -i 's/import org\.junit\.Before;/import org.junit.jupiter.api.BeforeEach;/' "$TEMP_FILE"
        verbose_log "Updated @Before imports"
        ((CHANGES_MADE++))
    fi

    # Replace org.junit.After imports
    if grep -q "import org.junit.After;" "$TEMP_FILE"; then
        sed -i 's/import org\.junit\.After;/import org.junit.jupiter.api.AfterEach;/' "$TEMP_FILE"
        verbose_log "Updated @After imports"
        ((CHANGES_MADE++))
    fi

    # Replace org.junit.BeforeClass imports
    if grep -q "import org.junit.BeforeClass;" "$TEMP_FILE"; then
        sed -i 's/import org\.junit\.BeforeClass;/import org.junit.jupiter.api.BeforeAll;/' "$TEMP_FILE"
        verbose_log "Updated @BeforeClass imports"
        ((CHANGES_MADE++))
    fi

    # Replace org.junit.AfterClass imports
    if grep -q "import org.junit.AfterClass;" "$TEMP_FILE"; then
        sed -i 's/import org\.junit\.AfterClass;/import org.junit.jupiter.api.AfterAll;/' "$TEMP_FILE"
        verbose_log "Updated @AfterClass imports"
        ((CHANGES_MADE++))
    fi

    # Replace org.junit.Ignore imports
    if grep -q "import org.junit.Ignore;" "$TEMP_FILE"; then
        sed -i 's/import org\.junit\.Ignore;/import org.junit.jupiter.api.Disabled;/' "$TEMP_FILE"
        verbose_log "Updated @Ignore imports"
        ((CHANGES_MADE++))
    fi
}

migrate_annotations() {
    verbose_log "Migrating annotations..."

    # Replace @Before with @BeforeEach
    if grep -q "@Before" "$TEMP_FILE"; then
        sed -i 's/@Before/@BeforeEach/g' "$TEMP_FILE"
        verbose_log "Updated @Before -> @BeforeEach"
        ((CHANGES_MADE++))
    fi

    # Replace @After with @AfterEach
    if grep -q "@After" "$TEMP_FILE"; then
        sed -i 's/@After/@AfterEach/g' "$TEMP_FILE"
        verbose_log "Updated @After -> @AfterEach"
        ((CHANGES_MADE++))
    fi

    # Replace @BeforeClass with @BeforeAll
    if grep -q "@BeforeClass" "$TEMP_FILE"; then
        sed -i 's/@BeforeClass/@BeforeAll/g' "$TEMP_FILE"
        verbose_log "Updated @BeforeClass -> @BeforeAll"
        ((CHANGES_MADE++))
    fi

    # Replace @AfterClass with @AfterAll
    if grep -q "@AfterClass" "$TEMP_FILE"; then
        sed -i 's/@AfterClass/@AfterAll/g' "$TEMP_FILE"
        verbose_log "Updated @AfterClass -> @AfterAll"
        ((CHANGES_MADE++))
    fi

    # Replace @Ignore with @Disabled
    if grep -q "@Ignore" "$TEMP_FILE"; then
        sed -i 's/@Ignore/@Disabled/g' "$TEMP_FILE"
        verbose_log "Updated @Ignore -> @Disabled"
        ((CHANGES_MADE++))
    fi
}

migrate_assertions() {
    verbose_log "Migrating assertion calls..."

    # Update Assert.assertEquals to assertEquals
    if grep -q "Assert\.assertEquals" "$TEMP_FILE"; then
        sed -i 's/Assert\.assertEquals/assertEquals/g' "$TEMP_FILE"
        verbose_log "Updated Assert.assertEquals references"
        ((CHANGES_MADE++))
    fi

    # Update Assert.assertTrue to assertTrue
    if grep -q "Assert\.assertTrue" "$TEMP_FILE"; then
        sed -i 's/Assert\.assertTrue/assertTrue/g' "$TEMP_FILE"
        verbose_log "Updated Assert.assertTrue references"
        ((CHANGES_MADE++))
    fi

    # Update Assert.assertFalse to assertFalse
    if grep -q "Assert\.assertFalse" "$TEMP_FILE"; then
        sed -i 's/Assert\.assertFalse/assertFalse/g' "$TEMP_FILE"
        verbose_log "Updated Assert.assertFalse references"
        ((CHANGES_MADE++))
    fi

    # Update Assert.assertNull to assertNull
    if grep -q "Assert\.assertNull" "$TEMP_FILE"; then
        sed -i 's/Assert\.assertNull/assertNull/g' "$TEMP_FILE"
        verbose_log "Updated Assert.assertNull references"
        ((CHANGES_MADE++))
    fi

    # Update Assert.assertNotNull to assertNotNull
    if grep -q "Assert\.assertNotNull" "$TEMP_FILE"; then
        sed -i 's/Assert\.assertNotNull/assertNotNull/g' "$TEMP_FILE"
        verbose_log "Updated Assert.assertNotNull references"
        ((CHANGES_MADE++))
    fi

    # Update Assert.fail to fail
    if grep -q "Assert\.fail" "$TEMP_FILE"; then
        sed -i 's/Assert\.fail/fail/g' "$TEMP_FILE"
        verbose_log "Updated Assert.fail references"
        ((CHANGES_MADE++))
    fi
}

show_diff() {
    echo ""
    log_info "Showing changes:"
    echo ""
    diff -u "$BACKUP_FILE" "$TEMP_FILE" || true
    echo ""
}

###############################################################################
# Main Execution
###############################################################################

# Run migrations
migrate_import_statements
migrate_annotations
migrate_assertions

# Show diff
show_diff

if [ "$CHANGES_MADE" -eq 0 ]; then
    log_warning "No changes were made. File may already be migrated or not a JUnit test."
else
    log_info "Total changes made: $CHANGES_MADE"
fi

# Apply or discard changes
if [ "$DRY_RUN" = true ]; then
    log_warning "Dry run mode: No changes applied"
    rm "$TEMP_FILE"
    rm "$BACKUP_FILE"
    exit 0
fi

# Apply changes
mv "$TEMP_FILE" "$TEST_FILE"
log_success "Migration complete: $TEST_FILE"
log_info "Backup saved: $BACKUP_FILE"

echo ""
log_info "Next steps:"
echo "  1. Review the changes above"
echo "  2. Run the tests: mvn test -Dtest=$(basename "${TEST_FILE%.java}")"
echo "  3. If tests pass, delete the backup: rm $BACKUP_FILE"
echo "  4. If tests fail, restore from backup: mv $BACKUP_FILE $TEST_FILE"
