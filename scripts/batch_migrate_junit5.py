#!/usr/bin/env python3
"""
Batch JUnit 5 Migration Script

Automatically migrates multiple JUnit 4 test files to JUnit 5 (Jupiter).

Usage:
    python3 batch_migrate_junit5.py [--dir DIR] [--pattern PATTERN] [--dry-run] [--verbose]

Options:
    --dir DIR       Target directory (default: starexec-app/src/test/java)
    --pattern PAT   File pattern to match (default: *Tests.java)
    --dry-run       Preview changes without modifying files
    --verbose       Show detailed debug information
"""

import argparse
import os
import sys
from pathlib import Path
from migrate_junit5 import migrate_file, log_info, log_success, log_warning, log_error


def find_test_files(directory: str, pattern: str) -> list:
    """Find all test files matching the pattern."""
    test_files = []
    base_path = Path(directory)
    
    if not base_path.exists():
        log_error(f"Directory not found: {directory}")
        return []
    
    # Convert glob pattern to pathlib format
    for file_path in base_path.rglob(pattern):
        if file_path.is_file() and file_path.suffix == ".java":
            test_files.append(str(file_path))
    
    return sorted(test_files)


def batch_migrate(directory: str, pattern: str, dry_run: bool = False, verbose: bool = False):
    """Migrate multiple test files."""
    
    log_info("Starting batch JUnit 5 migration")
    log_info(f"Target directory: {directory}")
    log_info(f"File pattern: {pattern}")
    
    if dry_run:
        log_warning("DRY RUN MODE - No changes will be made")
    
    print()
    
    # Find test files
    test_files = find_test_files(directory, pattern)
    
    if not test_files:
        log_warning("No test files found matching pattern")
        return
    
    log_info(f"Found {len(test_files)} test files matching pattern: {pattern}")
    print()
    
    # Migrate each file
    successful = 0
    skipped = 0
    failed = 0
    
    for test_file in test_files:
        # Check if already migrated
        try:
            with open(test_file, "r", encoding="utf-8") as f:
                content = f.read()
                if "import org.junit.jupiter.api" in content:
                    print(f"Skipping {os.path.basename(test_file)} (already migrated)")
                    skipped += 1
                    continue
        except:
            pass
        
        print(f"Migrating {os.path.basename(test_file)}...", end=" ", flush=True)
        
        if migrate_file(test_file, dry_run=dry_run, verbose=verbose):
            print(f"✓")
            successful += 1
        else:
            print(f"✗")
            failed += 1
    
    print()
    print("=" * 80)
    log_info("Batch migration complete")
    print("=" * 80)
    print()
    
    print(f"Results:")
    print(f"  Total files found: {len(test_files)}")
    print(f"  Successfully migrated: {successful}")
    print(f"  Skipped (already migrated): {skipped}")
    print(f"  Failed: {failed}")
    print()
    
    if dry_run:
        log_warning("Dry run mode: No changes were applied")
        print()
        log_info("To apply changes, run without --dry-run:")
        print(f"  python3 batch_migrate_junit5.py --dir {directory} --pattern '{pattern}'")
    elif successful > 0:
        log_success(f"Migrated {successful} test files successfully")
        print()
        log_info("Next steps:")
        print("  1. Verify changes: git diff")
        print("  2. Run tests: mvn clean test")
        print("  3. Commit changes: git add -A && git commit -m 'Migrate tests to JUnit 5'")


def main():
    parser = argparse.ArgumentParser(
        description="Batch migrate JUnit 4 tests to JUnit 5",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python3 batch_migrate_junit5.py
  python3 batch_migrate_junit5.py --dir starexec-app/src/test/java/org/starexec/test/junit
  python3 batch_migrate_junit5.py --dry-run --verbose
  python3 batch_migrate_junit5.py --pattern "*Tests.java"
        """,
    )
    
    parser.add_argument(
        "--dir",
        default="starexec-app/src/test/java",
        help="Target directory (default: starexec-app/src/test/java)",
    )
    parser.add_argument(
        "--pattern",
        default="*Tests.java",
        help="File pattern to match (default: *Tests.java)",
    )
    parser.add_argument(
        "--dry-run", action="store_true", help="Preview changes without modifying"
    )
    parser.add_argument(
        "--verbose", action="store_true", help="Show detailed debug information"
    )
    
    args = parser.parse_args()
    
    batch_migrate(args.dir, args.pattern, dry_run=args.dry_run, verbose=args.verbose)


if __name__ == "__main__":
    main()
