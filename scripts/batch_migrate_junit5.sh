#!/bin/bash

###############################################################################
# Batch JUnit 5 Migration Script
#
# This script automates the migration of multiple test files from JUnit 4 to JUnit 5.
# It runs the migration script on all test files matching a pattern or in a directory.
#
# Usage:
#   ./scripts/batch_migrate_junit5.sh [options]
#
# Options:
#   --dir <directory>     Migrate all test files in directory (default: starexec-app/src/test/java)
#   --pattern <pattern>   File pattern to match (default: *.java)
#   --dry-run             Preview changes without applying them
#   --verbose             Show detailed debug information
#   --skip-backup         Don't create backups (NOT recommended)
#   --help                Show this help message
#
# Examples:
#   ./scripts/batch_migrate_junit5.sh
#   ./scripts/batch_migrate_junit5.sh --dir starexec-app/src/test/java/org/starexec/test/junit
#   ./scripts/batch_migrate_junit5.sh --dry-run --verbose
#   ./scripts/batch_migrate_junit5.sh --pattern "*Tests.java"
###############################################################################

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
TEST_DIR="${TEST_DIR:-starexec-app/src/test/java}"
PATTERN="${PATTERN:-*Tests.java}"
DRY_RUN=${DRY_RUN:-false}
VERBOSE=${VERBOSE:-false}
SKIP_BACKUP=${SKIP_BACKUP:-false}
MIGRATION_SCRIPT="./scripts/migrate_junit5.sh"

# Counters
TOTAL_FILES=0
SUCCESSFUL_MIGRATIONS=0
FAILED_MIGRATIONS=0
SKIPPED_FILES=0

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
    cat << EOF
Batch JUnit 5 Migration Script

Usage: $0 [options]

Options:
  --dir <directory>     Migrate all test files in directory
                       (default: starexec-app/src/test/java)
  --pattern <pattern>   File pattern to match (default: *Tests.java)
  --dry-run             Preview changes without applying them
  --verbose             Show detailed debug information
  --skip-backup         Don't create backups (NOT recommended)
  --help                Show this help message

Examples:
  $0
  $0 --dir starexec-app/src/test/java/org/starexec/test/junit
  $0 --dry-run --verbose
  $0 --pattern "*Tests.java"

EOF
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --dir)
            TEST_DIR="$2"
            shift 2
            ;;
        --pattern)
            PATTERN="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --verbose)
            VERBOSE=true
            shift
            ;;
        --skip-backup)
            SKIP_BACKUP=true
            shift
            ;;
        --help)
            show_usage
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            show_usage
            exit 1
            ;;
    esac
done

# Validate inputs
if [ ! -d "$TEST_DIR" ]; then
    log_error "Directory not found: $TEST_DIR"
    exit 1
fi

if [ ! -f "$MIGRATION_SCRIPT" ]; then
    log_error "Migration script not found: $MIGRATION_SCRIPT"
    exit 1
fi

log_info "Starting batch JUnit 5 migration"
log_info "Target directory: $TEST_DIR"
log_info "File pattern: $PATTERN"

if [ "$DRY_RUN" = true ]; then
    log_warning "DRY RUN MODE - No changes will be made"
fi

echo ""

# Create migration log
MIGRATION_LOG="/tmp/junit5_migration_$(date +%s).log"
verbose_log "Migration log: $MIGRATION_LOG"

# Find all matching test files
TOTAL_FILES=$(find "$TEST_DIR" -name "$PATTERN" -type f | wc -l)
log_info "Found $TOTAL_FILES test files matching pattern: $PATTERN"
echo ""

if [ "$TOTAL_FILES" -eq 0 ]; then
    log_warning "No files found matching pattern"
    exit 0
fi

# Process each file
while IFS= read -r test_file; do
    # Skip files that have already been migrated
    if grep -q "import org.junit.jupiter.api" "$test_file"; then
        verbose_log "Skipping already migrated file: $test_file"
        ((SKIPPED_FILES++))
        continue
    fi

    # Skip files that don't have JUnit 4 imports
    if ! grep -q "import org.junit.Test\|import org.junit.Assert\|import org.junit.Before\|import org.junit.After" "$test_file"; then
        verbose_log "Skipping non-JUnit file: $test_file"
        ((SKIPPED_FILES++))
        continue
    fi

    echo -n "Migrating $(basename "$test_file")... "

    # Set environment variables for the migration script
    export DRY_RUN
    export VERBOSE
    export SKIP_BACKUP

    # Run the migration script
    if "$MIGRATION_SCRIPT" "$test_file" >> "$MIGRATION_LOG" 2>&1; then
        log_success "✓"
        ((SUCCESSFUL_MIGRATIONS++))
    else
        log_error "✗"
        ((FAILED_MIGRATIONS++))
        # Continue with next file even if one fails
    fi
done < <(find "$TEST_DIR" -name "$PATTERN" -type f | sort)

echo ""
echo "==============================================================================="
log_info "Batch migration complete"
echo "==============================================================================="
echo ""
echo "Results:"
echo "  Total files found: $TOTAL_FILES"
echo "  Successfully migrated: $SUCCESSFUL_MIGRATIONS"
echo "  Skipped (already migrated or not JUnit): $SKIPPED_FILES"
echo "  Failed: $FAILED_MIGRATIONS"
echo ""

if [ "$FAILED_MIGRATIONS" -gt 0 ]; then
    log_warning "Some migrations failed. See log for details:"
    log_warning "  $MIGRATION_LOG"
    echo ""
fi

if [ "$DRY_RUN" = true ]; then
    log_warning "Dry run mode: No changes were applied"
    echo ""
    log_info "To apply changes, run without --dry-run:"
    echo "  $0 --dir $TEST_DIR --pattern '$PATTERN'"
    echo ""
fi

# Next steps
if [ "$SUCCESSFUL_MIGRATIONS" -gt 0 ] && [ "$DRY_RUN" = false ]; then
    echo "Next steps:"
    echo "  1. Run tests to verify migrations: mvn test"
    echo "  2. Review migration log if needed: cat $MIGRATION_LOG"
    echo "  3. Commit changes to git: git add -A && git commit -m 'Migrate to JUnit 5'"
    echo ""
fi

# Exit with appropriate code
if [ "$FAILED_MIGRATIONS" -gt 0 ]; then
    exit 1
fi

exit 0
